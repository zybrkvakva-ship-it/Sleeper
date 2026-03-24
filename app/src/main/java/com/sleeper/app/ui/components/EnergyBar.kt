package com.sleeper.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sleeper.app.R
import com.sleeper.app.ui.theme.AppDuration
import com.sleeper.app.ui.theme.AppGlow
import com.sleeper.app.ui.theme.NightAccent
import com.sleeper.app.ui.theme.accentGold
import com.sleeper.app.ui.theme.accentGreen
import com.sleeper.app.ui.theme.textPrimary

@Composable
fun EnergyBar(
    current: Int,
    max: Int,
    isMining: Boolean = false,
    modifier: Modifier = Modifier
) {
    val percentage = if (max > 0) current.toFloat() / max else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = percentage,
        animationSpec = tween(AppDuration.Normal),
        label = "energyProgress"
    )

    // Shimmer sweeps continuously; alpha gates it smoothly on/off
    val shimmerTranslate by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -400f,
        targetValue = 800f,
        animationSpec = infiniteRepeatable(
            animation = tween(AppDuration.Shimmer, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerX"
    )
    val shimmerAlpha by animateFloatAsState(
        targetValue = if (isMining && animatedProgress > 0.05f) AppGlow.SHIMMER_ALPHA else 0f,
        animationSpec = tween(AppDuration.Normal),
        label = "shimmerAlpha"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.mining_energy, current, max),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = textPrimary
            )
            Text(
                text = "${(percentage * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = accentGreen
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(NightAccent)
        ) {
            // Filled portion
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .height(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(accentGreen, accentGold),
                            start = Offset.Zero,
                            end = Offset(600f, 0f)
                        )
                    )
            ) {
                // Shimmer highlight — fades in/out via alpha
                if (shimmerAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = shimmerAlpha),
                                        Color.White.copy(alpha = shimmerAlpha * 0.5f),
                                        Color.Transparent
                                    ),
                                    start = Offset(shimmerTranslate, 0f),
                                    end = Offset(shimmerTranslate + 200f, 0f)
                                )
                            )
                    )
                }
            }
        }
    }
}
