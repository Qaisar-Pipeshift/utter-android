package io.github.qaisarpipeshift.utter.ime

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Scaffold panel: replaced by the full panel (docs/plan.md, Task 9). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Panel(onSwitchBack: () -> Unit, onPickKeyboard: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier.fillMaxWidth().height(220.dp).navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Icon(
                imageVector = Icons.Rounded.Language,
                contentDescription = "Switch keyboard",
                modifier = Modifier.combinedClickable(onClick = onSwitchBack, onLongClick = onPickKeyboard),
            )
            Text("Utter", style = MaterialTheme.typography.titleMedium)
        }
    }
}
