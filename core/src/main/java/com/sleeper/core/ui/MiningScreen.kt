package com.sleeper.core.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.sleeper.core.ui.MiningViewModel

/**
 * Simple composable screen that shows the mining status and lets the user
 * start/stop the mining process. It obtains the ViewModel via Hilt
 * (`compose` delegate) and automatically reacts to its mutable state.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MiningScreen(
    // Hilt will provide the same MiningViewModel instance throughout the process
    miningViewModel: com.sleeper.core.domain.ui.MiningViewModel = 
        viewModel()
) {
    // Pull UI‑state from the ViewModel
    val isMining by miningViewModel::isMining
    val logText by miningViewModel::logText

    // Scaffold gives us a standard top bar & padding handling
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Seeker Miner") },
                colors = TopAppBarDefaults.colorScheme(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // Status text
            Text(
                text = logText,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            // Action button – enabled only when not already mining
            Button(
                onClick = { if (!isMining) miningViewModel.startMining() }
                    else { miningViewModel.stopMining() },
                enabled = !isMining,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 24.dp)
            ) {
                Text(if (!isMining) "Start Mining" else "Stop Mining")
            }
        }
    }
}