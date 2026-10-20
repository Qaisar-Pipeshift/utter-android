package io.github.qaisarpipeshift.utter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.qaisarpipeshift.utter.Graph
import io.github.qaisarpipeshift.utter.R
import io.github.qaisarpipeshift.utter.ui.components.SectionCard
import io.github.qaisarpipeshift.utter.ui.components.SettingRow

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
fun UtterAppNav(graph: Graph, startDestination: String = Routes.HOME) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenServer = { navController.navigate(Routes.SERVER) },
                onOpenShareSetup = { navController.navigate(Routes.SHARE_SETUP) },
            )
        }
        // Task 12 replaces this placeholder with the wizard.
        composable(Routes.ONBOARDING) {
            PlaceholderScreen(stringResource(R.string.onboarding_title)) { navController.navigateUp() }
        }
        // Task 12 replaces this placeholder with the real Server screen.
        composable(Routes.SERVER) {
            PlaceholderScreen(stringResource(R.string.server_title)) { navController.navigateUp() }
        }
        composable(Routes.DICTATION) {
            PlaceholderScreen(stringResource(R.string.screen_dictation)) { navController.navigateUp() }
        }
        composable(Routes.TEXT_CLEANUP) {
            PlaceholderScreen(stringResource(R.string.screen_text_cleanup)) { navController.navigateUp() }
        }
        composable(Routes.DICTIONARY) {
            PlaceholderScreen(stringResource(R.string.screen_dictionary)) { navController.navigateUp() }
        }
        composable(Routes.APP_RULES) {
            PlaceholderScreen(stringResource(R.string.screen_app_rules)) { navController.navigateUp() }
        }
        composable(Routes.HISTORY) {
            PlaceholderScreen(stringResource(R.string.screen_history)) { navController.navigateUp() }
        }
        composable(Routes.INSIGHTS) {
            PlaceholderScreen(stringResource(R.string.screen_insights)) { navController.navigateUp() }
        }
        composable(Routes.APPEARANCE) {
            PlaceholderScreen(stringResource(R.string.screen_appearance)) { navController.navigateUp() }
        }
        composable(Routes.NOTCH) {
            PlaceholderScreen(stringResource(R.string.screen_notch)) { navController.navigateUp() }
        }
        composable(Routes.BACKUP) {
            PlaceholderScreen(stringResource(R.string.screen_backup)) { navController.navigateUp() }
        }
        composable(Routes.DIAGNOSTICS) {
            PlaceholderScreen(stringResource(R.string.screen_diagnostics)) { navController.navigateUp() }
        }
        composable(Routes.ABOUT) {
            PlaceholderScreen(stringResource(R.string.screen_about)) { navController.navigateUp() }
        }
        // Task 12 replaces this placeholder with the Share setup screen.
        composable(Routes.SHARE_SETUP) {
            PlaceholderScreen(stringResource(R.string.share_title)) { navController.navigateUp() }
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
        topBar = {
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
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/**
 * Home until Task 13 builds the dashboard: the app's only title plus entry points to the screens that already
 * exist. It is deliberately plain — the real Home has the status card, quick toggles and today's stats.
 */
@Composable
private fun HomeScreen(
    onOpenServer: () -> Unit,
    onOpenShareSetup: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionCard(title = stringResource(R.string.utter_coming_soon)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.home_coming_soon_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SectionCard(title = stringResource(R.string.home_settings)) {
                SettingRow(
                    title = stringResource(R.string.server_title),
                    value = stringResource(R.string.home_server_value),
                    onClick = onOpenServer,
                )
                SettingRow(
                    title = stringResource(R.string.share_title),
                    value = stringResource(R.string.share_setup_short),
                    onClick = onOpenShareSetup,
                )
            }
        }
    }
}

/** A calm placeholder for a screen a later task implements (docs/plan.md Task 11). */
@Composable
private fun PlaceholderScreen(title: String, onBack: () -> Unit) {
    UtterScreen(title = title, onBack = onBack) {
        SectionCard(title = stringResource(R.string.utter_coming_soon)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.utter_coming_soon_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
