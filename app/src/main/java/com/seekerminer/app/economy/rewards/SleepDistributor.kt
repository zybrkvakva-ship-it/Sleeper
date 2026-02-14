package com.seekerminer.app.economy.rewards

import com.seekerminer.app.utils.DevLog

/**
 * Распределитель токенов SLEEP за ночь
 * 
 * Токены SLEEP распределяются пропорционально Night Points каждого пользователя.
 * 
 * Формула: SLEEP_user = poolNight × (NP_user / NP_total)
 * 
 * Это обеспечивает:
 * - Справедливое распределение (больше NP → больше токенов)
 * - Фиксированный supply (сумма наград = пул на ночь)
 * - Защиту от инфляции (токены не печатаются из воздуха)
 */
object SleepDistributor {
    
    private const val TAG = "SleepDistributor"
    
    /**
     * Вычислить награду SLEEP для пользователя за ночь
     * 
     * Токены распределяются пропорционально доле NP пользователя
     * от общего количества NP по сети за ночь.
     * 
     * @param userNp Night Points пользователя
     * @param totalNp сумма Night Points всех майнеров за ночь
     * @param poolNight пул токенов на ночь
     * @return количество токенов SLEEP для пользователя
     * 
     * @example
     * ```
     * // Пользователь намайнил 1000 NP
     * // Всего по сети 10_000 NP
     * // Пул на ночь 100_000 SLEEP
     * calcSleepRewardForUser(1000.0, 10_000.0, 100_000)
     * // -> 10_000 SLEEP (10% от пула)
     * ```
     */
    fun calcSleepRewardForUser(
        userNp: Double,
        totalNp: Double,
        poolNight: Long
    ): Long {
        // Защита от деления на 0
        if (totalNp <= 0.0 || userNp <= 0.0) {
            DevLog.w(TAG, "Invalid NP values: user=$userNp, total=$totalNp")
            return 0L
        }
        
        // Проверка корректности данных
        if (userNp > totalNp) {
            DevLog.e(TAG, "User NP ($userNp) exceeds total NP ($totalNp). " +
                       "This should never happen!")
            return 0L
        }
        
        // Вычисляем долю пользователя
        val share = userNp / totalNp
        
        // Вычисляем награду
        val reward = (poolNight.toDouble() * share).toLong()
        
        // Гарантируем неотрицательное значение
        val finalReward = reward.coerceAtLeast(0L)
        
        DevLog.d(TAG, "SLEEP reward: $finalReward tokens " +
                   "(${String.format("%.2f", userNp)} NP, " +
                   "${String.format("%.4f", share * 100)}% of pool)")
        
        return finalReward
    }
    
    /**
     * Вычислить награды для нескольких пользователей
     * 
     * Полезно для batch обработки на бэкенде.
     * 
     * @param usersNp map[userId -> NP]
     * @param poolNight пул на ночь
     * @return map[userId -> SLEEP reward]
     */
    fun calcSleepRewardsForUsers(
        usersNp: Map<String, Double>,
        poolNight: Long
    ): Map<String, Long> {
        // Вычисляем общий NP
        val totalNp = usersNp.values.sum()
        
        if (totalNp <= 0.0) {
            DevLog.w(TAG, "Total NP is zero, no rewards to distribute")
            return usersNp.keys.associateWith { 0L }
        }
        
        // Вычисляем награды для каждого
        val rewards = usersNp.mapValues { (userId, userNp) ->
            calcSleepRewardForUser(userNp, totalNp, poolNight)
        }
        
        // Валидация: сумма наград не должна превышать пул
        val totalRewards = rewards.values.sum()
        if (totalRewards > poolNight) {
            DevLog.e(TAG, "Total rewards ($totalRewards) exceed pool ($poolNight)! " +
                       "This is a bug in calculation.")
        }
        
        DevLog.i(TAG, "Distributed $totalRewards SLEEP to ${rewards.size} users " +
                   "(pool: $poolNight, remaining: ${poolNight - totalRewards})")
        
        return rewards
    }
    
    /**
     * Получить долю пользователя от пула
     * 
     * @param userNp Night Points пользователя
     * @param totalNp общий NP
     * @return доля (0.0-1.0)
     */
    fun calcUserShare(userNp: Double, totalNp: Double): Double {
        if (totalNp <= 0.0 || userNp <= 0.0) return 0.0
        return (userNp / totalNp).coerceIn(0.0, 1.0)
    }
    
    /**
     * Вычислить эффективный APY для пользователя
     * 
     * Полезно для показа пользователю потенциального дохода.
     * 
     * @param dailyReward средняя награда за ночь (SLEEP)
     * @param sleepPriceUsd цена токена SLEEP в USD
     * @param investmentUsd инвестиции пользователя (например, цена NFT)
     * @return годовой процент доходности
     */
    fun calcEffectiveApy(
        dailyReward: Double,
        sleepPriceUsd: Double,
        investmentUsd: Double
    ): Double {
        if (investmentUsd <= 0.0) return 0.0
        
        val dailyIncome = dailyReward * sleepPriceUsd
        val dailyReturn = dailyIncome / investmentUsd
        val yearlyReturn = dailyReturn * 365.0
        
        return yearlyReturn * 100.0  // в процентах
    }
}

/**
 * Результат распределения для ночи
 * 
 * Используется на бэкенде для аудита и статистики.
 */
data class NightDistribution(
    val poolNight: Long,
    val totalNp: Double,
    val usersCount: Int,
    val totalDistributed: Long,
    val remainingPool: Long,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Процент использованного пула
     */
    val poolUtilization: Double
        get() = if (poolNight > 0) {
            (totalDistributed.toDouble() / poolNight) * 100.0
        } else 0.0
    
    override fun toString(): String {
        return """
            |Night Distribution:
            |  Pool: $poolNight SLEEP
            |  Total NP: ${String.format("%.2f", totalNp)}
            |  Users: $usersCount
            |  Distributed: $totalDistributed SLEEP (${String.format("%.1f", poolUtilization)}%)
            |  Remaining: $remainingPool SLEEP
        """.trimMargin()
    }
}
