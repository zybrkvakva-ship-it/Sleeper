package com.sleeper.app.domain.manager

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StorageManager(private val context: Context) {

    /**
     * Возвращает доступное место на устройстве в МБ (информационно, не используется в формуле).
     */
    suspend fun getAvailableStorageMB(): Long = withContext(Dispatchers.IO) {
        val stat = android.os.StatFs(context.filesDir.path)
        stat.availableBytes / (1024L * 1024L)
    }
}
