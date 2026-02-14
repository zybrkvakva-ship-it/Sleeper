package com.seekerminer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.seekerminer.app.utils.DevLog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.seekerminer.app.data.local.AppDatabase
import com.seekerminer.app.service.HumanCheckWorker
import com.seekerminer.app.ui.navigation.MainNavigation
import com.seekerminer.app.ui.theme.SeekerMinerTheme
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

// CompositionLocal для передачи ActivityResultSender в Composables
val LocalActivityResultSender = staticCompositionLocalOf<ActivityResultSender> {
    error("ActivityResultSender not provided")
}

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    // Создаём ActivityResultSender один раз в onCreate (до STARTED)
    private lateinit var activityResultSender: ActivityResultSender
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        DevLog.d(TAG, "Notification permission: $isGranted")
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Создаём ActivityResultSender ДО setContent (до STARTED состояния)
        activityResultSender = ActivityResultSender(this)
        
        // Запрашиваем разрешения
        requestNotificationPermission()
        
        // Настраиваем periodic human checks
        setupHumanCheckWorker()
        
        // Проверяем intent на human check
        handleHumanCheck(intent)
        
        setContent {
            SeekerMinerTheme {
                CompositionLocalProvider(LocalActivityResultSender provides activityResultSender) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainNavigation()
                    }
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleHumanCheck(it) }
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    DevLog.d(TAG, "Notification permission already granted")
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
    
    private fun setupHumanCheckWorker() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .build()
        
        val humanCheckWork = PeriodicWorkRequestBuilder<HumanCheckWorker>(
            30, TimeUnit.MINUTES // каждые 30 минут
        )
            .setConstraints(constraints)
            .setInitialDelay(30, TimeUnit.MINUTES)
            .build()
        
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            HumanCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            humanCheckWork
        )
        
        DevLog.d(TAG, "Human check worker scheduled")
    }
    
    private fun handleHumanCheck(intent: Intent) {
        val isHumanCheck = intent.getBooleanExtra("human_check", false)
        
        if (isHumanCheck) {
            lifecycleScope.launch {
                val database = AppDatabase.getInstance(applicationContext)
                database.userStatsDao().recordHumanCheckPassed(System.currentTimeMillis())
                
                DevLog.d(TAG, "Human check confirmed")
            }
        }
    }
}
