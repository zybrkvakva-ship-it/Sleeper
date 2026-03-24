package com.sleeper.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sleeper.app.ui.theme.AppDuration
import com.sleeper.app.ui.theme.NightAccent
import com.sleeper.app.ui.theme.accentGreen
import com.sleeper.app.ui.theme.background
import com.sleeper.app.ui.theme.textPrimary

@Composable
fun CyberButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    primary: Boolean = false,
    strokeColor: Color = accentGreen,
    height: Dp = 56.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "cyberButtonScale"
    )
    val shape = MaterialTheme.shapes.medium

    // Smooth color transition when enabled changes
    val textColor by animateColorAsState(
        targetValue = when {
            primary && enabled  -> background
            primary && !enabled -> background.copy(alpha = 0.6f)
            enabled             -> strokeColor
            else                -> textPrimary.copy(alpha = 0.4f)
        },
        animationSpec = tween(AppDuration.ColorTransition),
        label = "cyberBtnTextColor"
    )
    val borderColor by animateColorAsState(
        targetValue = if (enabled) strokeColor else strokeColor.copy(alpha = 0.3f),
        animationSpec = tween(AppDuration.ColorTransition),
        label = "cyberBtnBorderColor"
    )

    Box(
        modifier = modifier
            .then(
                if (primary && enabled)
                    Modifier.cyberGlow(strokeColor, radius = 8.dp, alpha = 0.25f, cornerRadius = 12.dp)
                else Modifier
            )
            .height(height)
            .scale(scale)
            .then(
                if (primary)
                    Modifier.background(
                        if (enabled) strokeColor else strokeColor.copy(alpha = 0.3f),
                        shape
                    )
                else
                    Modifier
                        .border(1.5.dp, borderColor, shape)
                        .background(NightAccent.copy(alpha = if (enabled) 1f else 0.5f), shape)
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (primary) text.replaceFirstChar { it.uppercase() } else text,
            style = MaterialTheme.typography.labelLarge,
            color = textColor
        )
    }
}
