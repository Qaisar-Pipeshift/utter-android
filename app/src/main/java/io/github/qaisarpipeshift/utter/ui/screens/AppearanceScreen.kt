package io.github.qaisarpipeshift.utter.ui.screens

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.core.EnterAction
import io.github.qaisarpipeshift.utter.core.PanelState
import io.github.qaisarpipeshift.utter.data.Palette
import io.github.qaisarpipeshift.utter.data.PanelHeight
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.data.ThemeMode
import io.github.qaisarpipeshift.utter.ime.KeyboardPanel
import io.github.qaisarpipeshift.utter.ime.NoopPanelHost
import io.github.qaisarpipeshift.utter.ui.UtterScreen
import io.github.qaisarpipeshift.utter.ui.components.ChoiceRow
import io.github.qaisarpipeshift.utter.ui.components.SectionCard
import io.github.qaisarpipeshift.utter.ui.components.SwitchRow
import kotlinx.coroutines.launch

/**
 * Appearance settings (docs/spec.md §15.5, §16; docs/plan.md Task 13).
 *
 * Why the preview is the keyboard's own [KeyboardPanel]: theme, palette and height decisions are only meaningful
 * against the real chrome, and the hand-made mock had drifted (it lost the backspace/sparkle row and used different
 * padding). Here the panel is non-interactive — no gesture may eat a drag meant for this scrolling page — and it
 * skips the navigation-bar inset, which the page already handles. At phone widths it fits the card unscaled.
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
            PanelPreview(settings = settings)
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

/** The real idle panel at the chosen height, non-interactive and without the navigation-bar inset. */
@Composable
private fun PanelPreview(settings: Settings) {
    KeyboardPanel(
        state = PanelState.Idle(result = null, pendingCount = 0, offline = false),
        settings = settings,
        selectionAvailable = false,
        speaking = false,
        enterAction = EnterAction.NEWLINE,
        holdHint = false,
        interactive = false,
        applyInsets = false,
        host = NoopPanelHost,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
    )
}
