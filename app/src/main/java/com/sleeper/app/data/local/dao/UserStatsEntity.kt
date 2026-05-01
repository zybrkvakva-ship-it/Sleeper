package com.sleeper.app.data.local.dao

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity that stores all per‑user statistics.
 * A single row (id = 1) is enough because the app has exactly one user
 * profile stored on the device.
 */
@Entity(tableName = "user_stats")
data class UserStatsEntity(
    @PrimaryKey val id: Long = 1,

    // Basic energy & reward fields
    val energyCurrent: Int = 0,
    val energyMax: Int = 100,
    val pointsBalance: Long = 0L,

        // Mining‑related flags
        val isMining: Boolean = false,
        val miningStartTime: Long = 0L,

        // Human‑check counters (used for the “human verification” multiplier)
        val humanChecksPassed: Int = 0,
        val humanChecksFailed: Int = 0,
        val lastHumanCheck: Long = 0L,

        // Energy‑restore timing
        val lastEnergyRestore: Long = 0L,

        // Stake‑related data
        val stakedSkrHuman: Double = 0.0,

        // Daily‑social bonus (percentage, e.g. 0.12 = 12 %)
        val dailySocialBonusPercent: Double = 0.0,

        // Active paid‑boost metadata
        val activeSkrBoostId: String? = null,
        val activeSkrBoostEndsAt: Long = 0L,

        // Genesis‑NFT multiplier (used by some future feature)
        val genesisNftMultiplier: Double = 0.0,
        val hasGenesisNft: Boolean = false
)