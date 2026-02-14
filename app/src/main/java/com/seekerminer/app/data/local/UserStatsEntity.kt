package com.seekerminer.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_stats")
data class UserStatsEntity(
    @PrimaryKey val id: Int = 1,
    val energyCurrent: Int = 25_200, // полный бак ≈ 7 ч майнинга при 1/сек
    val energyMax: Int = 25_200,
    val pointsBalance: Long = 0,
    val storageMB: Int = 100,
    val storageMultiplier: Double = 1.0,
    val uptimeMinutes: Long = 0,
    val totalBlocksMined: Int = 0,
    val currentBlock: Int = 1247,
    val deviceFingerprint: String = "",
    val lastHumanCheck: Long = 0,
    val humanChecksPassed: Int = 0,
    val humanChecksFailed: Int = 0,
    val miningStartTime: Long = 0,
    val isMining: Boolean = false,
    val lastEnergyRestore: Long = System.currentTimeMillis(),
    val stakedSkrRaw: Long = 0,
    val stakedSkrHuman: Double = 0.0,
    // Daily / social микробуст: накопленный бонус (1.0 + bonus, кап задаётся в логике)
    val dailySocialBonusPercent: Double = 0.0,
    // Метка последнего «дня», для которого считался бонус (UTC-день по времени устройства)
    val lastDailyResetAt: Long = 0L,
    // Буст за SKR: id из SkrBoostCatalog, действует до timestamp
    val activeSkrBoostId: String? = null,
    val activeSkrBoostEndsAt: Long = 0L,
    // Genesis NFT: постоянный множитель к награде после платного минта (250–300 SKR)
    val hasGenesisNft: Boolean = false,
    val genesisNftMultiplier: Double = 1.0
)
