package com.sleeper.app.ui.screen.wallet

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sleeper.app.data.local.AppDatabase
import com.sleeper.app.data.network.MiningBackendApi
import com.sleeper.app.data.repository.MiningRepository
import com.sleeper.app.domain.manager.WalletManager
import com.sleeper.app.domain.manager.WalletConnectionResult
import com.sleeper.app.domain.manager.SignMessageResult
import com.sleeper.app.utils.DevLog
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
    val error: String? = null,
    val referralApplied: Boolean = false  // one-shot: показать тост "Referral applied!"
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
    private val backendApi = MiningBackendApi()
    
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
                    authenticateBackend(result.address, sender)
                    _walletState.value.copy(
                        isConnecting = false,
                        connectedAddress = result.address
                    )
                }
                is WalletConnectionResult.NoWalletFound -> {
                    DevLog.w(TAG, "connectWallet NoWalletFound")
                    _walletState.value.copy(
                        isConnecting = false,
                        error = getApplication<Application>().getString(com.sleeper.app.R.string.wallet_install_solana_wallet)
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

    private suspend fun authenticateBackend(walletAddress: String, sender: ActivityResultSender) {
        if (!backendApi.isConfigured()) {
            DevLog.d(TAG, "authenticateBackend skipped: API not configured")
            return
        }

        backendApi.requestAuthChallenge(walletAddress)
            .onSuccess { challenge ->
                val signed = walletManager.signMessage(sender, challenge.message)
                if (signed is SignMessageResult.Success) {
                    backendApi.verifyAuthChallenge(walletAddress, challenge.nonce, signed.signature)
                        .onSuccess { authToken ->
                            walletManager.saveBackendAuthToken(authToken)
                            DevLog.i(TAG, "Backend auth token received and saved")
                            autoApplyPendingReferral(walletAddress)
                        }
                        .onFailure { e ->
                            walletManager.saveBackendAuthToken(null)
                            DevLog.e(TAG, "Backend auth verify failed: ${e.message}")
                            _walletState.value = _walletState.value.copy(error = "Backend auth failed")
                        }
                } else if (signed is SignMessageResult.Error) {
                    walletManager.saveBackendAuthToken(null)
                    _walletState.value = _walletState.value.copy(error = signed.message)
                }
            }
            .onFailure { e ->
                walletManager.saveBackendAuthToken(null)
                DevLog.e(TAG, "Backend auth challenge failed: ${e.message}")
                _walletState.value = _walletState.value.copy(error = "Backend auth unavailable")
            }
    }
    
    /** Применяет реферальный код из deep link автоматически, без диалога. */
    private suspend fun autoApplyPendingReferral(walletAddress: String) {
        val code = walletManager.getPendingReferralCode() ?: return
        DevLog.d(TAG, "autoApplyPendingReferral code=${code.take(4)}***")
        val error = repository.applyReferral(walletAddress, code)
        walletManager.savePendingReferralCode(null)
        walletManager.markReferralOnboardingAsked()
        if (error == null) {
            DevLog.i(TAG, "Referral auto-applied: $code")
            _walletState.value = _walletState.value.copy(referralApplied = true)
        } else {
            // 409 = already used, 400 = own code — silently skip, not an error for the user
            DevLog.w(TAG, "autoApplyPendingReferral skipped: $error")
        }
    }

    fun clearReferralApplied() {
        _walletState.value = _walletState.value.copy(referralApplied = false)
    }

    fun claimPoints(sender: ActivityResultSender) {
        viewModelScope.launch {
            DevLog.d(TAG, "claimPoints ENTRY")
            val points = userStats.value?.pointsBalance ?: 0
            if (points == 0L) {
                DevLog.w(TAG, "claimPoints SKIP: points=0")
                _walletState.value = _walletState.value.copy(
                    claimStatus = ClaimStatus.Error(getApplication<Application>().getString(com.sleeper.app.R.string.wallet_no_points_claim))
                )
                return@launch
            }

            val walletAddress = walletManager.getSavedWalletAddress() ?: run {
                _walletState.value = _walletState.value.copy(
                    claimStatus = ClaimStatus.Error(getApplication<Application>().getString(com.sleeper.app.R.string.upgrade_wallet_not_found))
                )
                return@launch
            }
            val authToken = walletManager.getSavedBackendAuthToken()

            _walletState.value = _walletState.value.copy(claimStatus = ClaimStatus.Processing)

            // Sign claim message to prove wallet ownership
            val claimMessage = "Claim $points Sleep Points from Seeker Mining"
            DevLog.d(TAG, "claimPoints signMessage points=$points")
            val signResult = walletManager.signMessage(sender, claimMessage)
            if (signResult !is SignMessageResult.Success) {
                _walletState.value = _walletState.value.copy(
                    claimStatus = when (signResult) {
                        is SignMessageResult.NoWalletFound -> ClaimStatus.Error(getApplication<Application>().getString(com.sleeper.app.R.string.upgrade_wallet_not_found))
                        is SignMessageResult.Error -> ClaimStatus.Error(signResult.message)
                        else -> ClaimStatus.Error("Signing failed")
                    }
                )
                return@launch
            }

            // Post claim to backend (signature proves intent; backend validates balance + sends tokens)
            if (backendApi.isConfigured() && authToken != null) {
                backendApi.postClaim(walletAddress, authToken)
                    .onSuccess { claimResult ->
                        DevLog.i(TAG, "claimPoints backend SUCCESS txHash=${claimResult.txHash.take(16)}...")
                        _walletState.value = _walletState.value.copy(
                            claimStatus = ClaimStatus.Success(claimResult.txHash)
                        )
                        repository.syncBalanceFromServer(walletAddress)
                    }
                    .onFailure { e ->
                        DevLog.e(TAG, "claimPoints backend FAILED: ${e.message}")
                        _walletState.value = _walletState.value.copy(
                            claimStatus = ClaimStatus.Error(e.message ?: "Claim failed")
                        )
                    }
            } else {
                // Pre-TGE or API not configured: show signed confirmation
                DevLog.i(TAG, "claimPoints offline/pre-TGE signature=${signResult.signature.take(24)}...")
                _walletState.value = _walletState.value.copy(
                    claimStatus = ClaimStatus.Success(signResult.signature)
                )
            }
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
