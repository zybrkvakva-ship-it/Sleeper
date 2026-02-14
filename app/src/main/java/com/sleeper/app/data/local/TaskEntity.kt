package com.sleeper.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val reward: Int,
    val type: TaskType,
    val isCompleted: Boolean = false,
    val completedAt: Long = 0
)

enum class TaskType {
    DAILY,
    SPECIAL
}
