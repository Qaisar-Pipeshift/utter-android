package io.github.qaisarpipeshift.utter.pulse

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageEventsQuery
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.qaisarpipeshift.utter.core.PulseDaily
import io.github.qaisarpipeshift.utter.core.PulseEventNames
import io.github.qaisarpipeshift.utter.core.PulseIds
import io.github.qaisarpipeshift.utter.core.PulseRecords
import io.github.qaisarpipeshift.utter.core.PulseSample
import io.github.qaisarpipeshift.utter.core.PulseSampling
import io.github.qaisarpipeshift.utter.core.PulseWindows
import io.github.qaisarpipeshift.utter.data.PulseStore
import io.github.qaisarpipeshift.utter.diag.Diag
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Reads Android's own usage log for Pulse (docs/spec.md §18.1): every usage event since the cursor, per-app daily
 * foreground time, and per-UID Wi-Fi/mobile bytes per day. Requires the usage access the owner grants in Settings —
 * a denied access or a locked device yields an empty sample and keeps the cursor, never an error.
 *
 * Called at most every `pulseMinIntervalMin` (the engine's throttle) on the IO dispatcher: `querySummary` may take
 * seconds. Android 15 only — the engine and the listener never construct it below SDK 35, and the API-35
 * [UsageEventsQuery] form is used there.
 */
@RequiresApi(30)
class PulseSampler(
    context: Context,
    private val store: PulseStore,
    private val ids: PulseIds,
    private val diag: Diag? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : PulseSampling {

    private val appContext = context.applicationContext

    override suspend fun sample(): PulseSample = withContext(Dispatchers.IO) {
        val meta = store.meta()
        val now = clock()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()

        val (events, usageCursor) = usageEvents(meta.usageCursor, now)
        val (usageRecords, dailyDay) = dailyUsage(meta.dailyDay, today, zone)
        val (netRecords, netDay) = netDaily(meta.netDay, today, zone)

        PulseSample(events + usageRecords + netRecords, usageCursor, dailyDay, netDay)
    }

    /**
     * Every event in `(cursor, now]`, nothing filtered. The cursor moves to the last event seen (+1) and only
     * when events were returned: several OEM builds (One UI included) hold the current day's events in a buffer
     * that queryEvents does not expose until it flushes, so advancing to [now] on an empty result would skip
     * those events permanently. An idle log keeps the cursor where it is; the 10-day clamp bounds re-queries.
     */
    private fun usageEvents(cursor: Long, now: Long): Pair<List<JSONObject>, Long> {
        if (now <= cursor) return emptyList<JSONObject>() to cursor
        val begin = maxOf(cursor, now - LOOKBACK_MS)
        val usm = appContext.getSystemService(UsageStatsManager::class.java)
            ?: return emptyList<JSONObject>() to cursor
        val events = try {
            if (Build.VERSION.SDK_INT >= 35) {
                usm.queryEvents(UsageEventsQuery.Builder(begin, now).build())
            } else {
                usm.queryEvents(begin, now)
            }
        } catch (_: SecurityException) {
            // Silent deny cost a full debugging session once: it must be visible in Diagnostics.
            diag?.event("pulse_sample_denied", "usage_events")
            return emptyList<JSONObject>() to cursor // usage access not granted yet
        }
        if (events == null) return emptyList<JSONObject>() to cursor // device locked (docs note)
        val out = ArrayList<JSONObject>()
        val event = UsageEvents.Event()
        var last = -1L
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(event)) break
            val type = event.eventType
            val bucket = if (type == PulseEventNames.STANDBY_BUCKET_CHANGED) event.appStandbyBucket else 0
            out.add(
                PulseRecords.usageEvent(ids, event.timeStamp, type, event.packageName, event.className, bucket),
            )
            if (event.timeStamp > last) last = event.timeStamp
        }
        return out to if (last >= 0) last + 1 else cursor
    }

    /**
     * Per-app foreground/visible time for the closed days not yet emitted. A day is only reported when Android's
     * buckets for it are provably per-day and cover it whole ([PulseDaily.rollupDay]) — One UI answers stale
     * windows with frozen multi-day buckets, and labeling those with the requested day wrote one snapshot under
     * six day labels during the Nov 7 2026 backfill. A skipped day stays visible via Diagnostics and is retried
     * until the 10-day clamp drops it; its event timeline is unaffected.
     */
    private fun dailyUsage(lastDay: Long, todayEpochDay: Long, zone: ZoneId): Pair<List<JSONObject>, Long> {
        val days = PulseWindows.closedDays(lastDay, todayEpochDay)
        if (days.isEmpty()) return emptyList<JSONObject>() to lastDay
        val usm = appContext.getSystemService(UsageStatsManager::class.java)
            ?: return emptyList<JSONObject>() to lastDay
        val out = ArrayList<JSONObject>()
        var done = lastDay
        for (day in days) {
            val dayLabel = LocalDate.ofEpochDay(day).toString()
            val start = dayStart(day, zone)
            val end = dayStart(day + 1, zone)
            val stats = try {
                usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            } catch (_: SecurityException) {
                diag?.event("pulse_sample_denied", "usage_daily")
                return out to done
            }
            if (stats == null) return out to done // device locked: the day stays open for the next sample
            val buckets = ArrayList<PulseDaily.Stat>(stats.size)
            for (stat in stats) {
                // firstTimeStamp/lastTimeStamp are the bucket's own interval, returned whole (never clipped to
                // the query window) — exactly what the per-day proof needs.
                buckets.add(
                    PulseDaily.Stat(
                        stat.firstTimeStamp, stat.lastTimeStamp, stat.packageName,
                        stat.totalTimeInForeground, stat.totalTimeVisible, stat.lastTimeUsed,
                    ),
                )
            }
            val rollup = PulseDaily.rollupDay(buckets, start, end)
            if (rollup == null) {
                // Frozen or partly-flushed buckets: emitting them would mislabel days. Skip, show, retry later.
                diag?.event("pulse_daily_skipped", dayLabel)
            } else {
                for ((pkg, acc) in rollup) {
                    val record = PulseRecords.usageDaily(ids, dayLabel, pkg, acc[0], acc[1], acc[2])
                    // Stable per (day, pkg): the same aggregate re-observed on a later flush dedupes server-side
                    // instead of piling up as fresh ids, and a changed total only ever means a genuinely new value.
                    record.put("id", ids.stable("daily:$dayLabel:$pkg"))
                    out.add(record)
                }
            }
            done = day
        }
        return out to done
    }

    /** Per-UID Wi-Fi and mobile bytes for the closed days not yet emitted. */
    private fun netDaily(lastDay: Long, todayEpochDay: Long, zone: ZoneId): Pair<List<JSONObject>, Long> {
        val days = PulseWindows.closedDays(lastDay, todayEpochDay)
        if (days.isEmpty()) return emptyList<JSONObject>() to lastDay
        val nsm = appContext.getSystemService(NetworkStatsManager::class.java)
            ?: return emptyList<JSONObject>() to lastDay
        val out = ArrayList<JSONObject>()
        var done = lastDay
        for (day in days) {
            val start = dayStart(day, zone)
            val end = dayStart(day + 1, zone)
            val wifi = querySummary(nsm, ConnectivityManager.TYPE_WIFI, start, end) ?: return out to done
            // Both handles must close on every path: the mobile query can fail after wifi is already open
            // (denied appop on one transport, a RuntimeException — each leak is an fd in the IME process).
            val totals = HashMap<Int, LongArray>()
            try {
                accumulate(wifi, 0, totals)
                val mobile = querySummary(nsm, ConnectivityManager.TYPE_MOBILE, start, end) ?: return out to done
                try {
                    accumulate(mobile, 2, totals)
                } finally {
                    mobile.close()
                }
            } finally {
                wifi.close()
            }
            for ((uid, bytes) in totals) {
                out.add(PulseRecords.netDaily(ids, LocalDate.ofEpochDay(day).toString(), uid, bytes[0], bytes[1], bytes[2], bytes[3]))
            }
            done = day
        }
        return out to done
    }

    private fun accumulate(stats: NetworkStats, index: Int, totals: HashMap<Int, LongArray>) {
        val bucket = NetworkStats.Bucket()
        while (stats.hasNextBucket()) {
            if (!stats.getNextBucket(bucket)) break
            val per = totals.getOrPut(bucket.uid) { LongArray(4) }
            per[index] += bucket.txBytes
            per[index + 1] += bucket.rxBytes
        }
    }

    private fun querySummary(nsm: NetworkStatsManager, type: Int, start: Long, end: Long): NetworkStats? = try {
        nsm.querySummary(type, null, start, end) // null subscriberId = all networks, no telephony state needed
    } catch (_: SecurityException) {
        diag?.event("pulse_sample_denied", "net_daily")
        null
    }

    private fun dayStart(day: Long, zone: ZoneId): Long =
        LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()

    private companion object {
        /** Android keeps ~10 days of usage history; older cursors are clamped to this window (spec §18.1). */
        const val LOOKBACK_MS = 10L * 24 * 60 * 60 * 1000
    }
}
