package com.sleeper.app.ui.screen.upgrade

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sleeper.app.LocalActivityResultSender
import com.sleeper.app.R
import com.sleeper.app.data.local.SkrBoostItem
import com.sleeper.app.ui.components.CyberButton
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

    LaunchedEffect(Unit) {
        viewModel.refreshAvailableSkr()
    }

    // Screen enter stagger state
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
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Title — instant
        Text(
            text = stringResource(R.string.upgrade_screen_title).uppercase(),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = CyberWhite
        )

        // Purchase message — animated in/out
        AnimatedVisibility(
            visible = purchaseMessage != null,
            enter = fadeIn(tween(AppDuration.Fast)) + expandVertically(),
            exit = fadeOut(tween(AppDuration.Fast)) + shrinkVertically()
        ) {
            purchaseMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (purchaseSuccess == true) CyberGreen else CyberYellow,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Energy card — index 0
        AnimatedVisibility(visible = visible, enter = enterSpec(0)) {
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
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Boosts header — index 1
        AnimatedVisibility(visible = visible, enter = enterSpec(1)) {
            Column {
                Text(
                    text = stringResource(R.string.upgrade_boosts_skr),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyberGreen
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.upgrade_available, String.format("%,.2f", availableSkrRaw / 1_000_000.0)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberGray
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Boost cards — staggered starting at index 2
        viewModel.skrBoosts.forEachIndexed { i, boost ->
            AnimatedVisibility(visible = visible, enter = enterSpec(2 + i)) {
                Column {
                    SkrBoostCard(
                        boost = boost,
                        name = stringResource(boostNameResId(boost.id)),
                        description = stringResource(boostDescResId(boost.id)),
                        availableSkrRaw = availableSkrRaw,
                        onPurchase = { viewModel.purchaseSkrBoost(boost.id, activityResultSender) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Genesis NFT header — after boosts
        val genesisIndex = 2 + viewModel.skrBoosts.size
        AnimatedVisibility(visible = visible, enter = enterSpec(genesisIndex)) {
            Text(
                text = stringResource(R.string.upgrade_genesis_nft),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = CyberYellow
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Genesis owned vs not owned — Crossfade
        AnimatedVisibility(visible = visible, enter = enterSpec(genesisIndex + 1)) {
            val hasNft = userStats?.hasGenesisNft == true
            Crossfade(
                targetState = hasNft,
                animationSpec = tween(AppDuration.Normal),
                label = "genesisState"
            ) { owned ->
                if (owned) {
                    val stats = userStats!!
                    CyberCard(
                        modifier = Modifier.fillMaxWidth(),
                        strokeColor = CyberYellow,
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
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = CyberYellow
                            )
                            Text(
                                text = stringResource(R.string.upgrade_genesis_forever, ((stats.genesisNftMultiplier - 1.0) * 100).toInt()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = CyberGray
                            )
                        }
                    }
                } else {
                    CyberCard(
                        modifier = Modifier.fillMaxWidth(),
                        strokeColor = CyberYellow,
                        cornerRadius = 12.dp
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.upgrade_genesis_description),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = CyberWhite
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.upgrade_genesis_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = CyberGray
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            CyberButton(
                                text = stringResource(R.string.upgrade_mint_button),
                                onClick = { viewModel.purchaseGenesisNft(activityResultSender) },
                                enabled = availableSkrRaw >= viewModel.genesisNftPriceSkrRaw,
                                primary = true,
                                strokeColor = CyberYellow
                            )
                        }
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
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyberWhite
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberGray
                )
                Text(
                    text = "${boost.durationDisplay()} · x${String.format("%.2f", boost.multiplier)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberGray
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            CyberButton(
                text = "${String.format("%.1f", priceSkrHuman)} SKR",
                onClick = onPurchase,
                modifier = Modifier.width(110.dp),
                enabled = canAfford,
                primary = true,
                strokeColor = CyberGreen,
                height = 44.dp
            )
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
