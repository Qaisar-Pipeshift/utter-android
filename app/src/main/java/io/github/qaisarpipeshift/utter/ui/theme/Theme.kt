package io.github.qaisarpipeshift.utter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Handy's tokens (docs/spec.md §9). */
object UtterColors {
    val Accent = Color(0xFFDA5893)
    val AccentSoftLight = Color(0xFFFAA2CA)
    val AccentSoftDark = Color(0xFFF28CBB)
    val BackgroundLight = Color(0xFFFBFBFB)
    val BackgroundDark = Color(0xFF2C2B29)
    val TextLight = Color(0xFF0F0F0F)
    val TextDark = Color(0xFFFBFBFB)
    val MutedLight = Color(0xFF6E6E6E)
    val MutedDark = Color(0xFFA3A09A)
    val WarningLight = Color(0xFFD97706)
    val WarningDark = Color(0xFFFBBF24)
    val ErrorLight = Color(0xFFDC2626)
    val ErrorDark = Color(0xFFF87171)
}

private val Light = lightColorScheme(
    primary = UtterColors.Accent,
    onPrimary = Color.White,
    secondary = UtterColors.AccentSoftLight,
    background = UtterColors.BackgroundLight,
    onBackground = UtterColors.TextLight,
    surface = UtterColors.BackgroundLight,
    onSurface = UtterColors.TextLight,
    onSurfaceVariant = UtterColors.MutedLight,
    error = UtterColors.ErrorLight,
)

private val Dark = darkColorScheme(
    primary = UtterColors.Accent,
    onPrimary = Color.White,
    secondary = UtterColors.AccentSoftDark,
    background = UtterColors.BackgroundDark,
    onBackground = UtterColors.TextDark,
    surface = UtterColors.BackgroundDark,
    onSurface = UtterColors.TextDark,
    onSurfaceVariant = UtterColors.MutedDark,
    error = UtterColors.ErrorDark,
)

@Composable
fun UtterTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
