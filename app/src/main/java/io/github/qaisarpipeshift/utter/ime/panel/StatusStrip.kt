package io.github.qaisarpipeshift.utter.ime.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.core.FailReason
import io.github.qaisarpipeshift.utter.core.PanelState
import io.github.qaisarpipeshift.utter.core.ResultChip
import kotlinx.coroutines.flow.collect

/**
 * The strip above the keys (docs/spec.md §4; docs/plan.md Task 9).
 *
 * Why one strip for every state: it is the only place text appears, so the eye has one thing to read — live words
 * while recording, a one-line outcome afterwards, or the failure with its Retry. Long live text is vertical-scrolled
 * to its end on every update, because the newest words are the ones the user is still speaking.
 */
@Composable
fun StatusStrip(
    state: PanelState,
    holdHint: Boolean,
    selectionAvailable: Boolean,
    speaking: Boolean,
    onRetry: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onUndo: () -> Unit,
    onSwap: () -> Unit,
    onReadAloud: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        when (state) {
            is PanelState.Recording -> LiveText(state)
            is PanelState.Finishing -> StripText(stringResource(R.string.utter_transcribing))
            is PanelState.Failed -> FailedStrip(state, onRetry, onDiscard)
            is PanelState.Idle -> IdleStrip(state, holdHint, selectionAvailable, speaking, onUndo, onSwap, onReadAloud)
            else -> Unit
        }
    }
}

@Composable
private fun LiveText(state: PanelState.Recording) {
    if (state.committed.isEmpty() && state.tentative.isEmpty()) {
        StripText(stringResource(R.string.utter_listening), muted = true)
        return
    }
    val scroll = rememberScrollState()
    val live = state.committed + state.tentative
    LaunchedEffect(live) {
        // Keep the newest words visible: after each text change, follow the end of the strip as it grows.
        snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
    }
    val faded = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val text = buildAnnotatedString {
        append(state.committed)
        if (state.tentative.isNotEmpty()) {
            withStyle(SpanStyle(color = faded)) { append(state.tentative) }
        }
    }
    Text(
        text = text,
        // Two lines at the largest supported font scale; longer live text scrolls inside instead of growing the panel.
        modifier = Modifier.fillMaxWidth().heightIn(max = LIVE_TEXT_MAX_HEIGHT).verticalScroll(scroll),
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** The live strip keeps the panel's height stable while the transcript grows. */
private val LIVE_TEXT_MAX_HEIGHT = 56.dp

@Composable
private fun IdleStrip(
    state: PanelState.Idle,
    holdHint: Boolean,
    selectionAvailable: Boolean,
    speaking: Boolean,
    onUndo: () -> Unit,
    onSwap: () -> Unit,
    onReadAloud: () -> Unit,
) {
    val result = state.result
    if (result == null && state.pendingCount == 0 && !state.offline && !holdHint && !selectionAvailable) {
        StripText(stringResource(R.string.utter_panel_hint), muted = true)
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (holdHint) {
            Chip(label = stringResource(R.string.utter_hold_to_talk), muted = true)
        }
        if (result != null) {
            ResultStatusChip(result)
            if (result.canSwap && !result.noSpeech) {
                Chip(
                    label = stringResource(
                        if (result.cleaned || result.notCleaned) R.string.utter_raw else R.string.utter_clean_it,
                    ),
                    onClick = onSwap,
                )
            }
            if (result.canUndo) {
                Chip(label = stringResource(R.string.utter_undo), onClick = onUndo)
            }
        }
        if (selectionAvailable) {
            // Read aloud works on whatever is selected right now; the same chip becomes Stop while speaking.
            Chip(
                label = stringResource(if (speaking) R.string.extras_stop else R.string.extras_read_aloud),
                icon = if (speaking) Icons.Rounded.Stop else Icons.Rounded.VolumeUp,
                onClick = onReadAloud,
            )
        }
        if (state.pendingCount > 0) {
            Chip(label = stringResource(R.string.utter_pending, state.pendingCount), muted = true)
        }
        if (state.offline) {
            Chip(label = stringResource(R.string.utter_offline), muted = true)
        }
    }
}

@Composable
private fun ResultStatusChip(result: ResultChip) {
    if (result.noSpeech) {
        Chip(label = stringResource(R.string.utter_no_speech), muted = true)
        return
    }
    // canUndo is exactly "the insert reached the field"; false means the text went to the clipboard instead.
    if (!result.canUndo) {
        Chip(label = stringResource(R.string.utter_copied), muted = true)
        return
    }
    val label = stringResource(
        when {
            result.cleaned -> R.string.utter_cleaned
            result.notCleaned -> R.string.utter_not_cleaned
            else -> R.string.utter_inserted
        },
    )
    Chip(label = stringResource(R.string.utter_result_chip, label, result.latencyMs))
}

@Composable
private fun FailedStrip(state: PanelState.Failed, onRetry: (String) -> Unit, onDiscard: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = failText(state),
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.error,
        )
        if (state.takeId.isNotEmpty()) {
            Chip(label = stringResource(R.string.utter_retry), onClick = { onRetry(state.takeId) }, emphasized = true)
            Chip(label = stringResource(R.string.utter_discard), onClick = { onDiscard(state.takeId) })
        }
    }
}

@Composable
private fun failText(state: PanelState.Failed): String = when (state.reason) {
    FailReason.OFFLINE -> stringResource(R.string.utter_fail_offline)
    FailReason.UNREACHABLE -> stringResource(R.string.utter_fail_unreachable)
    FailReason.KEY_REJECTED -> stringResource(R.string.utter_fail_key)
    FailReason.STT_DOWN -> stringResource(R.string.utter_fail_stt)
    FailReason.SERVER_ERROR -> stringResource(R.string.utter_fail_server)
    FailReason.RECORDER -> {
        val code = state.code
        if (code == null) stringResource(R.string.utter_fail_recorder) else stringResource(R.string.utter_fail_recorder_code, code)
    }

    FailReason.TOO_SHORT -> stringResource(R.string.utter_fail_short)
}

@Composable
private fun StripText(text: String, muted: Boolean = false) {
    Text(
        text = text,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * A rounded pill: a result, an action, or a quiet note. At most one small icon — the panel stays calm.
 *
 * The visual pill is small, but the clickable box is a full 48 dp tall, so the target is thumb-sized without a
 * chunky key look.
 */
@Composable
private fun Chip(
    label: String,
    onClick: (() -> Unit)? = null,
    emphasized: Boolean = false,
    muted: Boolean = false,
    icon: ImageVector? = null,
) {
    val background = if (emphasized) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    }
    val content = when {
        emphasized -> MaterialTheme.colorScheme.onPrimary
        muted -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = onClick != null) { onClick?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(background)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(text = label, fontSize = 12.sp, color = content, maxLines = 1)
        }
    }
}
