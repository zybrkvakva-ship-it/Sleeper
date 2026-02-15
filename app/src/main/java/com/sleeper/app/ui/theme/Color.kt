package com.sleeper.app.ui.theme

import androidx.compose.ui.graphics.Color

// ——— Seed Vault design tokens ———
val background = Color(0xFF0B0C0E)
val surface = Color(0xFF0F1315)
val accentGreen = Color(0xFF00E88A)
val accentGold = Color(0xFFF2C357)
val textPrimary = Color(0xFFE6EEF0)
val textMuted = Color(0xFF9AA0A6)
val border = Color(0x10FFFFFF)
val errorRed = Color(0xFFFF3B3B)

// ——— Backward-compatible aliases (existing screens/components) ———
val BgMain = background
val BgCard = surface
val CyberGreen = accentGreen
val CyberRed = errorRed
val CyberYellow = accentGold
val CyberWhite = textPrimary
val CyberGray = textMuted
val Stroke = border

/** Фон нижней навигационной панели (чуть светлее основного фона) */
val BottomBarBackground = Color(0xFF121518)

// Legacy aliases
val Black = BgMain
val White = CyberWhite
val NeonGreen = CyberGreen
val NeonGreenDark = Color(0xFF00CC7A)
val BrightOrange = CyberYellow
val BrightOrangeDark = Color(0xFFE6BE5C)
val DarkGray = BgCard
val MediumGray = Color(0xFF1A1A1A)
val LightGray = CyberGray
val Red = CyberRed
val Purple = Color(0xFF9C27B0)
val Blue = Color(0xFF2196F3)
val Cyan = Color(0xFF00BCD4)
