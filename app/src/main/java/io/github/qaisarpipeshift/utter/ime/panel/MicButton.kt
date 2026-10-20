package io.github.qaisarpipeshift.utter.ime.panel

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.core.PanelState
import io.github.qaisarpipeshift.utter.ui.theme.LocalUtterDark
import io.github.qaisarpipeshift.utter.ui.theme.UtterColors
import kotlinx.coroutines.withTimeoutOrNull

/** Tiny overlay telling the user what a slide will do before they release (docs/plan.md Task 9). */
private enum class MicHint { NONE, CANCEL, LOCK }

/** Turn warning colour threshold, matching the controller's warning haptic (docs/spec.md §4). */
private const val WARNING_MS = 280_000L

/**
 * The mic key with its gestures (docs/spec.md §4, §15.1; docs/plan.md Task 9).
 *
 * Why the gesture logic lives here: the thresholds are physical finger travel, and the take must start/stop exactly
 * once per gesture — a tap toggles hands-free, holding past the threshold is push-to-talk until release, sliding
 * left throws the take away and sliding up locks it hands-free. The controller is only told about the decisions,
 * never about the finger. `rememberUpdatedState` keeps the gesture coroutine alive across the state changes it
 * causes, so starting a take cannot cancel the press that started it.
 */
@Composable
fun MicButton(
    state: PanelState,
    haptics: Boolean,
    holdThresholdMs: Int,
    slideGestures: Boolean,
    amplitudes: List<Float>,
    elapsedMs: Long?,
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldRelease: () -> Unit,
    onCancelGesture: () -> Unit,
    onLockGesture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val view = LocalView.current
    val density = LocalDensity.current
    val cancelThreshold = with(density) { (-96).dp.toPx() }
    val cancelHintAt = with(density) { (-32).dp.toPx() }
    val lockThreshold = with(density) { (-72).dp.toPx() }
    val threshold = holdThresholdMs.coerceIn(150, 800).toLong()

    val stateNow by rememberUpdatedState(state)
    val hapticsNow by rememberUpdatedState(haptics)
    val slideNow by rememberUpdatedState(slideGestures)
    val enabledNow by rememberUpdatedState(state !is PanelState.Finishing)
    val tapNow by rememberUpdatedState(onTap)
    val holdStartNow by rememberUpdatedState(onHoldStart)
    val holdReleaseNow by rememberUpdatedState(onHoldRelease)
    val cancelNow by rememberUpdatedState(onCancelGesture)
    val lockNow by rememberUpdatedState(onLockGesture)

    var hint by remember { mutableStateOf(MicHint.NONE) }
    val recording = state as? PanelState.Recording

    Box(modifier.size(88.dp), contentAlignment = Alignment.Center) {
        if (recording != null) {
            Waveform(amplitudes = amplitudes, modifier = Modifier.fillMaxSize())
        }
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (enabledNow) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        if (!enabledNow) return@awaitEachGesture
                        if (hapticsNow) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        hint = MicHint.NONE
                        val startedRecording = stateNow is PanelState.Recording
                        val deadline = SystemClock.uptimeMillis() + threshold
                        var holdingStarted = false
                        var gestureCancelled = false
                        var gestureLocked = false
                        var released = false
                        try {
                            var pressed = true
                            while (pressed) {
                                val remaining = deadline - SystemClock.uptimeMillis()
                                val event = if (!startedRecording && !holdingStarted && remaining > 0) {
                                    withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                } else {
                                    awaitPointerEvent()
                                }
                                if (event == null) {
                                    // The finger is still down after the hold threshold: push-to-talk begins.
                                    holdingStarted = true
                                    holdStartNow()
                                    continue
                                }
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) {
                                    change?.consume()
                                    pressed = false
                                    continue
                                }
                                if (!holdingStarted || gestureCancelled || gestureLocked) continue
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                when {
                                    slideNow && dy < lockThreshold -> {
                                        gestureLocked = true
                                        hint = MicHint.LOCK
                                        lockNow()
                                    }

                                    slideNow && dx < cancelThreshold -> {
                                        gestureCancelled = true
                                        hint = MicHint.NONE
                                        cancelNow()
                                    }

                                    slideNow && dx < cancelHintAt -> hint = MicHint.CANCEL
                                    else -> hint = MicHint.NONE
                                }
                            }
                            when {
                                gestureCancelled || gestureLocked -> Unit
                                holdingStarted -> holdReleaseNow()
                                else -> tapNow()
                            }
                            released = true
                        } finally {
                            hint = MicHint.NONE
                            if (holdingStarted && !released && !gestureCancelled && !gestureLocked) {
                                // The node went away mid-hold (panel hidden, IME restart): never leave the mic open.
                                holdReleaseNow()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = stringResource(
                    if (recording != null) R.string.utter_stop_dictation else R.string.utter_start_dictation,
                ),
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
        if (hint == MicHint.CANCEL) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.utter_cancel_dictation),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.offset(x = (-34).dp).size(18.dp),
            )
        }
        if (hint == MicHint.LOCK) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = stringResource(R.string.utter_lock_hands_free),
                tint = accent,
                modifier = Modifier.offset(y = (-34).dp).size(18.dp),
            )
        }
        if (elapsedMs != null) {
            val warning = if (LocalUtterDark.current) UtterColors.WarningDark else UtterColors.WarningLight
            Text(
                text = formatElapsed(elapsedMs),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 5.dp),
                fontSize = 11.sp,
                color = if (elapsedMs >= WARNING_MS) warning else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** `m:ss`, the same shape as the notch status while recording (docs/spec.md §3b). */
private fun formatElapsed(elapsedMs: Long): String {
    val seconds = (elapsedMs / 1000).coerceAtLeast(0)
    val minutes = seconds / 60
    val rest = seconds % 60
    return "$minutes:${rest.toString().padStart(2, '0')}"
}
