package io.github.qaisarpipeshift.utter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.qaisarpipeshift.utter.ui.theme.utterWarningColor

/** What a [StatusPill] is saying: things are fine, worth a look, broken, or simply neutral. */
enum class PillTone { OK, WARN, ERROR, NEUTRAL }

/**
 * A small rounded status label (docs/plan.md Task 11).
 *
 * Why a pill instead of coloured body text: statuses appear next to check rows, permission steps and the Server
 * screen, and a filled shape stays legible in both themes without an extra icon.
 */
@Composable
fun StatusPill(text: String, tone: PillTone = PillTone.OK, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (background, content) = when (tone) {
        PillTone.OK -> scheme.primaryContainer to scheme.onPrimaryContainer
        PillTone.WARN -> utterWarningColor().copy(alpha = 0.16f) to utterWarningColor()
        PillTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
        PillTone.NEUTRAL -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = content,
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}
