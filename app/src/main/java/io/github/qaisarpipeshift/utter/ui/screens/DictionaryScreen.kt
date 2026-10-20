package io.github.qaisarpipeshift.utter.ui.screens

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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import io.github.qaisarpipeshift.utter.data.Replacement
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.ui.UtterScreen
import io.github.qaisarpipeshift.utter.ui.components.SectionCard
import kotlinx.coroutines.launch

/**
 * Vocabulary and word replacements (docs/spec.md §15.2, §16; docs/plan.md Task 13).
 *
 * Vocabulary is sent as server hints (`keyterms_prompt` / `transcription.prompt`), so it is a plain list of strings.
 * Replacements are whole-word and case-insensitive, and adding a `from` that already exists updates its target in
 * place instead of growing a list the pipeline would have to disambiguate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreen(graph: Graph, onBack: () -> Unit) {
    val settings by graph.settings.flow.collectAsState(initial = graph.settings.current())
    val scope = rememberCoroutineScope()
    var newWord by remember { mutableStateOf("") }
    var replaceFrom by remember { mutableStateOf("") }
    var replaceTo by remember { mutableStateOf("") }

    fun update(transform: (Settings) -> Settings) {
        scope.launch { graph.settings.update(transform) }
    }

    fun addWord() {
        val word = newWord.trim()
        if (word.isEmpty()) return
        update { current ->
            if (current.vocabulary.any { it.equals(word, ignoreCase = true) }) {
                current
            } else {
                current.copy(vocabulary = current.vocabulary + word)
            }
        }
        newWord = ""
    }

    fun addReplacement() {
        val from = replaceFrom.trim()
        val to = replaceTo.trim()
        if (from.isEmpty() || to.isEmpty()) return
        update { current ->
            val existing = current.replacements.indexOfFirst { it.from.equals(from, ignoreCase = true) }
            val list = current.replacements.toMutableList()
            val item = Replacement(from = from, to = to)
            if (existing >= 0) list[existing] = item else list.add(item)
            current.copy(replacements = list)
        }
        replaceFrom = ""
        replaceTo = ""
    }

    UtterScreen(title = stringResource(R.string.screen_dictionary), onBack = onBack) {
        SectionCard(title = stringResource(R.string.dict_section_vocabulary)) {
            Text(
                text = stringResource(R.string.dict_vocabulary_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newWord,
                    onValueChange = { newWord = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.dict_vocabulary_hint)) },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { addWord() }, enabled = newWord.isNotBlank()) {
                    Text(stringResource(R.string.common_add))
                }
            }
            if (settings.vocabulary.isEmpty()) {
                Text(
                    text = stringResource(R.string.dict_vocabulary_empty),
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
                    settings.vocabulary.forEach { word ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                update { current ->
                                    current.copy(
                                        vocabulary = current.vocabulary.filterNot { it == word },
                                    )
                                }
                            },
                            label = { Text(word) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.dict_remove_word, word),
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
            }
        }

        SectionCard(title = stringResource(R.string.dict_section_replacements)) {
            Text(
                text = stringResource(R.string.dict_replacements_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (settings.replacements.isEmpty()) {
                Text(
                    text = stringResource(R.string.dict_replacements_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    settings.replacements.forEachIndexed { index, replacement ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.dict_replace_row,
                                    replacement.from,
                                    replacement.to,
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    update { current ->
                                        current.copy(
                                            replacements = current.replacements.filterIndexed { i, _ ->
                                                i != index
                                            },
                                        )
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = stringResource(R.string.dict_delete_replacement),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = replaceFrom,
                onValueChange = { replaceFrom = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.dict_replace_from)) },
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = replaceTo,
                onValueChange = { replaceTo = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.dict_replace_to)) },
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { addReplacement() },
                enabled = replaceFrom.isNotBlank() && replaceTo.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.common_add))
            }
        }
    }
}
