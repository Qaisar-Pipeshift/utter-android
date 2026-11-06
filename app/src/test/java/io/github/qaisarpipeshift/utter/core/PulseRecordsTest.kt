package io.github.qaisarpipeshift.utter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Record-shape contract for Pulse (docs/spec.md §18.2): every record starts `{id, type, device, at}` and the
 * usage-event mapping covers the fixture table — the public UsageEvents.Event constants plus the raw-int fallback
 * for the types Android emits without one.
 */
class PulseRecordsTest {

    private var counter = 0
    private val ids = PulseIds(device = "testdevice", newId = { "id-${++counter}" }, clock = { 1_700_000_000_000 })

    @Test
    fun `base frame carries id, type, device and at`() {
        val record = ids.base("usage_event", at = 42L)

        assertEquals("id-1", record.getString("id"))
        assertEquals("usage_event", record.getString("type"))
        assertEquals("testdevice", record.getString("device"))
        assertEquals(42L, record.getLong("at"))
    }

    @Test
    fun `usage event maps the public constants to names`() {
        val fixtures = mapOf(
            1 to "ACTIVITY_RESUMED",
            2 to "ACTIVITY_PAUSED",
            5 to "CONFIGURATION_CHANGE",
            7 to "USER_INTERACTION",
            11 to "STANDBY_BUCKET_CHANGED",
            15 to "SCREEN_INTERACTIVE",
            16 to "SCREEN_NON_INTERACTIVE",
            17 to "KEYGUARD_SHOWN",
            18 to "KEYGUARD_HIDDEN",
            19 to "FOREGROUND_SERVICE_START",
            20 to "FOREGROUND_SERVICE_STOP",
            23 to "ACTIVITY_STOPPED",
            26 to "DEVICE_SHUTDOWN",
            27 to "DEVICE_STARTUP",
        )
        for ((type, name) in fixtures) {
            assertEquals(name, PulseEventNames.nameOf(type))
        }
    }

    @Test
    fun `unknown event types keep a raw-int name and are still recorded`() {
        assertEquals("event_99", PulseEventNames.nameOf(99))

        val record = PulseRecords.usageEvent(ids, at = 5L, eventType = 99, pkg = "com.example", className = null, bucket = 0)

        assertEquals("usage_event", record.getString("type"))
        assertEquals("event_99", record.getString("event"))
        assertEquals(99, record.getInt("eventType"))
        assertEquals("com.example", record.getString("pkg"))
        assertTrue(record.isNull("class"))
        assertFalse(record.has("bucket"))
    }

    @Test
    fun `standby bucket changes carry their bucket`() {
        val record = PulseRecords.usageEvent(
            ids, at = 5L, eventType = 11, pkg = "com.example", className = "com.example.Main", bucket = 30,
        )

        assertEquals(30, record.getInt("bucket"))
        assertEquals("com.example.Main", record.getString("class"))
    }

    @Test
    fun `daily records carry day, pkg and byte or millisecond totals`() {
        val usage = PulseRecords.usageDaily(ids, "2026-11-06", "com.example", 120_000L, 100_000L, 1_700_000_000_000)
        assertEquals("usage_daily", usage.getString("type"))
        assertEquals("2026-11-06", usage.getString("day"))
        assertEquals(120_000L, usage.getLong("foregroundMs"))
        assertEquals(100_000L, usage.getLong("visibleMs"))
        assertEquals(1_700_000_000_000, usage.getLong("lastUsedAt"))

        val net = PulseRecords.netDaily(ids, "2026-11-06", 10042, 5_000L, 50_000L, 0L, 0L)
        assertEquals("net_daily", net.getString("type"))
        assertEquals(10042, net.getInt("uid"))
        assertEquals(5_000L, net.getLong("wifiTx"))
        assertEquals(50_000L, net.getLong("wifiRx"))
        assertEquals(0L, net.getLong("mobileTx"))
        assertEquals(0L, net.getLong("mobileRx"))
    }

    @Test
    fun `gap, dnd and dropped records keep their payload`() {
        val connected = PulseRecords.listenerGap(ids, connected = true, reason = null)
        assertEquals("listener_gap", connected.getString("type"))
        assertTrue(connected.getBoolean("connected"))
        assertTrue(connected.isNull("reason"))

        val disconnected = PulseRecords.listenerGap(ids, connected = false, reason = 3)
        assertEquals(3, disconnected.getInt("reason"))

        val dnd = PulseRecords.dnd(ids, filter = 2)
        assertEquals("dnd", dnd.getString("type"))
        assertEquals(2, dnd.getInt("filter"))
        assertEquals("PRIORITY", dnd.getString("filterName"))
        assertEquals("ALL", DndFilters.nameOf(1))
        assertEquals("UNKNOWN", DndFilters.nameOf(0))

        val dropped = PulseRecords.storeDropped(ids, files = 2, bytes = 4096L)
        assertEquals("store_dropped", dropped.getString("type"))
        assertEquals(2, dropped.getInt("files"))
        assertEquals(4096L, dropped.getLong("bytes"))
    }

    @Test
    fun `the framework's redaction replacement text marks a record`() {
        assertTrue(PulseRecords.isRedacted(PulseRecords.REDACTED_TEXT))
        assertTrue(PulseRecords.isRedacted("sensitive notification content hidden"))
        assertFalse(PulseRecords.isRedacted("Your package has arrived"))
        assertFalse(PulseRecords.isRedacted(null))
    }

    @Test
    fun `sample carriers are plain data`() {
        val sample = PulseSample(emptyList(), usageCursor = 10L, dailyDay = 20_000, netDay = 20_000)
        assertTrue(sample.records.isEmpty())
        assertEquals(10L, sample.usageCursor)
        assertEquals(20_000L, sample.dailyDay)
        assertEquals(20_000L, sample.netDay)
    }

    @Test
    fun `closed days start after the last emitted day and never include today`() {
        assertEquals(20_001L until 20_005L, PulseWindows.closedDays(lastDay = 20_000, todayEpochDay = 20_005))
    }

    @Test
    fun `an unset daily cursor falls back to the ten-day prune window`() {
        assertEquals(19_995L until 20_005L, PulseWindows.closedDays(lastDay = 0, todayEpochDay = 20_005))

        val shortWindow = PulseWindows.closedDays(lastDay = 19_000, todayEpochDay = 20_005)
        assertEquals(19_995L, shortWindow.first) // older days are pruned by Android anyway
    }

    @Test
    fun `nothing to backfill when the last day is today`() {
        assertTrue(PulseWindows.closedDays(lastDay = 20_004, todayEpochDay = 20_005).isEmpty())
        assertTrue(PulseWindows.closedDays(lastDay = 20_005, todayEpochDay = 20_005).isEmpty())
    }

    @Test
    fun `record json never contains anything but the documented keys`() {
        val record = PulseRecords.usageEvent(ids, at = 5L, eventType = 1, pkg = "com.example", className = "Main", bucket = 0)
        assertEquals(
            setOf("id", "type", "device", "at", "event", "eventType", "pkg", "class"),
            record.keys().asSequence().toSet(),
        )
    }
}
