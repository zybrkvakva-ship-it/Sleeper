package com.sleeper.app.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * Кэш для PDA (Program Derived Address) вычислений
 * Оптимизирует производительность за счет сохранения результатов дорогостоящих криптографических операций
 */
object PdaCache {
    private val cache = ConcurrentHashMap<String, Pair<ByteArray, Byte>>()
    private const val MAX_CACHE_SIZE = 1000
    
    /**
     * Получить закэшированный PDA или вычислить и сохранить
     */
    fun getCachedPda(seeds: List<ByteArray>, programId: ByteArray): Pair<ByteArray, Byte>? {
        val cacheKey = generateCacheKey(seeds, programId)
        
        // Попробовать получить из кэша
        cache[cacheKey]?.let { return it }
        
        // Вычислить и закэшировать
        val result = findProgramAddressOptimized(seeds, programId)
        if (result != null && cache.size < MAX_CACHE_SIZE) {
            cache[cacheKey] = result
        }
        
        return result
    }
    
    /**
     * Оптимизированная функция поиска PDA с улучшенной логикой итерации bump значений
     */
    fun findProgramAddressOptimized(seeds: List<ByteArray>, programId: ByteArray): Pair<ByteArray, Byte>? {
        // Используем оптимизированный алгоритм: начинаем с 255 и идем вниз
        // Это более эффективно, так как вероятность нахождения PDA выше у больших bump значений
        for (bump in 255 downTo 0) {
            val bumpSeed = byteArrayOf(bump.toByte())
            val allSeeds = seeds + bumpSeed
            
            val pda = SolanaPda.createProgramAddress(allSeeds, programId)
            if (pda != null) {
                return Pair(pda, bump.toByte())
            }
        }
        return null
    }
    
    /**
     * Альтернативная стратегия поиска PDA с оптимизацией поиска
     * Позволяет использовать разные стратегии итерации для разных сценариев
     */
    fun findProgramAddressWithStrategy(
        seeds: List<ByteArray>, 
        programId: ByteArray,
        searchStrategy: (Int) -> Sequence<Int> = { startValue -> (255 downTo 0).asSequence() }
    ): Pair<ByteArray, Byte>? {
        val bumps = searchStrategy(255)
        
        for (bump in bumps) {
            val bumpSeed = byteArrayOf(bump.toByte())
            val allSeeds = seeds + bumpSeed
            
            val pda = SolanaPda.createProgramAddress(allSeeds, programId)
            if (pda != null) {
                return Pair(pda, bump.toByte())
            }
        }
        return null
    }
    
    /**
     * Генерация уникального ключа для кэширования на основе seeds и programId
     */
    private fun generateCacheKey(seeds: List<ByteArray>, programId: ByteArray): String {
        val crc = CRC32()
        
        // Хэшируем programId
        crc.update(programId)
        val programIdHash = crc.value
        
        // Сбрасываем CRC и хэшируем seeds
        crc.reset()
        for (seed in seeds) {
            crc.update(seed)
        }
        val seedsHash = crc.value
        
        return "${programIdHash}_${seedsHash}_${seeds.size}"
    }
    
    /**
     * Очистить кэш (для тестирования или освобождения памяти)
     */
    fun clearCache() {
        cache.clear()
    }
    
    /**
     * Получить размер кэша
     */
    fun getCacheSize(): Int {
        return cache.size
    }
}