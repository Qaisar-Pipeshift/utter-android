package io.github.qaisarpipeshift.utter.extras

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.UtterApp
import io.github.qaisarpipeshift.utter.core.FieldKind
import io.github.qaisarpipeshift.utter.core.Prompts
import io.github.qaisarpipeshift.utter.core.TextOps
import io.github.qaisarpipeshift.utter.core.TextPipeline
import io.github.qaisarpipeshift.utter.data.HistoryEntry
import io.github.qaisarpipeshift.utter.net.CleanResult
import io.github.qaisarpipeshift.utter.net.SttResult
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Share → Utter" for a voice note (docs/spec.md §17, docs/plan.md Task 15).
 *
 * Why a separate activity from the keyboard: WhatsApp and Telegram share `audio/*` URIs, which the IME never sees.
 * The file is read once with a hard 25 MB ceiling and its duration is checked before a single byte is uploaded, so a
 * long recording cannot cost a pointless upload; the transcript then offers the same four calm actions as history —
 * copy, share, clean, save.
 */
class ShareAudioActivity : ComponentActivity() {
    private lateinit var graph: Graph
    private val ui = MutableStateFlow<ShareUi>(ShareUi.Loading)
    private var durationMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        graph = (application as UtterApp).graph
        setContent {
            ExtrasTheme(graph) {
                val state by ui.collectAsState()
                ExtrasSheet(onDismiss = ::finish) {
                    ShareBody(
                        state = state,
                        onCopy = { text -> copyToClipboard(text) },
                        onShare = { text -> shareText(text) },
                        onClean = ::clean,
                        onSave = ::save,
                        onClose = { finish() },
                    )
                }
            }
        }
        val uri = shareUri(intent)
        if (uri == null) {
            toast(R.string.extras_share_missing)
            finish()
            return
        }
        transcribe(uri)
    }

    private fun transcribe(uri: Uri) {
        ui.value = ShareUi.Loading
        lifecycleScope.launch {
            val knownDuration = withContext(Dispatchers.IO) {
                try {
                    readDuration(uri)
                } catch (_: Exception) {
                    null
                }
            }
            if (knownDuration != null && knownDuration > MAX_DURATION_MS) {
                ui.value = ShareUi.Error(getString(R.string.extras_share_too_long))
                return@launch
            }
            durationMs = knownDuration ?: 0L
            when (val bytes = withContext(Dispatchers.IO) { readLimited(uri, MAX_BYTES) }) {
                FileBytes.TooLarge -> {
                    ui.value = ShareUi.Error(getString(R.string.extras_share_too_large))
                    return@launch
                }

                FileBytes.Unreadable -> {
                    ui.value = ShareUi.Error(getString(R.string.extras_share_unreadable))
                    return@launch
                }

                is FileBytes.Ok -> {
                    if (bytes.data.isEmpty()) {
                        ui.value = ShareUi.Error(getString(R.string.extras_share_unreadable))
                        return@launch
                    }
                    upload(bytes.data, uri)
                }
            }
        }
    }

    private suspend fun upload(bytes: ByteArray, uri: Uri) {
        val name = withContext(Dispatchers.IO) { displayName(uri) }
        val mime = contentResolver.getType(uri)?.takeIf { it.contains('/') && !it.contains('*') } ?: DEFAULT_MIME
        when (val result = graph.api.transcribeFile(bytes, name, mime)) {
            is SttResult.Text -> {
                val settings = graph.settings.current()
                val raw = TextPipeline.raw(result.text, settings)
                ui.value = ShareUi.Done(raw = raw, text = raw, latencyMs = result.latencyMs)
            }

            SttResult.NoSpeech -> ui.value = ShareUi.Error(getString(R.string.extras_share_no_speech))
            SttResult.SttDown -> ui.value = ShareUi.Error(getString(R.string.utter_fail_stt))
            SttResult.Unauthorized -> ui.value = ShareUi.Error(getString(R.string.utter_fail_key))
            is SttResult.Http -> ui.value = ShareUi.Error(getString(R.string.extras_server_error, result.code))
            is SttResult.Network -> ui.value = ShareUi.Error(
                if (result.kind == "offline") {
                    getString(R.string.utter_fail_offline)
                } else {
                    getString(R.string.utter_fail_unreachable)
                },
            )
        }
    }

    private fun clean(current: ShareUi.Done) {
        if (current.busy) return
        ui.value = current.copy(busy = true, message = null)
        lifecycleScope.launch {
            val settings = graph.settings.current()
            val preset = settings.presets.firstOrNull { it.id == settings.defaultPresetId }
                ?: settings.presets.firstOrNull()
            val result = if (preset == null) {
                null
            } else {
                try {
                    graph.cleaner.clean(Prompts.cleanMessage(preset.prompt, current.raw))
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    CleanResult.Failed("exception")
                }
            }
            val cleaned = (result as? CleanResult.Text)?.text?.takeIf { it.isNotBlank() }
            ui.value = current.copy(
                text = TextPipeline.forField(cleaned ?: current.raw, FieldKind.TEXT, settings),
                cleaned = cleaned != null,
                busy = false,
                message = if (cleaned == null) getString(R.string.extras_share_clean_failed) else null,
            )
        }
    }

    private fun save(current: ShareUi.Done) {
        if (current.busy || current.saved) return
        val settings = graph.settings.current()
        if (!settings.historyOn) {
            ui.value = current.copy(message = getString(R.string.extras_share_history_off))
            return
        }
        ui.value = current.copy(busy = true, message = null)
        lifecycleScope.launch {
            graph.history.add(
                HistoryEntry(
                    id = UUID.randomUUID().toString(),
                    ts = System.currentTimeMillis(),
                    pkg = "",
                    appLabel = getString(R.string.extras_share_app_label),
                    raw = current.raw,
                    finalText = current.text,
                    action = ACTION_SHARE,
                    presetName = null,
                    durationMs = durationMs,
                    latencyMs = current.latencyMs,
                    words = TextOps.wordCount(current.text),
                    audioId = null,
                ),
            )
            graph.history.prune(settings.historyLimit)
            ui.value = current.copy(
                busy = false,
                saved = true,
                message = getString(R.string.extras_share_saved),
            )
        }
    }

    private fun copyToClipboard(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        toast(R.string.history_copied)
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, getString(R.string.extras_share_chooser)))
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    private fun shareUri(intent: Intent?): Uri? =
        intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent?.clipData?.getItemAt(0)?.uri

    private fun displayName(uri: Uri): String {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        return name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: DEFAULT_NAME
    }

    private fun readDuration(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Reads at most [limit] bytes; a larger file is refused before it can exhaust the heap. */
    private fun readLimited(uri: Uri, limit: Long): FileBytes {
        val input = contentResolver.openInputStream(uri) ?: return FileBytes.Unreadable
        return try {
            input.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(READ_BUFFER_BYTES)
                var total = 0L
                var read = stream.read(buffer)
                while (read >= 0) {
                    total += read
                    if (total > limit) return FileBytes.TooLarge
                    out.write(buffer, 0, read)
                    read = stream.read(buffer)
                }
                FileBytes.Ok(out.toByteArray())
            }
        } catch (_: Exception) {
            FileBytes.Unreadable
        }
    }

    private companion object {
        const val MAX_BYTES = 25L * 1024 * 1024
        const val MAX_DURATION_MS = 300_000L
        const val READ_BUFFER_BYTES = 64 * 1024
        const val DEFAULT_MIME = "audio/mpeg"
        const val DEFAULT_NAME = "voice-note"
        const val ACTION_SHARE = "SHARE"
    }
}

private sealed interface FileBytes {
    data class Ok(val data: ByteArray) : FileBytes
    data object TooLarge : FileBytes
    data object Unreadable : FileBytes
}

private sealed interface ShareUi {
    data object Loading : ShareUi
    data class Error(val error: String) : ShareUi
    data class Done(
        val raw: String,
        val text: String,
        val latencyMs: Long,
        val cleaned: Boolean = false,
        val saved: Boolean = false,
        val busy: Boolean = false,
        val message: String? = null,
    ) : ShareUi
}

@Composable
private fun ShareBody(
    state: ShareUi,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onClean: (ShareUi.Done) -> Unit,
    onSave: (ShareUi.Done) -> Unit,
    onClose: () -> Unit,
) {
    SheetTitle(stringResource(R.string.extras_share_title))
    when (state) {
        ShareUi.Loading -> SheetWorking(stringResource(R.string.extras_share_working))
        is ShareUi.Error -> {
            SheetBody(state.error, error = true)
            Row(modifier = Modifier.padding(top = 12.dp)) {
                SheetAction(label = stringResource(R.string.extras_close), onClick = onClose)
            }
        }

        is ShareUi.Done -> {
            if (state.cleaned) {
                Text(
                    text = stringResource(R.string.extras_share_cleaned),
                    modifier = Modifier.padding(top = 4.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = state.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(14.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            state.message?.let { message ->
                SheetBody(message, modifier = Modifier.padding(top = 8.dp))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SheetAction(label = stringResource(R.string.extras_share_copy), onClick = { onCopy(state.text) })
                SheetAction(label = stringResource(R.string.extras_share_share), onClick = { onShare(state.text) })
                SheetAction(
                    label = stringResource(R.string.extras_share_clean),
                    enabled = !state.busy,
                    onClick = { onClean(state) },
                )
                SheetAction(
                    label = stringResource(R.string.extras_share_save),
                    enabled = !state.busy && !state.saved,
                    onClick = { onSave(state) },
                )
            }
        }
    }
}
