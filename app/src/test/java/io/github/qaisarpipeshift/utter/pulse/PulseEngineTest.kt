package io.github.qaisarpipeshift.utter.pulse

import io.github.qaisarpipeshift.utter.core.PulseIds
import io.github.qaisarpipeshift.utter.core.PulseSample
import io.github.qaisarpipeshift.utter.data.PulseStore
import io.github.qaisarpipeshift.utter.data.PulseMeta
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.diag.Diag
import io.github.qaisarpipeshift.utter.net.Api
import io.github.qaisarpipeshift.utter.net.Http
import io.github.qaisarpipeshift.utter.net.ServerConfig
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Trigger, throttle and push behaviour of the flush engine (docs/spec.md §18.2): the four triggers are the only
 * schedule, the sample throttle is `pulseMinIntervalMin`, pushes are idempotent (ids stay stable) and split on 413,
 * and every failure keeps the batches.
 */
class PulseEngineTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: PulseStore
    private var sampleCount = 0
    private var now = 1_800_000_000_000
    private var unmeteredNow = true
    private var settings = Settings()
    private var idCounter = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = PulseStore(folder.newFolder("pulse"), ids())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun ids(): PulseIds = PulseIds(device = "d", newId = { "id-${++idCounter}" }, clock = { now })

    private fun sampler(records: List<JSONObject> = emptyList(), cursor: Long = 0, dailyDay: Long = 0, netDay: Long = 0): PulseSampling =
        PulseSampling {
            sampleCount++
            PulseSample(records, cursor, dailyDay, netDay)
        }

    private fun engine(sampling: PulseSampling = sampler()): PulseEngine = PulseEngine(
        store = store,
        sampler = sampling,
        api = Api(Http(OkHttpClient(), allowCleartext = true)) {
            ServerConfig(server.url("/").toString(), "test-key")
        },
        settings = { settings },
        unmetered = { unmeteredNow },
        diag = Diag(),
        clock = { now },
        sdkInt = { 35 },
    )

    private fun record(id: String): JSONObject = JSONObject().put("id", id).put("type", "usage_event").put("device", "d").put("at", now)

    private fun bodyLines(request: okhttp3.mockwebserver.RecordedRequest): Int {
        val bytes = request.body?.readByteArray() ?: ByteArray(0)
        val text = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().decodeToString() }
        return text.split("\n").count { it.isNotBlank() }
    }

    private fun closedBatchLines(): List<List<String>> = runBlocking {
        store.closedBatches().map { store.readBatch(it) }
    }

    // ---- triggers and gating ----

    @Test
    fun `pulse disabled samples and pushes nothing`() = runBlocking {
        settings = settings.copy(pulseEnabled = false)
        store.append(listOf(record("a")))
        store.closeBatch()

        engine(sampler()).onTrigger(PulseEngine.TRIGGER_TAKE_FINISHED)

        assertEquals(0, sampleCount)
        assertEquals(0, server.requestCount)
        assertTrue(store.closedBatches().isNotEmpty()) // batch kept, not deleted
    }

    @Test
    fun `below android 15 the engine is inert`() = runBlocking {
        store.append(listOf(record("a")))
        store.closeBatch()
        val inert = PulseEngine(
            store, sampler(), Api(Http(OkHttpClient(), allowCleartext = true)) { ServerConfig("http://x/", "k") },
            { settings }, { true }, Diag(), { now }, sdkInt = { 34 },
        )

        inert.onTrigger(PulseEngine.TRIGGER_APP_OPENED)

        assertEquals(0, sampleCount)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the sample throttle holds until pulseMinIntervalMin has passed`() = runBlocking {
        settings = settings.copy(pulseMinIntervalMin = 15)
        val engine = engine()

        engine.onTrigger(PulseEngine.TRIGGER_NOTIFICATION)
        now += 14 * 60_000
        engine.onTrigger(PulseEngine.TRIGGER_NOTIFICATION)
        assertEquals(1, sampleCount)

        now += 16 * 60_000
        engine.onTrigger(PulseEngine.TRIGGER_NOTIFICATION)
        assertEquals(2, sampleCount)
    }

    @Test
    fun `cursors advance only after the records are written`() = runBlocking {
        settings = settings.copy(pulseMinIntervalMin = 1)
        val sample = PulseSample(listOf(record("a")), usageCursor = 999L, dailyDay = 20_000, netDay = 20_001)

        engine(sampler(sample.records, 999L, 20_000, 20_001)).onTrigger(PulseEngine.TRIGGER_NOTIFICATION)

        assertEquals(PulseMeta(999L, 20_000, 20_001, now), store.meta())
    }

    @Test
    fun `a take-finished trigger closes the open batch`() = runBlocking {
        store.append(listOf(record("a")))

        engine().onTrigger(PulseEngine.TRIGGER_TAKE_FINISHED)

        assertEquals(1, closedBatchLines().size)
    }

    @Test
    fun `a notification trigger leaves the open batch open`() = runBlocking {
        store.append(listOf(record("a")))

        engine().onTrigger(PulseEngine.TRIGGER_NOTIFICATION)

        assertTrue(closedBatchLines().isEmpty())
    }

    // ---- push ----

    @Test
    fun `a successful push deletes the batch and reports accepted`() = runBlocking {
        store.append(listOf(record("a"), record("b")))
        store.closeBatch()
        server.enqueue(MockResponse().setBody("""{"accepted":2,"duplicates":0}"""))

        engine().onTrigger(PulseEngine.TRIGGER_APP_OPENED)

        assertEquals(1, server.requestCount)
        val request = server.takeRequest()!!
        assertEquals("/v1/pulse", request.path)
        assertEquals("test-key", request.headers["X-API-Key"])
        val body = GZIPInputStream(ByteArrayInputStream(request.body?.readByteArray() ?: ByteArray(0)))
            .use { it.readBytes().decodeToString() }
        assertTrue(body.contains("\"id\":\"a\""))
        assertTrue(body.contains("\"id\":\"b\""))
        assertTrue(closedBatchLines().isEmpty())
    }

    @Test
    fun `413 splits the batch and retries the halves`() = runBlocking {
        store.append(List(6) { record("r$it") })
        store.closeBatch()
        server.enqueue(MockResponse().setResponseCode(413))
        server.enqueue(MockResponse().setBody("""{"accepted":3,"duplicates":0}"""))
        server.enqueue(MockResponse().setBody("""{"accepted":3,"duplicates":0}"""))

        engine().onTrigger(PulseEngine.TRIGGER_LISTENER_CONNECTED)

        assertEquals(3, server.requestCount)
        val request = server.takeRequest()!!
        assertEquals(6, bodyLines(request)) // the first attempt was the whole batch
        assertEquals(3, bodyLines(server.takeRequest()!!)) // then the engine split it
        assertEquals(3, bodyLines(server.takeRequest()!!))
        assertTrue(closedBatchLines().isEmpty())
    }

    @Test
    fun `a single record over the wire cap is dropped, not retried forever`() = runBlocking {
        val big = record("big").put("pad", "x".repeat(600 * 1024)) // > the 512 KiB pre-gzip cap alone
        store.append(listOf(record("a"), big, record("c")))
        store.closeBatch()
        server.enqueue(MockResponse().setBody("""{"accepted":1,"duplicates":0}""")) // [a]
        server.enqueue(MockResponse().setResponseCode(413)) // [big] alone, still too large

        engine().onTrigger(PulseEngine.TRIGGER_APP_OPENED)

        assertEquals(2, server.requestCount)
        assertEquals(listOf("a", "c"), closedBatchLines().single()) // big dropped, the rest kept

        now += 15 * 60_000 + 1 // the failed push waits out the interval before retrying
        server.enqueue(MockResponse().setBody("""{"accepted":2,"duplicates":1}"""))
        engine().onTrigger(PulseEngine.TRIGGER_TAKE_FINISHED)
        assertTrue(closedBatchLines().isEmpty()) // the batch emptied out and was deleted
        store.closeBatch()
        val marker = closedBatchLines().single().single()
        assertTrue(marker.contains("store_dropped"))
        assertTrue(JSONObject(marker).getLong("bytes") > 512 * 1024)
    }

    @Test
    fun `a failed push waits out the interval instead of retrying per notification`() = runBlocking {
        store.append(listOf(record("a")))
        store.closeBatch()
        server.enqueue(MockResponse().setResponseCode(500))

        engine().onTrigger(PulseEngine.TRIGGER_NOTIFICATION)
        assertEquals(1, server.requestCount)

        store.append(listOf(record("b")))
        store.closeBatch()
        now += 5 * 60_000
        engine().onTrigger(PulseEngine.TRIGGER_NOTIFICATION)
        assertEquals(1, server.requestCount) // gated: no second attempt five minutes later

        now += 11 * 60_000
        server.enqueue(MockResponse().setBody("""{"accepted":1,"duplicates":0}""")) // [a]
        server.enqueue(MockResponse().setBody("""{"accepted":1,"duplicates":0}""")) // [b]
        engine().onTrigger(PulseEngine.TRIGGER_TAKE_FINISHED)
        assertEquals(3, server.requestCount)
        assertTrue(closedBatchLines().isEmpty())
    }

    @Test
    fun `a rejected push keeps the batch for the next trigger`() = runBlocking {
        store.append(listOf(record("a")))
        store.closeBatch()
        server.enqueue(MockResponse().setResponseCode(500))

        engine().onTrigger(PulseEngine.TRIGGER_APP_OPENED)

        assertEquals(1, closedBatchLines().single().size)
    }

    @Test
    fun `a network failure keeps the batch`() = runBlocking {
        server.close() // connection refused
        store.append(listOf(record("a")))
        store.closeBatch()

        engine().onTrigger(PulseEngine.TRIGGER_APP_OPENED)

        assertEquals(1, closedBatchLines().single().size)
    }

    @Test
    fun `wifi-only skips pushes on a metered connection`() = runBlocking {
        settings = settings.copy(pulseWifiOnly = true)
        unmeteredNow = false
        store.append(listOf(record("a")))
        store.closeBatch()

        engine().onTrigger(PulseEngine.TRIGGER_APP_OPENED)

        assertEquals(0, server.requestCount)
        assertTrue(closedBatchLines().isNotEmpty())

        unmeteredNow = true
        server.enqueue(MockResponse().setBody("""{"accepted":1,"duplicates":0}"""))
        engine().onTrigger(PulseEngine.TRIGGER_APP_OPENED)
        assertEquals(1, server.requestCount)
        assertTrue(closedBatchLines().isEmpty())
    }

    @Test
    fun `batches push oldest first`() = runBlocking {
        store.append(listOf(record("old")))
        store.closeBatch()
        store.append(listOf(record("new")))
        store.closeBatch()
        server.enqueue(MockResponse().setBody("""{"accepted":1}"""))
        server.enqueue(MockResponse().setBody("""{"accepted":1}"""))

        engine().onTrigger(PulseEngine.TRIGGER_APP_OPENED)

        val first = server.takeRequest()!!.body?.readByteArray()!!.decodeToString()
        assertTrue(first.contains("\"id\":\"old\""))
        assertFalse(first.contains("\"id\":\"new\""))
    }

    @Test
    fun `an empty closed batch is deleted without a request`() = runBlocking {
        store.append(emptyList())
        store.closeBatch()

        engine().onTrigger(PulseEngine.TRIGGER_APP_OPENED)

        assertEquals(0, server.requestCount)
        assertTrue(closedBatchLines().isEmpty())
    }
}
