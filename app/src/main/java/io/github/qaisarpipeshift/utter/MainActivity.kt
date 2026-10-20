package io.github.qaisarpipeshift.utter

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.qaisarpipeshift.utter.ui.theme.UtterTheme

/** Scaffold screen: replaced by onboarding + settings (docs/plan.md, Tasks 11–14). */
class MainActivity : ComponentActivity() {

    companion object {
        /** Extra naming the onboarding step to open; the IME's "Grant mic" uses [STEP_MIC]. */
        const val EXTRA_ONBOARDING_STEP = "onboarding_step"
        const val STEP_MIC = "mic"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            UtterTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.safeDrawingPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Utter", style = MaterialTheme.typography.headlineMedium)
                        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})")
                        Button(onClick = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }) {
                            Text("Enable Utter keyboard")
                        }
                    }
                }
            }
        }
    }
}
