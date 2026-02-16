package com.sleeper.app.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Класс для оптимизации использования GPS ресурсов
 * Решает проблемы с чрезмерным использованием GPS и утечками ресурсов
 */
class GpsOptimizer(private val context: Context) {
    
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val isTracking = AtomicBoolean(false)
    
    companion object {
        private const val TAG = "GpsOptimizer"
        
        // Интервалы обновления для различных сценариев использования
        const val FAST_UPDATE_INTERVAL = 5000L // 5 секунд для высокой точности
        const val BALANCED_UPDATE_INTERVAL = 10000L // 10 секунд для баланса
        const val EFFICIENT_UPDATE_INTERVAL = 30000L // 30 секунд для экономии
        const val PASSIVE_UPDATE_INTERVAL = 60000L // 60 секунд для пассивного режима
        
        // Минимальное изменение расстояния для обновления
        const val MIN_DISTANCE_METERS = 10f
    }
    
    /**
     * Проверяет, доступен ли GPS
     */
    fun isGpsAvailable(): Boolean {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }
    
    /**
     * Проверяет, есть ли разрешение на использование GPS
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Оптимизированный метод получения текущего местоположения
     */
    fun getCurrentLocation(
        onSuccess: (Location) -> Unit,
        onError: (Exception) -> Unit,
        timeoutMs: Long = 10000L
    ) {
        if (!hasLocationPermission()) {
            onError(SecurityException("Location permission not granted"))
            return
        }
        
        if (!isGpsAvailable()) {
            onError(Exception("GPS provider not available"))
            return
        }
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Сначала пытаемся получить последнее известное местоположение
                val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                
                if (lastKnownLocation != null && 
                    System.currentTimeMillis() - lastKnownLocation.time < EFFICIENT_UPDATE_INTERVAL) {
                    // Используем кэшированное местоположение если оно достаточно свежее
                    DevLog.d(TAG, "Using cached location, age: ${System.currentTimeMillis() - lastKnownLocation.time}ms")
                    launch(Dispatchers.Main) {
                        onSuccess(lastKnownLocation)
                    }
                    return@launch
                }
                
                // Если кэшированное местоположение устарело, запрашиваем новое
                val locationListener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        DevLog.d(TAG, "New location received: ${location.latitude}, ${location.longitude}")
                        locationManager.removeUpdates(this)
                        launch(Dispatchers.Main) {
                            onSuccess(location)
                        }
                    }
                    
                    override fun onProviderDisabled(provider: String) {
                        if (provider == LocationManager.GPS_PROVIDER) {
                            launch(Dispatchers.Main) {
                                onError(Exception("GPS provider disabled"))
                            }
                        }
                    }
                    
                    override fun onProviderEnabled(provider: String) {}
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                }
                
                // Регистрируем слушатель с таймаутом
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    EFFICIENT_UPDATE_INTERVAL,
                    MIN_DISTANCE_METERS,
                    locationListener
                )
                
                // Через определенное время отменяем запрос, если не получили результат
                kotlinx.coroutines.delay(timeoutMs)
                
                // Проверяем, все еще ожидаем обновления
                if (locationManager.getAllProviders().contains(LocationManager.GPS_PROVIDER)) {
                    locationManager.removeUpdates(locationListener)
                    launch(Dispatchers.Main) {
                        onError(Exception("Location request timed out"))
                    }
                }
            } catch (e: Exception) {
                DevLog.e(TAG, "Error getting current location", e)
                launch(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }
    
    /**
     * Оптимизированное отслеживание местоположения с ограничением частоты
     */
    fun startLocationTracking(
        onLocationUpdate: (Location) -> Unit,
        updateInterval: Long = BALANCED_UPDATE_INTERVAL,
        minDistance: Float = MIN_DISTANCE_METERS
    ): Boolean {
        if (!hasLocationPermission() || !isGpsAvailable()) {
            return false
        }
        
        if (isTracking.compareAndSet(false, true)) {
            val locationListener = object : android.location.LocationListener {
                private var lastUpdateTimestamp = 0L
                
                override fun onLocationChanged(location: Location) {
                    val currentTime = System.currentTimeMillis()
                    
                    // Ограничиваем частоту обновлений, чтобы избежать избыточных вызовов
                    if (currentTime - lastUpdateTimestamp >= updateInterval / 2) {
                        lastUpdateTimestamp = currentTime
                        DevLog.d(TAG, "Location update: ${location.latitude}, ${location.longitude}, accuracy: ${location.accuracy}m")
                        
                        // Выполняем обновление в безопасном контексте
                        CoroutineScope(Dispatchers.Main).launch {
                            PerformanceOptimizer.executeSafeUITask {
                                onLocationUpdate(location)
                            }
                        }
                    }
                }
                
                override fun onProviderDisabled(provider: String) {
                    if (provider == LocationManager.GPS_PROVIDER) {
                        DevLog.w(TAG, "GPS provider disabled during tracking")
                        stopLocationTracking()
                    }
                }
                
                override fun onProviderEnabled(provider: String) {
                    DevLog.d(TAG, "GPS provider enabled")
                }
                
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
                    DevLog.d(TAG, "GPS provider status changed: $status")
                }
            }
            
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                updateInterval,
                minDistance,
                locationListener
            )
            
            DevLog.i(TAG, "Started location tracking with interval: ${updateInterval}ms, min distance: ${minDistance}m")
            return true
        }
        
        return false
    }
    
    /**
     * Останавливает отслеживание местоположения
     */
    fun stopLocationTracking() {
        if (isTracking.compareAndSet(true, false)) {
            // Удаляем все слушатели
            locationManager.removeUpdates { /* empty listener to remove all */ }
            DevLog.i(TAG, "Stopped location tracking")
        }
    }
    
    /**
     * Оптимизированный метод получения местоположения с использованием различных провайдеров
     */
    fun getLocationFromBestProvider(
        onSuccess: (Location) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (!hasLocationPermission()) {
            onError(SecurityException("Location permission not granted"))
            return
        }
        
        val providers = locationManager.getProviders(true)
        var locationFound = false
        
        for (provider in providers) {
            if (locationFound) break
            
            try {
                val location = locationManager.getLastKnownLocation(provider)
                if (location != null) {
                    val timeDiff = System.currentTimeMillis() - location.time
                    // Используем местоположение, если оно не старше 5 минут
                    if (timeDiff < 300000) {
                        DevLog.d(TAG, "Got location from provider: $provider, age: ${timeDiff}ms")
                        onSuccess(location)
                        locationFound = true
                    }
                }
            } catch (e: SecurityException) {
                DevLog.w(TAG, "Security exception for provider: $provider", e)
            }
        }
        
        if (!locationFound) {
            onError(Exception("No recent location available from any provider"))
        }
    }
    
    /**
     * Метод для получения энергоэффективного местоположения
     */
    fun getEfficientLocation(
        onSuccess: (Location) -> Unit,
        onError: (Exception) -> Unit,
        maxAgeMs: Long = EFFICIENT_UPDATE_INTERVAL
    ) {
        if (!hasLocationPermission()) {
            onError(SecurityException("Location permission not granted"))
            return
        }
        
        // Сначала пытаемся использовать кэшированное местоположение
        val gpsLocation = try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (e: SecurityException) {
            null
        }
        
        val networkLocation = try {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }
        
        val bestLocation = when {
            gpsLocation != null && networkLocation != null -> {
                // Выбираем лучшее местоположение по возрасту и точности
                if (gpsLocation.time > networkLocation.time && gpsLocation.accuracy < networkLocation.accuracy) {
                    gpsLocation
                } else {
                    networkLocation
                }
            }
            gpsLocation != null -> gpsLocation
            networkLocation != null -> networkLocation
            else -> null
        }
        
        if (bestLocation != null && System.currentTimeMillis() - bestLocation.time <= maxAgeMs) {
            DevLog.d(TAG, "Using efficient cached location, age: ${System.currentTimeMillis() - bestLocation.time}ms")
            onSuccess(bestLocation)
        } else {
            // Если кэшированное местоположение устарело, запрашиваем новое
            getCurrentLocation(onSuccess, onError)
        }
    }
}