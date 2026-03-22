package com.sleeper.app.economy.rewards

import com.sleeper.app.utils.DevLog
import com.sleeper.app.economy.models.EconomyConstants.BASE_RATE_PER_MINUTE
import com.sleeper.app.economy.models.EconomyConstants.MAX_SLEEP_MINUTES
import com.sleeper.app.economy.season.SeasonEconomy

/**
 * Калькулятор базовых Night Points (до применения бустов)
 *
 * Формула: NP_base = T × R × H × D
 *
 * Где:
 * - T = минуты сна (0-480)
 * - R = базовая ставка (1 NP/мин)
 * - H = мультипликатор Proof-of-Presence (человечность)
 * - D = time-decay сложности
 */
object BaseRewardCalculator {

    private const val TAG = "BaseRewardCalc"

    /**
     * Контекст для расчёта базовых поинтов
     *
     * @param minutesSlept минуты сна (0-480)
     * @param humanFactor коэффициент "человечности" (0.3, 0.7, 1.0)
     * @param weekIndex текущая неделя сезона
     * @param maxWeeks длительность сезона в неделях
     */
    data class BaseContext(
        val minutesSlept: Int,
        val humanFactor: Double,
        val weekIndex: Int,
        val maxWeeks: Int
    ) {
        init {
            require(minutesSlept in 0..MAX_SLEEP_MINUTES) {
                "Minutes slept must be in 0..$MAX_SLEEP_MINUTES"
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
     * Вычислить базовые Night Points за ночь
     *
     * Формула: NP = T × R × H × D
     *
     * @param ctx контекст ночи
     * @return базовые Night Points (без бустов)
     */
    fun calcBaseNp(ctx: BaseContext): Double {
        val T = ctx.minutesSlept.coerceIn(0, MAX_SLEEP_MINUTES).toDouble()
        val R = BASE_RATE_PER_MINUTE
        val H = ctx.humanFactor.coerceIn(0.0, 1.0)
        val D = SeasonEconomy.difficultyByWeek(ctx.weekIndex, ctx.maxWeeks)

        val baseNp = T * R * H * D

        DevLog.d(TAG, "Base NP: ${String.format("%.2f", baseNp)} " +
                "(T=$T, R=$R, H=${String.format("%.1f", H)}, D=${String.format("%.2f", D)})")

        return baseNp
    }

    /**
     * Создать BaseContext из упрощённых параметров
     */
    fun createContext(
        minutesSlept: Int,
        humanFactor: Double,
        weekIndex: Int,
        activeDevices: Int
    ): BaseContext {
        val maxWeeks = SeasonEconomy.currentWeeks(activeDevices)
        return BaseContext(
            minutesSlept = minutesSlept,
            humanFactor = humanFactor,
            weekIndex = weekIndex,
            maxWeeks = maxWeeks
        )
    }
}
