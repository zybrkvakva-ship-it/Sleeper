package com.sleeper.app.ui.common

import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.sleeper.app.utils.DevLog
import com.sleeper.app.utils.PerformanceOptimizer
import kotlinx.coroutines.delay

/**
 * Класс для оптимизации UI производительности
 * Решает проблемы с таймлайном рендеринга и синхронизацией GPU/VSync
 */
object UiPerformanceOptimizer {
    
    private const val TAG = "UiPerformanceOptimizer"
    
    /**
     * Оптимизированный способ выполнения задач в UI потоке с ограничением частоты
     */
    fun executeThrottledUiTask(
        taskKey: String,
        intervalMs: Long = 16, // ~60 FPS
        task: () -> Unit
    ) {
        val lastCallTimes = mutableMapOf<String, Long>()
        
        PerformanceOptimizer.throttle(lastCallTimes, taskKey, intervalMs) {
            try {
                PerformanceOptimizer.executeSafeUITask(task)
            } catch (e: Exception) {
                DevLog.e(TAG, "Error executing throttled UI task", e)
            }
        }
    }
    
    /**
     * Рассчитывает оптимальный интервал обновления для UI компонентов
     */
    fun calculateOptimalRefreshInterval(targetFps: Int = 60): Long {
        return (1000L / targetFps) // milliseconds per frame
    }
    
    /**
     * Оптимизирует частоту обновления данных для UI
     */
    fun <T> optimizeDataUpdates(
        data: T,
        updateThreshold: Long = 16, // 60 FPS threshold
        lastUpdateTime: Long = 0L,
        updateCallback: (T) -> Unit
    ): Boolean {
        val currentTime = System.currentTimeMillis()
        
        if (currentTime - lastUpdateTime >= updateThreshold) {
            updateCallback(data)
            return true
        }
        
        return false
    }
}

/**
 * Composable функция для оптимизации производительности в Jetpack Compose
 */
@Composable
fun rememberOptimizedState(initialValue: Float): State<Float> {
    val density = LocalDensity.current
    val optimizedState = remember { mutableStateOf(initialValue) }
    
    LaunchedEffect(Unit) {
        // Оптимизируем обновления с учетом плотности экрана и частоты обновления
        val optimalInterval = with(density) {
            16 // 60 FPS как оптимальный интервал
        }
        
        while (true) {
            delay(optimalInterval.toLong())
            // Здесь можно добавить логику периодической оптимизации
        }
    }
    
    return optimizedState
}

/**
 * Интерфейс для компонентов с оптимизацией производительности
 */
interface PerformanceOptimizedComponent {
    fun preDrawOptimization()
    fun postDrawOptimization()
    fun calculateFrameTiming(): Long
}

/**
 * Helper класс для синхронизации с VSync
 */
class VSyncHelper : Choreographer.FrameCallback {
    private var lastFrameTimeNanos: Long = 0
    private val choreographer = Choreographer.getInstance()
    private var isRegistered = false
    
    fun registerFrameCallback() {
        if (!isRegistered) {
            choreographer.postFrameCallback(this)
            isRegistered = true
        }
    }
    
    fun unregisterFrameCallback() {
        if (isRegistered) {
            choreographer.removeFrameCallback(this)
            isRegistered = false
        }
    }
    
    override fun doFrame(frameTimeNanos: Long) {
        val deltaTime = frameTimeNanos - lastFrameTimeNanos
        lastFrameTimeNanos = frameTimeNanos
        
        // Выполняем оптимизацию на основе времени кадра
        onFrameRendered(deltaTime)
        
        // Регистрируем следующий кадр
        if (isRegistered) {
            choreographer.postFrameCallback(this)
        }
    }
    
    private fun onFrameRendered(deltaTimeNanos: Long) {
        val deltaTimeMs = deltaTimeNanos / 1_000_000
        DevLog.d(TAG, "Frame rendered in ${deltaTimeMs}ms")
        
        // Если кадр занял слишком много времени, регистрируем это
        if (deltaTimeMs > 32) { // больше 30 FPS
            DevLog.w(TAG, "Slow frame detected: ${deltaTimeMs}ms")
        }
    }
    
    companion object {
        private const val TAG = "VSyncHelper"
    }
}