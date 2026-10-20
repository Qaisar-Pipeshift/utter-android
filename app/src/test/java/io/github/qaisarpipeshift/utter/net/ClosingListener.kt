package io.github.qaisarpipeshift.utter.net

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Test-server WebSocket listener that answers the client's close frame, like a real server does.
 *
 * Without it the fake server never completes the close handshake, OkHttp keeps the socket for up to 60 s, and
 * MockWebServer.shutdown() fails with "Gave up waiting for queue to shut down".
 */
class ClosingListener(private val inner: WebSocketListener) : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) = inner.onOpen(webSocket, response)

    override fun onMessage(webSocket: WebSocket, text: String) = inner.onMessage(webSocket, text)

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = inner.onMessage(webSocket, bytes)

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        inner.onClosing(webSocket, code, reason)
        webSocket.close(1000, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = inner.onClosed(webSocket, code, reason)

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
        inner.onFailure(webSocket, t, response)
}
