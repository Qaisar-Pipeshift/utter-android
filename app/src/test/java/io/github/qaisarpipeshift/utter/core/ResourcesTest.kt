package io.github.qaisarpipeshift.utter.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ledger's arithmetic: deltas, restarts, rollover, pruning and the estimate (v0.1.4). */
class ResourcesTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 21)

    @Test
    fun `delta counts forward progress`() {
        assertEquals(400L, ResourceMath.delta(current = 1_000, previous = 600))
    }

    @Test
    fun `delta treats a lower counter as a restart and counts from zero`() {
        assertEquals(250L, ResourceMath.delta(current = 250, previous = 900))
    }

    @Test
    fun `delta never goes negative`() {
        assertEquals(0L, ResourceMath.delta(current = -1, previous = 500))
    }

    @Test
    fun `checkpoint splits cpu into visible and hidden`() {
        val visible = ResourceMath.add(
            day = ResourceDay(today),
            cpuMs = 100,
            txBytes = 10,
            rxBytes = 20,
            hidden = false,
        )
        assertEquals(100L, visible.cpuVisibleMs)
        assertEquals(0L, visible.cpuHiddenMs)

        val hidden = ResourceMath.add(
            day = visible,
            cpuMs = 50,
            txBytes = 0,
            rxBytes = 0,
            hidden = true,
        )
        assertEquals(100L, hidden.cpuVisibleMs)
        assertEquals(50L, hidden.cpuHiddenMs)
    }

    @Test
    fun `a take end counts one take and its transport`() {
        val live = ResourceMath.add(
            day = ResourceDay(today),
            cpuMs = 0,
            txBytes = 0,
            rxBytes = 0,
            hidden = false,
            micMs = 1_500,
            transport = TakeTransport.LIVE,
        )
        assertEquals(1, live.takes)
        assertEquals(1, live.liveTakes)
        assertEquals(0, live.uploadTakes)
        assertEquals(1_500L, live.micMs)

        val upload = ResourceMath.add(
            day = live,
            cpuMs = 0,
            txBytes = 0,
            rxBytes = 0,
            hidden = false,
            micMs = 500,
            transport = TakeTransport.UPLOAD,
        )
        assertEquals(2, upload.takes)
        assertEquals(1, upload.liveTakes)
        assertEquals(1, upload.uploadTakes)
        assertEquals(2_000L, upload.micMs)
    }

    @Test
    fun `series fills empty days and ends at today`() {
        val older = ResourceDay(today.minusDays(2), cpuVisibleMs = 5)
        val series = ResourceMath.series(listOf(older), today, count = 7)

        assertEquals(7, series.size)
        assertEquals(today.minusDays(6), series.first().date)
        assertEquals(today, series.last().date)
        assertEquals(5L, series[4].cpuVisibleMs)
        assertEquals(0L, series.last().cpuVisibleMs)
    }

    @Test
    fun `prune keeps today and the twenty-nine days before it`() {
        val kept = (0..29).map { ResourceDay(today.minusDays(it.toLong()), takes = 1) }
        val old = ResourceDay(today.minusDays(30), takes = 1)

        val pruned = ResourceMath.prune(kept + old, today)

        assertEquals(30, pruned.size)
        assertTrue(pruned.containsKey(today))
        assertTrue(pruned.containsKey(today.minusDays(29)))
        assertFalse(pruned.containsKey(today.minusDays(30)))
    }

    @Test
    fun `merge sums every counter on the left date`() {
        val left = ResourceDay(today, cpuVisibleMs = 1, cpuHiddenMs = 2, micMs = 3, txBytes = 4, rxBytes = 5, takes = 1)
        val right = ResourceDay(today.minusDays(1), cpuVisibleMs = 10, cpuHiddenMs = 20, micMs = 30, txBytes = 40, rxBytes = 50, takes = 2)

        val merged = ResourceMath.merge(left, right)

        assertEquals(today, merged.date)
        assertEquals(11L, merged.cpuVisibleMs)
        assertEquals(22L, merged.cpuHiddenMs)
        assertEquals(33L, merged.micMs)
        assertEquals(44L, merged.txBytes)
        assertEquals(55L, merged.rxBytes)
        assertEquals(3, merged.takes)
    }

    @Test
    fun `estimate uses the documented constants`() {
        val day = ResourceDay(
            date = today,
            cpuVisibleMs = 1_000,
            cpuHiddenMs = 1_000,
            micMs = 1_000,
            txBytes = 500,
            rxBytes = 500,
        )

        val expected = 2_000.0 * ResourceMath.CPU_MAH_PER_MS +
            1_000.0 * ResourceMath.MIC_MAH_PER_MS +
            1_000.0 * ResourceMath.NET_MAH_PER_BYTE

        assertEquals(expected, ResourceMath.estimateMah(day), 1e-12)
        assertEquals(1_000.0 * ResourceMath.CPU_MAH_PER_MS, ResourceMath.hiddenCpuMah(day), 1e-15)
        assertEquals(ResourceMath.estimateMah(day), ResourceMath.estimateMah(2_000, 1_000, 1_000), 1e-12)
    }

    @Test
    fun `total sums every stored day`() {
        val days = listOf(
            ResourceDay(today, cpuVisibleMs = 10, takes = 1),
            ResourceDay(today.minusDays(1), cpuVisibleMs = 20, takes = 2),
        )

        val total = ResourceMath.total(days, today)

        assertEquals(30L, total.cpuVisibleMs)
        assertEquals(3, total.takes)
        assertEquals(today, total.date)
    }
}
