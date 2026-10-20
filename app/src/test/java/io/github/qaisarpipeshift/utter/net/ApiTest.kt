package io.github.qaisarpipeshift.utter.net

import java.io.File
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiTest {

    private lateinit var server: MockWebServer
    private lateinit var pcm: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        pcm = File.createTempFile("take", ".pcm").apply {
            writeBytes("ABCD".toByteArray())
            deleteOnExit()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(http: Http = Http(OkHttpClient(), allowCleartext = true)): Api =
        Api(http) { ServerConfig(server.url("/").toString(), KEY) }

    // ---- upload ----

    @Test
    fun `upload sends config then audio with the bearer key`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"Hello","llm_error":null}"""))

        val result = api().upload(pcm, listOf("Utter"))

        val text = result as SttResult.Text
        assertEquals("Hello", text.text)

        val request = server.takeRequest()!!
        assertEquals("/v1/transcribe/live", request.path)
        assertEquals("Bearer $KEY", request.headers["Authorization"])
        val body = request.body?.readUtf8().orEmpty()
        assertTrue(body.indexOf("name=\"config\"") < body.indexOf("name=\"audio\""))
        assertTrue(body.contains("Content-Type: application/json"))
        assertTrue(body.contains("\"sample_rate\":16000"))
        assertTrue(body.contains("\"channels\":1"))
        assertTrue(body.contains("\"llm\":null"))
        assertTrue(body.contains("\"keyterms_prompt\":[\"Utter\"]"))
        assertTrue(body.contains("Content-Type: audio/pcm"))
        assertTrue(body.contains("filename=\"audio.pcm\""))
        assertTrue(body.contains("ABCD"))
    }

    @Test
    fun `upload without vocabulary omits the keyterms hint`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"Hi"}"""))

        api().upload(pcm, emptyList())

        val body = server.takeRequest()!!.body?.readUtf8().orEmpty()
        assertFalse(body.contains("keyterms_prompt"))
    }

    @Test
    fun `no speech in a 200 body is not inserted`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"","llm_error":"no speech"}"""))

        assertEquals(SttResult.NoSpeech, api().upload(pcm, emptyList()))
    }

    @Test
    fun `speech-to-text unavailable keeps the take for retry`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"text":"placeholder","llm_error":"speech-to-text unavailable"}"""),
        )

        assertEquals(SttResult.SttDown, api().upload(pcm, emptyList()))
    }

    @Test
    fun `a blank transcript without an error is no speech`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"  "}"""))

        assertEquals(SttResult.NoSpeech, api().upload(pcm, emptyList()))
    }

    @Test
    fun `401 and 403 are unauthorized`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(SttResult.Unauthorized, api().upload(pcm, emptyList()))

        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(SttResult.Unauthorized, api().upload(pcm, emptyList()))
    }

    @Test
    fun `other statuses keep their code`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        assertEquals(SttResult.Http(500), api().upload(pcm, emptyList()))
    }

    @Test
    fun `a timeout is reported as a network timeout`() = runBlocking {
        val failing = OkHttpClient.Builder()
            .addInterceptor { throw SocketTimeoutException("simulated") }
            .build()

        val result = api(Http(failing, allowCleartext = true)).upload(pcm, emptyList())

        assertEquals(SttResult.Network("timeout"), result)
    }

    @Test
    fun `a refused connection is reported as connect`() {
        val dead = MockWebServer()
        dead.start()
        val url = dead.url("/").toString()
        dead.shutdown()
        val api = Api(Http(OkHttpClient(), allowCleartext = true)) { ServerConfig(url, KEY) }

        val result = runBlocking { api.upload(pcm, emptyList()) }

        assertEquals(SttResult.Network("connect"), result)
    }

    // ---- transcribeFile ----

    @Test
    fun `transcribe file posts the file with model and json format`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"text":"note text"}"""))

        val result = api().transcribeFile("abc".toByteArray(), "voice.ogg", "audio/ogg")

        assertEquals("note text", (result as SttResult.Text).text)
        val request = server.takeRequest()!!
        assertEquals("/v1/audio/transcriptions", request.path)
        val body = request.body?.readUtf8().orEmpty()
        assertTrue(body.contains("filename=\"voice.ogg\""))
        assertTrue(body.contains("name=\"model\""))
        assertTrue(body.contains("qwen3-asr"))
        assertTrue(body.contains("name=\"response_format\""))
    }

    // ---- clean ----

    @Test
    fun `clean sends one user message and strips think blocks`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"choices":[{"message":{"content":"<think>plan</think>Hello"}}]}"""),
        )

        val result = api().clean("cleanup", "do it", 15_000L)

        assertEquals(CleanResult.Text("Hello"), result)
        val request = server.takeRequest()!!
        assertEquals("/v1/chat/completions", request.path)
        val json = JSONObject(request.body?.readUtf8().orEmpty())
        assertEquals("cleanup", json.getString("model"))
        val message = json.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        assertEquals("do it", message.getString("content"))
        assertFalse(json.has("temperature"))
        assertFalse(json.has("stream"))
    }

    @Test
    fun `empty clean output is a failure`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"   "}}]}"""))

        assertEquals(CleanResult.Failed("empty"), api().clean("cleanup", "do it", 15_000L))
    }

    @Test
    fun `clean http error is a failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        assertEquals(CleanResult.Failed("http 503"), api().clean("cleanup", "do it", 15_000L))
    }

    // ---- probes ----

    @Test
    fun `check key maps 200 401 and other`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(api().checkKey().ok)

        server.enqueue(MockResponse().setResponseCode(401))
        val rejected = api().checkKey()
        assertFalse(rejected.ok)
        assertEquals("Key rejected", rejected.detail)

        server.enqueue(MockResponse().setResponseCode(500))
        val broken = api().checkKey()
        assertFalse(broken.ok)
        assertEquals("Server error HTTP 500", broken.detail)
    }

    @Test
    fun `warm sends no auth header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        assertTrue(api().warm().ok)
        val request = server.takeRequest()!!
        assertEquals("/warm", request.path)
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun `models lists the ids`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"cleanup"},{"id":"qwen3-asr"}]}"""))

        val ids = api().models().getOrThrow()

        assertEquals(listOf("cleanup", "qwen3-asr"), ids)
    }

    @Test
    fun `live probe succeeds once the session is updated`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                ClosingListener(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (JSONObject(text).optString("type") == "session.update") {
                            webSocket.send("""{"type":"session.updated"}""")
                        }
                    }
                }),
            ),
        )

        val probe = api().probeLive()

        assertTrue(probe.detail, probe.ok)
    }

    // ---- base URL normalisation ----

    @Test
    fun `base url normalisation table`() {
        assertEquals("https://h", normalizeBaseUrl("https://h/"))
        assertEquals("https://h", normalizeBaseUrl("https://h/v1"))
        assertEquals("https://h", normalizeBaseUrl("https://h/v1/"))
        assertEquals("https://h", normalizeBaseUrl("  https://h  "))
        assertEquals("https://h/api", normalizeBaseUrl("https://h/api/"))
        assertEquals("http://h:8080", normalizeBaseUrl("http://h:8080/", allowCleartext = true))
        assertNull(normalizeBaseUrl("http://h"))
        assertNull(normalizeBaseUrl("ftp://h"))
        assertNull(normalizeBaseUrl(""))
        assertNull(normalizeBaseUrl("   "))
        assertNull(normalizeBaseUrl("https://"))
    }

    private companion object {
        const val KEY = "test-key"
    }
}
