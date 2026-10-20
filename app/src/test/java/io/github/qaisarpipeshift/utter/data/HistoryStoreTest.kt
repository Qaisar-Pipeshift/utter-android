package io.github.qaisarpipeshift.utter.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var store: HistoryStore

    @Before
    fun setUp() {
        dir = folder.newFolder("history")
        store = HistoryStore(dir)
    }

    private fun entry(id: String, ts: Long, finalText: String, appLabel: String = "Messages") = HistoryEntry(
        id = id,
        ts = ts,
        pkg = "com.example.app",
        appLabel = appLabel,
        raw = "raw $finalText",
        finalText = finalText,
        action = "RAW",
        presetName = null,
        durationMs = 1_200,
        latencyMs = 140,
        words = 2,
    )

    @Test
    fun `add then list newest first`() = runBlocking {
        store.add(entry("1", 100, "first"))
        store.add(entry("2", 200, "second"))

        val entries = store.list()

        assertEquals(listOf("2", "1"), entries.map { it.id })
        assertEquals("second", entries.first().finalText)
    }

    @Test
    fun `search is case insensitive over text and app`() = runBlocking {
        store.add(entry("1", 100, "Hello World"))
        store.add(entry("2", 200, "lunch plans", appLabel = "WhatsApp"))

        assertEquals(listOf("1"), store.list("hello").map { it.id })
        assertEquals(listOf("2"), store.list("whatsapp").map { it.id })
        assertEquals(2, store.list("").size)
    }

    @Test
    fun `corrupt lines are skipped`() = runBlocking {
        store.add(entry("1", 100, "kept"))
        File(dir, "history.jsonl").appendText("not json\n")

        assertEquals(listOf("1"), store.list().map { it.id })
    }

    @Test
    fun `delete removes only the requested entry`() = runBlocking {
        store.add(entry("1", 100, "one"))
        store.add(entry("2", 200, "two"))

        store.delete("1")

        assertEquals(listOf("2"), store.list().map { it.id })
    }

    @Test
    fun `prune keeps the newest entries`() = runBlocking {
        for (index in 1..5) store.add(entry("$index", index.toLong(), "text $index"))

        store.prune(2)

        assertEquals(listOf("5", "4"), store.list().map { it.id })
    }

    @Test
    fun `prune zero keeps everything`() = runBlocking {
        store.add(entry("1", 100, "one"))
        store.add(entry("2", 200, "two"))

        store.prune(0)

        assertEquals(2, store.list().size)
    }

    @Test
    fun `clear removes the file`() = runBlocking {
        store.add(entry("1", 100, "one"))

        store.clear()

        assertTrue(store.list().isEmpty())
        assertFalse(File(dir, "history.jsonl").exists())
    }
}
