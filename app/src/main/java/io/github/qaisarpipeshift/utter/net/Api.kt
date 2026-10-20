package io.github.qaisarpipeshift.utter.net

import io.github.qaisarpipeshift.utter.core.Prompts
import io.github.qaisarpipeshift.utter.core.Transcriber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/** Where requests go and how they authenticate; built fresh from settings so a URL change takes effect at once. */
data class ServerConfig(val baseUrl: String, val apiKey: String)

/**
 * Every server call the app makes (docs/spec.md §6, docs/plan.md Task 4).
 *
 * Why a class with an injected config lambda: the IME must never hold stale settings, and tests must point at a
 * MockWebServer. All suspend functions run on [Dispatchers.IO] and are cancellable through [await]; nothing here
 * logs, because request bodies and keys are private.
 */
class Api(
    internal val http: Http,
    internal val config: () -> ServerConfig?,
) : Transcriber {

    /** The controller's live-session port; a missing server or key becomes a handle that fails on first read. */
    override fun live(prompt: String?): LiveHandle = openLive(prompt)

    /** `GET /warm`, no auth. Fire-and-forget from the controller; the result only feeds Diagnostics. */
    suspend fun warm(): Probe = withContext(Dispatchers.IO) {
        val base = baseUrl() ?: return@withContext Probe(false, 0, DETAIL_SETUP)
        val started = now()
        try {
            val request = Request.Builder().url("$base/warm").get().build()
            http.client.newCall(request).await().use { response ->
                Probe(response.isSuccessful, now() - started, "HTTP ${response.code}")
            }
        } catch (_: IOException) {
            Probe(false, now() - started, DETAIL_UNREACHABLE)
        }
    }

    /** `GET /v2/transcript`: proves both reachability and that the ingress accepts the key. */
    suspend fun checkKey(): Probe = withContext(Dispatchers.IO) {
        val cfg = config() ?: return@withContext Probe(false, 0, DETAIL_SETUP)
        val base = baseUrl() ?: return@withContext Probe(false, 0, DETAIL_URL)
        val started = now()
        try {
            http.client.newCall(authGet(cfg, "$base/v2/transcript")).await().use { response ->
                val ms = now() - started
                when (response.code) {
                    200 -> Probe(true, ms, "Connected, key accepted")
                    401 -> Probe(false, ms, "Key rejected")
                    else -> Probe(false, ms, "Server error HTTP ${response.code}")
                }
            }
        } catch (_: IOException) {
            Probe(false, now() - started, DETAIL_UNREACHABLE)
        }
    }

    /** Opens the realtime socket, sends `session.update` and waits up to 5 s for `session.updated`. */
    suspend fun probeLive(): Probe = withContext(Dispatchers.IO) {
        val cfg = config() ?: return@withContext Probe(false, 0, DETAIL_SETUP)
        val base = baseUrl() ?: return@withContext Probe(false, 0, DETAIL_URL)
        val started = now()
        val updated = CompletableDeferred<Boolean>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(LiveProtocol.sessionUpdate())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isSessionUpdated(text)) updated.complete(true)
            }

            override fun onFailure(webSocket: WebSocket, failure: Throwable, response: Response?) {
                updated.complete(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                updated.complete(false)
            }
        }
        val socket = http.webSocketClient.newWebSocket(
            authRequest(cfg, "$base/v1/realtime?intent=transcription").build(),
            listener,
        )
        // Closed on every path, including cancellation from the Server screen's Check being restarted.
        val ok = try {
            withTimeoutOrNull(PROBE_LIVE_TIMEOUT_MS) { updated.await() } ?: false
        } finally {
            socket.close(CLOSE_NORMAL, null)
        }
        val ms = now() - started
        if (ok) Probe(true, ms, "Live accepted") else Probe(false, ms, "Live failed")
    }

    /** `GET /v1/models` → the `data[].id` list; the cleanup model picker uses it. */
    suspend fun models(): Result<List<String>> = withContext(Dispatchers.IO) {
        val cfg = config() ?: return@withContext Result.failure(IllegalStateException("not configured"))
        val base = baseUrl() ?: return@withContext Result.failure(IllegalArgumentException("invalid url"))
        try {
            http.client.newCall(authGet(cfg, "$base/v1/models")).await().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val root = parseObject(response.body.string())
                    ?: return@withContext Result.failure(IOException("bad json"))
                val data = root.optJSONArray("data") ?: return@withContext Result.success(emptyList())
                val ids = ArrayList<String>(data.length())
                for (index in 0 until data.length()) {
                    val id = data.optJSONObject(index)?.optString("id").orEmpty()
                    if (id.isNotBlank()) ids.add(id)
                }
                Result.success(ids)
            }
        } catch (failure: IOException) {
            Result.failure(failure)
        }
    }

    /**
     * `POST /v1/transcribe/live` with the full take as one multipart body (spec §6). The config part goes first and
     * the audio part second, exactly as the server reader expects; `keyterms_prompt` is added only when the user has
     * vocabulary, so the default request is byte-identical to the documented one.
     */
    override suspend fun upload(pcm: File, keyterms: List<String>): SttResult = withContext(Dispatchers.IO) {
        val cfg = config() ?: return@withContext SttResult.Network(KIND_CONFIG)
        val base = baseUrl() ?: return@withContext SttResult.Network(KIND_CONFIG)
        val started = now()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("config", null, configJson(keyterms).toRequestBody(JSON_MEDIA))
            .addFormDataPart("audio", "audio.pcm", pcm.asRequestBody(PCM_MEDIA))
            .build()
        try {
            val request = authRequest(cfg, "$base/v1/transcribe/live").post(body).build()
            http.client.newCall(request).await().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> SttResult.Unauthorized
                    !response.isSuccessful -> SttResult.Http(response.code)
                    else -> classifyTranscript(response.body.string(), now() - started)
                }
            }
        } catch (failure: IOException) {
            SttResult.Network(failure.networkKind())
        }
    }

    /** `POST /v1/audio/transcriptions` for a shared voice note; same response mapping as [upload]. */
    suspend fun transcribeFile(bytes: ByteArray, filename: String, mime: String): SttResult =
        withContext(Dispatchers.IO) {
            val cfg = config() ?: return@withContext SttResult.Network(KIND_CONFIG)
            val base = baseUrl() ?: return@withContext SttResult.Network(KIND_CONFIG)
            val started = now()
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, bytes.toRequestBody(mime.toMediaType()))
                .addFormDataPart("model", LiveProtocol.MODEL)
                .addFormDataPart("response_format", "json")
                .build()
            try {
                val request = authRequest(cfg, "$base/v1/audio/transcriptions").post(body).build()
                http.client.newCall(request).await().use { response ->
                    when {
                        response.code == 401 || response.code == 403 -> SttResult.Unauthorized
                        !response.isSuccessful -> SttResult.Http(response.code)
                        else -> {
                            val text = parseObject(response.body.string())
                                ?.optString("text")
                                ?.trim()
                                .orEmpty()
                            if (text.isEmpty()) SttResult.NoSpeech else SttResult.Text(text, now() - started)
                        }
                    }
                }
            } catch (failure: IOException) {
                SttResult.Network(failure.networkKind())
            }
        }

    /**
     * `POST /v1/chat/completions` with one user message. No temperature (the server defaults to 0) and no stream.
     * [timeoutMs] is applied per call through a derived client, so a slow cleanup never blocks the next dictation.
     */
    suspend fun clean(model: String, message: String, timeoutMs: Long): CleanResult =
        withContext(Dispatchers.IO) {
            val cfg = config() ?: return@withContext CleanResult.Failed(KIND_CONFIG)
            val base = baseUrl() ?: return@withContext CleanResult.Failed(KIND_CONFIG)
            val payload = JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", message)),
                )
            val request = authRequest(cfg, "$base/v1/chat/completions")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            val client = http.client.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            try {
                client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext CleanResult.Failed("http ${response.code}")
                    }
                    val content = parseObject(response.body.string())
                        ?.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                        .orEmpty()
                    val cleaned = Prompts.stripModelNoise(content)
                        ?: return@withContext CleanResult.Failed("empty")
                    CleanResult.Text(cleaned)
                }
            } catch (failure: IOException) {
                CleanResult.Failed(failure.networkKind())
            }
        }

    /**
     * `POST /v1/audio/speech`, streamed in 8 KiB chunks so playback starts before the whole clip arrives. The sample
     * rate comes from `X-Audio-Sample-Rate` (server default 24000) and is passed to [sink] with every chunk.
     */
    suspend fun speech(
        text: String,
        voice: String,
        sink: suspend (ByteArray, Int) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cfg = config() ?: return@withContext Result.failure(IllegalStateException("not configured"))
        val base = baseUrl() ?: return@withContext Result.failure(IllegalArgumentException("invalid url"))
        val payload = JSONObject()
            .put("model", "qwen3-tts")
            .put("voice", voice)
            .put("input", text)
            .put("response_format", "pcm")
        val request = authRequest(cfg, "$base/v1/audio/speech")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        try {
            http.client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val rate = response.header(SAMPLE_RATE_HEADER)?.toIntOrNull() ?: DEFAULT_TTS_RATE
                val source = response.body.byteStream()
                val buffer = ByteArray(CHUNK_BYTES)
                var read = source.read(buffer)
                while (read >= 0) {
                    ensureActive()
                    if (read > 0) sink(buffer.copyOf(read), rate)
                    read = source.read(buffer)
                }
                Result.success(Unit)
            }
        } catch (failure: IOException) {
            Result.failure(failure)
        }
    }

    // ---- helpers ----

    private fun baseUrl(): String? = config()?.let { normalizeBaseUrl(it.baseUrl, http.allowCleartext) }

    private fun authRequest(cfg: ServerConfig, url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer ${cfg.apiKey}")

    private fun authGet(cfg: ServerConfig, url: String): Request = authRequest(cfg, url).get().build()

    private fun configJson(keyterms: List<String>): String {
        val json = JSONObject()
            .put("sample_rate", LiveProtocol.SAMPLE_RATE)
            .put("channels", 1)
            .put("llm", JSONObject.NULL)
        if (keyterms.isNotEmpty()) json.put("keyterms_prompt", JSONArray(keyterms))
        return json.toString()
    }

    private fun classifyTranscript(body: String, latencyMs: Long): SttResult {
        val root = parseObject(body) ?: return SttResult.Http(200)
        when (root.optString("llm_error").trim()) {
            "no speech" -> return SttResult.NoSpeech
            "speech-to-text unavailable" -> return SttResult.SttDown
        }
        val text = root.optString("text").trim()
        return if (text.isEmpty()) SttResult.NoSpeech else SttResult.Text(text, latencyMs)
    }

    private fun parseObject(body: String): JSONObject? = try {
        JSONObject(body)
    } catch (_: Exception) {
        null
    }

    private fun isSessionUpdated(text: String): Boolean = parseObject(text)
        ?.optString("type") == "session.updated"

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
        val PCM_MEDIA = "audio/pcm".toMediaType()

        const val DETAIL_SETUP = "Set up Utter"
        const val DETAIL_URL = "Invalid server URL"
        const val DETAIL_UNREACHABLE = "Can't reach server"
        const val KIND_CONFIG = "config"
        const val CLOSE_NORMAL = 1000
        const val PROBE_LIVE_TIMEOUT_MS = 5_000L
        const val SAMPLE_RATE_HEADER = "X-Audio-Sample-Rate"
        const val DEFAULT_TTS_RATE = 24_000
        const val CHUNK_BYTES = 8 * 1024
    }
}
