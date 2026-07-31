package com.cuetotech.vibetube.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VibeDarkColorScheme = darkColorScheme(
    primary = VibeRed,
    onPrimary = Color.White,
    primaryContainer = VibeRedContainer,
    onPrimaryContainer = VibeOnRedContainer,
    inversePrimary = VibeRed,
    secondary = Color(0xFFFFB3B8),
    onSecondary = Color(0xFF660012),
    secondaryContainer = Color(0xFF8F1D2C),
    onSecondaryContainer = Color(0xFFFFDADA),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD9E2),
    background = VibeBackground,
    onBackground = VibeOnSurface,
    surface = VibeSurface,
    onSurface = VibeOnSurface,
    surfaceVariant = VibeSurfaceVariant,
    onSurfaceVariant = VibeOnSurfaceVariant,
    surfaceContainerLowest = VibeSurfaceContainerLowest,
    surfaceContainerLow = VibeSurfaceContainerLow,
    surfaceContainer = VibeSurfaceContainer,
    surfaceContainerHigh = VibeSurfaceContainerHigh,
    surfaceContainerHighest = VibeSurfaceContainerHighest,
    outline = VibeOutline,
    outlineVariant = VibeOutlineVariant,
    error = Color(0xFFCF6679),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFFB3261E),
    onErrorContainer = Color(0xFFFFFFFF),
    inverseSurface = VibeOnSurface,
    inverseOnSurface = VibeSurfaceContainer,
    surfaceTint = VibeRed,
    scrim = Color(0xFF000000),
)

@Composable
fun VibeTubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VibeDarkColorScheme,
        typography = Typography,
        content = content,
    )
}
