package io.github.qaisarpipeshift.utter.core

/**
 * Everything the panel can show (docs/spec.md §4, docs/plan.md §2).
 *
 * Why a sealed hierarchy of immutable values: the controller emits one state per transition and Compose renders it
 * directly, so there is no second source of truth to keep in sync. [SetupNeeded], [MicPermissionNeeded] and
 * [SecureField] are decided by the IME; the controller only emits [Idle], [Recording], [Finishing] and [Failed].
 */
sealed interface PanelState {
    data object SetupNeeded : PanelState
    data object MicPermissionNeeded : PanelState
    data object SecureField : PanelState

    /** Nothing in flight; [result] is the last outcome chip, [pendingCount] the retry queue, [offline] the last check. */
    data class Idle(val result: ResultChip?, val pendingCount: Int, val offline: Boolean) : PanelState

    /**
     * Mic open. [committed] and [tentative] come from the live socket; [elapsedMs] and [amplitude] drive the timer
     * and waveform. [locked] means the user slid up to hands-free; [holding] means push-to-talk is still held.
     */
    data class Recording(
        val action: TakeAction,
        val locked: Boolean,
        val holding: Boolean,
        val elapsedMs: Long,
        val amplitude: Float,
        val committed: String,
        val tentative: String,
    ) : PanelState

    /** Mic released, waiting for STT, cleanup and insertion. */
    data class Finishing(val action: TakeAction) : PanelState

    /**
     * The take is kept in [takeId] for Retry; [reason] is what the panel says. [takeId] is empty when there is
     * nothing to retry (the recorder never opened) and [code] then carries the Android error code to show.
     */
    data class Failed(val reason: FailReason, val takeId: String, val code: Int? = null) : PanelState
}

/**
 * The last insert's result chip (docs/spec.md §4, §17).
 *
 * [cleaned] and [notCleaned] are separate because "cleaned" is a success and "not cleaned" is the hint shown when
 * cleanup failed but the raw text was still inserted. [canUndo] and [canSwap] are the IME's checks at insert time.
 * [noSpeech] marks the "No speech" notice, which is not a result: [text] is empty and the panel shows the notice.
 */
data class ResultChip(
    val text: String,
    val cleaned: Boolean,
    val notCleaned: Boolean,
    val latencyMs: Long,
    val canUndo: Boolean,
    val canSwap: Boolean,
    val noSpeech: Boolean = false,
)

/** Why a take failed (docs/spec.md §8). No speech is not a failure; [TOO_SHORT] is reserved for the IME's short-tap hint. */
enum class FailReason { OFFLINE, UNREACHABLE, KEY_REJECTED, STT_DOWN, SERVER_ERROR, RECORDER, TOO_SHORT }

/**
 * Haptic signals the controller asks for (docs/spec.md §15.1). The IME maps each value to
 * `HapticFeedbackConstants`; the controller never touches a `View`.
 */
enum class Haptic { PRESS, START, STOP, CANCEL, LOCK, WARNING, ERROR }
