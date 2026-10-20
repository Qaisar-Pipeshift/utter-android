package io.github.qaisarpipeshift.utter.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.net.Api
import io.github.qaisarpipeshift.utter.net.Probe
import io.github.qaisarpipeshift.utter.net.ServerConfig
import io.github.qaisarpipeshift.utter.ui.OnResumeEffect
import io.github.qaisarpipeshift.utter.ui.Routes
import io.github.qaisarpipeshift.utter.ui.components.PillTone
import io.github.qaisarpipeshift.utter.ui.components.SectionCard
import io.github.qaisarpipeshift.utter.ui.components.SettingRow
import io.github.qaisarpipeshift.utter.ui.components.StatusPill
import io.github.qaisarpipeshift.utter.ui.components.SwitchRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The three probes Home shows, filled in as each one returns (docs/spec.md §15.5). */
data class HomeCheck(
    val baseUrl: String,
    val reach: Probe? = null,
    val key: Probe? = null,
    val live: Probe? = null,
    val models: Probe? = null,
    val modelCount: Int = 0,
)

/** Everything the dashboard draws: the in-memory server check plus today's counters and the pending queue. */
data class HomeUiState(
    val checking: Boolean = false,
    val check: HomeCheck? = null,
    val setupNeeded: Boolean = false,
    val takes: Int = 0,
    val words: Int = 0,
    val timeSavedMs: Long = 0L,
    val pending: Int = 0,
)

/**
 * Process-wide memory of the last Home check (docs/plan.md Task 13).
 *
 * Why not persistent: the spec asks for the last Check result "kept in memory", and a stored green tick would lie
 * after the server moved or the phone went offline. Re-entering Home therefore sees this run's last result, and a
 * fresh launch honestly says "Not checked".
 */
private object HomeCheckMemory {
    val last = MutableStateFlow<HomeCheck?>(null)
}

/**
 * Home's state holder (docs/plan.md Task 13).
 *
 * Why a ViewModel: the Check button starts three network probes that must survive recomposition and be cancelled if
 * the user presses it again. Stats and the pending queue are disk reads, so they are refreshed on every resume
 * rather than collected — nothing in this app writes them in the foreground except History, and the back-stack
 * entry resumes after History closes.
 */
class HomeViewModel(private val graph: Graph) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState(check = HomeCheckMemory.last.value))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    private var checkJob: Job? = null

    /** Guards late writes from a cancelled check so two quick taps cannot interleave results. */
    private var generation = 0

    init {
        refresh()
    }

    /** Re-reads today's counters, the pending queue and whether a server and key exist. */
    fun refresh() {
        viewModelScope.launch {
            val summary = graph.stats.summary()
            val pending = graph.pending.list().size
            val stored = graph.settings.current()
            val key = withContext(Dispatchers.IO) { graph.secrets.load() }
            onBaseUrlChanged(stored.baseUrl)
            _state.value = _state.value.copy(
                takes = summary.today.takes,
                words = summary.today.words,
                timeSavedMs = summary.today.timeSavedMs,
                pending = pending,
                setupNeeded = stored.baseUrl.isBlank() || key.isNullOrBlank(),
            )
        }
    }

    /** Runs the same three probes as the Server screen's Check, against the saved server. */
    fun check() {
        val current = graph.settings.current()
        val run = ++generation
        checkJob?.cancel()
        if (current.baseUrl.isBlank()) {
            HomeCheckMemory.last.value = null
            _state.value = _state.value.copy(checking = false, check = null, setupNeeded = true)
            return
        }
        _state.value = _state.value.copy(checking = true, setupNeeded = false)
        checkJob = viewModelScope.launch {
            try {
                val key = withContext(Dispatchers.IO) { graph.secrets.load() }.orEmpty()
                if (key.isBlank()) {
                    if (run == generation) {
                        HomeCheckMemory.last.value = null
                        _state.value = _state.value.copy(checking = false, check = null, setupNeeded = true)
                    }
                    return@launch
                }
                val api = Api(graph.http) { ServerConfig(current.baseUrl, key) }
                val reach = api.warm()
                publish(run, HomeCheck(baseUrl = current.baseUrl, reach = reach))
                val keyProbe = api.checkKey()
                publish(run, HomeCheck(baseUrl = current.baseUrl, reach = reach, key = keyProbe))
                val live = api.probeLive()
                publish(run, HomeCheck(baseUrl = current.baseUrl, reach = reach, key = keyProbe, live = live))
                val started = System.currentTimeMillis()
                val models = try {
                    api.models()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    Result.failure<List<String>>(IllegalStateException("models"))
                }
                val modelsMs = System.currentTimeMillis() - started
                val modelList = models.getOrNull()
                publish(
                    run,
                    HomeCheck(
                        baseUrl = current.baseUrl,
                        reach = reach,
                        key = keyProbe,
                        live = live,
                        models = Probe(modelList != null, modelsMs, ""),
                        modelCount = modelList?.size ?: 0,
                    ),
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Api maps network failures to Probe; an unexpected throw leaves the partial result visible.
            } finally {
                if (run == generation) _state.value = _state.value.copy(checking = false)
            }
        }
    }

    /**
     * Drops a cached result that belongs to a different server (docs/spec.md §15.5).
     *
     * A green tick from the old URL would be a lie after the URL is edited, and the result is only process memory —
     * forgetting it is cheaper and more honest than storing which server it tested.
     */
    fun onBaseUrlChanged(baseUrl: String) {
        val cached = HomeCheckMemory.last.value ?: return
        if (cached.baseUrl == baseUrl) return
        HomeCheckMemory.last.value = null
        _state.value = _state.value.copy(check = null)
    }

    private fun publish(run: Int, check: HomeCheck) {
        if (run != generation) return
        HomeCheckMemory.last.value = check
        _state.value = _state.value.copy(check = check)
    }
}

internal fun homeViewModelFactory(graph: Graph) = viewModelFactory {
    initializer { HomeViewModel(graph) }
}

/** One destination square of the section grid. */
private data class HomeTile(val icon: ImageVector, val titleRes: Int, val route: String)

/**
 * The dashboard (docs/spec.md §15.5, §16; docs/plan.md Task 13).
 *
 * Calm first: the one thing worth knowing (does the server answer?), the three switches people actually flip, and
 * today's numbers. Everything else is one tap away through the tile grid, which mirrors the spec's screen list.
 * Every switch writes straight through `SettingsRepo.update`; nothing is mirrored locally.
 */
@Composable
fun HomeScreen(graph: Graph, onNavigate: (String) -> Unit) {
    val settings by graph.settings.flow.collectAsState(initial = graph.settings.current())
    val model: HomeViewModel = viewModel(factory = homeViewModelFactory(graph))
    val ui by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    var trial by rememberSaveable { mutableStateOf("") }

    OnResumeEffect { model.refresh() }
    LaunchedEffect(settings.baseUrl) { model.onBaseUrlChanged(settings.baseUrl) }

    fun update(transform: (Settings) -> Settings) {
        scope.launch { graph.settings.update(transform) }
    }

    val tiles = listOf(
        HomeTile(Icons.Rounded.Settings, R.string.server_title, Routes.SERVER),
        HomeTile(Icons.Rounded.Mic, R.string.screen_dictation, Routes.DICTATION),
        HomeTile(Icons.Rounded.AutoAwesome, R.string.screen_text_cleanup, Routes.TEXT_CLEANUP),
        HomeTile(Icons.Rounded.MenuBook, R.string.screen_dictionary, Routes.DICTIONARY),
        HomeTile(Icons.Rounded.Palette, R.string.screen_appearance, Routes.APPEARANCE),
        HomeTile(Icons.Rounded.NotificationsActive, R.string.screen_notch, Routes.NOTCH),
        HomeTile(Icons.Rounded.History, R.string.screen_history, Routes.HISTORY),
        HomeTile(Icons.Rounded.BarChart, R.string.screen_insights, Routes.INSIGHTS),
        HomeTile(Icons.Rounded.Apps, R.string.screen_app_rules, Routes.APP_RULES),
        HomeTile(Icons.Rounded.Save, R.string.screen_backup, Routes.BACKUP),
        HomeTile(Icons.Rounded.Tune, R.string.screen_diagnostics, Routes.DIAGNOSTICS),
        HomeTile(Icons.Rounded.Info, R.string.screen_about, Routes.ABOUT),
        HomeTile(Icons.Rounded.Link, R.string.share_title, Routes.SHARE_SETUP),
    )

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionCard(title = stringResource(R.string.home_server_section)) {
                ServerStatus(ui = ui, notConfigured = settings.baseUrl.isBlank() || ui.setupNeeded)
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = model::check,
                    enabled = !ui.checking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (ui.checking) R.string.home_checking else R.string.home_check_now,
                        ),
                    )
                }
                SettingRow(
                    title = stringResource(R.string.home_server_settings),
                    value = stringResource(R.string.home_server_settings_value),
                    onClick = { onNavigate(Routes.SERVER) },
                )
            }

            SectionCard(title = stringResource(R.string.home_quick_title)) {
                SwitchRow(
                    title = stringResource(R.string.home_toggle_live),
                    subtitle = stringResource(R.string.home_toggle_live_sub),
                    checked = settings.live,
                    onChange = { value -> update { it.copy(live = value) } },
                )
                SwitchRow(
                    title = stringResource(R.string.home_toggle_notch),
                    subtitle = stringResource(R.string.home_toggle_notch_sub),
                    checked = settings.notch,
                    onChange = { value -> update { it.copy(notch = value) } },
                )
                SwitchRow(
                    title = stringResource(R.string.home_toggle_return),
                    subtitle = stringResource(R.string.home_toggle_return_sub),
                    checked = settings.returnToPrevious,
                    onChange = { value -> update { it.copy(returnToPrevious = value) } },
                )
            }

            if (ui.pending > 0) {
                PendingBanner(count = ui.pending, onClick = { onNavigate(Routes.HISTORY) })
            }

            SectionCard(title = stringResource(R.string.home_today_title)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        value = ui.takes.toString(),
                        label = stringResource(R.string.home_stat_takes),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = ui.words.toString(),
                        label = stringResource(R.string.home_stat_words),
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        value = savedLabel(ui.timeSavedMs),
                        label = stringResource(R.string.home_stat_saved),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            SectionCard(title = stringResource(R.string.home_try_title)) {
                OutlinedTextField(
                    value = trial,
                    onValueChange = { trial = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.onboarding_try_label)) },
                    placeholder = { Text(stringResource(R.string.onboarding_try_hint)) },
                    minLines = 2,
                    maxLines = 4,
                )
            }

            Text(
                text = stringResource(R.string.home_sections),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tiles.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { tile ->
                            SectionTile(
                                tile = tile,
                                modifier = Modifier.weight(1f),
                                onClick = { onNavigate(tile.route) },
                            )
                        }
                        if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** The summary pill plus one line per probe; absent probes read "Waiting", a failed one reads "Failed". */
@Composable
private fun ServerStatus(ui: HomeUiState, notConfigured: Boolean) {
    val check = ui.check
    val status: Pair<PillTone, Int> = when {
        notConfigured -> PillTone.NEUTRAL to R.string.home_status_not_set_up
        ui.checking && check == null -> PillTone.NEUTRAL to R.string.home_checking
        check == null -> PillTone.NEUTRAL to R.string.home_status_not_checked
        ui.checking && check?.models == null -> PillTone.NEUTRAL to R.string.home_checking
        check?.reach?.ok != true -> PillTone.ERROR to R.string.home_status_unreachable
        check?.key?.ok != true -> PillTone.ERROR to R.string.home_status_key_rejected
        check?.live?.ok != true -> PillTone.WARN to R.string.home_status_live_off
        check?.models?.ok != true -> PillTone.WARN to R.string.home_status_models_off
        else -> PillTone.OK to R.string.home_status_connected
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusPill(tone = status.first, text = stringResource(status.second))
        if (ui.checking) {
            Spacer(modifier = Modifier.width(10.dp))
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        }
    }
    when {
        notConfigured -> Text(
            text = stringResource(R.string.home_setup_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        check == null -> Text(
            text = stringResource(R.string.home_check_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        else -> Column(modifier = Modifier.padding(top = 8.dp)) {
            ProbeLine(label = stringResource(R.string.server_check_reach), probe = check?.reach)
            ProbeLine(label = stringResource(R.string.server_check_key), probe = check?.key)
            ProbeLine(label = stringResource(R.string.server_check_live), probe = check?.live)
            ProbeLine(
                label = stringResource(R.string.server_check_models),
                probe = check?.models,
                okText = check?.let { stringResource(R.string.server_models_count, it.modelCount) },
            )
        }
    }
}

@Composable
private fun ProbeLine(label: String, probe: Probe?, okText: String? = null) {
    val icon = when {
        probe == null -> Icons.Rounded.RadioButtonUnchecked
        probe.ok -> Icons.Rounded.CheckCircle
        else -> Icons.Rounded.Cancel
    }
    val tint = when {
        probe == null -> MaterialTheme.colorScheme.onSurfaceVariant
        probe.ok -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    val value = when {
        probe == null -> stringResource(R.string.home_check_waiting)
        probe.ok -> okText ?: stringResource(R.string.server_check_ms, probe.ms)
        else -> stringResource(R.string.home_check_failed)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Failed takes are the one thing Home interrupts for: they will not retry themselves. */
@Composable
private fun PendingBanner(count: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pluralStringResource(R.plurals.home_pending_takes, count, count),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = stringResource(R.string.home_pending_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Whole minutes, with "<1 min" for a positive but sub-minute saving (docs/spec.md §15.4). */
@Composable
private fun savedLabel(ms: Long): String {
    val minutes = ms / 60_000L
    return when {
        ms <= 0L -> stringResource(R.string.home_stat_minutes, 0L)
        minutes == 0L -> stringResource(R.string.home_stat_lt_min)
        else -> stringResource(R.string.home_stat_minutes, minutes)
    }
}

@Composable
private fun SectionTile(tile: HomeTile, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(96.dp).clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                imageVector = tile.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = stringResource(tile.titleRes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
