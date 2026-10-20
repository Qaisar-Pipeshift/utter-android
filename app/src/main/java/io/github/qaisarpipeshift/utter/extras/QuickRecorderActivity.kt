package io.github.qaisarpipeshift.utter.extras

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.UtterApp
import io.github.qaisarpipeshift.utter.audio.AndroidRecorder
import io.github.qaisarpipeshift.utter.core.DictationController
import io.github.qaisarpipeshift.utter.core.EnterAction
import io.github.qaisarpipeshift.utter.core.FailReason
import io.github.qaisarpipeshift.utter.core.FieldInfo
import io.github.qaisarpipeshift.utter.core.FieldKind
import io.github.qaisarpipeshift.utter.core.InsertMeta
import io.github.qaisarpipeshift.utter.core.Inserter
import io.github.qaisarpipeshift.utter.core.PanelState
import io.github.qaisarpipeshift.utter.core.TakeAction
import io.github.qaisarpipeshift.utter.core.TakeRequest
import io.github.qaisarpipeshift.utter.ui.MainActivity
import java.util.Locale
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Quick Settings tile's recorder (docs/spec.md §17, docs/plan.md Task 15).
 *
 * Why it reuses [DictationController] with a clipboard [Inserter]: a note-to-self has no text field, but it deserves
 * the same live preview, retry queue, history and stats as a keyboard take. The controller inserts nothing here; the
 * clipboard inserter returns false, which is exactly its "field was closed" path, so the text is copied and stored —
 * no second state machine to drift from the keyboard's.
 */
class QuickRecorderActivity : ComponentActivity() {
    private lateinit var graph: Graph
    private lateinit var recorder: AndroidRecorder
    private lateinit var controller: DictationController
    private val gate = MutableStateFlow<QuickGate>(QuickGate.Checking)

    /**
     * The controller's scope, owned by the activity rather than `lifecycleScope`: the stop-and-keep pass in
     * [onDestroy] must not be cancelled by the very teardown that asked for it. A failure here becomes a Diagnostics
     * event instead of a crash.
     */
    private val quickScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, failure ->
            if (::graph.isInitialized) graph.diag.event("quick_error", failure.javaClass.simpleName)
        },
    )

    /** No app owns this take; the empty package lets it skip per-app rules while history gets a readable label. */
    private val quickField = FieldInfo(
        kind = FieldKind.TEXT,
        incognito = false,
        pkg = "",
        fieldId = -1,
        actionLabel = EnterAction.NEWLINE,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        graph = (application as UtterApp).graph
        recorder = AndroidRecorder(this, { graph.settings.current() }, graph.diag)
        controller = DictationController(
            scope = quickScope,
            settings = { graph.settings.current() },
            recorder = recorder,
            transcriber = graph.api,
            cleaner = graph.cleaner,
            inserter = ClipboardInserter(this),
            pending = graph.pending,
            history = graph.history,
            stats = graph.stats,
            isOnline = ::isOnline,
            diag = graph.diag,
            prewarm = { graph.api.warm() },
            historyLabel = getString(R.string.extras_quick_app_label),
        )
        lifecycleScope.launch { graph.pending.purge() }
        lifecycleScope.launch {
            val settings = graph.settings.flow.first()
            val key = withContext(Dispatchers.IO) { graph.secrets.load() }
            gate.value = when {
                settings.baseUrl.isBlank() || key.isNullOrBlank() -> QuickGate.Setup
                !micGranted() -> QuickGate.Mic
                else -> QuickGate.Ready
            }
        }
        setContent {
            ExtrasTheme(graph) {
                val gateNow by gate.collectAsState()
                val state by controller.state.collectAsState()
                ExtrasSheet(onDismiss = ::finish) {
                    SheetTitle(stringResource(R.string.extras_quick_title))
                    when (gateNow) {
                        QuickGate.Checking -> SheetWorking(stringResource(R.string.extras_quick_working))
                        QuickGate.Setup -> GateBody(
                            body = stringResource(R.string.extras_quick_setup),
                            action = stringResource(R.string.utter_setup_button),
                            onAction = { openApp() },
                        )

                        QuickGate.Mic -> GateBody(
                            body = stringResource(R.string.utter_mic_needed_body),
                            action = stringResource(R.string.utter_grant_mic),
                            onAction = { openApp(MainActivity.STEP_MIC) },
                        )

                        QuickGate.Ready -> QuickBody(
                            state = state,
                            onStart = {
                                controller.start(TakeRequest(TakeAction.RAW, null, quickField, selection = null))
                            },
                            onStop = controller::stop,
                            onCancel = controller::cancel,
                            onRetry = controller::retry,
                            onDiscard = controller::discard,
                            onClose = ::finish,
                        )
                    }
                }
            }
        }
    }

    override fun onStop() {
        // The sheet is going away (not a rotation: the activity handles those in place). Stop the mic and run the
        // take to the clipboard through the normal path; if that cannot finish, its audio stays pending for Retry.
        if (::controller.isInitialized && !isChangingConfigurations) {
            quickScope.launch { controller.onPanelHidden() }
        }
        super.onStop()
    }

    override fun onDestroy() {
        // Never discard a take on teardown. The shutdown releases the mic and keeps the captured audio as a pending
        // take; it runs on its own NonCancellable job because the scope is cancelled on the next line.
        if (::controller.isInitialized) quickScope.launch(NonCancellable) { controller.shutdown() }
        quickScope.cancel()
        super.onDestroy()
    }

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** One-shot network check at record start, the same capability read the IME makes (docs/spec.md §17). */
    private fun isOnline(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun openApp(step: String? = null) {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (step != null) intent.putExtra(MainActivity.EXTRA_ONBOARDING_STEP, step)
        startActivity(intent)
        finish()
    }
}

private sealed interface QuickGate {
    data object Checking : QuickGate
    data object Setup : QuickGate
    data object Mic : QuickGate
    data object Ready : QuickGate
}

/** The clipboard is the field here; returning false is the controller's copy-and-store path. */
private class ClipboardInserter(context: Context) : Inserter {
    private val appContext = context.applicationContext
    private val label = appContext.getString(R.string.app_name)

    override fun insert(text: String, meta: InsertMeta): Boolean {
        val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
        return false
    }

    override fun preview(text: String) = Unit

    override fun clearPreview() = Unit
}

@Composable
private fun GateBody(body: String, action: String, onAction: () -> Unit) {
    SheetBody(body)
    Row(modifier = Modifier.padding(top = 12.dp)) {
        SheetAction(label = action, onClick = onAction, primary = true)
    }
}

@Composable
private fun QuickBody(
    state: PanelState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onRetry: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onClose: () -> Unit,
) {
    when (state) {
        is PanelState.Recording -> {
            val live = state.committed + state.tentative
            val listening = stringResource(R.string.utter_listening)
            Text(
                text = live.ifBlank { listening },
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = if (live.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = elapsedLabel(state.elapsedMs),
                modifier = Modifier.padding(top = 4.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BigMic(recording = true, onClick = onStop)
            Row(modifier = Modifier.padding(top = 8.dp)) {
                SheetAction(label = stringResource(R.string.extras_quick_cancel), onClick = onCancel)
            }
        }

        is PanelState.Finishing -> SheetWorking(stringResource(R.string.utter_transcribing))

        is PanelState.Failed -> {
            SheetBody(failureText(state), error = true)
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.takeId.isNotEmpty()) {
                    SheetAction(
                        label = stringResource(R.string.utter_retry),
                        onClick = { onRetry(state.takeId) },
                        primary = true,
                    )
                    SheetAction(
                        label = stringResource(R.string.extras_quick_discard),
                        onClick = { onDiscard(state.takeId) },
                    )
                } else {
                    SheetAction(label = stringResource(R.string.extras_close), onClick = onClose)
                }
            }
        }

        is PanelState.Idle -> {
            val result = state.result
            when {
                result?.noSpeech == true -> SheetBody(stringResource(R.string.utter_no_speech))
                result != null && result.text.isNotBlank() -> {
                    Text(
                        text = result.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(14.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    SheetBody(stringResource(R.string.extras_quick_copied))
                }

                else -> SheetBody(stringResource(R.string.extras_quick_hint))
            }
            if (state.pendingCount > 0) {
                SheetBody(stringResource(R.string.utter_pending, state.pendingCount))
            }
            BigMic(recording = false, onClick = onStart)
        }

        else -> SheetBody(stringResource(R.string.extras_quick_failed), error = true)
    }
}

@Composable
private fun BigMic(recording: Boolean, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (recording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                contentDescription = stringResource(
                    if (recording) R.string.utter_stop_dictation else R.string.utter_start_dictation,
                ),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun failureText(state: PanelState.Failed): String = when (state.reason) {
    FailReason.OFFLINE -> stringResource(R.string.utter_fail_offline)
    FailReason.UNREACHABLE -> stringResource(R.string.utter_fail_unreachable)
    FailReason.KEY_REJECTED -> stringResource(R.string.utter_fail_key)
    FailReason.STT_DOWN -> stringResource(R.string.utter_fail_stt)
    FailReason.SERVER_ERROR -> stringResource(R.string.utter_fail_server)
    FailReason.RECORDER -> {
        val code = state.code
        if (code == null) stringResource(R.string.utter_fail_recorder) else stringResource(R.string.utter_fail_recorder_code, code)
    }

    FailReason.TOO_SHORT -> stringResource(R.string.utter_fail_short)
}

private fun elapsedLabel(elapsedMs: Long): String {
    val seconds = (elapsedMs / 1_000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
}
