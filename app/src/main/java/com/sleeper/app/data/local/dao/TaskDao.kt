package com.sleeper.app.data.local.dao

import androidx.room.*
import com.sleeper.app.data.local.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY type ASC, id ASC")
    fun getAllTasksFlow(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY type ASC, id ASC")
    suspend fun getTasks(): List<TaskEntity>

    /** Full upsert — replaces existing row if id matches (used on server sync). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Query("UPDATE tasks SET isCompleted = 1, completedAt = :timestamp WHERE id = :id")
    suspend fun markCompleted(id: String, timestamp: Long)

    /** Reset completion flag on all DAILY tasks (called at day rollover). */
    @Query("UPDATE tasks SET isCompleted = 0, completedAt = 0 WHERE type = 'DAILY'")
    suspend fun resetDailyCompletions()

    /** Legacy: delete DAILY rows (kept for migration compatibility). */
    @Query("DELETE FROM tasks WHERE type = 'DAILY'")
    suspend fun resetDailyTasks()
}
