package com.sleeper.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sleeper.app.data.local.PendingSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: PendingSessionEntity): Long

    @Query("SELECT * FROM pending_sessions ORDER BY createdAt ASC")
    fun getAllPendingFlow(): Flow<List<PendingSessionEntity>>

    @Query("SELECT * FROM pending_sessions ORDER BY createdAt ASC")
    suspend fun getAllPending(): List<PendingSessionEntity>

    @Query("DELETE FROM pending_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_sessions")
    suspend fun deleteAll()
}
