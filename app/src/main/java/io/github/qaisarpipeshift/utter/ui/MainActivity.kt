package io.github.qaisarpipeshift.utter.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.UtterApp
import io.github.qaisarpipeshift.utter.core.LedgerEvent
import io.github.qaisarpipeshift.utter.data.SetupLink
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.data.ThemeMode
import io.github.qaisarpipeshift.utter.ui.theme.UtterTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect

/**
 * The settings app's single activity (docs/spec.md §16, docs/plan.md Task 11).
 *
 * Why it waits for the first settings emission before drawing the graph: routing depends on `onboardingDone`, and
 * the repository starts from defaults. Showing the wizard for a frame to an already-onboarded user would be a
 * visible bug, so a plain background is drawn until the real settings arrive.
 *
 * Two intents reach this activity besides the launcher: the IME's "Grant mic" extra, which opens the wizard at the
 * microphone step, and an `utter://setup` link, which opens a confirmation dialog first. The dialog names only the
 * server host — the link contains a key, and a key must never be rendered into a dialog, a log or a screenshot.
 *
 * Predictive back only works when the activity opts in at the manifest level (it does) and nothing intercepts the
 * gesture; this class therefore registers no back callback.
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** Extra naming the onboarding step to open; the IME's "Grant mic" uses [STEP_MIC]. */
        const val EXTRA_ONBOARDING_STEP = "onboarding_step"
        const val STEP_MIC = "mic"

        /** Index of the wizard's microphone step (docs/spec.md §7). */
        private const val MIC_STEP_INDEX = 1
    }

    /** A link waiting for confirmation; a new intent while the app is already open replaces it. */
    private val linkRequests = MutableStateFlow<SetupLink.Data?>(null)

    /** Counts "Grant mic" requests so a second one still moves an open wizard to the microphone step. */
    private val micStepRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val startAtMic = intent?.getStringExtra(EXTRA_ONBOARDING_STEP) == STEP_MIC
        if (startAtMic) intent?.removeExtra(EXTRA_ONBOARDING_STEP)
        // A recreated activity must not re-open a dialog the user already answered.
        if (savedInstanceState == null) {
            linkRequests.value = setupLinkFrom(intent)
            if (startAtMic) micStepRequests.value = 1
        }
        val graph = (application as UtterApp).graph

        setContent {
            var settings by remember { mutableStateOf(Settings()) }
            var loaded by remember { mutableStateOf(false) }
            var imported by remember { mutableStateOf<SetupLink.Data?>(null) }
            val pendingLink by linkRequests.collectAsState()
            val micRequest by micStepRequests.collectAsState()

            LaunchedEffect(graph) {
                graph.settings.flow.collect { latest ->
                    settings = latest
                    loaded = true
                }
            }
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // A forced theme must also flip the system bar icons; enableEdgeToEdge only follows the system.
            LaunchedEffect(dark) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
            UtterTheme(dark = dark, palette = settings.palette) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (loaded) {
                        // Frozen on first composition: finishing the wizard updates `onboardingDone`, but the
                        // graph's start destination must not be rebuilt under the running back stack. A mic request
                        // never changes the start route; AppNav's micStepRequest navigates afterwards.
                        val startDestination = remember {
                            if (settings.onboardingDone) Routes.HOME else Routes.ONBOARDING
                        }
                        UtterAppNav(
                            graph = graph,
                            startDestination = startDestination,
                            initialStep = if (startAtMic) MIC_STEP_INDEX else 0,
                            micStepRequest = micRequest,
                            imported = imported,
                            onImportedConsumed = { imported = null },
                        )
                    } else {
                        LoadingScreen()
                    }
                }

                pendingLink?.let { data ->
                    SetupLinkDialog(
                        host = hostOf(data.url),
                        onConfirm = {
                            imported = data
                            linkRequests.value = null
                        },
                        onDismiss = { linkRequests.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        linkRequests.value = setupLinkFrom(intent)
        if (intent.getStringExtra(EXTRA_ONBOARDING_STEP) == STEP_MIC) micStepRequests.value += 1
    }

    override fun onStop() {
        super.onStop()
        // One of the resource ledger's checkpoints (v0.1.4); it only reads counters — the disk is not touched here.
        (application as? UtterApp)?.graph?.ledger?.checkpoint(LedgerEvent.ACTIVITY_STOP)
    }

    private fun setupLinkFrom(intent: Intent?): SetupLink.Data? =
        intent?.dataString?.let(SetupLink::parse)
}

/** Confirmation before a link overwrites the server settings; shows the host only, never the key. */
@Composable
private fun SetupLinkDialog(host: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setup_link_title)) },
        text = { Text(stringResource(R.string.setup_link_body, host)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.setup_link_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.setup_link_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

/** One calm frame while DataStore delivers the first settings snapshot. */
@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
            modifier = Modifier.size(28.dp),
        )
    }
}

/** The host alone for the confirmation dialog. */
private fun hostOf(url: String): String = Uri.parse(url).host ?: url
