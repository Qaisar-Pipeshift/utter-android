package io.github.qaisarpipeshift.utter.core

import io.github.qaisarpipeshift.utter.data.HistoryStore
import io.github.qaisarpipeshift.utter.data.PendingMeta
import io.github.qaisarpipeshift.utter.data.PendingStore
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.data.StatsStore
import io.github.qaisarpipeshift.utter.diag.Diag
import io.github.qaisarpipeshift.utter.net.CleanResult
import io.github.qaisarpipeshift.utter.net.LiveHandle
import io.github.qaisarpipeshift.utter.net.SttResult
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TakeRetrierTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var pending: PendingStore
    private lateinit var history: HistoryStore
    private lateinit var stats: StatsStore
    private val transcriber = RetryTestUploader()
    private val cleaner = RetryTestCleaner()

    private var settings = Settings()
    private var now = 1_700_000_000_000L
    private val day = LocalDate.of(2026, 10, 20)

    @Before
    fun setUp() {
        pending = PendingStore(
            folder.newFolder("cache"),
            folder.newFolder("files"),
            io = Dispatchers.Unconfined,
        )
        history = HistoryStore(folder.newFolder("history"), io = Dispatchers.Unconfined)
        stats = StatsStore(folder.newFolder("stats"), io = Dispatchers.Unconfined, clock = { day })
    }

    private fun retrier(): TakeRetrier = TakeRetrier(
        transcriber = transcriber,
        cleaner = cleaner,
        pending = pending,
        history = history,
        stats = stats,
        clock = Clock { now },
        diag = Diag(),
    )

    private suspend fun take(
        action: String = "RAW",
        presetId: String? = null,
        fieldKind: String = "TEXT",
        selection: String? = null,
        incognito: Boolean = false,
    ): PendingMeta {
        val meta = PendingMeta(
            id = "take-1",
            createdAt = now,
            action = action,
            presetId = presetId,
            pkg = "com.example.app",
            fieldKind = fieldKind,
            durationMs = 1_500,
            selection = selection,
            incognito = incognito,
        )
        val file = pending.newTake(meta)
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        return meta
    }

    @Test
    fun `raw retry records history and deletes the pending take`() = runTest {
        settings = settings.copy(vocabulary = listOf("Utter"))
        transcriber.result = SttResult.Text("um hello world", 140)
        val meta = take()

        val outcome = retrier().retry(meta, settings)

        assertEquals("hello world", (outcome as RetryOutcome.Success).text)
        assertFalse(outcome.cleaned)
        assertEquals(listOf("Utter"), transcriber.keyterms)
        val entry = history.list().single()
        assertEquals("hello world", entry.finalText)
        assertEquals("RAW", entry.action)
        assertEquals(meta.id, entry.audioId)
        assertTrue(pending.list().isEmpty())
        assertEquals(1, stats.summary().total.takes)
    }

    @Test
    fun `clean retry uses the preset prompt and records its name`() = runTest {
        transcriber.result = SttResult.Text("um hello world", 90)
        cleaner.result = CleanResult.Text("Hello, world.")
        val meta = take(action = "CLEAN", presetId = Presets.CLEAN_ID)

        val outcome = retrier().retry(meta, settings)

        val success = outcome as RetryOutcome.Success
        assertEquals("Hello, world.", success.text)
        assertTrue(success.cleaned)
        assertEquals("Clean", success.presetName)
        assertTrue(cleaner.messages.single().contains("<TRANSCRIPT>"))
        assertTrue(cleaner.messages.single().contains("hello world"))
        assertEquals("Clean", history.list().single().presetName)
    }

    @Test
    fun `clean failure inserts raw text marked not cleaned`() = runTest {
        transcriber.result = SttResult.Text("hello there", 70)
        cleaner.result = CleanResult.Failed("boom")
        val meta = take(action = "CLEAN", presetId = Presets.CLEAN_ID)

        val outcome = retrier().retry(meta, settings) as RetryOutcome.Success

        assertEquals("hello there", outcome.text)
        assertFalse(outcome.cleaned)
        assertTrue(outcome.notCleaned)
    }

    @Test
    fun `no speech drops the pending take`() = runTest {
        transcriber.result = SttResult.NoSpeech
        val meta = take()

        val outcome = retrier().retry(meta, settings)

        assertEquals(RetryOutcome.NoSpeech, outcome)
        assertTrue(pending.list().isEmpty())
        assertTrue(history.list().isEmpty())
    }

    @Test
    fun `upload failure keeps the take and records the error`() = runTest {
        transcriber.result = SttResult.SttDown
        val meta = take()

        val outcome = retrier().retry(meta, settings)

        assertEquals(FailReason.STT_DOWN, (outcome as RetryOutcome.Failed).reason)
        val kept = pending.list().single()
        assertEquals("stt_down", kept.lastError)
        assertTrue(pending.pcmFile(meta.id).exists())
    }

    @Test
    fun `edit selection uses the edit prompt`() = runTest {
        transcriber.result = SttResult.Text("make it formal", 80)
        cleaner.result = CleanResult.Text("The cat.")
        val meta = take(action = "EDIT_SELECTION", selection = "teh cat")

        val outcome = retrier().retry(meta, settings) as RetryOutcome.Success

        assertEquals("The cat.", outcome.text)
        assertTrue(cleaner.messages.single().contains("<INSTRUCTION>"))
        assertTrue(cleaner.messages.single().contains("teh cat"))
    }

    @Test
    fun `incognito retry writes neither history nor stats`() = runTest {
        transcriber.result = SttResult.Text("a secret", 50)
        val meta = take(incognito = true)

        retrier().retry(meta, settings)

        assertTrue(history.list().isEmpty())
        assertEquals(0, stats.summary().total.takes)
        assertTrue(pending.list().isEmpty())
    }

    @Test
    fun `raw phone field skips cleanup and keeps digits`() = runTest {
        transcriber.result = SttResult.Text("+91 98765 43210", 60)
        val meta = take(action = "CLEAN", fieldKind = "PHONE")

        val outcome = retrier().retry(meta, settings) as RetryOutcome.Success

        assertEquals("+919876543210", outcome.text)
        assertTrue(cleaner.messages.isEmpty())
    }

    @Test
    fun `keep audio moves the clip next to history`() = runTest {
        settings = settings.copy(keepAudioDays = 1)
        transcriber.result = SttResult.Text("keep this", 40)
        val meta = take()

        retrier().retry(meta, settings)

        assertTrue(pending.list().isEmpty())
        assertTrue(pending.audioFile(meta.id).exists())
        assertTrue(pending.keptAudioIds().contains(meta.id))
    }

    @Test
    fun `missing pcm fails without throwing`() = runTest {
        val meta = PendingMeta(
            id = "gone",
            createdAt = now,
            action = "RAW",
            presetId = null,
            pkg = "com.example.app",
            fieldKind = "TEXT",
        )

        val outcome = retrier().retry(meta, settings)

        assertEquals(FailReason.UNREACHABLE, (outcome as RetryOutcome.Failed).reason)
        assertEquals(0, transcriber.uploads)
    }
}

private class RetryTestUploader : Transcriber {
    var result: SttResult = SttResult.Text("hello world", 100)
    var keyterms: List<String> = emptyList()
    var uploads = 0

    override fun live(prompt: String?): LiveHandle? = null

    override suspend fun upload(pcm: File, keyterms: List<String>): SttResult {
        uploads++
        this.keyterms = keyterms
        return result
    }
}

private class RetryTestCleaner : Cleaner {
    val messages = mutableListOf<String>()
    var result: CleanResult = CleanResult.Text("cleaned")

    override suspend fun clean(message: String): CleanResult {
        messages += message
        return result
    }
}
