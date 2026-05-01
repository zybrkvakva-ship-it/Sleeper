package com.sleeper.core.di

import com.sleeper.core.data.repository.MiningRepository
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * Hilt module that provides repository dependencies.
 * The repository is a singleton because it holds the Room database and network clients.
 */
@Module
object RepositoryModule {
    @Provides
    @Singleton
    fun provideMiningRepository(
        // The repository needs the database and network clients.
        // Those are provided by other modules (NetworkModule, DatabaseModule, etc.).
        // For now we give a no‑arg constructor; real implementation will receive
        // AppDatabase, WalletManager, EnergyManager, MiningBackendApi, etc.
        miningRepository: com.sleeper.core.data.repository.MiningRepository = com.sleeper.core.data.repository.MiningRepository()
    ) {
        // If your constructor requires parameters, expose them via @Provides methods.
        // This placeholder just returns the default instance you created earlier.
        // No additional @Provides code needed here.
    }
}