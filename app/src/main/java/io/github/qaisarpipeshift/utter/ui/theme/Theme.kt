package io.github.qaisarpipeshift.utter.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.qaisarpipeshift.utter.data.Palette

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
    primaryContainer = Color(0xFFFBE3EE),
    onPrimaryContainer = Color(0xFF3A1026),
    secondary = UtterColors.AccentSoftLight,
    onSecondary = Color(0xFF3A1026),
    secondaryContainer = Color(0xFFFCE7F0),
    onSecondaryContainer = Color(0xFF3A1026),
    background = UtterColors.BackgroundLight,
    onBackground = UtterColors.TextLight,
    surface = UtterColors.BackgroundLight,
    onSurface = UtterColors.TextLight,
    surfaceVariant = Color(0xFFF2F0ED),
    onSurfaceVariant = UtterColors.MutedLight,
    outline = Color(0xFFD8D4CE),
    outlineVariant = Color(0xFFE7E4DF),
    error = UtterColors.ErrorLight,
    onError = Color.White,
    errorContainer = Color(0xFFFDE8E8),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val Dark = darkColorScheme(
    primary = UtterColors.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A2235),
    onPrimaryContainer = Color(0xFFFBD9E8),
    secondary = UtterColors.AccentSoftDark,
    onSecondary = Color(0xFF3A1026),
    secondaryContainer = Color(0xFF4A2235),
    onSecondaryContainer = Color(0xFFFBD9E8),
    background = UtterColors.BackgroundDark,
    onBackground = UtterColors.TextDark,
    surface = UtterColors.BackgroundDark,
    onSurface = UtterColors.TextDark,
    surfaceVariant = Color(0xFF3A3936),
    onSurfaceVariant = UtterColors.MutedDark,
    outline = Color(0xFF5C5A55),
    outlineVariant = Color(0xFF47453F),
    error = UtterColors.ErrorDark,
    onError = Color(0xFF2C0B0B),
    errorContainer = Color(0xFF5A1F1F),
    onErrorContainer = Color(0xFFFBD5D5),
)

/**
 * Larger, semibold titles than Material's defaults (docs/plan.md Task 11).
 *
 * The first screen a user sees is the wizard, so headings carry the hierarchy: 32 sp titles, 16 sp body. Styles are
 * copied from the Material base so anything not overridden keeps the stock metrics.
 */
private val UtterTypography: Typography = with(Typography()) {
    Typography(
        displayLarge = displayLarge,
        displayMedium = displayMedium,
        displaySmall = displaySmall,
        headlineLarge = headlineLarge.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium,
        labelSmall = labelSmall,
    )
}

/** Flat 16 dp cards, the Handy radius (docs/spec.md §9). */
private val UtterShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * Whether the current [UtterTheme] is dark. Tokens that Material 3 has no slot for — the warning colour of the
 * panel's elapsed timer — are picked from [UtterColors] through this local, so a forced theme beats the system one.
 */
val LocalUtterDark = staticCompositionLocalOf { false }

/** The warning token for the active theme; Material has no warning role. */
@Composable
fun utterWarningColor(): Color = if (LocalUtterDark.current) UtterColors.WarningDark else UtterColors.WarningLight

/**
 * The one theme for the keyboard panel and the settings app (docs/spec.md §9, docs/plan.md Task 11).
 *
 * [palette] is the user's choice: [Palette.HANDY] uses the shipped tokens, [Palette.DYNAMIC] asks Android 12+ for
 * wallpaper colours. Dynamic colour is only available on API 31+, so older phones quietly keep the Handy palette
 * instead of crashing or asking the user to choose again.
 */
@Composable
fun UtterTheme(
    dark: Boolean = isSystemInDarkTheme(),
    palette: Palette = Palette.HANDY,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = if (palette == Palette.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) Dark else Light
    }
    CompositionLocalProvider(LocalUtterDark provides dark) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = UtterTypography,
            shapes = UtterShapes,
            content = content,
        )
    }
}
