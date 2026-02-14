package com.seekerminer.app.ui.screen.wallet

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.seekerminer.app.data.local.AppDatabase
import com.seekerminer.app.data.repository.MiningRepository
import com.seekerminer.app.domain.manager.WalletManager
import com.seekerminer.app.domain.manager.WalletConnectionResult
import com.seekerminer.app.domain.manager.SignMessageResult
import com.seekerminer.app.utils.DevLog
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WalletUiState(
    val isConnecting: Boolean = false,
    val connectedAddress: String? = null,
    val claimStatus: ClaimStatus = ClaimStatus.Idle,
    val error: String? = null
)

sealed class ClaimStatus {
    object Idle : ClaimStatus()
    object Processing : ClaimStatus()
    data class Success(val signature: String) : ClaimStatus()
    data class Error(val message: String) : ClaimStatus()
}

class WalletViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "WalletViewModel"
    }

    private val repository = MiningRepository(AppDatabase.getInstance(application))
    private val walletManager = WalletManager(application)
    
    val userStats = repository.userStats
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    
    private val _walletState = MutableStateFlow(
        WalletUiState(connectedAddress = walletManager.getSavedWalletAddress())
    )
    val walletState: StateFlow<WalletUiState> = _walletState.asStateFlow()
    
    fun connectWallet(sender: ActivityResultSender) {
        viewModelScope.launch {
            DevLog.d(TAG, "connectWallet ENTRY")
            _walletState.value = _walletState.value.copy(isConnecting = true, error = null)

            val result = walletManager.connectWallet(sender)

            DevLog.d(TAG, "connectWallet result: ${result::class.simpleName} ${if (result is WalletConnectionResult.Success) "address=${DevLog.mask(result.address)}" else if (result is WalletConnectionResult.Error) "message=${result.message}" else ""}")
            _walletState.value = when (result) {
                is WalletConnectionResult.Success -> {
                    _walletState.value.copy(
                        isConnecting = false,
                        connectedAddress = result.address
                    )
                }
                is WalletConnectionResult.NoWalletFound -> {
                    DevLog.w(TAG, "connectWallet NoWalletFound")
                    _walletState.value.copy(
                        isConnecting = false,
                        error = "Установите Solana Mobile Wallet"
                    )
                }
                is WalletConnectionResult.Error -> {
                    DevLog.e(TAG, "connectWallet Error: ${result.message}")
                    _walletState.value.copy(
                        isConnecting = false,
                        error = result.message
                    )
                }
            }
        }
    }
    
    fun claimPoints(sender: ActivityResultSender) {
        viewModelScope.launch {
            DevLog.d(TAG, "claimPoints ENTRY")
            val points = userStats.value?.pointsBalance ?: 0
            if (points == 0L) {
                DevLog.w(TAG, "claimPoints SKIP: points=0")
                _walletState.value = _walletState.value.copy(
                    claimStatus = ClaimStatus.Error("Нет поинтов для claim")
                )
                return@launch
            }

            _walletState.value = _walletState.value.copy(claimStatus = ClaimStatus.Processing)

            val claimMessage = "Claim $points SKR Points from SeekerMiner"
            DevLog.d(TAG, "claimPoints signMessage points=$points")
            val result = walletManager.signMessage(sender, claimMessage)

            DevLog.d(TAG, "claimPoints result: ${result::class.simpleName} ${if (result is SignMessageResult.Error) "message=${result.message}" else ""}")
            _walletState.value = _walletState.value.copy(
                claimStatus = when (result) {
                    is SignMessageResult.Success -> {
                        DevLog.i(TAG, "claimPoints SUCCESS signature=${result.signature.take(24)}...")
                        ClaimStatus.Success(result.signature)
                    }
                    is SignMessageResult.NoWalletFound -> {
                        DevLog.w(TAG, "claimPoints NoWalletFound")
                        ClaimStatus.Error("Wallet не найден")
                    }
                    is SignMessageResult.Error -> {
                        DevLog.e(TAG, "claimPoints Error: ${result.message}")
                        ClaimStatus.Error(result.message)
                    }
                }
            )
        }
    }
    
    fun disconnectWallet(sender: ActivityResultSender) {
        viewModelScope.launch {
            walletManager.disconnectWallet(sender)
            _walletState.value = WalletUiState()
        }
    }
    
    fun clearError() {
        _walletState.value = _walletState.value.copy(error = null)
    }
    
    fun clearClaimStatus() {
        _walletState.value = _walletState.value.copy(claimStatus = ClaimStatus.Idle)
    }

    /** При открытии экрана кошелька запрашивает баланс с бэкенда (если API настроен). */
    fun syncBalanceFromServerIfNeeded() {
        viewModelScope.launch {
            DevLog.d(TAG, "syncBalanceFromServerIfNeeded ENTRY")
            walletManager.getSavedWalletAddress()?.let { addr ->
                val ok = repository.syncBalanceFromServer(addr)
                DevLog.d(TAG, "syncBalanceFromServerIfNeeded result=$ok")
            } ?: DevLog.d(TAG, "syncBalanceFromServerIfNeeded no wallet")
        }
    }
}
