package io.github.qaisarpipeshift.utter.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.core.DayStats
import io.github.qaisarpipeshift.utter.core.Stats
import io.github.qaisarpipeshift.utter.ui.OnResumeEffect
import io.github.qaisarpipeshift.utter.ui.UtterScreen
import io.github.qaisarpipeshift.utter.ui.components.EmptyState
import io.github.qaisarpipeshift.utter.ui.components.SectionCard
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Loads the 30-day summary; a ViewModel so a screen rotation does not re-read the stats file. */
class InsightsViewModel(private val graph: Graph) : ViewModel() {
    private val _summary = MutableStateFlow<Stats.Summary?>(null)
    val summary: StateFlow<Stats.Summary?> = _summary.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { _summary.value = graph.stats.summary() }
    }
}

internal fun insightsViewModelFactory(graph: Graph) = viewModelFactory {
    initializer { InsightsViewModel(graph) }
}

/**
 * Insights (docs/spec.md §15.4, §16; docs/plan.md Task 14).
 *
 * Totals are the numbers people quote ("I saved 12 minutes"); the bar chart is the last seven days of words, drawn
 * on a plain [Canvas] with the accent colour. The list of top apps uses the labels Utter recorded when the user
 * dictated in them, so it never needs package enumeration.
 */
@Composable
fun InsightsScreen(graph: Graph, onBack: () -> Unit) {
    val model: InsightsViewModel = viewModel(factory = insightsViewModelFactory(graph))
    val summary by model.summary.collectAsState()
    val settings by graph.settings.flow.collectAsState(initial = graph.settings.current())

    OnResumeEffect { model.refresh() }

    UtterScreen(title = stringResource(R.string.screen_insights), onBack = onBack) {
        val data = summary
        when {
            data == null -> Box(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            }

            data.total.takes == 0 -> EmptyState(
                icon = Icons.Rounded.BarChart,
                title = stringResource(R.string.insights_empty_title),
                body = stringResource(R.string.insights_empty_body),
            )

            else -> InsightsBody(data = data, seenApps = settings.seenApps)
        }
    }
}

@Composable
private fun ColumnScope.InsightsBody(data: Stats.Summary, seenApps: Map<String, String>) {
        SectionCard(title = stringResource(R.string.insights_section_totals)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InsightTile(
                    value = data.total.takes.toString(),
                    label = stringResource(R.string.home_stat_takes),
                    modifier = Modifier.weight(1f),
                )
                InsightTile(
                    value = data.total.words.toString(),
                    label = stringResource(R.string.home_stat_words),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InsightTile(
                    value = stringResource(R.string.home_stat_minutes, data.total.speechMs / 60_000L),
                    label = stringResource(R.string.insights_stat_minutes),
                    modifier = Modifier.weight(1f),
                )
                InsightTile(
                    value = stringResource(R.string.home_stat_minutes, data.total.timeSavedMs / 60_000L),
                    label = stringResource(R.string.home_stat_saved),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SectionCard(title = stringResource(R.string.insights_section_week)) {
            WeekChart(series = data.series)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                data.series.forEach { day ->
                    Text(
                        text = dayLabel(day.date),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        SectionCard(title = stringResource(R.string.insights_section_apps)) {
            if (data.topApps.isEmpty()) {
                Text(
                    text = stringResource(R.string.insights_no_apps),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                data.topApps.forEach { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = seenApps[app.pkg] ?: app.pkg,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = pluralStringResource(R.plurals.insights_takes_count, app.takes, app.takes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
}

@Composable
private fun InsightTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Seven accent bars, one per day, scaled to the busiest day; zero days keep a faint baseline. */
@Composable
private fun WeekChart(series: List<DayStats>) {
    if (series.isNotEmpty()) {
        val accent = MaterialTheme.colorScheme.primary
        val maxWords = series.maxOfOrNull { it.words }?.coerceAtLeast(1) ?: 1
        Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
            val count = series.size
            val gap = size.width * 0.02f
            val barWidth = (size.width - gap * (count + 1)) / count
            val baseline = 2.dp.toPx()
            series.forEachIndexed { index, day ->
                val height = if (day.words == 0) {
                    baseline
                } else {
                    (size.height * day.words.toFloat() / maxWords).coerceAtLeast(baseline)
                }
                drawRoundRect(
                    color = if (day.words == 0) accent.copy(alpha = 0.18f) else accent,
                    topLeft = Offset(
                        x = gap + index * (barWidth + gap),
                        y = size.height - height,
                    ),
                    size = Size(width = barWidth, height = height),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                )
            }
        }
    }
}

private fun dayLabel(date: LocalDate): String =
    date.dayOfWeek.getDisplayName(JavaTextStyle.NARROW, Locale.getDefault())
