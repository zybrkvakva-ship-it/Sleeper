package com.sleeper.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = accentGreen,
    secondary = accentGold,
    tertiary = textMuted,
    background = background,
    surface = surface,
    surfaceVariant = MediumGray,
    outline = border,
    onPrimary = background,
    onSecondary = background,
    onTertiary = textPrimary,
    onBackground = textPrimary,
    onSurface = textPrimary,
    onSurfaceVariant = textMuted,
    error = errorRed,
    onError = background
)

@Composable
fun SleeperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        shapes = SleeperShapes,
        content = content
    )
}
