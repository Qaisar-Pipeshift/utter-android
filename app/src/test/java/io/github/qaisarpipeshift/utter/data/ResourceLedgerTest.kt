package io.github.qaisarpipeshift.utter.data

import io.github.qaisarpipeshift.utter.core.LedgerEvent
import io.github.qaisarpipeshift.utter.core.ResourceReading
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The ledger's storage paths: in-memory deltas, the three writes, rollover and pruning (v0.1.4). */
class ResourceLedgerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var dir: File
    private var day: LocalDate = LocalDate.of(2026, 10, 20)
    private var cpuMs = 0L
    private var txBytes = 0L
    private var rxBytes = 0L
    private lateinit var ledger: ResourceLedger

    @Before
    fun setUp() {
        dir = folder.newFolder("resources")
        ledger = newLedger()
    }

    private fun newLedger(): ResourceLedger = ResourceLedger(
        dir = dir,
        clock = { day },
        reading = { ResourceReading(cpuMs = cpuMs, txBytes = txBytes, rxBytes = rxBytes) },
    )

    @Test
    fun `show and hide split the cpu delta into hidden and visible`() = runBlocking {
        ledger.checkpoint(LedgerEvent.SHOW)
        cpuMs = 300
        ledger.checkpoint(LedgerEvent.HIDE)
        cpuMs = 500
        ledger.checkpoint(LedgerEvent.SHOW)

        val stored = ledger.snapshot().single()

        assertEquals(300L, stored.cpuVisibleMs)
        assertEquals(200L, stored.cpuHiddenMs)
    }

    @Test
    fun `a take end counts one take and writes it immediately`() = runBlocking {
        ledger.checkpoint(LedgerEvent.SHOW)
        ledger.takeEnded(micMs = 2_000, live = true)

        val stored = ledger.snapshot().single()

        assertEquals(1, stored.takes)
        assertEquals(1, stored.liveTakes)
        assertEquals(0, stored.uploadTakes)
        assertEquals(2_000L, stored.micMs)
    }

    @Test
    fun `a stored day survives a new ledger over the same directory`() = runBlocking {
        ledger.checkpoint(LedgerEvent.SHOW)
        cpuMs = 100
        ledger.checkpoint(LedgerEvent.HIDE)
        ledger.snapshot()

        val reopened = newLedger().snapshot().single()

        assertEquals(100L, reopened.cpuVisibleMs)
    }

    @Test
    fun `a checkpoint after midnight lands on the new day`() = runBlocking {
        ledger.checkpoint(LedgerEvent.SHOW)
        cpuMs = 100
        ledger.checkpoint(LedgerEvent.HIDE)
        day = day.plusDays(1)
        cpuMs = 300
        ledger.checkpoint(LedgerEvent.SHOW)

        val stored = ledger.snapshot()

        assertEquals(2, stored.size)
        assertEquals(day.minusDays(1), stored[0].date)
        assertEquals(100L, stored[0].cpuVisibleMs)
        assertEquals(day, stored[1].date)
        assertEquals(200L, stored[1].cpuHiddenMs)
    }

    @Test
    fun `prune drops a bucket older than thirty days`() = runBlocking {
        ledger.checkpoint(LedgerEvent.SHOW)
        cpuMs = 100
        ledger.checkpoint(LedgerEvent.HIDE)
        day = day.plusDays(31)
        cpuMs = 300
        ledger.checkpoint(LedgerEvent.SHOW)

        val stored = ledger.snapshot().single()

        assertEquals(day, stored.date)
        assertEquals(200L, stored.cpuHiddenMs)
    }

    @Test
    fun `traffic counters that move backwards count from zero`() = runBlocking {
        txBytes = 1_000
        rxBytes = 0
        ledger.checkpoint(LedgerEvent.SHOW)
        txBytes = 1_500
        rxBytes = 200
        ledger.checkpoint(LedgerEvent.HIDE)
        txBytes = 100
        rxBytes = 50
        ledger.checkpoint(LedgerEvent.SHOW)

        val stored = ledger.snapshot().single()

        assertEquals(600L, stored.txBytes)
        assertEquals(250L, stored.rxBytes)
    }

    @Test
    fun `a new process counts its cpu from zero`() = runBlocking {
        ledger.checkpoint(LedgerEvent.SHOW)
        cpuMs = 1_000
        ledger.checkpoint(LedgerEvent.HIDE)
        ledger.snapshot()

        cpuMs = 50
        val restarted = newLedger()
        restarted.checkpoint(LedgerEvent.SHOW)

        val stored = restarted.snapshot().single()

        assertEquals(1_000L, stored.cpuVisibleMs)
        assertEquals(50L, stored.cpuHiddenMs)
    }
}
