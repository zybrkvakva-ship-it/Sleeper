package com.sleeper.core.di

import com.sleeper.core.data.network.MiningBackendApi
import com.sleeper.core.data.network.SolanaRpcClient
import com.sleeper.core.data.network.SplTransferBuilder
import dagger.Module
import dagger.Provides
import javax.inject.Singleton
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Hilt module that provides network‑related dependencies.
 * All objects are singletons and use Moshi for JSON parsing.
 */
@Module
object NetworkModule {
    private const val BASE_URL = "https://api.seekerminer.example/"

    @Provides
    @Singleton
    fun provideOkHttpClient(): okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .build()

    @Provides
    @Singleton
    fun provideMoshi(): com.squareup.moshi.Moshi = com.squareup.moshi.Moshi.Builder()
        .build()

    @Provides
    @Singleton
    fun provideMiningBackendApi(
        okHttpClient: okhttp3.OkHttpClient,
        moshi: com.squareup.moshi.Moshi
    ): MiningBackendApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(MiningBackendApi::class.java)

    @Provides
    @Singleton
    fun provideSolanaRpcClient(): SolanaRpcClient = SolanaRpcClient()

    @Provides
    @Singleton
    fun provideSplTransferBuilder(
        miningBackendApi: MiningBackendApi
    ): SplTransferBuilder = SplTransferBuilder(miningBackendApi)
}