package io.github.qaisarpipeshift.utter.pulse

import android.os.Build
import io.github.qaisarpipeshift.utter.core.PulseSampling
import io.github.qaisarpipeshift.utter.data.PulseMeta
import io.github.qaisarpipeshift.utter.data.PulseStore
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.diag.Diag
import io.github.qaisarpipeshift.utter.net.Api
import io.github.qaisarpipeshift.utter.net.PulsePost
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The flush engine (docs/spec.md §18.2): whenever one of the four triggers fires — notification received, take
 * finished, app opened, listener connected — it samples Android's usage log if the throttle allows and pushes
 * closed batches oldest first. No alarms, no jobs, no wakeups: the triggers are the only schedule.
 *
 * The [Mutex] serialises triggers so a notification burst and a take finishing never push concurrently; every disk
 * and network step dispatches to IO inside [PulseStore], [Api.postPulse] and the sampler, so callers may stay on
 * their main-thread scopes. All failures keep the batches — the next trigger retries, and the server's id dedupe
 * makes those retries free.
 */
class PulseEngine(
    private val store: PulseStore,
    private val sampler: PulseSampling,
    private val api: Api,
    private val settings: () -> Settings,
    private val unmetered: () -> Boolean,
    private val diag: Diag,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sdkInt: () -> Int = { Build.VERSION.SDK_INT },
) {
    private val mutex = Mutex()

    /** One opportunistic flush for [trigger]; returns quickly when Pulse is off or the platform is older than 15. */
    suspend fun onTrigger(trigger: String) {
        if (!settings().pulseEnabled) return
        if (sdkInt() < 35) return
        mutex.withLock {
            onTriggerLocked(trigger)
        }
    }

    private suspend fun onTriggerLocked(trigger: String) {
        val started = clock()
        sampleIfDue(settings())
        pushIfDue(settings(), trigger, store.meta())
        diag.event("pulse_trigger", "$trigger ${clock() - started}ms")
    }

    private suspend fun sampleIfDue(current: Settings) {
        val meta = store.meta()
        if (clock() - meta.lastSampleAt < current.pulseMinIntervalMin * MILLIS_PER_MIN) return
        val sample = try {
            sampler.sample()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            diag.event("pulse_sample_failed", failure.javaClass.simpleName)
            store.saveMeta(meta.copy(lastSampleAt = clock()))
            return
        }
        // The cursors move only after the records are durably on disk (docs/spec.md §18.1).
        val written = store.append(sample.records)
        store.saveMeta(
            if (written) {
                meta.copy(lastSampleAt = clock(), usageCursor = sample.usageCursor, dailyDay = sample.dailyDay, netDay = sample.netDay)
            } else {
                meta.copy(lastSampleAt = clock())
            },
        )
        diag.event("pulse_sample", "${sample.records.size}")
    }

    private suspend fun pushIfDue(current: Settings, trigger: String, meta: PulseMeta) {
        if (trigger != TRIGGER_NOTIFICATION) store.closeBatch()
        if (current.pulseWifiOnly && !unmetered()) return
        // A failing server must not cost one request per notification: after a failed attempt the pushes
        // wait out the same interval the sampler does (docs/spec.md §18.2). A successful drain sets nothing,
        // so the next closed batch still goes immediately.
        if (meta.lastPushAt > 0 && clock() - meta.lastPushAt < current.pulseMinIntervalMin * MILLIS_PER_MIN) return
        for (batch in store.closedBatches()) {
            if (!pushBatch(batch)) {
                store.saveMeta(meta.copy(lastPushAt = clock()))
                return // oldest first: retry the rest on the next trigger
            }
        }
    }

    /** Pushes one batch, splitting on 413; true when every record left the file (which is then deleted). */
    private suspend fun pushBatch(batch: File): Boolean {
        val lines = store.readBatch(batch)
        if (lines.isEmpty()) {
            store.deleteBatch(batch)
            return true
        }
        var index = 0
        var limit = MAX_PUSH_RECORDS
        var accepted = 0
        var duplicates = 0
        while (index < lines.size) {
            val end = chunkEnd(lines, index, limit)
            val body = lines.subList(index, end).joinToString(separator = "\n").toByteArray(Charsets.UTF_8)
            when (val result = api.postPulse(body)) {
                is PulsePost.Ok -> {
                    accepted += result.accepted
                    duplicates += result.duplicates
                    index = end
                }

                is PulsePost.TooLarge -> {
                    if (end - index <= 1) {
                        // One record alone exceeds the wire cap (a huge MessagingStyle, say). Drop it with a
                        // store_dropped marker and stop here: the in-memory line indexes would drift from the
                        // rewritten file if we kept pushing. The next trigger re-reads the batch.
                        val dropped = store.dropRecord(batch, index)
                        if (dropped < 0L) {
                            diag.event("pulse_push_stuck", "$index")
                        } else {
                            diag.event("pulse_push_dropped", "$dropped")
                        }
                        return false
                    }
                    limit = (end - index) / 2 // split this range and retry it smaller
                }

                is PulsePost.Rejected -> {
                    diag.event("pulse_push_rejected", "${result.code}")
                    return false
                }

                is PulsePost.Network -> {
                    diag.event("pulse_push_offline", result.kind)
                    return false
                }
            }
        }
        store.deleteBatch(batch)
        diag.event("pulse_push", "$accepted/$duplicates")
        return true
    }

    /** The widest range from [from] that fits the wire caps: [limit] records and [MAX_PUSH_BYTES] pre-gzip. */
    private fun chunkEnd(lines: List<String>, from: Int, limit: Int): Int {
        var bytes = 0
        var end = from
        while (end < lines.size && end - from < limit) {
            val size = lines[end].toByteArray(Charsets.UTF_8).size + 1
            if (end > from && bytes + size > MAX_PUSH_BYTES) break
            bytes += size
            end++
        }
        return end
    }

    companion object {
        const val TRIGGER_NOTIFICATION = "notification"
        const val TRIGGER_TAKE_FINISHED = "take_finished"
        const val TRIGGER_APP_OPENED = "app_opened"
        const val TRIGGER_LISTENER_CONNECTED = "listener_connected"

        /** Wire caps (docs/spec.md §18.2): ≤ 512 records and ≤ 512 KiB pre-gzip per request. */
        const val MAX_PUSH_RECORDS = 512
        const val MAX_PUSH_BYTES = 512 * 1024
        const val MILLIS_PER_MIN = 60_000L
    }
}
