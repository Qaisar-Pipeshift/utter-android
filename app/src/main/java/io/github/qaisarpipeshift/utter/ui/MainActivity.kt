package io.github.qaisarpipeshift.utter.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.qaisarpipeshift.utter.UtterApp
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.data.ThemeMode
import io.github.qaisarpipeshift.utter.ui.theme.UtterTheme
import kotlinx.coroutines.flow.collect

/**
 * The settings app's single activity (docs/spec.md §16, docs/plan.md Task 11).
 *
 * Why it waits for the first settings emission before drawing the graph: routing depends on `onboardingDone`, and
 * the repository starts from defaults. Showing the wizard for a frame to an already-onboarded user would be a
 * visible bug, so a plain background is drawn until the real settings arrive.
 *
 * Predictive back only works when the activity opts in at the manifest level (it does) and nothing intercepts the
 * gesture; this class therefore registers no back callback.
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** Extra naming the onboarding step to open; the IME's "Grant mic" uses [STEP_MIC]. */
        const val EXTRA_ONBOARDING_STEP = "onboarding_step"
        const val STEP_MIC = "mic"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as UtterApp).graph

        setContent {
            var settings by remember { mutableStateOf(Settings()) }
            var loaded by remember { mutableStateOf(false) }
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
            UtterTheme(dark = dark, palette = settings.palette) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (loaded) {
                        UtterAppNav(graph = graph, startDestination = Routes.HOME)
                    } else {
                        LoadingScreen()
                    }
                }
            }
        }
    }
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
