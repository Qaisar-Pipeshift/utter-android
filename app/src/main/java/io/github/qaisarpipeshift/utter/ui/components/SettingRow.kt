package io.github.qaisarpipeshift.utter.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A tappable label/value row with a chevron (docs/plan.md Task 11).
 *
 * [value] is the current choice shown to the right of the title — a short summary such as "Handy pink" — never a
 * secret: the API key lives in SecretStore and is edited only on the Server screen with a masked field.
 */
@Composable
fun SettingRow(
    title: String,
    value: String = "",
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .let { base -> if (onClick != null) base.clickable(onClick = onClick) else base }
        .padding(vertical = 8.dp)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // A card's surfaceVariant background would otherwise tint titles with onSurfaceVariant; the title is
            // primary text and must stay onSurface. Only values and descriptions are secondary.
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (value.isNotBlank()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onClick != null) {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
