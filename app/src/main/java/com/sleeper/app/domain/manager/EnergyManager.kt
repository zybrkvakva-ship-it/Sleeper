package com.sleeper.app.domain.manager

import com.sleeper.app.utils.DevLog
import com.sleeper.app.data.local.SkrBoostCatalog
import com.sleeper.app.data.local.dao.UserStatsDao
import kotlinx.coroutines.flow.first
import kotlin.math.min

class EnergyManager(private val userStatsDao: UserStatsDao) {
    
    companion object {
        private const val TAG = "EnergyManager"
        private const val ENERGY_DRAIN_PER_SECOND = 1 // 1 энергия/сек при майнинге
        private const val ENERGY_RESTORE_PER_MINUTE = 25 // 25 энергии/мин при простое (~17h до полного бака)
        /** Полный бак = 7 часов майнинга (25_200 сек × 1/сек). */
        const val ENERGY_MAX_FULL_TANK = 25_200
        private const val BASE_POINTS_PER_SECOND = 0.2 // базовая награда
        // Стейк SKR — множитель к награде (ступени)
        private const val STAKE_TIER_1_SKR = 1000.0
        private const val STAKE_TIER_2_SKR = 10000.0
        private const val STAKE_MULT_1 = 1.0
        private const val STAKE_MULT_2 = 1.2
        private const val STAKE_MULT_3 = 1.5
        // Дейлики / социалки — микробуст к награде
        // Итоговый множитель: 1.0 + dailySocialBonusPercent, где bonus ∈ [0, DAILY_SOCIAL_MAX_BONUS]
        private const val DAILY_SOCIAL_CAP = 1.15          // максимальный итоговый множитель
        private const val DAILY_SOCIAL_MAX_BONUS = DAILY_SOCIAL_CAP - 1.0 // 0.15
    }
    
    /** Множитель от стейка SKR: 0 = 1.0, 1k–10k = 1.2, 10k+ = 1.5 */
    private fun stakeMultiplier(stakedSkrHuman: Double): Double {
        if (stakedSkrHuman <= 0) return 1.0
        return when {
            stakedSkrHuman >= STAKE_TIER_2_SKR -> STAKE_MULT_3
            stakedSkrHuman >= STAKE_TIER_1_SKR -> STAKE_MULT_2
            else -> STAKE_MULT_1
        }
    }
    
    /** Публичный расчёт множителя для отображения в UI (тот же порядок ступеней). */
    fun getStakeMultiplierForDisplay(stakedSkrHuman: Double): Double = stakeMultiplier(stakedSkrHuman)

    /** Множитель от дейликов / социалок: 1.0 по умолчанию, максимум DAILY_SOCIAL_CAP. */
    private fun dailySocialMultiplier(dailyBonusPercent: Double): Double {
        if (dailyBonusPercent <= 0.0) return 1.0
        val clampedBonus = min(dailyBonusPercent, DAILY_SOCIAL_MAX_BONUS)
        return 1.0 + clampedBonus
    }

    /** Множитель от купленного за SKR буста: если активен — из каталога, иначе 1.0. */
    private fun paidBoostMultiplier(activeSkrBoostId: String?, activeSkrBoostEndsAt: Long): Double {
        if (activeSkrBoostId == null || activeSkrBoostEndsAt <= 0L) return 1.0
        if (System.currentTimeMillis() >= activeSkrBoostEndsAt) return 1.0
        return SkrBoostCatalog.get(activeSkrBoostId)?.multiplier ?: 1.0
    }

    /** Для UI: текущий множитель платного буста. */
    fun getPaidBoostMultiplierForDisplay(activeSkrBoostId: String?, activeSkrBoostEndsAt: Long): Double =
        paidBoostMultiplier(activeSkrBoostId, activeSkrBoostEndsAt)
    
    /**
     * Проверяет, достаточно ли энергии для майнинга
     */
    suspend fun hasEnoughEnergy(): Boolean {
        val stats = userStatsDao.getUserStats() ?: return false
        return stats.energyCurrent > 0
    }
    
    /**
     * Тратит энергию во время майнинга
     * @return true если энергия была списана, false если энергия закончилась
     */
    suspend fun drainEnergy(seconds: Int): Boolean {
        val stats = userStatsDao.getUserStats() ?: return false
        val energyToDrain = ENERGY_DRAIN_PER_SECOND * seconds

        if (stats.energyCurrent < energyToDrain) {
            userStatsDao.updateEnergy(0)
            DevLog.d(TAG, "drainEnergy DEPLETED current=${stats.energyCurrent} requested=$energyToDrain -> 0")
            return false
        }
        
        val newEnergy = (stats.energyCurrent - energyToDrain).coerceAtLeast(0)
        userStatsDao.updateEnergy(newEnergy)
        DevLog.d(TAG, "Energy drained: $energyToDrain, remaining: $newEnergy")
        return true
    }
    
    /**
     * Восстанавливает энергию при простое
     */
    suspend fun restoreEnergy() {
        val stats = userStatsDao.getUserStats() ?: return
        
        if (stats.isMining || stats.energyCurrent >= stats.energyMax) return
        
        val now = System.currentTimeMillis()
        val minutesPassed = (now - stats.lastEnergyRestore) / 60_000
        
        if (minutesPassed >= 1) {
            val energyToRestore = (ENERGY_RESTORE_PER_MINUTE * minutesPassed).toInt()
            val newEnergy = min(stats.energyCurrent + energyToRestore, stats.energyMax)
            
            userStatsDao.update(
                stats.copy(
                    energyCurrent = newEnergy,
                    lastEnergyRestore = now
                )
            )
            
            DevLog.d(TAG, "Energy restored: $energyToRestore, new: $newEnergy")
        }
    }
    
    /**
     * Вычисляет награду за майнинг
     * НАГРАДА = BASE × STORAGE × HUMAN_CHECK
     */
    suspend fun calculateReward(
        uptimeMinutes: Long,
        storageMultiplier: Double,
        humanCheckMultiplier: Double
    ): Long {
        val baseReward = BASE_POINTS_PER_SECOND * uptimeMinutes * 60
        val finalReward = baseReward * storageMultiplier * humanCheckMultiplier
        
        DevLog.d(TAG, "Reward calculated: base=$baseReward, storage=$storageMultiplier, human=$humanCheckMultiplier, final=$finalReward")
        return finalReward.toLong()
    }
    
    /**
     * Начисляет поинты и обновляет статистику
     */
    suspend fun awardPoints(points: Long) {
        if (points > 0) {
            userStatsDao.addPoints(points)
            DevLog.d(TAG, "Points awarded: $points")
        }
    }
    
    /**
     * Возвращает текущую скорость фарма поинтов в секунду.
     * Формула: BASE × storage × human × stake × dailySocial × genesisNft × paidBoost.
     */
    suspend fun getCurrentPointsPerSecond(): Double {
        val stats = userStatsDao.getUserStatsFlow().first() ?: return 0.0
        val humanMultiplier = calculateHumanCheckMultiplier(stats.humanChecksPassed, stats.humanChecksFailed)
        val stakeMult = stakeMultiplier(stats.stakedSkrHuman)
        val socialMult = dailySocialMultiplier(stats.dailySocialBonusPercent)
        val genesisMult = if (stats.hasGenesisNft && stats.genesisNftMultiplier > 0) stats.genesisNftMultiplier else 1.0
        val paidMult = paidBoostMultiplier(stats.activeSkrBoostId, stats.activeSkrBoostEndsAt)
        return BASE_POINTS_PER_SECOND * stats.storageMultiplier * humanMultiplier * stakeMult * socialMult * genesisMult * paidMult
    }
    
    /**
     * Вычисляет множитель за human checks
     */
    private fun calculateHumanCheckMultiplier(passed: Int, failed: Int): Double {
        val total = passed + failed
        if (total == 0) return 1.0
        
        val passRate = passed.toDouble() / total
        return when {
            passRate >= 0.8 -> 1.0  // 80%+ успешных проверок = 100%
            passRate >= 0.5 -> 0.7  // 50-80% = 70%
            else -> 0.3             // <50% = 30%
        }
    }
}
