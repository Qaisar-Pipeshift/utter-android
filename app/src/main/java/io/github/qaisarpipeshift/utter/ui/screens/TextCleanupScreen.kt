package io.github.qaisarpipeshift.utter.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.core.Preset
import io.github.qaisarpipeshift.utter.core.Presets
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.ui.UtterScreen
import io.github.qaisarpipeshift.utter.ui.components.ChoiceRow
import io.github.qaisarpipeshift.utter.ui.components.SectionCard
import io.github.qaisarpipeshift.utter.ui.components.SwitchRow
import java.util.UUID
import kotlinx.coroutines.launch

/** A preset being edited; [id] is null for a new one, so Save knows whether to append or replace. */
private data class PresetDraft(
    val id: String?,
    val name: String,
    val prompt: String,
    val builtIn: Boolean,
)

/**
 * Text and cleanup settings (docs/spec.md §15.2, §16; docs/plan.md Task 13).
 *
 * The preset list is the screen's heart: every row edits in a dialog, the arrows reorder it, built-ins can be
 * restored to their shipped prompt, and only custom presets can be deleted. All writes go through
 * `SettingsRepo.update`; the whole list (not an id) is persisted so a user's edits survive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextCleanupScreen(graph: Graph, onBack: () -> Unit) {
    val settings by graph.settings.flow.collectAsState(initial = graph.settings.current())
    val scope = rememberCoroutineScope()
    var customFiller by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<PresetDraft?>(null) }

    fun update(transform: (Settings) -> Settings) {
        scope.launch { graph.settings.update(transform) }
    }

    fun addFiller() {
        val word = customFiller.trim()
        if (word.isEmpty()) return
        update { current ->
            if (current.customFillers.any { it.equals(word, ignoreCase = true) }) {
                current
            } else {
                current.copy(customFillers = current.customFillers + word)
            }
        }
        customFiller = ""
    }

    UtterScreen(title = stringResource(R.string.screen_text_cleanup), onBack = onBack) {
        SectionCard(title = stringResource(R.string.text_section_spacing)) {
            SwitchRow(
                title = stringResource(R.string.text_smart_spacing),
                subtitle = stringResource(R.string.text_smart_spacing_sub),
                checked = settings.smartSpacing,
                onChange = { value -> update { it.copy(smartSpacing = value) } },
            )
            SwitchRow(
                title = stringResource(R.string.text_trailing_space),
                subtitle = stringResource(R.string.text_trailing_space_sub),
                checked = settings.trailingSpace,
                onChange = { value -> update { it.copy(trailingSpace = value) } },
            )
            SwitchRow(
                title = stringResource(R.string.text_capitalize),
                subtitle = stringResource(R.string.text_capitalize_sub),
                checked = settings.capitalize,
                onChange = { value -> update { it.copy(capitalize = value) } },
            )
        }

        SectionCard(title = stringResource(R.string.text_section_raw)) {
            SwitchRow(
                title = stringResource(R.string.text_fillers),
                subtitle = stringResource(R.string.text_fillers_sub),
                checked = settings.removeFillers,
                onChange = { value -> update { it.copy(removeFillers = value) } },
            )
            if (settings.removeFillers) {
                Text(
                    text = stringResource(R.string.text_custom_fillers),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = customFiller,
                        onValueChange = { customFiller = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.text_custom_fillers_hint)) },
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { addFiller() }, enabled = customFiller.isNotBlank()) {
                        Text(stringResource(R.string.common_add))
                    }
                }
                if (settings.customFillers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.text_custom_fillers_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        settings.customFillers.forEach { word ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    update { current ->
                                        current.copy(
                                            customFillers = current.customFillers.filterNot { it == word },
                                        )
                                    }
                                },
                                label = { Text(word) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.text_remove_word, word),
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }
            }
            SwitchRow(
                title = stringResource(R.string.text_spoken_punctuation),
                subtitle = stringResource(R.string.text_spoken_punctuation_sub),
                checked = settings.spokenPunctuation,
                onChange = { value -> update { it.copy(spokenPunctuation = value) } },
            )
        }

        SectionCard(title = stringResource(R.string.text_section_presets)) {
            Text(
                text = stringResource(R.string.text_presets_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            settings.presets.forEachIndexed { index, preset ->
                PresetRow(
                    preset = preset,
                    index = index,
                    count = settings.presets.size,
                    onEdit = { editing = PresetDraft(preset.id, preset.name, preset.prompt, preset.builtIn) },
                    onMoveUp = {
                        update { current ->
                            val list = current.presets.toMutableList()
                            val target = index - 1
                            if (index in list.indices && target in list.indices) {
                                list.add(target, list.removeAt(index))
                            }
                            current.copy(presets = list)
                        }
                    },
                    onMoveDown = {
                        update { current ->
                            val list = current.presets.toMutableList()
                            val target = index + 1
                            if (index in list.indices && target in list.indices) {
                                list.add(target, list.removeAt(index))
                            }
                            current.copy(presets = list)
                        }
                    },
                )
            }
            TextButton(onClick = { editing = PresetDraft(null, "", "", false) }) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.text_add_preset))
            }
            Spacer(modifier = Modifier.height(8.dp))
            ChoiceRow(
                title = stringResource(R.string.text_default_preset),
                options = settings.presets.map { it.name },
                selected = settings.presets.indexOfFirst { it.id == settings.defaultPresetId },
                onSelect = { index ->
                    settings.presets.getOrNull(index)?.let { preset ->
                        update { it.copy(defaultPresetId = preset.id) }
                    }
                },
            )
        }

        SectionCard(title = stringResource(R.string.text_section_after)) {
            SwitchRow(
                title = stringResource(R.string.text_voice_edit),
                subtitle = stringResource(R.string.text_voice_edit_sub),
                checked = settings.voiceEdit,
                onChange = { value -> update { it.copy(voiceEdit = value) } },
            )
            SwitchRow(
                title = stringResource(R.string.text_auto_enter),
                subtitle = stringResource(R.string.text_auto_enter_sub),
                checked = settings.autoEnter,
                onChange = { value -> update { it.copy(autoEnter = value) } },
            )
            SwitchRow(
                title = stringResource(R.string.text_copy),
                subtitle = stringResource(R.string.text_copy_sub),
                checked = settings.copyToClipboard,
                onChange = { value -> update { it.copy(copyToClipboard = value) } },
            )
            SwitchRow(
                title = stringResource(R.string.text_return_prev),
                subtitle = stringResource(R.string.text_return_prev_sub),
                checked = settings.returnToPrevious,
                onChange = { value -> update { it.copy(returnToPrevious = value) } },
            )
        }
    }

    val draft = editing
    if (draft != null) {
        PresetDialog(
            draft = draft,
            onDismiss = { editing = null },
            onSave = { name, prompt ->
                val id = draft.id ?: UUID.randomUUID().toString()
                val saved = Preset(id = id, name = name, prompt = prompt, builtIn = draft.builtIn)
                update { current ->
                    current.copy(
                        presets = if (draft.id == null) {
                            current.presets + saved
                        } else {
                            current.presets.map { if (it.id == id) saved else it }
                        },
                    )
                }
                editing = null
            },
            onReset = {
                val original = Presets.defaults.firstOrNull { it.id == draft.id }
                if (original != null) {
                    update { current ->
                        current.copy(
                            presets = current.presets.map { if (it.id == original.id) original else it },
                        )
                    }
                }
                editing = null
            },
            onDelete = {
                val id = draft.id
                if (id != null) {
                    update { current ->
                        val remaining = current.presets.filterNot { it.id == id }
                        current.copy(
                            presets = remaining,
                            defaultPresetId = if (current.defaultPresetId == id) {
                                remaining.firstOrNull()?.id ?: Presets.CLEAN_ID
                            } else {
                                current.defaultPresetId
                            },
                        )
                    }
                }
                editing = null
            },
        )
    }
}

/** One preset: the name block edits, the arrows reorder, the pencil edits. */
@Composable
private fun PresetRow(
    preset: Preset,
    index: Int,
    count: Int,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit)
                .padding(vertical = 8.dp),
        ) {
            Text(text = preset.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(
                    if (preset.builtIn) R.string.text_preset_builtin else R.string.text_preset_custom,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onMoveUp, enabled = index > 0) {
            Icon(
                imageVector = Icons.Rounded.ArrowUpward,
                contentDescription = stringResource(R.string.text_preset_move_up),
            )
        }
        IconButton(onClick = onMoveDown, enabled = index < count - 1) {
            Icon(
                imageVector = Icons.Rounded.ArrowDownward,
                contentDescription = stringResource(R.string.text_preset_move_down),
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = stringResource(R.string.text_preset_edit),
            )
        }
    }
}

/** Name + prompt editor; Reset restores a built-in, Delete removes a custom preset. */
@Composable
private fun PresetDialog(
    draft: PresetDraft,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(draft) { mutableStateOf(draft.name) }
    var prompt by remember(draft) { mutableStateOf(draft.prompt) }
    val canSave = name.isNotBlank() && prompt.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (draft.id == null) R.string.text_preset_new_title else R.string.text_preset_edit_title,
                ),
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.text_preset_name)) },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.text_preset_prompt)) },
                    minLines = 5,
                    maxLines = 10,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (draft.builtIn) {
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.text_preset_reset))
                    }
                } else {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.text_preset_delete))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), prompt.trim()) }, enabled = canSave) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}
