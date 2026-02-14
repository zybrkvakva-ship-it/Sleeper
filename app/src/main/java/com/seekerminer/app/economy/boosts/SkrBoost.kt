package com.seekerminer.app.economy.boosts

/**
 * Уровни платных бустов за SKR токены
 * 
 * Пользователь может купить буст на одну ночь, чтобы увеличить фарм NP.
 * Буст действует только текущую ночь и не накапливается.
 * 
 * @param boostPercent процент буста (0.0 = 0%, 0.05 = 5%, 1.0 = 100%)
 * @param priceSkr цена в SKR токенах
 */
enum class SkrBoostLevel(
    val boostPercent: Double,
    val priceSkr: Double
) {
    /** Нет буста */
    NONE(0.0, 0.0),
    
    /** Lite: +5% за 1 SKR */
    LITE(0.05, 1.0),
    
    /** Plus: +10% за 2.5 SKR */
    PLUS(0.10, 2.5),
    
    /** Pro: +50% за 10 SKR */
    PRO(0.50, 10.0),
    
    /** Ultra: +100% за 20 SKR (удваивает фарм!) */
    ULTRA(1.0, 20.0);
    
    /**
     * Получить читаемое название
     */
    fun displayName(): String {
        return when (this) {
            NONE -> "No Boost"
            LITE -> "Lite Boost (+5%)"
            PLUS -> "Plus Boost (+10%)"
            PRO -> "Pro Boost (+50%)"
            ULTRA -> "Ultra Boost (+100%)"
        }
    }
}

/**
 * Получить процент буста по уровню
 * 
 * @param level уровень SKR буста
 * @return процент буста в долях (0.0-1.0)
 */
fun skrBoostPercent(level: SkrBoostLevel): Double {
    return level.boostPercent
}

/**
 * Получить цену буста в SKR
 * 
 * @param level уровень SKR буста
 * @return цена в SKR токенах
 */
fun skrBoostPrice(level: SkrBoostLevel): Double {
    return level.priceSkr
}
