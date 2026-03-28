package com.sleeper.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = accentGreen,
    secondary = accentGold,
    tertiary = textMuted,
    background = background,
    surface = NightAccent,
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
        content = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to NightDeep,
                                0.45f to background,
                                1f to Color(0xFF0D1018)
                            )
                        )
                    )
            ) {
                content()
            }
        }
    )
}
