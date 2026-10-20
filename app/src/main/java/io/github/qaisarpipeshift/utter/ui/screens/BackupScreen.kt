package io.github.qaisarpipeshift.utter.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.data.Backup
import io.github.qaisarpipeshift.utter.ui.UtterScreen
import io.github.qaisarpipeshift.utter.ui.components.PillTone
import io.github.qaisarpipeshift.utter.ui.components.SectionCard
import io.github.qaisarpipeshift.utter.ui.components.StatusPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backup and restore through the Storage Access Framework (docs/spec.md §15.5, docs/plan.md Task 14).
 *
 * Export writes [Backup.encode], which never contains the API key: the key lives encrypted in `SecretStore`, outside
 * [io.github.qaisarpipeshift.utter.data.Settings]. Import decodes and validates first, shows how many settings will
 * change, and only applies on confirmation — a mismatched JSON file cannot silently reset the app.
 */
@Composable
fun BackupScreen(graph: Graph, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var resultRes by remember { mutableStateOf<Int?>(null) }
    var resultOk by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Backup.Summary?>(null) }

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                scope.launch {
                    busy = true
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val text = Backup.encode(graph.settings.current())
                            val stream = context.contentResolver.openOutputStream(uri)
                                ?: error("no output stream")
                            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        }.isSuccess
                    }
                    busy = false
                    resultOk = ok
                    resultRes = if (ok) R.string.backup_exported else R.string.backup_export_failed
                }
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    busy = true
                    val text = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        }.getOrNull()
                    }
                    val imported = Backup.decode(text)
                    busy = false
                    if (imported == null) {
                        resultOk = false
                        resultRes = R.string.backup_import_failed
                    } else {
                        resultRes = null
                        pendingImport = Backup.summary(graph.settings.current(), imported)
                    }
                }
            }
        }

    UtterScreen(title = stringResource(R.string.screen_backup), onBack = onBack) {
        SectionCard(title = stringResource(R.string.backup_section_export)) {
            Text(
                text = stringResource(R.string.backup_export_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { exportLauncher.launch(Backup.FILE_NAME) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.backup_export_button))
            }
        }

        SectionCard(title = stringResource(R.string.backup_section_import)) {
            Text(
                text = stringResource(R.string.backup_import_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    importLauncher.launch(
                        arrayOf("application/json", "text/plain", "application/octet-stream"),
                    )
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.backup_import_button))
            }
        }

        val shownResult = resultRes
        if (shownResult != null) {
            StatusPill(
                tone = if (resultOk) PillTone.OK else PillTone.ERROR,
                text = stringResource(shownResult),
            )
        }
    }

    val summary = pendingImport
    if (summary != null) {
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = { Text(stringResource(R.string.backup_confirm_body, summary.changed)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImport = null
                        scope.launch {
                            busy = true
                            graph.settings.update { summary.imported }
                            busy = false
                            resultOk = true
                            resultRes = R.string.backup_imported
                        }
                    },
                ) {
                    Text(stringResource(R.string.backup_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }
}
