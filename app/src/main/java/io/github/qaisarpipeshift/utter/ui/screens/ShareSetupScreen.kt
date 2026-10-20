package io.github.qaisarpipeshift.utter.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.data.SetupLink
import io.github.qaisarpipeshift.utter.ui.UtterScreen
import io.github.qaisarpipeshift.utter.ui.components.SectionCard
import io.github.qaisarpipeshift.utter.ui.theme.utterWarningColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds and shares the `utter://setup` link (docs/spec.md §17, docs/plan.md Task 12).
 *
 * The warning is not decoration: the link is a bearer credential, so the screen says so before showing it. The key
 * is read from SecretStore at composition time and rendered nowhere except inside the encoded link.
 */
@Composable
fun ShareSetupScreen(graph: Graph, onBack: () -> Unit) {
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    val chooserTitle = stringResource(R.string.share_chooser)
    var link by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(graph) {
        val settings = graph.settings.current()
        val key = withContext(Dispatchers.IO) { graph.secrets.load() }.orEmpty()
        link = SetupLink.build(settings.baseUrl, key)
        loaded = true
    }

    UtterScreen(title = stringResource(R.string.share_title), onBack = onBack) {
        SectionCard(title = stringResource(R.string.server_section_share)) {
            Text(
                text = stringResource(R.string.server_share_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = utterWarningColor(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.share_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!loaded) {
            // One frame; the key decrypt happens on IO.
        } else if (link == null) {
            SectionCard(title = stringResource(R.string.share_missing_title)) {
                Text(
                    text = stringResource(R.string.share_no_key),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val setupLink = link!!
            SectionCard(title = stringResource(R.string.share_link_title)) {
                Text(
                    text = setupLink,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(
                                ClipData.newPlainText(appName, setupLink),
                            )
                            copied = true
                            Toast.makeText(context, R.string.share_copied, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(if (copied) R.string.share_copied else R.string.share_copy))
                    }
                    Button(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, setupLink)
                                .putExtra(Intent.EXTRA_SUBJECT, chooserTitle)
                            context.startActivity(
                                Intent.createChooser(send, chooserTitle),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.share_send))
                    }
                }
            }
        }
    }
}
