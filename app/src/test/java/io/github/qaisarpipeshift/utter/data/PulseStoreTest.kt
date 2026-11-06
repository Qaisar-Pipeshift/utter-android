package io.github.qaisarpipeshift.utter.data

import io.github.qaisarpipeshift.utter.core.PulseIds
import io.github.qaisarpipeshift.utter.core.PulseTypes
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Batch semantics of the Pulse store (docs/spec.md §18.2): JSONL per batch, oldest-first closed batches, the
 * 100 MB cap dropping the oldest batch with a `store_dropped` record, and cursors that only move on a good write.
 */
class PulseStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var dir: File
    private var counter = 0
    private val ids = PulseIds(device = "d", newId = { "id-${++counter}" }, clock = { 1_700_000_000_000 })
    private var capMb = PulseStore.DEFAULT_CAP_MB
    private lateinit var store: PulseStore

    @Before
    fun setUp() {
        dir = folder.newFolder("pulse")
        store = PulseStore(dir, ids, capBytes = { capMb * PulseStore.BYTES_PER_MB })
    }

    private fun record(kind: String = "usage_event", pad: String = ""): JSONObject =
        ids.base(kind).put("pkg", "com.example$pad")

    @Test
    fun `append then close reads back the same records`() = runBlocking {
        val records = listOf(record(), record(pad = "-2"), record(kind = "notif_posted", pad = "-3"))
        assertTrue(store.append(records))

        store.closeBatch()

        val batches = store.closedBatches()
        assertEquals(1, batches.size)
        val lines = store.readBatch(batches.first())!!
        assertEquals(records.map { it.getString("id") }, lines.map { JSONObject(it).getString("id") })
    }

    @Test
    fun `the open batch is never listed as closed`() = runBlocking {
        store.append(listOf(record()))

        assertEquals(emptyList<File>(), store.closedBatches())

        store.closeBatch()
        assertEquals(1, store.closedBatches().size)
    }

    @Test
    fun `a batch auto-closes at the record limit`() = runBlocking {
        store.append(List(PulseStore.BATCH_RECORDS + 1) { record(pad = "-${it}") })

        assertEquals(1, store.closedBatches().size)
        assertEquals(PulseStore.BATCH_RECORDS, store.readBatch(store.closedBatches().first())!!.size)
    }

    @Test
    fun `cursors and throttle timestamp round-trip`() = runBlocking {
        assertEquals(PulseMeta(), store.meta())

        store.saveMeta(PulseMeta(usageCursor = 123L, dailyDay = 20_000, netDay = 20_001, lastSampleAt = 456L))

        assertEquals(PulseMeta(123L, 20_000, 20_001, 456L), store.meta())
    }

    @Test
    fun `overflow drops the oldest batch and records the drop`() = runBlocking {
        capMb = 1
        val big = "x".repeat(400_000)
        store.append(listOf(record(pad = "-1-$big"), record(pad = "-1b-$big")))
        store.closeBatch()
        val firstBatch = store.closedBatches().single().name // 800 KB, under the cap

        // Every further batch pushes the total over 1 MB, so an older batch goes away each time.
        for (index in 2..4) {
            store.append(listOf(record(pad = "-$index-$big"), record(pad = "-${index}b-$big")))
            store.closeBatch()
        }

        val nowClosed = store.closedBatches()
        assertFalse(nowClosed.any { it.name == firstBatch }) // the oldest batch is gone
        val drop = nowClosed.asSequence()
            .map { store.readBatch(it)!! }
            .flatten()
            .plus(listOfNotNull(readOpenRecord()))
            .map { JSONObject(it) }
            .firstOrNull { it.getString("type") == PulseTypes.STORE_DROPPED }
        assertTrue(drop != null)
        assertTrue(drop!!.getInt("files") >= 1)
        assertTrue(drop.getLong("bytes") > 0)
    }

    @Test
    fun `a failed write reports false and cursors stay put`() = runBlocking {
        // The parent of the pulse directory is a regular file, so mkdirs() cannot create it.
        val blockedParent = File(folder.root, "blocker")
        blockedParent.writeText("not a directory")
        val blocked = PulseStore(File(blockedParent, "pulse"), ids)

        assertFalse(blocked.append(listOf(record())))
        assertEquals(PulseMeta(), blocked.meta())
    }

    @Test
    fun `delete removes only the named batch`() = runBlocking {
        store.append(listOf(record()))
        store.closeBatch()
        store.append(listOf(record()))
        store.closeBatch()
        val batches = store.closedBatches()

        store.deleteBatch(batches.first())

        assertEquals(listOf(batches.last().name), store.closedBatches().map { it.name })
    }

    /** The open batch may hold the dropped-record line; read it directly when present. */
    private fun readOpenRecord(): String? {
        val open = dir.listFiles()?.firstOrNull { it.name.endsWith(PulseStore.OPEN_SUFFIX) } ?: return null
        return open.readLines().firstOrNull { it.isNotBlank() }
    }
}
