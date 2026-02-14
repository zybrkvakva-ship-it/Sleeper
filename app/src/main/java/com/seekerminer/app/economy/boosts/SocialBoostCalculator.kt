package com.seekerminer.app.economy.boosts

import com.seekerminer.app.utils.DevLog
import com.seekerminer.app.economy.models.EconomyConstants.MAX_SOCIAL_BOOST

/**
 * Калькулятор социальных бустов (рефералы + daily tasks)
 * 
 * Социальные бусты стимулируют:
 * - Привлечение новых пользователей (рефералы)
 * - Ежедневную активность (daily check-ins, social shares)
 * - Регулярный майнинг (sleep streaks)
 * 
 * Максимальный социальный буст: 40% (0.4)
 */
object SocialBoostCalculator {
    
    private const val TAG = "SocialBoostCalc"
    
    /** Максимальный буст от рефералов: 20% */
    private const val MAX_REFERRAL_BOOST = 0.20
    
    /** Буст за одного реферала: 1% */
    private const val REFERRAL_BOOST_PER_USER = 0.01
    
    /** Рефералов для достижения максимума */
    private const val REFERRALS_FOR_MAX = 20
    
    /**
     * Контекст социальных бустов
     * 
     * @param referralCount количество активных рефералов
     * @param dailyTasksPercent суммарный буст от daily tasks (0.0-0.3)
     */
    data class SocialBoost(
        val referralCount: Int,
        val dailyTasksPercent: Double
    ) {
        init {
            require(referralCount >= 0) {
                "Referral count cannot be negative: $referralCount"
            }
            require(dailyTasksPercent >= 0.0) {
                "Daily tasks percent cannot be negative: $dailyTasksPercent"
            }
        }
    }
    
    /**
     * Вычислить буст от рефералов
     * 
     * Формула: 1% за реферала, максимум 20%
     * 
     * Примеры:
     * - 0 рефералов → 0%
     * - 5 рефералов → 5%
     * - 10 рефералов → 10%
     * - 20+ рефералов → 20% (cap)
     * 
     * @param referralCount количество активных рефералов
     * @return буст от рефералов (0.0-0.2)
     */
    fun calcReferralBoost(referralCount: Int): Double {
        val clampedCount = referralCount.coerceAtLeast(0)
        val boost = (clampedCount * REFERRAL_BOOST_PER_USER).coerceAtMost(MAX_REFERRAL_BOOST)
        
        DevLog.d(TAG, "Referral boost: ${(boost * 100).toInt()}% " +
                   "($clampedCount referrals)")
        return boost
    }
    
    /**
     * Вычислить общий социальный буст
     * 
     * Складывает бусты от рефералов и daily tasks, с ограничением на
     * максимальный социальный буст (40%).
     * 
     * @param boost контекст социальных бустов
     * @param maxPercent максимальный социальный буст (по умолчанию 0.4)
     * @return общий социальный буст (0.0-0.4)
     * 
     * @example
     * ```
     * val boost = SocialBoost(
     *     referralCount = 15,           // +15%
     *     dailyTasksPercent = 0.10      // +10%
     * )
     * calcSocialBoost(boost)  // 0.25 (25%)
     * ```
     */
    fun calcSocialBoost(
        boost: SocialBoost,
        maxPercent: Double = MAX_SOCIAL_BOOST
    ): Double {
        // Буст от рефералов
        val refBoost = calcReferralBoost(boost.referralCount)
        
        // Буст от daily tasks (capped на 30%)
        val taskBoost = boost.dailyTasksPercent.coerceIn(0.0, 0.30)
        
        // Суммарный буст
        val totalBoost = refBoost + taskBoost
        
        // Cap на максимальный социальный буст
        val cappedBoost = totalBoost.coerceAtMost(maxPercent)
        
        DevLog.d(TAG, "Social boost: ${(cappedBoost * 100).toInt()}% " +
                   "(refs: ${(refBoost * 100).toInt()}%, " +
                   "tasks: ${(taskBoost * 100).toInt()}%)")
        
        return cappedBoost
    }
    
    /**
     * Создать SocialBoost из раздельных daily task бонусов
     * 
     * Вспомогательная функция для быстрого создания контекста,
     * когда у вас есть отдельные бонусы от разных тасков.
     * 
     * @param referralCount количество рефералов
     * @param dailyCheckIn буст от daily check-in (обычно 0.02-0.03)
     * @param socialShare буст от social share (обычно 0.05)
     * @param sleepStreak буст от sleep streak (обычно 0.0 или 0.10)
     * @return SocialBoost
     */
    fun createBoost(
        referralCount: Int,
        dailyCheckIn: Double = 0.0,
        socialShare: Double = 0.0,
        sleepStreak: Double = 0.0
    ): SocialBoost {
        val totalTasks = dailyCheckIn + socialShare + sleepStreak
        return SocialBoost(
            referralCount = referralCount,
            dailyTasksPercent = totalTasks
        )
    }
    
    /**
     * Получить прогресс к максимальному рефералному бусту
     * 
     * @param referralCount текущее количество рефералов
     * @return прогресс (0.0-1.0)
     */
    fun getReferralProgress(referralCount: Int): Double {
        return (referralCount.toDouble() / REFERRALS_FOR_MAX).coerceAtMost(1.0)
    }
}
