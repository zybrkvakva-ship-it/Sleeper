package com.seekerminer.app.data.local.dao

import androidx.room.*
import com.seekerminer.app.data.local.UpgradeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UpgradeDao {
    @Query("SELECT * FROM upgrades")
    fun getAllUpgradesFlow(): Flow<List<UpgradeEntity>>
    
    @Query("SELECT * FROM upgrades WHERE id = :id")
    suspend fun getUpgrade(id: String): UpgradeEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(upgrades: List<UpgradeEntity>)
    
    @Query("UPDATE upgrades SET isPurchased = 1 WHERE id = :id")
    suspend fun markPurchased(id: String)
}
