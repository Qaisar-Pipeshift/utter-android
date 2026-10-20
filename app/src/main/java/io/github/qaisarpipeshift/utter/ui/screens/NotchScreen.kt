package io.github.qaisarpipeshift.utter.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.data.Settings
import io.github.qaisarpipeshift.utter.ui.OnResumeEffect
import io.github.qaisarpipeshift.utter.ui.UtterScreen
import io.github.qaisarpipeshift.utter.ui.components.PillTone
import io.github.qaisarpipeshift.utter.ui.components.SectionCard
import io.github.qaisarpipeshift.utter.ui.components.StatusPill
import io.github.qaisarpipeshift.utter.ui.components.SwitchRow
import kotlinx.coroutines.launch

/**
 * Notch status settings and OEM guidance (docs/spec.md §3b, §16; docs/plan.md Task 13).
 *
 * The toggle is only half the feature: Android can promote the recording notification to the OnePlus Live Alerts
 * capsule or the Samsung Now Bar, but both OEMs hide that behind their own notification screens. The page therefore
 * shows the live permission state (re-checked on resume, because the user leaves for system settings and comes back)
 * with a direct button, then the two guides — the phone's own maker listed first.
 */
@Composable
fun NotchScreen(graph: Graph, onBack: () -> Unit) {
    val settings by graph.settings.flow.collectAsState(initial = graph.settings.current())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(notificationsPermitted(context)) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            allowed = granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    OnResumeEffect { allowed = notificationsPermitted(context) }

    fun update(transform: (Settings) -> Settings) {
        scope.launch { graph.settings.update(transform) }
    }

    UtterScreen(title = stringResource(R.string.screen_notch), onBack = onBack) {
        SectionCard(title = stringResource(R.string.notch_section_status)) {
            SwitchRow(
                title = stringResource(R.string.notch_toggle),
                subtitle = stringResource(R.string.notch_toggle_sub),
                checked = settings.notch,
                onChange = { value -> update { it.copy(notch = value) } },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.notch_permission),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(
                    tone = if (allowed) PillTone.OK else PillTone.WARN,
                    text = stringResource(
                        if (allowed) {
                            R.string.notch_permission_allowed
                        } else {
                            R.string.notch_permission_missing
                        },
                    ),
                )
            }
            Text(
                text = stringResource(R.string.notch_permission_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (!allowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text(stringResource(R.string.notch_allow))
                }
            }
            OutlinedButton(
                onClick = { openNotificationSettings(context) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.notch_open_settings))
            }
        }

        SectionCard(title = stringResource(R.string.notch_section_guides)) {
            val onePlusFirst = !Build.MANUFACTURER.equals("samsung", ignoreCase = true)
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (onePlusFirst) {
                    Guide(R.string.notch_oneplus_title, R.string.notch_oneplus_body)
                    Guide(R.string.notch_samsung_title, R.string.notch_samsung_body)
                } else {
                    Guide(R.string.notch_samsung_title, R.string.notch_samsung_body)
                    Guide(R.string.notch_oneplus_title, R.string.notch_oneplus_body)
                }
            }
        }
    }
}

@Composable
private fun Guide(titleRes: Int, bodyRes: Int) {
    Column {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Mirrors `LiveStatus.allowed()`: the runtime permission on API 33+, then the app's own notification switch. */
private fun notificationsPermitted(context: Context): Boolean {
    val permissionOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    return permissionOk && NotificationManagerCompat.from(context).areNotificationsEnabled()
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}
