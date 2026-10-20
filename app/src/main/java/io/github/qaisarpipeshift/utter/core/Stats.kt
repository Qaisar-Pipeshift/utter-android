package io.github.qaisarpipeshift.utter.core

import java.time.LocalDate
import kotlin.math.max

/** One day of dictation counters (docs/spec.md §15.4 insights, docs/plan.md Task 7). */
data class DayStats(
    val date: LocalDate,
    val takes: Int = 0,
    val words: Int = 0,
    val speechMs: Long = 0,
    val pkgCounts: Map<String, Int> = emptyMap(),
)

/**
 * Pure insights arithmetic over [DayStats] buckets (docs/plan.md Task 7).
 *
 * Why pure: the summary drives a chart and three numbers, and "time saved" has a definition worth testing — words at
 * a 40 wpm typing speed minus the time actually spent speaking, never below zero.
 */
object Stats {
    const val TYPING_WORDS_PER_MINUTE = 40

    /** Aggregated counters; [timeSavedMs] is derived, not stored. */
    data class Totals(val takes: Int, val words: Int, val speechMs: Long, val timeSavedMs: Long)

    data class AppCount(val pkg: String, val takes: Int)

    /** Everything the Insights screen needs in one immutable value. */
    data class Summary(
        val total: Totals,
        val today: Totals,
        val series: List<DayStats>,
        val topApps: List<AppCount>,
    )

    fun totals(days: Collection<DayStats>): Totals {
        var takes = 0
        var words = 0
        var speechMs = 0L
        for (day in days) {
            takes += day.takes
            words += day.words
            speechMs += day.speechMs
        }
        return Totals(takes, words, speechMs, timeSavedMs(words, speechMs))
    }

    /** Time typing [words] at [TYPING_WORDS_PER_MINUTE] minus [speechMs] spent dictating; never negative. */
    fun timeSavedMs(words: Int, speechMs: Long): Long =
        max(0L, words * 60_000L / TYPING_WORDS_PER_MINUTE - speechMs)

    /**
     * Builds the screen's summary: totals over everything, today alone, the last [seriesDays] days oldest-first with
     * empty days filled in (so the chart has no gaps), and the [topApps] packages by take count.
     */
    fun summary(
        days: Collection<DayStats>,
        today: LocalDate,
        seriesDays: Int = 7,
        topApps: Int = 3,
    ): Summary {
        val byDate = days.associateBy { it.date }
        val series = (seriesDays - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            byDate[date] ?: DayStats(date)
        }
        val counts = HashMap<String, Int>()
        for (day in days) {
            for ((pkg, count) in day.pkgCounts) {
                counts[pkg] = (counts[pkg] ?: 0) + count
            }
        }
        val apps = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(topApps)
            .map { AppCount(it.key, it.value) }
        return Summary(
            total = totals(days),
            today = totals(listOf(byDate[today] ?: DayStats(today))),
            series = series,
            topApps = apps,
        )
    }
}
