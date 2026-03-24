package com.sleeper.app.ui.screen.tasks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sleeper.app.R
import com.sleeper.app.data.local.TaskEntity
import com.sleeper.app.data.local.TaskType
import com.sleeper.app.ui.components.CyberCard
import com.sleeper.app.ui.theme.*
import com.sleeper.app.ui.theme.AppDuration

@Composable
fun TasksScreen(
    viewModel: TasksViewModel = viewModel()
) {
    val tasks by viewModel.tasks.collectAsState()

    val dailyTasks   = tasks.filter { it.type == TaskType.DAILY }
    val specialTasks = tasks.filter { it.type == TaskType.SPECIAL }

    // Use LazyColumn for stagger-friendly layout
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Daily tasks header
        item {
            Text(
                text = stringResource(R.string.tasks_daily_energy).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = CyberWhite
            )
            Spacer(Modifier.height(8.dp))
        }

        // Daily tasks — staggered entrance
        itemsIndexed(dailyTasks) { index, task ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(AppDuration.Normal, delayMillis = index * AppDuration.Stagger)) +
                        slideInVertically(tween(AppDuration.Normal, delayMillis = index * AppDuration.Stagger)) { it / 3 }
            ) {
                TaskCard(task = task, onComplete = { viewModel.completeTask(task.id) })
            }
        }

        // Special tasks header
        item {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.tasks_special_energy).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = CyberYellow
            )
            Spacer(Modifier.height(8.dp))
        }

        // Special tasks — staggered entrance (offset index by dailyTasks count for independent timing)
        itemsIndexed(specialTasks) { index, task ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(AppDuration.Normal, delayMillis = (dailyTasks.size + index) * AppDuration.Stagger)) +
                        slideInVertically(tween(AppDuration.Normal, delayMillis = (dailyTasks.size + index) * AppDuration.Stagger)) { it / 3 }
            ) {
                TaskCard(task = task, onComplete = { viewModel.completeTask(task.id) })
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TaskCard(
    task: TaskEntity,
    onComplete: () -> Unit
) {
    CyberCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !task.isCompleted) { onComplete() },
        strokeColor = if (task.isCompleted) CyberGreen else Stroke,
        glowColor = if (task.isCompleted) accentGreen else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (task.isCompleted) "✓" else "○",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (task.isCompleted) CyberGreen else CyberGray
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(taskTitleResId(task.id)),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (task.isCompleted) CyberGray else CyberWhite
                    )
                    if (task.isCompleted) {
                        Text(
                            text = stringResource(R.string.task_completed),
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberGreen
                        )
                    }
                }
            }
            Text(
                text = "+${task.reward}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = CyberYellow
            )
        }
    }
}

private fun taskTitleResId(taskId: String): Int = when (taskId) {
    "invite_friend" -> R.string.task_invite_friend
    "share"         -> R.string.task_share_social
    "watch_story"   -> R.string.task_watch_stories
    "subscribe"     -> R.string.task_subscribe
    else            -> R.string.task_invite_friend
}
