package io.github.qaisarpipeshift.utter.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.qaisarpipeshift.utter.core.EnterAction
import io.github.qaisarpipeshift.utter.core.FailReason
import io.github.qaisarpipeshift.utter.core.PanelState
import io.github.qaisarpipeshift.utter.core.TakeAction
import io.github.qaisarpipeshift.utter.data.Palette
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.ime.Panel
import io.github.qaisarpipeshift.utter.ime.PanelHost
import io.github.qaisarpipeshift.utter.ui.theme.UtterTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests for the keyboard panel (docs/spec.md §4, §9; v0.1.4).
 *
 * Why: the panel is drawn entirely from [PanelState] and [Settings], so a fake host plus a state literal renders the
 * real pixels without a service, a network or the Keystore. Roborazzi records one PNG per state in light and dark;
 * CI uploads them as the review surface for look-and-feel changes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class PanelScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun idleLight() = capture("panel_idle_light", dark = false, PanelState.Idle(null, 0, false))

    @Test
    fun idleDark() = capture("panel_idle_dark", dark = true, PanelState.Idle(null, 0, false))

    @Test
    fun recordingLight() = capture("panel_recording_light", dark = false, recording())

    @Test
    fun recordingDark() = capture("panel_recording_dark", dark = true, recording())

    @Test
    fun finishingLight() = capture("panel_finishing_light", dark = false, PanelState.Finishing(TakeAction.CLEAN))

    @Test
    fun finishingDark() = capture("panel_finishing_dark", dark = true, PanelState.Finishing(TakeAction.CLEAN))

    @Test
    fun failedLight() =
        capture("panel_failed_light", dark = false, PanelState.Failed(FailReason.UNREACHABLE, "take-1"))

    @Test
    fun failedDark() =
        capture("panel_failed_dark", dark = true, PanelState.Failed(FailReason.UNREACHABLE, "take-1"))

    @Test
    fun secureFieldLight() = capture("panel_secure_light", dark = false, PanelState.SecureField)

    @Test
    fun secureFieldDark() = capture("panel_secure_dark", dark = true, PanelState.SecureField)

    @Test
    fun setupNeededLight() = capture("panel_setup_light", dark = false, PanelState.SetupNeeded)

    @Test
    fun setupNeededDark() = capture("panel_setup_dark", dark = true, PanelState.SetupNeeded)

    /** Committed text solid, tentative at 50 %, timer and ring visible — the panel's busiest state. */
    private fun recording(): PanelState.Recording = PanelState.Recording(
        action = TakeAction.RAW,
        locked = false,
        holding = false,
        elapsedMs = 4_200L,
        amplitude = 0.55f,
        committed = "So the plan is to ship the keyboard",
        tentative = " today and then",
    )

    private fun capture(name: String, dark: Boolean, state: PanelState) {
        compose.setContent {
            UtterTheme(dark = dark, palette = Palette.HANDY) {
                Box(Modifier.fillMaxWidth().testTag(TAG)) {
                    Panel(
                        state = state,
                        settings = Settings(),
                        selectionAvailable = false,
                        speaking = false,
                        enterAction = EnterAction.NEWLINE,
                        holdHint = false,
                        host = NoopHost,
                    )
                }
            }
        }
        compose.onNodeWithTag(TAG).captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private companion object {
        const val TAG = "panel-root"
    }
}

/** Every gesture and intent is a no-op: the screenshot only reads the panel's visual state. */
private object NoopHost : PanelHost {
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
