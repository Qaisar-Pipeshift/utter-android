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
import io.github.qaisarpipeshift.utter.core.PulseEventNames
import io.github.qaisarpipeshift.utter.core.PulseIds
import io.github.qaisarpipeshift.utter.core.PulseRecords
import io.github.qaisarpipeshift.utter.core.PulseSample
import io.github.qaisarpipeshift.utter.core.PulseSampling
import io.github.qaisarpipeshift.utter.core.PulseWindows
import io.github.qaisarpipeshift.utter.data.PulseStore
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
@RequiresApi(35)
class PulseSampler(
    context: Context,
    private val store: PulseStore,
    private val ids: PulseIds,
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

    /** Every event in `(cursor, now]`, nothing filtered; the cursor moves to [now] even for an empty batch. */
    private fun usageEvents(cursor: Long, now: Long): Pair<List<JSONObject>, Long> {
        if (now <= cursor) return emptyList<JSONObject>() to cursor
        val begin = if (cursor <= 0) now - LOOKBACK_MS else cursor
        val usm = appContext.getSystemService(UsageStatsManager::class.java)
            ?: return emptyList<JSONObject>() to cursor
        val events = try {
            if (Build.VERSION.SDK_INT >= 35) {
                usm.queryEvents(UsageEventsQuery.Builder(begin, now).build())
            } else {
                usm.queryEvents(begin, now)
            }
        } catch (_: SecurityException) {
            return emptyList<JSONObject>() to cursor // usage access not granted yet
        }
        if (events == null) return emptyList<JSONObject>() to cursor // device locked (docs note)
        val out = ArrayList<JSONObject>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(event)) break
            val type = event.eventType
            val bucket = if (type == PulseEventNames.STANDBY_BUCKET_CHANGED) event.appStandbyBucket else 0
            out.add(
                PulseRecords.usageEvent(ids, event.timeStamp, type, event.packageName, event.className, bucket),
            )
        }
        return out to now
    }

    /** Per-app foreground/visible time for the closed days not yet emitted. */
    private fun dailyUsage(lastDay: Long, todayEpochDay: Long, zone: ZoneId): Pair<List<JSONObject>, Long> {
        val days = PulseWindows.closedDays(lastDay, todayEpochDay)
        if (days.isEmpty()) return emptyList<JSONObject>() to lastDay
        val usm = appContext.getSystemService(UsageStatsManager::class.java)
            ?: return emptyList<JSONObject>() to lastDay
        val out = ArrayList<JSONObject>()
        var done = lastDay
        for (day in days) {
            val start = dayStart(day, zone)
            val stats = try {
                usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, dayStart(day + 1, zone))
            } catch (_: SecurityException) {
                return out to done
            }
            if (stats == null) return out to done // device locked: the day stays open for the next sample
            for (stat in stats) {
                out.add(
                    PulseRecords.usageDaily(
                        ids,
                        LocalDate.ofEpochDay(day).toString(),
                        stat.packageName,
                        stat.totalTimeInForeground,
                        stat.totalTimeVisible,
                        stat.lastTimeUsed,
                    ),
                )
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
            val mobile = querySummary(nsm, ConnectivityManager.TYPE_MOBILE, start, end) ?: return out to done
            val totals = HashMap<Int, LongArray>()
            for ((index, stats) in listOf(wifi to 0, mobile to 2)) {
                stats.use {
                    val bucket = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        if (!stats.getNextBucket(bucket)) break
                        val per = totals.getOrPut(bucket.uid) { LongArray(4) }
                        per[index] += bucket.txBytes
                        per[index + 1] += bucket.rxBytes
                    }
                }
            }
            for ((uid, bytes) in totals) {
                out.add(PulseRecords.netDaily(ids, LocalDate.ofEpochDay(day).toString(), uid, bytes[0], bytes[1], bytes[2], bytes[3]))
            }
            done = day
        }
        return out to done
    }

    private fun querySummary(nsm: NetworkStatsManager, type: Int, start: Long, end: Long): NetworkStats? = try {
        nsm.querySummary(type, null, start, end) // null subscriberId = all networks, no telephony state needed
    } catch (_: SecurityException) {
        null
    }

    private fun dayStart(day: Long, zone: ZoneId): Long =
        LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()

    private companion object {
        /** Android keeps ~10 days of usage history; older cursors are clamped to this window (spec §18.1). */
        const val LOOKBACK_MS = 10L * 24 * 60 * 60 * 1000
    }
}
