package com.sleeper.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Сессия майнинга, ожидающая отправки на бэкенд.
 * При остановке майнинга запись добавляется в очередь; при появлении сети отправляется POST /mining/session.
 */
@Entity(tableName = "pending_sessions")
data class PendingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val walletAddress: String,
    val authToken: String?,
    val skrUsername: String?,
    val uptimeMinutes: Long,
    val durationSeconds: Long,
    val storageMB: Int,
    val storageMultiplier: Double,
    val stakedSkrHuman: Double,
    val stakeMultiplier: Double,
    val humanChecksPassed: Int,
    val humanChecksFailed: Int,
    val humanMultiplier: Double,
    val dailySocialBonusPercent: Double,
    val paidBoostMultiplier: Double,
    val dailySocialMultiplier: Double,
    val pointsPerSecond: Double,
    val pointsBalance: Long,
    val sessionStartedAt: Long,
    val sessionEndedAt: Long,
    val deviceFingerprint: String?,
    val hasGenesisNft: Boolean = false,
    val genesisNftMultiplier: Double = 1.0,
    val activeSkrBoostId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
