package com.sleeper.app.ui.screen.mining

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sleeper.app.R
import com.sleeper.app.ui.components.EnergyBar
import kotlinx.coroutines.delay
import com.sleeper.app.ui.components.MiningButton
import com.sleeper.app.ui.components.StatusChip
import com.sleeper.app.ui.theme.*
import com.sleeper.app.data.local.SkrBoostCatalog

@Composable
fun MiningScreen(
    viewModel: MiningViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // При каждом показе экрана обновляем кошелёк, .skr и стейк
    LaunchedEffect(Unit) {
        viewModel.refreshWalletAndSkr()
        viewModel.refreshStakeIfNeeded()
    }
    // Периодическое обновление стейка раз в 5 мин
    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60 * 1000L)
            viewModel.refreshStakeIfNeeded()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgMain)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Проверка устройства
        when {
            uiState.isVerifying -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = CyberGreen)
                }
                return@Column
            }
            
            !uiState.isDeviceValid -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                        .background(CyberRed.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.errorMessage,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberRed,
                        textAlign = TextAlign.Center
                    )
                }
                return@Column
            }
        }
        
        // Блок и награда (Seed Vault: первая карточка)
        com.sleeper.app.ui.components.CyberCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 12.dp,
            strokeColor = Stroke
        ) {
            Column(
                modifier = Modifier.padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Block",
                                tint = CyberGreen,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.mining_block, uiState.currentBlock),
                            fontSize = 16.sp,
                            color = CyberWhite,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.mining_difficulty, uiState.difficulty),
                                fontSize = 14.sp,
                                color = CyberGray
                            )
                            if (uiState.isDemoNetworkStats) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.mining_demo),
                                    fontSize = 11.sp,
                                    color = CyberGray
                                )
                            }
                        }
                    }
                    
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                        text = stringResource(R.string.mining_reward_label),
                        fontSize = 12.sp,
                        color = CyberGray
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "500 pts",
                            fontSize = 18.sp,
                            color = CyberYellow,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Online",
                                tint = CyberGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = String.format("%,d", uiState.onlineUsers),
                                fontSize = 14.sp,
                                color = CyberWhite
                            )
                            if (uiState.isDemoNetworkStats) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.mining_demo),
                                    fontSize = 11.sp,
                                    color = CyberGray
                                )
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Энергия в карточке (Seed Vault)
        com.sleeper.app.ui.components.CyberCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 12.dp,
            strokeColor = Stroke
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                EnergyBar(
                    current = uiState.energyCurrent,
                    max = uiState.energyMax
                )
                Text(
                    text = if (uiState.energyCurrent >= uiState.energyMax) {
                        stringResource(R.string.mining_energy_hint_full)
                    } else if (!uiState.isMining && uiState.energyCurrent < uiState.energyMax) {
                        stringResource(R.string.mining_energy_hint_recovery)
                    } else {
                        stringResource(R.string.mining_energy_hint_rates)
                    },
                    fontSize = 12.sp,
                    color = CyberGray,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        
        // Стейк SKR (множитель к награде; не блокирует майнинг)
        com.sleeper.app.ui.components.CyberCard(
            modifier = Modifier.fillMaxWidth(),
            strokeColor = Stroke,
            cornerRadius = 12.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (uiState.stakedSkrHuman > 0) {
                        stringResource(R.string.mining_stake_format, String.format("%,.0f", uiState.stakedSkrHuman))
                    } else {
                        stringResource(R.string.mining_stake_none)
                    },
                    fontSize = 14.sp,
                    color = CyberWhite
                )
                Text(
                    text = if (uiState.stakeMultiplier > 1.0) {
                        stringResource(R.string.mining_stake_bonus, ((uiState.stakeMultiplier - 1.0) * 100).toInt())
                    } else {
                        "x1.0"
                    },
                    fontSize = 14.sp,
                    color = CyberGreen,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (uiState.hasGenesisNft) {
            Spacer(modifier = Modifier.height(6.dp))
            com.sleeper.app.ui.components.CyberCard(
                modifier = Modifier.fillMaxWidth(),
                strokeColor = CyberYellow,
                cornerRadius = 12.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.mining_genesis_holder),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberYellow
                    )
                    Text(
                        text = stringResource(R.string.mining_genesis_forever, String.format("%.1f", uiState.genesisNftMultiplier)),
                        fontSize = 14.sp,
                        color = CyberGray
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Day 3: Low energy warning (< 20%)
        if (uiState.showLowEnergyWarning) {
            Spacer(modifier = Modifier.height(8.dp))
            com.sleeper.app.ui.components.CyberCard(
                modifier = Modifier.fillMaxWidth(),
                strokeColor = CyberYellow,
                cornerRadius = 12.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = CyberYellow,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = stringResource(R.string.mining_low_energy_warning),
                        fontSize = 14.sp,
                        color = CyberWhite,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(14.dp))
        
        // .skr Token Verification Section
        if (uiState.walletConnected) {
            if (uiState.isTokenVerified && uiState.skrUsername != null) {
                com.sleeper.app.ui.components.CyberCard(
                    modifier = Modifier.fillMaxWidth(),
                    strokeColor = CyberGreen,
                    cornerRadius = 12.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Verified",
                                tint = CyberGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.skr_mining_authorized),
                                    fontSize = 7.sp,
                                    color = CyberGreen,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = uiState.skrUsername ?: "",
                                    fontSize = 9.sp,
                                    color = CyberWhite,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(10.dp))
            } else {
                // Token not verified (уменьшено в 2 раза)
                com.sleeper.app.ui.components.CyberCard(
                    modifier = Modifier.fillMaxWidth(),
                    strokeColor = CyberYellow,
                    cornerRadius = 12.dp
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Locked",
                            tint = CyberYellow,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.skr_required),
                            fontSize = 9.sp,
                            color = CyberYellow,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.skr_required_hint),
                            fontSize = 7.sp,
                            color = CyberWhite.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        com.sleeper.app.ui.components.CyberButton(
                            text = stringResource(R.string.skr_verify_button),
                            onClick = { viewModel.verifyTokenForMining() },
                            strokeColor = CyberYellow
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(10.dp))
            }
        } else {
            com.sleeper.app.ui.components.CyberCard(
                modifier = Modifier.fillMaxWidth(),
                strokeColor = CyberRed,
                cornerRadius = 12.dp
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = CyberRed,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.wallet_not_connected),
                        fontSize = 9.sp,
                        color = CyberRed,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.wallet_connect_hint),
                        fontSize = 7.sp,
                        color = CyberWhite.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(10.dp))
        }
        
        // Mining Button (ГЛАВНАЯ КНОПКА!)
        MiningButton(
            text = if (uiState.isMining) stringResource(R.string.mining_stop) else stringResource(R.string.mining_start),
            onClick = {
                if (uiState.isMining) {
                    viewModel.stopMining()
                } else {
                    viewModel.startMining()
                }
            },
            enabled = uiState.energyCurrent > 0 && uiState.isTokenVerified,
            isMining = uiState.isMining
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Бусты (Seed Vault: сворачиваемая секция; по умолчанию свёрнуты — строка Sleep Points видна без свайпа)
        var boostsExpanded by remember { mutableStateOf(false) }
        val firstBoost = SkrBoostCatalog.microTxBoosts.firstOrNull()
        com.sleeper.app.ui.components.CyberCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { boostsExpanded = !boostsExpanded },
            cornerRadius = 12.dp,
            strokeColor = Stroke
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.mining_boosts),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CyberWhite
                    )
                    Icon(
                        imageVector = if (boostsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (boostsExpanded) stringResource(R.string.accessibility_collapse) else stringResource(R.string.accessibility_expand),
                        tint = CyberGray,
                        modifier = Modifier.size(22.dp)
                    )
                }
                if (boostsExpanded && firstBoost != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.mining_boost_reward_line, ((firstBoost.multiplier - 1.0) * 100).toInt(), firstBoost.durationDisplay()),
                                fontSize = 14.sp,
                                color = CyberWhite,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.mining_boost_one_tx),
                                fontSize = 12.sp,
                                color = CyberGray,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Text(
                                text = "${firstBoost.durationDisplay()} • x${String.format("%.2f", firstBoost.multiplier)}",
                                fontSize = 12.sp,
                                color = CyberGray,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = com.sleeper.app.ui.theme.surface
                        ) {
                            Text(
                                text = "%.1f SKR".format(firstBoost.priceSkrRaw / 1_000_000.0),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberYellow,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        // Mining Stats
        if (uiState.isMining) {
            com.sleeper.app.ui.components.CyberCard(
                modifier = Modifier.fillMaxWidth(),
                strokeColor = Stroke,
                cornerRadius = 12.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    StatItem(
                        label = "%.2f pts/s".format(uiState.pointsPerSecond),
                        color = CyberGreen
                    )
                    StatItem(
                        label = "${formatUptime(uiState.uptimeMinutes)} uptime",
                        color = CyberWhite
                    )
                    StatItem(
                        label = "Storage: ${uiState.storageMB}MB x${String.format("%.1f", uiState.storageMultiplier)}",
                        color = CyberYellow
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(10.dp))
        
        // Points Balance (нижняя строка — должна помещаться без свайпа)
        com.sleeper.app.ui.components.CyberCard(
            modifier = Modifier.fillMaxWidth(),
            strokeColor = CyberYellow,
            cornerRadius = 12.dp,
            glowColor = accentGold.copy(alpha = 0.15f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Points",
                        tint = CyberYellow,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.sleep_points_label),
                        fontSize = 12.sp,
                        color = CyberGray,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = String.format("%,d", uiState.pointsBalance),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = CyberYellow
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Device Info (для отладки)
        if (uiState.deviceFingerprint.isNotEmpty()) {
            Text(
                text = "Device: ${uiState.deviceFingerprint.take(16)}...",
                fontSize = 10.sp,
                color = CyberGray.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun StatItem(label: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = label,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = color
    )
}

private fun formatUptime(minutes: Long): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        hours > 0 -> "${hours}:${String.format("%02d", mins)}"
        else -> "${mins}m"
    }
}
