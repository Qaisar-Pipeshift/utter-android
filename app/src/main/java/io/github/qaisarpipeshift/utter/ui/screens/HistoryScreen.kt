package io.github.qaisarpipeshift.utter.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.core.FailReason
import io.github.qaisarpipeshift.utter.core.RetryOutcome
import io.github.qaisarpipeshift.utter.data.HistoryEntry
import io.github.qaisarpipeshift.utter.data.PendingMeta
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.ui.UtterListScreen
import io.github.qaisarpipeshift.utter.ui.components.ChoiceRow
import io.github.qaisarpipeshift.utter.ui.components.EmptyState
import io.github.qaisarpipeshift.utter.ui.components.SectionCard
import io.github.qaisarpipeshift.utter.ui.components.SwitchRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A transient line under the list after a retry; copying is silent apart from its toast. */
sealed interface HistoryNotice {
    data class Failed(val reason: FailReason) : HistoryNotice
    data object NoSpeech : HistoryNotice
}

/**
 * History state: entries, the pending queue above them, and kept-audio playback (docs/spec.md §15.4).
 *
 * Why a ViewModel: search re-reads the JSON-lines file, retries can take half a minute, and playback owns an
 * `AudioTrack` through [Graph.pcmPlayer]. All three must survive recomposition and be cancelled when the screen
 * leaves the back stack. Retry delegates to [io.github.qaisarpipeshift.utter.core.TakeRetrier], so the app and the
 * keyboard produce text through exactly the same pipeline.
 */
class HistoryViewModel(private val graph: Graph) : ViewModel() {

    data class UiState(
        val query: String = "",
        val entries: List<HistoryEntry> = emptyList(),
        val pending: List<PendingMeta> = emptyList(),
        val playable: Set<String> = emptySet(),
        val retryingId: String? = null,
        val playingId: String? = null,
        val copiedText: String? = null,
        val notice: HistoryNotice? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var playJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        val query = _state.value.query
        val entries = graph.history.list(query)
        val pending = graph.pending.list()
        val playable = graph.pending.keptAudioIds()
        // A newer keystroke may have changed the query while these reads ran; its load owns the state now.
        if (_state.value.query != query) return
        _state.value = _state.value.copy(entries = entries, pending = pending, playable = playable)
    }

    fun setQuery(query: String) {
        if (query == _state.value.query) return
        _state.value = _state.value.copy(query = query)
        refresh()
    }

    fun copyText(text: String) {
        _state.value = _state.value.copy(copiedText = text)
    }

    fun consumeCopied() {
        _state.value = _state.value.copy(copiedText = null)
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    fun delete(entry: HistoryEntry) {
        if (entry.audioId != null && _state.value.playingId == entry.audioId) stopPlayback()
        viewModelScope.launch {
            graph.history.delete(entry.id)
            entry.audioId?.let { graph.pending.deleteAudio(it) }
            refresh()
        }
    }

    fun clearAll() {
        stopPlayback()
        viewModelScope.launch {
            graph.history.clear()
            graph.pending.clearAudio()
            refresh()
        }
    }

    fun discard(takeId: String) {
        viewModelScope.launch {
            graph.pending.delete(takeId)
            refresh()
        }
    }

    fun setHistoryOn(on: Boolean) {
        viewModelScope.launch { graph.settings.update { it.copy(historyOn = on) } }
    }

    fun setHistoryLimit(limit: Int) {
        viewModelScope.launch {
            graph.settings.update { it.copy(historyLimit = limit) }
            graph.history.prune(limit)
            refresh()
        }
    }

    fun setKeepAudio(days: Int) {
        viewModelScope.launch {
            graph.settings.update { it.copy(keepAudioDays = days) }
            if (days <= 0) graph.pending.clearAudio() else graph.pending.purgeAudio(days)
            refresh()
        }
    }

    fun retry(takeId: String) {
        if (_state.value.retryingId != null) return
        viewModelScope.launch {
            val meta = graph.pending.meta(takeId)
            if (meta == null) {
                _state.value = _state.value.copy(notice = HistoryNotice.Failed(FailReason.UNREACHABLE))
                return@launch
            }
            _state.value = _state.value.copy(retryingId = takeId, notice = null)
            val outcome = try {
                graph.retrier.retry(meta, graph.settings.current())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                RetryOutcome.Failed(FailReason.UNREACHABLE, "retry_exception")
            }
            _state.value = _state.value.copy(
                retryingId = null,
                copiedText = (outcome as? RetryOutcome.Success)?.text,
                notice = when (outcome) {
                    is RetryOutcome.Success -> null
                    RetryOutcome.NoSpeech -> HistoryNotice.NoSpeech
                    is RetryOutcome.Failed -> HistoryNotice.Failed(outcome.reason)
                },
            )
            refresh()
        }
    }

    fun play(entry: HistoryEntry) {
        val audioId = entry.audioId ?: entry.id
        if (_state.value.playingId == audioId) {
            stopPlayback()
            return
        }
        val file = graph.pending.audioFile(audioId)
        if (!file.exists()) {
            _state.value = _state.value.copy(notice = HistoryNotice.Failed(FailReason.UNREACHABLE))
            return
        }
        playJob?.cancel()
        graph.pcmPlayer.stop()
        _state.value = _state.value.copy(playingId = audioId)
        playJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    file.inputStream().use { input ->
                        val buffer = ByteArray(CHUNK_BYTES)
                        var read = input.read(buffer)
                        while (read >= 0) {
                            ensureActive()
                            if (read > 0 && !graph.pcmPlayer.write(buffer.copyOf(read), SAMPLE_RATE)) break
                            read = input.read(buffer)
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                graph.diag.event("history_play_error")
            } finally {
                if (_state.value.playingId == audioId) {
                    _state.value = _state.value.copy(playingId = null)
                }
            }
        }
    }

    fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        graph.pcmPlayer.stop()
        _state.value = _state.value.copy(playingId = null)
    }

    override fun onCleared() {
        playJob?.cancel()
        graph.pcmPlayer.stop()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_BYTES = 8_192
    }
}

internal fun historyViewModelFactory(graph: Graph) = viewModelFactory {
    initializer { HistoryViewModel(graph) }
}

/**
 * History screen (docs/spec.md §15.4, §16; docs/plan.md Task 14).
 *
 * Pending takes sit above the list because they are the only actionable history: a retry runs the full pipeline and
 * copies the result, since the settings app has no text field. Tapping a row copies its final text; swiping left
 * deletes it; kept audio gets a play control.
 */
@Composable
fun HistoryScreen(graph: Graph, onBack: () -> Unit) {
    val model: HistoryViewModel = viewModel(factory = historyViewModelFactory(graph))
    val ui by model.state.collectAsState()
    val settings by graph.settings.flow.collectAsState(initial = graph.settings.current())
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    // Resolved in composition so a configuration change (locale, font) re-reads them.
    val failMessages = FailReason.entries.associateWith { stringResource(failReasonRes(it)) }
    val noSpeechMessage = stringResource(R.string.utter_no_speech)

    LaunchedEffect(ui.copiedText) {
        val text = ui.copiedText ?: return@LaunchedEffect
        copyToClipboard(context, text)
        Toast.makeText(context, R.string.history_copied, Toast.LENGTH_SHORT).show()
        model.consumeCopied()
    }
    LaunchedEffect(ui.notice) {
        val notice = ui.notice ?: return@LaunchedEffect
        val message = when (notice) {
            is HistoryNotice.Failed -> failMessages.getValue(notice.reason)
            HistoryNotice.NoSpeech -> noSpeechMessage
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        model.consumeNotice()
    }

    UtterListScreen(title = stringResource(R.string.screen_history), onBack = onBack) {
        if (ui.pending.isNotEmpty()) {
            item(key = "pending") {
                PendingSection(
                    pending = ui.pending,
                    retryingId = ui.retryingId,
                    seenApps = settings.seenApps,
                    onRetry = model::retry,
                    onDiscard = model::discard,
                )
            }
        }
        item(key = "storage") {
            StorageSection(
                settings = settings,
                canClear = ui.entries.isNotEmpty(),
                onHistoryOn = model::setHistoryOn,
                onLimit = model::setHistoryLimit,
                onKeepAudio = model::setKeepAudio,
                onClear = { confirmClear = true },
            )
        }
        item(key = "search") {
            OutlinedTextField(
                value = ui.query,
                onValueChange = model::setQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.history_search_hint)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                },
                singleLine = true,
            )
        }
        if (ui.entries.isEmpty()) {
            item(key = "empty") {
                val searching = ui.query.isNotBlank()
                EmptyState(
                    icon = Icons.Rounded.History,
                    title = stringResource(
                        if (searching) R.string.history_no_results else R.string.history_empty_title,
                    ),
                    body = stringResource(
                        if (searching) R.string.history_no_results_body else R.string.history_empty_body,
                    ),
                )
            }
        }
        val groups = ui.entries.groupBy { dateOf(it.ts) }
        groups.forEach { (date, entries) ->
            item(key = "day-$date") {
                Text(
                    text = formatDay(date),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(entries, key = { it.id }) { entry ->
                HistoryItem(
                    entry = entry,
                    playable = entry.audioId?.let { it in ui.playable } == true,
                    playing = ui.playingId == (entry.audioId ?: entry.id),
                    onCopy = { model.copyText(entry.finalText) },
                    onDelete = { model.delete(entry) },
                    onPlay = { model.play(entry) },
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_title)) },
            text = { Text(stringResource(R.string.history_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        model.clearAll()
                    },
                ) {
                    Text(stringResource(R.string.history_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}

@Composable
private fun PendingSection(
    pending: List<PendingMeta>,
    retryingId: String?,
    seenApps: Map<String, String>,
    onRetry: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    SectionCard(title = stringResource(R.string.history_pending_section)) {
        pending.forEach { meta ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = seenApps[meta.pkg] ?: meta.pkg,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.history_seconds, meta.durationMs / 1_000),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (retryingId == meta.id) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    TextButton(onClick = { onRetry(meta.id) }, enabled = retryingId == null) {
                        Text(stringResource(R.string.utter_retry))
                    }
                }
                TextButton(onClick = { onDiscard(meta.id) }, enabled = retryingId == null) {
                    Text(stringResource(R.string.utter_discard))
                }
            }
        }
    }
}

/** Retention and keep-audio choices, plus the destructive clear-all entry. */
@Composable
private fun StorageSection(
    settings: Settings,
    canClear: Boolean,
    onHistoryOn: (Boolean) -> Unit,
    onLimit: (Int) -> Unit,
    onKeepAudio: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val limits = listOf(50, 200, 1_000, 0)
    val limitLabels = listOf(
        stringResource(R.string.history_limit_50),
        stringResource(R.string.history_limit_200),
        stringResource(R.string.history_limit_1000),
        stringResource(R.string.history_limit_forever),
    )
    val keepDays = listOf(0, 1, 7)
    val keepLabels = listOf(
        stringResource(R.string.history_keep_never),
        stringResource(R.string.history_keep_one_day),
        stringResource(R.string.history_keep_seven_days),
    )
    SectionCard(title = stringResource(R.string.history_settings_title)) {
        SwitchRow(
            title = stringResource(R.string.history_on_title),
            subtitle = stringResource(R.string.history_on_sub),
            checked = settings.historyOn,
            onChange = onHistoryOn,
        )
        ChoiceRow(
            title = stringResource(R.string.history_limit_title),
            options = limitLabels,
            selected = limits.indexOf(settings.historyLimit),
            onSelect = { index -> onLimit(limits[index]) },
        )
        ChoiceRow(
            title = stringResource(R.string.history_keep_audio_title),
            options = keepLabels,
            selected = keepDays.indexOf(settings.keepAudioDays).coerceAtLeast(0),
            onSelect = { index -> onKeepAudio(keepDays[index]) },
        )
        Text(
            text = stringResource(R.string.history_keep_audio_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        OutlinedButton(
            onClick = onClear,
            enabled = canClear,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text(stringResource(R.string.history_clear_all))
        }
    }
}

/** One history row: tap copies, drag left deletes, the icons reveal raw text, replay audio or delete explicitly. */
@Composable
private fun HistoryItem(
    entry: HistoryEntry,
    playable: Boolean,
    playing: Boolean,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    var drag by remember(entry.id) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val threshold = with(density) { 72.dp.toPx() }
    val maxSwipe = with(density) { 120.dp.toPx() }
    val appLabel = entry.appLabel.ifBlank { entry.pkg }
    val shape = MaterialTheme.shapes.large
    val showRaw = entry.raw != entry.finalText

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.errorContainer),
    ) {
        Icon(
            imageVector = Icons.Rounded.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 24.dp)
                .size(24.dp),
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = drag }
                .pointerInput(entry.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (drag <= -threshold) onDelete() else drag = 0f
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            drag = (drag + amount).coerceIn(-maxSwipe, 0f)
                        },
                    )
                }
                .clickable(onClick = onCopy),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = entry.finalText,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (expanded && showRaw) {
                    Text(
                        text = entry.raw,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.history_meta,
                            appLabel,
                            entry.durationMs / 1_000,
                            entry.latencyMs,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (showRaw) {
                        IconButton(onClick = { expanded = !expanded }) {
                            Icon(
                                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = stringResource(
                                    if (expanded) R.string.history_hide_raw else R.string.history_show_raw,
                                ),
                            )
                        }
                    }
                    if (playable) {
                        IconButton(onClick = onPlay) {
                            Icon(
                                imageVector = if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(
                                    if (playing) R.string.history_stop_audio else R.string.history_play_audio,
                                ),
                            )
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.history_delete),
                        )
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
}

private fun failReasonRes(reason: FailReason): Int = when (reason) {
    FailReason.OFFLINE -> R.string.utter_fail_offline
    FailReason.UNREACHABLE -> R.string.utter_fail_unreachable
    FailReason.KEY_REJECTED -> R.string.utter_fail_key
    FailReason.STT_DOWN -> R.string.utter_fail_stt
    FailReason.SERVER_ERROR -> R.string.utter_fail_server
    FailReason.RECORDER -> R.string.utter_fail_recorder
    FailReason.TOO_SHORT -> R.string.utter_fail_short
}

private fun dateOf(ts: Long): LocalDate =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()

private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun formatDay(date: LocalDate): String = date.format(dayFormatter)
