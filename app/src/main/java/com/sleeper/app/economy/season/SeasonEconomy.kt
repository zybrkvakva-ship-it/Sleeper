package com.sleeper.app.economy.season

import com.sleeper.app.utils.DevLog
import com.sleeper.app.economy.models.EconomyConstants.MAX_DEVICES
import com.sleeper.app.economy.models.EconomyConstants.SEASON_POOL
import kotlin.math.max

/**
 * Логика сезонов Sleeper
 * 
 * Сезон сжимается по мере роста количества активных устройств:
 * - 0-1k устройств → 16 недель
 * - 1k-5k → 15 недель
 * - 5k-10k → 14 недель
 * - 10k-25k → 12 недель
 * - 25k-50k → 10 недель
 * - 50k+ → 8 недель (минимум)
 * 
 * Это создаёт дефицит токенов и стимулирует ранних пользователей.
 */
object SeasonEconomy {
    
    private const val TAG = "SeasonEconomy"
    
    /**
     * Вычислить длительность сезона в неделях на основе активных устройств
     * 
     * Чем больше устройств присоединяется, тем короче становится сезон,
     * и тем больше токенов распределяется за каждую ночь.
     * 
     * @param nActive количество уникальных устройств, хоть раз майнивших в сезоне
     * @return длительность сезона в неделях (8-16)
     * 
     * @example
     * ```
     * currentWeeks(500)     // 16 недель (ранний сезон)
     * currentWeeks(3_000)   // 15 недель
     * currentWeeks(100_000) // 8 недель (поздний сезон)
     * ```
     */
    fun currentWeeks(nActive: Int): Int {
        require(nActive >= 0) { "Active devices cannot be negative: $nActive" }
        
        val weeks = when {
            nActive <= 1_000 -> 16
            nActive <= 5_000 -> 15
            nActive <= 10_000 -> 14
            nActive <= 25_000 -> 12
            nActive <= 50_000 -> 10
            else -> 8  // 50k+ устройств (минимум)
        }
        
        DevLog.d(TAG, "Season duration: $weeks weeks for $nActive active devices")
        return weeks
    }
    
    /**
     * Вычислить пул токенов SLEEP на одну ночь
     * 
     * Формула: seasonPool / (currentWeeks * 7)
     * 
     * По мере роста nActive сезон становится короче, и пул на ночь увеличивается.
     * Это создаёт higher APY для поздних adopters, но меньше общих токенов.
     * 
     * @param nActive количество активных устройств
     * @param seasonPool общий пул токенов на сезон (по умолчанию 5M)
     * @return пул токенов на одну ночь
     * 
     * @example
     * ```
     * poolPerNight(500)     // ~44,642 SLEEP/ночь (16 недель = 112 ночей)
     * poolPerNight(100_000) // ~89,285 SLEEP/ночь (8 недель = 56 ночей)
     * ```
     */
    fun poolPerNight(nActive: Int, seasonPool: Long = SEASON_POOL): Long {
        require(nActive >= 0) { "Active devices cannot be negative: $nActive" }
        require(seasonPool > 0) { "Season pool must be positive: $seasonPool" }
        
        val weeks = currentWeeks(nActive)
        val nightsTotal = weeks * 7
        val pool = seasonPool / nightsTotal
        
        DevLog.d(TAG, "Pool per night: $pool SLEEP ($weeks weeks, $nightsTotal nights)")
        return pool
    }
    
    /**
     * Вычислить time-decay мультипликатор сложности по неделям
     * 
     * Сложность линейно снижается от 1.0 (Week 1) до 0.2 (последняя неделя).
     * Это стимулирует ранний майнинг и создаёт дефицит токенов в поздние недели.
     * 
     * @param weekIndex текущая неделя сезона (1-based, 1 = первая неделя)
     * @param maxWeeks максимальная длительность сезона в неделях
     * @return мультипликатор сложности (0.2-1.0)
     * 
     * @example
     * ```
     * difficultyByWeek(1, 16)  // 1.0 (первая неделя)
     * difficultyByWeek(8, 16)  // ~0.63 (середина)
     * difficultyByWeek(16, 16) // 0.2 (последняя неделя)
     * ```
     */
    fun difficultyByWeek(weekIndex: Int, maxWeeks: Int): Double {
        require(weekIndex >= 1) { "Week index must be >= 1: $weekIndex" }
        require(maxWeeks >= 1) { "Max weeks must be >= 1: $maxWeeks" }
        
        // Если weekIndex > maxWeeks, используем maxWeeks (последняя неделя)
        val actualWeek = weekIndex.coerceAtMost(maxWeeks)
        
        // Линейное снижение от 1.0 до 0.2
        val progress = (actualWeek - 1).toDouble() / max(maxWeeks - 1, 1)
        val difficulty = 1.0 - (progress * 0.8)  // 0.8 = (1.0 - 0.2)
        
        // Floor на 0.2
        val result = difficulty.coerceIn(0.2, 1.0)
        
        DevLog.d(TAG, "Difficulty: ${String.format("%.2f", result)} " +
                   "(Week $actualWeek of $maxWeeks)")
        return result
    }
    
    /**
     * Получить информацию о текущем состоянии сезона
     * 
     * @param nActive количество активных устройств
     * @param weekIndex текущая неделя сезона
     * @return SeasonInfo с деталями сезона
     */
    fun getSeasonInfo(nActive: Int, weekIndex: Int): SeasonInfo {
        val weeks = currentWeeks(nActive)
        val poolNight = poolPerNight(nActive)
        val difficulty = difficultyByWeek(weekIndex, weeks)
        val progress = (weekIndex.toDouble() / weeks).coerceAtMost(1.0)
        
        return SeasonInfo(
            activeDevices = nActive,
            totalWeeks = weeks,
            currentWeek = weekIndex.coerceAtMost(weeks),
            poolPerNight = poolNight,
            difficulty = difficulty,
            progress = progress
        )
    }
}

/**
 * Информация о текущем состоянии сезона
 */
data class SeasonInfo(
    val activeDevices: Int,
    val totalWeeks: Int,
    val currentWeek: Int,
    val poolPerNight: Long,
    val difficulty: Double,
    val progress: Double  // 0.0-1.0
) {
    /**
     * Осталось недель до конца сезона
     */
    val weeksRemaining: Int
        get() = (totalWeeks - currentWeek).coerceAtLeast(0)
    
    /**
     * Осталось ночей до конца сезона
     */
    val nightsRemaining: Int
        get() = weeksRemaining * 7
    
    /**
     * Форматированный вывод
     */
    override fun toString(): String {
        return """
            |Season Info:
            |  Active Devices: $activeDevices
            |  Duration: $totalWeeks weeks ($currentWeek/$totalWeeks completed)
            |  Progress: ${(progress * 100).toInt()}%
            |  Pool per Night: $poolPerNight SLEEP
            |  Current Difficulty: ${String.format("%.2f", difficulty)}x
            |  Remaining: $weeksRemaining weeks ($nightsRemaining nights)
        """.trimMargin()
    }
}
