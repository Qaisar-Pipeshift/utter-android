package io.github.qaisarpipeshift.utter.pulse

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.annotation.RequiresApi
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.UtterApp
import io.github.qaisarpipeshift.utter.core.PulseIds
import io.github.qaisarpipeshift.utter.core.PulseRecords
import io.github.qaisarpipeshift.utter.core.PulseTypes
import io.github.qaisarpipeshift.utter.data.PulseStore
import io.github.qaisarpipeshift.utter.diag.Diag
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The notification half of Pulse (docs/spec.md §18.1): the system binds this service once the owner grants
 * notification access in Settings, and every callback appends a record to [PulseStore] and fires the engine's
 * notification trigger. No polling, no wakeups — the callbacks are the schedule (spec §18.2).
 *
 * Inert below Android 15 and before any callback arrives: `onCreate` skips graph wiring entirely, so a user who
 * never grants the access (or runs below SDK 35) gets a bound but silent service. Android's OTP redaction is
 * honored as-is — the record keeps the rewritten copy and is marked `redacted` (docs/spec.md §18.1). The service
 * is strictly read-only: it never cancels, snoozes or acts on a notification, and never unbinds (docs/spec.md §18.5).
 *
 * Extraction never runs on the callback (main) thread and every notification is extracted under its own
 * `runCatching` inside a handler-equipped scope: one malformed third-party notification must cost one skipped
 * record, never the process — a crash here would rebind and re-crash on the same shade forever.
 */
class PulseListener : NotificationListenerService() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure ->
            Diag().event("pulse_listener_failed", failure.javaClass.simpleName)
        },
    )
    private var graph: Graph? = null
    private var diag: Diag? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT < 35) return
        graph = (application as? UtterApp)?.graph
        diag = graph?.diag
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val graph = graph ?: return
        diag?.event("pulse_connect")
        scope.launch {
            val store = graph.pulseStore
            store.append(listOf(PulseRecords.listenerGap(graph.pulseIds, connected = true, reason = null)))
            // The listener only sees notifications from now on; the ones already on the shade go out as a snapshot
            // with stable ids (PulseIds.stable) so a rebind re-observing the same shade dedupes server-side.
            val snapshot = activeNotifications.orEmpty().mapNotNull { sbn ->
                runCatching { PulseNotifs.posted(graph.pulseIds, sbn, null, snapshot = true) }
                    .onFailure { diag?.event("pulse_snapshot_failed", it.javaClass.simpleName) }
                    .getOrNull()
            }
            store.append(snapshot)
            graph.pulse.onTrigger(PulseEngine.TRIGGER_LISTENER_CONNECTED)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        val graph = graph ?: return
        scope.launch {
            // Extraction touches third-party parcelables: never trust it on the main thread, never let it throw.
            val record = runCatching { PulseNotifs.posted(graph.pulseIds, sbn, rankingOf(sbn, rankingMap)) }
                .onFailure { diag?.event("pulse_notif_failed", it.javaClass.simpleName) }
                .getOrNull() ?: return@launch
            graph.pulseStore.append(listOf(record))
            graph.pulse.onTrigger(PulseEngine.TRIGGER_NOTIFICATION)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        val graph = graph ?: return
        scope.launch {
            val record = runCatching { PulseNotifs.removed(graph.pulseIds, sbn, reason) }
                .onFailure { diag?.event("pulse_notif_failed", it.javaClass.simpleName) }
                .getOrNull() ?: return@launch
            graph.pulseStore.append(listOf(record))
            graph.pulse.onTrigger(PulseEngine.TRIGGER_NOTIFICATION)
        }
    }

    override fun onInterruptionFilterChanged(filter: Int) {
        val graph = graph ?: return
        scope.launch {
            graph.pulseStore.append(listOf(PulseRecords.dnd(graph.pulseIds, filter)))
            graph.pulse.onTrigger(PulseEngine.TRIGGER_NOTIFICATION)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        val graph = graph ?: return
        scope.launch {
            graph.pulseStore.append(
                listOf(PulseRecords.listenerGap(graph.pulseIds, connected = false, reason = null)),
            )
            // Nothing will fire this engine again until the system rebinds, and this trigger closes the open
            // batch, so the gap record leaves with the next push attempt (docs/spec.md §18.2).
            graph.pulse.onTrigger(PulseEngine.TRIGGER_LISTENER_DISCONNECTED)
        }
    }

    private fun rankingOf(sbn: StatusBarNotification, map: RankingMap): Ranking? {
        val ranking = Ranking()
        return if (map.getRanking(sbn.key, ranking)) ranking else null
    }
}

/**
 * Record extraction for one [StatusBarNotification] — the payload parts of `notif_posted` / `notif_removed`
 * (docs/spec.md §18.1): the text fields the listener copy carries, MessagingStyle messages, inbox lines, actions,
 * progress, ongoing, category, channel, group, and the ranking's importance/rank/DND-suppression when a
 * [RankingMap] provided it. Images are a boolean, never pixels.
 */
@RequiresApi(35)
internal object PulseNotifs {

    fun posted(ids: PulseIds, sbn: StatusBarNotification, ranking: Ranking?, snapshot: Boolean = false): JSONObject {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.charSequence(Notification.EXTRA_TITLE)
        val text = extras.charSequence(Notification.EXTRA_TEXT)
        val record = ids.base(PulseTypes.NOTIF_POSTED, at = sbn.postTime)
            .put("pkg", sbn.packageName)
            .put("uid", sbn.uid)
            .put("notifId", sbn.id)
            .put("key", sbn.key ?: JSONObject.NULL)
            .put("category", notification.category ?: JSONObject.NULL)
            .put("channelId", notification.channelId ?: JSONObject.NULL)
            .put("group", notification.group ?: JSONObject.NULL)
            .put("ongoing", sbn.isOngoing)
            .put("progress", extras.getInt(Notification.EXTRA_PROGRESS))
            .put("progressMax", extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            // org.json's put(key, null String) REMOVES the key; NULL keeps it so absent and null stay distinct
            // on the wire for every optional text field.
            .put("title", title ?: JSONObject.NULL)
            .put("text", text ?: JSONObject.NULL)
            .put("bigText", extras.charSequence(Notification.EXTRA_BIG_TEXT) ?: JSONObject.NULL)
            .put("subText", extras.charSequence(Notification.EXTRA_SUB_TEXT) ?: JSONObject.NULL)
            .put("infoText", extras.charSequence(Notification.EXTRA_INFO_TEXT) ?: JSONObject.NULL)
            .put("inbox", stringArray(extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)))
            .put("conversationTitle", style(notification)?.conversationTitle?.toString() ?: JSONObject.NULL)
            .put("messages", messages(notification))
            .put("actions", actions(notification))
            .put("image", hasImage(notification))
            .put("redacted", PulseRecords.isRedacted(text))
        if (snapshot) record.put("id", ids.stable("snap:${sbn.key}")).put("snapshot", true)
        if (ranking != null) {
            record.put("importance", ranking.importance)
            record.put("rank", ranking.rank)
            record.put("dndSuppressed", ranking.isSuppressedByDND)
        }
        return record
    }

    fun removed(ids: PulseIds, sbn: StatusBarNotification, reason: Int): JSONObject = ids
        .base(PulseTypes.NOTIF_REMOVED)
        .put("pkg", sbn.packageName)
        .put("notifId", sbn.id)
        .put("key", sbn.key ?: JSONObject.NULL)
        .put("postTime", sbn.postTime)
        .put("reason", reason)
        .put("reasonName", reasonName(reason))

    /** All public `REASON_*` codes by reflection, so a new Android release's codes are named without a code change. */
    fun reasonName(code: Int): String = reasons[code] ?: "reason_$code"

    private val reasons: Map<Int, String> by lazy { reasonMap() }

    internal fun reasonMap(): Map<Int, String> {
        val found = HashMap<Int, String>()
        for (field in NotificationListenerService::class.java.fields) {
            val static = java.lang.reflect.Modifier.isStatic(field.modifiers)
            if (static && field.type == Int::class.javaPrimitiveType && field.name.startsWith("REASON_")) {
                runCatching { found[field.getInt(null)] = field.name }
            }
        }
        return found
    }

    private fun style(notification: Notification): Notification.MessagingStyle? =
        notification.extras.getParcelable(Notification.EXTRA_STYLE, Notification.MessagingStyle::class.java)

    private fun messages(notification: Notification): JSONArray {
        val style = style(notification) ?: return JSONArray()
        val out = JSONArray()
        for (message in style.messages) {
            @Suppress("DEPRECATION")
            val sender = message.senderPerson?.name?.toString() ?: message.sender?.toString() ?: ""
            out.put(JSONObject().put("sender", sender).put("text", message.text?.toString() ?: "").put("time", message.timestamp))
        }
        return out
    }

    private fun actions(notification: Notification): JSONArray {
        val out = JSONArray()
        notification.actions?.forEach { action -> out.put(action.title?.toString() ?: "") }
        return out
    }

    private fun hasImage(notification: Notification): Boolean =
        notification.largeIcon != null ||
            notification.extras.containsKey(Notification.EXTRA_LARGE_ICON) ||
            notification.extras.containsKey(Notification.EXTRA_PICTURE)

    private fun stringArray(lines: Array<out CharSequence>?): JSONArray {
        val out = JSONArray()
        lines?.forEach { line -> out.put(line?.toString() ?: "") }
        return out
    }

    private fun Bundle.charSequence(key: String): String? = getCharSequence(key)?.toString()
}
