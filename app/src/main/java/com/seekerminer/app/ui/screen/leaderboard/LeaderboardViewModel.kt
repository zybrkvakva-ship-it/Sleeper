package com.seekerminer.app.ui.screen.leaderboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.seekerminer.app.BuildConfig
import com.seekerminer.app.data.network.MiningBackendApi
import com.seekerminer.app.domain.manager.WalletManager
import com.seekerminer.app.utils.DevLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LeaderboardEntry(
    val rank: Int,
    val username: String,
    val blocks: Int
)

data class LeaderboardUiState(
    val isLoading: Boolean = true,
    val topPlayers: List<LeaderboardEntry> = emptyList(),
    val userRank: Int = 847,       // с бэкенда при USE_REAL_LEADERBOARD
    val userBlocks: Int = 124,     // с бэкенда при USE_REAL_LEADERBOARD
    val currentUserSkrUsername: String? = null,
    val globalStats: GlobalStats = GlobalStats(),
    val isDemoData: Boolean = false  // true = показываем mock, в UI — подпись «Демо-данные»
)

data class GlobalStats(
    val totalPoints: Long = 1_000_000_000,  // СТУБ: с бэкенда
    val totalBlocks: Int = 124_700          // СТУБ: с бэкенда
)

class LeaderboardViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "LeaderboardVM"
    }

    private val walletManager = WalletManager(application)
    private val _uiState = MutableStateFlow(LeaderboardUiState())
    val uiState: StateFlow<LeaderboardUiState> = _uiState.asStateFlow()
    
    init {
        loadLeaderboard()
    }
    
    private fun loadLeaderboard() {
        viewModelScope.launch {
            DevLog.d(TAG, "loadLeaderboard ENTRY USE_REAL_LEADERBOARD=${BuildConfig.USE_REAL_LEADERBOARD}")
            val currentSkr = walletManager.getSavedSkrUsername()
            if (BuildConfig.USE_REAL_LEADERBOARD) {
                val api = MiningBackendApi()
                if (api.isConfigured()) {
                    val wallet = walletManager.getSavedWalletAddress()
                    DevLog.d(TAG, "loadLeaderboard fetching from API wallet=${wallet?.take(8)}...")
                    api.getLeaderboard(wallet).onSuccess { resp ->
                        DevLog.i(TAG, "loadLeaderboard SUCCESS topSize=${resp.top.size} userRank=${resp.userRank} totalPoints=${resp.totalPoints}")
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            topPlayers = resp.top.map { LeaderboardEntry(it.rank, it.username, it.blocks) },
                            userRank = resp.userRank,
                            userBlocks = resp.userBlocks,
                            currentUserSkrUsername = currentSkr,
                            globalStats = GlobalStats(resp.totalPoints, resp.totalBlocks),
                            isDemoData = false
                        )
                    }.onFailure {
                        DevLog.e(TAG, "loadLeaderboard API failed: ${it.message} cause=${it.cause?.message}", it)
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            topPlayers = emptyList(),
                            currentUserSkrUsername = currentSkr,
                            isDemoData = false
                        )
                    }
                } else {
                    DevLog.d(TAG, "loadLeaderboard API not configured -> empty")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        topPlayers = emptyList(),
                        currentUserSkrUsername = currentSkr,
                        isDemoData = false
                    )
                }
            } else {
                DevLog.d(TAG, "loadLeaderboard using mock data")
                val mockData = listOf(
                    LeaderboardEntry(1, "whale_sol", 12847),
                    LeaderboardEntry(2, "seeker_god", 9234),
                    LeaderboardEntry(3, "crypto_king", 7845),
                    LeaderboardEntry(4, "hodl_master", 6521),
                    LeaderboardEntry(5, "moon_boy", 5432),
                    LeaderboardEntry(6, "diamond_hands", 4987),
                    LeaderboardEntry(7, "satoshi_fan", 4321),
                    LeaderboardEntry(8, "block_hunter", 3876),
                    LeaderboardEntry(9, "miner_pro", 3456),
                    LeaderboardEntry(10, "solana_dev", 3123)
                )
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    topPlayers = mockData,
                    currentUserSkrUsername = currentSkr,
                    isDemoData = true
                )
            }
        }
    }
}
