package com.sleeper.core.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Simple ViewModel that holds the mining UI state.
 * It receives the repository via constructor injection.
 */
@HiltViewModel
class MiningViewModel @Inject constructor(
    private val repository: com.sleeper.core.data.repository.MiningRepository
) : ViewModel() {
    // UI‑state exposed to the composable layer
    var isMining by mutableStateOf(false)
    var logText by mutableStateOf("Ready to start mining")
        private set

    fun startMining() {
        isMining.value = true
        logText = "Mining started..."
        // In a real app you would call repository.startMining() here.
    }

    fun stopMining() {
        isMining.value = false
        logText = "Mining stopped."
    }

    // Additional functions can be added later (e.g. fetchStats, etc.)
}