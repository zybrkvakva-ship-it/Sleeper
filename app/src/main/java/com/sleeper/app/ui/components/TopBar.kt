package com.sleeper.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sleeper.app.ui.theme.accentGreen
import com.sleeper.app.ui.theme.background
import com.sleeper.app.ui.theme.border
import com.sleeper.app.ui.theme.textMuted

@Composable
fun TopBar(
    modifier: Modifier = Modifier,
    statusCenter: String = "ONLINE",
    statusRight: String? = null,
    leftContent: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leftContent != null) {
            leftContent()
        } else {
            Text(
                text = "Sleeper ",
                style = MaterialTheme.typography.labelMedium,
                fontSize = 14.sp,
                color = textMuted
            )
            Text(
                text = ">",
                style = MaterialTheme.typography.labelMedium,
                fontSize = 14.sp,
                color = accentGreen
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(8.dp)
                    .background(accentGreen, CircleShape)
            ) {}
            Text(
                text = statusCenter.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 12.sp,
                color = accentGreen
            )
        }
        if (statusRight != null) {
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = statusRight,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 12.sp,
                color = textMuted
            )
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(border)
    ) {}
}
