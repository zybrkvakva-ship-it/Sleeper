package com.seekerminer.app.economy.models

import com.seekerminer.app.economy.boosts.SkrBoostLevel

/**
 * Константы экономики NightMiner
 * 
 * NightMiner — это mining SLEEP токенов во время сна.
 * Пользователь кладёт телефон перед сном, и приложение считает Night Points (NP),
 * которые затем конвертируются в токены SLEEP.
 */
object EconomyConstants {
    /** Общий пул токенов SLEEP на сезон */
    const val SEASON_POOL = 5_000_000L
    
    /** Максимум устройств, которые могут участвовать */
    const val MAX_DEVICES = 150_000
    
    /** Максимальный социальный буст (40%) */
    const val MAX_SOCIAL_BOOST = 0.4
    
    /** Максимальный общий мультипликатор фарма (×6) */
    const val MAX_TOTAL_MULTIPLIER = 6.0
    
    /** Базовая ставка: 1 NP за минуту сна */
    const val BASE_RATE_PER_MINUTE = 1.0
    
    /** Максимум минут сна для расчёта (8 часов) */
    const val MAX_SLEEP_MINUTES = 480
    
    /** Максимум storage для Proof-of-Storage (MB) */
    const val MAX_STORAGE_MB = 500
    
    /** Цена генезис NFT (SKR) */
    const val GENESIS_NFT_PRICE = 500.0
    
    /** Лимит генезис NFT */
    const val GENESIS_NFT_LIMIT = 10_000
}

/**
 * Контекст ночи для расчёта Night Points
 * 
 * Содержит все параметры, необходимые для расчёта вознаграждения за ночь.
 * 
 * @param minutesSlept минуты сна (0-480, макс 8 часов)
 * @param storageMb выделенное хранилище для Proof-of-Storage (0-500 MB)
 * @param humanFactor коэффициент "человечности" Proof-of-Presence (0.3, 0.7, 1.0)
 * @param weekIndex текущая неделя сезона (1-16)
 * @param activeDevices количество активных устройств в сети
 * @param referralCount количество активных рефералов
 * @param dailyTasksPercent бонус процент от выполненных daily tasks (0.0-0.3)
 * @param skrBoostLevel выбранный уровень платного буста за SKR
 * @param hasGenesisNft владеет ли пользователь генезис NFT
 */
data class NightContext(
    val minutesSlept: Int,
    val storageMb: Int,
    val humanFactor: Double,
    val weekIndex: Int,
    val activeDevices: Int,
    val referralCount: Int,
    val dailyTasksPercent: Double,
    val skrBoostLevel: SkrBoostLevel,
    val hasGenesisNft: Boolean
) {
    init {
        require(minutesSlept in 0..EconomyConstants.MAX_SLEEP_MINUTES) {
            "Minutes slept must be in range 0..${EconomyConstants.MAX_SLEEP_MINUTES}"
        }
        require(storageMb in 0..EconomyConstants.MAX_STORAGE_MB) {
            "Storage MB must be in range 0..${EconomyConstants.MAX_STORAGE_MB}"
        }
        require(humanFactor in 0.0..1.0) {
            "Human factor must be in range 0.0..1.0"
        }
        require(weekIndex >= 1) {
            "Week index must be >= 1"
        }
        require(activeDevices >= 0) {
            "Active devices must be >= 0"
        }
        require(referralCount >= 0) {
            "Referral count must be >= 0"
        }
        require(dailyTasksPercent >= 0.0) {
            "Daily tasks percent must be >= 0.0"
        }
    }
}

/**
 * Результат расчёта вознаграждения за ночь
 * 
 * @param baseNp базовые Night Points без бустов
 * @param socialBoost социальный буст (0.0-0.4)
 * @param skrBoost SKR буст (0.0-1.0)
 * @param nftMultiplier NFT мультипликатор (1.0 или 3.0)
 * @param totalMultiplier итоговый мультипликатор всех бустов
 * @param finalNp финальные Night Points с учётом всех бустов
 * @param sleepTokens заработанные токены SLEEP (требует totalNp от всех юзеров)
 */
data class NightReward(
    val baseNp: Double,
    val socialBoost: Double,
    val skrBoost: Double,
    val nftMultiplier: Double,
    val totalMultiplier: Double,
    val finalNp: Double,
    val sleepTokens: Long = 0L  // заполняется на бэкенде
) {
    /**
     * Форматированный вывод для отладки
     */
    override fun toString(): String {
        return """
            |NightReward:
            |  Base NP: $baseNp
            |  Social Boost: ${(socialBoost * 100).toInt()}%
            |  SKR Boost: ${(skrBoost * 100).toInt()}%
            |  NFT Multiplier: ${nftMultiplier}x
            |  Total Multiplier: ${String.format("%.2f", totalMultiplier)}x
            |  Final NP: ${String.format("%.2f", finalNp)}
            |  SLEEP Tokens: $sleepTokens
        """.trimMargin()
    }
}

/**
 * Human Factor уровни для Proof-of-Presence
 * 
 * Определяет, насколько "человечно" вёл себя пользователь во время сна.
 */
enum class HumanFactorLevel(val factor: Double) {
    /** Идеально: телефон не двигался, экран не включался */
    PERFECT(1.0),
    
    /** Хорошо: минимальные нарушения */
    GOOD(0.7),
    
    /** Плохо: много движений/включений экрана */
    POOR(0.3);
    
    companion object {
        /**
         * Получить уровень по фактору
         */
        fun fromFactor(factor: Double): HumanFactorLevel {
            return when {
                factor >= 0.9 -> PERFECT
                factor >= 0.6 -> GOOD
                else -> POOR
            }
        }
    }
}
