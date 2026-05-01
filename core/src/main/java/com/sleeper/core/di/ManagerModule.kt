package com.sleeper.core.di

import com.sleeper.core.domain.manager.EnergyManager
import com.sleeper.core.domain.manager.WalletManager
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * Hilt module that provides core‑level managers.
 * All provided instances are singletons for the whole app.
 */
@Module
object ManagerModule {
    @Provides
    @Singleton
    fun provideEnergyManager(): EnergyManager = EnergyManager()
    
    @Provides
    @Singleton
    fun provideWalletManager(): WalletManager = WalletManager()
}