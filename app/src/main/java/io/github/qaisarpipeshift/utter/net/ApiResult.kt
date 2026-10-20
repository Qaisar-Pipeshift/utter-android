package io.github.qaisarpipeshift.utter.net

/**
 * Outcome of one speech-to-text call (docs/spec.md §6, docs/plan.md §2).
 *
 * [NoSpeech] and [SttDown] are separated from [Http] because the server reports both inside a 200 response body and
 * the controller treats them differently: no speech is dropped silently, STT down keeps the take for Retry.
 */
sealed interface SttResult {
    /** Recognised text; [latencyMs] is measured client-side from request start to parsed response. */
    data class Text(val text: String, val latencyMs: Long) : SttResult

    /** Silence, or a take too short to contain speech. */
    data object NoSpeech : SttResult

    /** The server answered `llm_error == "speech-to-text unavailable"`; `text` in that response is a placeholder. */
    data object SttDown : SttResult

    /** 401/403 — the key is missing, wrong, or revoked. */
    data object Unauthorized : SttResult

    /** Any other non-2xx status. */
    data class Http(val code: Int) : SttResult

    /** The request never produced a response; [kind] is one of `timeout`, `dns`, `connect`, `io`, or `config`. */
    data class Network(val kind: String) : SttResult
}

/** Outcome of one cleanup call. A failure is never fatal: the controller inserts the raw text and says so. */
sealed interface CleanResult {
    data class Text(val text: String) : CleanResult
    data class Failed(val why: String) : CleanResult
}

/** One settings-screen probe: success, elapsed milliseconds, and a short human-readable detail. */
data class Probe(val ok: Boolean, val ms: Long, val detail: String)
