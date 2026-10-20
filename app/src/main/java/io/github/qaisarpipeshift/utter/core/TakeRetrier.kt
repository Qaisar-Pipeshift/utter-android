package io.github.qaisarpipeshift.utter.core

import io.github.qaisarpipeshift.utter.data.HistoryEntry
import io.github.qaisarpipeshift.utter.data.HistoryStore
import io.github.qaisarpipeshift.utter.data.PendingMeta
import io.github.qaisarpipeshift.utter.data.PendingStore
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.data.StatsStore
import io.github.qaisarpipeshift.utter.diag.Diag
import io.github.qaisarpipeshift.utter.net.CleanResult
import io.github.qaisarpipeshift.utter.net.SttResult
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** What a retried take produced, before the settings app decides how to present or copy it. */
sealed interface RetryOutcome {
    /** The finished text; the caller copies it to the clipboard because there is no text field. */
    data class Success(
        val text: String,
        val cleaned: Boolean,
        val notCleaned: Boolean,
        val presetName: String?,
        val latencyMs: Long,
        val words: Int,
    ) : RetryOutcome

    /** The recording contained no speech; the pending take was removed. */
    data object NoSpeech : RetryOutcome

    /** The take is still kept for another retry; [reason] drives the message. */
    data class Failed(val reason: FailReason, val detail: String = "") : RetryOutcome
}

/**
 * Retries a kept take from the settings app (docs/spec.md §15.4, docs/plan.md Task 14).
 *
 * Why a separate class instead of the IME's `DictationController`: the controller owns a live recorder, a panel
 * state machine and an input connection, none of which exist in the settings app. What must stay identical is the
 * part that produces text — upload, the raw pipeline, cleanup with the same preset, and the same history/stats
 * bookkeeping — so this class reuses [Transcriber], [Cleaner], [TextPipeline] and the stores directly. The caller
 * copies [RetryOutcome.Success.text] to the clipboard; there is no field to insert into.
 */
class TakeRetrier(
    private val transcriber: Transcriber,
    private val cleaner: Cleaner,
    private val pending: PendingStore,
    private val history: HistoryStore,
    private val stats: StatsStore,
    private val clock: Clock = Clock { System.currentTimeMillis() },
    private val diag: Diag = Diag(),
) {

    /** Runs the kept PCM through upload → pipeline → cleanup, then records history/stats on success. */
    suspend fun retry(meta: PendingMeta, settings: Settings): RetryOutcome {
        val file = pending.pcmFile(meta.id)
        if (!file.exists()) {
            quietStore { pending.delete(meta.id) }
            return RetryOutcome.Failed(FailReason.UNREACHABLE, "missing_file")
        }
        val speech = try {
            transcriber.upload(file, settings.vocabulary)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            diag.event("retry_upload_exception", failure.javaClass.simpleName)
            SttResult.Network("io")
        }
        return when (speech) {
            is SttResult.Text -> finish(meta, settings, speech)
            SttResult.NoSpeech -> {
                quietStore { pending.delete(meta.id) }
                diag.event("retry_no_speech")
                RetryOutcome.NoSpeech
            }

            SttResult.SttDown -> fail(meta, FailReason.STT_DOWN, "stt_down")
            SttResult.Unauthorized -> fail(meta, FailReason.KEY_REJECTED, "unauthorized")
            is SttResult.Http -> fail(meta, FailReason.SERVER_ERROR, "http_${speech.code}")
            is SttResult.Network -> if (speech.kind == KIND_OFFLINE) {
                fail(meta, FailReason.OFFLINE, "offline")
            } else {
                fail(meta, FailReason.UNREACHABLE, "network_${speech.kind}")
            }
        }
    }

    private suspend fun finish(meta: PendingMeta, settings: Settings, speech: SttResult.Text): RetryOutcome {
        val kind = fieldKindOf(meta.fieldKind)
        val action = actionOf(meta.action)
        val rawText = TextPipeline.raw(speech.text, settings)
        val selection = meta.selection

        if (action == TakeAction.EDIT_SELECTION) {
            if (selection.isNullOrBlank()) {
                // Without the original selection the edit would be meaningless; fail like the controller does.
                return fail(meta, FailReason.SERVER_ERROR, "edit_selection_lost")
            }
            val edited = when (val result = cleanSafely(Prompts.editMessage(rawText, selection))) {
                is CleanResult.Text -> result.text.trim()
                is CleanResult.Failed -> ""
            }
            if (edited.isBlank()) {
                return fail(meta, FailReason.SERVER_ERROR, "edit_failed")
            }
            val done = RetryOutcome.Success(
                text = edited,
                cleaned = true,
                notCleaned = false,
                presetName = null,
                latencyMs = speech.latencyMs,
                words = TextOps.wordCount(edited),
            )
            record(meta, settings, done, raw = speech.text)
            return done
        }

        if (action == TakeAction.RAW) {
            return rawDone(meta, settings, speech, rawText, kind, presetName = null, notCleaned = false)
        }

        val preset = presetFor(settings, meta.presetId)
        if (preset == null) {
            return rawDone(meta, settings, speech, rawText, kind, presetName = null, notCleaned = true)
        }
        if (needsRawField(kind)) {
            return rawDone(meta, settings, speech, rawText, kind, presetName = preset.name, notCleaned = false)
        }

        val cleaned = when (val result = cleanSafely(Prompts.cleanMessage(preset.prompt, rawText))) {
            is CleanResult.Text -> result.text
            is CleanResult.Failed -> {
                diag.event("retry_clean_failed", result.why)
                null
            }
        }
        val finalText = TextPipeline.forField(cleaned?.takeIf { it.isNotBlank() } ?: rawText, kind, settings)
        if (finalText.isBlank()) return noSpeech(meta)
        val done = RetryOutcome.Success(
            text = finalText,
            cleaned = !cleaned.isNullOrBlank(),
            notCleaned = cleaned.isNullOrBlank(),
            presetName = preset.name,
            latencyMs = speech.latencyMs,
            words = TextOps.wordCount(finalText),
        )
        record(meta, settings, done, raw = speech.text)
        return done
    }

    private suspend fun rawDone(
        meta: PendingMeta,
        settings: Settings,
        speech: SttResult.Text,
        rawText: String,
        kind: FieldKind,
        presetName: String?,
        notCleaned: Boolean,
    ): RetryOutcome {
        val finalText = TextPipeline.forField(rawText, kind, settings)
        if (finalText.isBlank()) return noSpeech(meta)
        val done = RetryOutcome.Success(
            text = finalText,
            cleaned = false,
            notCleaned = notCleaned,
            presetName = presetName,
            latencyMs = speech.latencyMs,
            words = TextOps.wordCount(finalText),
        )
        record(meta, settings, done, raw = speech.text)
        return done
    }

    private suspend fun noSpeech(meta: PendingMeta): RetryOutcome {
        quietStore { pending.delete(meta.id) }
        diag.event("retry_no_speech")
        return RetryOutcome.NoSpeech
    }

    private suspend fun fail(meta: PendingMeta, reason: FailReason, error: String): RetryOutcome {
        quietStore { pending.update(meta.id, error, meta.durationMs) }
        diag.event("retry_fail", error)
        return RetryOutcome.Failed(reason, error)
    }

    private suspend fun record(meta: PendingMeta, settings: Settings, done: RetryOutcome.Success, raw: String) {
        val rule = settings.appRules.firstOrNull { it.pkg == meta.pkg }
        val allowed = !meta.incognito && rule?.historyOff != true
        if (settings.historyOn && allowed) {
            quietStore {
                history.add(
                    HistoryEntry(
                        id = UUID.randomUUID().toString(),
                        ts = clock.now(),
                        pkg = meta.pkg,
                        appLabel = settings.seenApps[meta.pkg] ?: meta.pkg,
                        raw = raw,
                        finalText = done.text,
                        action = meta.action,
                        presetName = done.presetName,
                        durationMs = meta.durationMs,
                        latencyMs = done.latencyMs,
                        words = done.words,
                        audioId = meta.id,
                    ),
                )
            }
            quietStore { history.prune(settings.historyLimit) }
        }
        if (allowed) {
            quietStore { stats.record(meta.pkg, done.words, meta.durationMs) }
        }
        if (settings.keepAudioDays > 0) {
            quietStore { pending.keepAudio(meta.id, settings.keepAudioDays) }
        } else {
            quietStore { pending.delete(meta.id) }
        }
    }

    private suspend fun cleanSafely(message: String): CleanResult =
        try {
            cleaner.clean(message)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            diag.event("retry_clean_exception", failure.javaClass.simpleName)
            CleanResult.Failed("exception")
        }

    private suspend fun quietStore(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            diag.event("retry_store_error")
        }
    }

    private fun presetFor(settings: Settings, presetId: String?): Preset? {
        val wanted = presetId ?: settings.defaultPresetId
        return settings.presets.firstOrNull { it.id == wanted }
            ?: settings.presets.firstOrNull()
            ?: Presets.defaults.firstOrNull()
    }

    private fun actionOf(name: String): TakeAction =
        runCatching { TakeAction.valueOf(name) }.getOrDefault(TakeAction.RAW)

    private fun fieldKindOf(name: String): FieldKind =
        runCatching { FieldKind.valueOf(name) }.getOrDefault(FieldKind.TEXT)

    /** Fields where cleanup would corrupt the value (spec §17). */
    private fun needsRawField(kind: FieldKind): Boolean = when (kind) {
        FieldKind.URI, FieldKind.EMAIL, FieldKind.NUMBER, FieldKind.PHONE -> true
        FieldKind.TEXT, FieldKind.EMAIL_SUBJECT, FieldKind.PASSWORD -> false
    }

    private companion object {
        const val KIND_OFFLINE = "offline"
    }
}
