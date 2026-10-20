package io.github.qaisarpipeshift.utter.core

import io.github.qaisarpipeshift.utter.audio.RecorderStart
import io.github.qaisarpipeshift.utter.audio.TakeRecorder
import io.github.qaisarpipeshift.utter.data.HistoryEntry
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
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Speech-to-text behind one small port: a live socket for previews, one upload for the fallback. */
interface Transcriber {
    /** Opens a live session for this take, or null when live is unavailable; a session that fails late fails in [LiveHandle]. */
    fun live(prompt: String?): LiveHandle?

    /** Uploads the whole take; [keyterms] is the user's vocabulary hint. */
    suspend fun upload(pcm: File, keyterms: List<String>): SttResult
}

/** Cleanup model behind one port so the controller never knows the API's model or timeout settings. */
interface Cleaner {
    /** Sends the whole user message and returns the cleaned text or why it failed. */
    suspend fun clean(message: String): CleanResult
}

/** Where a result goes: the field the take started in, and whether it replaces the selected text. */
data class InsertMeta(val field: FieldInfo, val replaceSelection: Boolean = false)

/** Text insertion behind one port; the IME implements it on the current input connection. */
interface Inserter {
    /** Inserts [text] and returns false when the field was gone and the text went to the clipboard instead. */
    fun insert(text: String, meta: InsertMeta): Boolean

    /** Shows [text] as composing text while live dictation is in field mode. */
    fun preview(text: String)

    /** Removes the composing text; called before the final insert. */
    fun clearPreview()

    /** Deletes the last insert if it is still intact; false when it is not. */
    fun undo(): Boolean = false

    /** Replaces the last insert with [text] if it is still intact. */
    fun swap(text: String, meta: InsertMeta): Boolean = false
}

/** Wall clock behind a port so tests drive latency and history timestamps without sleeping. */
fun interface Clock {
    fun now(): Long
}

/**
 * The dictation state machine (docs/spec.md §4, §6, §8; docs/plan.md Task 8).
 *
 * Why one class owns the whole take: every failure path has to end in exactly one of "inserted", "no speech", "kept
 * for retry" or "discarded", and spreading that across the panel, the recorder and the API is what loses takes.
 * Everything that can be Android-specific is injected, so this file has no Android types and the whole machine runs
 * on the JVM in tests.
 *
 * Threading contract: every state write happens in a coroutine on [scope] (Main in the app). The recorder's chunk
 * and error callbacks arrive on an IO thread, so they hop onto [scope] before touching state; forwarding a chunk to
 * [LiveHandle.send] is the one thing done directly on the IO thread, because it is a non-blocking socket write.
 *
 * Take safety: the pending PCM is deleted only after a successful insert (or a clip-board fallback decided by the
 * IME), after "no speech", or on an explicit cancel/discard. Every other outcome keeps the file and the metadata so
 * the failure chip's Retry can finish the take later.
 */
class DictationController(
    private val scope: CoroutineScope,
    private val settings: () -> Settings,
    private val recorder: TakeRecorder,
    private val transcriber: Transcriber,
    private val cleaner: Cleaner,
    private val inserter: Inserter,
    private val pending: PendingStore,
    private val history: HistoryStore,
    private val stats: StatsStore,
    private val clock: Clock = Clock { System.currentTimeMillis() },
    private val isOnline: () -> Boolean = { true },
    private val haptic: (Haptic) -> Unit = {},
    private val diag: Diag = Diag(),
) {
    private val _state = MutableStateFlow<PanelState>(PanelState.Idle(result = null, pendingCount = 0, offline = false))
    val state: StateFlow<PanelState> = _state.asStateFlow()

    private var take: Take? = null

    @Volatile
    private var live: LiveHandle? = null
    private var liveJob: Job? = null
    private var finalEvent: CompletableDeferred<LiveEvent>? = null
    private var tickerJob: Job? = null
    private var specJob: Job? = null
    private var specCommitted: String? = null
    private var specCleaned: String? = null
    private var lastInsert: LastInsert? = null

    // ---- panel API ----

    /** Starts a take. Ignored while another take is recording or finishing; a failed take is dropped (one kept). */
    fun start(request: TakeRequest, holding: Boolean = false) {
        scope.launch { startTake(request, holding) }
    }

    /** Ends the take and runs it to insertion. Ignored unless recording. */
    fun stop() {
        scope.launch { finishCurrent() }
    }

    /** Discards the recording and its pending file. Ignored unless recording. */
    fun cancel() {
        scope.launch { cancelTake() }
    }

    /** Turns a held push-to-talk take into hands-free, so releasing the finger no longer stops it. */
    fun lock() {
        scope.launch {
            val recording = _state.value as? PanelState.Recording ?: return@launch
            if (recording.locked) return@launch
            _state.value = recording.copy(locked = true, holding = false)
            haptic(Haptic.LOCK)
            diag.event("take_lock")
        }
    }

    /** The panel is going away mid-take: release the mic at once and finish normally (IME falls back to clipboard). */
    fun onPanelHidden() {
        scope.launch { finishCurrent() }
    }

    /** Runs a kept take through upload again, using the metadata stored with it. */
    fun retry(takeId: String) {
        scope.launch { retryTake(takeId) }
    }

    /** Drops a kept take the user no longer wants. */
    fun discard(takeId: String) {
        scope.launch {
            pending.delete(takeId)
            val failed = _state.value as? PanelState.Failed
            if (failed?.takeId == takeId) _state.value = idleState(null)
            diag.event("take_discard")
        }
    }

    /** Undo the last insert when the IME still sees it in the field. */
    fun undo() {
        scope.launch {
            if (lastInsert == null) return@launch
            if (inserter.undo()) {
                lastInsert = null
                diag.event("undo")
                _state.value = idleState(null)
            }
        }
    }

    /** Swap the last insert for its other variant: cleaned text for raw, or one cleanup pass for raw text. */
    fun swap() {
        scope.launch { swapLast() }
    }

    // ---- take lifecycle ----

    private suspend fun startTake(request: TakeRequest, holding: Boolean) {
        val current = _state.value
        when (current) {
            is PanelState.Recording, is PanelState.Finishing -> return
            is PanelState.Failed -> pending.delete(current.takeId)
            else -> Unit
        }
        val snapshot = settings()
        val field = request.field
        if (field.kind == FieldKind.PASSWORD) {
            _state.value = PanelState.SecureField
            return
        }

        val preset = if (request.action == TakeAction.CLEAN) presetFor(snapshot, request.presetId) else null
        val id = UUID.randomUUID().toString()
        val file = pending.newTake(
            PendingMeta(
                id = id,
                createdAt = clock.now(),
                action = request.action.name,
                presetId = request.presetId,
                pkg = field.pkg,
                fieldKind = field.kind.name,
            ),
        )
        val started = Take(id, file, request, preset, snapshot)
        take = started
        lastInsert = null
        resetSpeculation()
        finalEvent = CompletableDeferred()
        _state.value = PanelState.Recording(
            action = request.action,
            locked = false,
            holding = holding,
            elapsedMs = 0,
            amplitude = 0f,
            committed = "",
            tentative = "",
        )
        diag.event("take_start", request.action.name)
        haptic(Haptic.START)
        startTicker()
        startLive(started)

        val outcome = try {
            recorder.start(file, ::onChunk, ::onRecorderError)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // The interface promises a value; an implementation bug must still not strand the panel in Recording.
            RecorderStart.Error(RECORDER_FAILED, failure.javaClass.simpleName)
        }
        if (outcome is RecorderStart.Error) {
            diag.event("recorder_fail", outcome.code.toString())
            stopLive(cancel = true)
            pending.update(id, "recorder_${outcome.code}")
            clearTake()
            haptic(Haptic.ERROR)
            _state.value = PanelState.Failed(FailReason.RECORDER, id)
        }
    }

    private suspend fun finishCurrent() {
        val recording = _state.value as? PanelState.Recording ?: return
        val started = take
        _state.value = PanelState.Finishing(recording.action)
        tickerJob?.cancel()
        tickerJob = null
        specJob?.cancel()
        specJob = null
        haptic(Haptic.STOP)

        if (started == null) {
            // Defensive: a take without its metadata cannot be retried; do not invent one.
            recorder.cancel()
            _state.value = idleState(null)
            return
        }

        val duration = recorder.stop()
        started.durationMs = duration
        diag.event("take_stop", duration.toString())
        pending.update(started.id, null, duration)
        if (started.settings.liveInField) runCatching { inserter.clearPreview() }

        if (duration < MIN_TAKE_MS) {
            stopLive(cancel = true)
            pending.delete(started.id)
            clearTake()
            diag.event("take_short", duration.toString())
            _state.value = idleState(null)
            return
        }

        val result = awaitLive() ?: uploadTake(started)
        handleResult(started, result)
    }

    private suspend fun cancelTake() {
        if (_state.value !is PanelState.Recording) return
        val started = take
        tickerJob?.cancel()
        tickerJob = null
        specJob?.cancel()
        specJob = null
        if (started?.settings?.liveInField == true) runCatching { inserter.clearPreview() }
        stopLive(cancel = true)
        recorder.cancel()
        started?.let { pending.delete(it.id) }
        clearTake()
        lastInsert = null
        haptic(Haptic.CANCEL)
        diag.event("take_cancel")
        _state.value = idleState(null)
    }

    private suspend fun retryTake(takeId: String) {
        when (_state.value) {
            is PanelState.Recording, is PanelState.Finishing -> return
            else -> Unit
        }
        val meta = pending.meta(takeId)
        if (meta == null) {
            _state.value = idleState(null)
            return
        }
        val file = pending.pcmFile(takeId)
        if (!file.exists()) {
            pending.delete(takeId)
            _state.value = idleState(null)
            return
        }
        val snapshot = settings()
        val action = actionOf(meta.action)
        val kind = fieldKindOf(meta.fieldKind)
        // The field may be a different one after an app switch; the inserter targets the current connection anyway.
        val field = FieldInfo(kind, incognito = false, pkg = meta.pkg, fieldId = -1, actionLabel = EnterAction.NEWLINE)
        val request = TakeRequest(action, meta.presetId, field, selection = null)
        val retried = Take(takeId, file, request, presetFor(snapshot, meta.presetId), snapshot)
        retried.durationMs = meta.durationMs
        take = retried
        lastInsert = null
        _state.value = PanelState.Finishing(action)
        diag.event("take_retry", action.name)
        handleResult(retried, uploadTake(retried))
    }

    // ---- live session ----

    private fun startLive(started: Take) {
        if (!started.settings.live) {
            diag.event("live_off")
            return
        }
        val prompt = started.settings.vocabulary.joinToString(", ").ifBlank { null }
        val handle = try {
            transcriber.live(prompt)
        } catch (_: Exception) {
            null
        }
        if (handle == null) {
            diag.event("live_unavailable")
            return
        }
        live = handle
        liveJob = scope.launch {
            handle.events.collect { event -> onLiveEvent(event) }
        }
    }

    private fun onLiveEvent(event: LiveEvent) {
        if (event is LiveEvent.Preview) {
            val recording = _state.value as? PanelState.Recording ?: return
            _state.value = recording.copy(committed = event.committed, tentative = event.tentative)
            if (take?.settings?.liveInField == true) {
                runCatching { inserter.preview(event.committed + event.tentative) }
            }
            maybeSpeculate(event.committed, event.tentative)
            return
        }
        finalEvent?.complete(event)
    }

    /** Sends a chunk to the socket on the IO thread, then hops to [scope] for the panel's amplitude. */
    private fun onChunk(chunk: ByteArray, amplitude: Float) {
        live?.send(chunk)
        scope.launch {
            val recording = _state.value as? PanelState.Recording ?: return@launch
            _state.value = recording.copy(amplitude = amplitude)
        }
    }

    /** The mic died mid-take: finish exactly like a normal stop so the captured audio is still transcribed. */
    private fun onRecorderError(code: Int) {
        diag.event("recorder_error", code.toString())
        scope.launch { finishCurrent() }
    }

    private suspend fun awaitLive(): SttResult? {
        val handle = live ?: return null
        val deferred = finalEvent ?: return null
        handle.commit()
        val started = clock.now()
        val event = withTimeoutOrNull(FINAL_TIMEOUT_MS) { deferred.await() }
        val result = when (event) {
            is LiveEvent.Final -> SttResult.Text(event.text, (clock.now() - started).coerceAtLeast(0))
            LiveEvent.NoSpeech -> SttResult.NoSpeech
            else -> null
        }
        if (result == null) {
            diag.event("live_fallback", (event as? LiveEvent.Failed)?.why ?: "timeout")
        }
        stopLive(cancel = result == null)
        return result
    }

    private fun stopLive(cancel: Boolean) {
        val handle = live
        liveJob?.cancel()
        liveJob = null
        live = null
        if (cancel && handle != null) runCatching { handle.cancel() }
    }

    // ---- result paths ----

    private suspend fun uploadTake(started: Take): SttResult {
        if (!isOnline()) {
            diag.event("upload_offline")
            return SttResult.Network(KIND_OFFLINE)
        }
        diag.event("upload", started.durationMs.toString())
        return transcriber.upload(started.file, started.settings.vocabulary)
    }

    private suspend fun handleResult(started: Take, result: SttResult) {
        when (result) {
            is SttResult.Text -> applyCompletion(started, result, compose(started, result))
            SttResult.NoSpeech -> noSpeech(started, 0)
            SttResult.SttDown -> fail(started, FailReason.STT_DOWN, "stt_down")
            SttResult.Unauthorized -> fail(started, FailReason.KEY_REJECTED, "unauthorized")
            is SttResult.Http -> fail(started, FailReason.SERVER_ERROR, "http_${result.code}")
            is SttResult.Network -> if (result.kind == KIND_OFFLINE) {
                fail(started, FailReason.OFFLINE, "offline")
            } else {
                fail(started, FailReason.UNREACHABLE, "network_${result.kind}")
            }
        }
    }

    /**
     * Turns recognised text into what should be inserted. Pure apart from the cleanup call, so every branch —
     * raw field types, clean failure, speculative reuse, voice edit — is one readable rule.
     */
    private suspend fun compose(started: Take, speech: SttResult.Text): Completion {
        val snapshot = started.settings
        val kind = started.field.kind
        val rawText = TextPipeline.raw(speech.text, snapshot)
        val selection = started.request.selection
        val action = started.request.action

        if (action == TakeAction.EDIT_SELECTION && !selection.isNullOrBlank()) {
            return when (val result = cleaner.clean(Prompts.editMessage(rawText, selection))) {
                is CleanResult.Text -> {
                    val text = result.text.trim()
                    if (text.isEmpty()) {
                        Completion.Failed(FailReason.SERVER_ERROR, "edit_empty")
                    } else {
                        Completion.Done(text, cleaned = true, notCleaned = false, presetName = null, replaceSelection = true)
                    }
                }

                is CleanResult.Failed -> Completion.Failed(FailReason.SERVER_ERROR, "edit_failed")
            }
        }

        if (action == TakeAction.RAW) {
            return Completion.Done(TextPipeline.forField(rawText, kind, snapshot), false, false, null, false)
        }

        // CLEAN, or an edit request whose selection disappeared while the user was speaking.
        val preset = started.preset ?: presetFor(snapshot, started.request.presetId)
        if (preset == null) {
            return Completion.Done(TextPipeline.forField(rawText, kind, snapshot), false, true, null, false)
        }
        if (needsRawField(kind)) {
            return Completion.Done(TextPipeline.forField(rawText, kind, snapshot), false, false, preset.name, false)
        }
        val speculative = specCleaned?.takeIf { specCommitted == speech.text }
        val cleanedText = speculative ?: when (val result = cleaner.clean(Prompts.cleanMessage(preset.prompt, rawText))) {
            is CleanResult.Text -> result.text

            is CleanResult.Failed -> {
                diag.event("clean_failed", result.why)
                null
            }
        }
        return if (cleanedText.isNullOrBlank()) {
            Completion.Done(TextPipeline.forField(rawText, kind, snapshot), false, true, preset.name, false)
        } else {
            Completion.Done(TextPipeline.forField(cleanedText, kind, snapshot), true, false, preset.name, false)
        }
    }

    private suspend fun applyCompletion(started: Take, speech: SttResult.Text, completion: Completion) {
        when (completion) {
            is Completion.Failed -> fail(started, completion.reason, completion.error)
            is Completion.Done -> if (completion.text.isBlank()) noSpeech(started, speech.latencyMs) else insertDone(started, speech, completion)
        }
    }

    private suspend fun insertDone(started: Take, speech: SttResult.Text, done: Completion.Done) {
        val snapshot = started.settings
        val meta = InsertMeta(started.field, done.replaceSelection)
        val inserted = runCatching { inserter.insert(done.text, meta) }.getOrDefault(false)
        diag.event(if (inserted) "insert" else "insert_clipboard")

        val rule = snapshot.appRules.firstOrNull { it.pkg == started.field.pkg }
        val keepHistory = snapshot.historyOn && !started.field.incognito && rule?.historyOff != true
        val words = TextOps.wordCount(done.text)
        if (keepHistory) {
            history.add(
                HistoryEntry(
                    id = UUID.randomUUID().toString(),
                    ts = clock.now(),
                    pkg = started.field.pkg,
                    appLabel = snapshot.seenApps[started.field.pkg] ?: started.field.pkg,
                    raw = speech.text,
                    finalText = done.text,
                    action = started.request.action.name,
                    presetName = done.presetName,
                    durationMs = started.durationMs,
                    latencyMs = speech.latencyMs,
                    words = words,
                ),
            )
            history.prune(snapshot.historyLimit)
            stats.record(started.field.pkg, words, started.durationMs)
        }

        if (snapshot.keepAudioDays > 0) {
            pending.keepAudio(started.id, snapshot.keepAudioDays)
        } else {
            pending.delete(started.id)
        }

        val canSwap = inserted && !done.replaceSelection && (done.cleaned || !needsRawField(started.field.kind))
        lastInsert = LastInsert(
            raw = TextPipeline.raw(speech.text, snapshot),
            inserted = done.text,
            cleaned = done.cleaned,
            notCleaned = done.notCleaned,
            meta = meta,
            field = started.field,
            latencyMs = speech.latencyMs,
        )
        val chip = ResultChip(done.text, done.cleaned, done.notCleaned, speech.latencyMs, inserted, canSwap)
        clearTake()
        _state.value = idleState(chip)
    }

    private suspend fun noSpeech(started: Take, latencyMs: Long) {
        pending.delete(started.id)
        clearTake()
        lastInsert = null
        diag.event("no_speech")
        _state.value = idleState(ResultChip("No speech", false, false, latencyMs, false, false))
    }

    private suspend fun fail(started: Take, reason: FailReason, error: String) {
        stopLive(cancel = true)
        pending.update(started.id, error, started.durationMs)
        clearTake()
        lastInsert = null
        haptic(Haptic.ERROR)
        diag.event("take_fail", error)
        _state.value = PanelState.Failed(reason, started.id)
    }

    // ---- speculative cleanup ----

    /**
     * "Clean while I pause" (docs/spec.md §15.1): after 900 ms of a stable committed preview with no tentative text,
     * clean it once. The result is used at Final only when the recogniser's final text is identical.
     */
    private fun maybeSpeculate(committed: String, tentative: String) {
        val started = take ?: return
        val snapshot = started.settings
        val preset = started.preset ?: return
        if (!snapshot.speculativeClean || started.request.action != TakeAction.CLEAN) return
        if (committed.isBlank() || tentative.isNotEmpty() || needsRawField(started.field.kind)) return
        if (committed == specCommitted) return
        specJob?.cancel()
        specCommitted = committed
        specCleaned = null
        specJob = scope.launch {
            delay(SPECULATIVE_DELAY_MS)
            val message = Prompts.cleanMessage(preset.prompt, TextPipeline.raw(committed, snapshot))
            val result = cleaner.clean(message)
            if (result is CleanResult.Text && specCommitted == committed) specCleaned = result.text
        }
    }

    // ---- swap ----

    private suspend fun swapLast() {
        val last = lastInsert ?: return
        if (_state.value !is PanelState.Idle || last.replaceSelection) return
        val snapshot = settings()
        if (last.cleaned || last.notCleaned) {
            val raw = TextPipeline.forField(last.raw, last.field.kind, snapshot)
            if (raw.isBlank() || raw == last.inserted) {
                diag.event("swap_noop")
                return
            }
            if (inserter.swap(raw, last.meta)) {
                last.inserted = raw
                last.cleaned = false
                last.notCleaned = false
                diag.event("swap_raw")
            }
            _state.value = idleState(chipFor(last))
            return
        }

        val preset = presetFor(snapshot, null) ?: return
        _state.value = PanelState.Finishing(TakeAction.CLEAN)
        val result = cleaner.clean(Prompts.cleanMessage(preset.prompt, last.raw))
        val cleaned = if (result is CleanResult.Text && result.text.isNotBlank()) {
            TextPipeline.forField(result.text, last.field.kind, snapshot)
        } else {
            null
        }
        if (cleaned != null && inserter.swap(cleaned, last.meta)) {
            last.inserted = cleaned
            last.cleaned = true
            last.notCleaned = false
            diag.event("swap_clean")
        } else {
            last.notCleaned = true
        }
        _state.value = idleState(chipFor(last))
    }

    // ---- helpers ----

    private fun startTicker() {
        tickerJob = scope.launch {
            var elapsed = 0L
            var warned = false
            while (isActive) {
                delay(TICK_MS)
                val recording = _state.value as? PanelState.Recording ?: return@launch
                elapsed += TICK_MS
                _state.value = recording.copy(elapsedMs = elapsed)
                if (!warned && elapsed >= WARNING_MS) {
                    warned = true
                    haptic(Haptic.WARNING)
                    diag.event("take_warning")
                }
                if (elapsed >= AUTO_STOP_MS) {
                    // A separate coroutine: finishCurrent cancels the ticker, and a job must not cancel itself.
                    scope.launch { finishCurrent() }
                    return@launch
                }
            }
        }
    }

    private fun clearTake() {
        tickerJob?.cancel()
        tickerJob = null
        specJob?.cancel()
        specJob = null
        resetSpeculation()
        liveJob?.cancel()
        liveJob = null
        live = null
        finalEvent = null
        take = null
    }

    private fun resetSpeculation() {
        specCommitted = null
        specCleaned = null
    }

    private suspend fun idleState(chip: ResultChip?): PanelState.Idle =
        PanelState.Idle(chip, pending.list().size, !isOnline())

    private fun chipFor(last: LastInsert): ResultChip =
        ResultChip(last.inserted, last.cleaned, last.notCleaned, last.latencyMs, canUndo = true, canSwap = true)

    private fun presetFor(snapshot: Settings, presetId: String?): Preset? {
        val wanted = presetId ?: snapshot.defaultPresetId
        return snapshot.presets.firstOrNull { it.id == wanted }
            ?: snapshot.presets.firstOrNull()
            ?: Presets.defaults.firstOrNull()
    }

    private fun actionOf(name: String): TakeAction =
        runCatching { TakeAction.valueOf(name) }.getOrDefault(TakeAction.RAW)

    private fun fieldKindOf(name: String): FieldKind =
        runCatching { FieldKind.valueOf(name) }.getOrDefault(FieldKind.TEXT)

    /** Fields where cleanup would corrupt the value (spec §17): URL, email, number and phone are raw-only. */
    private fun needsRawField(kind: FieldKind): Boolean = when (kind) {
        FieldKind.URI, FieldKind.EMAIL, FieldKind.NUMBER, FieldKind.PHONE -> true
        FieldKind.TEXT, FieldKind.EMAIL_SUBJECT, FieldKind.PASSWORD -> false
    }

    private data class Take(
        val id: String,
        val file: File,
        val request: TakeRequest,
        val preset: Preset?,
        val settings: Settings,
    ) {
        var durationMs: Long = 0
    }

    private sealed interface Completion {
        data class Done(
            val text: String,
            val cleaned: Boolean,
            val notCleaned: Boolean,
            val presetName: String?,
            val replaceSelection: Boolean,
        ) : Completion

        data class Failed(val reason: FailReason, val error: String) : Completion
    }

    private class LastInsert(
        val raw: String,
        var inserted: String,
        var cleaned: Boolean,
        var notCleaned: Boolean,
        val meta: InsertMeta,
        val field: FieldInfo,
        val latencyMs: Long,
    ) {
        val replaceSelection: Boolean get() = meta.replaceSelection
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val WARNING_MS = 280_000L
        const val AUTO_STOP_MS = 295_000L
        const val MIN_TAKE_MS = 300L
        const val FINAL_TIMEOUT_MS = 20_000L
        const val SPECULATIVE_DELAY_MS = 900L
        const val KIND_OFFLINE = "offline"
        const val RECORDER_FAILED = -1
    }
}
