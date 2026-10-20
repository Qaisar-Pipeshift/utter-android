package io.github.qaisarpipeshift.utter.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.ui.MainActivity
import io.github.qaisarpipeshift.utter.UtterApp
import io.github.qaisarpipeshift.utter.audio.AndroidRecorder
import io.github.qaisarpipeshift.utter.core.Cleaner
import io.github.qaisarpipeshift.utter.core.DictationController
import io.github.qaisarpipeshift.utter.core.EnterAction
import io.github.qaisarpipeshift.utter.core.FieldInfo
import io.github.qaisarpipeshift.utter.core.FieldInfos
import io.github.qaisarpipeshift.utter.core.FieldKind
import io.github.qaisarpipeshift.utter.core.Haptic
import io.github.qaisarpipeshift.utter.core.PanelState
import io.github.qaisarpipeshift.utter.core.TakeAction
import io.github.qaisarpipeshift.utter.core.TakeRequest
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.data.ThemeMode
import io.github.qaisarpipeshift.utter.net.CleanResult
import io.github.qaisarpipeshift.utter.notify.LiveStatus
import io.github.qaisarpipeshift.utter.ui.theme.UtterTheme
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Utter's keyboard (docs/spec.md §2–§4; docs/plan.md Task 9).
 *
 * Why this service is the composer: InputMethodService is not a LifecycleOwner, so it provides the three owners
 * Compose looks up on the view tree, maps the IME's show/hide callbacks onto lifecycle states, and owns the one
 * dictation controller and the scope it runs on. Nothing needs rebuilding between showings — the controller survives
 * while the service lives, and every setting is read fresh from the repository so a change applies to the next key.
 *
 * Threading: the controller and every state write run on [Dispatchers.Main.immediate] with a [SupervisorJob]; the one
 * exception handler turns an unexpected failure into a Diagnostics event instead of taking the keyboard down. Disk
 * touches (Keystore, settings write) hop to IO. The scope and the controller are cancelled in [onDestroy].
 */
class UtterIme :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner,
    PanelHost {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private lateinit var graph: Graph
    private lateinit var inputOps: InputOps
    private lateinit var controller: DictationController
    private lateinit var liveStatus: LiveStatus

    /** Never let a bug in the keyboard crash the keyboard: record the failure kind and carry on. */
    private val imeScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, failure ->
            if (::graph.isInitialized) graph.diag.event("ime_error", failure.javaClass.simpleName)
        },
    )

    private val settingsState = MutableStateFlow(Settings())
    private val uiState = MutableStateFlow<PanelState>(PanelState.Idle(result = null, pendingCount = 0, offline = false))
    private val selectionState = MutableStateFlow<String?>(null)
    private val fieldState = MutableStateFlow<FieldInfo?>(null)

    /** Shown briefly when a tap lands in hold-only mode instead of starting a take. */
    private val holdHint = MutableStateFlow(false)
    private var holdHintJob: Job? = null

    private var lastControllerState: PanelState = PanelState.Idle(null, 0, false)

    /** False until the first settings emission has been read, so nothing decides "setup needed" on defaults. */
    private var setupResolved = false
    private var keyPresent = false
    private var secureField = false

    override fun onCreate() {
        super.onCreate()
        graph = (application as UtterApp).graph
        savedState.performAttach()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        inputOps = InputOps(this, { graph.settings.current() }, graph.diag, ::onInserted)
        controller = DictationController(
            scope = imeScope,
            settings = { graph.settings.current() },
            recorder = AndroidRecorder(this, { graph.settings.current() }, graph.diag),
            transcriber = graph.api,
            cleaner = object : Cleaner {
                override suspend fun clean(message: String): CleanResult {
                    val snapshot = graph.settings.current()
                    val model = snapshot.cleanModel.ifBlank { DEFAULT_CLEAN_MODEL }
                    return graph.api.clean(model, message, snapshot.cleanTimeoutMs)
                }
            },
            inserter = inputOps,
            pending = graph.pending,
            history = graph.history,
            stats = graph.stats,
            isOnline = ::isOnline,
            haptic = ::performHaptic,
            diag = graph.diag,
        )

        liveStatus = LiveStatus(this, { graph.settings.current() }, imeScope)

        imeScope.launch {
            controller.state.collect { state ->
                lastControllerState = state
                refreshUi()
                liveStatus.onState(state)
            }
        }
        imeScope.launch { graph.pending.purge() }

        lifecycleScope.launch {
            // Awaiting the first emission is what keeps a configured keyboard from flashing "Set up Utter": the
            // DataStore read is async, and the defaults it starts from have no server URL and no key.
            val first = graph.settings.flow.first()
            settingsState.value = first
            keyPresent = withContext(Dispatchers.IO) { !graph.secrets.load().isNullOrBlank() }
            setupResolved = true
            refreshUi()
            graph.settings.flow.collect { settings ->
                settingsState.value = settings
                refreshUi()
            }
        }
    }

    override fun onCreateInputView(): View {
        // Compose walks up from the ComposeView to find its owners; the IME window's decor view is the root.
        window.window?.decorView?.let { root ->
            root.setViewTreeLifecycleOwner(this)
            root.setViewTreeViewModelStoreOwner(this)
            root.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val settings by settingsState.collectAsState()
                val state by uiState.collectAsState()
                val selection by selectionState.collectAsState()
                val field by fieldState.collectAsState()
                val holdHintNow by holdHint.collectAsState()
                val dark = when (settings.theme) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
                UtterTheme(dark = dark, palette = settings.palette) {
                    Panel(
                        state = state,
                        settings = settings,
                        selectionAvailable = selection != null,
                        enterAction = field?.actionLabel ?: EnterAction.NEWLINE,
                        holdHint = holdHintNow,
                        host = this@UtterIme,
                    )
                }
            }
        }
    }

    /** Never take over the whole screen in landscape; the panel is small. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val pkg = info?.packageName.orEmpty()
        val field = FieldInfos.from(
            inputType = info?.inputType ?: 0,
            imeOptions = info?.imeOptions ?: 0,
            pkg = pkg,
            fieldId = info?.fieldId ?: 0,
        )
        fieldState.value = field
        secureField = field.kind == FieldKind.PASSWORD
        recordSeenApp(pkg)
        refreshSelection()
        refreshUi()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        refreshKeyPresence()
        if (!restarting && info != null && graph.settings.current().autoStart && canDictate() &&
            lastControllerState is PanelState.Idle
        ) {
            startTake(TakeAction.RAW)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        controller.onPanelHidden()
        selectionState.value = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        refreshSelection()
        refreshUi()
        refreshKeyPresence()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        refreshSelection()
    }

    override fun onDestroy() {
        if (::liveStatus.isInitialized) liveStatus.cancel()
        imeScope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        super.onDestroy()
    }

    // ---- PanelHost ----

    override fun onMicTap() {
        if (!canDictate()) return
        when (lastControllerState) {
            is PanelState.Recording -> controller.stop()
            is PanelState.Finishing -> Unit
            else -> if (graph.settings.current().tapHandsFree) {
                startTake(TakeAction.RAW)
            } else {
                // Hold-only mode: a tap explains the gesture instead of starting a take.
                showHoldHint()
            }
        }
    }

    override fun onMicHoldStart() {
        if (!canDictate()) return
        if (lastControllerState is PanelState.Recording || lastControllerState is PanelState.Finishing) return
        startTake(TakeAction.RAW, holding = true)
    }

    override fun onMicHoldRelease() {
        controller.stop()
    }

    override fun onMicCancel() {
        controller.cancel()
    }

    override fun onMicLock() {
        controller.lock()
    }

    override fun onClean(presetId: String?) {
        if (!canDictate()) return
        if (lastControllerState is PanelState.Recording || lastControllerState is PanelState.Finishing) return
        val field = fieldState.value ?: return
        if (field.kind == FieldKind.PASSWORD) return
        // A selection turns ✨ into "Edit": the take rewrites the selected text instead of appending.
        val selection = if (graph.settings.current().voiceEdit) inputOps.selection() else null
        val action = if (selection != null) TakeAction.EDIT_SELECTION else TakeAction.CLEAN
        controller.start(TakeRequest(action, presetId, field, selection))
    }

    override fun onBackspace() {
        inputOps.backspace()
    }

    override fun onDeleteWord() {
        inputOps.deleteWord()
    }

    override fun onSpace() {
        inputOps.space()
    }

    override fun onEnter() {
        inputOps.enter(fieldState.value?.actionLabel ?: EnterAction.NEWLINE)
    }

    override fun onUndo() {
        controller.undo()
    }

    override fun onSwap() {
        controller.swap()
    }

    override fun onRetry(takeId: String) {
        controller.retry(takeId)
    }

    override fun onDiscard(takeId: String) {
        controller.discard(takeId)
    }

    override fun onSwitchBack() {
        switchBack()
    }

    override fun onPickKeyboard() {
        pickKeyboard()
    }

    override fun onSettings() {
        openApp()
    }

    override fun onSetup() {
        openApp()
    }

    override fun onGrantMic() {
        openApp(MainActivity.STEP_MIC)
    }

    // ---- state ----

    private fun startTake(action: TakeAction, presetId: String? = null, holding: Boolean = false) {
        val field = fieldState.value ?: return
        if (field.kind == FieldKind.PASSWORD) return
        val selection = if (action == TakeAction.EDIT_SELECTION) inputOps.selection() else null
        controller.start(TakeRequest(action, presetId, field, selection), holding)
    }

    /**
     * The single visible state: IME-level conditions (secure field, setup, mic permission) outrank the controller's
     * take state. Until the first settings emission [PanelState.Idle] is shown, never a guessed "Set up Utter".
     */
    private fun refreshUi() {
        uiState.value = when {
            secureField -> PanelState.SecureField
            !setupResolved -> PanelState.Idle(result = null, pendingCount = 0, offline = false)
            setupNeeded() -> PanelState.SetupNeeded
            !micGranted() -> PanelState.MicPermissionNeeded
            else -> lastControllerState
        }
    }

    private fun setupNeeded(): Boolean = !keyPresent || graph.settings.current().baseUrl.isBlank()

    private fun canDictate(): Boolean = setupResolved && !setupNeeded() && micGranted() && !secureField

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun refreshSelection() {
        selectionState.value = if (secureField) null else inputOps.selection()
    }

    /** One-shot network check at record start (docs/spec.md §17): a capability read, never a callback. */
    private fun isOnline(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun showHoldHint() {
        holdHint.value = true
        holdHintJob?.cancel()
        holdHintJob = imeScope.launch {
            delay(HOLD_HINT_MS)
            holdHint.value = false
        }
    }

    /** The key lives in the Keystore, not in settings, so a fresh setup is noticed when the panel is shown again. */
    private fun refreshKeyPresence() {
        if (!::graph.isInitialized) return
        imeScope.launch {
            keyPresent = withContext(Dispatchers.IO) { !graph.secrets.load().isNullOrBlank() }
            refreshUi()
        }
    }

    private fun recordSeenApp(pkg: String) {
        if (pkg.isBlank() || graph.settings.current().seenApps.containsKey(pkg)) return
        imeScope.launch {
            val label = withContext(Dispatchers.IO) {
                try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (_: Exception) {
                    pkg
                }
            }
            graph.settings.update { it.copy(seenApps = it.seenApps + (pkg to label)) }
        }
    }

    // ---- platform edges ----

    /** Haptic mapping for the controller's signals; off entirely when the user turned haptics off. */
    private fun performHaptic(kind: Haptic) {
        if (!graph.settings.current().haptics) return
        val view = window?.window?.decorView ?: return
        val constant = when (kind) {
            Haptic.PRESS -> HapticFeedbackConstants.KEYBOARD_TAP
            Haptic.START, Haptic.STOP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }

            Haptic.CANCEL, Haptic.ERROR -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.REJECT
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }

            Haptic.LOCK -> HapticFeedbackConstants.LONG_PRESS
            Haptic.WARNING -> HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(constant)
    }

    /** After a committed insert the panel hands the user back to Gboard, unless they turned that off. */
    private fun onInserted() {
        if (!graph.settings.current().returnToPrevious) return
        switchToPreviousInputMethod()
    }

    /** Back to the keyboard the user came from (Gboard); the system picker when there is none. */
    private fun switchBack() {
        if (!switchToPreviousInputMethod()) pickKeyboard()
    }

    private fun pickKeyboard() {
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    private fun openApp(step: String? = null) {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (step != null) intent.putExtra(MainActivity.EXTRA_ONBOARDING_STEP, step)
        startActivity(intent)
    }

    private companion object {
        /** The server's documented cleanup model (docs/spec.md §6) until the user picks one. */
        const val DEFAULT_CLEAN_MODEL = "cleanup"

        /** How long the hold-only hint stays in the status strip. */
        const val HOLD_HINT_MS = 1_800L
    }
}
