package io.github.qaisarpipeshift.utter.core

import java.time.LocalDate

/**
 * One day of resource counters (v0.1.4; docs/spec.md §15.5).
 *
 * Why CPU is split: the claim to prove is "near-zero battery", so the CPU spent while the keyboard is hidden — the
 * idle drain — is a separate bucket from the CPU spent while the panel is on screen. Mic and traffic are the other
 * two costs a dictation has; take counts say how many of those costs produced text.
 */
data class ResourceDay(
    val date: LocalDate,
    val cpuVisibleMs: Long = 0L,
    val cpuHiddenMs: Long = 0L,
    val micMs: Long = 0L,
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
    val takes: Int = 0,
    val liveTakes: Int = 0,
    val uploadTakes: Int = 0,
)

/** One raw counter reading. [cpuMs] is process CPU since start; the traffic counters are per-uid since boot. */
data class ResourceReading(val cpuMs: Long, val txBytes: Long, val rxBytes: Long)

/** Which transport a finished take used (docs/spec.md §6): the live socket or the upload fallback. */
enum class TakeTransport { LIVE, UPLOAD }

/** The only moments the ledger reads counters (docs/spec.md §15.5, v0.1.4). No timer, no polling. */
enum class LedgerEvent { SHOW, HIDE, TAKE_END, DESTROY, ACTIVITY_STOP }

/**
 * Pure resource arithmetic over [ResourceDay] buckets (v0.1.4).
 *
 * Why pure and Android-free: deltas, restarts, day rollover and pruning are the parts that can be wrong silently,
 * so they run in JVM tests. The energy numbers are an estimate from documented typical constants — never a
 * measurement — and are labelled as such wherever they are shown.
 */
object ResourceMath {
    /** Keep 30 days, like the insights counters. */
    const val RETENTION_DAYS = 30

    /**
     * CPU at ~1.5 W on a 3.85 V battery: 1.5 Wh per CPU-hour / 3.85 V ≈ 0.39 Ah = 390 mAh per CPU-hour, so
     * 390 / 3,600,000 ms ≈ 1.08e-4 mAh per CPU-ms. A mid-range core's active power, not a benchmark peak.
     */
    const val CPU_MAH_PER_MS = 1.08e-4

    /** The 16 kHz mic path plus its DSP at ~20 mW: ≈ 5.2 mA = 1.44e-6 mAh per ms of open microphone. */
    const val MIC_MAH_PER_MS = 1.44e-6

    /** Radio with tail energy at ~14 µJ per byte on a 3.85 V battery: ≈ 1.0e-6 mAh per byte. */
    const val NET_MAH_PER_BYTE = 1.0e-6

    /**
     * Delta since the previous reading. A lower counter means the process (CPU) or the phone (traffic) restarted,
     * so the current value is the whole delta and counts from zero.
     */
    fun delta(current: Long, previous: Long): Long {
        val now = current.coerceAtLeast(0L)
        return if (now >= previous) now - previous else now
    }

    /**
     * Folds one checkpoint into [day]. A delta is hidden when the panel was not visible over the interval; a take
     * end also adds its microphone time and its transport.
     */
    fun add(
        day: ResourceDay,
        cpuMs: Long,
        txBytes: Long,
        rxBytes: Long,
        hidden: Boolean,
        micMs: Long = 0L,
        transport: TakeTransport? = null,
    ): ResourceDay = day.copy(
        cpuVisibleMs = day.cpuVisibleMs + if (hidden) 0L else cpuMs.coerceAtLeast(0L),
        cpuHiddenMs = day.cpuHiddenMs + if (hidden) cpuMs.coerceAtLeast(0L) else 0L,
        micMs = day.micMs + micMs.coerceAtLeast(0L),
        txBytes = day.txBytes + txBytes.coerceAtLeast(0L),
        rxBytes = day.rxBytes + rxBytes.coerceAtLeast(0L),
        takes = day.takes + if (transport != null) 1 else 0,
        liveTakes = day.liveTakes + if (transport == TakeTransport.LIVE) 1 else 0,
        uploadTakes = day.uploadTakes + if (transport == TakeTransport.UPLOAD) 1 else 0,
    )

    /** Adds two buckets; the result keeps [a]'s date. */
    fun merge(a: ResourceDay, b: ResourceDay): ResourceDay = ResourceDay(
        date = a.date,
        cpuVisibleMs = a.cpuVisibleMs + b.cpuVisibleMs,
        cpuHiddenMs = a.cpuHiddenMs + b.cpuHiddenMs,
        micMs = a.micMs + b.micMs,
        txBytes = a.txBytes + b.txBytes,
        rxBytes = a.rxBytes + b.rxBytes,
        takes = a.takes + b.takes,
        liveTakes = a.liveTakes + b.liveTakes,
        uploadTakes = a.uploadTakes + b.uploadTakes,
    )

    /** The estimated battery use of a bucket, in mAh. Labelled "estimate" wherever it is shown. */
    fun estimateMah(day: ResourceDay): Double = estimateMah(
        cpuMs = day.cpuVisibleMs + day.cpuHiddenMs,
        micMs = day.micMs,
        bytes = day.txBytes + day.rxBytes,
    )

    /** The estimated battery use of CPU, microphone and bytes, in mAh. */
    fun estimateMah(cpuMs: Long, micMs: Long, bytes: Long): Double =
        cpuMs.coerceAtLeast(0L) * CPU_MAH_PER_MS +
            micMs.coerceAtLeast(0L) * MIC_MAH_PER_MS +
            bytes.coerceAtLeast(0L) * NET_MAH_PER_BYTE

    /** The hidden-CPU share of [day]'s estimate, the segment the chart highlights. */
    fun hiddenCpuMah(day: ResourceDay): Double = day.cpuHiddenMs.coerceAtLeast(0L) * CPU_MAH_PER_MS

    /** A fixed-length series ending at [today], oldest first, with empty days filled in so the chart has no gaps. */
    fun series(days: Collection<ResourceDay>, today: LocalDate, count: Int = 7): List<ResourceDay> {
        val byDate = days.associateBy { it.date }
        return (count - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            byDate[date] ?: ResourceDay(date)
        }
    }

    /** Drops buckets outside the retention window: [today] and the [retentionDays] - 1 days before it. */
    fun prune(
        days: Collection<ResourceDay>,
        today: LocalDate,
        retentionDays: Int = RETENTION_DAYS,
    ): Map<LocalDate, ResourceDay> {
        val oldest = today.minusDays((retentionDays - 1).toLong())
        return days
            .filter { !it.date.isBefore(oldest) && !it.date.isAfter(today) }
            .associateBy { it.date }
    }

    /** Sums [days] into one bucket dated [date], for the "all stored days" line. */
    fun total(days: Collection<ResourceDay>, date: LocalDate): ResourceDay =
        days.fold(ResourceDay(date)) { sum, day -> merge(sum, day) }
}
