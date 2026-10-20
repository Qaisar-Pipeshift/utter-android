package io.github.qaisarpipeshift.utter.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.data.Palette
import io.github.qaisarpipeshift.utter.ui.onboarding.OnboardingScreen
import io.github.qaisarpipeshift.utter.ui.screens.AppearanceScreen
import io.github.qaisarpipeshift.utter.ui.screens.HomeScreen
import io.github.qaisarpipeshift.utter.ui.theme.UtterTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests for the settings screens (docs/spec.md §16; v0.1.4).
 *
 * Why a real [Graph]: the previews are only honest when drawn by the shipped screen with its real defaults. The
 * Robolectric application has an empty `filesDir`, no stored key and no server URL, so nothing touches the network
 * or the Keystore — Home shows its "not set up" state and Onboarding its first (server) step.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-xxhdpi")
class ScreenScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun appearanceLight() = capture("appearance_light", dark = false) { graph ->
        AppearanceScreen(graph = graph, onBack = {})
    }

    @Test
    fun appearanceDark() = capture("appearance_dark", dark = true) { graph ->
        AppearanceScreen(graph = graph, onBack = {})
    }

    @Test
    fun homeLight() = capture("home_light", dark = false) { graph ->
        HomeScreen(graph = graph, onNavigate = {})
    }

    @Test
    fun homeDark() = capture("home_dark", dark = true) { graph ->
        HomeScreen(graph = graph, onNavigate = {})
    }

    @Test
    fun onboardingStep1Light() = capture("onboarding_step1_light", dark = false) { graph ->
        OnboardingScreen(graph = graph, onDone = {})
    }

    @Test
    fun onboardingStep1Dark() = capture("onboarding_step1_dark", dark = true) { graph ->
        OnboardingScreen(graph = graph, onDone = {})
    }

    private fun capture(name: String, dark: Boolean, screen: @Composable (Graph) -> Unit) {
        val graph = Graph(RuntimeEnvironment.getApplication())
        compose.setContent {
            UtterTheme(dark = dark, palette = Palette.HANDY) {
                screen(graph)
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
}
