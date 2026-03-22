package com.sleeper.app.data.local

/**
 * Каталог бустов за SKR: цена в наименьших единицах (6 decimals), множитель к награде, длительность.
 * Один активный буст на пользователя (activeSkrBoostId + activeSkrBoostEndsAt в UserStats).
 * durationHours > 0 — длительность в часах; иначе используется durationDays.
 */
data class SkrBoostItem(
    val id: String,
    val name: String,
    val description: String,
    /** Цена в наименьших единицах SKR (6 decimals). */
    val priceSkrRaw: Long,
    /** Множитель к pointsPerSecond (1.05 = +5%). */
    val multiplier: Double,
    val durationDays: Int,
    /** Длительность в часах; если > 0, используется вместо durationDays. */
    val durationHours: Int = 0
) {
    /** Длительность буста в миллисекундах. */
    fun durationMs(): Long = if (durationHours > 0) {
        durationHours * 3_600_000L
    } else {
        durationDays * 24L * 60 * 60 * 1000
    }

    /** Для UI: "7 ч", "49 ч", "7 дн." или "1 дн." */
    fun durationDisplay(): String = when {
        durationHours > 0 -> if (durationHours >= 24) "${durationHours / 24} дн." else "$durationHours ч"
        else -> "$durationDays дн."
    }
}

/** Адрес казначейства для приёма SKR при покупке бустов (ончейн). Заполнить в BuildConfig или конфиге. */
const val BOOST_TREASURY_DEFAULT = ""

object SkrBoostCatalog {
    private const val SKR_6 = 1_000_000L // 1 SKR

    /** Классические бусты (длительность в днях). */
    val legacyBoosts: List<SkrBoostItem> = listOf(
        SkrBoostItem(
            id = "skr_lite",
            name = "Lite +5%",
            description = "Буст награды на 5%",
            priceSkrRaw = 1 * SKR_6,
            multiplier = 1.05,
            durationDays = 1
        ),
        SkrBoostItem(
            id = "skr_plus",
            name = "Plus +10%",
            description = "Буст награды на 10%",
            priceSkrRaw = (2.5 * SKR_6).toLong(),
            multiplier = 1.10,
            durationDays = 1
        ),
        SkrBoostItem(
            id = "skr_pro",
            name = "Pro +50%",
            description = "Буст награды на 50%",
            priceSkrRaw = 10 * SKR_6,
            multiplier = 1.50,
            durationDays = 3
        ),
        SkrBoostItem(
            id = "skr_ultra",
            name = "Ultra +100%",
            description = "Удваивает награду",
            priceSkrRaw = 20 * SKR_6,
            multiplier = 2.0,
            durationDays = 7
        )
    )

    val all: List<SkrBoostItem> = legacyBoosts

    fun get(id: String): SkrBoostItem? = all.find { it.id == id }
}
