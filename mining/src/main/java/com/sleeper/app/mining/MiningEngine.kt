package com.sleeper.app.mining

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.TimeUnit
import com.sleeper.app.domain.manager.EnergyManager
import com.sleeper.app.data.local.AppDatabase

/**
 * Central façade for all mining‑related operations.
 *
 * * Provides a single entry point for scheduling periodic mining work.
 * * Lazily creates an [EnergyManager] that works with the local [UserStatsDao].
 * * All background execution is delegated to WorkManager – the same worker
 *   (`MiningWorker`) is reused for every cycle.
 *
 * Usage:
 * ```kotlin
 * MiningEngine.init(this)               // usually in your Application subclass
 * MiningEngine.scheduleNextCycle()      // enqueue a 15‑minute periodic work
 * ```
 */
object MiningEngine {

    private var appContext: Context? = null

    /**
     * Initialise the engine with an [Application] (or any Context).
     * Must be called before any other method.
     */
    fun init(context: Context) {
        require(context != null) { "Context cannot be null" }
        appContext = context.applicationContext
    }

    /**
     * Lazily creates an [EnergyManager] that talks to the DAO.
     */
    private fun energyManager(): EnergyManager {
        val db = AppDatabase.getInstance(appContext!!)
        return EnergyManager(db.userStatsDao())
    }

    /**
     * Schedules a periodic mining job (15 minutes, battery‑aware).
     *
     * The job will only run when the device is charging and the battery is not low.
     * If a job with the same name already exists it will be kept (`KEEP` policy).
     */
    fun scheduleNextCycle() {
        val em = energyManager()
        // Optional: you could early‑exit if there is no energy left.
        // if (!em.hasEnoughEnergy()) return

        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<com.sleeper.app.mining.worker.MiningWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            "MiningWork",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}