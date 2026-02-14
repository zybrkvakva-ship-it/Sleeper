package com.seekerminer.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.seekerminer.app.ui.theme.BgMain

/**
 * Кибер-оверлей: виньетка по краям + сканлайны.
 * Рисуется поверх контента в том же слое (через Modifier), не перехватывает касания.
 */
@Composable
fun CyberOverlay(
    modifier: Modifier = Modifier,
    vignetteStrength: Float = 0.25f,
    scanlinesAlpha: Float = 0.03f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "scanline")
    val scanlineOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "offset"
    )

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val r = minOf(w, h) * 0.5f

            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        BgMain.copy(alpha = vignetteStrength)
                    ),
                    center = Offset(w / 2f, h / 2f),
                    radius = r
                ),
                size = size
            )

            if (scanlinesAlpha > 0f) {
                val lineHeight = 2f
                var y = (scanlineOffset * 20f) % 20f - 20f
                while (y < h) {
                    drawRect(
                        color = Color.White.copy(alpha = scanlinesAlpha),
                        topLeft = Offset(0f, y),
                        size = Size(w, lineHeight)
                    )
                    y += 20f
                }
            }
        }
    }
}

/**
 * Модификатор: рисует контент, затем поверх — виньетку. Не создаёт отдельного слоя для касаний.
 */
fun Modifier.cyberOverlayModifier(
    vignetteStrength: Float = 0.2f
): Modifier = this.drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.Transparent,
                BgMain.copy(alpha = vignetteStrength)
            ),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = minOf(size.width, size.height) * 0.5f
        ),
        size = size
    )
}
