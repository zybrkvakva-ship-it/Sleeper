package com.sleeper.app.ui.screen.mining

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sleeper.app.data.local.AppDatabase
import com.sleeper.app.data.network.SolanaCluster
import com.sleeper.app.data.network.SolanaRpcClient
import com.sleeper.app.data.network.WebSocketManager
import com.sleeper.app.data.network.deriveWsUrl
import com.sleeper.app.BuildConfig
import com.sleeper.app.data.local.PendingSessionEntity
import com.sleeper.app.data.repository.MiningRepository
import com.sleeper.app.domain.manager.EnergyManager
import com.sleeper.app.domain.manager.WalletManager
import com.sleeper.app.security.DeviceVerifier
import com.sleeper.app.security.TokenVerifier
import com.sleeper.app.service.MiningService
import com.sleeper.app.utils.DevLog
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class MiningUiState(
    val isVerifying: Boolean = true,
    val isDeviceValid: Boolean = false,
    val errorMessage: String = "",
    val isMining: Boolean = false,
    val energyCurrent: Int = 28_800,
    val energyMax: Int = 28_800,
    val showLowEnergyWarning: Boolean = false,  // Day 3: уведомление при энергии < 20%
    val pointsBalance: Long = 0,
    val currentBlock: Int = 1247,   // из БД
    val difficulty: Double = 12.4,  // с бэкенда при USE_REAL_LEADERBOARD
    val onlineUsers: Int = 2847,   // с бэкенда при USE_REAL_LEADERBOARD
    val isDemoNetworkStats: Boolean = true,  // true = заглушки, показываем «Демо» в UI
    val pointsPerSecond: Double = 0.0,
    val uptimeMinutes: Long = 0,
    val deviceFingerprint: String = "",
    val skrUsername: String? = null,  // .skr token username
    val isTokenVerified: Boolean = false,  // Верифицирован ли .skr token
    val walletConnected: Boolean = false,  // Подключён ли wallet
    val stakedSkrHuman: Double = 0.0,     // Стейк SKR (для множителя и отображения)
    val stakeMultiplier: Double = 1.0,    // +X% к награде за стейк
    val hasGenesisNft: Boolean = false,
    val genesisNftMultiplier: Double = 1.0,
    val pendingSessionsCount: Int = 0,   // сессии ожидающие синхронизации с бэкендом
    // Acceleration Mining tier
    val accelerationTier: String = "Genesis",
    val seasonWeeks: Int = 16,
    val seasonWeeksRemaining: Int = 16,
    val nightPoolPerNight: Long = 0L,
    val seasonNumber: Int = 1,
)

class MiningViewModel(application: Application) : AndroidViewModel(application) {
    
    private val database = AppDatabase.getInstance(application)
    private val repository = MiningRepository(database)
    private val energyManager = EnergyManager(database.userStatsDao())
    private val deviceVerifier = DeviceVerifier(application)
    private val walletManager = WalletManager(application)
    private val tokenVerifier = TokenVerifier(walletManager)
    private val rpcClient = SolanaRpcClient(SolanaCluster.MAINNET_HELIUS)
    private val wsManager = WebSocketManager(deriveWsUrl(BuildConfig.API_BASE_URL))

    private val _uiState = MutableStateFlow(MiningUiState())
    val uiState: StateFlow<MiningUiState> = _uiState.asStateFlow()
    
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        DevLog.e(TAG, "Coroutine error: ${e.message}", e)
        _uiState.value = _uiState.value.copy(
            isVerifying = false,
            errorMessage = "Startup error: ${e.javaClass.simpleName}"
        )
    }

    companion object {
        private const val TAG = "MiningViewModel"
        private const val SKR_PREFS = "skr_verification_cache"
        private const val KEY_SKR_WALLET = "wallet"
        private const val KEY_SKR_USERNAME = "username"
        private const val KEY_SKR_TOKEN_ADDRESS = "token_address"
        private const val KEY_SKR_VERIFIED_AT = "verified_at"
        private const val SKR_CACHE_TTL_MS = 12 * 60 * 60 * 1000L // 12 часов
    }

    init {
        verifyDevice()
        observeUserStats()
        observePendingSessions()
        observeNetworkStats()
        startEnergyRestoration()
        startNightUpdateLoop()
        checkWalletConnection()
        syncWithBackend()
        wsManager.connect()
    }

    override fun onCleared() {
        super.onCleared()
        wsManager.destroy()
    }

    private fun observeNetworkStats() {
        wsManager.networkStats
            .onEach { stats ->
                if (stats.onlineMiners > 0) {
                    _uiState.value = _uiState.value.copy(
                        onlineUsers = stats.onlineMiners,
                        isDemoNetworkStats = false
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun observePendingSessions() {
        database.pendingSessionDao().getAllPendingFlow()
            .onEach { list ->
                _uiState.value = _uiState.value.copy(pendingSessionsCount = list.size)
            }
            .launchIn(viewModelScope)
    }
    
    private fun verifyDevice() {
        viewModelScope.launch(exceptionHandler) {
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
        viewModelScope.launch(exceptionHandler) {
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
        viewModelScope.launch(exceptionHandler) {
            while (true) {
                delay(60_000) // каждую минуту
                energyManager.restoreEnergy()
            }
        }
    }

    /** Раз в 30 секунд отправляет night:update в WebSocket во время активной сессии. */
    private fun startNightUpdateLoop() {
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                val sessionId = currentSessionId ?: continue
                if (!_uiState.value.isMining) continue
                val wallet = walletManager.getSavedWalletAddress() ?: continue
                val stats = repository.getUserStats() ?: continue
                wsManager.sendNightUpdate(
                    walletAddress = wallet,
                    sessionId = sessionId,
                    uptimeSeconds = (stats.uptimeMinutes * 60).toInt(),
                    humanChecksPassed = stats.humanChecksPassed,
                    humanChecksFailed = stats.humanChecksFailed,
                    socialBoostPercent = stats.dailySocialBonusPercent
                )
            }
        }
    }
    
    /**
     * Проверяет .skr token перед началом майнинга
     */
    fun verifyTokenForMining() {
        viewModelScope.launch {
            val walletAddr = walletManager.getSavedWalletAddress()
            DevLog.d(TAG, "[VERIFY_MINING] verifyTokenForMining ENTRY")
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

                // Кэшируем результат — следующий запуск будет мгновенным
                val addr = walletManager.getSavedWalletAddress()
                if (addr != null) saveSkrVerificationCache(addr, tokenResult.username, tokenResult.tokenAddress)

                // Сохраняем для аудита
                tokenVerifier.saveVerifiedToken(
                    tokenResult.username,
                    tokenResult.tokenAddress ?: "",
                    walletManager.getSavedWalletAddress() ?: ""
                )
                
                DevLog.i(TAG, "✅ Mining authorized for: ${tokenResult.username}")
            } else {
                walletManager.saveSkrUsername(null)
                val errorMsg = when (tokenResult.reasonCode) {
                    "NO_WALLET" -> getApplication<Application>().getString(com.sleeper.app.R.string.wallet_not_connected)
                    "NO_SKR_TOKEN" -> getApplication<Application>().getString(com.sleeper.app.R.string.skr_required_hint)
                    else -> tokenResult.reason
                }
                _uiState.value = _uiState.value.copy(
                    isVerifying = false,
                    isTokenVerified = false,
                    skrUsername = null,
                    isDeviceValid = false,
                    errorMessage = errorMsg
                )
                DevLog.w(TAG, "❌ Mining blocked: $errorMsg")
            }
        }
    }
    
    /** ID текущей ночной сессии, генерируется при startMining(). */
    private var currentSessionId: String? = null

    private val isMiningStarting = java.util.concurrent.atomic.AtomicBoolean(false)

    fun startMining() {
        if (!isMiningStarting.compareAndSet(false, true)) {
            DevLog.w(TAG, "startMining: duplicate call ignored (already starting)")
            return
        }
        viewModelScope.launch {
            try {
                // Доступ только по .skr; стейк используется только как множитель к награде.
                // 1. Device (Seeker) 2. Wallet (connected) 3. .skr Token (ownership)
                if (!_uiState.value.isTokenVerified) {
                    DevLog.w(TAG, "❌ Mining blocked: .skr token not verified.")
                    _uiState.value = _uiState.value.copy(
                        errorMessage = getApplication<Application>().getString(com.sleeper.app.R.string.mining_connect_wallet_skr)
                    )
                    return@launch
                }

                if (!energyManager.hasEnoughEnergy()) {
                    DevLog.w(TAG, "Not enough energy to start mining")
                    return@launch
                }

                // Генерируем ID сессии и регистрируем в WebSocket
                val sessionId = java.util.UUID.randomUUID().toString()
                currentSessionId = sessionId
                walletManager.getSavedWalletAddress()?.let { addr ->
                    wsManager.registerMiningSession(addr, sessionId)
                }

                // Запускаем MiningService
                val intent = Intent(getApplication(), MiningService::class.java).apply {
                    action = MiningService.ACTION_START_MINING
                }
                getApplication<Application>().startService(intent)

                DevLog.i(TAG, "✅ Mining started for user: ${_uiState.value.skrUsername} sessionId=${sessionId.take(8)}")
            } finally {
                isMiningStarting.set(false)
            }
        }
    }
    
    fun stopMining() {
        viewModelScope.launch {
            // Сохраняем сессию в очередь для отправки на бэкенд (при появлении сети)
            val stats = repository.getUserStats()
            val wallet = walletManager.getSavedWalletAddress()
            val backendAuthToken = walletManager.getSavedBackendAuthToken()
            val skr = walletManager.getSavedSkrUsername()
            if (stats != null && !wallet.isNullOrBlank() && stats.miningStartTime > 0L) {
                val stakeMult = energyManager.getStakeMultiplierForDisplay(stats.stakedSkrHuman)
                val paidMult = energyManager.getPaidBoostMultiplierForDisplay(stats.activeSkrBoostId, stats.activeSkrBoostEndsAt)
                val endedAt = System.currentTimeMillis()
                val durationSeconds = ((endedAt - stats.miningStartTime) / 1000L).coerceAtLeast(0L)
                val dailyBonus = minOf(stats.dailySocialBonusPercent, 0.15)
                val dailySocialMult = 1.0 + dailyBonus
                val humanMultiplier = calculateHumanCheckMultiplier(stats.humanChecksPassed, stats.humanChecksFailed)
                val pointsPerSecond = energyManager.getCurrentPointsPerSecond()
                val session = PendingSessionEntity(
                    walletAddress = wallet,
                    authToken = backendAuthToken,
                    skrUsername = skr,
                    uptimeMinutes = stats.uptimeMinutes,
                    durationSeconds = durationSeconds,
                    stakedSkrHuman = stats.stakedSkrHuman,
                    stakeMultiplier = stakeMult,
                    humanChecksPassed = stats.humanChecksPassed,
                    humanChecksFailed = stats.humanChecksFailed,
                    humanMultiplier = humanMultiplier,
                    dailySocialBonusPercent = dailyBonus,
                    paidBoostMultiplier = paidMult,
                    dailySocialMultiplier = dailySocialMult,
                    pointsPerSecond = pointsPerSecond,
                    pointsBalance = stats.pointsBalance,
                    sessionStartedAt = stats.miningStartTime,
                    sessionEndedAt = endedAt,
                    deviceFingerprint = stats.deviceFingerprint.ifBlank { null },
                    hasGenesisNft = stats.hasGenesisNft,
                    genesisNftMultiplier = stats.genesisNftMultiplier,
                    activeSkrBoostId = stats.activeSkrBoostId
                )
                repository.enqueuePendingSession(session)
                DevLog.d(TAG, "Session enqueued for backend: ${session.sessionEndedAt - session.sessionStartedAt}ms")
            }
            currentSessionId = null
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
        DevLog.d(TAG, "[WALLET_CHECK] checkWalletConnection")
        val isConnected = walletManager.isWalletConnected()
        val walletAddress = walletManager.getSavedWalletAddress()
        DevLog.d(TAG, "[WALLET_CHECK] isWalletConnected=$isConnected walletAddress=${walletAddress ?: "null"} full=$walletAddress")
        
        _uiState.value = _uiState.value.copy(
            walletConnected = isConnected
        )
        
        if (isConnected && walletAddress != null) {
            DevLog.d(TAG, "[WALLET_CHECK] Wallet connected. Проверяем кэш SKR...")
            loadCachedSkrVerification(walletAddress)
        } else {
            DevLog.d(TAG, "[WALLET_CHECK] No wallet connected")
        }
    }

    /**
     * Загружает кэшированный результат верификации .skr из SharedPreferences.
     * Если кэш свежий (< 12ч) и кошелёк совпадает — применяем без RPC вызова.
     */
    private fun loadCachedSkrVerification(walletAddress: String) {
        val prefs = getApplication<Application>().getSharedPreferences(SKR_PREFS, Context.MODE_PRIVATE)
        val cachedWallet = prefs.getString(KEY_SKR_WALLET, null)
        val cachedUsername = prefs.getString(KEY_SKR_USERNAME, null)
        val cachedTokenAddress = prefs.getString(KEY_SKR_TOKEN_ADDRESS, null)
        val cachedAt = prefs.getLong(KEY_SKR_VERIFIED_AT, 0L)

        val age = System.currentTimeMillis() - cachedAt
        if (cachedUsername != null && cachedWallet == walletAddress && age < SKR_CACHE_TTL_MS) {
            DevLog.d(TAG, "[SKR_CACHE] HIT: username=$cachedUsername age=${age / 1000}s")
            walletManager.saveSkrUsername(cachedUsername)
            _uiState.value = _uiState.value.copy(
                isTokenVerified = true,
                skrUsername = cachedUsername
            )
        } else {
            DevLog.d(TAG, "[SKR_CACHE] MISS: cachedWallet=${cachedWallet?.take(8)} age=${age / 1000}s")
        }
    }

    /** Сохраняет верифицированный .skr в кэш (вызывать после успешной верификации). */
    private fun saveSkrVerificationCache(walletAddress: String, username: String, tokenAddress: String?) {
        getApplication<Application>().getSharedPreferences(SKR_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SKR_WALLET, walletAddress)
            .putString(KEY_SKR_USERNAME, username)
            .putString(KEY_SKR_TOKEN_ADDRESS, tokenAddress ?: "")
            .putLong(KEY_SKR_VERIFIED_AT, System.currentTimeMillis())
            .apply()
        DevLog.d(TAG, "[SKR_CACHE] SAVED: username=$username")
    }

    /** Сбрасывает кэш SKR верификации (при смене кошелька или явном сбросе). */
    fun clearSkrCache() {
        getApplication<Application>().getSharedPreferences(SKR_PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
        DevLog.d(TAG, "[SKR_CACHE] CLEARED")
    }
    
    /**
     * Фоновая проверка .skr без спиннера (для диагностики и предзаполнения username).
     * .skr и стейк запрашиваются параллельно — время = max(domains, staking), а не сумма.
     */
    private fun verifySkrTokenInBackground() {
        viewModelScope.launch {
            val addr = walletManager.getSavedWalletAddress() ?: return@launch
            DevLog.d(TAG, "[SKR_BG] verifySkrTokenInBackground ENTRY wallet=$addr")
            coroutineScope {
                val skrDeferred = async { tokenVerifier.verifySkrToken() }
                val stakeDeferred = async { refreshStake(addr) }
                val tokenResult = skrDeferred.await()
                DevLog.d(TAG, "[SKR_BG] .skr verification: valid=${tokenResult.isValid} username=${tokenResult.username} reason=${tokenResult.reason} tokenAddress=${tokenResult.tokenAddress}")
                if (tokenResult.isValid && tokenResult.username != null) {
                    walletManager.saveSkrUsername(tokenResult.username)
                    _uiState.value = _uiState.value.copy(
                        isTokenVerified = true,
                        skrUsername = tokenResult.username
                    )
                    DevLog.d(TAG, "Auto-verified .skr: ${tokenResult.username}")
                }
                stakeDeferred.await()
            }
        }
    }
    
    /** Запрашивает стейк SKR по кошельку и сохраняет в БД + обновляет UI. */
    private suspend fun refreshStake(walletAddress: String) {
        DevLog.i(TAG, "[STAKING] refreshStake ENTRY")
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
        DevLog.i(TAG, "[STAKING] refreshStake EXIT")
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
        viewModelScope.launch(exceptionHandler) {
            DevLog.d(TAG, "syncWithBackend ENTRY")
            val sent = repository.syncPendingSessions()
            DevLog.d(TAG, "syncWithBackend syncPendingSessions sent=$sent")
            if (sent > 0) DevLog.d(TAG, "Backend: sent $sent pending session(s)")
            walletManager.getSavedWalletAddress()?.let { addr ->
                val balanceSynced = repository.syncBalanceFromServer(addr)
                DevLog.d(TAG, "syncWithBackend syncBalanceFromServer addr=${DevLog.mask(addr)} ok=$balanceSynced")
                if (balanceSynced) DevLog.d(TAG, "Backend: balance synced from server")
                repository.syncUserProfile(addr, walletManager)
            } ?: DevLog.d(TAG, "syncWithBackend no wallet, skip balance sync")
            // Refresh acceleration tier (no auth required)
            refreshSeasonTier()
        }
    }

    private fun refreshSeasonTier() {
        viewModelScope.launch {
            repository.getBackendApi().getSeasonTier()
                .onSuccess { tier ->
                    _uiState.value = _uiState.value.copy(
                        accelerationTier = tier.tier,
                        seasonWeeks = tier.seasonWeeks,
                        seasonWeeksRemaining = tier.weeksRemaining,
                        nightPoolPerNight = tier.poolPerNight,
                        onlineUsers = if (tier.activeDevices > 0) tier.activeDevices else _uiState.value.onlineUsers,
                        isDemoNetworkStats = false,
                        seasonNumber = tier.seasonNumber,
                    )
                }
                .onFailure { DevLog.w(TAG, "getSeasonTier failed: ${it.message}") }
        }
    }

    private fun calculateHumanCheckMultiplier(passed: Int, failed: Int): Double {
        val total = passed + failed
        if (total == 0) return 1.0
        val passRate = passed.toDouble() / total
        return 0.5 + passRate * 0.5
    }
}
