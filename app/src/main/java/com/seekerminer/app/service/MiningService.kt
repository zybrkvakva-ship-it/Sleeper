package com.seekerminer.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.seekerminer.app.utils.DevLog
import androidx.core.app.NotificationCompat
import com.seekerminer.app.MainActivity
import com.seekerminer.app.R
import com.seekerminer.app.data.local.AppDatabase
import com.seekerminer.app.domain.manager.EnergyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MiningService : Service() {
    
    companion object {
        private const val TAG = "MiningService"
        const val CHANNEL_ID = "mining_channel"
        const val NOTIFICATION_ID = 1
        
        const val ACTION_START_MINING = "START_MINING"
        const val ACTION_STOP_MINING = "STOP_MINING"
        
        private const val UPDATE_INTERVAL_MS = 1000L // обновление каждую секунду
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var miningJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    private lateinit var database: AppDatabase
    private lateinit var energyManager: EnergyManager
    private lateinit var notificationManager: NotificationManager
    
    private var miningStartTime = 0L
    private var totalPoints = 0L
    
    override fun onCreate() {
        super.onCreate()
        
        database = AppDatabase.getInstance(applicationContext)
        energyManager = EnergyManager(database.userStatsDao())
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        createNotificationChannel()
        acquireWakeLock()

        DevLog.d(TAG, "MiningService created")
        DevLog.d(TAG, "MiningService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MINING -> startMining()
            ACTION_STOP_MINING -> stopMining()
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun startMining() {
        if (miningJob?.isActive == true) {
            DevLog.d(TAG, "startMining SKIP: already active")
            return
        }

        miningStartTime = System.currentTimeMillis()
        totalPoints = 0L
        DevLog.i(TAG, "startMining ENTRY startTime=$miningStartTime")

        startForeground(NOTIFICATION_ID, createNotification(0, "00:00"))

        serviceScope.launch {
            database.userStatsDao().setMiningState(true, miningStartTime)
            DevLog.d(TAG, "startMining setMiningState(true)")
        }
        
        // Запускаем майнинг цикл
        miningJob = serviceScope.launch {
            var secondsElapsed = 0
            var accumulatedPoints = 0.0 // дробные поинты (BASE=0.2/сек)
            
            while (true) {
                delay(UPDATE_INTERVAL_MS)
                secondsElapsed++
                
                val hasEnergy = energyManager.drainEnergy(1)
                if (!hasEnergy) {
                    DevLog.w(TAG, "startMining loop: energy depleted at totalPoints=$totalPoints secondsElapsed=$secondsElapsed")
                    stopMining()
                    break
                }

                val stats = database.userStatsDao().getUserStats()
                if (stats != null) {
                    val pointsPerSecond = energyManager.getCurrentPointsPerSecond()
                    accumulatedPoints += pointsPerSecond
                    val toAward = accumulatedPoints.toLong()
                    if (toAward > 0) {
                        energyManager.awardPoints(toAward)
                        totalPoints += toAward
                        accumulatedPoints -= toAward
                        if (secondsElapsed % 30 == 0) DevLog.d(TAG, "startMining loop: sec=$secondsElapsed totalPoints=$totalPoints pps=$pointsPerSecond energy=${stats.energyCurrent}/${stats.energyMax}")
                    }
                    
                    // Uptime: +1 минута раз в 60 секунд (раньше было +1 каждую секунду — баг)
                    val newUptimeMinutes = stats.uptimeMinutes + (if (secondsElapsed % 60 == 0) 1L else 0L)
                    if (newUptimeMinutes != stats.uptimeMinutes) {
                        database.userStatsDao().update(
                            stats.copy(uptimeMinutes = newUptimeMinutes)
                        )
                    }
                    
                    // Обновляем notification каждые 5 секунд
                    if (secondsElapsed % 5 == 0) {
                        val uptime = formatUptime(secondsElapsed)
                        updateNotification(totalPoints, uptime)
                    }
                }
            }
        }
        
        DevLog.d(TAG, "Mining started")
    }
    
    private fun stopMining() {
        miningJob?.cancel()
        miningJob = null
        
        // Обновляем статус в БД
        serviceScope.launch {
            database.userStatsDao().setMiningState(false, 0)
        }
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        DevLog.i(TAG, "stopMining EXIT totalPoints=$totalPoints")
        DevLog.d(TAG, "Mining stopped. Total points: $totalPoints")
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_mining),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Уведомления о процессе майнинга"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(points: Long, uptime: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_mining_title))
            .setContentText(getString(R.string.notif_mining_text, points, uptime))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
    
    private fun updateNotification(points: Long, uptime: String) {
        val notification = createNotification(points, uptime)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun formatUptime(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, secs)
            else -> String.format("%02d:%02d", minutes, secs)
        }
    }
    
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SeekerMiner::MiningWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 минут
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        miningJob?.cancel()
        serviceScope.cancel()
        
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        
        DevLog.d(TAG, "MiningService destroyed")
    }
}
