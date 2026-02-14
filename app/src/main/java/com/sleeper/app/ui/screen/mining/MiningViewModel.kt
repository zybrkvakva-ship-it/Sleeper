package com.sleeper.app.ui.screen.mining

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sleeper.app.data.local.AppDatabase
import com.sleeper.app.data.network.SolanaCluster
import com.sleeper.app.data.network.SolanaRpcClient
import com.sleeper.app.BuildConfig
import com.sleeper.app.data.local.PendingSessionEntity
import com.sleeper.app.data.repository.MiningRepository
import com.sleeper.app.domain.manager.EnergyManager
import com.sleeper.app.domain.manager.StorageManager
import com.sleeper.app.domain.manager.WalletManager
import com.sleeper.app.security.DeviceVerifier
import com.sleeper.app.security.TokenVerifier
import com.sleeper.app.service.MiningService
import com.sleeper.app.utils.DevLog
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MiningUiState(
    val isVerifying: Boolean = true,
    val isDeviceValid: Boolean = false,
    val errorMessage: String = "",
    val isMining: Boolean = false,
    val energyCurrent: Int = 25_200,
    val energyMax: Int = 25_200,
    val showLowEnergyWarning: Boolean = false,  // Day 3: уведомление при энергии < 20%
    val pointsBalance: Long = 0,
    val currentBlock: Int = 1247,   // из БД
    val difficulty: Double = 12.4,  // с бэкенда при USE_REAL_LEADERBOARD
    val onlineUsers: Int = 2847,   // с бэкенда при USE_REAL_LEADERBOARD
    val isDemoNetworkStats: Boolean = true,  // true = заглушки, показываем «Демо» в UI
    val pointsPerSecond: Double = 0.0,
    val uptimeMinutes: Long = 0,
    val storageMB: Int = 100,
    val storageMultiplier: Double = 1.0,
    val deviceFingerprint: String = "",
    val skrUsername: String? = null,  // .skr token username
    val isTokenVerified: Boolean = false,  // Верифицирован ли .skr token
    val walletConnected: Boolean = false,  // Подключён ли wallet
    val stakedSkrHuman: Double = 0.0,     // Стейк SKR (для множителя и отображения)
    val stakeMultiplier: Double = 1.0,    // +X% к награде за стейк
    val hasGenesisNft: Boolean = false,
    val genesisNftMultiplier: Double = 1.0
)

class MiningViewModel(application: Application) : AndroidViewModel(application) {
    
    private val database = AppDatabase.getInstance(application)
    private val repository = MiningRepository(database)
    private val energyManager = EnergyManager(database.userStatsDao())
    private val storageManager = StorageManager(application)
    private val deviceVerifier = DeviceVerifier(application)
    private val walletManager = WalletManager(application)
    private val tokenVerifier = TokenVerifier(walletManager)
    private val rpcClient = SolanaRpcClient(SolanaCluster.MAINNET_HELIUS)
    
    private val _uiState = MutableStateFlow(MiningUiState())
    val uiState: StateFlow<MiningUiState> = _uiState.asStateFlow()
    
    companion object {
        private const val TAG = "MiningViewModel"
    }
    
    init {
        verifyDevice()
        observeUserStats()
        startEnergyRestoration()
        checkWalletConnection()
        syncWithBackend()
    }
    
    private fun verifyDevice() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isVerifying = true)
            
            // Инициализируем БД
            repository.initializeDefaultData()
            
            // Проверяем устройство
            val result = deviceVerifier.verifyDevice()
            
            if (result.isValid) {
                // Сохраняем fingerprint
                database.userStatsDao().getUserStats()?.let { stats ->
                    database.userStatsDao().update(
                        stats.copy(deviceFingerprint = result.fingerprint)
                    )
                }
                
                // Проверяем/создаём storage и синхронизируем БД с реальным объёмом
                val currentPlots = storageManager.getAllocatedPlotsCount()
                if (currentPlots == 0) {
                    storageManager.allocateStorage(1) // По умолчанию 1 плот (100MB)
                }
                val realStorageMB = storageManager.getTotalStorageMB()
                val realMultiplier = storageManager.calculateStorageMultiplier(realStorageMB)
                repository.syncStorage(realStorageMB, realMultiplier)
                
                _uiState.value = _uiState.value.copy(
                    isVerifying = false,
                    isDeviceValid = true,
                    deviceFingerprint = result.fingerprint,
                    isDemoNetworkStats = !BuildConfig.USE_REAL_LEADERBOARD
                )
                
                DevLog.d(TAG, "Device verified: ${result.fingerprint}")
            } else {
                _uiState.value = _uiState.value.copy(
                    isVerifying = false,
                    isDeviceValid = false,
                    errorMessage = result.reason,
                    isDemoNetworkStats = !BuildConfig.USE_REAL_LEADERBOARD
                )
                
                DevLog.w(TAG, "Device verification failed: ${result.reason}")
            }
        }
    }
    
    private fun observeUserStats() {
        viewModelScope.launch {
            repository.userStats.collect { stats ->
                stats?.let {
                    val pps = energyManager.getCurrentPointsPerSecond()
                    val energyPercent = if (it.energyMax > 0) it.energyCurrent.toFloat() / it.energyMax else 1f
                    val showLowEnergy = energyPercent < 0.2f && it.energyCurrent > 0  // Day 3: предупреждение при < 20%
                    
                    val stakeMult = energyManager.getStakeMultiplierForDisplay(it.stakedSkrHuman)
                    _uiState.value = _uiState.value.copy(
                        isMining = it.isMining,
                        energyCurrent = it.energyCurrent,
                        energyMax = it.energyMax,
                        showLowEnergyWarning = showLowEnergy,
                        pointsBalance = it.pointsBalance,
                        currentBlock = it.currentBlock,
                        pointsPerSecond = pps,
                        uptimeMinutes = it.uptimeMinutes,
                        storageMB = it.storageMB,
                        storageMultiplier = it.storageMultiplier,
                        stakedSkrHuman = it.stakedSkrHuman,
                        stakeMultiplier = stakeMult,
                        hasGenesisNft = it.hasGenesisNft,
                        genesisNftMultiplier = it.genesisNftMultiplier
                    )
                }
            }
        }
    }
    
    private fun startEnergyRestoration() {
        viewModelScope.launch {
            while (true) {
                delay(60_000) // каждую минуту
                energyManager.restoreEnergy()
            }
        }
    }
    
    /**
     * Проверяет .skr token перед началом майнинга
     */
    fun verifyTokenForMining() {
        viewModelScope.launch {
            val walletAddr = walletManager.getSavedWalletAddress()
            DevLog.d(TAG, "[VERIFY_MINING] ========== verifyTokenForMining() ENTRY ==========")
            DevLog.d(TAG, "[VERIFY_MINING] walletAddress=${walletAddr ?: "null"}")
            _uiState.value = _uiState.value.copy(isVerifying = true)
            
            DevLog.d(TAG, "[VERIFY_MINING] Calling tokenVerifier.verifySkrToken()...")
            val tokenResult = tokenVerifier.verifySkrToken()
            DevLog.d(TAG, "[VERIFY_MINING] verifySkrToken returned: isValid=${tokenResult.isValid} username=${tokenResult.username} reason=${tokenResult.reason}")
            
            if (tokenResult.isValid && tokenResult.username != null) {
                // Token verified - разрешаем майнинг, сохраняем реальный .skr для лидерборда
                walletManager.saveSkrUsername(tokenResult.username)
                _uiState.value = _uiState.value.copy(
                    isVerifying = false,
                    isTokenVerified = true,
                    skrUsername = tokenResult.username
                )
                
                // Сохраняем для аудита
                tokenVerifier.saveVerifiedToken(
                    tokenResult.username,
                    tokenResult.tokenAddress ?: "",
                    walletManager.getSavedWalletAddress() ?: ""
                )
                
                DevLog.i(TAG, "✅ Mining authorized for: ${tokenResult.username}")
            } else {
                walletManager.saveSkrUsername(null)
                _uiState.value = _uiState.value.copy(
                    isVerifying = false,
                    isTokenVerified = false,
                    skrUsername = null,
                    isDeviceValid = false,
                    errorMessage = tokenResult.reason
                )
                DevLog.w(TAG, "❌ Mining blocked: ${tokenResult.reason}")
            }
        }
    }
    
    fun startMining() {
        viewModelScope.launch {
            // Доступ только по .skr; стейк используется только как множитель к награде.
            // 1. Device (Seeker) 2. Wallet (connected) 3. .skr Token (ownership)
            if (!_uiState.value.isTokenVerified) {
                DevLog.w(TAG, "❌ Mining blocked: .skr token not verified. Filter logcat by 'SolanaRpcClient' or 'TokenVerifier' for detection details.")
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Подключите wallet с .skr токеном для майнинга"
                )
                return@launch
            }
            
            if (!energyManager.hasEnoughEnergy()) {
                DevLog.w(TAG, "Not enough energy to start mining")
                return@launch
            }
            
            // Запускаем MiningService
            val intent = Intent(getApplication(), MiningService::class.java).apply {
                action = MiningService.ACTION_START_MINING
            }
            getApplication<Application>().startService(intent)
            
            DevLog.i(TAG, "✅ Mining started for user: ${_uiState.value.skrUsername}")
        }
    }
    
    fun stopMining() {
        viewModelScope.launch {
            // Сохраняем сессию в очередь для отправки на бэкенд (при появлении сети)
            val stats = repository.getUserStats()
            val wallet = walletManager.getSavedWalletAddress()
            val skr = walletManager.getSavedSkrUsername()
            if (stats != null && !wallet.isNullOrBlank() && stats.miningStartTime > 0L) {
                val stakeMult = energyManager.getStakeMultiplierForDisplay(stats.stakedSkrHuman)
                val paidMult = energyManager.getPaidBoostMultiplierForDisplay(stats.activeSkrBoostId, stats.activeSkrBoostEndsAt)
                val dailySocialMult = 1.0 + minOf(stats.dailySocialBonusPercent, 0.15)
                val endedAt = System.currentTimeMillis()
                val session = PendingSessionEntity(
                    walletAddress = wallet,
                    skrUsername = skr,
                    uptimeMinutes = stats.uptimeMinutes,
                    storageMB = stats.storageMB,
                    storageMultiplier = stats.storageMultiplier,
                    stakeMultiplier = stakeMult,
                    paidBoostMultiplier = paidMult,
                    dailySocialMultiplier = dailySocialMult,
                    pointsBalance = stats.pointsBalance,
                    sessionStartedAt = stats.miningStartTime,
                    sessionEndedAt = endedAt,
                    deviceFingerprint = stats.deviceFingerprint.ifBlank { null },
                    genesisNftMultiplier = stats.genesisNftMultiplier,
                    activeSkrBoostId = stats.activeSkrBoostId
                )
                repository.enqueuePendingSession(session)
                DevLog.d(TAG, "Session enqueued for backend: ${session.sessionEndedAt - session.sessionStartedAt}ms")
            }
            // Останавливаем MiningService
            val intent = Intent(getApplication(), MiningService::class.java).apply {
                action = MiningService.ACTION_STOP_MINING
            }
            getApplication<Application>().startService(intent)
            DevLog.d(TAG, "Mining stopped")
        }
    }
    
    fun onHumanCheckResponse(passed: Boolean) {
        viewModelScope.launch {
            repository.recordHumanCheckResponse(passed)
            DevLog.d(TAG, "Human check: passed=$passed")
        }
    }
    
    /**
     * Обновляет состояние кошелька и при наличии адреса запускает проверку .skr.
     * Вызывать при каждом показе экрана Майнинга (после подключения кошелька на вкладке Кошелёк).
     */
    fun refreshWalletAndSkr() {
        checkWalletConnection()
    }

    /**
     * Проверяет подключение wallet при старте и при наличии кошелька — автоматически
     * запускает проверку .skr (диагностика в логах TokenVerifier / SolanaRpcClient).
     */
    private fun checkWalletConnection() {
        DevLog.d(TAG, "[WALLET_CHECK] ========== checkWalletConnection() ==========")
        val isConnected = walletManager.isWalletConnected()
        val walletAddress = walletManager.getSavedWalletAddress()
        DevLog.d(TAG, "[WALLET_CHECK] isWalletConnected=$isConnected walletAddress=${walletAddress ?: "null"} full=$walletAddress")
        
        _uiState.value = _uiState.value.copy(
            walletConnected = isConnected
        )
        
        if (isConnected) {
            DevLog.d(TAG, "[WALLET_CHECK] Wallet connected. Верификация .skr только по кнопке «Верифицировать .skr токен» — автозапуск отключён.")
        } else {
            DevLog.d(TAG, "[WALLET_CHECK] No wallet connected")
        }
    }
    
    /**
     * Фоновая проверка .skr без спиннера (для диагностики и предзаполнения username).
     */
    private fun verifySkrTokenInBackground() {
        viewModelScope.launch {
            val addr = walletManager.getSavedWalletAddress()
            DevLog.d(TAG, "[SKR_BG] verifySkrTokenInBackground ENTRY wallet=$addr")
            val tokenResult = tokenVerifier.verifySkrToken()
            DevLog.d(TAG, "[SKR_BG] .skr verification: valid=${tokenResult.isValid} username=${tokenResult.username} reason=${tokenResult.reason} tokenAddress=${tokenResult.tokenAddress}")
            if (tokenResult.isValid && tokenResult.username != null) {
                walletManager.saveSkrUsername(tokenResult.username)
                _uiState.value = _uiState.value.copy(
                    isTokenVerified = true,
                    skrUsername = tokenResult.username
                )
                DevLog.d(TAG, "Auto-verified .skr: ${tokenResult.username}")
                refreshStake(addr!!)
            }
            // Если не найден — не меняем состояние; пользователь может нажать «Верифицировать» и увидеть причину
        }
    }
    
    /** Запрашивает стейк SKR по кошельку и сохраняет в БД + обновляет UI. */
    private suspend fun refreshStake(walletAddress: String) {
        DevLog.i(TAG, "[STAKING] ========== refreshStake ENTRY ==========")
        DevLog.i(TAG, "[STAKING] wallet length=${walletAddress.length} preview=${walletAddress.take(10)}...${walletAddress.takeLast(8)}")
        DevLog.d(TAG, "[STAKING] refreshStake ENTRY wallet=${DevLog.mask(walletAddress)}")
        DevLog.i(TAG, "[STAKING] calling rpcClient.getStakedBalance(wallet)...")
        val balance = rpcClient.getStakedBalance(walletAddress)
        DevLog.i(TAG, "[STAKING] getStakedBalance RETURNED: rawAmount=${balance.rawAmount} humanReadable=${balance.humanReadable}")
        DevLog.d(TAG, "[STAKING] refreshStake getStakedBalance returned raw=${balance.rawAmount} human=${balance.humanReadable}")
        DevLog.i(TAG, "[STAKING] calling repository.syncStake(raw=${balance.rawAmount}, human=${balance.humanReadable})")
        repository.syncStake(balance.rawAmount, balance.humanReadable)
        val mult = energyManager.getStakeMultiplierForDisplay(balance.humanReadable)
        DevLog.i(TAG, "[STAKING] stakeMultiplier=$mult (from human=$balance.humanReadable)")
        _uiState.value = _uiState.value.copy(
            stakedSkrHuman = balance.humanReadable,
            stakeMultiplier = mult
        )
        DevLog.i(TAG, "[STAKING] UI state updated: stakedSkrHuman=${balance.humanReadable} stakeMultiplier=$mult")
        DevLog.d(TAG, "[STAKING] refreshed: raw=${balance.rawAmount} human=${balance.humanReadable} mult=$mult")
        DevLog.i(TAG, "[STAKING] ========== refreshStake EXIT ==========")
        DevLog.d(TAG, "[STAKING] refreshStake EXIT stakedSkrHuman=${balance.humanReadable} stakeMultiplier=$mult")
    }
    
    /** Вызов при открытии экрана майнинга: обновить стейк раз в 5 мин. */
    fun refreshStakeIfNeeded() {
        viewModelScope.launch {
            DevLog.d(TAG, "[STAKING] refreshStakeIfNeeded ENTRY")
            val addr = walletManager.getSavedWalletAddress()
            if (addr == null) {
                DevLog.d(TAG, "[STAKING] refreshStakeIfNeeded no wallet skip")
                return@launch
            }
            DevLog.d(TAG, "[STAKING] refreshStakeIfNeeded wallet present, calling refreshStake")
            refreshStake(addr)
            DevLog.d(TAG, "[STAKING] refreshStakeIfNeeded EXIT")
        }
    }

    /**
     * Синхронизация с бэкендом: отправка очереди сессий и запрос баланса.
     * Вызывается при старте и может вызываться при открытии кошелька/экрана майнинга.
     */
    fun syncWithBackend() {
        viewModelScope.launch {
            DevLog.d(TAG, "syncWithBackend ENTRY")
            val sent = repository.syncPendingSessions()
            DevLog.d(TAG, "syncWithBackend syncPendingSessions sent=$sent")
            if (sent > 0) DevLog.d(TAG, "Backend: sent $sent pending session(s)")
            walletManager.getSavedWalletAddress()?.let { addr ->
                val balanceSynced = repository.syncBalanceFromServer(addr)
                DevLog.d(TAG, "syncWithBackend syncBalanceFromServer addr=${DevLog.mask(addr)} ok=$balanceSynced")
                if (balanceSynced) DevLog.d(TAG, "Backend: balance synced from server")
            } ?: DevLog.d(TAG, "syncWithBackend no wallet, skip balance sync")
        }
    }
}
