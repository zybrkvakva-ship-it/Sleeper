package com.seekerminer.app.economy.rewards

import com.seekerminer.app.utils.DevLog
import com.seekerminer.app.economy.models.EconomyConstants.BASE_RATE_PER_MINUTE
import com.seekerminer.app.economy.models.EconomyConstants.MAX_SLEEP_MINUTES
import com.seekerminer.app.economy.models.EconomyConstants.MAX_STORAGE_MB
import com.seekerminer.app.economy.season.SeasonEconomy

/**
 * Калькулятор базовых Night Points (до применения бустов)
 * 
 * Формула: NP_base = T × R × S × H × D
 * 
 * Где:
 * - T = минуты сна (0-480)
 * - R = базовая ставка (1 NP/мин)
 * - S = мультипликатор Proof-of-Storage
 * - H = мультипликатор Proof-of-Presence (человечность)
 * - D = time-decay сложности
 */
object BaseRewardCalculator {
    
    private const val TAG = "BaseRewardCalc"
    
    /**
     * Контекст для расчёта базовых поинтов
     * 
     * @param minutesSlept минуты сна (0-480)
     * @param storageMb выделенное хранилище (0-500 MB)
     * @param humanFactor коэффициент "человечности" (0.3, 0.7, 1.0)
     * @param weekIndex текущая неделя сезона
     * @param maxWeeks длительность сезона в неделях
     */
    data class BaseContext(
        val minutesSlept: Int,
        val storageMb: Int,
        val humanFactor: Double,
        val weekIndex: Int,
        val maxWeeks: Int
    ) {
        init {
            require(minutesSlept in 0..MAX_SLEEP_MINUTES) {
                "Minutes slept must be in 0..$MAX_SLEEP_MINUTES"
            }
            require(storageMb in 0..MAX_STORAGE_MB) {
                "Storage MB must be in 0..$MAX_STORAGE_MB"
            }
            require(humanFactor in 0.0..1.0) {
                "Human factor must be in 0.0..1.0"
            }
            require(weekIndex >= 1) {
                "Week index must be >= 1"
            }
            require(maxWeeks >= 1) {
                "Max weeks must be >= 1"
            }
        }
    }
    
    /**
     * Вычислить мультипликатор Proof-of-Storage
     * 
     * Формула: S = 1 + (storageMB / 100)
     * 
     * Примеры:
     * - 0 MB → ×1.0 (без буста)
     * - 100 MB → ×2.0
     * - 250 MB → ×3.5
     * - 500 MB → ×6.0 (максимум)
     * 
     * @param storageMb выделенное хранилище (0-500 MB)
     * @return мультипликатор storage (1.0-6.0)
     */
    fun calcStorageMultiplier(storageMb: Int): Double {
        val clampedStorage = storageMb.coerceIn(0, MAX_STORAGE_MB)
        val multiplier = 1.0 + (clampedStorage / 100.0)
        
        DevLog.d(TAG, "Storage multiplier: ${String.format("%.1f", multiplier)}x " +
                   "($clampedStorage MB)")
        return multiplier
    }
    
    /**
     * Вычислить базовые Night Points за ночь
     * 
     * Формула: NP = T × R × S × H × D
     * 
     * Где:
     * - T = минуты сна (coerced 0-480)
     * - R = 1.0 NP/минуту
     * - S = storage multiplier (1.0-6.0)
     * - H = human factor (0.3, 0.7, 1.0)
     * - D = difficulty by week (0.2-1.0)
     * 
     * @param ctx контекст ночи
     * @return базовые Night Points (без бустов)
     * 
     * @example
     * ```
     * val ctx = BaseContext(
     *     minutesSlept = 480,    // 8 часов сна
     *     storageMb = 100,       // 100 MB storage
     *     humanFactor = 1.0,     // идеальный сон
     *     weekIndex = 1,         // первая неделя
     *     maxWeeks = 16
     * )
     * calcBaseNp(ctx)  // ~960 NP
     * ```
     */
    fun calcBaseNp(ctx: BaseContext): Double {
        // T - минуты сна
        val T = ctx.minutesSlept.coerceIn(0, MAX_SLEEP_MINUTES).toDouble()
        
        // R - базовая ставка
        val R = BASE_RATE_PER_MINUTE
        
        // S - storage multiplier
        val S = calcStorageMultiplier(ctx.storageMb)
        
        // H - human factor
        val H = ctx.humanFactor.coerceIn(0.0, 1.0)
        
        // D - time-decay difficulty
        val D = SeasonEconomy.difficultyByWeek(ctx.weekIndex, ctx.maxWeeks)
        
        // Финальный расчёт
        val baseNp = T * R * S * H * D
        
        DevLog.d(TAG, "Base NP calculated: ${String.format("%.2f", baseNp)} " +
                   "(T=$T, R=$R, S=${String.format("%.1f", S)}, " +
                   "H=${String.format("%.1f", H)}, D=${String.format("%.2f", D)})")
        
        return baseNp
    }
    
    /**
     * Создать BaseContext из упрощённых параметров
     * 
     * Вспомогательная функция для быстрого создания контекста без ручного
     * подсчёта maxWeeks.
     * 
     * @param minutesSlept минуты сна
     * @param storageMb хранилище
     * @param humanFactor человечность
     * @param weekIndex текущая неделя
     * @param activeDevices активных устройств в сети
     * @return BaseContext
     */
    fun createContext(
        minutesSlept: Int,
        storageMb: Int,
        humanFactor: Double,
        weekIndex: Int,
        activeDevices: Int
    ): BaseContext {
        val maxWeeks = SeasonEconomy.currentWeeks(activeDevices)
        return BaseContext(
            minutesSlept = minutesSlept,
            storageMb = storageMb,
            humanFactor = humanFactor,
            weekIndex = weekIndex,
            maxWeeks = maxWeeks
        )
    }
}
