package io.github.qaisarpipeshift.utter.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.data.SetupLink
import io.github.qaisarpipeshift.utter.ime.UtterIme
import io.github.qaisarpipeshift.utter.net.normalizeBaseUrl
import io.github.qaisarpipeshift.utter.ui.components.PillTone
import io.github.qaisarpipeshift.utter.ui.components.SectionCard
import io.github.qaisarpipeshift.utter.ui.components.StatusPill
import io.github.qaisarpipeshift.utter.ui.screens.ConnectionCheckViewModel
import io.github.qaisarpipeshift.utter.ui.screens.ConnectionChecklist
import io.github.qaisarpipeshift.utter.ui.screens.ServerForm
import io.github.qaisarpipeshift.utter.ui.screens.connectionCheckViewModelFactory
import io.github.qaisarpipeshift.utter.ui.screens.persistServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val STEP_SERVER = 0
private const val STEP_MIC = 1
private const val STEP_NOTIFICATIONS = 2
private const val STEP_ENABLE = 3
private const val STEP_TRY = 4
private const val STEP_TIPS = 5
private const val STEP_COUNT = 6

/**
 * The six-step first-run wizard (docs/spec.md §7, §16; docs/plan.md Task 12).
 *
 * Why one destination with an internal step counter instead of six routes: the wizard is one flow with one set of
 * form values, and predictive back should take the user out of setup — not back through six half-filled screens.
 * The visible Back button moves a step; the system back gesture is never intercepted.
 *
 * The API key is deliberately held in plain `remember`, not `rememberSaveable`: it must not be written into the
 * saved-instance-state bundle. Rotation therefore reloads it from SecretStore, which is where it belongs.
 */
@Composable
fun OnboardingScreen(
    graph: Graph,
    onDone: () -> Unit,
    initialStep: Int = STEP_SERVER,
    stepRequest: Int = 0,
    imported: SetupLink.Data? = null,
    onImportedConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { graph.settings.current() }

    var step by rememberSaveable { mutableStateOf(initialStep.coerceIn(STEP_SERVER, STEP_TIPS)) }
    var url by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var key by remember { mutableStateOf(imported?.key.orEmpty()) }
    var trial by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    var micGranted by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECORD_AUDIO)) }
    var micAsked by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        micAsked = true
    }

    val notificationsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var notificationsGranted by remember {
        mutableStateOf(!notificationsSupported || hasPermission(context, Manifest.permission.POST_NOTIFICATIONS))
    }
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsGranted = granted
        }

    var utterEnabled by remember { mutableStateOf(isUtterEnabled(context)) }

    val checkViewModel: ConnectionCheckViewModel =
        viewModel(factory = connectionCheckViewModelFactory(graph.http))
    val check by checkViewModel.state.collectAsState()

    // Returning from the keyboard-settings or App-info screens must update the step without a recomposition trick.
    OnResumeEffect {
        utterEnabled = isUtterEnabled(context)
        micGranted = hasPermission(context, Manifest.permission.RECORD_AUDIO)
        if (notificationsSupported) {
            notificationsGranted = hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(graph) {
        if (key.isBlank()) {
            val stored = withContext(Dispatchers.IO) { graph.secrets.load() }.orEmpty()
            if (key.isBlank()) key = stored
        }
    }

    // A confirmed utter://setup link fills the first step and never contains a visible key anywhere else.
    LaunchedEffect(imported) {
        val data = imported ?: return@LaunchedEffect
        url = data.url
        key = data.key
        step = STEP_SERVER
        saveFailed = false
        onImportedConsumed()
    }

    // "Grant mic" from the keyboard while this activity was already open.
    LaunchedEffect(stepRequest) {
        if (stepRequest > 0) step = STEP_MIC
    }

    val urlValid = normalizeBaseUrl(url) != null
    val canContinue = when (step) {
        STEP_SERVER -> urlValid && key.isNotBlank()
        STEP_MIC -> micGranted
        STEP_ENABLE -> utterEnabled
        else -> true
    }
    val nextLabel = when {
        step == STEP_TIPS -> R.string.utter_finish
        step == STEP_NOTIFICATIONS && notificationsSupported && !notificationsGranted -> R.string.utter_skip
        else -> R.string.utter_next
    }
    val onAdvance: () -> Unit = {
        when (step) {
            STEP_SERVER -> scope.launch {
                saving = true
                val ok = runCatching { persistServer(graph, url, key) }.getOrDefault(false)
                saving = false
                if (ok) {
                    saveFailed = false
                    step = STEP_MIC
                } else {
                    saveFailed = true
                }
            }

            STEP_TIPS -> scope.launch {
                saving = true
                runCatching { graph.settings.update { it.copy(onboardingDone = true) } }
                saving = false
                onDone()
            }

            else -> step += 1
        }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.onboarding_step, step + 1, STEP_COUNT),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            ProgressDots(step = step)
            Spacer(modifier = Modifier.height(28.dp))

            when (step) {
                STEP_SERVER -> {
                    StepHeading(
                        icon = Icons.Rounded.Keyboard,
                        title = stringResource(R.string.onboarding_server_title),
                        body = stringResource(R.string.onboarding_server_body),
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    SectionCard(title = stringResource(R.string.server_section_connection)) {
                        ServerForm(
                            url = url,
                            onUrlChange = {
                                url = it
                                saveFailed = false
                                checkViewModel.reset()
                            },
                            key = key,
                            onKeyChange = {
                                key = it
                                saveFailed = false
                                checkViewModel.reset()
                            },
                            urlError = url.isNotBlank() && !urlValid,
                        )
                        if (key.isBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.onboarding_key_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { checkViewModel.check(url, key) },
                            enabled = urlValid && !check.running,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (check.running) R.string.server_checking else R.string.server_check,
                                ),
                            )
                        }
                        if (check.ran) {
                            Spacer(modifier = Modifier.height(16.dp))
                            ConnectionChecklist(state = check)
                        }
                    }
                    if (saveFailed) {
                        Spacer(modifier = Modifier.height(12.dp))
                        StatusPill(
                            text = stringResource(R.string.server_save_failed),
                            tone = PillTone.ERROR,
                        )
                    }
                }

                STEP_MIC -> {
                    StepHeading(
                        icon = Icons.Rounded.Mic,
                        title = stringResource(R.string.onboarding_mic_title),
                        body = stringResource(R.string.onboarding_mic_body),
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    if (micGranted) {
                        StatusPill(
                            text = stringResource(R.string.onboarding_permission_allowed),
                            tone = PillTone.OK,
                        )
                    } else {
                        Button(
                            onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.onboarding_allow_mic))
                        }
                        if (micAsked) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.onboarding_mic_denied_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { openAppInfo(context) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.onboarding_open_app_info))
                            }
                        }
                    }
                }

                STEP_NOTIFICATIONS -> {
                    StepHeading(
                        icon = Icons.Rounded.Notifications,
                        title = stringResource(R.string.onboarding_notifications_title),
                        body = stringResource(R.string.onboarding_notifications_body),
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    when {
                        !notificationsSupported -> StatusPill(
                            text = stringResource(R.string.onboarding_notifications_not_needed),
                            tone = PillTone.NEUTRAL,
                        )

                        notificationsGranted -> StatusPill(
                            text = stringResource(R.string.onboarding_permission_allowed),
                            tone = PillTone.OK,
                        )

                        else -> {
                            Button(
                                onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.onboarding_allow_notifications))
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.onboarding_notifications_skip),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                STEP_ENABLE -> {
                    StepHeading(
                        icon = Icons.Rounded.Keyboard,
                        title = stringResource(R.string.onboarding_enable_title),
                        body = stringResource(R.string.onboarding_enable_body),
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    if (utterEnabled) {
                        StatusPill(
                            text = stringResource(R.string.onboarding_enabled),
                            tone = PillTone.OK,
                        )
                    } else {
                        Button(
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.onboarding_open_keyboard_settings))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.onboarding_enable_waiting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                STEP_TRY -> {
                    StepHeading(
                        icon = Icons.Rounded.Mic,
                        title = stringResource(R.string.onboarding_try_title),
                        body = stringResource(R.string.onboarding_try_body),
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    OutlinedTextField(
                        value = trial,
                        onValueChange = { trial = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.onboarding_try_label)) },
                        placeholder = { Text(stringResource(R.string.onboarding_try_hint)) },
                        minLines = 3,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.utter_switch_keyboard))
                    }
                }

                STEP_TIPS -> {
                    StepHeading(
                        icon = Icons.Rounded.AutoAwesome,
                        title = stringResource(R.string.onboarding_tips_title),
                        body = stringResource(R.string.onboarding_tips_body),
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    SectionCard(title = stringResource(R.string.onboarding_tip_gboard_title)) {
                        Text(
                            text = stringResource(R.string.onboarding_tip_gboard_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                        SectionCard(title = stringResource(R.string.onboarding_tip_samsung_title)) {
                            Text(
                                text = stringResource(R.string.onboarding_tip_samsung_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (Build.MANUFACTURER.equals("oneplus", ignoreCase = true)) {
                        SectionCard(title = stringResource(R.string.onboarding_tip_oneplus_title)) {
                            Text(
                                text = stringResource(R.string.onboarding_tip_oneplus_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { openNotificationSettings(context) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.onboarding_notification_settings))
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > STEP_SERVER) {
                TextButton(onClick = { step -= 1 }) {
                    Text(stringResource(R.string.utter_back))
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onAdvance, enabled = canContinue && !saving) {
                Text(stringResource(nextLabel))
            }
        }
    }
}

/** A large icon, a title and one calm sentence at the top of each step. */
@Composable
private fun StepHeading(icon: ImageVector, title: String, body: String) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(text = title, style = MaterialTheme.typography.headlineSmall)
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Six dots: done, current (larger), upcoming. */
@Composable
private fun ProgressDots(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(STEP_COUNT) { index ->
            val color = when {
                index == step -> MaterialTheme.colorScheme.primary
                index < step -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
            }
            Box(
                modifier = Modifier
                    .size(if (index == step) 10.dp else 7.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/**
 * Runs [onResume] every time the owner is resumed. Used for the enabled-keyboard and permission checks, which only
 * change while the user is inside another app's settings screen.
 */
@Composable
private fun OnResumeEffect(onResume: () -> Unit) {
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

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/**
 * Utter counts as enabled when the system's enabled-input-methods list contains its own service. That list is the
 * only reliable source: `Settings.Secure` needs a permission and the programming guide's intent round-trip is
 * asynchronous, while this call reflects the keyboard picker exactly.
 */
private fun isUtterEnabled(context: Context): Boolean {
    val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
    val serviceName = UtterIme::class.java.name
    return imm.enabledInputMethodList.any { info ->
        info.packageName == context.packageName && info.serviceName == serviceName
    }
}

private fun openAppInfo(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    runCatching { context.startActivity(intent) }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}
