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

// ——— Background depth layers (3 levels) ———
val NightDeep    = Color(0xFF07080A)   // darkest — gradient top / under cards
val NightMid     = background          // alias — primary background
val NightAccent  = Color(0xFF12141A)   // card surfaces
val NightSurface = Color(0xFF1A1D26)   // elevated surface / shimmer highlight

// ——— Glow colours (for drawBehind glow layers) ———
val GreenGlow    = Color(0x4000E88A)   // 25% alpha green
val GoldGlow     = Color(0x40F2C357)   // 25% alpha gold
val GreenDim     = Color(0xFF00A862)
val GoldDim      = Color(0xFFB8923F)

// ——— Stroke / border variants ———
val StrokeGreen  = Color(0x3300E88A)   // 20% alpha green
val StrokeGold   = Color(0x33F2C357)
val StrokeWhite  = Color(0x1AFFFFFF)   // 10% white

// ——— Backward-compatible aliases (existing screens/components) ———
val BgMain = background
val BgCard = surface
val CyberGreen = accentGreen
val CyberRed = errorRed
val CyberYellow = accentGold
val CyberWhite = textPrimary
val CyberGray = textMuted
val Stroke = border

/** Фон нижней навигационной панели */
val BottomBarBackground = Color(0xFF0E1014)

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
