package com.seekerminer.app.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seekerminer.app.ui.theme.accentGold
import com.seekerminer.app.ui.theme.accentGreen
import com.seekerminer.app.ui.theme.background
import com.seekerminer.app.ui.theme.errorRed

@Composable
fun MiningButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isMining: Boolean = false
) {
    val view = LocalView.current

    val infiniteTransition = rememberInfiniteTransition(label = "mining_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val pulseScale = if (isMining) scale else 1f
    val shape = MaterialTheme.shapes.medium
    val showGlow = enabled && !isMining

    Button(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        },
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth(0.6f)
            .then(
                if (showGlow) Modifier.shadow(
                    12.dp,
                    shape,
                    ambientColor = accentGreen.copy(alpha = 0.35f),
                    spotColor = accentGreen.copy(alpha = 0.35f)
                )
                else Modifier
            )
            .height(72.dp)
            .padding(vertical = 8.dp)
            .scale(pulseScale),
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isMining) errorRed else accentGreen,
            contentColor = background,
            disabledContainerColor = accentGold.copy(alpha = 0.2f),
            disabledContentColor = background.copy(alpha = 0.5f)
        )
    ) {
        Text(
            text = text.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelLarge,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
