package io.github.qaisarpipeshift.utter.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.core.EnterAction
import io.github.qaisarpipeshift.utter.core.PanelState
import io.github.qaisarpipeshift.utter.data.PanelHeight
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.ime.panel.BackspaceKey
import io.github.qaisarpipeshift.utter.ime.panel.KeyRow
import io.github.qaisarpipeshift.utter.ime.panel.MicButton
import io.github.qaisarpipeshift.utter.ime.panel.PresetSheet
import io.github.qaisarpipeshift.utter.ime.panel.StatusStrip
import io.github.qaisarpipeshift.utter.ui.theme.utterWarningColor

/** Every action the panel can ask the IME for; the IME owns the controller, the presence flags and the intents. */
interface PanelHost {
    fun onMicTap()
    fun onMicHoldStart()
    fun onMicHoldRelease()
    fun onMicCancel()
    fun onMicLock()
    fun onClean(presetId: String?)
    fun onBackspace()
    fun onDeleteWord()
    fun onSpace()
    fun onEnter()
    fun onUndo()
    fun onSwap()
    fun onReadAloud()
    fun onRetry(takeId: String)
    fun onDiscard(takeId: String)
    fun onSwitchBack()
    fun onPickKeyboard()
    fun onSettings()
    fun onSetup()
    fun onGrantMic()
}

/** Ignores every intent; the Appearance preview draws the real panel without a controller behind it. */
object NoopPanelHost : PanelHost {
    override fun onMicTap() = Unit
    override fun onMicHoldStart() = Unit
    override fun onMicHoldRelease() = Unit
    override fun onMicCancel() = Unit
    override fun onMicLock() = Unit
    override fun onClean(presetId: String?) = Unit
    override fun onBackspace() = Unit
    override fun onDeleteWord() = Unit
    override fun onSpace() = Unit
    override fun onEnter() = Unit
    override fun onUndo() = Unit
    override fun onSwap() = Unit
    override fun onReadAloud() = Unit
    override fun onRetry(takeId: String) = Unit
    override fun onDiscard(takeId: String) = Unit
    override fun onSwitchBack() = Unit
    override fun onPickKeyboard() = Unit
    override fun onSettings() = Unit
    override fun onSetup() = Unit
    override fun onGrantMic() = Unit
}

/** Panel heights from docs/plan.md Task 9, before the navigation-bar inset. */
private fun panelHeight(mode: PanelHeight): Dp = when (mode) {
    PanelHeight.COMPACT -> 196.dp
    PanelHeight.NORMAL -> 228.dp
    PanelHeight.TALL -> 268.dp
}

/** How many amplitudes the ring remembers (must match Waveform's bar count). */
private const val WAVE_BARS = 32

/**
 * The keyboard panel in the IME (docs/spec.md §4, §9, §16; docs/plan.md Task 9).
 *
 * Why one stateless composable: it renders the [PanelState] the controller publishes and forwards gestures to
 * [PanelHost] — no business state lives here, so a recomposition can never disagree with the controller. The panel
 * is flat on the Handy tokens: one accent, 16 dp radii, muted secondary keys, and nothing moves while idle; the
 * waveform ring is composed only while recording.
 *
 * This is the IME's entry point: always interactive and always responsible for the navigation-bar inset. The
 * settings preview draws the same pixels through [KeyboardPanel] with both turned off.
 */
@Composable
fun Panel(
    state: PanelState,
    settings: Settings,
    selectionAvailable: Boolean,
    speaking: Boolean,
    enterAction: EnterAction,
    holdHint: Boolean,
    host: PanelHost,
) {
    KeyboardPanel(
        state = state,
        settings = settings,
        selectionAvailable = selectionAvailable,
        speaking = speaking,
        enterAction = enterAction,
        holdHint = holdHint,
        interactive = true,
        applyInsets = true,
        host = host,
    )
}

/**
 * The visual panel shared by the IME and the Appearance preview (docs/spec.md §16).
 *
 * Why the two flags: the preview lives inside a scrolling settings page, so it must not eat drag gestures meant for
 * the page ([interactive] = false removes every pointer handler and every click target) and must not reserve the
 * navigation-bar inset ([applyInsets] = false) — the page's own insets already cover it. Everything else — paddings,
 * heights, the backspace/sparkle row, the waveform ring, the theme tokens — is exactly what the keyboard shows.
 */
@Composable
fun KeyboardPanel(
    state: PanelState,
    settings: Settings,
    selectionAvailable: Boolean,
    speaking: Boolean,
    enterAction: EnterAction,
    holdHint: Boolean,
    interactive: Boolean,
    applyInsets: Boolean,
    host: PanelHost,
    modifier: Modifier = Modifier,
) {
    var showPresets by remember { mutableStateOf(false) }
    val amplitudes = remember { mutableStateListOf<Float>() }
    val recording = state as? PanelState.Recording
    LaunchedEffect(recording?.amplitude) {
        if (recording == null) {
            amplitudes.clear()
        } else {
            amplitudes.add(recording.amplitude)
            while (amplitudes.size > WAVE_BARS) amplitudes.removeAt(0)
        }
    }
    // The sheet belongs to the idle panel; a take starting behind it (auto-start) must not leave it armed.
    LaunchedEffect(state is PanelState.Idle) {
        if (state !is PanelState.Idle) showPresets = false
    }

    val dictation = state is PanelState.Idle ||
        state is PanelState.Recording ||
        state is PanelState.Finishing ||
        state is PanelState.Failed

    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (applyInsets) Modifier.navigationBarsPadding() else Modifier),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // A minimum, not a fixed height: the panel may grow at large font scales but never clips.
                    .heightIn(min = panelHeight(settings.panelHeight))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                when (state) {
                    PanelState.SetupNeeded -> SimpleBody(
                        icon = Icons.Rounded.Settings,
                        iconDescription = stringResource(R.string.utter_setup_icon),
                        title = stringResource(R.string.utter_setup_title),
                        body = stringResource(R.string.utter_setup_body),
                        action = stringResource(R.string.utter_setup_button),
                        onAction = host::onSetup,
                        interactive = interactive,
                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                    )

                    PanelState.MicPermissionNeeded -> SimpleBody(
                        icon = Icons.Rounded.Mic,
                        iconDescription = stringResource(R.string.utter_start_dictation),
                        title = stringResource(R.string.utter_mic_needed_title),
                        body = stringResource(R.string.utter_mic_needed_body),
                        action = stringResource(R.string.utter_grant_mic),
                        onAction = host::onGrantMic,
                        interactive = interactive,
                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                    )

                    PanelState.SecureField -> SimpleBody(
                        icon = Icons.Rounded.Lock,
                        iconDescription = stringResource(R.string.utter_secure_icon),
                        title = stringResource(R.string.utter_secure_title),
                        body = stringResource(R.string.utter_secure_body),
                        action = null,
                        onAction = null,
                        interactive = interactive,
                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                    )

                    PanelState.Blocked -> SimpleBody(
                        icon = Icons.Rounded.MicOff,
                        iconDescription = stringResource(R.string.utter_blocked_icon),
                        title = stringResource(R.string.utter_blocked_title),
                        body = stringResource(R.string.utter_blocked_body),
                        action = null,
                        onAction = null,
                        interactive = interactive,
                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                    )

                    else -> {
                        StatusStrip(
                            state = state,
                            holdHint = holdHint,
                            selectionAvailable = selectionAvailable,
                            speaking = speaking,
                            interactive = interactive,
                            onRetry = host::onRetry,
                            onDiscard = host::onDiscard,
                            onUndo = host::onUndo,
                            onSwap = host::onSwap,
                            onReadAloud = host::onReadAloud,
                            modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                        )
                        ControlRow(
                            state = state,
                            settings = settings,
                            amplitudes = amplitudes,
                            selectionAvailable = selectionAvailable,
                            interactive = interactive,
                            host = host,
                            onShowPresets = { showPresets = true },
                        )
                    }
                }
                KeyRow(
                    action = enterAction,
                    undoEnabled = (state as? PanelState.Idle)?.result?.canUndo == true,
                    minimal = !dictation,
                    interactive = interactive,
                    onSwitchBack = host::onSwitchBack,
                    onPickKeyboard = host::onPickKeyboard,
                    onSettings = host::onSettings,
                    onSpace = host::onSpace,
                    onUndo = host::onUndo,
                    onEnter = host::onEnter,
                )
            }
            if (interactive && showPresets && state is PanelState.Idle) {
                PresetSheet(
                    presets = settings.presets,
                    selectedId = settings.defaultPresetId,
                    onPick = { preset ->
                        showPresets = false
                        host.onClean(preset.id)
                    },
                    onDismiss = { showPresets = false },
                )
            }
        }
    }
}

@Composable
private fun ControlRow(
    state: PanelState,
    settings: Settings,
    amplitudes: List<Float>,
    selectionAvailable: Boolean,
    interactive: Boolean,
    host: PanelHost,
    onShowPresets: () -> Unit,
) {
    val editable = state is PanelState.Idle || state is PanelState.Failed
    val editMode = selectionAvailable && settings.voiceEdit
    val recording = state as? PanelState.Recording
    Column(modifier = Modifier.fillMaxWidth()) {
        // Buttons band: the mic's 72 dp circle owns the centre of its 88 dp ring canvas and the sparkle sits in its
        // own 72 dp slot, so both circles share one vertical centre.
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = MIC_BOX),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BackspaceKey(
                    enabled = state !is PanelState.Finishing,
                    interactive = interactive,
                    onBackspace = host::onBackspace,
                    onDeleteWord = host::onDeleteWord,
                )
            }
            Box(Modifier.width(MIC_BOX), contentAlignment = Alignment.Center) {
                MicButton(
                    state = state,
                    haptics = settings.haptics,
                    holdThresholdMs = settings.holdThresholdMs,
                    slideGestures = settings.slideGestures,
                    interactive = interactive,
                    amplitudes = amplitudes,
                    onTap = host::onMicTap,
                    onHoldStart = host::onMicHoldStart,
                    onHoldRelease = host::onMicHoldRelease,
                    onCancelGesture = host::onMicCancel,
                    onLockGesture = host::onMicLock,
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.size(BUTTON_SLOT), contentAlignment = Alignment.Center) {
                    CleanButton(
                        enabled = editable,
                        editMode = editMode,
                        presetsAllowed = state is PanelState.Idle,
                        interactive = interactive,
                        onClean = { host.onClean(null) },
                        onShowPresets = onShowPresets,
                    )
                }
            }
        }
        // Captions: one row, one baseline, each centred under the same slot as its button.
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(MIC_BOX), contentAlignment = Alignment.Center) {
                if (recording != null) {
                    ElapsedLabel(recording.elapsedMs)
                } else {
                    ButtonLabel(stringResource(R.string.utter_label_dictate))
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.size(BUTTON_SLOT), contentAlignment = Alignment.Center) {
                    ButtonLabel(
                        stringResource(if (editMode) R.string.utter_edit_label else R.string.utter_label_clean),
                    )
                }
            }
        }
    }
}

/** ✨ — one tap cleans, or edits the selection; long-press picks the preset (docs/spec.md §15.2). */
@Composable
private fun CleanButton(
    enabled: Boolean,
    editMode: Boolean,
    presetsAllowed: Boolean,
    interactive: Boolean,
    onClean: () -> Unit,
    onShowPresets: () -> Unit,
) {
    val enabledNow by rememberUpdatedState(enabled)
    val presetsAllowedNow by rememberUpdatedState(presetsAllowed)
    val cleanNow by rememberUpdatedState(onClean)
    val presetsNow by rememberUpdatedState(onShowPresets)
    val gestures = if (interactive) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { if (enabledNow) cleanNow() },
                onLongPress = { if (enabledNow && presetsAllowedNow) presetsNow() },
            )
        }
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(
                MaterialTheme.colorScheme.secondary.copy(alpha = if (enabledNow) 0.30f else 0.12f),
            )
            .then(gestures),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = stringResource(
                if (editMode) R.string.utter_edit_selection else R.string.utter_clean_dictation,
            ),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabledNow) 1f else 0.4f),
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * The small caption under the centre keys (docs/spec.md §4).
 *
 * Why `clearAndSetSemantics`: the key's icon already carries the accessible name ("Start dictation", "Clean
 * dictation", "Edit selection"), so the visible caption is for sighted users and TalkBack must not read it twice.
 * The label never wraps: at the largest font scale it still sits on one line under its key.
 */
@Composable
private fun ButtonLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.clearAndSetSemantics { },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
    )
}

/** While recording the mic caption becomes the timer — same slot, same style, same baseline as "Clean". */
@Composable
private fun ElapsedLabel(elapsedMs: Long) {
    Text(
        text = formatElapsed(elapsedMs),
        modifier = Modifier.clearAndSetSemantics { },
        style = MaterialTheme.typography.labelSmall,
        color = if (elapsedMs >= WARNING_MS) utterWarningColor() else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
    )
}

/** `m:ss`, the same shape as the notch status while recording (docs/spec.md §3b). */
private fun formatElapsed(elapsedMs: Long): String {
    val seconds = (elapsedMs / 1000).coerceAtLeast(0)
    val minutes = seconds / 60
    val rest = seconds % 60
    return "$minutes:${rest.toString().padStart(2, '0')}"
}

/** The two centre buttons sit in 72 dp slots so their circles share one vertical centre. */
private val BUTTON_SLOT = 72.dp

/** The mic's layout box: the 72 dp circle plus 8 dp of ring canvas on each side. */
private val MIC_BOX = 88.dp

/** Turn warning colour threshold, matching the controller's warning haptic (docs/spec.md §4). */
private const val WARNING_MS = 280_000L

/** Setup, mic-permission and secure-field states: one icon, one explanation, at most one action. */
@Composable
private fun SimpleBody(
    icon: ImageVector,
    iconDescription: String,
    title: String,
    body: String,
    action: String?,
    onAction: (() -> Unit)?,
    interactive: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = iconDescription,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            text = title,
            modifier = Modifier.padding(top = 6.dp),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            modifier = Modifier.padding(start = 12.dp, top = 2.dp, end = 12.dp),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null && onAction != null) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(enabled = interactive, onClick = onAction)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = action, fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
