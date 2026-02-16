package com.sleeper.app.security

import android.content.Context
import com.sleeper.app.BuildConfig
import com.sleeper.app.R
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.provider.Settings
import com.sleeper.app.utils.DevLog
import java.io.File
import java.security.MessageDigest

/**
 * Проверяет устройство на подлинность (только реальные Seeker телефоны)
 * Блокирует эмуляторы, клон-апы, root
 */
class DeviceVerifier(private val context: Context) {
    
    companion object {
        private const val TAG = "DeviceVerifier"
        private const val PREFS_NAME = "device_prefs"
        private const val KEY_FINGERPRINT = "device_fingerprint"
        // Feature flag для постепенного включения device verification
        private const val DEVICE_CHECK_ROLLOUT_PERCENTAGE = 0.1 // 10% пользователей для начала
    }
    
    data class VerificationResult(
        val isValid: Boolean,
        val reason: String,
        val fingerprint: String
    )
    
    /**
     * Полная проверка устройства
     */
    
    /**
     * Полная проверка устройства
     */
    fun verifyDevice(): VerificationResult {
        // 1. Проверка на Seeker
        if (!isSeekerDevice()) {
            return VerificationResult(
                isValid = false,
                reason = context.getString(R.string.error_not_seeker),
                fingerprint = ""
            )
        }
        
        // 2. Проверка на эмулятор
        if (isEmulator()) {
            return VerificationResult(
                isValid = false,
                reason = context.getString(R.string.error_emulator),
                fingerprint = ""
            )
        }
        
        // 3. Проверка на root
        if (isRooted()) {
            return VerificationResult(
                isValid = false,
                reason = context.getString(R.string.error_rooted),
                fingerprint = ""
            )
        }
        
        // 4. Проверка на клон-апы
        if (isCloneApp()) {
            return VerificationResult(
                isValid = false,
                reason = context.getString(R.string.error_clone_app),
                fingerprint = ""
            )
        }
        
        // 5. Генерация уникального отпечатка
        val fingerprint = generateDeviceFingerprint()
        
        return VerificationResult(
            isValid = true,
            reason = "OK",
            fingerprint = fingerprint
        )
    }
    
    /**
     * Проверяет, является ли устройство Solana Seeker.
     * Постепенное включение с feature flag для безопасности.
     */
    private fun isSeekerDevice(): Boolean {
        // Для разработки всегда разрешаем
        if (BuildConfig.DEBUG) {
            DevLog.d(TAG, "Device check bypassed (debug build)")
            return true
        }
        
        // Постепенное включение для production
        val shouldEnforce = shouldEnforceDeviceCheck()
        
        if (!shouldEnforce) {
            DevLog.d(TAG, "Device check soft mode - allowing device")
            return true
        }
        
        // Строгая проверка
        val model = Build.MODEL.lowercase()
        val device = Build.DEVICE.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val isSeekerModel = model.contains("seeker") ||
            model.contains("saga") ||
            device.contains("seeker")
        val isSolanaManufacturer = manufacturer.contains("solana") ||
            manufacturer.contains("osom")
        val result = isSeekerModel || isSolanaManufacturer
        DevLog.d(TAG, "Device check: model=$model, device=$device, manufacturer=$manufacturer -> $result")
        return result
    }
    
    /**
     * Определяет, следует ли применять строгую проверку устройства
     * на основе feature flag и fingerprint'а устройства
     */
    private fun shouldEnforceDeviceCheck(): Boolean {
        val fingerprint = generateDeviceFingerprint()
        // Используем первые символы fingerprint'а для равномерного распределения
        val hashPrefix = fingerprint.take(4).toInt(16)
        val maxHash = 0xFFFF
        val rolloutThreshold = (maxHash.toDouble() * DEVICE_CHECK_ROLLOUT_PERCENTAGE).toInt()
        
        val shouldEnforce = hashPrefix < rolloutThreshold
        DevLog.d(TAG, "Device check rollout: hash=$hashPrefix, threshold=$rolloutThreshold, enforce=$shouldEnforce")
        
        return shouldEnforce
    }
    
    /**
     * Проверяет, является ли устройство эмулятором
     */
    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
                || "google_sdk" == Build.PRODUCT
                || hasMissingSensors())
    }
    
    /**
     * Проверяет отсутствие важных сенсоров (признак эмулятора)
     */
    private fun hasMissingSensors(): Boolean {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        
        val hasAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        val hasGyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        
        // Эмуляторы обычно не имеют всех сенсоров
        return !hasAccelerometer || !hasGyroscope
    }
    
    /**
     * Проверяет наличие root
     */
    private fun isRooted(): Boolean {
        return checkRootMethod1() || checkRootMethod2() || checkRootMethod3()
    }
    
    private fun checkRootMethod1(): Boolean {
        val buildTags = Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }
    
    private fun checkRootMethod2(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        return paths.any { File(it).exists() }
    }
    
    private fun checkRootMethod3(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val input = process.inputStream.bufferedReader().readText()
            input.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Проверяет, является ли приложение клоном
     */
    private fun isCloneApp(): Boolean {
        // Проверка через UserHandle (клон-апы работают в отдельном профиле)
        try {
            val userHandle = android.os.Process.myUserHandle()
            val userHandleMethod = userHandle.javaClass.getDeclaredMethod("getIdentifier")
            val userId = userHandleMethod.invoke(userHandle) as Int
            
            if (userId != 0) {
                DevLog.d(TAG, "Clone app detected: userId=$userId")
                return true
            }
        } catch (e: Exception) {
            DevLog.w(TAG, "Failed to check clone app via UserHandle", e)
        }
        
        // Проверка через package signature
        val expectedPackage = context.packageName
        val actualPackage = context.applicationInfo.packageName
        
        return expectedPackage != actualPackage
    }
    
    /**
     * Генерирует уникальный отпечаток устройства
     */
    fun generateDeviceFingerprint(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Проверяем сохранённый отпечаток
        val savedFingerprint = prefs.getString(KEY_FINGERPRINT, null)
        if (!savedFingerprint.isNullOrEmpty()) {
            return savedFingerprint
        }
        
        // Генерируем новый
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        
        val components = listOf(
            Build.BOARD,
            Build.BRAND,
            Build.DEVICE,
            Build.HARDWARE,
            Build.ID,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.PRODUCT,
            Build.FINGERPRINT,
            androidId
        )
        
        val fingerprint = sha256(components.joinToString("|"))
        
        // Сохраняем
        prefs.edit().putString(KEY_FINGERPRINT, fingerprint).apply()
        
        DevLog.d(TAG, "Generated fingerprint: $fingerprint")
        return fingerprint
    }
    
    /**
     * SHA-256 хеш
     */
    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Сохраняет fingerprint в SharedPreferences
     */
    fun saveFingerprint(fingerprint: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FINGERPRINT, fingerprint)
            .apply()
    }
    
    /**
     * Получает сохранённый fingerprint
     */
    fun getSavedFingerprint(): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FINGERPRINT, null)
    }
}
