package io.github.qaisarpipeshift.utter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.net.normalizeBaseUrl
import io.github.qaisarpipeshift.utter.ui.UtterScreen
import io.github.qaisarpipeshift.utter.ui.components.ChoiceRow
import io.github.qaisarpipeshift.utter.ui.components.PillTone
import io.github.qaisarpipeshift.utter.ui.components.SectionCard
import io.github.qaisarpipeshift.utter.ui.components.StatusPill
import io.github.qaisarpipeshift.utter.ui.theme.utterWarningColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saves the Server screen's values: URL, cleanup model, timeout, and the API key — the key only through
 * [io.github.qaisarpipeshift.utter.data.SecretStore]. Returns false when the URL is not a usable `https://` base.
 */
internal suspend fun persistServer(
    graph: Graph,
    rawUrl: String,
    key: String,
    cleanModel: String? = null,
    timeoutMs: Long? = null,
): Boolean {
    val base = normalizeBaseUrl(rawUrl) ?: return false
    // The secret is written first: if the Keystore refuses, the URL is not half-saved against the old key.
    val cleanKey = key.trim()
    if (cleanKey.isNotEmpty()) {
        withContext(Dispatchers.IO) { graph.secrets.save(cleanKey) }
    }
    graph.settings.update { current ->
        current.copy(
            baseUrl = base,
            cleanModel = cleanModel?.trim() ?: current.cleanModel,
            cleanTimeoutMs = timeoutMs ?: current.cleanTimeoutMs,
        )
    }
    return true
}

/**
 * URL and masked key fields, shared by the Server screen and the wizard's first step (docs/plan.md Task 12).
 *
 * The key is always masked unless the user taps the eye; it is a plain `String` in Compose state that is never
 * logged and never copied into [io.github.qaisarpipeshift.utter.data.Settings].
 */
@Composable
internal fun ServerForm(
    url: String,
    onUrlChange: (String) -> Unit,
    key: String,
    onKeyChange: (String) -> Unit,
    urlError: Boolean,
    modifier: Modifier = Modifier,
) {
    var keyVisible by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.server_url_label)) },
            placeholder = { Text(stringResource(R.string.server_url_hint)) },
            singleLine = true,
            isError = urlError,
            supportingText = if (urlError) {
                { Text(stringResource(R.string.server_invalid_url)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = key,
            onValueChange = onKeyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.server_key_label)) },
            placeholder = { Text(stringResource(R.string.server_key_hint)) },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        imageVector = if (keyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(
                            if (keyVisible) R.string.server_hide_key else R.string.server_show_key,
                        ),
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )
    }
}

/** The four probe rows: check/cross, the short probe detail and its elapsed milliseconds. */
@Composable
internal fun ConnectionChecklist(state: CheckUiState, modifier: Modifier = Modifier) {
    val labels = listOf(
        stringResource(R.string.server_check_reach),
        stringResource(R.string.server_check_key),
        stringResource(R.string.server_check_live),
        stringResource(R.string.server_check_models),
    )
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        labels.forEachIndexed { index, label ->
            val item = state.items.getOrElse(index) { CheckItem() }
            val detail = when {
                item.status == CheckStatus.PENDING -> stringResource(R.string.server_check_waiting)
                item.status == CheckStatus.RUNNING -> stringResource(R.string.server_check_running)
                index == 3 && item.status == CheckStatus.OK ->
                    stringResource(R.string.server_models_count, state.models.size)

                else -> item.detail
            }
            val ms = if (item.ms > 0) stringResource(R.string.server_check_ms, item.ms) else ""
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (item.status) {
                    CheckStatus.OK -> Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )

                    CheckStatus.FAIL -> Icon(
                        imageVector = Icons.Rounded.Cancel,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp),
                    )

                    CheckStatus.RUNNING -> CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )

                    CheckStatus.PENDING -> Icon(
                        imageVector = Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = listOf(detail, ms).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Cleanup timeout choices, in the values settings stores. */
private val TIMEOUT_VALUES = listOf(5_000L, 10_000L, 15_000L, 30_000L, 60_000L)

/**
 * Server settings (docs/spec.md §7, §15.5): URL, masked key, the four-probe Check, cleanup model and timeout, and
 * the Share setup entry point. Nothing here reads or writes the key outside SecretStore.
 */
@Composable
fun ServerScreen(graph: Graph, onBack: () -> Unit, onShareSetup: () -> Unit) {
    val initial = remember { graph.settings.current() }
    var url by remember { mutableStateOf(initial.baseUrl) }
    var key by remember { mutableStateOf("") }
    var cleanModel by remember { mutableStateOf(initial.cleanModel) }
    var timeoutMs by remember { mutableStateOf(initial.cleanTimeoutMs) }
    var modelMenu by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val checkViewModel: ConnectionCheckViewModel =
        viewModel(factory = connectionCheckViewModelFactory(graph.http))
    val check by checkViewModel.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(graph) {
        val stored = withContext(Dispatchers.IO) { graph.secrets.load() }.orEmpty()
        if (key.isBlank()) key = stored
    }

    val urlValid = normalizeBaseUrl(url) != null
    val urlError = url.isNotBlank() && !urlValid
    val timeoutLabels = List(TIMEOUT_VALUES.size) {
        stringResource(R.string.server_seconds, TIMEOUT_VALUES[it] / 1_000)
    }

    UtterScreen(title = stringResource(R.string.server_title), onBack = onBack) {
        SectionCard(title = stringResource(R.string.server_section_connection)) {
            ServerForm(
                url = url,
                onUrlChange = { url = it; saved = false; saveFailed = false; checkViewModel.reset() },
                key = key,
                onKeyChange = { key = it; saved = false; saveFailed = false; checkViewModel.reset() },
                urlError = urlError,
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = { checkViewModel.check(url, key) },
                enabled = urlValid && !check.running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (check.running) R.string.server_checking else R.string.server_check))
            }
            if (check.ran) {
                Spacer(modifier = Modifier.height(16.dp))
                ConnectionChecklist(state = check)
            }
        }

        SectionCard(title = stringResource(R.string.server_section_cleanup)) {
            Box {
                OutlinedTextField(
                    value = cleanModel,
                    onValueChange = { cleanModel = it; saved = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.server_model_label)) },
                    placeholder = { Text(stringResource(R.string.server_model_hint)) },
                    singleLine = true,
                    trailingIcon = {
                        if (check.models.isNotEmpty()) {
                            IconButton(onClick = { modelMenu = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.ArrowDropDown,
                                    contentDescription = stringResource(R.string.server_models_menu),
                                )
                            }
                        }
                    },
                )
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    check.models.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                cleanModel = model
                                modelMenu = false
                                saved = false
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            ChoiceRow(
                title = stringResource(R.string.server_timeout_label),
                options = timeoutLabels,
                selected = TIMEOUT_VALUES.indexOf(timeoutMs),
                onSelect = { index ->
                    timeoutMs = TIMEOUT_VALUES[index]
                    saved = false
                },
            )
        }

        SectionCard(title = stringResource(R.string.server_section_share)) {
            Text(
                text = stringResource(R.string.server_share_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = utterWarningColor(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onShareSetup, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.server_share_button))
            }
        }

        Button(
            onClick = {
                scope.launch {
                    saving = true
                    val ok = runCatching {
                        persistServer(graph, url, key, cleanModel, timeoutMs)
                    }.getOrDefault(false)
                    saving = false
                    saved = ok
                    saveFailed = !ok
                }
            },
            enabled = urlValid && !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (saving) R.string.server_saving else R.string.server_save))
        }
        if (saved) StatusPill(text = stringResource(R.string.server_saved), tone = PillTone.OK)
        if (saveFailed) StatusPill(text = stringResource(R.string.server_save_failed), tone = PillTone.ERROR)
    }
}
