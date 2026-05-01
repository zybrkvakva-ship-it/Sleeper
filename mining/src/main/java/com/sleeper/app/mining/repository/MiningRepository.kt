package com.sleeper.app.mining.repository

import com.sleeper.app.data.local.dao.UserStats

interface MiningRepository {
    suspend fun insert(stats: UserStats): Long
    suspend fun update(stats: UserStats)
    suspend fun getByUid(uid: Long): UserStats?
    suspend fun getAll(): List<UserStats>
    suspend fun clearAll()
}

/**
 * Simple concrete implementation that delegates to [UserStatsDao].
 * In the future this class can be provided by Hilt or another DI framework.
 */
class MiningRepository(private val dao: UserStatsDao) : MiningRepository {
    override suspend fun insert(stats: UserStats): Long = dao.insert(stats)
    override suspend fun update(stats: UserStats) = dao.update(stats)
    override suspend fun getByUid(uid: Long): UserStats? = dao.getByUid(uid)
    override suspend fun getAll(): List<UserStats> = dao.getAll()
    override suspend fun clearAll() = dao.clearAll()
}