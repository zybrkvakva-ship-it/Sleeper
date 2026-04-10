package com.sleeper.app.ui.screen.wallet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.sleeper.app.LocalActivityResultSender
import com.sleeper.app.R
import com.sleeper.app.ui.components.CyberCard
import com.sleeper.app.ui.components.CyberButton
import com.sleeper.app.ui.theme.*

@Composable
fun WalletScreen(
    viewModel: WalletViewModel = viewModel(),
    navController: NavController? = null
) {
    val userStats    by viewModel.userStats.collectAsState()
    val walletState  by viewModel.walletState.collectAsState()
    val activityResultSender = LocalActivityResultSender.current

    LaunchedEffect(Unit) {
        viewModel.syncBalanceFromServerIfNeeded()
    }

    // Auto-dismiss referral applied banner after 3s
    var showReferralBanner by remember { mutableStateOf(false) }
    LaunchedEffect(walletState.referralApplied) {
        if (walletState.referralApplied) {
            showReferralBanner = true
            delay(3000)
            showReferralBanner = false
            viewModel.clearReferralApplied()
        }
    }

    // Screen enter state — triggers stagger sequence
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    fun enterSpec(index: Int) = fadeIn(
        tween(AppDuration.Normal, delayMillis = index * AppDuration.Stagger)
    ) + slideInVertically(
        tween(AppDuration.Normal, delayMillis = index * AppDuration.Stagger)
    ) { it / 3 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Hero gradient header + balance card — index 0 ─────────────────────
        AnimatedVisibility(visible = visible, enter = enterSpec(0)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to NightDeep,
                                0.6f to NightAccent,
                                1f to background
                            )
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.wallet_screen_title).uppercase(),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = CyberWhite
                    )
                    Spacer(Modifier.height(24.dp))
                    CyberCard(
                        modifier = Modifier.fillMaxWidth(),
                        strokeColor = CyberYellow,
                        glowColor = accentGold
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.sleep_points_label),
                                style = MaterialTheme.typography.titleMedium,
                                color = CyberGray
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = String.format("%,d", userStats?.pointsBalance ?: 0),
                                style = MaterialTheme.typography.numeric,
                                fontWeight = FontWeight.Bold,
                                color = CyberYellow
                            )
                        }
                    }
                }
            }
        }

        // ── Rest of content ────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // Referral auto-applied banner
            AnimatedVisibility(
                visible = showReferralBanner,
                enter = fadeIn(tween(AppDuration.Fast)) + slideInVertically(tween(AppDuration.Fast)) { -it },
                exit = fadeOut(tween(AppDuration.Fast))
            ) {
                CyberCard(
                    modifier = Modifier.fillMaxWidth(),
                    strokeColor = CyberGreen,
                    glowColor = accentGreen
                ) {
                    Text(
                        text = stringResource(R.string.referral_applied_bonus),
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberGreen,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Wallet connection block (Crossfade between connected/disconnected) — index 1
            AnimatedVisibility(visible = visible, enter = enterSpec(1)) {
                Crossfade(
                    targetState = walletState.connectedAddress != null,
                    animationSpec = tween(AppDuration.Normal),
                    label = "walletConnected"
                ) { isConnected ->
                    if (!isConnected) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CyberButton(
                                text = if (walletState.isConnecting) stringResource(R.string.wallet_connecting) else stringResource(R.string.wallet_connect_button),
                                onClick = { viewModel.connectWallet(activityResultSender) },
                                enabled = !walletState.isConnecting,
                                strokeColor = CyberGreen
                            )
                            // Connecting spinner
                            AnimatedVisibility(
                                visible = walletState.isConnecting,
                                enter = fadeIn(tween(AppDuration.Fast)),
                                exit = fadeOut(tween(AppDuration.Fast))
                            ) {
                                CircularProgressIndicator(color = CyberGreen, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                            walletState.error?.let {
                                Text(
                                    text = stringResource(R.string.wallet_connection_error),
                                    color = CyberRed,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    } else {
                        CyberCard(modifier = Modifier.fillMaxWidth(), strokeColor = CyberGreen, glowColor = accentGreen) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.wallet_connected), style = MaterialTheme.typography.titleMedium, color = CyberGreen, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.wallet_address, walletState.connectedAddress?.take(16) ?: ""),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CyberWhite
                                )
                                Spacer(Modifier.height(12.dp))
                                TextButton(onClick = { viewModel.disconnectWallet(activityResultSender) }) {
                                    Text(stringResource(R.string.wallet_disconnect), color = CyberRed)
                                }
                            }
                        }
                    }
                }
            }

            // Claim button — only when connected, index 2
            AnimatedVisibility(
                visible = visible && walletState.connectedAddress != null,
                enter = enterSpec(2),
                exit = fadeOut(tween(AppDuration.Fast))
            ) {
                Column {
                    // Claim status via Crossfade
                    Crossfade(
                        targetState = walletState.claimStatus,
                        animationSpec = tween(AppDuration.Normal),
                        label = "claimStatus"
                    ) { status ->
                        CyberButton(
                            text = when (status) {
                                is ClaimStatus.Processing -> stringResource(R.string.wallet_claim_processing)
                                is ClaimStatus.Success    -> stringResource(R.string.wallet_claim_success)
                                is ClaimStatus.Error      -> stringResource(R.string.wallet_claim_error)
                                else -> stringResource(R.string.wallet_claim_pts, userStats?.pointsBalance ?: 0)
                            },
                            onClick = { viewModel.claimPoints(activityResultSender) },
                            enabled = status !is ClaimStatus.Processing &&
                                      (userStats?.pointsBalance ?: 0) > 0,
                            strokeColor = CyberYellow
                        )
                    }

                    if (walletState.claimStatus is ClaimStatus.Success) {
                        AlertDialog(
                            onDismissRequest = { viewModel.clearClaimStatus() },
                            containerColor = com.sleeper.app.ui.theme.NightAccent,
                            tonalElevation = 0.dp,
                            title = {
                                Text(
                                    stringResource(R.string.wallet_claim_dialog_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CyberWhite,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            text = {
                                Column {
                                    Text(
                                        stringResource(R.string.wallet_claim_dialog_message),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = CyberWhite
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        stringResource(R.string.wallet_claim_signature, (walletState.claimStatus as ClaimStatus.Success).signature.take(16)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyberGray
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { viewModel.clearClaimStatus() }) {
                                    Text("OK", color = CyberGreen, fontWeight = FontWeight.Bold)
                                }
                            }
                        )
                    }
                }
            }

            // Stats card — index 3
            AnimatedVisibility(visible = visible, enter = enterSpec(3)) {
                CyberCard(modifier = Modifier.fillMaxWidth(), strokeColor = Stroke) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        StatRow(stringResource(R.string.stats_total_blocks), "${userStats?.totalBlocksMined ?: 0}")
                        Spacer(Modifier.height(8.dp))
                        StatRow(stringResource(R.string.stats_mining_time), "${userStats?.uptimeMinutes ?: 0} ${stringResource(R.string.stats_min)}")
                    }
                }
            }

            if (navController != null) {
                TextButton(onClick = { navController.navigate("privacy") }) {
                    Text(
                        text = stringResource(R.string.privacy_title),
                        color = CyberGray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = CyberGray)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = CyberWhite)
    }
}
