package com.sleeper.app.ui.screen.upgrade

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sleeper.app.BuildConfig
import com.sleeper.app.data.local.AppDatabase
import com.sleeper.app.data.local.SkrBoostCatalog
import com.sleeper.app.data.local.SkrBoostItem
import com.sleeper.app.data.network.MiningBackendApi
import com.sleeper.app.data.network.SolanaCluster
import com.sleeper.app.data.network.SolanaRpcClient
import com.sleeper.app.data.network.TxConfirmResult
import com.sleeper.app.data.repository.MiningRepository
import com.sleeper.app.domain.manager.WalletManager
import com.sleeper.app.domain.manager.SplTransferResult
import com.sleeper.app.utils.DevLog
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Purchase state machine
// ---------------------------------------------------------------------------

sealed class PurchaseState {
    object Idle : PurchaseState()
    /** MWA открыт — ждём подпись от пользователя */
    data class Pending(val boostId: String) : PurchaseState()
    /** TX подписана и отправлена — polling подтверждения */
    data class Verifying(val boostId: String) : PurchaseState()
    /** Финальный результат (успех или ошибка) */
    data class Done(val success: Boolean, val message: String) : PurchaseState()
}

// ---------------------------------------------------------------------------

class UpgradeViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "UpgradeViewModel"
    }

    private val repository = MiningRepository(AppDatabase.getInstance(application))
    private val walletManager = WalletManager(application)
    private val rpcClient = SolanaRpcClient(SolanaCluster.MAINNET_HELIUS)
    private val backendApi = MiningBackendApi()

    val userStats = repository.userStats
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val skrBoosts: List<SkrBoostItem> = SkrBoostCatalog.all

    private val _availableSkrRaw = MutableStateFlow(0L)
    val availableSkrRaw: StateFlow<Long> = _availableSkrRaw.asStateFlow()

    private val _purchaseState = MutableStateFlow<PurchaseState>(PurchaseState.Idle)
    val purchaseState: StateFlow<PurchaseState> = _purchaseState.asStateFlow()

    /** Сбросить состояние после показа сообщения Done. */
    fun clearPurchaseState() {
        _purchaseState.value = PurchaseState.Idle
    }

    // Legacy aliases — UpgradeScreen использует их напрямую
    val purchaseMessage: StateFlow<String?> get() = MutableStateFlow(
        (_purchaseState.value as? PurchaseState.Done)?.message
    ).also { /* не используется как flow, только для чтения Done */ }
    val purchaseSuccess: StateFlow<Boolean?> get() = MutableStateFlow(
        (_purchaseState.value as? PurchaseState.Done)?.success
    )
    fun clearPurchaseMessage() = clearPurchaseState()

    fun refreshAvailableSkr() {
        viewModelScope.launch {
            DevLog.d(TAG, "refreshAvailableSkr ENTRY")
            val wallet = walletManager.getSavedWalletAddress() ?: run {
                _availableSkrRaw.value = 0L
                return@launch
            }
            val raw = rpcClient.getAvailableSkrForPurchase(wallet)
            _availableSkrRaw.value = raw
            DevLog.d(TAG, "refreshAvailableSkr wallet=${DevLog.mask(wallet)} availableRaw=$raw")
        }
    }

    fun purchaseSkrBoost(boostId: String, sender: ActivityResultSender?) {
        viewModelScope.launch {
            DevLog.d(TAG, "purchaseSkrBoost ENTRY boostId=$boostId")
            val app = getApplication<Application>()

            // Guard: уже идёт покупка — игнорируем повторный тап
            if (_purchaseState.value is PurchaseState.Pending ||
                _purchaseState.value is PurchaseState.Verifying) {
                DevLog.w(TAG, "purchaseSkrBoost ignored — already in progress")
                return@launch
            }

            val boost = SkrBoostCatalog.get(boostId) ?: run {
                _purchaseState.value = PurchaseState.Done(false,
                    app.getString(com.sleeper.app.R.string.upgrade_boost_not_found))
                return@launch
            }

            // Guard: буст уже активен — не перезаписываем молча
            val stats = repository.getUserStats()
            val now = System.currentTimeMillis()
            if (stats != null && stats.activeSkrBoostId == boostId && stats.activeSkrBoostEndsAt > now) {
                val remaining = formatRemainingTime(stats.activeSkrBoostEndsAt - now)
                _purchaseState.value = PurchaseState.Done(false,
                    "Буст уже активен ещё $remaining. Нажмите ещё раз для продления.")
                return@launch
            }

            // Offline режим (без treasury или без sender)
            val treasury = BuildConfig.BOOST_TREASURY?.trim()?.takeIf { it.isNotEmpty() }
            if (treasury == null || sender == null) {
                DevLog.d(TAG, "purchaseSkrBoost offline mode")
                repository.purchaseSkrBoost(boostId)
                refreshAvailableSkr()
                _purchaseState.value = PurchaseState.Done(true,
                    app.getString(com.sleeper.app.R.string.upgrade_boost_activated_offline))
                return@launch
            }

            // Online режим
            val blockhash = rpcClient.getLatestBlockhash()
            if (blockhash == null) {
                _purchaseState.value = PurchaseState.Done(false,
                    app.getString(com.sleeper.app.R.string.upgrade_blockhash_failed))
                return@launch
            }

            _purchaseState.value = PurchaseState.Pending(boostId)
            DevLog.d(TAG, "purchaseSkrBoost → Pending, calling signAndSendSplTransfers...")

            when (val result = walletManager.signAndSendSplTransfers(
                sender, treasury, listOf(boost.priceSkrRaw), blockhash
            )) {
                is SplTransferResult.Success -> {
                    val sig = result.signatureBase58
                    DevLog.i(TAG, "purchaseSkrBoost TX sent: ${sig.take(20)}... → Verifying")
                    _purchaseState.value = PurchaseState.Verifying(boostId)

                    when (val confirm = rpcClient.waitForConfirmation(sig)) {
                        is TxConfirmResult.Confirmed -> {
                            repository.purchaseSkrBoost(boostId)
                            refreshAvailableSkr()
                            DevLog.i(TAG, "purchaseSkrBoost ✅ confirmed")
                            _purchaseState.value = PurchaseState.Done(true,
                                app.getString(com.sleeper.app.R.string.upgrade_boost_paid_tx, sig.take(16)))
                        }
                        is TxConfirmResult.Timeout -> {
                            // Оптимистично активируем — TX может ещё подтвердиться
                            repository.purchaseSkrBoost(boostId)
                            refreshAvailableSkr()
                            DevLog.w(TAG, "purchaseSkrBoost TX timeout — activated locally")
                            _purchaseState.value = PurchaseState.Done(false,
                                "⚠️ TX не подтверждена за 60с. Буст активирован локально.")
                        }
                        is TxConfirmResult.Failed -> {
                            DevLog.e(TAG, "purchaseSkrBoost TX failed: ${confirm.error}")
                            _purchaseState.value = PurchaseState.Done(false,
                                "❌ TX отклонена: ${confirm.error}")
                        }
                    }
                }
                is SplTransferResult.NoWalletFound -> {
                    DevLog.w(TAG, "purchaseSkrBoost NoWalletFound")
                    _purchaseState.value = PurchaseState.Done(false,
                        app.getString(com.sleeper.app.R.string.upgrade_wallet_not_found))
                }
                is SplTransferResult.Error -> {
                    DevLog.e(TAG, "purchaseSkrBoost Error: ${result.message}")
                    _purchaseState.value = PurchaseState.Done(false, result.message)
                }
            }
        }
    }

    /** Цена Genesis NFT в наименьших единицах SKR (6 decimals). 500 SKR. */
    val genesisNftPriceSkrRaw: Long = 500 * 1_000_000L

    fun purchaseGenesisNft(sender: ActivityResultSender?) {
        viewModelScope.launch {
            DevLog.d(TAG, "purchaseGenesisNft ENTRY sender=${sender != null}")
            val app = getApplication<Application>()

            if (_purchaseState.value is PurchaseState.Pending ||
                _purchaseState.value is PurchaseState.Verifying) return@launch

            // Guard: уже владеет NFT
            val existingStats = repository.getUserStats()
            if (existingStats?.hasGenesisNft == true) {
                val num = existingStats.genesisNftNumber
                _purchaseState.value = PurchaseState.Done(false,
                    if (num != null) "Уже владеете Genesis NFT #$num" else "Genesis NFT уже активирован")
                return@launch
            }

            if (sender == null) {
                repository.activateGenesisNft()
                refreshAvailableSkr()
                _purchaseState.value = PurchaseState.Done(true,
                    app.getString(com.sleeper.app.R.string.upgrade_genesis_activated_offline))
                return@launch
            }
            val destination = BuildConfig.BOOST_TREASURY?.trim()?.takeIf { it.isNotEmpty() }
            if (destination == null) {
                repository.activateGenesisNft()
                refreshAvailableSkr()
                _purchaseState.value = PurchaseState.Done(true,
                    app.getString(com.sleeper.app.R.string.upgrade_genesis_activated))
                return@launch
            }
            val blockhash = rpcClient.getLatestBlockhash()
            if (blockhash == null) {
                _purchaseState.value = PurchaseState.Done(false,
                    app.getString(com.sleeper.app.R.string.upgrade_blockhash_failed))
                return@launch
            }
            _purchaseState.value = PurchaseState.Pending("genesis_nft")
            val walletAddress = walletManager.getSavedWalletAddress()
            when (val result = walletManager.signAndSendSplTransfers(
                sender, destination, listOf(genesisNftPriceSkrRaw), blockhash
            )) {
                is SplTransferResult.Success -> {
                    val txSig = result.signatureBase58
                    _purchaseState.value = PurchaseState.Verifying("genesis_nft")
                    when (val confirm = rpcClient.waitForConfirmation(txSig)) {
                        is TxConfirmResult.Confirmed -> {
                            // Пытаемся заминтить реальный NFT через бэкенд
                            val authToken = walletManager.getSavedBackendAuthToken()
                            if (authToken != null && walletAddress != null) {
                                val mintResult = backendApi.mintGenesisNft(walletAddress, authToken, txSig)
                                if (mintResult.isSuccess) {
                                    val nftResp = mintResult.getOrThrow()
                                    repository.activateGenesisNft(nftResp.nftMint, nftResp.nftNumber)
                                    refreshAvailableSkr()
                                    DevLog.i(TAG, "purchaseGenesisNft ✅ NFT #${nftResp.nftNumber} mint=${nftResp.nftMint.take(16)}...")
                                    _purchaseState.value = PurchaseState.Done(true,
                                        "🎉 Genesis NFT #${nftResp.nftNumber} заминчен!")
                                } else {
                                    // TX прошла, но бэкенд не ответил — активируем локально
                                    repository.activateGenesisNft()
                                    refreshAvailableSkr()
                                    val errMsg = mintResult.exceptionOrNull()?.message ?: "ошибка"
                                    DevLog.w(TAG, "purchaseGenesisNft backend mint failed: $errMsg")
                                    _purchaseState.value = PurchaseState.Done(false,
                                        "NFT активирован локально. Backend: $errMsg")
                                }
                            } else {
                                // Нет authToken — активируем локально, просим авторизоваться
                                repository.activateGenesisNft()
                                refreshAvailableSkr()
                                _purchaseState.value = PurchaseState.Done(true,
                                    app.getString(com.sleeper.app.R.string.upgrade_genesis_paid_tx,
                                        txSig.take(16)))
                            }
                        }
                        is TxConfirmResult.Timeout -> {
                            repository.activateGenesisNft()
                            refreshAvailableSkr()
                            _purchaseState.value = PurchaseState.Done(false,
                                "⚠️ TX не подтверждена за 60с. Genesis активирован локально.")
                        }
                        is TxConfirmResult.Failed -> {
                            _purchaseState.value = PurchaseState.Done(false,
                                "❌ TX отклонена: ${confirm.error}")
                        }
                    }
                }
                is SplTransferResult.NoWalletFound -> {
                    _purchaseState.value = PurchaseState.Done(false,
                        app.getString(com.sleeper.app.R.string.upgrade_wallet_not_found))
                }
                is SplTransferResult.Error -> {
                    _purchaseState.value = PurchaseState.Done(false, result.message)
                }
            }
        }
    }

    private fun formatRemainingTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        return if (h > 0) "${h}ч ${m}м" else "${m}м"
    }
}
