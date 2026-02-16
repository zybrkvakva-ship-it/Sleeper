package com.sleeper.app.utils

import org.junit.Test
import org.junit.Assert.*
import java.util.*

class PdaCacheTest {

    @Test
    fun `test PDA caching works correctly`() {
        // Подготовка тестовых данных
        val programId = "ALTNSZ46uaAUU7XUV6awvdorLGqAsPwa9shm7h4uP2FK".decodeBase58()
        val seeds = listOf(
            "tld_house".toByteArray(),
            ".skr".toByteArray()
        )

        // Очистка кэша перед тестом
        PdaCache.clearCache()
        
        assertEquals(0, PdaCache.getCacheSize())
        
        // Первый вызов - вычисление
        val result1 = PdaCache.getCachedPda(seeds, programId)
        
        // Проверяем, что результат не null (для корректных данных)
        assertNotNull("PDA should be found for valid inputs", result1)
        
        // Второй вызов - из кэша
        val result2 = PdaCache.getCachedPda(seeds, programId)
        
        // Результаты должны совпадать
        assertNotNull("PDA should be found in cache", result2)
        assertArrayEquals("PDA bytes should match", result1?.first, result2?.first)
        assertEquals("Bump value should match", result1?.second, result2?.second)
        
        // Размер кэша должен быть 1
        assertEquals(1, PdaCache.getCacheSize())
    }

    @Test
    fun `test PDA cache handles different seeds`() {
        // Подготовка тестовых данных
        val programId = "ALTNSZ46uaAUU7XUV6awvdorLGqAsPwa9shm7h4uP2FK".decodeBase58()
        val seeds1 = listOf(
            "tld_house".toByteArray(),
            ".skr".toByteArray()
        )
        val seeds2 = listOf(
            "tld_house".toByteArray(),
            ".sol".toByteArray()
        )

        // Очистка кэша перед тестом
        PdaCache.clearCache()
        
        // Вычисляем PDA для разных сидов
        val result1 = PdaCache.getCachedPda(seeds1, programId)
        val result2 = PdaCache.getCachedPda(seeds2, programId)
        
        // Оба результата должны быть не null
        assertNotNull("PDA should be found for .skr", result1)
        assertNotNull("PDA should be found for .sol", result2)
        
        // Результаты должны отличаться
        assertFalse("PDA for .skr and .sol should be different", 
            Arrays.equals(result1?.first, result2?.first))
        
        // Размер кэша должен быть 2
        assertEquals(2, PdaCache.getCacheSize())
    }

    @Test
    fun `test PDA cache size limit`() {
        // Подготовка тестовых данных
        val programId = "ALTNSZ46uaAUU7XUV6awvdorLGqAsPwa9shm7h4uP2FK".decodeBase58()
        
        // Очистка кэша перед тестом
        PdaCache.clearCache()
        
        // Добавляем больше элементов, чем максимальный размер кэша
        for (i in 0 until 1010) {
            val seeds = listOf(
                "seed_$i".toByteArray()
            )
            PdaCache.getCachedPda(seeds, programId)
        }
        
        // Размер кэша не должен превышать максимальный
        assertTrue("Cache size should not exceed max limit", PdaCache.getCacheSize() <= 1000)
    }
}