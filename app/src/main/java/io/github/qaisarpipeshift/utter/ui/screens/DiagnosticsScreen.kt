package io.github.qaisarpipeshift.utter.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Process
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.ui.UtterScreen
import io.github.qaisarpipeshift.utter.ui.components.SectionCard
import io.github.qaisarpipeshift.utter.ui.components.SettingRow

/**
 * Diagnostics (docs/spec.md §15.5, §16; docs/plan.md Task 14).
 *
 * Shows the bounded [io.github.qaisarpipeshift.utter.diag.Diag] buffer exactly as recorded — event kinds, codes and
 * timings; never text, audio or the key — plus the process's own CPU time. Copy produces a paste-ready bug report.
 */
@Composable
fun DiagnosticsScreen(graph: Graph, onBack: () -> Unit) {
    val context = LocalContext.current
    var dump by remember { mutableStateOf(graph.diag.dump()) }
    var cpuMs by remember { mutableLongStateOf(Process.getElapsedCpuTime()) }

    fun refresh() {
        dump = graph.diag.dump()
        cpuMs = Process.getElapsedCpuTime()
    }

    UtterScreen(title = stringResource(R.string.screen_diagnostics), onBack = onBack) {
        SectionCard(title = stringResource(R.string.diag_section_events)) {
            Text(
                text = stringResource(R.string.diag_events_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val shown = if (dump.isBlank()) stringResource(R.string.diag_empty) else dump
            SelectionContainer {
                Text(
                    text = shown,
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        copyToClipboard(context, dump)
                        Toast.makeText(context, R.string.diag_copied, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.diag_copy))
                }
                OutlinedButton(onClick = { refresh() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.diag_refresh))
                }
            }
        }

        SectionCard(title = stringResource(R.string.diag_section_device)) {
            SettingRow(
                title = stringResource(R.string.diag_cpu),
                value = stringResource(R.string.diag_cpu_value, cpuMs / 1_000L),
            )
            SettingRow(
                title = stringResource(R.string.diag_events_count),
                value = dump.lineSequence().count { it.isNotBlank() }.toString(),
            )
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
}
