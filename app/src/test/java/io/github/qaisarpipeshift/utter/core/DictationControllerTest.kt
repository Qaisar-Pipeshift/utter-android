package io.github.qaisarpipeshift.utter.core

import io.github.qaisarpipeshift.utter.audio.RecorderStart
import io.github.qaisarpipeshift.utter.audio.TakeRecorder
import io.github.qaisarpipeshift.utter.data.AppRule
import io.github.qaisarpipeshift.utter.data.HistoryStore
import io.github.qaisarpipeshift.utter.data.PendingMeta
import io.github.qaisarpipeshift.utter.data.PendingStore
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.data.StatsStore
import io.github.qaisarpipeshift.utter.diag.Diag
import io.github.qaisarpipeshift.utter.net.CleanResult
import io.github.qaisarpipeshift.utter.net.LiveEvent
import io.github.qaisarpipeshift.utter.net.LiveHandle
import io.github.qaisarpipeshift.utter.net.SttResult
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DictationControllerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var cacheDir: File
    private lateinit var filesDir: File
    private lateinit var pending: PendingStore
    private lateinit var history: HistoryStore
    private lateinit var stats: StatsStore
    private val recorder = FakeRecorder()
    private val transcriber = FakeTranscriber()
    private val cleaner = FakeCleaner()
    private val inserter = FakeInserter()
    private val haptics = mutableListOf<Haptic>()
    private val diag = Diag()

    private var settings: Settings = Settings()
    private var online = true
    private var now = 1_000_000L

    @Before
    fun setUp() {
        cacheDir = folder.newFolder("cache")
        filesDir = folder.newFolder("files")
        pending = PendingStore(cacheDir, filesDir, io = Dispatchers.Unconfined)
        history = HistoryStore(folder.newFolder("history"), io = Dispatchers.Unconfined)
        stats = StatsStore(folder.newFolder("stats"), io = Dispatchers.Unconfined, clock = { DAY })
    }

    private fun TestScope.controller(
        pendingStore: PendingStore = pending,
        onInsertDone: () -> Unit = {},
    ): DictationController = DictationController(
        scope = this,
        settings = { settings },
        recorder = recorder,
        transcriber = transcriber,
        cleaner = cleaner,
        inserter = inserter,
        pending = pendingStore,
        history = history,
        stats = stats,
        clock = Clock { now },
        isOnline = { online },
        haptic = { haptics += it },
        diag = diag,
        onInsertDone = onInsertDone,
    )

    private fun request(
        action: TakeAction = TakeAction.RAW,
        presetId: String? = null,
        kind: FieldKind = FieldKind.TEXT,
        selection: String? = null,
        incognito: Boolean = false,
    ): TakeRequest = TakeRequest(
        action = action,
        presetId = presetId,
        field = FieldInfo(kind, incognito = incognito, pkg = "com.example.app", fieldId = 7, actionLabel = EnterAction.NEWLINE),
        selection = selection,
    )

    // ---- happy paths ----

    @Test
    fun `live preview then final inserts once and clears pending`() = runTest {
        val controller = controller()
        controller.start(request())
        runCurrent()
        assertTrue(controller.state.value is PanelState.Recording)

        val live = transcriber.handle!!
        recorder.chunk()
        runCurrent()
        assertEquals(1, live.sends)

        live.emit(LiveEvent.Preview("hel", "lo"))
        runCurrent()
        assertEquals("lo", (controller.state.value as PanelState.Recording).tentative)

        live.emit(LiveEvent.Final("hello world"))
        controller.stop()
        advanceUntilIdle()

        assertEquals(listOf("hello world"), inserter.inserts.map { it.first })
        assertTrue(pending.list().isEmpty())
        val idle = controller.state.value as PanelState.Idle
        assertEquals("hello world", idle.result?.text)
        assertFalse(idle.result!!.cleaned)
    }

    @Test
    fun `successful take records history and stats`() = runTest {
        transcriber.liveEnabled = false
        val controller = controller()

        controller.start(request())
        runCurrent()
        controller.stop()
        advanceUntilIdle()

        val entries = history.list()
        assertEquals(1, entries.size)
        assertEquals("hello world", entries.single().raw)
        assertEquals("hello world", entries.single().finalText)
        assertEquals(1, stats.summary().total.takes)
    }

    @Test
    fun `live failure falls back to uploading the same file`() = runTest {
        transcriber.uploadResult = SttResult.Text("from upload", 40)
        val controller = controller()
        controller.start(request())
        runCurrent()

        transcriber.handle!!.emit(LiveEvent.Failed("boom"))
        runCurrent()
        controller.stop()
        advanceUntilIdle()

        assertEquals(1, transcriber.uploads)
        assertEquals("from upload", inserter.inserts.single().first)
        val idle = controller.state.value as PanelState.Idle
        assertEquals("from upload", idle.result?.text)
    }

    @Test
    fun `live final timeout falls back to upload after twenty seconds`() = runTest {
        transcriber.uploadResult = SttResult.Text("late upload", 10)
        val controller = controller()
        controller.start(request())
        runCurrent()

        controller.stop()
        runCurrent()
        advanceTimeBy(20_001)
        advanceUntilIdle()

        assertEquals(1, transcriber.uploads)
        assertEquals(1, transcriber.handle!!.cancels)
        assertEquals("late upload", inserter.inserts.single().first)
    }

    @Test
    fun `clean action inserts the cleaned text`() = runTest {
        transcriber.liveEnabled = false
        cleaner.result = CleanResult.Text("Hello, world.")
        val controller = controller()

        controller.start(request(TakeAction.CLEAN))
        runCurrent()
        controller.stop()
        advanceUntilIdle()

        assertEquals("Hello, world.", inserter.inserts.single().first)
        assertTrue((controller.state.value as PanelState.Idle).result!!.cleaned)
    }

    // ---- failure paths ----

    @Test
    fun `upload STT down fails the take and keeps it pending`() = runTest {
        transcriber.liveEnabled = false
        transcriber.uploadResult = SttResult.SttDown
        val controller = controller()

        controller.start(request())
        runCurrent()
        controller.stop()
        advanceUntilIdle()

        val failed = controller.state.value as PanelState.Failed
        assertEquals(FailReason.STT_DOWN, failed.reason)
        assertEquals(1, pending.list().size)
        assertEquals("stt_down", pending.meta(failed.takeId)?.lastError)
        assertTrue(inserter.inserts.isEmpty())
    }

    @Test
    fun `unauthorized keeps the take for retry`() = runTest {
        transcriber.liveEnabled = false
        transcriber.uploadResult = SttResult.Unauthorized
        val controller = controller()

        controller.start(request())
        runCurrent()
        controller.stop()
        advanceUntilIdle()

        assertEquals(FailReason.KEY_REJECTED, (controller.state.value as PanelState.Failed).reason)
        assertEquals(1, pending.list().size)
    }

    @Test
    fun `retry uploads the kept take and deletes it on success`() = runTest {
        transcriber.liveEnabled = false
        transcriber.uploadResult = SttResult.Network("timeout")
        val controller = controller()
        controller.start(request())
        runCurrent()
        controller.stop()
        advanceUntilIdle()
        val failed = controller.state.value as PanelState.Failed

        transcriber.uploadResult = SttResult.Text("retried text", 15)
        controller.retry(failed.takeId)
        advanceUntilIdle()

        assertEquals("retried text", inserter.inserts.single().first)
        assertTrue(pending.list().isEmpty())
    }

    @Test
    fun `discard drops a kept take and returns to idle`() = runTest {
        transcriber.liveEnabled = false
        transcriber.uploadResult = SttResult.Http(500)
        val controller = controller()
        controller.start(request())
        runCurrent()
        controller.stop()
        advanceUntilIdle()
        val failed = controller.state.value as PanelState.Failed
        assertEquals(FailReason.SERVER_ERROR, failed.reason)

        controller.discard(failed.takeId)
        advanceUntilIdle()

        assertTrue(pending.list().isEmpty())
        assertTrue(controller.state.value is PanelState.Idle)
    }

    @Test
    fun `no speech drops the take without inserting`() = runTest {
        transcriber.liveEnabled = false
        transcriber.uploadResult = SttResult.NoSpeech
        val controller = controller()

        controller.start(request())
        runCurrent()
        controller.stop()
        advanceUntilIdle()

        assertTrue(inserter.inserts.isEmpty())
        assertTrue(pending.list().isEmpty())
        val idle = controller.state.value as PanelState.Idle
        assertTrue(idle.result!!.noSpeech)
        assertTrue(idle.result!!.text.isEmpty())
    }

    @Test
    fun `offline start still records and fails offline at stop`() = runTest {
        transcriber.liveEnabled = false
        online = false
        val controller = controller()

        controller.start(request())
        runCurrent()
        assertTrue(controller.state.value is PanelState.Recording)

        controller.stop()
        advanceUntilIdle()

        assertEquals(FailReason.OFFLINE, (controller.state.value as PanelState.Failed).reason)
        assertEquals(0, transcriber.uploads)
        assertEquals(1, pending.list().size)
    }

    @Test
    fun `recorder error mid-take finishes and uploads what was captured`() = runTest {
        transcriber.liveEnabled = false
        val controller = controller()
        controller.start(request())
        runCurrent()

        recorder.fail(-3)
        advanceUntilIdle()

        assertEquals(1, recorder.stopCalls)
        assertEquals(1, transcriber.uploads)
        assertEquals(1, inserter.inserts.size)
    }

    // ---- edges ----

    @Test
    fun `cancel deletes the pending take and releases the mic`() = runTest {
        val controller = controller()
        controller.start(request())
        runCurrent()

        controller.cancel()
        advanceUntilIdle()

        assertEquals(1, recorder.cancelCalls)
        assertTrue(pending.list().isEmpty())
        assertTrue(controller.state.value is PanelState.Idle)
        assertTrue(inserter.inserts.isEmpty())
    }

    @Test
    fun `auto stop at 295 seconds finishes the take`() = runTest {
        transcriber.liveEnabled = false
        val controller = controller()
        controller.start(request())
        runCurrent()

        advanceTimeBy(295_001)
        advanceUntilIdle()

        assertEquals(1, recorder.stopCalls)
        assertEquals(1, transcriber.uploads)
        assertEquals(1, inserter.inserts.size)
        assertTrue(haptics.contains(Haptic.WARNING))
    }

    @Test
    fun `a take shorter than 300 ms is discarded silently`() = runTest {
        transcriber.liveEnabled = false
        recorder.durationMs = 200
        val controller = controller()

        controller.start(request())
        runCurrent()
        controller.stop()
        advanceUntilIdle()

        assertTrue(inserter.inserts.isEmpty())
        assertTrue(pending.list().isEmpty())
        assertNull((controller.state.value as PanelState.Idle).result)
    }

    @Test
    fun `clean failure inserts the raw text and flags it as not cleaned`() = runTest {
        transcriber.liveEnabled = false
        cleaner.result = CleanResult.Failed("down")
        val controller = controller()

        controller.start(request(TakeAction.CLEAN))
        runCurrent()
        controller.stop()
        advanceUntilIdle()

        val idle = controller.state.value as PanelState.Idle
        assertEquals("hello world", idle.result?.text)
        assertTrue(idle.result!!.notCleaned)
        assertEquals("hello world", inserter.inserts.single().first)
    }

    @Test
    fun `edit selection sends Prompts edit message and replaces the selection`() = runTest {
        transcriber.liveEnabled = false
        transcriber.uploadResult = SttResult.Text("fix the cat", 20)
        cleaner.result = CleanResult.Text("the cat")
        val controller = controller()

        controller.start(request(TakeAction.EDIT_SELECTION, selection = "teh cat"))
        runCurrent()
        controller.stop()
        advanceUntilIdle()

        assertEquals(Prompts.editMessage("fix the cat", "teh cat"), cleaner.messages.single())
        val (text, meta) = inserter.inserts.single()
        assertEquals("the cat", text)
        assertTrue(meta.replaceSelection)
    }

    @Test
    fun `speculative clean is reused when the final text matches`() = runTest {
        settings = settings.copy(speculativeClean = true)
        cleaner.result = CleanResult.Text("Hello there.")
        val controller = controller()
        controller.start(request(TakeAction.CLEAN))
        runCurrent()

        transcriber.handle!!.emit(LiveEvent.Preview("hello there", ""))
        runCurrent()
        advanceTimeBy(901)
        runCurrent()
        assertEquals(1, cleaner.messages.size)

        transcriber.handle!!.emit(LiveEvent.Final("hello there"))
        controller.stop()
        advanceUntilIdle()

        assertEquals(1, cleaner.messages.size)
        assertEquals("Hello there.", inserter.inserts.single().first)
    }

    @Test
    fun `speculative clean is discarded when the final text differs`() = runTest {
        settings = settings.copy(speculativeClean = true)
        cleaner.result = CleanResult.Text("Hello there.")
        val controller = controller()
        controller.start(request(TakeAction.CLEAN))
        runCurrent()

        transcriber.handle!!.emit(LiveEvent.Preview("hello there", ""))
        runCurrent()
        advanceTimeBy(901)
        runCurrent()
        assertEquals(1, cleaner.messages.size)

        transcriber.handle!!.emit(LiveEvent.Final("hello there friend"))
        controller.stop()
        advanceUntilIdle()

        assertEquals(2, cleaner.messages.size)
        assertEquals("Hello there.", inserter.inserts.single().first)
    }

    @Test
    fun `panel hidden mid-take finishes the take`() = runTest {
        transcriber.liveEnabled = false
        val controller = controller()
        controller.start(request())
        runCurrent()

        controller.onPanelHidden()
        advanceUntilIdle()

        assertEquals(1, recorder.stopCalls)
        assertEquals(1, transcriber.uploads)
        assertEquals(1, inserter.inserts.size)
    }

    @Test
    fun `chunks reach the live socket and update the amplitude`() = runTest {
        val controller = controller()
        controller.start(request())
        runCurrent()

        recorder.chunk(amplitude = 0.8f)
        runCurrent()

        assertEquals(1, transcriber.handle!!.sends)
        assertEquals(0.8f, (controller.state.value as PanelState.Recording).amplitude, 0.001f)

        controller.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `a password field never opens the mic`() = runTest {
        val controller = controller()

        controller.start(request(kind = FieldKind.PASSWORD))
        runCurrent()

        assertTrue(controller.state.value is PanelState.SecureField)
        assertFalse(recorder.started)
    }

    @Test
    fun `starting while finishing is ignored`() = runTest {
        // The live socket never answers, so the take parks in Finishing until the 20 s fallback fires.
        val controller = controller()
        controller.start(request())
        runCurrent()
        controller.stop()
        runCurrent()
        assertTrue(controller.state.value is PanelState.Finishing)

        controller.start(request())
        advanceUntilIdle()

        assertEquals(1, recorder.stopCalls)
        assertEquals(1, transcriber.uploads)
    }

    // ---- review fixes ----

    @Test
    fun `two quick starts open the mic once`() = runTest {
        // A store on its own dispatcher makes the first start suspend exactly where the double-tap race lives.
        val slowPending = PendingStore(cacheDir, filesDir, io = StandardTestDispatcher(testScheduler))
        val controller = controller(slowPending)

        controller.start(request())
        controller.start(request())
        // runCurrent (not advanceUntilIdle) so the 295 s ticker cannot fire before the assertion.
        runCurrent()

        assertEquals(1, recorder.startCalls)
        assertTrue(controller.state.value is PanelState.Recording)

        controller.cancel()
        advanceUntilIdle()
        assertTrue(slowPending.list().isEmpty())
    }

    @Test
    fun `a new take leaves the failed take in the pending queue`() = runTest {
        transcriber.liveEnabled = false
        transcriber.uploadResult = SttResult.SttDown
        val controller = controller()
        controller.start(request())
        runCurrent()
        controller.stop()
        advanceUntilIdle()
        val failed = controller.state.value as PanelState.Failed
        assertEquals(1, pending.list().size)

        transcriber.uploadResult = SttResult.Text("second", 10)
        controller.start(request())
        runCurrent()
        assertEquals(2, pending.list().size)

        controller.stop()
        advanceUntilIdle()

        val remaining = pending.list()
        assertEquals(1, remaining.size)
        assertEquals(failed.takeId, remaining.single().id)
        assertEquals(1, (controller.state.value as PanelState.Idle).pendingCount)
    }

    @Test
    fun `recorder start failure drops the take and reports the android code`() = runTest {
        recorder.startResult = RecorderStart.Error(-3, "no")
        val controller = controller()

        controller.start(request())
        runCurrent()

        val failed = controller.state.value as PanelState.Failed
        assertEquals(FailReason.RECORDER, failed.reason)
        assertEquals("", failed.takeId)
        assertEquals(-3, failed.code)
        assertTrue(pending.list().isEmpty())
    }

    @Test
    fun `an upload exception becomes unreachable instead of crashing`() = runTest {
        transcriber.liveEnabled = false
        transcriber.throwOnUpload = true
        val controller = controller()

        controller.start(request())
        runCurrent()
        controller.stop()
        advanceUntilIdle()

        val failed = controller.state.value as PanelState.Failed
        assertEquals(FailReason.UNREACHABLE, failed.reason)
        assertEquals("network_io", pending.meta(failed.takeId)?.lastError)
    }

    @Test
    fun `a cleaner exception falls back to the raw text`() = runTest {
        transcriber.liveEnabled = false
        cleaner.throwOnClean = true
        val controller = controller()

        controller.start(request(TakeAction.CLEAN))
        runCurrent()
        controller.stop()
        advanceUntilIdle()

        val idle = controller.state.value as PanelState.Idle
        assertTrue(idle.result!!.notCleaned)
        assertEquals("hello world", inserter.inserts.single().first)
    }

    @Test
    fun `an inserter exception still ends the take`() = runTest {
        transcriber.liveEnabled = false
        inserter.throwOnInsert = true
        val controller = controller()

        controller.start(request())
        runCurrent()
        controller.stop()
        advanceUntilIdle()

        val idle = controller.state.value as PanelState.Idle
        assertEquals("hello world", idle.result?.text)
        assertFalse(idle.result!!.canUndo)
        assertTrue(pending.list().isEmpty())
    }

    @Test
    fun `stats are recorded even when history is off`() = runTest {
        transcriber.liveEnabled = false
        settings = settings.copy(historyOn = false)
        val controller = controller()

        controller.start(request())
        runCurrent()
        controller.stop()
        advanceUntilIdle()

        assertTrue(history.list().isEmpty())
        assertEquals(1, stats.summary().total.takes)
    }

    @Test
    fun `a blocked app never opens the mic`() = runTest {
        settings = settings.copy(appRules = listOf(AppRule("com.example.app", block = true)))
        val controller = controller()

        controller.start(request())
        runCurrent()

        assertTrue(controller.state.value is PanelState.Blocked)
        assertEquals(0, recorder.startCalls)
        assertTrue(pending.list().isEmpty())
        assertTrue(haptics.isEmpty())
    }

    @Test
    fun `a blocked app does not retry a kept take`() = runTest {
        val pcm = pending.newTake(
            PendingMeta(
                id = "old",
                createdAt = now,
                action = TakeAction.RAW.name,
                presetId = null,
                pkg = "com.example.app",
                fieldKind = FieldKind.TEXT.name,
            ),
        )
        pcm.writeBytes(ByteArray(320))
        settings = settings.copy(appRules = listOf(AppRule("com.example.app", block = true)))
        val controller = controller()

        controller.retry("old")
        advanceUntilIdle()

        assertTrue(controller.state.value is PanelState.Blocked)
        assertEquals(0, transcriber.uploads)
        assertEquals(1, pending.list().size)
    }

    @Test
    fun `an app rule with history off keeps the take out of history`() = runTest {
        transcriber.liveEnabled = false
        settings = settings.copy(appRules = listOf(AppRule("com.example.app", historyOff = true)))
        val controller = controller()

        controller.start(request())
        runCurrent()
        controller.stop()
        advanceUntilIdle()

        assertEquals(listOf("hello world"), inserter.inserts.map { it.first })
        assertTrue(history.list().isEmpty())
        assertTrue(pending.list().isEmpty())
    }

    @Test
    fun `edit retry restores the selection and never inserts the instruction`() = runTest {
        transcriber.liveEnabled = false
        transcriber.uploadResult = SttResult.Unauthorized
        cleaner.result = CleanResult.Text("the cat")
        val controller = controller()

        controller.start(request(TakeAction.EDIT_SELECTION, selection = "teh cat", incognito = true))
        runCurrent()
        controller.stop()
        advanceUntilIdle()

        val failed = controller.state.value as PanelState.Failed
        assertEquals(FailReason.KEY_REJECTED, failed.reason)
        val stored = pending.meta(failed.takeId)!!
        assertEquals("teh cat", stored.selection)
        assertTrue(stored.incognito)

        transcriber.uploadResult = SttResult.Text("fix the cat", 25)
        controller.retry(failed.takeId)
        advanceUntilIdle()

        assertEquals(Prompts.editMessage("fix the cat", "teh cat"), cleaner.messages.single())
        val (text, meta) = inserter.inserts.single()
        assertEquals("the cat", text)
        assertTrue(meta.replaceSelection)
        assertTrue(history.list().isEmpty())
        assertEquals(0, stats.summary().total.takes)
        assertTrue(pending.list().isEmpty())
    }

    @Test
    fun `edit retry without a selection fails instead of inserting the instruction`() = runTest {
        val controller = controller()
        val pcm = pending.newTake(
            PendingMeta(
                id = "old",
                createdAt = now,
                action = TakeAction.EDIT_SELECTION.name,
                presetId = null,
                pkg = "com.example.app",
                fieldKind = FieldKind.TEXT.name,
                selection = null,
            ),
        )
        pcm.writeBytes(ByteArray(320))

        controller.retry("old")
        advanceUntilIdle()

        val failed = controller.state.value as PanelState.Failed
        assertEquals(FailReason.SERVER_ERROR, failed.reason)
        assertEquals(0, transcriber.uploads)
        assertTrue(inserter.inserts.isEmpty())
        assertEquals("edit_selection_lost", pending.meta("old")?.lastError)
        assertEquals(1, pending.list().size)
    }

    // ---- recorder ordering and shutdown ----

    @Test
    fun `a stop during a suspended recorder start waits and releases exactly once`() = runTest {
        transcriber.liveEnabled = false
        val gate = CompletableDeferred<Unit>()
        recorder.startGate = gate
        val controller = controller()

        controller.start(request())
        runCurrent()
        assertEquals(1, recorder.startCalls)

        controller.stop()
        runCurrent()
        // The stop is real only once the mic open returns; stopping first would race it and leave the mic held.
        assertEquals(0, recorder.stopCalls)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, recorder.startCalls)
        assertEquals(1, recorder.stopCalls)
        assertTrue(recorder.released)
        assertEquals(1, transcriber.uploads)
        assertEquals("hello world", inserter.inserts.single().first)
        assertTrue(pending.list().isEmpty())
        assertTrue(controller.state.value is PanelState.Idle)
    }

    @Test
    fun `a stop that lands before recording is consumed so the mic never opens`() = runTest {
        transcriber.liveEnabled = false
        recorder.durationMs = 0
        // The store's own dispatcher parks the start inside pending.newTake, which is exactly the hold-release race.
        val slowPending = PendingStore(cacheDir, filesDir, io = StandardTestDispatcher(testScheduler))
        val controller = controller(slowPending)

        controller.start(request())
        controller.stop()
        advanceUntilIdle()

        assertEquals(0, recorder.startCalls)
        assertNull(transcriber.handle)
        assertTrue(slowPending.list().isEmpty())
        assertTrue(controller.state.value is PanelState.Idle)
    }

    @Test
    fun `shutdown stops the take and keeps the audio as a pending take`() = runTest {
        val controller = controller()
        controller.start(request())
        runCurrent()

        controller.shutdown()
        advanceUntilIdle()

        assertEquals(1, recorder.stopCalls)
        assertEquals(1, transcriber.handle!!.cancels)
        val kept = pending.list().single()
        assertEquals("shutdown", kept.lastError)
        assertEquals(1_500L, kept.durationMs)
        assertTrue(pending.pcmFile(kept.id).exists())
        assertTrue(controller.state.value is PanelState.Idle)
    }

    @Test
    fun `shutdown without a take only releases the recorder`() = runTest {
        val controller = controller()

        controller.shutdown()

        assertEquals(1, recorder.cancelCalls)
        assertTrue(pending.list().isEmpty())
    }

    // ---- the keyboard hand-back ----

    @Test
    fun `the keyboard hand-back runs after the post-insert bookkeeping`() = runTest {
        transcriber.liveEnabled = false
        var historyAtHandBack = -1
        var chipAtHandBack: String? = null
        lateinit var controller: DictationController
        controller = controller(
            onInsertDone = {
                // The switch would hide the panel; by the time it runs, the take must be fully recorded.
                historyAtHandBack = history.list().size
                chipAtHandBack = (controller.state.value as? PanelState.Idle)?.result?.text
            },
        )

        controller.start(request())
        runCurrent()
        controller.stop()
        advanceUntilIdle()

        assertEquals(1, historyAtHandBack)
        assertEquals("hello world", chipAtHandBack)
        assertTrue(pending.list().isEmpty())
    }

    @Test
    fun `no keyboard hand-back when the text went to the clipboard`() = runTest {
        transcriber.liveEnabled = false
        inserter.result = false
        var handedBack = false
        val controller = controller(onInsertDone = { handedBack = true })

        controller.start(request())
        runCurrent()
        controller.stop()
        advanceUntilIdle()

        assertFalse(handedBack)
        val idle = controller.state.value as PanelState.Idle
        assertEquals("hello world", idle.result?.text)
        assertFalse(idle.result!!.canUndo)
    }

    private companion object {
        val DAY: LocalDate = LocalDate.of(2026, 10, 20)
    }
}

private class FakeRecorder : TakeRecorder {
    var startResult: RecorderStart = RecorderStart.Ok
    var durationMs = 1_500L
    var stopCalls = 0
    var cancelCalls = 0
    var startCalls = 0
    var started = false
    var released = false

    /** When set, the mic open parks here, which is exactly the window a stop must wait for. */
    var startGate: CompletableDeferred<Unit>? = null

    private var chunkSink: ((ByteArray, Float) -> Unit)? = null
    private var errorSink: ((Int) -> Unit)? = null

    override suspend fun start(
        out: File,
        onChunk: (ByteArray, Float) -> Unit,
        onError: (Int) -> Unit,
    ): RecorderStart {
        startCalls++
        startGate?.await()
        started = true
        chunkSink = onChunk
        errorSink = onError
        out.writeBytes(ByteArray(320))
        return startResult
    }

    fun chunk(amplitude: Float = 0.5f) {
        chunkSink?.invoke(ByteArray(320), amplitude)
    }

    fun fail(code: Int = -3) {
        errorSink?.invoke(code)
    }

    override suspend fun stop(): Long {
        stopCalls++
        released = true
        return durationMs
    }

    override fun cancel() {
        cancelCalls++
    }
}

private class FakeLive : LiveHandle {
    private val channel = Channel<LiveEvent>(Channel.UNLIMITED)

    var sends = 0
    var commits = 0
    var cancels = 0

    override val events: Flow<LiveEvent> = channel.receiveAsFlow()

    override fun send(pcm16le: ByteArray): Boolean {
        sends++
        return true
    }

    override fun commit() {
        commits++
    }

    override fun cancel() {
        cancels++
    }

    fun emit(event: LiveEvent) {
        channel.trySend(event)
    }
}

private class FakeTranscriber : Transcriber {
    var liveEnabled = true
    var handle: FakeLive? = null
    var uploads = 0
    var uploadResult: SttResult = SttResult.Text("hello world", 120)
    var throwOnUpload = false
    var prompt: String? = null

    override fun live(prompt: String?): LiveHandle? {
        this.prompt = prompt
        if (!liveEnabled) return null
        return FakeLive().also { handle = it }
    }

    override suspend fun upload(pcm: File, keyterms: List<String>): SttResult {
        uploads++
        if (throwOnUpload) throw IllegalStateException("upload exploded")
        return uploadResult
    }
}

private class FakeCleaner : Cleaner {
    val messages = mutableListOf<String>()
    var result: CleanResult = CleanResult.Text("cleaned")
    var throwOnClean = false

    override suspend fun clean(message: String): CleanResult {
        messages += message
        if (throwOnClean) throw IllegalStateException("clean exploded")
        return result
    }
}

private class FakeInserter : Inserter {
    val inserts = mutableListOf<Pair<String, InsertMeta>>()
    var result = true
    var throwOnInsert = false
    var previews = 0
    var cleared = 0

    override fun insert(text: String, meta: InsertMeta): Boolean {
        inserts += text to meta
        if (throwOnInsert) throw IllegalStateException("insert exploded")
        return result
    }

    override fun preview(text: String) {
        previews++
    }

    override fun clearPreview() {
        cleared++
    }
}
