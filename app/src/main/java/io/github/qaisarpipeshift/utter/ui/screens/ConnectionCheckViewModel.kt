package io.github.qaisarpipeshift.utter.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.qaisarpipeshift.utter.net.Api
import io.github.qaisarpipeshift.utter.net.Http
import io.github.qaisarpipeshift.utter.net.Probe
import io.github.qaisarpipeshift.utter.net.ServerConfig
import io.github.qaisarpipeshift.utter.net.normalizeBaseUrl
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of the Server screen's four-probe checklist. */
enum class CheckStatus { PENDING, RUNNING, OK, FAIL }

/** Result of one probe; [detail] comes from [Probe] and never contains the key or the URL path. */
data class CheckItem(
    val status: CheckStatus = CheckStatus.PENDING,
    val ms: Long = 0L,
    val detail: String = "",
)

/** The four rows plus the model list the cleanup dropdown needs. */
data class CheckUiState(
    val items: List<CheckItem> = List(ROW_COUNT) { CheckItem() },
    val running: Boolean = false,
    val ran: Boolean = false,
    val models: List<String> = emptyList(),
)

/** Row order is fixed: reach, key, live socket, models (docs/spec.md §15.5). */
const val ROW_COUNT = 4

/**
 * Runs the Server screen's Check button (docs/spec.md §15.5, docs/plan.md Task 12).
 *
 * Why a ViewModel: the four probes are a sequence of network calls that must survive recomposition and be cancelled
 * when the user edits the URL and presses Check again. The [Api] is built from the values currently in the form —
 * not from saved settings — so Check can test a server before it is saved, and the key never has to be written to
 * settings to be tested. Every probe runs on [Dispatchers.IO]; the state writes happen back on the main dispatcher.
 *
 * The shared [Http] comes from [io.github.qaisarpipeshift.utter.Graph] so the probes reuse the app's single
 * connection pool instead of adding one client per screen.
 */
class ConnectionCheckViewModel(private val http: Http) : ViewModel() {

    private val _state = MutableStateFlow(CheckUiState())
    val state: StateFlow<CheckUiState> = _state.asStateFlow()
    private var job: Job? = null

    /** Clears results when the URL or key changed, so a stale checkmark never describes a different server. */
    fun reset() {
        job?.cancel()
        job = null
        _state.value = CheckUiState()
    }

    fun check(rawUrl: String, key: String) {
        job?.cancel()
        val base = normalizeBaseUrl(rawUrl)
        if (base == null) {
            _state.value = CheckUiState(
                items = List(ROW_COUNT) { index ->
                    if (index == 0) CheckItem(status = CheckStatus.FAIL) else CheckItem()
                },
                ran = true,
            )
            return
        }
        // A pasted trailing newline would make every Authorization header invalid; trim before it reaches OkHttp.
        val api = Api(http) { ServerConfig(base, key.trim()) }
        _state.value = CheckUiState(running = true, ran = true)
        job = viewModelScope.launch {
            try {
                probeRow(0) { api.warm() }
                probeRow(1) { api.checkKey() }
                probeRow(2) { api.probeLive() }

                updateItem(3) { it.copy(status = CheckStatus.RUNNING) }
                val started = System.currentTimeMillis()
                val models = try {
                    withContext(Dispatchers.IO) { api.models() }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Exception) {
                    Result.failure<List<String>>(failure)
                }
                val elapsed = System.currentTimeMillis() - started
                models.fold(
                    onSuccess = { list ->
                        _state.value = _state.value.copy(
                            items = _state.value.items.replace(3, CheckItem(CheckStatus.OK, elapsed)),
                            models = list,
                        )
                    },
                    onFailure = { failure ->
                        updateItem(3) {
                            CheckItem(CheckStatus.FAIL, elapsed, safeDetail(failure))
                        }
                    },
                )
                _state.value = _state.value.copy(running = false)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                // Never leave the Check button spinning, whatever a probe throws.
                _state.value = _state.value.copy(running = false)
            }
        }
    }

    private suspend fun probeRow(index: Int, call: suspend () -> Probe) {
        updateItem(index) { it.copy(status = CheckStatus.RUNNING) }
        val item = try {
            val probe = withContext(Dispatchers.IO) { call() }
            CheckItem(
                status = if (probe.ok) CheckStatus.OK else CheckStatus.FAIL,
                ms = probe.ms,
                detail = probe.detail,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            // Class name only: OkHttp puts an offending Authorization value inside its message.
            CheckItem(CheckStatus.FAIL, 0L, failure.javaClass.simpleName)
        }
        updateItem(index) { item }
    }

    private fun updateItem(index: Int, transform: (CheckItem) -> CheckItem) {
        _state.value = _state.value.copy(items = _state.value.items.replace(index, transform(_state.value.items[index])))
    }

    /** IO messages are written by this app or OkHttp and describe HTTP status; anything else could echo the key. */
    private fun safeDetail(failure: Throwable): String =
        if (failure is IOException) failure.message.orEmpty() else failure.javaClass.simpleName
}

private fun List<CheckItem>.replace(index: Int, item: CheckItem): List<CheckItem> =
    mapIndexed { i, existing -> if (i == index) item else existing }

/** Supplies the shared [Http] to the screen's ViewModel (the default factory has no constructor arguments). */
internal fun connectionCheckViewModelFactory(http: Http) = viewModelFactory {
    initializer { ConnectionCheckViewModel(http) }
}
