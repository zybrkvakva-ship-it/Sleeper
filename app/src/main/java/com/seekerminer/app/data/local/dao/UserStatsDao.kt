package com.seekerminer.app.data.local.dao

import androidx.room.*
import com.seekerminer.app.data.local.UserStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserStatsDao {
    @Query("SELECT * FROM user_stats WHERE id = 1")
    fun getUserStatsFlow(): Flow<UserStatsEntity?>
    
    @Query("SELECT * FROM user_stats WHERE id = 1")
    suspend fun getUserStats(): UserStatsEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stats: UserStatsEntity)
    
    @Update
    suspend fun update(stats: UserStatsEntity)
    
    @Query("UPDATE user_stats SET energyCurrent = :energy WHERE id = 1")
    suspend fun updateEnergy(energy: Int)
    
    @Query("UPDATE user_stats SET pointsBalance = pointsBalance + :points WHERE id = 1")
    suspend fun addPoints(points: Long)
    
    @Query("UPDATE user_stats SET isMining = :isMining, miningStartTime = :startTime WHERE id = 1")
    suspend fun setMiningState(isMining: Boolean, startTime: Long)
    
    @Query("UPDATE user_stats SET lastHumanCheck = :timestamp, humanChecksPassed = humanChecksPassed + 1 WHERE id = 1")
    suspend fun recordHumanCheckPassed(timestamp: Long)
    
    @Query("UPDATE user_stats SET humanChecksFailed = humanChecksFailed + 1 WHERE id = 1")
    suspend fun recordHumanCheckFailed()
}
