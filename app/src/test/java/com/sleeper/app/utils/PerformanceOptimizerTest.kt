package com.sleeper.app.utils

import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito
import android.content.Context
import android.location.LocationManager
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString

class PerformanceOptimizerTest {

    @Test
    fun `test cached calculation works correctly`() {
        var calculationCount = 0
        val testKey = "test_calculation"
        
        // Очищаем кэш перед тестом
        PerformanceOptimizer.cleanupExpiredCache()
        
        // Выполняем вычисление первый раз
        val result1 = PerformanceOptimizer.cachedCalculation(testKey) {
            calculationCount++
            "calculated_value"
        }
        
        // Повторяем то же вычисление
        val result2 = PerformanceOptimizer.cachedCalculation(testKey) {
            calculationCount++
            "another_value"
        }
        
        // Результаты должны совпадать, вычисление должно выполниться только один раз
        assertEquals("calculated_value", result1)
        assertEquals("calculated_value", result2)
        assertEquals(1, calculationCount) // Только одно выполнение
    }

    @Test
    fun `test cached calculation respects TTL`() {
        var calculationCount = 0
        val testKey = "ttl_test"
        
        // Выполняем вычисление
        val result1 = PerformanceOptimizer.cachedCalculation(testKey, 100) { // 100ms TTL
            calculationCount++
            "first_value"
        }
        
        // Ждем, чтобы кэш истек
        Thread.sleep(150)
        
        // Выполняем снова - должно вычислиться заново
        val result2 = PerformanceOptimizer.cachedCalculation(testKey, 100) {
            calculationCount++
            "second_value"
        }
        
        assertEquals("first_value", result1)
        assertEquals("second_value", result2)
        assertEquals(2, calculationCount) // Два выполнения
    }

    @Test
    fun `test clamp value works correctly`() {
        assertEquals(5f, PerformanceOptimizer.clampValue(5f, 0f, 10f))
        assertEquals(0f, PerformanceOptimizer.clampValue(-5f, 0f, 10f))
        assertEquals(10f, PerformanceOptimizer.clampValue(15f, 0f, 10f))
        assertEquals(2f, PerformanceOptimizer.clampValue(2f, 2f, 8f))
    }

    @Test
    fun `test throttle function works correctly`() {
        val lastCallTimes = mutableMapOf<String, Long>()
        var callCount = 0
        val key = "throttle_test"
        val interval = 50L // 50ms interval
        
        // Вызываем функцию несколько раз быстро
        for (i in 0..5) {
            PerformanceOptimizer.throttle(lastCallTimes, key, interval) {
                callCount++
            }
        }
        
        // Проверяем, что функция вызвана только один раз
        assertEquals(1, callCount)
        
        // Ждем, чтобы интервал прошел
        Thread.sleep(interval + 10)
        
        // Вызываем снова - теперь должно сработать
        PerformanceOptimizer.throttle(lastCallTimes, key, interval) {
            callCount++
        }
        
        assertEquals(2, callCount)
    }
}

class GpsOptimizerTest {
    
    @Test
    fun `test GPS optimizer initialization`() {
        val mockContext = Mockito.mock(Context::class.java)
        val mockLocationManager = Mockito.mock(LocationManager::class.java)
        
        // Настройка моков
        Mockito.`when`(mockContext.getSystemService(Context.LOCATION_SERVICE))
            .thenReturn(mockLocationManager)
        Mockito.`when`(mockLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            .thenReturn(true)
        Mockito.`when`(ContextCompat.checkSelfPermission(
            mockContext,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )).thenReturn(PackageManager.PERMISSION_GRANTED)
        
        val gpsOptimizer = GpsOptimizer(mockContext)
        
        assertTrue(gpsOptimizer.isGpsAvailable())
        assertTrue(gpsOptimizer.hasLocationPermission())
    }
    
    @Test
    fun `test update intervals constants`() {
        assertEquals(5000L, GpsOptimizer.FAST_UPDATE_INTERVAL)
        assertEquals(10000L, GpsOptimizer.BALANCED_UPDATE_INTERVAL)
        assertEquals(30000L, GpsOptimizer.EFFICIENT_UPDATE_INTERVAL)
        assertEquals(60000L, GpsOptimizer.PASSIVE_UPDATE_INTERVAL)
    }
}