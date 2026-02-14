package com.sleeper.app.economy.boosts

import com.sleeper.app.utils.DevLog
import com.sleeper.app.economy.models.EconomyConstants.GENESIS_NFT_LIMIT
import com.sleeper.app.economy.models.EconomyConstants.GENESIS_NFT_PRICE
import com.sleeper.app.economy.models.EconomyConstants.MAX_TOTAL_MULTIPLIER

/**
 * Генезис NFT и финальный расчёт Night Points с бустами
 * 
 * Генезис NFT:
 * - Лимит: 10,000 NFT
 * - Цена: 500 SKR
 * - Эффект: постоянный ×3.0 мультипликатор фарма
 * 
 * Формула финального NP:
 * NP_final = NP_base × (1 + B_social) × (1 + B_SKR) × B_NFT
 * 
 * С ограничением на maxTotalMultiplier (по умолчанию ×6).
 */
object NftBoost {
    
    private const val TAG = "NftBoost"
    
    /** Мультипликатор генезис NFT */
    const val NFT_MULTIPLIER = 3.0
    
    /**
     * Контекст для финального расчёта с бустами
     * 
     * @param baseNp базовые Night Points без бустов
     * @param socialBoost социальный буст (0.0-0.4)
     * @param skrBoost SKR буст (0.0-1.0)
     * @param hasGenesisNft владеет ли пользователь генезис NFT
     * @param maxTotalMultiplier максимальный общий мультипликатор
     */
    data class BoostContext(
        val baseNp: Double,
        val socialBoost: Double,
        val skrBoost: Double,
        val hasGenesisNft: Boolean,
        val maxTotalMultiplier: Double = MAX_TOTAL_MULTIPLIER
    ) {
        init {
            require(baseNp >= 0.0) {
                "Base NP cannot be negative: $baseNp"
            }
            require(socialBoost in 0.0..0.5) {
                "Social boost must be in 0.0..0.5: $socialBoost"
            }
            require(skrBoost in 0.0..1.0) {
                "SKR boost must be in 0.0..1.0: $skrBoost"
            }
            require(maxTotalMultiplier > 0.0) {
                "Max total multiplier must be positive: $maxTotalMultiplier"
            }
        }
    }
    
    /**
     * Вычислить финальные Night Points с применением всех бустов
     * 
     * Формула: NP = base × (1 + social) × (1 + skr) × NFT
     * 
     * Бусты перемножаются для максимального эффекта при комбинировании.
     * Итоговый мультипликатор ограничен maxTotalMultiplier.
     * 
     * @param ctx контекст бустов
     * @return финальные Night Points
     * 
     * @example
     * ```
     * // Whale с NFT + Ultra boost + max social
     * val ctx = BoostContext(
     *     baseNp = 1000.0,
     *     socialBoost = 0.4,      // +40%
     *     skrBoost = 1.0,         // +100% (Ultra)
     *     hasGenesisNft = true    // ×3
     * )
     * calcFinalNp(ctx)  // 6000.0 (capped at ×6)
     * // Raw: 1000 × 1.4 × 2.0 × 3.0 = 8400, but capped at 6000
     * ```
     */
    fun calcFinalNp(ctx: BoostContext): Double {
        // NFT мультипликатор
        val bNft = if (ctx.hasGenesisNft) NFT_MULTIPLIER else 1.0
        
        // Расчёт "сырого" мультипликатора
        // (1 + social) × (1 + skr) × NFT
        val rawMultiplier = (1.0 + ctx.socialBoost) * (1.0 + ctx.skrBoost) * bNft
        
        // Cap на maxTotalMultiplier
        val cappedMultiplier = rawMultiplier.coerceAtMost(ctx.maxTotalMultiplier)
        
        // Финальный расчёт
        val finalNp = ctx.baseNp * cappedMultiplier
        
        val wasCapped = rawMultiplier > ctx.maxTotalMultiplier
        
        DevLog.d(TAG, "Final NP: ${String.format("%.2f", finalNp)} " +
                   "(base: ${String.format("%.2f", ctx.baseNp)}, " +
                   "multiplier: ${String.format("%.2f", cappedMultiplier)}x" +
                   if (wasCapped) " [CAPPED from ${String.format("%.2f", rawMultiplier)}x]" else "" +
                   ")")
        
        DevLog.d(TAG, "Boost breakdown: " +
                   "social=${(ctx.socialBoost * 100).toInt()}%, " +
                   "skr=${(ctx.skrBoost * 100).toInt()}%, " +
                   "nft=${if (ctx.hasGenesisNft) "×3" else "×1"}")
        
        return finalNp
    }
    
    /**
     * Вычислить итоговый мультипликатор для данных бустов
     * 
     * Полезно для предпросмотра эффекта перед покупкой буста.
     * 
     * @param socialBoost социальный буст
     * @param skrBoost SKR буст
     * @param hasGenesisNft владение NFT
     * @param maxMultiplier максимальный мультипликатор
     * @return итоговый мультипликатор (capped)
     */
    fun calcTotalMultiplier(
        socialBoost: Double,
        skrBoost: Double,
        hasGenesisNft: Boolean,
        maxMultiplier: Double = MAX_TOTAL_MULTIPLIER
    ): Double {
        val bNft = if (hasGenesisNft) NFT_MULTIPLIER else 1.0
        val rawMultiplier = (1.0 + socialBoost) * (1.0 + skrBoost) * bNft
        return rawMultiplier.coerceAtMost(maxMultiplier)
    }
    
    /**
     * Проверить, достигнут ли cap мультипликатора
     * 
     * @param socialBoost социальный буст
     * @param skrBoost SKR буст
     * @param hasGenesisNft владение NFT
     * @param maxMultiplier максимальный мультипликатор
     * @return true если cap достигнут
     */
    fun isMultiplierCapped(
        socialBoost: Double,
        skrBoost: Double,
        hasGenesisNft: Boolean,
        maxMultiplier: Double = MAX_TOTAL_MULTIPLIER
    ): Boolean {
        val bNft = if (hasGenesisNft) NFT_MULTIPLIER else 1.0
        val rawMultiplier = (1.0 + socialBoost) * (1.0 + skrBoost) * bNft
        return rawMultiplier > maxMultiplier
    }
    
    /**
     * Получить информацию о генезис NFT
     */
    fun getGenesisNftInfo(): GenesisNftInfo {
        return GenesisNftInfo(
            price = GENESIS_NFT_PRICE,
            limit = GENESIS_NFT_LIMIT,
            multiplier = NFT_MULTIPLIER
        )
    }
}

/**
 * Информация о генезис NFT
 */
data class GenesisNftInfo(
    val price: Double,      // SKR
    val limit: Int,         // максимум NFT
    val multiplier: Double  // мультипликатор фарма
) {
    override fun toString(): String {
        return """
            |Genesis NFT:
            |  Price: ${price.toInt()} SKR
            |  Limit: $limit NFTs
            |  Multiplier: ${multiplier}x
        """.trimMargin()
    }
}
