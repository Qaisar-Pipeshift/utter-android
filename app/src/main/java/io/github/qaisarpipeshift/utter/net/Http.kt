package io.github.qaisarpipeshift.utter.net

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * The app's single HTTP stack (docs/spec.md §6, docs/plan.md Task 4).
 *
 * Why a wrapper instead of a global client: unit tests need their own client (short timeouts, cleartext to
 * MockWebServer) while production wants exactly one connection pool, so the keyboard never keeps more than one idle
 * socket alive (spec §10 battery rule). The WebSocket client is derived from the same pool and adds only the 10 s
 * ping that notices a dead network while a take streams.
 */
class Http(
    val client: OkHttpClient = defaultClient(),
    /**
     * Allows `http://` base URLs. False in the app — the network security config blocks cleartext anyway. Unit
     * tests and a local dev server set it, because MockWebServer serves no TLS certificate.
     */
    val allowCleartext: Boolean = false,
) {
    /** Client for `/v1/realtime`: same pool and timeouts as [client], plus a ping while the socket is open. */
    val webSocketClient: OkHttpClient = client.newBuilder()
        .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val PING_SECONDS = 10L

        /** Short connect, generous read/write for a long upload, one idle pooled socket. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(1, 30, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * Runs the call asynchronously and suspends until its response arrives, cancelling the call when the coroutine is
 * cancelled. Blocking `execute()` would pin an IO thread with no way to stop it when the user cancels a dictation.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            // If the caller was cancelled meanwhile, nobody will close the response: close it here so the pooled
            // connection is released instead of leaking.
            continuation.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, failure: IOException) {
            continuation.resumeWithException(failure)
        }
    })
    continuation.invokeOnCancellation { cancel() }
}

/**
 * Normalises a user-entered server URL: trims, drops a trailing `/`, drops one trailing `/v1` (the UI may show the
 * OpenAI-style base path), and requires `https://` unless [allowCleartext] is set for tests.
 *
 * Returns null for anything unusable, so callers can tell "not set up" apart from a network failure. Plain string
 * handling on purpose: this must run identically on the JVM tests and on Android.
 */
fun normalizeBaseUrl(raw: String, allowCleartext: Boolean = false): String? {
    val trimmed = raw.trim()
    val scheme = when {
        trimmed.startsWith("https://", ignoreCase = true) -> "https"
        allowCleartext && trimmed.startsWith("http://", ignoreCase = true) -> "http"
        else -> return null
    }
    var rest = trimmed.substring(scheme.length + 3).trimEnd('/')
    if (rest.isEmpty() || rest.any { it.isWhitespace() }) return null
    if (rest.endsWith("/v1", ignoreCase = true)) {
        rest = rest.dropLast(3).trimEnd('/')
        if (rest.isEmpty()) return null
    }
    val url = "$scheme://$rest"
    // OkHttp throws IllegalArgumentException (not IOException) for a host it cannot parse; reject it here so a typo in
    // Settings shows "check the URL" instead of crashing the keyboard.
    return if (url.toHttpUrlOrNull() != null) url else null
}

/** Coarse failure kind for [SttResult.Network]; keeps raw exception text (which may carry a URL) out of diagnostics. */
internal fun IOException.networkKind(): String = when {
    this is SocketTimeoutException || this is InterruptedIOException -> "timeout"
    this is UnknownHostException -> "dns"
    this is ConnectException -> "connect"
    else -> "io"
}
