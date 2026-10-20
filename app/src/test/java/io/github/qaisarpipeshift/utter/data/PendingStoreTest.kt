package io.github.qaisarpipeshift.utter.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var cache: File
    private lateinit var files: File
    // A realistic epoch time: File.setLastModified rejects negative values, and "8 days ago" must stay positive.
    private var now = 1_800_000_000_000L
    private lateinit var store: PendingStore

    @Before
    fun setUp() {
        cache = folder.newFolder("cache")
        files = folder.newFolder("files")
        store = PendingStore(cache, files, now = { now })
    }

    private fun meta(id: String, createdAt: Long = 0): PendingMeta = PendingMeta(
        id = id,
        createdAt = createdAt,
        action = "RAW",
        presetId = null,
        pkg = "com.example.app",
        fieldKind = "TEXT",
    )

    @Test
    fun `new take writes meta and returns the pcm path`() = runBlocking {
        val pcm = store.newTake(meta("t1"))

        assertEquals("t1.pcm", pcm.name)
        assertEquals("takes", pcm.parentFile?.name)
        assertEquals(now, store.meta("t1")?.createdAt)
        assertEquals(listOf("t1"), store.list().map { it.id })
    }

    @Test
    fun `selection and incognito round-trip through the meta file`() = runBlocking {
        store.newTake(meta("t1").copy(selection = "teh cat", incognito = true))

        val loaded = store.meta("t1")!!

        assertEquals("teh cat", loaded.selection)
        assertTrue(loaded.incognito)
    }

    @Test
    fun `meta written without selection reads back as a plain take`() = runBlocking {
        store.newTake(meta("t1"))

        val loaded = store.meta("t1")!!

        assertNull(loaded.selection)
        assertFalse(loaded.incognito)
    }

    @Test
    fun `update stores the last error and duration`() = runBlocking {
        store.newTake(meta("t1"))

        store.update("t1", "unreachable", 3_000)

        val updated = store.meta("t1")
        assertEquals("unreachable", updated?.lastError)
        assertEquals(3_000L, updated?.durationMs)
    }

    @Test
    fun `purge drops takes older than a day and keeps fresh ones`() = runBlocking {
        store.newTake(meta("old", createdAt = now - 25L * 60 * 60 * 1000))
        store.newTake(meta("fresh", createdAt = now))
        File(cache, "takes/old.pcm").writeText("pcm")
        File(cache, "takes/fresh.pcm").writeText("pcm")

        store.purge()

        assertEquals(listOf("fresh"), store.list().map { it.id })
        assertFalse(File(cache, "takes/old.pcm").exists())
        assertTrue(File(cache, "takes/fresh.pcm").exists())
    }

    @Test
    fun `delete removes pcm and meta`() = runBlocking {
        val pcm = store.newTake(meta("t1"))
        pcm.writeText("pcm")

        store.delete("t1")

        assertFalse(pcm.exists())
        assertNull(store.meta("t1"))
    }

    @Test
    fun `keep audio moves the pcm and drops the pending meta`() = runBlocking {
        val pcm = store.newTake(meta("t1"))
        pcm.writeText("pcm")

        store.keepAudio("t1", days = 7)

        assertTrue(File(files, "audio/t1.pcm").exists())
        assertFalse(pcm.exists())
        assertNull(store.meta("t1"))
    }

    @Test
    fun `purge audio deletes kept audio older than the window`() = runBlocking {
        val pcm = store.newTake(meta("t1"))
        pcm.writeText("pcm")
        store.keepAudio("t1", days = 7)
        val kept = File(files, "audio/t1.pcm")
        kept.setLastModified(now - 8L * 24 * 60 * 60 * 1000)

        store.purgeAudio(days = 7)

        assertFalse(kept.exists())
    }
}
