package com.seekerminer.app.data.local

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
    val skrUsername: String?,
    val uptimeMinutes: Long,
    val storageMB: Int,
    val storageMultiplier: Double,
    val stakeMultiplier: Double,
    val paidBoostMultiplier: Double,
    val dailySocialMultiplier: Double,
    val pointsBalance: Long,
    val sessionStartedAt: Long,
    val sessionEndedAt: Long,
    val deviceFingerprint: String?,
    val genesisNftMultiplier: Double = 1.0,
    val activeSkrBoostId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
