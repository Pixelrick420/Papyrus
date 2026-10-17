package com.papyrus.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The app's blue, `primary` in *both* schemes, so the same blue shows in light and dark. */
private val Blue = Color(0xFF326AE3)

/**
 * Fixed, not wallpaper-derived: Android 12's dynamic scheme tinted every surface with the wallpaper
 * hue, so a white page sat on a coloured field. Accents are [Blue]; neutrals are true greys.
 */
private val LightColors: ColorScheme = lightColorScheme(
    primary = Blue,
    onPrimary = Color(0xFFFFFFFF), // 4.9:1 on Blue
    primaryContainer = Color(0xFFDCE3FB),
    onPrimaryContainer = Color(0xFF0A2472),
    inversePrimary = Blue,
    secondary = Blue,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE3FB),
    onSecondaryContainer = Color(0xFF0A2472),
    // Also [Blue]: the active-hit border in the PDF viewer is `tertiary` and the passive one is
    // `primary`, so equal colours leave thickness (5.dp against 3.dp) as the only signal.
    tertiary = Blue,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCE3FB),
    onTertiaryContainer = Color(0xFF0A2472),
    // The role `Scaffold` paints its background from. This was the lavender.
    background = Color(0xFFFBFBFB),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFCFCFC),
    onSurface = Color(0xFF1A1A1A),
    // The document backdrop. A true grey, never a tint: a page has to read as paper.
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF5C5C5C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F8F8),
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFF1F1F1),
    surfaceContainerHighest = Color(0xFFEBEBEB),
    surfaceDim = Color(0xFFE5E5E5),
    surfaceBright = Color(0xFFFCFCFC),
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFF2F2F2),
    outline = Color(0xFF767676),
    outlineVariant = Color(0xFFE5E5E5),
)

/**
 * Colour-blind safe: accents come from the Okabe-Ito palette, separable by hue alone under
 * protanopia, deuteranopia and tritanopia; surfaces match [LightColors] so a page still reads as paper.
 */
private val ColorblindColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF0072B2),
    onPrimary = Color(0xFFFFFFFF), // 5.2:1 on #0072B2
    primaryContainer = Color(0xFFD0E4F7),
    onPrimaryContainer = Color(0xFF003258),
    inversePrimary = Color(0xFF0072B2),
    secondary = Color(0xFFC25300),
    onSecondary = Color(0xFFFFFFFF), // 4.6:1 on #C25300
    secondaryContainer = Color(0xFFFBE0CE),
    onSecondaryContainer = Color(0xFF4D2000),
    tertiary = Color(0xFF008060),
    onTertiary = Color(0xFFFFFFFF), // 4.9:1 on #008060
    tertiaryContainer = Color(0xFFCDEEE2),
    onTertiaryContainer = Color(0xFF00301F),
    background = Color(0xFFFBFBFB),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFCFCFC),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF5C5C5C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F8F8),
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFF1F1F1),
    surfaceContainerHighest = Color(0xFFEBEBEB),
    surfaceDim = Color(0xFFE5E5E5),
    surfaceBright = Color(0xFFFCFCFC),
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFF2F2F2),
    outline = Color(0xFF767676),
    outlineVariant = Color(0xFFE5E5E5),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Blue,
    onPrimary = Color(0xFFFFFFFF), // 4.9:1 on Blue
    primaryContainer = Color(0xFF1E47B0),
    onPrimaryContainer = Color(0xFFDCE3FF),
    inversePrimary = Blue,
    secondary = Blue,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF1E47B0),
    onSecondaryContainer = Color(0xFFDCE3FF),
    tertiary = Blue,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF1E47B0),
    onTertiaryContainer = Color(0xFFDCE3FF),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF101010),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFFC4C4C4),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainer = Color(0xFF1C1C1C),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF313131),
    surfaceDim = Color(0xFF101010),
    surfaceBright = Color(0xFF363636),
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF1A1A1A),
    outline = Color(0xFF8E8E8E),
    outlineVariant = Color(0xFF3A3A3A),
)

private val ColorblindDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF64B6F7),
    onPrimary = Color(0xFF00385D),
    primaryContainer = Color(0xFF005D9F),
    onPrimaryContainer = Color(0xFFCCE5FF),
    inversePrimary = Color(0xFF0072B2),
    secondary = Color(0xFFF5A66E),
    onSecondary = Color(0xFF4D1E00),
    secondaryContainer = Color(0xFF703300),
    onSecondaryContainer = Color(0xFFFFDCC3),
    tertiary = Color(0xFF4DBAA0),
    onTertiary = Color(0xFF002F26),
    tertiaryContainer = Color(0xFF005E4A),
    onTertiaryContainer = Color(0xFFAAE7D9),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF101010),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFFC4C4C4),
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainer = Color(0xFF1C1C1C),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF313131),
    surfaceDim = Color(0xFF101010),
    surfaceBright = Color(0xFF363636),
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF1A1A1A),
    outline = Color(0xFF8E8E8E),
    outlineVariant = Color(0xFF3A3A3A),
)

@Composable
fun PapyrusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    colorblindMode: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        darkTheme && colorblindMode -> ColorblindDarkColors
        darkTheme -> DarkColors
        colorblindMode -> ColorblindColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
