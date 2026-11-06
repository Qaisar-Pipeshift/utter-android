package io.github.qaisarpipeshift.utter.core

import org.json.JSONObject

/**
 * The eight Pulse record types on the wire (docs/spec.md §18.2); the server's `/v1/pulse` accepts exactly these.
 */
object PulseTypes {
    const val USAGE_EVENT = "usage_event"
    const val USAGE_DAILY = "usage_daily"
    const val NET_DAILY = "net_daily"
    const val NOTIF_POSTED = "notif_posted"
    const val NOTIF_REMOVED = "notif_removed"
    const val LISTENER_GAP = "listener_gap"
    const val DND = "dnd"
    const val STORE_DROPPED = "store_dropped"
}

/**
 * Builds the common `{id, type, device, at}` frame every Pulse record carries (docs/spec.md §18.2).
 *
 * Why an injected generator instead of `UUID.randomUUID()` inline: tests pin ids and times, and the device tag is
 * read once at graph build time, so every collector stamps records identically.
 */
class PulseIds(
    private val device: String,
    private val newId: () -> String,
    private val clock: () -> Long,
) {
    /** A fresh record frame; collectors add their payload fields to the returned object. */
    fun base(type: String, at: Long = clock()): JSONObject =
        JSONObject().put("id", newId()).put("type", type).put("device", device).put("at", at)
}

/**
 * Usage event type numbers → stable names (docs/spec.md §18.1).
 *
 * Literal ints on purpose: `core` stays Android-free, and these values are frozen framework constants (public
 * `UsageEvents.Event` constants, compileSdk 37). Event types Android emits without a public constant — e.g. the
 * notification-seen and chooser events — arrive as raw ints and are recorded as `event_<n>`: every event is kept,
 * nothing is filtered, only the naming falls back.
 */
object PulseEventNames {

    /** Standby-bucket events are the one type whose payload the sampler must recognise; int per the map above. */
    const val STANDBY_BUCKET_CHANGED = 11

    val NAMES: Map<Int, String> = mapOf(
        1 to "ACTIVITY_RESUMED",
        2 to "ACTIVITY_PAUSED",
        5 to "CONFIGURATION_CHANGE",
        7 to "USER_INTERACTION",
        8 to "SHORTCUT_INVOCATION",
        11 to "STANDBY_BUCKET_CHANGED",
        15 to "SCREEN_INTERACTIVE",
        16 to "SCREEN_NON_INTERACTIVE",
        17 to "KEYGUARD_SHOWN",
        18 to "KEYGUARD_HIDDEN",
        19 to "FOREGROUND_SERVICE_START",
        20 to "FOREGROUND_SERVICE_STOP",
        23 to "ACTIVITY_STOPPED",
        26 to "DEVICE_SHUTDOWN",
        27 to "DEVICE_STARTUP",
    )

    fun nameOf(eventType: Int): String = NAMES[eventType] ?: "event_$eventType"
}

/** Interruption-filter numbers → names, same literal-int rule as [PulseEventNames] (`NotificationManager`). */
object DndFilters {
    val NAMES: Map<Int, String> = mapOf(
        0 to "UNKNOWN",
        1 to "ALL",
        2 to "PRIORITY",
        3 to "NONE",
        4 to "ALARMS",
    )

    fun nameOf(filter: Int): String = NAMES[filter] ?: "filter_$filter"
}

/** Pure record builders for the collector payloads; notification extraction lives on the Android edge. */
object PulseRecords {

    /**
     * One usage event. [bucket] is only meaningful for standby-bucket changes and is omitted otherwise.
     */
    fun usageEvent(
        ids: PulseIds,
        at: Long,
        eventType: Int,
        pkg: String,
        className: String?,
        bucket: Int,
    ): JSONObject = ids.base(PulseTypes.USAGE_EVENT, at)
        .put("event", PulseEventNames.nameOf(eventType))
        .put("eventType", eventType)
        .put("pkg", pkg)
        .put("class", className ?: JSONObject.NULL)
        .putIfPositive("bucket", bucket)

    /** Per-app foreground/visible time and last use for one closed calendar [day] (`YYYY-MM-DD`). */
    fun usageDaily(
        ids: PulseIds,
        day: String,
        pkg: String,
        foregroundMs: Long,
        visibleMs: Long,
        lastUsedAt: Long,
    ): JSONObject = ids.base(PulseTypes.USAGE_DAILY)
        .put("day", day)
        .put("pkg", pkg)
        .put("foregroundMs", foregroundMs)
        .put("visibleMs", visibleMs)
        .put("lastUsedAt", lastUsedAt)

    /** Per-UID bytes for one closed calendar [day]; mobile values omitted when the device has no SIM traffic. */
    fun netDaily(
        ids: PulseIds,
        day: String,
        uid: Int,
        wifiTx: Long,
        wifiRx: Long,
        mobileTx: Long,
        mobileRx: Long,
    ): JSONObject = ids.base(PulseTypes.NET_DAILY)
        .put("day", day)
        .put("uid", uid)
        .put("wifiTx", wifiTx)
        .put("wifiRx", wifiRx)
        .put("mobileTx", mobileTx)
        .put("mobileRx", mobileRx)

    /** Listener connected/disconnected; [reason] is only set by the system on some disconnects. */
    fun listenerGap(ids: PulseIds, connected: Boolean, reason: Int?): JSONObject =
        ids.base(PulseTypes.LISTENER_GAP)
            .put("connected", connected)
            .put("reason", reason ?: JSONObject.NULL)

    /** Do-not-disturb switched to [filter]. */
    fun dnd(ids: PulseIds, filter: Int): JSONObject = ids.base(PulseTypes.DND)
        .put("filter", filter)
        .put("filterName", DndFilters.nameOf(filter))

    /** Written by the store itself when the cap forced a batch out (docs/spec.md §18.2). */
    fun storeDropped(ids: PulseIds, files: Int, bytes: Long): JSONObject =
        ids.base(PulseTypes.STORE_DROPPED)
            .put("files", files)
            .put("bytes", bytes)

    /**
     * Android 15 rewrites the copy a listener receives of a 2FA notification to the app label plus this text
     * (docs/spec.md §18.1); Utter stores the record as received and marks it. Verified on device (D15) because the
     * framework keeps the replacement string private.
     */
    const val REDACTED_TEXT = "Sensitive notification content hidden"

    fun isRedacted(text: String?): Boolean = text != null && text.equals(REDACTED_TEXT, ignoreCase = true)

    private fun JSONObject.putIfPositive(key: String, value: Int): JSONObject =
        if (value > 0) put(key, value) else this
}

/**
 * Closed calendar days still to backfill, as epoch days: `(lastDay, today)` clamped to a small window — Android
 * prunes its daily usage files after roughly ten days (docs/spec.md §18.1), so older days cannot be queried anyway.
 */
object PulseWindows {
    const val MAX_BACK_DAYS = 10L

    fun closedDays(lastDay: Long, todayEpochDay: Long, maxBackDays: Long = MAX_BACK_DAYS): LongRange {
        val from = maxOf(lastDay + 1, todayEpochDay - maxBackDays)
        return if (from >= todayEpochDay) LongRange.EMPTY else from until todayEpochDay
    }
}

/** One sampling pass: the records to append plus the per-source cursors valid only after they are written. */
data class PulseSample(
    val records: List<JSONObject>,
    val usageCursor: Long,
    val dailyDay: Long,
    val netDay: Long,
)

/** The engine's view of the Android sampler so the flush engine stays JVM-testable. */
fun interface PulseSampling {
    /** Reads Android's usage and network logs since the store's cursors. Never throws for a denied access. */
    suspend fun sample(): PulseSample
}
