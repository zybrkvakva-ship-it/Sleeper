package com.sleeper.app.ui.screen.tasks

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sleeper.app.data.local.AppDatabase
import com.sleeper.app.data.local.TaskActionType
import com.sleeper.app.data.local.TaskEntity
import com.sleeper.app.data.repository.MiningRepository
import com.sleeper.app.domain.manager.WalletManager
import com.sleeper.app.utils.DevLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TasksUiState(
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
    /** Total task boost for tonight's mining (daily + special). */
    val totalBonusPercent: Double = 0.0,
    val nightStreak: Int = 0,
    val referralCount: Int = 0,
    /** User's own referral code (first 8 chars of wallet, server-authoritative). */
    val referralCode: String? = null,
    val walletAddress: String? = null,
    /** Show one-time referral onboarding dialog (enter inviter's code). */
    val showReferralOnboarding: Boolean = false,
    val referralOnboardingPreFill: String = "",
    val referralOnboardingError: String? = null,
    /** Ночей с welcome-бонусом +10% (для приглашённых пользователей). 0 = нет бонуса */
    val bonusNightsRemaining: Int = 0,
)

class TasksViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TasksViewModel"
    }

    private val db = AppDatabase.getInstance(application)
    private val repository = MiningRepository(db)
    private val walletManager = WalletManager(application)

    val tasks: StateFlow<List<TaskEntity>> = repository.tasks
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _uiState = MutableStateFlow(TasksUiState())
    val uiState: StateFlow<TasksUiState> = _uiState

    init {
        // Restore cached referral info immediately (no flicker)
        val savedWallet = walletManager.getSavedWalletAddress()
        val savedCode = walletManager.getSavedReferralCode()
            ?: savedWallet?.take(8)  // derive locally if not yet synced
        val pendingCode = walletManager.getPendingReferralCode() ?: ""
        val shouldAsk = savedWallet != null &&
            !walletManager.isReferralOnboardingAsked() &&
            walletManager.getSavedBackendAuthToken() != null
        _uiState.update { it.copy(
            walletAddress = savedWallet,
            referralCode = savedCode,
            referralCount = walletManager.getSavedReferralCount(),
            bonusNightsRemaining = walletManager.getBonusNightsRemaining(),
            showReferralOnboarding = shouldAsk,
            referralOnboardingPreFill = pendingCode
        ) }
        syncFromServer()
    }

    fun submitReferralCode(code: String) {
        val wallet = _uiState.value.walletAddress ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, referralOnboardingError = null) }
            val error = repository.applyReferral(wallet, code.trim())
            if (error == null) {
                walletManager.markReferralOnboardingAsked()
                walletManager.savePendingReferralCode(null)
                _uiState.update { it.copy(
                    isLoading = false,
                    showReferralOnboarding = false,
                    successMessage = "Реферальный код применён!"
                ) }
                syncFromServer()  // refresh referral count
            } else {
                _uiState.update { it.copy(isLoading = false, referralOnboardingError = error) }
            }
        }
    }

    fun dismissReferralOnboarding() {
        walletManager.markReferralOnboardingAsked()
        walletManager.savePendingReferralCode(null)
        _uiState.update { it.copy(showReferralOnboarding = false) }
    }

    /** Pull profile + tasks from server and update local cache. */
    fun syncFromServer() {
        viewModelScope.launch {
            val walletAddress = walletManager.getSavedWalletAddress() ?: return@launch
            val authToken = walletManager.getSavedBackendAuthToken()
            _uiState.update { it.copy(isSyncing = true, error = null) }

            // Load user profile (referral code + count)
            val profile = repository.syncUserProfile(walletAddress, walletManager)
            val referralCode = profile?.referralCode
                ?: walletManager.getSavedReferralCode()
                ?: walletAddress.take(8)
            val referralCount = profile?.referralCount ?: walletManager.getSavedReferralCount()
            val bonusNights = profile?.bonusNightsRemaining ?: walletManager.getBonusNightsRemaining()

            // Load tasks
            val tasksOk = repository.syncTasksFromServer(walletAddress, authToken)
            if (!tasksOk) DevLog.d(TAG, "syncFromServer: offline or no API — using local cache")

            val refreshed = db.userStatsDao().getUserStats()
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    walletAddress = walletAddress,
                    referralCode = referralCode,
                    referralCount = referralCount,
                    bonusNightsRemaining = bonusNights,
                    totalBonusPercent = refreshed?.dailySocialBonusPercent ?: it.totalBonusPercent
                )
            }
        }
    }

    /**
     * Complete a task. For OPEN_URL / SHARE / STORY / REFERRAL tasks:
     * the UI already opened the external link — this records the completion.
     */
    fun completeTask(taskId: String) {
        viewModelScope.launch {
            val walletAddress = walletManager.getSavedWalletAddress()
            val authToken = walletManager.getSavedBackendAuthToken()
            _uiState.update { it.copy(isLoading = true, error = null) }

            val success = repository.completeTask(
                taskId = taskId,
                walletAddress = walletAddress,
                authToken = authToken
            )

            if (success) {
                val task = db.taskDao().getTasks().find { it.id == taskId }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = if (task != null) "+${task.reward} pts" else "Task completed!",
                        totalBonusPercent = db.userStatsDao().getUserStats()?.dailySocialBonusPercent ?: it.totalBonusPercent
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false, error = "Could not complete task. Try again.") }
            }
        }
    }

    /**
     * Opens external URL via Android intent and returns the url to caller.
     * Actual completion is sent to server separately by [completeTask].
     */
    fun buildShareIntent(url: String, @Suppress("UNUSED_PARAMETER") taskId: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
    fun clearSuccess() = _uiState.update { it.copy(successMessage = null) }
}
