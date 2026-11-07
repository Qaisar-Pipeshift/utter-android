package io.github.qaisarpipeshift.utter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The daily-rollup honesty contract (docs/spec.md §18.2, born from the Nov 7 2026 One UI backfill): a day is only
 * reported from buckets that provably sit inside it and cover it whole — a frozen multi-day bucket must never be
 * labeled with the requested day, and a partly-flushed day must be skipped, never averaged.
 */
class PulseDailyTest {

    private val dayStart = 1_700_000_000_000L
    private val dayEnd = dayStart + 24 * 60 * 60 * 1000

    private fun stat(begin: Long, end: Long, pkg: String = "app", fg: Long = 60_000, visible: Long = 0, lastUsed: Long = end) =
        PulseDaily.Stat(begin, end, pkg, fg, visible, lastUsed)

    @Test
    fun `one full-day bucket rolls up per app`() {
        val rollup = PulseDaily.rollupDay(listOf(stat(dayStart, dayEnd, fg = 120_000)), dayStart, dayEnd)
        assertEquals(listOf(120_000L, 0L, dayEnd), rollup?.get("app")?.toList())
    }

    @Test
    fun `partial buckets of one day merge into per-app totals`() {
        val rollup = PulseDaily.rollupDay(
            listOf(
                stat(dayStart, dayStart + 3_600_000, fg = 100_000, lastUsed = dayStart + 3_500_000),
                stat(dayStart + 3_600_000, dayEnd, fg = 50_000, lastUsed = dayEnd - 1_000),
            ),
            dayStart, dayEnd,
        )
        assertEquals(listOf(150_000L, 0L, dayEnd - 1_000), rollup?.get("app")?.toList())
    }

    @Test
    fun `a bucket poking out of the day is a frozen multi-day bucket and blocks the day`() {
        assertNull(PulseDaily.rollupDay(listOf(stat(dayStart - 3_000, dayEnd, fg = 999_999)), dayStart, dayEnd))
        assertNull(PulseDaily.rollupDay(listOf(stat(dayStart, dayEnd + 3_000, fg = 999_999)), dayStart, dayEnd))
    }

    @Test
    fun `a partly covered day is skipped rather than averaged`() {
        assertNull(PulseDaily.rollupDay(listOf(stat(dayStart + 3_600_000, dayEnd)), dayStart, dayEnd))
    }

    @Test
    fun `an empty or zero-span day is skipped`() {
        assertNull(PulseDaily.rollupDay(emptyList(), dayStart, dayEnd))
        assertNull(PulseDaily.rollupDay(listOf(stat(dayStart, dayStart)), dayStart, dayEnd))
    }

    @Test
    fun `same app across buckets sums foreground and keeps the latest use`() {
        val rollup = PulseDaily.rollupDay(
            listOf(
                stat(dayStart, dayStart + 100, pkg = "a", fg = 10, lastUsed = 5),
                stat(dayStart + 100, dayEnd, pkg = "a", fg = 20, lastUsed = 3),
                stat(dayStart, dayEnd, pkg = "b", fg = 1),
            ),
            dayStart, dayEnd,
        )
        assertEquals(30L, rollup?.get("a")?.get(0))
        assertEquals(5L, rollup?.get("a")?.get(2))
        assertEquals(1L, rollup?.get("b")?.get(0))
    }
}
