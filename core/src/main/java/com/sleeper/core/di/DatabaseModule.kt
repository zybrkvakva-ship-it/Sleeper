package com.sleeper.core.di

import com.sleeper.core.data.local.AppDatabase
import com.sleeper.core.data.local.dao.EnergyManagerDao
import com.sleeper.core.data.local.dao.WalletManagerDao
import com.sleeper.core.data.repository.MiningRepository
import com.sleeper.core.domain.manager.EnergyManager
import com.sleeper.core.domain.manager.WalletManager
import com.sleeper.core.data.network.MiningBackendApi
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 *Provides database‑related objects and the concrete MiningRepository.
 *All instances are singletons for the whole process.
 */
@Module
object DatabaseModule {
    private const val DB_NAME = "seeker_miner.db"

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext ctx: android.content.Context): AppDatabase =
        androidx.room.Room.databaseBuilder(ctx, AppDatabase::class.java, DB_NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideEnergyManagerDao(db: AppDatabase) = db.energyManagerDao()

    @Provides
    @Singleton
    fun provideWalletManagerDao(db: AppDatabase) = db.walletManagerDao()

    @Provides
    @Singleton
    fun provideMiningRepository(
        db: AppDatabase,
        api: MiningBackendApi,
        walletMgr: WalletManager,
        energyMgr: EnergyManager
    ): MiningRepository = MiningRepository(db, api, walletMgr, energyMgr)
}