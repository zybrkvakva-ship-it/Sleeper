package com.sleeper.app.ui.screen.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.sleeper.app.LocalActivityResultSender
import com.sleeper.app.R
import com.sleeper.app.ui.theme.*

@Composable
fun WalletScreen(
    viewModel: WalletViewModel = viewModel(),
    navController: NavController? = null
) {
    val userStats by viewModel.userStats.collectAsState()
    val walletState by viewModel.walletState.collectAsState()
    val activityResultSender = LocalActivityResultSender.current

    LaunchedEffect(Unit) {
        viewModel.syncBalanceFromServerIfNeeded()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(com.sleeper.app.ui.theme.BgMain)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "КОШЕЛЁК",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = com.sleeper.app.ui.theme.CyberWhite
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Sleep Points
        com.sleeper.app.ui.components.CyberCard(
            modifier = Modifier.fillMaxWidth(),
            strokeColor = com.sleeper.app.ui.theme.CyberYellow,
            cornerRadius = 12.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "SKR POINTS",
                    fontSize = 18.sp,
                    color = com.sleeper.app.ui.theme.CyberGray
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = String.format("%,d", userStats?.pointsBalance ?: 0),
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = com.sleeper.app.ui.theme.CyberYellow
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Wallet Connection Section
        if (walletState.connectedAddress == null) {
            com.sleeper.app.ui.components.CyberButton(
                text = if (walletState.isConnecting) "Подключение..." else "Подключить кошелёк",
                onClick = { viewModel.connectWallet(activityResultSender) },
                enabled = !walletState.isConnecting,
                strokeColor = com.sleeper.app.ui.theme.CyberGreen
            )
            if (walletState.isConnecting) {
                Spacer(modifier = Modifier.height(8.dp))
                CircularProgressIndicator(
                    color = com.sleeper.app.ui.theme.CyberGreen,
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
            
            // Show error if any
            walletState.error?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    color = com.sleeper.app.ui.theme.CyberRed,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            com.sleeper.app.ui.components.CyberCard(
                modifier = Modifier.fillMaxWidth(),
                strokeColor = com.sleeper.app.ui.theme.CyberGreen,
                cornerRadius = 12.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "WALLET ПОДКЛЮЧЁН",
                        fontSize = 16.sp,
                        color = com.sleeper.app.ui.theme.CyberGreen,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Адрес: ${walletState.connectedAddress?.take(16) ?: ""}...",
                        fontSize = 14.sp,
                        color = com.sleeper.app.ui.theme.CyberWhite
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(
                        onClick = { viewModel.disconnectWallet(activityResultSender) }
                    ) {
                        Text("Отключить", color = com.sleeper.app.ui.theme.CyberRed)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            com.sleeper.app.ui.components.CyberButton(
                text = when (val s = walletState.claimStatus) {
                    is ClaimStatus.Processing -> "Обработка..."
                    is ClaimStatus.Success -> "Claim успешен!"
                    is ClaimStatus.Error -> "Ошибка"
                    else -> "Claim ${String.format("%,d", userStats?.pointsBalance ?: 0)} pts"
                },
                onClick = { viewModel.claimPoints(activityResultSender) },
                enabled = walletState.claimStatus !is ClaimStatus.Processing &&
                         (userStats?.pointsBalance ?: 0) > 0,
                strokeColor = com.sleeper.app.ui.theme.CyberYellow
            )
            // Show claim success dialog
            if (walletState.claimStatus is ClaimStatus.Success) {
                AlertDialog(
                    onDismissRequest = { viewModel.clearClaimStatus() },
                    title = { Text("🎉 Claim Успешен!") },
                    text = {
                        Column {
                            Text("Ваши Sleep Points подписаны wallet'ом!")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Signature: ${(walletState.claimStatus as ClaimStatus.Success).signature.take(16)}...",
                                fontSize = 12.sp,
                                color = com.sleeper.app.ui.theme.CyberGray
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.clearClaimStatus() }) {
                            Text("OK", color = com.sleeper.app.ui.theme.CyberGreen)
                        }
                    }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        com.sleeper.app.ui.components.CyberCard(
            modifier = Modifier.fillMaxWidth(),
            strokeColor = com.sleeper.app.ui.theme.Stroke,
            cornerRadius = 12.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                StatRow("Всего блоков:", "${userStats?.totalBlocksMined ?: 0}")
                Spacer(modifier = Modifier.height(8.dp))
                StatRow("Время майнинга:", "${userStats?.uptimeMinutes ?: 0} мин")
                Spacer(modifier = Modifier.height(8.dp))
                StatRow("Storage:", "${userStats?.storageMB ?: 0} MB")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        if (navController != null) {
            TextButton(onClick = { navController.navigate("privacy") }) {
                Text(
                    text = stringResource(R.string.privacy_title),
                    color = com.sleeper.app.ui.theme.CyberGray,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = com.sleeper.app.ui.theme.CyberGray
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = com.sleeper.app.ui.theme.CyberWhite
        )
    }
}
