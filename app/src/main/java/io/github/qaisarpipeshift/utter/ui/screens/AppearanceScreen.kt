package io.github.qaisarpipeshift.utter.ui.screens

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.core.EnterAction
import io.github.qaisarpipeshift.utter.core.PanelState
import io.github.qaisarpipeshift.utter.core.TakeAction
import io.github.qaisarpipeshift.utter.data.Palette
import io.github.qaisarpipeshift.utter.data.PanelHeight
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.data.ThemeMode
import io.github.qaisarpipeshift.utter.ime.panel.KeyRow
import io.github.qaisarpipeshift.utter.ime.panel.StatusStrip
import io.github.qaisarpipeshift.utter.ui.UtterScreen
import io.github.qaisarpipeshift.utter.ui.components.ChoiceRow
import io.github.qaisarpipeshift.utter.ui.components.SectionCard
import io.github.qaisarpipeshift.utter.ui.components.SwitchRow
import kotlinx.coroutines.launch

/** The panel's heights from docs/plan.md Task 9, mirrored here so the preview matches the keyboard exactly. */
private fun previewHeight(height: PanelHeight): Dp = when (height) {
    PanelHeight.COMPACT -> 196.dp
    PanelHeight.NORMAL -> 228.dp
    PanelHeight.TALL -> 268.dp
}

/** A recording snapshot for the preview strip: it reads "Listening…" and nothing animates. */
private val PreviewState = PanelState.Recording(
    action = TakeAction.RAW,
    locked = false,
    holding = false,
    elapsedMs = 0L,
    amplitude = 0f,
    committed = "",
    tentative = "",
)

/**
 * Appearance settings (docs/spec.md §15.5, §16; docs/plan.md Task 13).
 *
 * Why the preview is built from the panel's own [StatusStrip] and [KeyRow]: theme, palette and height decisions are
 * only meaningful against the real keyboard chrome. The mic is a static accent circle — a live `MicButton` would
 * eat drag gestures inside this scrolling settings page for no benefit.
 */
@Composable
fun AppearanceScreen(graph: Graph, onBack: () -> Unit) {
    val settings by graph.settings.flow.collectAsState(initial = graph.settings.current())
    val scope = rememberCoroutineScope()

    fun update(transform: (Settings) -> Settings) {
        scope.launch { graph.settings.update(transform) }
    }

    UtterScreen(title = stringResource(R.string.screen_appearance), onBack = onBack) {
        SectionCard(title = stringResource(R.string.appear_section_theme)) {
            ChoiceRow(
                title = stringResource(R.string.appear_theme),
                options = listOf(
                    stringResource(R.string.appear_theme_system),
                    stringResource(R.string.appear_theme_light),
                    stringResource(R.string.appear_theme_dark),
                ),
                selected = ThemeMode.entries.indexOf(settings.theme),
                onSelect = { index -> update { it.copy(theme = ThemeMode.entries[index]) } },
            )
            Spacer(modifier = Modifier.height(16.dp))
            ChoiceRow(
                title = stringResource(R.string.appear_palette),
                options = listOf(
                    stringResource(R.string.appear_palette_handy),
                    stringResource(R.string.appear_palette_dynamic),
                ),
                selected = Palette.entries.indexOf(settings.palette),
                onSelect = { index -> update { it.copy(palette = Palette.entries[index]) } },
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Text(
                    text = stringResource(R.string.appear_palette_dynamic_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        SectionCard(title = stringResource(R.string.appear_section_panel)) {
            ChoiceRow(
                title = stringResource(R.string.appear_panel_height),
                options = listOf(
                    stringResource(R.string.appear_height_compact),
                    stringResource(R.string.appear_height_normal),
                    stringResource(R.string.appear_height_tall),
                ),
                selected = PanelHeight.entries.indexOf(settings.panelHeight),
                onSelect = { index -> update { it.copy(panelHeight = PanelHeight.entries[index]) } },
            )
            Text(
                text = stringResource(R.string.appear_preview),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            PanelPreview(height = settings.panelHeight)
        }

        SectionCard(title = stringResource(R.string.appear_section_feedback)) {
            SwitchRow(
                title = stringResource(R.string.appear_haptics),
                subtitle = stringResource(R.string.appear_haptics_sub),
                checked = settings.haptics,
                onChange = { value -> update { it.copy(haptics = value) } },
            )
        }
    }
}

/** A non-interactive keyboard panel at the chosen height, drawn with the real panel components. */
@Composable
private fun PanelPreview(height: PanelHeight) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = previewHeight(height))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            StatusStrip(
                state = PreviewState,
                holdHint = false,
                selectionAvailable = false,
                speaking = false,
                onRetry = {},
                onDiscard = {},
                onUndo = {},
                onSwap = {},
                onReadAloud = {},
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
            KeyRow(
                action = EnterAction.NEWLINE,
                undoEnabled = false,
                minimal = false,
                onSwitchBack = {},
                onPickKeyboard = {},
                onSettings = {},
                onSpace = {},
                onUndo = {},
                onEnter = {},
            )
        }
    }
}
