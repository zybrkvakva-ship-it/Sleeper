package com.sleeper.app.data.local.dao

import androidx.room.*
import com.sleeper.app.data.local.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks")
    fun getAllTasksFlow(): Flow<List<TaskEntity>>
    
    @Query("SELECT * FROM tasks")
    suspend fun getTasks(): List<TaskEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<TaskEntity>)
    
    @Query("UPDATE tasks SET isCompleted = 1, completedAt = :timestamp WHERE id = :id")
    suspend fun markCompleted(id: String, timestamp: Long)
    
    @Query("DELETE FROM tasks WHERE type = 'DAILY'")
    suspend fun resetDailyTasks()
}
