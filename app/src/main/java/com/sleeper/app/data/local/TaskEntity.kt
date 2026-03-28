package com.sleeper.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String = "",
    val reward: Int,
    val bonusPercent: Double = 0.0,
    val type: TaskType,
    val actionType: TaskActionType = TaskActionType.CHECKIN,
    val actionUrl: String? = null,
    val isCompleted: Boolean = false,
    val completedAt: Long = 0,
    /** Server-authoritative: whether the user can complete this task right now. */
    val canComplete: Boolean = true
)

enum class TaskType {
    DAILY,
    SPECIAL
}

enum class TaskActionType {
    /** Just tap — daily check-in, no external action needed. */
    CHECKIN,
    /** Launch Android share sheet. */
    SHARE,
    /** Open story URL in browser/Telegram. */
    STORY,
    /** Open arbitrary external URL. */
    OPEN_URL,
    /** Auto-granted by server when streak condition is met. */
    AUTO,
    /** Requires active referral — shows referral link. */
    REFERRAL
}
