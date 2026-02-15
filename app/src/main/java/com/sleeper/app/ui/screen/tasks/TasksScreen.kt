package com.sleeper.app.ui.screen.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sleeper.app.R
import com.sleeper.app.data.local.TaskEntity
import com.sleeper.app.data.local.TaskType
import com.sleeper.app.ui.components.CyberCard
import com.sleeper.app.ui.theme.*

@Composable
fun TasksScreen(
    viewModel: TasksViewModel = viewModel()
) {
    val tasks by viewModel.tasks.collectAsState()
    
    val dailyTasks = tasks.filter { it.type == TaskType.DAILY }
    val specialTasks = tasks.filter { it.type == TaskType.SPECIAL }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgMain)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.tasks_daily_energy).uppercase(),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = CyberWhite
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        dailyTasks.forEach { task ->
            TaskCard(
                task = task,
                onComplete = { viewModel.completeTask(task.id) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = stringResource(R.string.tasks_special_energy).uppercase(),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = CyberYellow
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        specialTasks.forEach { task ->
            TaskCard(
                task = task,
                onComplete = { viewModel.completeTask(task.id) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
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
            .then(Modifier.clickable(enabled = !task.isCompleted) { onComplete() }),
        strokeColor = if (task.isCompleted) CyberGreen else Stroke,
        cornerRadius = 12.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (task.isCompleted) "✓" else "○",
                    fontSize = 24.sp,
                    color = if (task.isCompleted) CyberGreen else CyberGray
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(taskTitleResId(task.id)),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (task.isCompleted) CyberGray else CyberWhite
                    )
                    if (task.isCompleted) {
                        Text(
                            text = stringResource(R.string.task_completed),
                            fontSize = 12.sp,
                            color = CyberGreen
                        )
                    }
                }
            }
            
            Text(
                text = "+${task.reward}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = CyberYellow
            )
        }
    }
}

private fun taskTitleResId(taskId: String): Int = when (taskId) {
    "invite_friend" -> R.string.task_invite_friend
    "share" -> R.string.task_share_social
    "watch_story" -> R.string.task_watch_stories
    "subscribe" -> R.string.task_subscribe
    else -> R.string.task_invite_friend
}
