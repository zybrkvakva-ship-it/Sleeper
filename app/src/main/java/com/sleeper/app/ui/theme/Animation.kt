package com.sleeper.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale

/** Physics-based easing curves */
object AppEasing {
    val EaseOutCubic    = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
    val EaseInOutQuart  = CubicBezierEasing(0.76f, 0f, 0.24f, 1f)
    val EaseOutBack     = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
    val EaseOutExpo     = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
}

/** Standard durations (ms) */
object AppDuration {
    const val Fast            = 150
    const val Normal          = 280
    const val Slow            = 420
    const val Stagger         = 55    // delay between staggered list items
    const val Shimmer         = 1200  // shimmer sweep cycle
    const val ColorTransition = 200   // animateColor transitions
}

/** Glow / alpha constants for orb and shimmer effects */
object AppGlow {
    const val IDLE_ALPHA        = 0.15f
    const val MINING_ALPHA_MIN  = 0.25f
    const val MINING_ALPHA_MAX  = 0.60f
    const val SHIMMER_ALPHA     = 0.30f
    const val SHIMMER_ALPHA_MID = 0.15f
    const val ORB_ARC1_MINING   = 0.45f
    const val ORB_ARC1_IDLE     = 0.15f
    const val ORB_ARC2_MINING   = 0.20f
    const val ORB_ARC2_IDLE     = 0.08f
}

/** Reusable animation specs */
object AppSpec {
    val breathing   = tween<Float>(2000, easing = FastOutSlowInEasing)
    val glowPulse   = tween<Float>(2200, easing = FastOutSlowInEasing)
    val colorChange = tween<androidx.compose.ui.graphics.Color>(AppDuration.ColorTransition)
}

/**
 * Press-scale micro-interaction: shrinks to [scale] on press, springs back.
 * Works alongside the composable's own click handling — does not consume clicks.
 */
fun Modifier.pressScale(
    enabled: Boolean = true,
    scale: Float = 0.96f
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animScale by animateFloatAsState(
        targetValue = if (isPressed && enabled) scale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pressScale"
    )
    this
        .scale(animScale)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = false,  // click handled by the component itself
            onClick = {}
        )
}
