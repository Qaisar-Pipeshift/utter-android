package io.github.qaisarpipeshift.utter.net

import java.util.Base64
import org.json.JSONObject

/**
 * Events the live socket can produce, as the controller sees them (docs/plan.md Task 4/5).
 *
 * [LiveEvent] lives with the protocol rather than with the socket because [LiveProtocol.parse] builds it, and the
 * parser is pure JVM code with no OkHttp dependency.
 */
sealed interface LiveEvent {
    /** In-progress transcript: [committed] is stable, [tentative] may still change. */
    data class Preview(val committed: String, val tentative: String) : LiveEvent

    /** Final transcript for the take. Never blank — blank completions become [NoSpeech]. */
    data class Final(val text: String) : LiveEvent

    /** The server heard nothing (empty completion, or its "buffer too small" error after commit). */
    data object NoSpeech : LiveEvent

    /** Protocol or transport failure; [why] is a short kind, never transcript text. */
    data class Failed(val why: String) : LiveEvent
}

/**
 * Pure encoder/decoder for the live transcription socket (docs/spec.md §6, docs/plan.md Task 4).
 *
 * Why pure: the exact JSON shape is the contract with an unpatched server, so it is built and parsed here and unit
 * tested on the JVM; [LiveSession] only moves strings over the wire.
 */
object LiveProtocol {
    const val MODEL = "qwen3-asr"
    const val SAMPLE_RATE = 16_000

    const val COMMIT = """{"type":"input_audio_buffer.commit"}"""
    const val CLEAR = """{"type":"input_audio_buffer.clear"}"""

    private const val PREVIEW = "transcription.preview"
    private const val COMPLETED = "conversation.item.input_audio_transcription.completed"
    private const val TRANSCRIPTION_FAILED = "conversation.item.input_audio_transcription.failed"
    private const val BUFFER_TOO_SMALL = "buffer too small"

    /**
     * `session.update` in the GA realtime shape. [prompt] is the vocabulary hint and is omitted when blank — the
     * server rejects an empty prompt string on some builds, and there is nothing to bias towards anyway.
     */
    fun sessionUpdate(rate: Int = SAMPLE_RATE, prompt: String? = null): String {
        val transcription = JSONObject().put("model", MODEL)
        if (!prompt.isNullOrBlank()) transcription.put("prompt", prompt)
        val input = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", rate))
            .put("transcription", transcription)
            .put("turn_detection", JSONObject.NULL)
        val session = JSONObject()
            .put("type", "transcription")
            .put("audio", JSONObject().put("input", input))
        return JSONObject().put("type", "session.update").put("session", session).toString()
    }

    /** One 100 ms chunk as `input_audio_buffer.append`, base64 without line wrapping. */
    fun append(pcm16le: ByteArray): String = JSONObject()
        .put("type", "input_audio_buffer.append")
        .put("audio", Base64.getEncoder().encodeToString(pcm16le))
        .toString()

    /**
     * Maps one server frame to an event, or null for frames the controller does not care about. A "buffer too small"
     * error is only raised by the server after a commit on a silent take, so it means no speech, not a failure.
     */
    fun parse(json: String): LiveEvent? {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        return when (root.optString("type")) {
            PREVIEW -> LiveEvent.Preview(root.optString("committed"), root.optString("tentative"))

            COMPLETED -> {
                val transcript = root.optString("transcript").trim()
                if (transcript.isEmpty()) LiveEvent.NoSpeech else LiveEvent.Final(transcript)
            }

            TRANSCRIPTION_FAILED -> LiveEvent.Failed(errorMessage(root))

            "error" -> {
                val message = errorMessage(root)
                if (message.contains(BUFFER_TOO_SMALL, ignoreCase = true)) {
                    LiveEvent.NoSpeech
                } else {
                    LiveEvent.Failed(message)
                }
            }

            else -> null
        }
    }

    private fun errorMessage(root: JSONObject): String = root.optJSONObject("error")
        ?.optString("message")
        .orEmpty()
        .ifBlank { root.optString("message") }
        .ifBlank { "error" }
}
