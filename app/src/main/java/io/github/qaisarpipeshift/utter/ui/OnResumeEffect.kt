package io.github.qaisarpipeshift.utter.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Runs [onResume] every time the current lifecycle owner reaches `ON_RESUME` (docs/plan.md Task 13).
 *
 * Why not `LaunchedEffect(Unit)`: inside a `NavHost` the local owner is the destination's back-stack entry, so this
 * also fires when the user comes back from a pushed screen — Home re-reads stats and pending takes, Notch re-reads
 * the notification permission. Only the callback is captured through `rememberUpdatedState`; the observer itself is
 * installed once per owner and removed on dispose.
 */
@Composable
fun OnResumeEffect(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(onResume)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) current()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
