package com.sleeper.app.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleeper.app.ui.theme.AppGlow
import com.sleeper.app.ui.theme.AppSpec
import com.sleeper.app.ui.theme.GreenGlow
import com.sleeper.app.ui.theme.NightAccent
import com.sleeper.app.ui.theme.NightDeep
import com.sleeper.app.ui.theme.StrokeGreen
import com.sleeper.app.ui.theme.accentGreen
import com.sleeper.app.ui.theme.textPrimary

/**
 * Animated hero orb for the Mining screen. Tapping the orb starts/stops mining.
 *
 * When mining:
 *  - Core breathes (scale ±4%)
 *  - Outer ring rotates continuously
 *  - Glow pulses between 30%–70% opacity
 *
 * When idle + enabled:
 *  - Slow pulse ring outside the orb — draws attention as a button
 *  - Green border visible (55% alpha)
 *
 * When disabled (no energy / token not verified):
 *  - Dimmed to 40% alpha, non-interactive
 */
@Composable
fun MiningHeroOrb(
    isMining: Boolean,
    npPerSecond: Float,
    onClick: () -> Unit,
    enabled: Boolean = true,
    uptimeMins: Long = 0L,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "orbAnim")

    // Press scale — quick spring feedback
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(stiffness = 600f),
        label = "pressScale"
    )

    // Disabled dim
    val contentAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.4f,
        animationSpec = tween(300),
        label = "disabledAlpha"
    )

    // Breathing scale 1.0 ↔ 1.04 — organic 2s cycle
    val breathRaw by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = AppSpec.breathing,
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathScale"
    )
    val breathScale by animateFloatAsState(
        targetValue = if (isMining) breathRaw else 1f,
        animationSpec = spring(),
        label = "breathScaleGate"
    )

    // Ring rotation 0 → 360° continuous
    val ringRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringRotation"
    )
    val effectiveRingRotation by animateFloatAsState(
        targetValue = if (isMining) ringRotation else 0f,
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "ringRotationGate"
    )

    // Glow alpha pulse
    val glowAlphaRaw by infiniteTransition.animateFloat(
        initialValue = AppGlow.MINING_ALPHA_MIN,
        targetValue = AppGlow.MINING_ALPHA_MAX,
        animationSpec = infiniteRepeatable(
            animation = AppSpec.glowPulse,
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )
    val effectiveGlowAlpha by animateFloatAsState(
        targetValue = if (isMining) glowAlphaRaw else AppGlow.IDLE_ALPHA,
        animationSpec = tween(600),
        label = "glowAlphaGate"
    )

    // Arc alphas
    val arc1Alpha by animateFloatAsState(
        targetValue = if (isMining) AppGlow.ORB_ARC1_MINING else AppGlow.ORB_ARC1_IDLE,
        animationSpec = tween(600),
        label = "arc1Alpha"
    )
    val arc2Alpha by animateFloatAsState(
        targetValue = if (isMining) AppGlow.ORB_ARC2_MINING else AppGlow.ORB_ARC2_IDLE,
        animationSpec = tween(600),
        label = "arc2Alpha"
    )

    // Idle pulse ring — медленный пульс снаружи орба когда IDLE+enabled
    val idlePulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idlePulseAlpha"
    )
    val idlePulseScale by infiniteTransition.animateFloat(
        initialValue = 1.00f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idlePulseScale"
    )

    // Предвычисляем uptime строку до Composable-дерева
    val uptimeStr: String? = if (isMining && uptimeMins > 0L) {
        val h = uptimeMins / 60
        val m = uptimeMins % 60
        if (h > 0) "${h}:${String.format("%02d", m)}" else "${m}m"
    } else null

    // Outer box — даёт место для pulse ring
    Box(
        modifier = modifier.size(240.dp),
        contentAlignment = Alignment.Center
    ) {
        // Pulse ring — только IDLE + enabled
        if (!isMining && enabled) {
            Canvas(
                modifier = Modifier
                    .size(240.dp)
                    .scale(idlePulseScale)
            ) {
                drawCircle(
                    color = accentGreen.copy(alpha = idlePulseAlpha),
                    radius = size.minDimension / 2f - 4.dp.toPx(),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        // Core clickable orb (220dp)
        Box(
            modifier = Modifier
                .size(220.dp)
                .scale(pressScale)
                .alpha(contentAlpha)
                .clip(CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            // Outer glow layer
            Canvas(modifier = Modifier.size(220.dp)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            GreenGlow.copy(alpha = effectiveGlowAlpha),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.minDimension / 2f
                    )
                )
            }

            // Rotating arc ring
            Canvas(
                modifier = Modifier
                    .size(180.dp)
                    .rotate(effectiveRingRotation)
            ) {
                drawArc(
                    color = accentGreen.copy(alpha = arc1Alpha),
                    startAngle = 0f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = accentGreen.copy(alpha = arc2Alpha),
                    startAngle = 300f,
                    sweepAngle = 40f,
                    useCenter = false,
                    style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Core orb (130dp)
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .scale(breathScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(NightAccent, NightDeep),
                            center = Offset(65.dp.value, 65.dp.value),
                            radius = 130f
                        )
                    )
                    .border(
                        width = if (isMining) 1.dp else 1.5.dp,
                        color = when {
                            isMining -> StrokeGreen
                            enabled  -> accentGreen.copy(alpha = 0.55f)
                            else     -> Color.White.copy(alpha = 0.08f)
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isMining) "MINING" else "IDLE",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isMining) accentGreen else textPrimary.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "%.2f NP/s".format(npPerSecond),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isMining) textPrimary else textPrimary.copy(alpha = 0.5f)
                    )
                    // Uptime — только когда майнинг идёт
                    if (uptimeStr != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = uptimeStr,
                            fontSize = 9.sp,
                            color = textPrimary.copy(alpha = 0.4f),
                            letterSpacing = 0.5.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Кнопочный хинт — крупный и заметный
                    Text(
                        text = if (isMining) "◼ СТОП" else "▶ СТАРТ",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isMining)
                            Color.White.copy(alpha = 0.55f)
                        else
                            accentGreen.copy(alpha = if (enabled) 1.0f else 0.0f),
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}
