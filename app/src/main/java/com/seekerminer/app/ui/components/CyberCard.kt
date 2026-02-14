package com.seekerminer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.seekerminer.app.ui.theme.border
import com.seekerminer.app.ui.theme.surface

@Composable
fun CyberCard(
    modifier: Modifier = Modifier,
    strokeColor: Color = border,
    strokeWidth: Dp = 1.dp,
    cornerRadius: Dp = 12.dp,
    elevation: Dp = 2.dp,
    glowColor: Color? = null,
    content: @Composable () -> Unit
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .then(
                if (glowColor != null) Modifier.shadow(8.dp, shape, ambientColor = glowColor, spotColor = glowColor)
                else Modifier.shadow(elevation, shape)
            )
            .background(surface, shape)
            .border(strokeWidth, strokeColor, shape)
            .padding(1.dp)
    ) {
        content()
    }
}
