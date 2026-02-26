package com.sleeper.app.domain.manager

import android.content.Context
import com.sleeper.app.utils.DevLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

class StorageManager(private val context: Context) {
    
    companion object {
        private const val TAG = "StorageManager"
        private const val STORAGE_DIR = "mining_storage"
        private const val FILE_SIZE_MB = 100
        private const val BYTES_IN_MB = 1024 * 1024
    }
    
    private val storageDir: File by lazy {
        File(context.filesDir, STORAGE_DIR).apply {
            if (!exists()) mkdirs()
        }
    }
    
    /**
     * Выделяет storage (создаёт файлы на диске)
     * @param plotsCount количество плотов (каждый по 100MB)
     * @return true если успешно
     */
    suspend fun allocateStorage(plotsCount: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            DevLog.d(TAG, "Allocating $plotsCount plots...")
            
            for (i in 1..plotsCount) {
                val file = File(storageDir, "plot_$i.dat")
                
                if (file.exists() && file.length() == FILE_SIZE_MB * BYTES_IN_MB.toLong()) {
                    DevLog.d(TAG, "Plot $i already exists")
                    continue
                }
                
                // Создаём файл нужного размера
                RandomAccessFile(file, "rw").use { raf ->
                    raf.setLength(FILE_SIZE_MB * BYTES_IN_MB.toLong())
                    
                    // Записываем немного данных для валидности
                    val buffer = ByteArray(4096)
                    for (offset in 0 until FILE_SIZE_MB step 10) {
                        raf.seek(offset * BYTES_IN_MB.toLong())
                        raf.write(buffer)
                    }
                }
                
                DevLog.d(TAG, "Plot $i created: ${file.length() / BYTES_IN_MB}MB")
            }
            
            true
        } catch (e: Exception) {
            DevLog.e(TAG, "Failed to allocate storage", e)
            false
        }
    }
    
    /**
     * Возвращает количество выделенных плотов
     */
    suspend fun getAllocatedPlotsCount(): Int = withContext(Dispatchers.IO) {
        storageDir.listFiles()?.count { 
            it.name.startsWith("plot_") && 
            it.length() == FILE_SIZE_MB * BYTES_IN_MB.toLong() 
        } ?: 0
    }
    
    /**
     * Вычисляет множитель за storage
     * V2.0 (aligned with backend): logarithmic x1.0..x3.0
     */
    fun calculateStorageMultiplier(storageMB: Int): Double {
        val minStorage = 100.0
        val maxStorage = 600.0
        val minMult = 1.0
        val maxMult = 3.0

        if (storageMB <= minStorage) return minMult
        if (storageMB >= maxStorage) return maxMult

        val progress = kotlin.math.ln(storageMB - minStorage + 1.0) /
            kotlin.math.ln(maxStorage - minStorage + 1.0)
        return minMult + progress * (maxMult - minMult)
    }
    
    /**
     * Очищает storage (удаляет плоты)
     */
    suspend fun clearStorage(): Boolean = withContext(Dispatchers.IO) {
        try {
            storageDir.listFiles()?.forEach { it.delete() }
            DevLog.d(TAG, "Storage cleared")
            true
        } catch (e: Exception) {
            DevLog.e(TAG, "Failed to clear storage", e)
            false
        }
    }
    
    /**
     * Возвращает общий размер выделенного storage в MB
     */
    suspend fun getTotalStorageMB(): Int = withContext(Dispatchers.IO) {
        getAllocatedPlotsCount() * FILE_SIZE_MB
    }
    
    /**
     * Проверяет валидность storage (файлы не повреждены)
     */
    suspend fun verifyStorage(): Boolean = withContext(Dispatchers.IO) {
        try {
            val plots = storageDir.listFiles()?.filter { it.name.startsWith("plot_") } ?: return@withContext true
            
            plots.all { file ->
                file.exists() && 
                file.length() == FILE_SIZE_MB * BYTES_IN_MB.toLong() &&
                file.canRead()
            }
        } catch (e: Exception) {
            DevLog.e(TAG, "Storage verification failed", e)
            false
        }
    }
}
