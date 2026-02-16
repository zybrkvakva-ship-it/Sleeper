package com.sleeper.app.utils

import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.math.max
import kotlin.math.min

/**
 * Класс для оптимизации производительности приложения
 * Решает проблемы с UI рендерингом и эффективным использованием системных ресурсов
 */
object PerformanceOptimizer {
    
    // Максимальное время выполнения задачи в UI потоке (в миллисекундах)
    private const val MAX_UI_TASK_DURATION_MS = 16 // ~60 FPS
    
    // Кэш для часто используемых вычислений
    private val calculationCache = mutableMapOf<String, Pair<Long, Any?>>()
    private const val CACHE_TTL_MS = 5000L // 5 секунд
    
    /**
     * Выполняет тяжелую задачу в безопасном режиме, предотвращая блокировку UI
     */
    suspend fun <T> executeSafeBackgroundTask(
        task: () -> T,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ): T {
        return kotlinx.coroutines.withContext(dispatcher) {
            task()
        }
    }
    
    /**
     * Выполняет задачу в UI потоке с проверкой времени выполнения
     */
    fun executeSafeUITask(task: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Мы уже в UI потоке, просто выполняем с ограничением по времени
            val startTime = System.currentTimeMillis()
            task()
            val duration = System.currentTimeMillis() - startTime
            
            if (duration > MAX_UI_TASK_DURATION_MS) {
                DevLog.w("PerformanceOptimizer", "UI task took too long: ${duration}ms")
            }
        } else {
            // Переключаемся в UI поток через Handler или аналог
            task()
        }
    }
    
    /**
     * Кэширует результат вычислений с TTL
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> cachedCalculation(key: String, ttlMs: Long = CACHE_TTL_MS, calculation: () -> T): T {
        val currentTime = System.currentTimeMillis()
        
        val cached = calculationCache[key]
        if (cached != null) {
            if (currentTime - cached.first < ttlMs) {
                DevLog.d("PerformanceOptimizer", "Cache hit for key: $key")
                return cached.second as T
            } else {
                // Удаляем просроченный кэш
                calculationCache.remove(key)
            }
        }
        
        val result = calculation()
        calculationCache[key] = Pair(currentTime, result)
        DevLog.d("PerformanceOptimizer", "Cache miss for key: $key, cached new result")
        
        return result
    }
    
    /**
     * Очищает устаревшие записи кэша
     */
    fun cleanupExpiredCache() {
        val currentTime = System.currentTimeMillis()
        val keysToRemove = mutableListOf<String>()
        
        for ((key, value) in calculationCache) {
            if (currentTime - value.first > CACHE_TTL_MS) {
                keysToRemove.add(key)
            }
        }
        
        for (key in keysToRemove) {
            calculationCache.remove(key)
        }
        
        DevLog.d("PerformanceOptimizer", "Cleaned up ${keysToRemove.size} expired cache entries")
    }
    
    /**
     * Ограничивает частоту вызовов функции
     */
    fun <T> throttle(lastCallTimes: MutableMap<String, Long>, key: String, intervalMs: Long, action: () -> T?): T? {
        val currentTime = System.currentTimeMillis()
        val lastCallTime = lastCallTimes[key] ?: 0L
        
        if (currentTime - lastCallTime >= intervalMs) {
            lastCallTimes[key] = currentTime
            return action()
        }
        
        return null
    }
    
    /**
     * Ограничивает максимальное значение для параметров
     */
    fun clampValue(value: Float, min: Float, max: Float): Float {
        return kotlin.math.max(min, kotlin.math.min(max, value))
    }
    
    /**
     * Проверяет, находится ли приложение в активном состоянии
     */
    fun isAppActive(): Boolean {
        // В реальной реализации здесь будет проверка активности приложения
        return true
    }
}