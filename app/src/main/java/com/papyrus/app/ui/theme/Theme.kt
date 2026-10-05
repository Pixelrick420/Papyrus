package com.papyrus.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app's blue. It used to be read off the launcher icon background so icon and UI were one
 * colour; the icon is now a white page with grey lines, so this stands alone. It is `primary` in
 * *both* schemes -- not a tint in one and the tone-80 step in the other -- so the same blue shows
 * in light and dark.
 */
private val Blue = Color(0xFF326AE3)

/**
 * Fixed, not wallpaper-derived: Android 12's `dynamicLightColorScheme` tinted every surface role
 * with the wallpaper hue, and stock M3 below it fell back to a lavender `background` (#FFFBFE =
 * 255/251/254). Three surfaces paint from a surface role -- PDF letterbox, home cards, Office table
 * cells -- so a white page sat on a coloured field.
 *
 * One blue: every accent role is [Blue] or a tonal step of it, containers included, so a filled
 * `primaryContainer` never reads as a second brand colour. Neutrals are true greys (R == G == B):
 * M3's `surfaceVariant` #E7E0EC is visibly lavender, and one step per channel is enough to tint a
 * full-screen background. `background` is #FBFBFB, not white, to avoid glare without a cast.
 * `error` keeps the M3 baseline -- the re-grant banner needs a warning red, and red is not an accent.
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
    surfaceContainerLow = Color(0xFFF5F5F5),
    surfaceContainer = Color(0xFFF1F1F1),
    surfaceContainerHigh = Color(0xFFEBEBEB),
    surfaceContainerHighest = Color(0xFFE5E5E5),
    surfaceDim = Color(0xFFDCDCDC),
    surfaceBright = Color(0xFFFCFCFC),
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFF2F2F2),
    outline = Color(0xFF767676),
    outlineVariant = Color(0xFFDCDCDC),
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

@Composable
fun PapyrusTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
