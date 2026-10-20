package io.github.qaisarpipeshift.utter.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * One live transcription take as the controller sees it (docs/plan.md §2, Task 5).
 *
 * [events] is meant to be collected once. It completes after the first terminal event (Final, NoSpeech, Failed), so
 * the controller can simply await the end of the flow and read the last event.
 */
interface LiveHandle {
    /** Sends one PCM16LE chunk; false once the session has failed, finished or been cancelled. */
    fun send(pcm16le: ByteArray): Boolean

    /** Asks the server for the final transcript. Must arrive within 20 s or the session fails. */
    fun commit()

    /** User cancelled the take: clears the server buffer and closes the socket; no further events are emitted. */
    fun cancel()

    val events: Flow<LiveEvent>
}

/**
 * Opens `/v1/realtime` for one take (docs/spec.md §6, docs/plan.md Task 5).
 *
 * [finalTimeoutMs] is exposed only so tests can use a short wait; production always passes the default.
 */
fun Api.openLive(prompt: String?, finalTimeoutMs: Long = DEFAULT_FINAL_TIMEOUT_MS): LiveHandle {
    val cfg = config() ?: return FailedLiveHandle(OPEN_KIND_CONFIG)
    val base = normalizeBaseUrl(cfg.baseUrl, http.allowCleartext) ?: return FailedLiveHandle(OPEN_KIND_CONFIG)
    return LiveSession(
        client = http.webSocketClient,
        url = "$base/v1/realtime?intent=transcription",
        apiKey = cfg.apiKey,
        prompt = prompt,
        finalTimeoutMs = finalTimeoutMs,
    )
}

/**
 * OkHttp WebSocket session for one take.
 *
 * Why a hand-rolled bounded queue instead of a plain `Channel`: preview frames supersede each other but the final
 * event must never be dropped, and a channel cannot drop selectively. The queue keeps at most [QUEUE_CAPACITY]
 * events, prefers dropping the oldest preview when full, and never blocks the socket reader thread.
 *
 * Audio sent before the server's `session.updated` reply is queued (at most [MAX_QUEUED_CHUNKS] chunks) so the panel
 * can start streaming the instant the mic opens; overflowing the queue fails the session with `backlog`, which the
 * controller turns into the upload fallback.
 */
internal class LiveSession(
    client: OkHttpClient,
    url: String,
    apiKey: String,
    private val prompt: String?,
    private val finalTimeoutMs: Long,
) : LiveHandle {

    private val lock = Any()
    private val queue = ArrayDeque<LiveEvent>(QUEUE_CAPACITY)
    private val outbox = ArrayDeque<String>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cancelled = false

    @Volatile
    private var finished = false

    private var ready = false
    private var committed = false
    private var queuedAudio = 0

    // Volatile and nullable: OkHttp may call the listener on its own thread before the constructor has stored this.
    @Volatile
    private var socket: WebSocket? = null

    override val events: Flow<LiveEvent> = flow {
        var running = true
        while (running) {
            val next = synchronized(lock) { queue.removeFirstOrNull() }
            if (next != null) {
                emit(next)
                running = !isTerminal(next)
            } else if (synchronized(lock) { finished }) {
                running = false
            } else {
                signal.receive()
            }
        }
    }

    override fun send(pcm16le: ByteArray): Boolean =
        sendFrame(LiveProtocol.append(pcm16le), audioChunk = true)

    override fun commit() {
        synchronized(lock) {
            if (finished || cancelled || committed) return
            committed = true
        }
        if (!sendFrame(LiveProtocol.COMMIT, audioChunk = false)) return
        scope.launch {
            delay(finalTimeoutMs)
            fail(LiveProtocol.FAIL_TIMEOUT)
        }
    }

    override fun cancel() {
        synchronized(lock) {
            if (cancelled) return
            cancelled = true
            finished = true
            outbox.clear()
            queuedAudio = 0
        }
        socket?.send(LiveProtocol.CLEAR)
        socket?.close(CLOSE_NORMAL, "cancelled")
        signal.trySend(Unit)
        scope.cancel()
    }

    private fun sendFrame(frame: String, audioChunk: Boolean): Boolean {
        synchronized(lock) {
            if (finished || cancelled) return false
            if (!ready) {
                if (audioChunk && queuedAudio >= MAX_QUEUED_CHUNKS) {
                    fail(LiveProtocol.FAIL_BACKLOG)
                    return false
                }
                if (audioChunk) queuedAudio++
                outbox.addLast(frame)
                return !finished
            }
            return socket?.send(frame) ?: false
        }
    }

    private fun publish(event: LiveEvent) {
        if (cancelled) return
        synchronized(lock) {
            if (finished) return
            enqueueLocked(event)
        }
        signal.trySend(Unit)
    }

    private fun enqueueLocked(event: LiveEvent) {
        if (queue.size >= QUEUE_CAPACITY) {
            val previewIndex = queue.indexOfFirst { it is LiveEvent.Preview }
            if (event is LiveEvent.Preview) {
                if (previewIndex < 0) return
                queue.removeAt(previewIndex)
            } else if (previewIndex >= 0) {
                queue.removeAt(previewIndex)
            } else {
                queue.removeFirst()
            }
        }
        queue.addLast(event)
    }

    private fun fail(why: String) {
        terminate(LiveEvent.Failed(why))
    }

    /**
     * Publishes the session's one terminal event and ends it in one atomic step.
     *
     * Why the flag and the event share the lock: publishing first and finishing afterwards let a collector that had
     * already received the terminal event still see `send()` accepted, so a dead session kept taking audio. Every
     * terminal path — Final, NoSpeech, Failed, timeout, backlog, close, transport failure — goes through here.
     */
    private fun terminate(event: LiveEvent) {
        synchronized(lock) {
            if (finished || cancelled) return
            finished = true
            outbox.clear()
            queuedAudio = 0
            enqueueLocked(event)
        }
        socket?.close(CLOSE_NORMAL, null)
        signal.trySend(Unit)
        scope.cancel()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!webSocket.send(LiveProtocol.sessionUpdate(prompt = prompt))) fail(LiveProtocol.FAIL_CLOSED)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val type = try {
                JSONObject(text).optString("type")
            } catch (_: Exception) {
                ""
            }
            if (type == SESSION_UPDATED) {
                synchronized(lock) {
                    ready = true
                    // The callback's own WebSocket, not the `socket` field: this listener is handed to OkHttp from the
                    // constructor, so the field may not be visible yet on OkHttp's thread.
                    while (outbox.isNotEmpty()) webSocket.send(outbox.removeFirst())
                    queuedAudio = 0
                }
                return
            }
            when (val event = LiveProtocol.parse(text)) {
                null -> Unit
                is LiveEvent.Preview -> publish(event)
                else -> terminate(event)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(CLOSE_NORMAL, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail(LiveProtocol.FAIL_CLOSED)
        }

        override fun onFailure(webSocket: WebSocket, failure: Throwable, response: Response?) {
            fail(LiveProtocol.FAIL_CONNECT)
        }
    }

    // Runs after [listener] is initialised (property order), so the socket can never call back into a null listener.
    init {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .build()
        socket = client.newWebSocket(request, listener)
    }

    private fun isTerminal(event: LiveEvent): Boolean = when (event) {
        is LiveEvent.Preview -> false
        is LiveEvent.Final, LiveEvent.NoSpeech, is LiveEvent.Failed -> true
    }

    private companion object {
        const val QUEUE_CAPACITY = 64
        const val MAX_QUEUED_CHUNKS = 50
        const val CLOSE_NORMAL = 1000
        const val SESSION_UPDATED = "session.updated"
        const val KIND_CONFIG = "config"
    }
}

private const val DEFAULT_FINAL_TIMEOUT_MS = 20_000L

/** Failure kind for a session that cannot open because the server URL or key is missing or invalid. */
private const val OPEN_KIND_CONFIG = "config"

/** A session that could not even be opened (no server URL or key); it fails at once instead of throwing. */
private class FailedLiveHandle(private val why: String) : LiveHandle {
    override fun send(pcm16le: ByteArray): Boolean = false
    override fun commit() = Unit
    override fun cancel() = Unit
    override val events: Flow<LiveEvent> = flowOf(LiveEvent.Failed(why))
}
