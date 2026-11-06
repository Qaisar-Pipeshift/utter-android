package io.github.qaisarpipeshift.utter.net

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The `/v1/pulse` wire contract (docs/spec.md §18.2, matching `server/pulse_server.py`): gzip NDJSON under
 * `X-API-Key`, `200 {"accepted":n,"duplicates":m}`, 413 as the "split me" signal.
 */
class PostPulseTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(configured: Boolean = true): Api =
        Api(Http(OkHttpClient(), allowCleartext = true)) {
            if (configured) ServerConfig(server.url("/").toString(), KEY) else null
        }

    private fun postedBody(request: okhttp3.mockwebserver.RecordedRequest): String {
        val bytes = request.body?.readByteArray() ?: ByteArray(0)
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { stream -> stream.readBytes().decodeToString() }
    }

    @Test
    fun `pulse posts gzipped ndjson under the x-api-key header`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"accepted":2,"duplicates":1}"""))

        val result = api().postPulse("{\"id\":\"a\"}\n{\"id\":\"b\"}\n".toByteArray())

        assertEquals(PulsePost.Ok(accepted = 2, duplicates = 1), result)
        val request = server.takeRequest()!!
        assertEquals("POST", request.method)
        assertEquals("/v1/pulse", request.path)
        assertEquals(KEY, request.headers["X-API-Key"])
        assertEquals("gzip", request.headers["Content-Encoding"])
        assertEquals("application/x-ndjson", request.headers["Content-Type"])
        assertEquals("{\"id\":\"a\"}\n{\"id\":\"b\"}\n", postedBody(request))
    }

    @Test
    fun `413 asks for a split`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(413))

        assertEquals(PulsePost.TooLarge, api().postPulse("{\"id\":\"a\"}\n".toByteArray()))
    }

    @Test
    fun `other statuses keep their code`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(PulsePost.Rejected(401), api().postPulse("{\"id\":\"a\"}\n".toByteArray()))
    }

    @Test
    fun `a bad 200 body counts as zero accepted`() = runBlocking {
        server.enqueue(MockResponse().setBody("not json"))

        assertEquals(PulsePost.Ok(0, 0), api().postPulse(ByteArray(0)))
    }

    @Test
    fun `no server config keeps the batches queued`() = runBlocking {
        assertEquals(PulsePost.Network("config"), api(configured = false).postPulse(ByteArray(0)))
    }

    private companion object {
        const val KEY = "test-key"
    }
}
