package io.github.qaisarpipeshift.utter.data

import io.github.qaisarpipeshift.utter.core.LedgerEvent
import io.github.qaisarpipeshift.utter.core.ResourceDay
import io.github.qaisarpipeshift.utter.core.ResourceMath
import io.github.qaisarpipeshift.utter.core.ResourceReading
import io.github.qaisarpipeshift.utter.core.TakeTransport
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The persistent resource ledger (v0.1.4; docs/spec.md §15.5).
 *
 * Why deltas live in memory: Utter must never wake the disk for its own bookkeeping. Every checkpoint — panel shown,
 * panel hidden, take end, IME destroy, settings activity stopped — only reads the process CPU and the per-uid traffic
 * counters and folds the delta into a day bucket under one lock. Disk is touched at three moments only: the end of a
 * take (which already writes history), [io] shutdown inside `UtterIme.onDestroy`, and the Diagnostics screen opening.
 * There is no timer, no polling coroutine and no background wake-up.
 *
 * Restart safety: the CPU counter is per process, so a ledger built by a new process starts its baseline at zero and
 * the first checkpoint charges the CPU spent since that process started. Traffic counters are per uid since boot, so
 * the first checkpoint after a process start only records the baseline — the boot's bytes are never charged to one
 * day. A counter that ever moves backwards is treated as a restart and counted from zero.
 */
class ResourceLedger(
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> LocalDate = { LocalDate.now() },
    private val reading: () -> ResourceReading,
) {
    /** Guards the in-memory baseline and [pending]; never held across a suspension or a disk write. */
    private val lock = Any()

    /** Serialises the three disk paths; reads and writes of the one file never interleave. */
    private val writeMutex = Mutex()

    private val file = File(dir, FILE_NAME)

    private var lastCpuMs = 0L
    private var lastTxBytes = 0L
    private var lastRxBytes = 0L
    private var trafficSeen = false
    private var visible = false

    /** Deltas since the last disk write, oldest day first. */
    private val pending = LinkedHashMap<LocalDate, ResourceDay>()

    /**
     * Reads the counters and folds the delta into [event]'s day. Memory only: this is safe on the main thread, runs
     * once per checkpoint and never dispatches work.
     */
    fun checkpoint(event: LedgerEvent, micMs: Long = 0L, transport: TakeTransport? = null) {
        val now = reading()
        val date = clock()
        synchronized(lock) {
            val cpu = ResourceMath.delta(now.cpuMs, lastCpuMs)
            lastCpuMs = now.cpuMs.coerceAtLeast(0L)
            val tx: Long
            val rx: Long
            if (trafficSeen) {
                tx = ResourceMath.delta(now.txBytes, lastTxBytes)
                rx = ResourceMath.delta(now.rxBytes, lastRxBytes)
            } else {
                // Per-uid counters include everything since boot; a new process only records its baseline.
                trafficSeen = true
                tx = 0L
                rx = 0L
            }
            lastTxBytes = now.txBytes.coerceAtLeast(0L)
            lastRxBytes = now.rxBytes.coerceAtLeast(0L)

            val current = pending[date]
            val worthWriting = cpu > 0L || tx > 0L || rx > 0L || micMs > 0L || transport != null
            if (current != null || worthWriting) {
                pending[date] = ResourceMath.add(
                    day = current ?: ResourceDay(date),
                    cpuMs = cpu,
                    txBytes = tx,
                    rxBytes = rx,
                    hidden = !visible,
                    micMs = micMs,
                    transport = transport,
                )
            }
            when (event) {
                LedgerEvent.SHOW -> visible = true
                LedgerEvent.HIDE, LedgerEvent.DESTROY -> visible = false
                LedgerEvent.TAKE_END, LedgerEvent.ACTIVITY_STOP -> Unit
            }
        }
    }

    /** One finished take: counts it, then makes the in-memory delta durable — this is the hot-path write. */
    suspend fun takeEnded(micMs: Long, live: Boolean) {
        checkpoint(
            event = LedgerEvent.TAKE_END,
            micMs = micMs,
            transport = if (live) TakeTransport.LIVE else TakeTransport.UPLOAD,
        )
        persist()
    }

    /** Writes any in-memory delta. A no-op when nothing was recorded since the last write, so no disk is touched. */
    suspend fun persist() {
        if (synchronized(lock) { pending.isEmpty() }) return
        withContext(io) {
            writeMutex.withLock { flush() }
        }
    }

    /** The stored days plus any in-memory delta, written out first — the Diagnostics screen opens here. */
    suspend fun snapshot(): List<ResourceDay> = withContext(io) {
        writeMutex.withLock { flush() }
    }

    /**
     * Must run under [writeMutex]: take the pending deltas out of memory, merge them into the file, prune, write, and
     * put them back only if the write failed — a failed rename must never lose counters.
     */
    private fun flush(): List<ResourceDay> {
        val inflight = synchronized(lock) {
            val copy = LinkedHashMap(pending)
            pending.clear()
            copy
        }
        val stored = load()
        val merged = LinkedHashMap(stored)
        for ((date, day) in inflight) {
            merged[date] = merged[date]?.let { ResourceMath.merge(it, day) } ?: day
        }
        val pruned = ResourceMath.prune(merged.values, clock())
        if (inflight.isNotEmpty() && !write(pruned.values)) {
            synchronized(lock) {
                for ((date, day) in inflight) {
                    pending[date] = pending[date]?.let { ResourceMath.merge(it, day) } ?: day
                }
            }
        }
        return pruned.values.sortedBy { it.date }
    }

    private fun load(): Map<LocalDate, ResourceDay> {
        if (!file.exists()) return emptyMap()
        val text = try {
            file.readText()
        } catch (_: IOException) {
            return emptyMap()
        }
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return emptyMap()
        }
        val days = root.optJSONObject("days") ?: return emptyMap()
        val out = HashMap<LocalDate, ResourceDay>()
        val keys = days.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val date = try {
                LocalDate.parse(key)
            } catch (_: DateTimeParseException) {
                continue
            }
            val item = days.optJSONObject(key) ?: continue
            out[date] = ResourceDay(
                date = date,
                cpuVisibleMs = item.optLong("cpuVisibleMs", 0L),
                cpuHiddenMs = item.optLong("cpuHiddenMs", 0L),
                micMs = item.optLong("micMs", 0L),
                txBytes = item.optLong("txBytes", 0L),
                rxBytes = item.optLong("rxBytes", 0L),
                takes = item.optInt("takes", 0),
                liveTakes = item.optInt("liveTakes", 0),
                uploadTakes = item.optInt("uploadTakes", 0),
            )
        }
        return out
    }

    private fun write(days: Collection<ResourceDay>): Boolean {
        val root = JSONObject()
        val stored = JSONObject()
        for (day in days) {
            stored.put(
                day.date.toString(),
                JSONObject()
                    .put("cpuVisibleMs", day.cpuVisibleMs)
                    .put("cpuHiddenMs", day.cpuHiddenMs)
                    .put("micMs", day.micMs)
                    .put("txBytes", day.txBytes)
                    .put("rxBytes", day.rxBytes)
                    .put("takes", day.takes)
                    .put("liveTakes", day.liveTakes)
                    .put("uploadTakes", day.uploadTakes),
            )
        }
        root.put("days", stored)
        return try {
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (tmp.renameTo(file)) {
                true
            } else {
                file.delete()
                tmp.renameTo(file) || run {
                    tmp.delete()
                    false
                }
            }
        } catch (_: IOException) {
            false
        }
    }

    private companion object {
        const val FILE_NAME = "resources.json"
    }
}
