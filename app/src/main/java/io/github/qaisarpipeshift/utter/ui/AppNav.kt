package io.github.qaisarpipeshift.utter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.data.SetupLink
import io.github.qaisarpipeshift.utter.ui.onboarding.OnboardingScreen
import io.github.qaisarpipeshift.utter.ui.screens.AboutScreen
import io.github.qaisarpipeshift.utter.ui.screens.AppRulesScreen
import io.github.qaisarpipeshift.utter.ui.screens.AppearanceScreen
import io.github.qaisarpipeshift.utter.ui.screens.BackupScreen
import io.github.qaisarpipeshift.utter.ui.screens.DiagnosticsScreen
import io.github.qaisarpipeshift.utter.ui.screens.DictationScreen
import io.github.qaisarpipeshift.utter.ui.screens.DictionaryScreen
import io.github.qaisarpipeshift.utter.ui.screens.HistoryScreen
import io.github.qaisarpipeshift.utter.ui.screens.HomeScreen
import io.github.qaisarpipeshift.utter.ui.screens.InsightsScreen
import io.github.qaisarpipeshift.utter.ui.screens.NotchScreen
import io.github.qaisarpipeshift.utter.ui.screens.ServerScreen
import io.github.qaisarpipeshift.utter.ui.screens.TextCleanupScreen
import io.github.qaisarpipeshift.utter.ui.screens.ShareSetupScreen

/**
 * Every destination of the settings app (docs/spec.md §16).
 *
 * String routes, not type-safe objects: the graph is small, a typo is caught by the compiler only in type-safe
 * mode anyway, and string routes keep this file readable while screens are still placeholders.
 */
object Routes {
    const val HOME = "home"
    const val ONBOARDING = "onboarding"
    const val SERVER = "server"
    const val DICTATION = "dictation"
    const val TEXT_CLEANUP = "text_cleanup"
    const val DICTIONARY = "dictionary"
    const val APP_RULES = "app_rules"
    const val HISTORY = "history"
    const val INSIGHTS = "insights"
    const val APPEARANCE = "appearance"
    const val NOTCH = "notch"
    const val BACKUP = "backup"
    const val DIAGNOSTICS = "diagnostics"
    const val ABOUT = "about"
    const val SHARE_SETUP = "share_setup"
}

/**
 * The navigation graph of the settings app (docs/plan.md Task 11).
 *
 * Predictive back is left entirely to the system: nothing here registers `OnBackPressedCallback`, so every screen
 * gets the platform's back swipe animation. The only back affordance this file adds is the top-bar arrow.
 */
@Composable
fun UtterAppNav(
    graph: Graph,
    startDestination: String = Routes.HOME,
    initialStep: Int = 0,
    micStepRequest: Int = 0,
    imported: SetupLink.Data? = null,
    onImportedConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()

    // The destination lambdas must read stable state objects, not parameters: NavHost remembers its builder, so a
    // captured value would stay frozen at the first composition.
    var onboardingImport by remember { mutableStateOf<SetupLink.Data?>(null) }
    var onboardingStepRequest by remember { mutableStateOf(0) }
    var handledMicRequests by remember { mutableStateOf(0) }

    // A confirmed utter://setup link always lands in the wizard, pre-filled; the link itself never becomes a route.
    LaunchedEffect(imported) {
        val data = imported ?: return@LaunchedEffect
        onboardingImport = data
        // An import starts from the server step even if an old "Grant mic" counter is still set.
        onboardingStepRequest = 0
        navController.navigate(Routes.ONBOARDING) { launchSingleTop = true }
    }

    // The IME's "Grant mic" also works when the settings app is already open (singleTop + onNewIntent). Only a new
    // request counts: consuming an import must not replay an old one.
    LaunchedEffect(micStepRequest) {
        if (micStepRequest > handledMicRequests) {
            handledMicRequests = micStepRequest
            onboardingStepRequest = micStepRequest
            navController.navigate(Routes.ONBOARDING) { launchSingleTop = true }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.HOME) {
            HomeScreen(
                graph = graph,
                onNavigate = { route -> navController.navigate(route) { launchSingleTop = true } },
            )
        }
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                graph = graph,
                initialStep = initialStep,
                stepRequest = onboardingStepRequest,
                imported = onboardingImport,
                onImportedConsumed = {
                    onboardingImport = null
                    onImportedConsumed()
                },
                onDone = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.SERVER) {
            ServerScreen(
                graph = graph,
                onBack = { navController.navigateUp() },
                onShareSetup = { navController.navigate(Routes.SHARE_SETUP) },
            )
        }
        composable(Routes.SHARE_SETUP) {
            ShareSetupScreen(graph = graph, onBack = { navController.navigateUp() })
        }
        composable(Routes.DICTATION) {
            DictationScreen(graph = graph, onBack = { navController.navigateUp() })
        }
        composable(Routes.TEXT_CLEANUP) {
            TextCleanupScreen(graph = graph, onBack = { navController.navigateUp() })
        }
        composable(Routes.DICTIONARY) {
            DictionaryScreen(graph = graph, onBack = { navController.navigateUp() })
        }
        composable(Routes.APP_RULES) {
            AppRulesScreen(graph = graph, onBack = { navController.navigateUp() })
        }
        composable(Routes.HISTORY) {
            HistoryScreen(graph = graph, onBack = { navController.navigateUp() })
        }
        composable(Routes.INSIGHTS) {
            InsightsScreen(graph = graph, onBack = { navController.navigateUp() })
        }
        composable(Routes.APPEARANCE) {
            AppearanceScreen(graph = graph, onBack = { navController.navigateUp() })
        }
        composable(Routes.NOTCH) {
            NotchScreen(graph = graph, onBack = { navController.navigateUp() })
        }
        composable(Routes.BACKUP) {
            BackupScreen(graph = graph, onBack = { navController.navigateUp() })
        }
        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(graph = graph, onBack = { navController.navigateUp() })
        }
        composable(Routes.ABOUT) {
            AboutScreen(graph = graph, onBack = { navController.navigateUp() })
        }
    }
}

/**
 * The settings scaffold: flat background, a top bar with a back arrow, and one scrollable padded column.
 *
 * Insets come from [Scaffold] so the content never sits under the status bar or the gesture bar; screens only add
 * their own 20 dp side padding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UtterScreen(
    title: String,
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = { UtterTopBar(title = title, onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/**
 * The shared top bar: a back arrow and the screen title over the flat background.
 *
 * Kept private so the scrolling and lazy scaffolds cannot drift apart — both must show the same affordance and
 * colour for predictive back to feel consistent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UtterTopBar(title: String, onBack: (() -> Unit)?) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.utter_back),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

/**
 * A lazy variant of [UtterScreen] for long lists (docs/plan.md Task 14).
 *
 * History can hold a thousand rows; composing all of them inside a scrolling Column would cost memory and time for
 * nothing. The paddings and top bar are identical to [UtterScreen], so the two only differ in how content is laid
 * out. The keyboard inset keeps the search field visible while typing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UtterListScreen(
    title: String,
    onBack: (() -> Unit)?,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = { UtterTopBar(title = title, onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
