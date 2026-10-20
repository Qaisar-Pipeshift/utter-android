package io.github.qaisarpipeshift.utter.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsTest {

    private val today = LocalDate.of(2026, 10, 20)

    @Test
    fun `totals sum every day`() {
        val totals = Stats.totals(
            listOf(
                DayStats(today, takes = 2, words = 30, speechMs = 12_000),
                DayStats(today.minusDays(1), takes = 1, words = 10, speechMs = 5_000),
            ),
        )
        assertEquals(3, totals.takes)
        assertEquals(40, totals.words)
        assertEquals(17_000L, totals.speechMs)
        assertEquals(43_000L, totals.timeSavedMs)
    }

    @Test
    fun `time saved is typing time minus speech time`() {
        assertEquals(43_000L, Stats.timeSavedMs(40, 17_000))
        assertEquals(60_000L, Stats.timeSavedMs(80, 60_000))
    }

    @Test
    fun `time saved never goes negative`() {
        assertEquals(0L, Stats.timeSavedMs(1, 60_000))
        assertEquals(0L, Stats.timeSavedMs(0, 0))
    }

    @Test
    fun `series fills empty days oldest first and ends today`() {
        val summary = Stats.summary(
            days = listOf(DayStats(today.minusDays(1), takes = 1, words = 5)),
            today = today,
        )

        assertEquals(7, summary.series.size)
        assertEquals(today.minusDays(6), summary.series.first().date)
        assertEquals(today, summary.series.last().date)
        assertEquals(1, summary.series[5].takes)
        assertEquals(0, summary.series[6].takes)
        assertEquals(0, summary.today.takes)
        assertEquals(1, summary.total.takes)
    }

    @Test
    fun `top apps are summed across days and limited`() {
        val summary = Stats.summary(
            days = listOf(
                DayStats(today, pkgCounts = mapOf("a" to 2, "b" to 1)),
                DayStats(today.minusDays(1), pkgCounts = mapOf("b" to 3, "c" to 1, "d" to 1)),
            ),
            today = today,
        )

        assertEquals(
            listOf(Stats.AppCount("b", 4), Stats.AppCount("a", 2), Stats.AppCount("c", 1)),
            summary.topApps,
        )
    }
}
