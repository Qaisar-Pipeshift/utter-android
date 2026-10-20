package io.github.qaisarpipeshift.utter.net

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveSessionTest {

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

    private fun api(): Api = Api(Http(OkHttpClient(), allowCleartext = true)) {
        ServerConfig(server.url("/").toString(), "test-key")
    }

    private fun serve(listener: WebSocketListener) {
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
    }

    private fun collect(handle: LiveHandle): List<LiveEvent> = runBlocking {
        withTimeout(5_000) { handle.events.toList() }
    }

    private fun sessionUpdated(webSocket: WebSocket) {
        webSocket.send("""{"type":"session.updated"}""")
    }

    @Test
    fun `happy path previews then the final transcript`() {
        serve(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when {
                    text.contains("session.update") -> sessionUpdated(webSocket)

                    text.contains("input_audio_buffer.append") -> webSocket.send(
                        """{"type":"transcription.preview","committed":"hel","tentative":"lo"}""",
                    )

                    text.contains("input_audio_buffer.commit") -> webSocket.send(
                        """{"type":"conversation.item.input_audio_transcription.completed","transcript":"hello"}""",
                    )
                }
            }
        })

        val handle = api().openLive(null)
        assertTrue(handle.send(ByteArray(320)))
        handle.commit()

        val events = collect(handle)
        assertTrue(events.any { it is LiveEvent.Preview })
        assertEquals(LiveEvent.Final("hello"), events.last())
    }

    @Test
    fun `an error before commit fails the session`() {
        serve(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("session.update")) {
                    sessionUpdated(webSocket)
                    webSocket.send("""{"type":"error","error":{"message":"boom"}}""")
                }
            }
        })

        val handle = api().openLive(null)

        assertEquals(LiveEvent.Failed("boom"), collect(handle).last())
        assertFalse(handle.send(ByteArray(320)))
    }

    @Test
    fun `a buffer too small error after commit is no speech`() {
        serve(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("session.update")) sessionUpdated(webSocket)
                if (text.contains("input_audio_buffer.commit")) {
                    webSocket.send("""{"type":"error","error":{"message":"audio buffer too small"}}""")
                }
            }
        })

        val handle = api().openLive(null)
        handle.commit()

        assertEquals(LiveEvent.NoSpeech, collect(handle).last())
    }

    @Test
    fun `a commit without a final event times out`() {
        serve(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("session.update")) sessionUpdated(webSocket)
            }
        })

        val handle = api().openLive(null, finalTimeoutMs = 300)
        handle.send(ByteArray(320))
        handle.commit()

        assertEquals(LiveEvent.Failed("final timeout"), collect(handle).last())
    }

    @Test
    fun `a server close without a final fails the session`() {
        serve(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("session.update")) webSocket.close(1001, "bye")
            }
        })

        assertTrue(collect(api().openLive(null)).last() is LiveEvent.Failed)
    }

    @Test
    fun `audio queued before the session is ready is capped`() {
        serve(object : WebSocketListener() {})

        val handle = api().openLive(null)
        repeat(50) { assertTrue(handle.send(ByteArray(320))) }
        assertFalse(handle.send(ByteArray(320)))

        assertEquals(LiveEvent.Failed("backlog"), collect(handle).last())
    }

    @Test
    fun `cancel sends clear and stops accepting audio`() {
        val cleared = CountDownLatch(1)
        serve(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("input_audio_buffer.clear")) cleared.countDown()
            }
        })

        val handle = api().openLive(null)
        handle.cancel()

        assertTrue(cleared.await(5, TimeUnit.SECONDS))
        assertFalse(handle.send(ByteArray(320)))
    }
}
