package com.snipsearch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Brand colors sampled from the launcher icon, a vertical blue gradient (#04142C, #093F78,
 * #3973A1) with a hue of about 207 degrees. Every tone below stays in that hue family.
 */
internal object SnipColors {
    /** Icon blue, the brand core. */
    val SnipBlue = Color(0xFF0E5794)

    /** Matches `ic_launcher_background`. */
    val SnipBlueDeep = Color(0xFF083A72)

    /** Light tint of the same hue for glows and highlights over dark imagery. */
    val SnipBlueGlow = Color(0xFF5EAAE8)

    // Tonal ramp derived from the same hue.
    val Blue10 = Color(0xFF001C37)
    val Blue20 = Color(0xFF003258)
    val Blue30 = Color(0xFF00497D)
    val Blue80 = Color(0xFF9CCBFF)
    val Blue90 = Color(0xFFD2E4FF)
}

private val LightColors: ColorScheme = lightColorScheme(
    primary = SnipColors.SnipBlue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = SnipColors.Blue90,
    onPrimaryContainer = SnipColors.Blue10,
    inversePrimary = SnipColors.Blue80,
    secondary = Color(0xFF526070),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5E4F7),
    onSecondaryContainer = Color(0xFF0E1D2A),
    tertiary = Color(0xFF2F6580),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC7E7FF),
    onTertiaryContainer = Color(0xFF001E2E),
    background = Color(0xFFF7F9FD),
    onBackground = Color(0xFF181C20),
    surface = Color(0xFFF7F9FD),
    onSurface = Color(0xFF181C20),
    surfaceVariant = Color(0xFFDDE3EA),
    onSurfaceVariant = Color(0xFF41484F),
    surfaceTint = SnipColors.SnipBlue,
    inverseSurface = Color(0xFF2D3135),
    inverseOnSurface = Color(0xFFEEF1F6),
    outline = Color(0xFF71787F),
    outlineVariant = Color(0xFFC1C7CE),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7F9FD),
    surfaceDim = Color(0xFFD8DADF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4F8),
    surfaceContainer = Color(0xFFECEEF3),
    surfaceContainerHigh = Color(0xFFE6E8ED),
    surfaceContainerHighest = Color(0xFFE0E3E7),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = SnipColors.Blue80,
    onPrimary = SnipColors.Blue20,
    primaryContainer = SnipColors.Blue30,
    onPrimaryContainer = SnipColors.Blue90,
    inversePrimary = SnipColors.SnipBlue,
    secondary = Color(0xFFB9C8DA),
    onSecondary = Color(0xFF243240),
    secondaryContainer = Color(0xFF3A4857),
    onSecondaryContainer = Color(0xFFD5E4F7),
    tertiary = Color(0xFF8DCFEF),
    onTertiary = Color(0xFF00344A),
    tertiaryContainer = Color(0xFF1B4B63),
    onTertiaryContainer = Color(0xFFC7E7FF),
    background = Color(0xFF0F1418),
    onBackground = Color(0xFFDFE3E8),
    surface = Color(0xFF0F1418),
    onSurface = Color(0xFFDFE3E8),
    surfaceVariant = Color(0xFF41484F),
    onSurfaceVariant = Color(0xFFC1C7CE),
    surfaceTint = SnipColors.Blue80,
    inverseSurface = Color(0xFFDFE3E8),
    inverseOnSurface = Color(0xFF2D3135),
    outline = Color(0xFF8B9198),
    outlineVariant = Color(0xFF41484F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353A3F),
    surfaceDim = Color(0xFF0F1418),
    surfaceContainerLowest = Color(0xFF0A0F13),
    surfaceContainerLow = Color(0xFF181C20),
    surfaceContainer = Color(0xFF1C2024),
    surfaceContainerHigh = Color(0xFF262A2F),
    surfaceContainerHighest = Color(0xFF31353A),
)

/** Material 3 theme built from the app-icon blue. It never uses dynamic color. */
@Composable
internal fun SnipTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
