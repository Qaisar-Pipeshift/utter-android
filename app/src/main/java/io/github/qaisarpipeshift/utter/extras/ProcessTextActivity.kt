package io.github.qaisarpipeshift.utter.extras

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.UtterApp
import io.github.qaisarpipeshift.utter.core.FieldKind
import io.github.qaisarpipeshift.utter.core.Preset
import io.github.qaisarpipeshift.utter.core.Prompts
import io.github.qaisarpipeshift.utter.core.TextPipeline
import io.github.qaisarpipeshift.utter.net.CleanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * "Select text → Utter" in the system selection menu (docs/spec.md §17, docs/plan.md Task 15).
 *
 * Why a translucent activity: `ACTION_PROCESS_TEXT` hands us text from an app we do not own, and the user expects to
 * return to that app with the result. A bottom sheet lists the same cleanup presets as the keyboard; an editable
 * field receives the result through [Intent.EXTRA_PROCESS_TEXT], while a read-only selection is copied instead —
 * there is nowhere safe to write it back.
 */
class ProcessTextActivity : ComponentActivity() {
    private lateinit var graph: Graph
    private val ui = MutableStateFlow(ProcessUi())
    private var speechJob: Job? = null
    private var sourceText = ""
    private var readOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        graph = (application as UtterApp).graph
        sourceText = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        readOnly = intent?.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false) ?: false
        if (sourceText.isBlank()) {
            finish()
            return
        }
        setContent {
            ExtrasTheme(graph) {
                val state by ui.collectAsState()
                val settings by graph.settings.flow.collectAsState(initial = graph.settings.current())
                ExtrasSheet(onDismiss = ::finish) {
                    SheetTitle(stringResource(R.string.extras_process_title))
                    Text(
                        text = sourceText,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (readOnly) {
                        SheetBody(stringResource(R.string.extras_process_readonly_note))
                    }
                    ReadAloudRow(
                        speaking = state.speaking,
                        enabled = !state.busy,
                        onToggle = ::toggleReadAloud,
                    )
                    Text(
                        text = stringResource(R.string.extras_process_clean_with),
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    settings.presets.forEach { preset ->
                        PresetRow(
                            preset = preset,
                            enabled = !state.busy,
                            onClick = { cleanWith(preset) },
                        )
                    }
                    when {
                        state.busy -> SheetWorking(stringResource(R.string.extras_process_working))
                        state.message != null -> SheetBody(state.message.orEmpty(), error = true)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        stopSpeech()
        super.onDestroy()
    }

    private fun cleanWith(preset: Preset) {
        if (ui.value.busy) return
        ui.value = ui.value.copy(busy = true, message = null)
        lifecycleScope.launch {
            val settings = graph.settings.current()
            val raw = TextPipeline.raw(sourceText, settings)
            val result = try {
                graph.cleaner.clean(Prompts.cleanMessage(preset.prompt, raw))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                CleanResult.Failed("exception")
            }
            val cleaned = (result as? CleanResult.Text)?.text?.takeIf { it.isNotBlank() }
            if (cleaned == null) toast(R.string.extras_process_clean_failed)
            deliver(TextPipeline.forField(cleaned ?: raw, FieldKind.TEXT, settings))
        }
    }

    /** Hands the text back to the app that asked, or copies it when the selection was read-only. */
    private fun deliver(text: String) {
        if (readOnly) {
            copyToClipboard(text)
            toast(R.string.history_copied)
            finish()
            return
        }
        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
        finish()
    }

    private fun toggleReadAloud() {
        if (ui.value.speaking) {
            stopSpeech()
            return
        }
        ui.value = ui.value.copy(speaking = true, message = null)
        // Cancel first, stop second, arm third: a chunk from the previous stream must not slip into the new track.
        speechJob?.cancel()
        graph.pcmPlayer.stop()
        graph.pcmPlayer.begin()
        speechJob = lifecycleScope.launch {
            val settings = graph.settings.current()
            val result: Result<Unit> = try {
                graph.api.speech(sourceText, settings.ttsVoice) { chunk, rate ->
                    graph.pcmPlayer.write(chunk, rate)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                Result.failure(IllegalStateException("speech"))
            }
            // Let the buffered tail play out. Cancellation is handled by stopSpeech(), which releases the track.
            if (result.isSuccess) graph.pcmPlayer.drain()
            graph.pcmPlayer.stop()
            ui.value = ui.value.copy(
                speaking = false,
                message = if (result.isFailure) getString(R.string.extras_read_failed) else null,
            )
        }
    }

    private fun stopSpeech() {
        speechJob?.cancel()
        speechJob = null
        if (::graph.isInitialized) graph.pcmPlayer.stop()
        ui.value = ui.value.copy(speaking = false)
    }

    private fun copyToClipboard(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }
}

private data class ProcessUi(
    val busy: Boolean = false,
    val speaking: Boolean = false,
    val message: String? = null,
)

@Composable
private fun ReadAloudRow(speaking: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (speaking) Icons.Rounded.Stop else Icons.Rounded.VolumeUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(if (speaking) R.string.extras_stop else R.string.extras_read_aloud),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PresetRow(preset: Preset, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.9f else 0.4f),
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = preset.name,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f),
            )
        }
    }
}
