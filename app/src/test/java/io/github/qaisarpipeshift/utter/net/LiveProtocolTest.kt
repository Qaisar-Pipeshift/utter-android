package io.github.qaisarpipeshift.utter.net

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveProtocolTest {

    @Test
    fun `session update carries the GA realtime shape`() {
        val json = JSONObject(LiveProtocol.sessionUpdate())
        assertEquals("session.update", json.getString("type"))
        val session = json.getJSONObject("session")
        assertEquals("transcription", session.getString("type"))
        val input = session.getJSONObject("audio").getJSONObject("input")
        assertEquals("audio/pcm", input.getJSONObject("format").getString("type"))
        assertEquals(16_000, input.getJSONObject("format").getInt("rate"))
        assertEquals("qwen3-asr", input.getJSONObject("transcription").getString("model"))
        assertTrue(input.isNull("turn_detection"))
    }

    @Test
    fun `session update includes the vocabulary prompt only when given`() {
        val withPrompt = JSONObject(LiveProtocol.sessionUpdate(prompt = "Utter, Pipeshift"))
        val transcription = withPrompt.getJSONObject("session")
            .getJSONObject("audio")
            .getJSONObject("input")
            .getJSONObject("transcription")
        assertEquals("Utter, Pipeshift", transcription.getString("prompt"))

        val withoutPrompt = JSONObject(LiveProtocol.sessionUpdate(prompt = "  "))
        val emptyTranscription = withoutPrompt.getJSONObject("session")
            .getJSONObject("audio")
            .getJSONObject("input")
            .getJSONObject("transcription")
        assertFalse(emptyTranscription.has("prompt"))
    }

    @Test
    fun `append base64 encodes raw pcm without wrapping`() {
        val pcm = ByteArray(200) { index -> index.toByte() }
        val json = JSONObject(LiveProtocol.append(pcm))
        assertEquals("input_audio_buffer.append", json.getString("type"))
        val audio = json.getString("audio")
        assertFalse(audio.contains("\n"))
        assertArrayEquals(pcm, Base64.getDecoder().decode(audio))
    }

    @Test
    fun `commit and clear frames match the protocol`() {
        assertEquals("input_audio_buffer.commit", JSONObject(LiveProtocol.COMMIT).getString("type"))
        assertEquals("input_audio_buffer.clear", JSONObject(LiveProtocol.CLEAR).getString("type"))
    }

    @Test
    fun `preview keeps committed and tentative apart`() {
        val event = LiveProtocol.parse(
            """{"type":"transcription.preview","committed":"hello ","tentative":"wor"}""",
        )
        assertEquals(LiveEvent.Preview("hello ", "wor"), event)
    }

    @Test
    fun `completed transcript becomes the final text`() {
        val event = LiveProtocol.parse(
            """{"type":"conversation.item.input_audio_transcription.completed","transcript":" Hello there "}""",
        )
        assertEquals(LiveEvent.Final("Hello there"), event)
    }

    @Test
    fun `blank completion is no speech`() {
        val event = LiveProtocol.parse(
            """{"type":"conversation.item.input_audio_transcription.completed","transcript":"   "}""",
        )
        assertEquals(LiveEvent.NoSpeech, event)
    }

    @Test
    fun `buffer too small error after commit is no speech`() {
        val event = LiveProtocol.parse(
            """{"type":"error","error":{"message":"Audio buffer too small to transcribe"}}""",
        )
        assertEquals(LiveEvent.NoSpeech, event)
    }

    @Test
    fun `a server error becomes a fixed kind and never carries the server text`() {
        // A real server message can hold ids, URLs or request data; Diagnostics and logcat must never see it.
        val event = LiveProtocol.parse(
            """{"type":"error","error":{"message":"model crashed at https://internal.example/?k=secret"}}""",
        )
        assertEquals(LiveEvent.Failed(LiveProtocol.FAIL_SERVER), event)
    }

    @Test
    fun `transcription failed event is a fixed kind`() {
        val event = LiveProtocol.parse(
            """{"type":"conversation.item.input_audio_transcription.failed","error":{"message":"decode"}}""",
        )
        assertEquals(LiveEvent.Failed(LiveProtocol.FAIL_OTHER), event)
    }

    @Test
    fun `unknown frames and malformed json are ignored`() {
        assertNull(LiveProtocol.parse("""{"type":"session.updated"}"""))
        assertNull(LiveProtocol.parse("""{"type":"input_audio_buffer.committed"}"""))
        assertNull(LiveProtocol.parse("not json"))
        assertNull(LiveProtocol.parse("{}"))
    }
}
