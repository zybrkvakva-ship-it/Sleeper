package com.sleeper.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sleeper.app.ui.theme.NightAccent
import com.sleeper.app.ui.theme.border as defaultStroke

/**
 * Applies a soft, layered glow effect visible on dark backgrounds.
 *
 * Uses multiple translucent rounded-rect layers drawn behind the composable —
 * no software-layer required, works on API 26+.
 */
fun Modifier.cyberGlow(
    color: Color,
    radius: Dp = 10.dp,
    alpha: Float = 0.35f,
    cornerRadius: Dp = 12.dp
): Modifier = this.drawBehind {
    val radiusPx = radius.toPx()
    val cRadius = cornerRadius.toPx()
    repeat(3) { i ->
        val spread = radiusPx * (i + 1) / 3f
        val layerAlpha = alpha * (1f - i * 0.28f)
        drawIntoCanvas { canvas ->
            canvas.drawRoundRect(
                left = -spread,
                top = -spread,
                right = size.width + spread,
                bottom = size.height + spread,
                radiusX = cRadius + spread * 0.4f,
                radiusY = cRadius + spread * 0.4f,
                paint = Paint().also { p -> p.color = color.copy(alpha = layerAlpha) }
            )
        }
    }
}

/**
 * CyberCard — themed dark card with optional glow.
 *
 * Tier 1 (Hero):    pass glowColor + accentGreen stroke, 1.5.dp stroke
 * Tier 2 (Content): default — NightAccent fill + subtle border
 * Tier 3 (Inline):  strokeWidth = 0.dp, no glow
 */
@Composable
fun CyberCard(
    modifier: Modifier = Modifier,
    strokeColor: Color = defaultStroke,
    strokeWidth: Dp = 1.dp,
    cornerRadius: Dp = 12.dp,
    glowColor: Color? = null,
    fillColor: Color = NightAccent,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .then(
                if (glowColor != null)
                    Modifier.cyberGlow(glowColor, radius = 12.dp, alpha = 0.30f, cornerRadius = cornerRadius)
                else
                    Modifier
            )
            .background(fillColor, shape)
            .then(
                if (strokeWidth > 0.dp)
                    Modifier.border(strokeWidth, strokeColor, shape)
                else
                    Modifier
            )
    ) {
        content()
    }
}
