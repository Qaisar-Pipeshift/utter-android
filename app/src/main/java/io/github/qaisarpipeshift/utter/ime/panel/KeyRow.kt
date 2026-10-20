package io.github.qaisarpipeshift.utter.ime.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.core.EnterAction
import kotlinx.coroutines.delay

/**
 * The panel's key rows (docs/spec.md §4; docs/plan.md Task 9).
 *
 * Why every key is a 48 dp rounded square: the panel is used with a thumb, in a hurry, over another app; a smaller
 * target is a missed tap. Icons carry content descriptions so TalkBack can read the keyboard, and the space key is
 * the only wide one, exactly like a real keyboard's bottom row.
 */
@Composable
fun KeyRow(
    action: EnterAction,
    undoEnabled: Boolean,
    minimal: Boolean,
    onSwitchBack: () -> Unit,
    onPickKeyboard: () -> Unit,
    onSettings: () -> Unit,
    onSpace: () -> Unit,
    onUndo: () -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Key(
            icon = Icons.Rounded.Language,
            description = stringResource(R.string.utter_switch_keyboard),
            onClick = onSwitchBack,
            onLongClick = onPickKeyboard,
        )
        Key(
            icon = Icons.Rounded.Settings,
            description = stringResource(R.string.utter_open_settings),
            onClick = onSettings,
        )
        if (minimal) {
            Box(Modifier.weight(1f))
        } else {
            SpaceKey(modifier = Modifier.weight(1f), onSpace = onSpace)
            Key(
                icon = Icons.Rounded.Undo,
                description = stringResource(R.string.utter_undo),
                onClick = onUndo,
                enabled = undoEnabled,
            )
            Key(
                icon = enterIcon(action),
                description = enterDescription(action),
                onClick = onEnter,
            )
        }
    }
}

/**
 * Backspace with hold-to-repeat (docs/spec.md §4).
 *
 * Why a state flag instead of a timer: the repeat loop is a coroutine that dies with the composition, so it cannot
 * outlive the panel and cannot keep the keyboard awake; a `Timer` would do both.
 */
@Composable
fun BackspaceKey(
    enabled: Boolean,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var held by remember { mutableStateOf(false) }
    val enabledNow by rememberUpdatedState(enabled)
    val backspaceNow by rememberUpdatedState(onBackspace)
    LaunchedEffect(held) {
        if (!held) return@LaunchedEffect
        delay(REPEAT_DELAY_MS)
        while (held && enabledNow) {
            backspaceNow()
            delay(REPEAT_INTERVAL_MS)
        }
    }
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        if (enabledNow) {
                            backspaceNow()
                            held = true
                            tryAwaitRelease()
                            held = false
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Backspace,
            contentDescription = stringResource(R.string.utter_backspace),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabledNow) 0.75f else 0.3f),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SpaceKey(modifier: Modifier, onSpace: () -> Unit) {
    val spaceNow by rememberUpdatedState(onSpace)
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .pointerInput(Unit) { detectTapGestures { spaceNow() } },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.utter_space),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Key(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val enabledNow by rememberUpdatedState(enabled)
    val tapNow by rememberUpdatedState(onClick)
    val longNow by rememberUpdatedState(onLongClick)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (enabledNow) tapNow() },
                    onLongPress = { if (enabledNow) longNow?.invoke() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabledNow) 0.75f else 0.3f),
            modifier = Modifier.size(22.dp),
        )
    }
}

private fun enterIcon(action: EnterAction): ImageVector = when (action) {
    EnterAction.NEWLINE -> Icons.Rounded.KeyboardReturn
    EnterAction.SEND -> Icons.Rounded.Send
    EnterAction.SEARCH -> Icons.Rounded.Search
    EnterAction.GO, EnterAction.NEXT -> Icons.Rounded.ArrowForward
    EnterAction.DONE -> Icons.Rounded.Done
}

@Composable
private fun enterDescription(action: EnterAction): String = stringResource(
    when (action) {
        EnterAction.NEWLINE -> R.string.utter_enter_newline
        EnterAction.SEND -> R.string.utter_enter_send
        EnterAction.SEARCH -> R.string.utter_enter_search
        EnterAction.GO -> R.string.utter_enter_go
        EnterAction.NEXT -> R.string.utter_enter_next
        EnterAction.DONE -> R.string.utter_enter_done
    },
)

private const val REPEAT_DELAY_MS = 420L
private const val REPEAT_INTERVAL_MS = 70L
