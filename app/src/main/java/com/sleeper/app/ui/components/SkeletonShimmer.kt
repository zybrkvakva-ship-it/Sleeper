package com.sleeper.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sleeper.app.ui.theme.NightAccent
import com.sleeper.app.ui.theme.NightSurface

/**
 * A single shimmer placeholder box.
 * Use inside a Column/LazyColumn while content is loading.
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    cornerRadius: Dp = 8.dp
) {
    val shimmerX by rememberInfiniteTransition(label = "skeletonShimmer").animateFloat(
        initialValue = -600f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslateX"
    )

    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    colors = listOf(NightAccent, NightSurface, NightAccent),
                    start = Offset(shimmerX, 0f),
                    end = Offset(shimmerX + 400f, 0f)
                )
            )
    )
}

/**
 * Skeleton row for leaderboard — rank + username + score placeholders.
 */
@Composable
fun LeaderboardSkeletonItem(modifier: Modifier = Modifier) {
    CyberCard(
        modifier = modifier.fillMaxWidth(),
        strokeWidth = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShimmerBox(modifier = Modifier.weight(0.12f), height = 14.dp)
            ShimmerBox(modifier = Modifier.weight(0.50f), height = 14.dp)
            ShimmerBox(modifier = Modifier.weight(0.25f), height = 14.dp)
        }
    }
}
