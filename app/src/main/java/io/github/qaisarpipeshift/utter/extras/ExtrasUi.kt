package io.github.qaisarpipeshift.utter.extras

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.data.ThemeMode
import io.github.qaisarpipeshift.utter.ui.theme.UtterTheme

/**
 * Shared chrome for the on-demand extras (docs/spec.md §17, docs/plan.md Task 15).
 *
 * Why a bottom sheet instead of a dialog: these screens appear over another app — a text selection, a share sheet,
 * the Quick Settings panel — so they must feel like a temporary layer, not a second app. The activity window itself
 * is translucent (`Theme.Utter.Dialog`); this composable adds the dim scrim, the Handy 16 dp top corners and the
 * navigation-bar inset, keeping every extra visually identical to the keyboard panel.
 */
@Composable
fun ExtrasSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f))
                .clickable(onClick = onDismiss),
        )
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                content = content,
            )
        }
    }
}

/** The extras' theme follows the saved theme and palette, exactly like the keyboard and the settings app. */
@Composable
fun ExtrasTheme(graph: Graph, content: @Composable () -> Unit) {
    val settings by graph.settings.flow.collectAsState(initial = graph.settings.current())
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    UtterTheme(dark = dark, palette = settings.palette, content = content)
}

/** One calm sheet title. */
@Composable
fun SheetTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleLarge)
}

/** A quiet explanation under a title. */
@Composable
fun SheetBody(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(
        text = text,
        modifier = modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One action in a sheet: a 48 dp pill, filled for the primary action and softly tinted for the rest.
 *
 * Why not Material's Button: the extras share the panel's flat tokens — one accent, no elevation, no ripples that
 * outshine the content — so the pill is the same shape the panel's result chips use.
 */
@Composable
fun SheetAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val background = if (primary) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    }
    val textColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        primary -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(background.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, fontSize = 13.sp, color = textColor)
    }
}

/** A centered progress line for the short waits these extras have (transcribe, clean, speak). */
@Composable
fun SheetWorking(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
