package com.sleeper.app.ui.screen.upgrade

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sleeper.app.BuildConfig
import com.sleeper.app.data.local.AppDatabase
import com.sleeper.app.data.local.SkrBoostCatalog
import com.sleeper.app.data.local.SkrBoostItem
import com.sleeper.app.data.local.UpgradeEntity
import com.sleeper.app.data.local.UpgradeType
import com.sleeper.app.data.network.SolanaCluster
import com.sleeper.app.data.network.SolanaRpcClient
import com.sleeper.app.data.repository.MiningRepository
import com.sleeper.app.domain.manager.StorageManager
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

class UpgradeViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "UpgradeViewModel"
    }

    private val repository = MiningRepository(AppDatabase.getInstance(application))
    private val storageManager = StorageManager(application)
    private val walletManager = WalletManager(application)
    private val rpcClient = SolanaRpcClient(SolanaCluster.MAINNET_HELIUS)
    
    val upgrades: StateFlow<List<UpgradeEntity>> = repository.upgrades
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    val userStats = repository.userStats
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    
    val skrBoosts: List<SkrBoostItem> = SkrBoostCatalog.all
    
    private val _availableSkrRaw = MutableStateFlow(0L)
    val availableSkrRaw: StateFlow<Long> = _availableSkrRaw.asStateFlow()

    /** Результат покупки буста/Genesis (сообщение для UI). null = нет события. */
    private val _purchaseMessage = MutableStateFlow<String?>(null)
    val purchaseMessage: StateFlow<String?> = _purchaseMessage.asStateFlow()
    fun clearPurchaseMessage() { _purchaseMessage.value = null }
    
    fun refreshAvailableSkr() {
        viewModelScope.launch {
            DevLog.d(TAG, "refreshAvailableSkr ENTRY")
            val wallet = walletManager.getSavedWalletAddress() ?: run {
                DevLog.d(TAG, "refreshAvailableSkr no wallet -> 0")
                _availableSkrRaw.value = 0L
                return@launch
            }
            val raw = rpcClient.getAvailableSkrForPurchase(wallet)
            _availableSkrRaw.value = raw
            DevLog.d(TAG, "refreshAvailableSkr wallet=${DevLog.mask(wallet)} availableRaw=$raw")
        }
    }
    
    fun purchaseUpgrade(upgradeId: String) {
        viewModelScope.launch {
            val upgrade = repository.getUpgrade(upgradeId) ?: return@launch
            val stats = repository.getUserStats() ?: return@launch
            if (upgrade.type == UpgradeType.STORAGE) {
                val newPlots = maxOf(1, (stats.storageMB * upgrade.multiplier / 100).toInt())
                storageManager.allocateStorage(newPlots)
            }
            repository.purchaseUpgrade(upgradeId)
        }
    }
    
    fun purchaseSkrBoost(boostId: String, sender: ActivityResultSender?) {
        viewModelScope.launch {
            DevLog.d(TAG, "purchaseSkrBoost ENTRY boostId=$boostId sender=${sender != null}")
            val boost = SkrBoostCatalog.get(boostId) ?: run {
                DevLog.w(TAG, "purchaseSkrBoost boost not found: $boostId")
                _purchaseMessage.value = "Буст не найден"
                return@launch
            }
            val treasury = BuildConfig.BOOST_TREASURY?.trim()?.takeIf { it.isNotEmpty() }
            if (treasury == null || sender == null) {
                DevLog.d(TAG, "purchaseSkrBoost offline mode: treasury=${treasury != null} sender=${sender != null}")
                repository.purchaseSkrBoost(boostId)
                refreshAvailableSkr()
                _purchaseMessage.value = "Буст активирован (ончейн не настроен)"
                return@launch
            }
            val amounts = when (boostId) {
                "boost_7h" -> listOf(boost.priceSkrRaw)
                "boost_7x" -> List(7) { i -> if (i == 0) boost.priceSkrRaw - 6 * (boost.priceSkrRaw / 7) else boost.priceSkrRaw / 7 }
                "boost_49x" -> List(49) { i -> if (i == 0) boost.priceSkrRaw - 48 * (boost.priceSkrRaw / 49) else boost.priceSkrRaw / 49 }
                else -> listOf(boost.priceSkrRaw)
            }
            DevLog.d(TAG, "purchaseSkrBoost treasury=${DevLog.mask(treasury)} amountsCount=${amounts.size} totalRaw=${amounts.sum()}")
            val blockhash = rpcClient.getLatestBlockhash()
            if (blockhash == null) {
                DevLog.e(TAG, "purchaseSkrBoost getLatestBlockhash failed")
                _purchaseMessage.value = "Не удалось получить blockhash"
                return@launch
            }
            DevLog.d(TAG, "purchaseSkrBoost blockhash=${DevLog.mask(blockhash)} calling signAndSendSplTransfers...")
            when (val result = walletManager.signAndSendSplTransfers(sender, treasury, amounts, blockhash)) {
                is SplTransferResult.Success -> {
                    repository.purchaseSkrBoost(boostId)
                    refreshAvailableSkr()
                    DevLog.i(TAG, "purchaseSkrBoost SUCCESS tx=${result.signatureBase58.take(20)}...")
                    _purchaseMessage.value = "Буст оплачен! Tx: ${result.signatureBase58.take(16)}..."
                }
                is SplTransferResult.NoWalletFound -> {
                    DevLog.w(TAG, "purchaseSkrBoost NoWalletFound")
                    _purchaseMessage.value = "Кошелёк не найден"
                }
                is SplTransferResult.Error -> {
                    DevLog.e(TAG, "purchaseSkrBoost Error: ${result.message}")
                    _purchaseMessage.value = result.message
                }
            }
        }
    }

    /** Цена Genesis NFT в наименьших единицах SKR (6 decimals). 275 SKR. */
    val genesisNftPriceSkrRaw: Long = 275 * 1_000_000L

    fun purchaseGenesisNft(sender: ActivityResultSender?) {
        viewModelScope.launch {
            DevLog.d(TAG, "purchaseGenesisNft ENTRY sender=${sender != null} priceRaw=$genesisNftPriceSkrRaw")
            if (sender == null) {
                DevLog.d(TAG, "purchaseGenesisNft no sender -> offline activate")
                repository.activateGenesisNft()
                refreshAvailableSkr()
                _purchaseMessage.value = "Genesis NFT активирован (ончейн не настроен)"
                return@launch
            }
            val destination = BuildConfig.BOOST_TREASURY?.trim()?.takeIf { it.isNotEmpty() }
            if (destination == null) {
                DevLog.d(TAG, "purchaseGenesisNft no BOOST_TREASURY -> local only")
                repository.activateGenesisNft()
                refreshAvailableSkr()
                _purchaseMessage.value = "Genesis NFT активирован"
                return@launch
            }
            val blockhash = rpcClient.getLatestBlockhash()
            if (blockhash == null) {
                DevLog.e(TAG, "purchaseGenesisNft getLatestBlockhash failed")
                _purchaseMessage.value = "Не удалось получить blockhash"
                return@launch
            }
            DevLog.d(TAG, "purchaseGenesisNft destination=${DevLog.mask(destination)} calling signAndSendSplTransfers...")
            when (val result = walletManager.signAndSendSplTransfers(
                sender, destination, listOf(genesisNftPriceSkrRaw), blockhash
            )) {
                is SplTransferResult.Success -> {
                    repository.activateGenesisNft()
                    refreshAvailableSkr()
                    DevLog.i(TAG, "purchaseGenesisNft SUCCESS tx=${result.signatureBase58.take(20)}...")
                    _purchaseMessage.value = "Genesis NFT оплачен! Tx: ${result.signatureBase58.take(16)}..."
                }
                is SplTransferResult.NoWalletFound -> {
                    DevLog.w(TAG, "purchaseGenesisNft NoWalletFound")
                    _purchaseMessage.value = "Кошелёк не найден"
                }
                is SplTransferResult.Error -> {
                    DevLog.e(TAG, "purchaseGenesisNft Error: ${result.message}")
                    _purchaseMessage.value = result.message
                }
            }
        }
    }
}
