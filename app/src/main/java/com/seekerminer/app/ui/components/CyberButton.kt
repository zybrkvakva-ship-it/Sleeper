package com.seekerminer.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.seekerminer.app.ui.theme.accentGreen
import com.seekerminer.app.ui.theme.background
import com.seekerminer.app.ui.theme.border
import com.seekerminer.app.ui.theme.textPrimary

@Composable
fun CyberButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    strokeColor: Color = accentGreen,
    height: androidx.compose.ui.unit.Dp = 56.dp
) {
    val interactionSource = MutableInteractionSource()
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = tween(50), label = "scale"
    )
    val shape = MaterialTheme.shapes.medium
    val showGlow = primary && enabled

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (showGlow) Modifier.shadow(10.dp, shape, ambientColor = strokeColor.copy(alpha = 0.4f), spotColor = strokeColor.copy(alpha = 0.4f))
                else Modifier
            )
            .height(height)
            .scale(scale)
            .then(
                if (primary) Modifier.background(
                    if (enabled) strokeColor else strokeColor.copy(alpha = 0.3f),
                    shape
                )
                else Modifier
                    .border(1.5.dp, strokeColor, shape)
                    .background(background.copy(alpha = if (enabled) 0.3f else 0.1f), shape)
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (primary) text.replaceFirstChar { it.uppercase() } else text,
            style = MaterialTheme.typography.labelLarge,
            fontSize = 16.sp,
            color = when {
                primary && enabled -> background
                primary && !enabled -> background.copy(alpha = 0.6f)
                else -> if (enabled) strokeColor else textPrimary.copy(alpha = 0.5f)
            }
        )
    }
}
