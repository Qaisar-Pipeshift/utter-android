package io.github.qaisarpipeshift.utter.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.core.ResourceDay
import io.github.qaisarpipeshift.utter.core.ResourceMath
import io.github.qaisarpipeshift.utter.ui.UtterScreen
import io.github.qaisarpipeshift.utter.ui.components.SectionCard
import io.github.qaisarpipeshift.utter.ui.components.SettingRow
import io.github.qaisarpipeshift.utter.ui.theme.utterWarningColor
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * Diagnostics (docs/spec.md §15.5, §16; docs/plan.md Task 14; resource ledger v0.1.4).
 *
 * Shows the bounded [io.github.qaisarpipeshift.utter.diag.Diag] buffer exactly as recorded — event kinds, codes and
 * timings; never text, audio or the key — plus the process's own CPU time. Copy produces a paste-ready bug report.
 *
 * The Battery & resources card opens [Graph.ledger]'s snapshot: that is one of the three moments deltas are written
 * to disk, so the numbers shown are durable the moment they are read.
 */
@Composable
fun DiagnosticsScreen(graph: Graph, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dump by remember { mutableStateOf(graph.diag.dump()) }
    var cpuMs by remember { mutableLongStateOf(Process.getElapsedCpuTime()) }
    var resources by remember { mutableStateOf<List<ResourceDay>?>(null) }

    LaunchedEffect(graph) { resources = graph.ledger.snapshot() }

    fun refresh() {
        dump = graph.diag.dump()
        cpuMs = Process.getElapsedCpuTime()
        scope.launch { resources = graph.ledger.snapshot() }
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

        SectionCard(title = stringResource(R.string.diag_section_resources)) {
            Text(
                text = stringResource(R.string.diag_resources_estimate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val days = resources
            if (days == null) {
                Text(
                    text = stringResource(R.string.diag_resources_loading),
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val series = ResourceMath.series(days, LocalDate.now())
                val today = series.last()
                Text(
                    text = stringResource(R.string.diag_resources_today_value, ResourceMath.estimateMah(today)),
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SettingRow(
                    title = stringResource(R.string.diag_resources_visible),
                    value = seconds(today.cpuVisibleMs),
                )
                SettingRow(
                    title = stringResource(R.string.diag_resources_hidden),
                    value = seconds(today.cpuHiddenMs),
                )
                SettingRow(
                    title = stringResource(R.string.diag_resources_mic),
                    value = seconds(today.micMs),
                )
                SettingRow(
                    title = stringResource(R.string.diag_resources_network),
                    value = stringResource(
                        R.string.diag_resources_network_value,
                        Formatter.formatShortFileSize(context, today.txBytes),
                        Formatter.formatShortFileSize(context, today.rxBytes),
                    ),
                )
                SettingRow(
                    title = stringResource(R.string.diag_resources_takes),
                    value = stringResource(
                        R.string.diag_resources_takes_value,
                        today.takes,
                        today.liveTakes,
                        today.uploadTakes,
                    ),
                )
                Text(
                    text = stringResource(R.string.diag_resources_week),
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ResourceChart(series = series)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(utterWarningColor()),
                    )
                    Text(
                        text = stringResource(R.string.diag_resources_hidden_legend),
                        modifier = Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { openAppSettings(context) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text(stringResource(R.string.diag_resources_open))
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

/** Seven estimated-mAh bars, hidden-CPU share drawn on top in the warning colour so idle drain is visible. */
@Composable
private fun ResourceChart(series: List<ResourceDay>) {
    val accent = MaterialTheme.colorScheme.primary
    val hiddenColor = utterWarningColor()
    val peak = (series.maxOfOrNull { ResourceMath.estimateMah(it) } ?: 0.0).coerceAtLeast(0.01)
    Canvas(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(120.dp)) {
        val count = series.size.coerceAtLeast(1)
        val gap = size.width * 0.02f
        val barWidth = (size.width - gap * (count + 1)) / count
        val baseline = 2.dp.toPx()
        series.forEachIndexed { index, day ->
            val total = ResourceMath.estimateMah(day)
            val height = if (total <= 0.0) {
                baseline
            } else {
                (size.height * total / peak).toFloat().coerceAtLeast(baseline)
            }
            val hidden = (size.height * ResourceMath.hiddenCpuMah(day) / peak).toFloat().coerceAtMost(height)
            val x = gap + index * (barWidth + gap)
            drawRoundRect(
                color = if (total <= 0.0) accent.copy(alpha = 0.18f) else accent,
                topLeft = Offset(x, size.height - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(6.dp.toPx()),
            )
            if (hidden > 0f) {
                drawRoundRect(
                    color = hiddenColor,
                    topLeft = Offset(x, size.height - height),
                    size = Size(barWidth, hidden),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun seconds(ms: Long): String = stringResource(R.string.diag_resources_seconds, ms / 1_000L)

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
}

/** Android's per-app settings page, where the system's own battery usage for Utter lives. */
private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // A stripped build without the Settings app: there is nothing to open.
    }
}
