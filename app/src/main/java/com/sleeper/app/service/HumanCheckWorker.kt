package com.sleeper.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.sleeper.app.utils.DevLog
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sleeper.app.MainActivity
import com.sleeper.app.R
import com.sleeper.app.data.local.AppDatabase

/**
 * Worker для периодических human checks (раз в 30 минут)
 */
class HumanCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "HumanCheckWorker"
        const val CHANNEL_ID = "human_check_channel"
        const val NOTIFICATION_ID = 2
        
        const val WORK_NAME = "human_check_work"
    }
    
    override suspend fun doWork(): Result {
        DevLog.d(TAG, "Human check triggered")
        
        val database = AppDatabase.getInstance(applicationContext)
        val stats = database.userStatsDao().getUserStats()
        
        // Проверяем, идёт ли майнинг
        if (stats?.isMining != true) {
            DevLog.d(TAG, "Mining not active, skipping check")
            return Result.success()
        }
        
        // Показываем уведомление с просьбой подтверждения
        showHumanCheckNotification()
        
        return Result.success()
    }
    
    private fun showHumanCheckNotification() {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // Создаём канал
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Проверки активности",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления для подтверждения активности"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        // Intent для открытия приложения
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("human_check", true)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.notif_human_check_title))
            .setContentText(applicationContext.getString(R.string.notif_human_check_text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID, notification)
        
        DevLog.d(TAG, "Human check notification shown")
    }
}
