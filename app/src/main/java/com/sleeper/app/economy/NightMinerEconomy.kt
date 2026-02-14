package com.sleeper.app.economy

import com.sleeper.app.utils.DevLog
import com.sleeper.app.economy.boosts.NftBoost
import com.sleeper.app.economy.boosts.SocialBoostCalculator
import com.sleeper.app.economy.boosts.skrBoostPercent
import com.sleeper.app.economy.models.NightContext
import com.sleeper.app.economy.models.NightReward
import com.sleeper.app.economy.rewards.BaseRewardCalculator
import com.sleeper.app.economy.rewards.SleepDistributor
import com.sleeper.app.economy.season.SeasonEconomy

/**
 * NightMiner Economy — главный facade для всех экономических расчётов
 * 
 * Это единая точка входа для:
 * - Расчёта базовых Night Points
 * - Применения бустов (social, SKR, NFT)
 * - Распределения токенов SLEEP
 * - Получения информации о сезоне
 * 
 * Использование:
 * ```kotlin
 * val ctx = NightContext(
 *     minutesSlept = 480,
 *     storageMb = 250,
 *     humanFactor = 1.0,
 *     weekIndex = 5,
 *     activeDevices = 15_000,
 *     referralCount = 12,
 *     dailyTasksPercent = 0.08,
 *     skrBoostLevel = SkrBoostLevel.PLUS,
 *     hasGenesisNft = true
 * )
 * 
 * val reward = NightMinerEconomy.calculateNightReward(ctx)
 * println(reward.finalNp)      // Night Points с бустами
 * println(reward.sleepTokens)  // SLEEP токены (нужен totalNp)
 * ```
 */
object NightMinerEconomy {
    
    private const val TAG = "NightMinerEconomy"
    
    /**
     * Вычислить полное вознаграждение за ночь
     * 
     * Эта функция выполняет полный цикл расчёта:
     * 1. Базовые NP (время сна + storage + human + difficulty)
     * 2. Социальные бусты (рефералы + таски)
     * 3. SKR буст (платный)
     * 4. NFT мультипликатор
     * 5. Финальные NP с cap
     * 6. Токены SLEEP (если передан totalNp)
     * 
     * @param ctx контекст ночи со всеми параметрами
     * @param totalNpInNetwork общий NP всех майнеров (для расчёта SLEEP)
     * @return полное вознаграждение за ночь
     */
    fun calculateNightReward(
        ctx: NightContext,
        totalNpInNetwork: Double = 0.0
    ): NightReward {
        DevLog.i(TAG, "=== Calculating Night Reward ===")
        DevLog.d(TAG, "Active devices: ${ctx.activeDevices}, Week: ${ctx.weekIndex}")
        
        // 1. Получаем информацию о сезоне
        val maxWeeks = SeasonEconomy.currentWeeks(ctx.activeDevices)
        val poolNight = SeasonEconomy.poolPerNight(ctx.activeDevices)
        
        DevLog.d(TAG, "Season: $maxWeeks weeks, Pool/night: $poolNight SLEEP")
        
        // 2. Базовые NP
        val baseCtx = BaseRewardCalculator.BaseContext(
            minutesSlept = ctx.minutesSlept,
            storageMb = ctx.storageMb,
            humanFactor = ctx.humanFactor,
            weekIndex = ctx.weekIndex,
            maxWeeks = maxWeeks
        )
        val baseNp = BaseRewardCalculator.calcBaseNp(baseCtx)
        
        DevLog.d(TAG, "Base NP: ${String.format("%.2f", baseNp)}")
        
        // 3. Социальные бусты
        val socialBoostData = SocialBoostCalculator.SocialBoost(
            referralCount = ctx.referralCount,
            dailyTasksPercent = ctx.dailyTasksPercent
        )
        val socialBoost = SocialBoostCalculator.calcSocialBoost(socialBoostData)
        
        DevLog.d(TAG, "Social boost: ${(socialBoost * 100).toInt()}%")
        
        // 4. SKR буст
        val skrBoost = skrBoostPercent(ctx.skrBoostLevel)
        
        DevLog.d(TAG, "SKR boost: ${(skrBoost * 100).toInt()}% (${ctx.skrBoostLevel})")
        
        // 5. Финальные NP с NFT и cap
        val boostCtx = NftBoost.BoostContext(
            baseNp = baseNp,
            socialBoost = socialBoost,
            skrBoost = skrBoost,
            hasGenesisNft = ctx.hasGenesisNft
        )
        val finalNp = NftBoost.calcFinalNp(boostCtx)
        
        // Вычисляем фактический мультипликатор
        val actualMultiplier = if (baseNp > 0) finalNp / baseNp else 1.0
        val nftMultiplier = if (ctx.hasGenesisNft) NftBoost.NFT_MULTIPLIER else 1.0
        
        DevLog.d(TAG, "Final NP: ${String.format("%.2f", finalNp)} " +
                   "(${String.format("%.2f", actualMultiplier)}x)")
        
        // 6. Токены SLEEP (если известен totalNp)
        val sleepTokens = if (totalNpInNetwork > 0.0) {
            SleepDistributor.calcSleepRewardForUser(
                userNp = finalNp,
                totalNp = totalNpInNetwork,
                poolNight = poolNight
            )
        } else {
            0L
        }
        
        DevLog.i(TAG, "=== Reward calculated: " +
                  "${String.format("%.2f", finalNp)} NP, " +
                  "$sleepTokens SLEEP ===")
        
        return NightReward(
            baseNp = baseNp,
            socialBoost = socialBoost,
            skrBoost = skrBoost,
            nftMultiplier = nftMultiplier,
            totalMultiplier = actualMultiplier,
            finalNp = finalNp,
            sleepTokens = sleepTokens
        )
    }
    
    /**
     * Получить прогноз вознаграждения (без расчёта SLEEP)
     * 
     * Полезно для показа пользователю потенциального дохода
     * перед началом ночи.
     * 
     * @param ctx контекст ночи
     * @return прогноз NP
     */
    fun forecastNightReward(ctx: NightContext): NightReward {
        return calculateNightReward(ctx, totalNpInNetwork = 0.0)
    }
    
    /**
     * Вычислить потенциальный буст от покупки NFT
     * 
     * Показывает пользователю, сколько дополнительных NP он получит
     * с генезис NFT.
     * 
     * @param ctx контекст ночи (без NFT)
     * @return разница в NP с NFT и без
     */
    fun calculateNftBoostPotential(ctx: NightContext): Double {
        val withoutNft = forecastNightReward(ctx).finalNp
        val withNft = forecastNightReward(ctx.copy(hasGenesisNft = true)).finalNp
        return withNft - withoutNft
    }
    
    /**
     * Вычислить потенциальный буст от покупки SKR буста
     * 
     * Показывает разницу в NP для разных уровней SKR буста.
     * 
     * @param ctx контекст ночи
     * @param targetLevel целевой уровень буста
     * @return разница в NP
     */
    fun calculateSkrBoostPotential(
        ctx: NightContext,
        targetLevel: com.sleeper.app.economy.boosts.SkrBoostLevel
    ): Double {
        val current = forecastNightReward(ctx).finalNp
        val withBoost = forecastNightReward(ctx.copy(skrBoostLevel = targetLevel)).finalNp
        return withBoost - current
    }
    
    /**
     * Получить информацию о текущем сезоне
     * 
     * @param activeDevices количество активных устройств
     * @param weekIndex текущая неделя
     * @return информация о сезоне
     */
    fun getSeasonInfo(activeDevices: Int, weekIndex: Int) =
        SeasonEconomy.getSeasonInfo(activeDevices, weekIndex)
    
    /**
     * Получить информацию о генезис NFT
     */
    fun getGenesisNftInfo() = NftBoost.getGenesisNftInfo()
}

/**
 * Extension: копировать NightContext с изменениями
 */
fun NightContext.copy(
    minutesSlept: Int = this.minutesSlept,
    storageMb: Int = this.storageMb,
    humanFactor: Double = this.humanFactor,
    weekIndex: Int = this.weekIndex,
    activeDevices: Int = this.activeDevices,
    referralCount: Int = this.referralCount,
    dailyTasksPercent: Double = this.dailyTasksPercent,
    skrBoostLevel: com.sleeper.app.economy.boosts.SkrBoostLevel = this.skrBoostLevel,
    hasGenesisNft: Boolean = this.hasGenesisNft
) = NightContext(
    minutesSlept = minutesSlept,
    storageMb = storageMb,
    humanFactor = humanFactor,
    weekIndex = weekIndex,
    activeDevices = activeDevices,
    referralCount = referralCount,
    dailyTasksPercent = dailyTasksPercent,
    skrBoostLevel = skrBoostLevel,
    hasGenesisNft = hasGenesisNft
)
