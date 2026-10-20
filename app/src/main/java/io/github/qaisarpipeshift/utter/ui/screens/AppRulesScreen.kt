package io.github.qaisarpipeshift.utter.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.data.AppRule
import io.github.qaisarpipeshift.utter.data.RuleAction
import io.github.qaisarpipeshift.utter.ui.UtterScreen
import io.github.qaisarpipeshift.utter.ui.components.ChoiceRow
import io.github.qaisarpipeshift.utter.ui.components.EmptyState
import io.github.qaisarpipeshift.utter.ui.components.SectionCard
import io.github.qaisarpipeshift.utter.ui.components.SwitchRow
import kotlinx.coroutines.launch

/**
 * Per-app rules (docs/spec.md §15.3, §16; docs/plan.md Task 14).
 *
 * The list comes from `seenApps` — packages Utter has actually dictated in — so no package-enumeration permission is
 * needed. Choosing "Preset" stores the preset id with the rule; choosing Raw or Default clears it, so a later change
 * of the default preset cannot silently alter an app's behaviour.
 */
@Composable
fun AppRulesScreen(graph: Graph, onBack: () -> Unit) {
    val settings by graph.settings.flow.collectAsState(initial = graph.settings.current())
    val scope = rememberCoroutineScope()
    val apps = remember(settings.seenApps) {
        settings.seenApps.entries.sortedBy { it.value.lowercase() }
    }

    fun updateRule(pkg: String, transform: (AppRule) -> AppRule) {
        scope.launch {
            graph.settings.update { current ->
                val existing = current.appRules.firstOrNull { it.pkg == pkg } ?: AppRule(pkg)
                val updated = transform(existing)
                current.copy(appRules = current.appRules.filterNot { it.pkg == pkg } + updated)
            }
        }
    }

    UtterScreen(title = stringResource(R.string.screen_app_rules), onBack = onBack) {
        if (apps.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Apps,
                title = stringResource(R.string.rules_empty_title),
                body = stringResource(R.string.rules_empty_body),
            )
        } else {
            Text(
                text = stringResource(R.string.rules_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            apps.forEach { (pkg, label) ->
                val rule = settings.appRules.firstOrNull { it.pkg == pkg } ?: AppRule(pkg)
                SectionCard(title = label) {
                    Text(
                        text = pkg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    ChoiceRow(
                        title = stringResource(R.string.rules_action),
                        options = listOf(
                            stringResource(R.string.rules_action_default),
                            stringResource(R.string.rules_action_raw),
                            stringResource(R.string.rules_action_preset),
                        ),
                        selected = when (rule.action) {
                            RuleAction.DEFAULT -> 0
                            RuleAction.RAW -> 1
                            RuleAction.CLEAN -> 2
                        },
                        onSelect = { index ->
                            updateRule(pkg) { current ->
                                when (index) {
                                    0 -> current.copy(action = RuleAction.DEFAULT, presetId = null)
                                    1 -> current.copy(action = RuleAction.RAW, presetId = null)
                                    else -> current.copy(
                                        action = RuleAction.CLEAN,
                                        presetId = current.presetId ?: settings.defaultPresetId,
                                    )
                                }
                            }
                        },
                    )
                    if (rule.action == RuleAction.CLEAN) {
                        val presetId = rule.presetId ?: settings.defaultPresetId
                        ChoiceRow(
                            title = stringResource(R.string.rules_preset),
                            options = settings.presets.map { it.name },
                            selected = settings.presets.indexOfFirst { it.id == presetId },
                            onSelect = { index ->
                                settings.presets.getOrNull(index)?.let { preset ->
                                    updateRule(pkg) { current ->
                                        current.copy(action = RuleAction.CLEAN, presetId = preset.id)
                                    }
                                }
                            },
                        )
                    }
                    SwitchRow(
                        title = stringResource(R.string.rules_auto_send),
                        subtitle = stringResource(R.string.rules_auto_send_sub),
                        checked = rule.autoSend,
                        onChange = { value -> updateRule(pkg) { it.copy(autoSend = value) } },
                    )
                    SwitchRow(
                        title = stringResource(R.string.rules_block),
                        subtitle = stringResource(R.string.rules_block_sub),
                        checked = rule.block,
                        onChange = { value -> updateRule(pkg) { it.copy(block = value) } },
                    )
                    SwitchRow(
                        title = stringResource(R.string.rules_history_off),
                        subtitle = stringResource(R.string.rules_history_off_sub),
                        checked = rule.historyOff,
                        onChange = { value -> updateRule(pkg) { it.copy(historyOff = value) } },
                    )
                }
            }
        }
    }
}
