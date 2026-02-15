package com.sleeper.app.ui.screen.upgrade

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sleeper.app.LocalActivityResultSender
import com.sleeper.app.R
import com.sleeper.app.data.local.SkrBoostItem
import com.sleeper.app.ui.components.CyberCard
import com.sleeper.app.ui.components.EnergyBar
import com.sleeper.app.ui.theme.*

@Composable
fun UpgradeScreen(
    viewModel: UpgradeViewModel = viewModel()
) {
    val userStats by viewModel.userStats.collectAsState()
    val availableSkrRaw by viewModel.availableSkrRaw.collectAsState()
    val purchaseMessage by viewModel.purchaseMessage.collectAsState()
    val purchaseSuccess by viewModel.purchaseSuccess.collectAsState()
    val activityResultSender = LocalActivityResultSender.current

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refreshAvailableSkr()
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(com.sleeper.app.ui.theme.BgMain)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.upgrade_screen_title).uppercase(),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = com.sleeper.app.ui.theme.CyberWhite
        )
        
        purchaseMessage?.let { msg ->
            Text(
                text = msg,
                fontSize = 12.sp,
                color = if (purchaseSuccess == true) com.sleeper.app.ui.theme.CyberGreen else com.sleeper.app.ui.theme.CyberYellow,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        // Energy (в карточке, как на Майнинге)
        userStats?.let { stats ->
            CyberCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 12.dp,
                strokeColor = Stroke
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    EnergyBar(
                        current = stats.energyCurrent,
                        max = stats.energyMax
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Бусты за SKR
        Text(
            text = stringResource(R.string.upgrade_boosts_skr),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = com.sleeper.app.ui.theme.CyberGreen
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.upgrade_available, String.format("%,.2f", availableSkrRaw / 1_000_000.0)),
            fontSize = 14.sp,
            color = com.sleeper.app.ui.theme.CyberGray
        )
        Spacer(modifier = Modifier.height(12.dp))
        viewModel.skrBoosts.forEach { boost ->
            SkrBoostCard(
                boost = boost,
                name = stringResource(boostNameResId(boost.id)),
                description = stringResource(boostDescResId(boost.id)),
                availableSkrRaw = availableSkrRaw,
                onPurchase = { viewModel.purchaseSkrBoost(boost.id, activityResultSender) }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Genesis NFT
        Text(
            text = stringResource(R.string.upgrade_genesis_nft),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = com.sleeper.app.ui.theme.CyberYellow
        )
        Spacer(modifier = Modifier.height(8.dp))
        val stats = userStats
        if (stats?.hasGenesisNft == true) {
            CyberCard(
                modifier = Modifier.fillMaxWidth(),
                strokeColor = com.sleeper.app.ui.theme.CyberYellow,
                cornerRadius = 12.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.mining_genesis_holder),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = com.sleeper.app.ui.theme.CyberYellow
                    )
                    Text(
                        text = stringResource(R.string.upgrade_genesis_forever, ((stats.genesisNftMultiplier - 1.0) * 100).toInt()),
                        fontSize = 14.sp,
                        color = com.sleeper.app.ui.theme.CyberGray
                    )
                }
            }
        } else {
            CyberCard(
                modifier = Modifier.fillMaxWidth(),
                strokeColor = com.sleeper.app.ui.theme.CyberYellow,
                cornerRadius = 12.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = stringResource(R.string.upgrade_genesis_description),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = com.sleeper.app.ui.theme.CyberWhite
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.upgrade_genesis_hint),
                        fontSize = 14.sp,
                        color = com.sleeper.app.ui.theme.CyberGray
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { viewModel.purchaseGenesisNft(activityResultSender) },
                        enabled = availableSkrRaw >= viewModel.genesisNftPriceSkrRaw,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = com.sleeper.app.ui.theme.CyberYellow,
                            contentColor = BgMain,
                            disabledContainerColor = CyberGray,
                            disabledContentColor = BgMain
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.upgrade_mint_button),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkrBoostCard(
    boost: SkrBoostItem,
    name: String,
    description: String,
    availableSkrRaw: Long,
    onPurchase: () -> Unit
) {
    val canAfford = availableSkrRaw >= boost.priceSkrRaw
    val priceSkrHuman = boost.priceSkrRaw / 1_000_000.0
    CyberCard(
        modifier = Modifier.fillMaxWidth(),
        strokeColor = Stroke,
        cornerRadius = 12.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberWhite
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 14.sp,
                    color = CyberGray
                )
                Text(
                    text = "${boost.durationDisplay()} · x${String.format("%.2f", boost.multiplier)}",
                    fontSize = 12.sp,
                    color = CyberGray
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = onPurchase,
                enabled = canAfford,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberGreen,
                    contentColor = BgMain,
                    disabledContainerColor = CyberGray,
                    disabledContentColor = BgMain
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "${String.format("%.1f", priceSkrHuman)} SKR",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun boostNameResId(boostId: String): Int = when (boostId) {
    "boost_7h" -> R.string.boost_7h_name
    "boost_7x" -> R.string.boost_7x_name
    "boost_49x" -> R.string.boost_49x_name
    "skr_lite" -> R.string.skr_lite_name
    "skr_plus" -> R.string.skr_plus_name
    "skr_pro" -> R.string.skr_pro_name
    "skr_ultra" -> R.string.skr_ultra_name
    else -> R.string.boost_7h_name
}

private fun boostDescResId(boostId: String): Int = when (boostId) {
    "boost_7h" -> R.string.boost_7h_desc
    "boost_7x" -> R.string.boost_7x_desc
    "boost_49x" -> R.string.boost_49x_desc
    "skr_lite" -> R.string.skr_lite_desc
    "skr_plus" -> R.string.skr_plus_desc
    "skr_pro" -> R.string.skr_pro_desc
    "skr_ultra" -> R.string.skr_ultra_desc
    else -> R.string.boost_7h_desc
}
