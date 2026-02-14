package com.sleeper.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Seed Vault: Inter-like (SansSerif) for UI; JetBrains Mono (Monospace) for numbers.
// To use real Inter/JetBrains Mono, add .ttf to res/font and replace with Font(R.font.*).
private val Inter = FontFamily.SansSerif
private val JetBrainsMono = FontFamily.Monospace

private val letterSpacing = 0.12.sp

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        letterSpacing = letterSpacing
    ),
    displayMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = letterSpacing
    ),
    headlineLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        letterSpacing = letterSpacing
    ),
    headlineMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = letterSpacing
    ),
    titleMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = letterSpacing
    ),
    bodyLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        letterSpacing = letterSpacing
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        letterSpacing = letterSpacing
    ),
    labelLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        letterSpacing = letterSpacing
    ),
    labelMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = letterSpacing
    ),
    labelSmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = letterSpacing
    )
)

/** Seed Vault numeric style: JetBrains Mono Bold 48sp (for large numbers, rewards). */
val Typography.numeric: TextStyle
    get() = TextStyle(
        fontFamily = JetBrainsMono,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        letterSpacing = letterSpacing
    )
