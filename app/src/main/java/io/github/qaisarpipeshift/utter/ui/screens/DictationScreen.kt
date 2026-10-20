package io.github.qaisarpipeshift.utter.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.audio.Mics
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.ui.UtterScreen
import io.github.qaisarpipeshift.utter.ui.components.ChoiceRow
import io.github.qaisarpipeshift.utter.ui.components.SectionCard
import io.github.qaisarpipeshift.utter.ui.components.SettingRow
import io.github.qaisarpipeshift.utter.ui.components.SliderRow
import io.github.qaisarpipeshift.utter.ui.components.SwitchRow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Dictation settings (docs/spec.md §15.1, §16; docs/plan.md Task 13).
 *
 * Why the sliders keep a local value: dragging must be frame-smooth, and DataStore should get one write when the
 * finger lifts. Every other control commits immediately. The tail slider carries the reason it defaults to zero in
 * its own subtitle — Handy's 500 ms tail was long enough to record its stop chime.
 */
@Composable
fun DictationScreen(graph: Graph, onBack: () -> Unit) {
    val settings by graph.settings.flow.collectAsState(initial = graph.settings.current())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val mics = remember(context) { Mics.list(context) }

    var holdThreshold by remember(settings.holdThresholdMs) {
        mutableFloatStateOf(settings.holdThresholdMs.toFloat())
    }
    var tailMs by remember(settings.tailMs) { mutableFloatStateOf(settings.tailMs.toFloat()) }

    fun update(transform: (Settings) -> Settings) {
        scope.launch { graph.settings.update(transform) }
    }

    val micNames = listOf(stringResource(R.string.dictate_mic_auto)) + mics.map { it.label }
    val savedMicIndex = mics.indexOfFirst { it.id == settings.micId }
    val selectedMic = if (settings.micId == null || savedMicIndex < 0) 0 else savedMicIndex + 1

    UtterScreen(title = stringResource(R.string.screen_dictation), onBack = onBack) {
        SectionCard(title = stringResource(R.string.dictate_section_gestures)) {
            SwitchRow(
                title = stringResource(R.string.dictate_tap_toggle),
                subtitle = stringResource(R.string.dictate_tap_toggle_sub),
                checked = settings.tapHandsFree,
                onChange = { value -> update { it.copy(tapHandsFree = value) } },
            )
            SliderRow(
                title = stringResource(R.string.dictate_hold_threshold),
                subtitle = stringResource(R.string.dictate_hold_threshold_sub),
                valueText = stringResource(R.string.dictate_ms, holdThreshold.roundToInt()),
                value = holdThreshold,
                valueRange = 150f..800f,
                steps = 12,
                onValueChange = { holdThreshold = it },
                onValueChangeFinished = {
                    update { it.copy(holdThresholdMs = holdThreshold.roundToInt()) }
                },
            )
            SwitchRow(
                title = stringResource(R.string.dictate_slide),
                subtitle = stringResource(R.string.dictate_slide_sub),
                checked = settings.slideGestures,
                onChange = { value -> update { it.copy(slideGestures = value) } },
            )
        }

        SectionCard(title = stringResource(R.string.dictate_section_live)) {
            SwitchRow(
                title = stringResource(R.string.dictate_live),
                subtitle = stringResource(R.string.dictate_live_sub),
                checked = settings.live,
                onChange = { value -> update { it.copy(live = value) } },
            )
            ChoiceRow(
                title = stringResource(R.string.dictate_live_in),
                options = listOf(
                    stringResource(R.string.dictate_live_panel),
                    stringResource(R.string.dictate_live_field),
                ),
                selected = if (settings.liveInField) 1 else 0,
                onSelect = { index -> update { it.copy(liveInField = index == 1) } },
            )
            SwitchRow(
                title = stringResource(R.string.dictate_auto_start),
                subtitle = stringResource(R.string.dictate_auto_start_sub),
                checked = settings.autoStart,
                onChange = { value -> update { it.copy(autoStart = value) } },
            )
            SwitchRow(
                title = stringResource(R.string.dictate_speculative),
                subtitle = stringResource(R.string.dictate_speculative_sub),
                checked = settings.speculativeClean,
                onChange = { value -> update { it.copy(speculativeClean = value) } },
            )
            SwitchRow(
                title = stringResource(R.string.dictate_prewarm),
                subtitle = stringResource(R.string.dictate_prewarm_sub),
                checked = settings.prewarm,
                onChange = { value -> update { it.copy(prewarm = value) } },
            )
        }

        SectionCard(title = stringResource(R.string.dictate_section_audio)) {
            SwitchRow(
                title = stringResource(R.string.dictate_duck),
                subtitle = stringResource(R.string.dictate_duck_sub),
                checked = settings.duckAudio,
                onChange = { value -> update { it.copy(duckAudio = value) } },
            )
            SwitchRow(
                title = stringResource(R.string.dictate_sounds),
                subtitle = stringResource(R.string.dictate_sounds_sub),
                checked = settings.sounds,
                onChange = { value -> update { it.copy(sounds = value) } },
            )
            SliderRow(
                title = stringResource(R.string.dictate_tail),
                subtitle = stringResource(R.string.dictate_tail_sub),
                valueText = stringResource(R.string.dictate_ms, tailMs.roundToInt()),
                value = tailMs,
                valueRange = 0f..500f,
                steps = 9,
                onValueChange = { tailMs = it },
                onValueChangeFinished = {
                    update { it.copy(tailMs = tailMs.roundToInt()) }
                },
            )
            ChoiceRow(
                title = stringResource(R.string.dictate_mic),
                options = micNames,
                selected = selectedMic,
                onSelect = { index ->
                    update { it.copy(micId = if (index == 0) null else mics[index - 1].id) }
                },
            )
            Text(
                text = stringResource(R.string.dictate_mic_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            SettingRow(
                title = stringResource(R.string.dictate_max_take),
                value = stringResource(R.string.dictate_max_take_value),
            )
        }
    }
}
