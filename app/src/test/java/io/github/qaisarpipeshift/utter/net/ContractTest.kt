package io.github.qaisarpipeshift.utter.net

import io.github.qaisarpipeshift.utter.core.Presets
import io.github.qaisarpipeshift.utter.core.Prompts
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Opt-in proof that the app talks to a real dictation server (docs/spec.md §12, docs/plan.md Task 16).
 *
 * Why skipped by default: every other test is hermetic, and CI must stay green without a deployment. The test runs
 * only when `UTTER_TEST_URL` and `UTTER_TEST_KEY` are set, which the contract job does with repository secrets.
 * Nothing here prints the key, the URL or the recognised text: an assertion names the failure kind, never the data.
 *
 * Why upload-only and not a live-socket leg: `Api.openLive` declares 16 kHz, while the server's TTS answers at
 * 24 kHz and resampling inside the test would test the resampler, not the app. Widening the production signature for
 * a test is not worth it, so the live socket is covered by `LiveSessionTest` against a fake server and the real
 * server contract is proven through the same upload path the keyboard falls back to.
 */
class ContractTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var api: Api

    @Before
    fun requireServer() {
        val url = System.getenv(ENV_URL)?.trim().orEmpty()
        val key = System.getenv(ENV_KEY)?.trim().orEmpty()
        Assume.assumeTrue(
            "$ENV_URL and $ENV_KEY must be set to run the contract test",
            url.isNotEmpty() && key.isNotEmpty(),
        )
        // Cleartext is allowed so a local dev server can be the target; the app itself still refuses it.
        api = Api(Http(allowCleartext = true)) { ServerConfig(url, key) }
    }

    @Test
    fun `key check and warm probe succeed`() = runBlocking {
        val key = api.checkKey()
        assertTrue("key check failed: ${key.detail}", key.ok)

        val warm = api.warm()
        assertTrue("warm probe failed: ${warm.detail}", warm.ok)
    }

    @Test
    fun `models endpoint lists at least one model`() = runBlocking {
        val models = api.models().getOrThrow()
        assertTrue("models list was empty", models.isNotEmpty())
    }

    @Test
    fun `server speech transcribes back to the spoken words`() = runBlocking {
        val pcm = speak("testing one two three")
        val take = folder.newFile("tts.pcm").apply { writeBytes(pcm) }

        val result = api.upload(take, emptyList())

        assertTrue("expected a transcript, got ${result.javaClass.simpleName}", result is SttResult.Text)
        val text = (result as SttResult.Text).text
        assertTrue("transcript did not contain the spoken word", text.contains("testing", ignoreCase = true))
    }

    @Test
    fun `one second of silence is no speech`() = runBlocking {
        val silence = folder.newFile("silence.pcm").apply {
            writeBytes(ByteArray(SILENCE_SECONDS * SAMPLE_RATE * BYTES_PER_SAMPLE))
        }

        val result = api.upload(silence, emptyList())

        // Compare by kind, not by printing the result: a regression must not put recognised text in CI logs.
        assertTrue("expected no speech, got ${result.javaClass.simpleName}", result === SttResult.NoSpeech)
    }

    @Test
    fun `cleanup returns non-empty text`() = runBlocking {
        val model = api.models().getOrNull()?.firstOrNull() ?: DEFAULT_CLEAN_MODEL
        val preset = Presets.defaults.first { it.id == Presets.CLEAN_ID }

        val result = api.clean(model, Prompts.cleanMessage(preset.prompt, SAMPLE_UTTERANCE), CLEAN_TIMEOUT_MS)

        assertTrue("cleanup failed: ${(result as? CleanResult.Failed)?.why.orEmpty()}", result is CleanResult.Text)
        assertTrue("cleanup returned blank text", (result as CleanResult.Text).text.isNotBlank())
    }

    /** Records the server's own TTS output so the contract needs no audio fixture in the repository. */
    private suspend fun speak(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        var sampleRate = 0
        val result = api.speech(text, voice()) { chunk, rate ->
            sampleRate = rate
            out.write(chunk)
        }
        assertTrue(
            "speech request failed: ${result.exceptionOrNull()?.javaClass?.simpleName}",
            result.isSuccess,
        )
        assertTrue("speech returned no audio", out.size() > 0)
        assertTrue("speech did not report a sample rate", sampleRate > 0)
        return out.toByteArray()
    }

    private fun voice(): String =
        System.getenv(ENV_VOICE)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_VOICE

    private companion object {
        const val ENV_URL = "UTTER_TEST_URL"
        const val ENV_KEY = "UTTER_TEST_KEY"
        const val ENV_VOICE = "UTTER_TEST_VOICE"

        const val DEFAULT_VOICE = "aiden"
        const val DEFAULT_CLEAN_MODEL = "cleanup"
        const val SAMPLE_UTTERANCE = "um, hello world"
        const val CLEAN_TIMEOUT_MS = 60_000L
        const val SILENCE_SECONDS = 1
        const val SAMPLE_RATE = 16_000
        const val BYTES_PER_SAMPLE = 2
    }
}
