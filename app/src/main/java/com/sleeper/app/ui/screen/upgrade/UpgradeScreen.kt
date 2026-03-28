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
import androidx.compose.material3.AlertDialog
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
    val purchaseState by viewModel.purchaseState.collectAsState()
    val activityResultSender = LocalActivityResultSender.current

    LaunchedEffect(Unit) {
        viewModel.refreshAvailableSkr()
    }

    // Автосброс Done-состояния через 5 секунд
    LaunchedEffect(purchaseState) {
        if (purchaseState is PurchaseState.Done) {
            kotlinx.coroutines.delay(5000)
            viewModel.clearPurchaseState()
        }
    }

    // State для диалога подтверждения
    var pendingBoostId by remember { mutableStateOf<String?>(null) }

    // Screen enter stagger
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    fun enterSpec(index: Int) = fadeIn(
        tween(AppDuration.Normal, delayMillis = index * AppDuration.Stagger)
    ) + slideInVertically(
        tween(AppDuration.Normal, delayMillis = index * AppDuration.Stagger)
    ) { it / 3 }

    // Вычисляем active boost один раз для всего экрана
    val now = System.currentTimeMillis()
    val activeBoostId = userStats?.activeSkrBoostId
    val activeBoostEndsAt = userStats?.activeSkrBoostEndsAt ?: 0L

    // Диалог подтверждения покупки
    pendingBoostId?.let { boostId ->
        val boost = viewModel.skrBoosts.firstOrNull { it.id == boostId }
        if (boost != null) {
            val price = boost.priceSkrRaw / 1_000_000.0
            val balanceAfter = (availableSkrRaw - boost.priceSkrRaw) / 1_000_000.0
            AlertDialog(
                onDismissRequest = { pendingBoostId = null },
                containerColor = NightAccent,
                title = {
                    Text(
                        text = "Подтвердить покупку",
                        color = CyberWhite,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "${boost.name}  ·  ${boost.durationDisplay()}",
                            color = CyberGreen,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text("Стоимость:", color = CyberGray, style = MaterialTheme.typography.bodySmall)
                            Text("${String.format("%.1f", price)} SKR", color = CyberWhite, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text("Ваш баланс:", color = CyberGray, style = MaterialTheme.typography.bodySmall)
                            Text("${String.format("%,.2f", availableSkrRaw / 1_000_000.0)} SKR", color = CyberWhite, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text("Останется:", color = CyberGray, style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = "${String.format("%,.2f", balanceAfter)} SKR",
                                color = if (balanceAfter >= 0) CyberGray else CyberYellow,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                confirmButton = {
                    CyberButton(
                        text = "Купить",
                        onClick = {
                            viewModel.purchaseSkrBoost(boostId, activityResultSender)
                            pendingBoostId = null
                        },
                        primary = true,
                        strokeColor = CyberGreen
                    )
                },
                dismissButton = {
                    CyberButton(
                        text = "Отмена",
                        onClick = { pendingBoostId = null }
                    )
                }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Title
        Text(
            text = stringResource(R.string.upgrade_screen_title).uppercase(),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = CyberWhite
        )

        // Purchase message
        val doneState = purchaseState as? PurchaseState.Done
        AnimatedVisibility(
            visible = doneState != null,
            enter = fadeIn(tween(AppDuration.Fast)) + expandVertically(),
            exit = fadeOut(tween(AppDuration.Fast)) + shrinkVertically()
        ) {
            doneState?.let { done ->
                Text(
                    text = done.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (done.success) CyberGreen else CyberYellow,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Energy card
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

        // Boosts header
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
                    text = stringResource(R.string.upgrade_available,
                        String.format("%,.2f", availableSkrRaw / 1_000_000.0)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberGray
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Boost cards
        viewModel.skrBoosts.forEachIndexed { i, boost ->
            AnimatedVisibility(visible = visible, enter = enterSpec(2 + i)) {
                Column {
                    val isActive = activeBoostId == boost.id && activeBoostEndsAt > now
                    val remainingMs = if (isActive) activeBoostEndsAt - now else 0L
                    SkrBoostCard(
                        boost = boost,
                        name = stringResource(boostNameResId(boost.id)),
                        description = stringResource(boostDescResId(boost.id)),
                        availableSkrRaw = availableSkrRaw,
                        purchaseState = purchaseState,
                        isActive = isActive,
                        remainingMs = remainingMs,
                        onPurchase = { pendingBoostId = boost.id }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Genesis NFT header
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
                            Column {
                                Text(
                                    text = stringResource(R.string.mining_genesis_holder),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberYellow
                                )
                                stats.genesisNftNumber?.let { num ->
                                    Text(
                                        text = "Genesis #$num",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyberYellow.copy(alpha = 0.7f)
                                    )
                                }
                                stats.genesisNftMint?.let { mint ->
                                    Text(
                                        text = "${mint.take(8)}...${mint.takeLast(6)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyberGray
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.upgrade_genesis_forever,
                                    ((stats.genesisNftMultiplier - 1.0) * 100).toInt()),
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
                            val isGenesisLoading =
                                (purchaseState is PurchaseState.Pending &&
                                    (purchaseState as PurchaseState.Pending).boostId == "genesis_nft") ||
                                (purchaseState is PurchaseState.Verifying &&
                                    (purchaseState as PurchaseState.Verifying).boostId == "genesis_nft")
                            CyberButton(
                                text = when {
                                    isGenesisLoading &&
                                        purchaseState is PurchaseState.Pending -> "Подпись..."
                                    isGenesisLoading -> "Проверка..."
                                    else -> stringResource(R.string.upgrade_mint_button)
                                },
                                onClick = { viewModel.purchaseGenesisNft(activityResultSender) },
                                enabled = availableSkrRaw >= viewModel.genesisNftPriceSkrRaw &&
                                    purchaseState is PurchaseState.Idle,
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
    purchaseState: PurchaseState,
    isActive: Boolean,
    remainingMs: Long,
    onPurchase: () -> Unit
) {
    val canAfford = availableSkrRaw >= boost.priceSkrRaw
    val priceSkrHuman = boost.priceSkrRaw / 1_000_000.0

    val isPending = purchaseState is PurchaseState.Pending &&
        (purchaseState as PurchaseState.Pending).boostId == boost.id
    val isVerifying = purchaseState is PurchaseState.Verifying &&
        (purchaseState as PurchaseState.Verifying).boostId == boost.id
    val isLoading = isPending || isVerifying

    val cardStroke = if (isActive) CyberGreen else Stroke

    CyberCard(
        modifier = Modifier.fillMaxWidth(),
        strokeColor = cardStroke,
        cornerRadius = 12.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) CyberGreen else CyberWhite
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
                val btnText = when {
                    isPending   -> "Подпись..."
                    isVerifying -> "Проверка..."
                    isActive    -> "⟳ Продлить"
                    else        -> "${String.format("%.1f", priceSkrHuman)} SKR"
                }
                CyberButton(
                    text = btnText,
                    onClick = onPurchase,
                    modifier = Modifier.width(110.dp),
                    enabled = canAfford && (purchaseState is PurchaseState.Idle || isActive),
                    primary = true,
                    strokeColor = if (isActive) CyberGreen else CyberGreen,
                    height = 44.dp
                )
            }

            // Active badge
            if (isActive && remainingMs > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                val h = remainingMs / 3_600_000
                val m = (remainingMs % 3_600_000) / 60_000
                val timeStr = if (h > 0) "${h}ч ${m}м" else "${m}м"
                Text(
                    text = "● АКТИВЕН · ещё $timeStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberGreen,
                    fontWeight = FontWeight.Bold
                )
            }

            // Loading progress hint
            if (isLoading) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isPending) "Открываем кошелёк для подписи..." else "Ожидаем подтверждения в сети...",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberGray
                )
            }
        }
    }
}

private fun boostNameResId(boostId: String): Int = when (boostId) {
    "boost_7h"  -> R.string.boost_7h_name
    "boost_7x"  -> R.string.boost_7x_name
    "boost_49x" -> R.string.boost_49x_name
    "skr_lite"  -> R.string.skr_lite_name
    "skr_plus"  -> R.string.skr_plus_name
    "skr_pro"   -> R.string.skr_pro_name
    "skr_ultra" -> R.string.skr_ultra_name
    else        -> R.string.boost_7h_name
}

private fun boostDescResId(boostId: String): Int = when (boostId) {
    "boost_7h"  -> R.string.boost_7h_desc
    "boost_7x"  -> R.string.boost_7x_desc
    "boost_49x" -> R.string.boost_49x_desc
    "skr_lite"  -> R.string.skr_lite_desc
    "skr_plus"  -> R.string.skr_plus_desc
    "skr_pro"   -> R.string.skr_pro_desc
    "skr_ultra" -> R.string.skr_ultra_desc
    else        -> R.string.boost_7h_desc
}
