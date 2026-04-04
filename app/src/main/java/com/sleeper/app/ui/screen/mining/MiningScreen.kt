package com.sleeper.app.ui.screen.mining

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sleeper.app.BuildConfig
import com.sleeper.app.R
import com.sleeper.app.ui.components.CyberCard
import com.sleeper.app.ui.components.CyberButton
import com.sleeper.app.ui.components.EnergyBar
import com.sleeper.app.ui.components.MiningHeroOrb
import com.sleeper.app.ui.components.StatusChip
import com.sleeper.app.ui.theme.*
import com.sleeper.app.data.local.SkrBoostCatalog
import kotlinx.coroutines.delay

/** Stagger enter for a LazyColumn item at [index]. */
@Composable
private fun staggerEnter(index: Int) = fadeIn(
    tween(AppDuration.Normal, delayMillis = index * AppDuration.Stagger)
) + slideInVertically(
    tween(AppDuration.Normal, delayMillis = index * AppDuration.Stagger)
) { it / 3 }

@Composable
fun MiningScreen(
    viewModel: MiningViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refreshWalletAndSkr()
        viewModel.refreshStakeIfNeeded()
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60 * 1000L)
            viewModel.refreshStakeIfNeeded()
        }
    }

    // Auto-dismiss tier-up banner after 5 seconds
    LaunchedEffect(uiState.showTierUpBanner) {
        if (uiState.showTierUpBanner) {
            delay(5_000)
            viewModel.dismissTierUpBanner()
        }
    }

    // Auto-dismiss season-complete banner after 8 seconds
    LaunchedEffect(uiState.showSeasonCompleteBanner) {
        if (uiState.showSeasonCompleteBanner) {
            delay(8_000)
            viewModel.dismissSeasonCompleteBanner()
        }
    }

    // Device check gates
    when {
        uiState.isVerifying -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CyberGreen)
            }
            return
        }
        !uiState.isDeviceValid -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .background(CyberRed.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                        .padding(24.dp)
                ) {
                    Text(
                        text = uiState.errorMessage,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberRed,
                        textAlign = TextAlign.Center
                    )
                }
            }
            return
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Hero zone (fixed, non-scrollable) ──────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.38f),
            contentAlignment = Alignment.Center
        ) {
            MiningHeroOrb(
                isMining = uiState.isMining,
                npPerSecond = uiState.pointsPerSecond.toFloat(),
                onClick = {
                    if (uiState.isMining) viewModel.stopMining() else viewModel.startMining()
                },
                enabled = uiState.energyCurrent > 0 && uiState.isTokenVerified,
                uptimeMins = uiState.uptimeMinutes
            )
        }

        // ── Scrollable details ─────────────────────────────────────────────────
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.62f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // ── Tier-up celebration banner ─────────────────────────────────────
            item {
                AnimatedVisibility(
                    visible = uiState.showTierUpBanner,
                    enter = fadeIn(tween(300)) + slideInVertically { -it },
                    exit = fadeOut(tween(300)) + slideOutVertically { -it }
                ) {
                    CyberCard(
                        modifier = Modifier.fillMaxWidth(),
                        strokeColor = CyberGreen,
                        glowColor = accentGreen
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "🚀 TIER UP!  ${uiState.tierUpFrom} → ${uiState.tierUpTo}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberGreen
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Season is accelerating — invite more friends!",
                                    fontSize = 12.sp,
                                    color = CyberGray
                                )
                            }
                            IconButton(onClick = { viewModel.dismissTierUpBanner() }) {
                                Icon(Icons.Default.Close, null, tint = CyberGray, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // ── Season complete banner ─────────────────────────────────────────
            item {
                AnimatedVisibility(
                    visible = uiState.showSeasonCompleteBanner,
                    enter = fadeIn(tween(300)) + slideInVertically { -it },
                    exit = fadeOut(tween(300)) + slideOutVertically { -it }
                ) {
                    CyberCard(
                        modifier = Modifier.fillMaxWidth(),
                        strokeColor = CyberYellow,
                        glowColor = accentGold
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "🏁 Season ${uiState.completedSeasonNumber} Complete!",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberYellow
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Season ${uiState.seasonNumber} has started. Keep mining!",
                                    fontSize = 12.sp,
                                    color = CyberGray
                                )
                            }
                            IconButton(onClick = { viewModel.dismissSeasonCompleteBanner() }) {
                                Icon(Icons.Default.Close, null, tint = CyberGray, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // Block + stats row — index 0
            item {
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }
                AnimatedVisibility(visible = visible, enter = staggerEnter(0)) {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Refresh, "Block", tint = CyberGreen, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.mining_block, uiState.currentBlock),
                                        fontSize = 16.sp,
                                        color = CyberWhite,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.mining_difficulty, uiState.difficulty), fontSize = 14.sp, color = CyberGray)
                                    if (uiState.isDemoNetworkStats) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.mining_demo), fontSize = 12.sp, color = CyberGray)
                                    }
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(stringResource(R.string.mining_reward_label), fontSize = 12.sp, color = CyberGray)
                                Spacer(Modifier.height(4.dp))
                                Text("500 pts", fontSize = 18.sp, color = CyberYellow, fontWeight = FontWeight.ExtraBold)
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Person, "Online", tint = CyberGreen, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = String.format("%,d", uiState.onlineUsers),
                                        fontSize = 14.sp, color = CyberWhite
                                    )
                                    if (uiState.isDemoNetworkStats) {
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.mining_demo), fontSize = 12.sp, color = CyberGray)
                                    }
                                }
                                // Sleep Points мини-чип
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Star, "SP", tint = CyberYellow, modifier = Modifier.size(11.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        text = String.format("%,d SP", uiState.pointsBalance),
                                        fontSize = 11.sp,
                                        color = CyberYellow,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Acceleration Tier card — index 1
            item {
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }
                AnimatedVisibility(visible = visible, enter = staggerEnter(1)) {
                    AccelerationTierCard(
                        tier = uiState.accelerationTier,
                        activeDevices = uiState.onlineUsers,
                        seasonDays = uiState.seasonDays,
                        daysRemaining = uiState.seasonDaysRemaining,
                        seasonNumber = uiState.seasonNumber,
                        referralCode = uiState.referralCode,
                        onInviteClick = {
                            val text = if (uiState.referralCode.isNotBlank())
                                "Join Seeker Mining — earn SPR tokens while you sleep! Use my code: ${uiState.referralCode}\nhttps://seekermining.com"
                            else
                                "Join Seeker Mining — earn SPR tokens while you sleep!\nhttps://seekermining.com"
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(intent, "Invite friends"))
                        }
                    )
                }
            }

            // Energy + Stake — объединённая карточка (index 2)
            item {
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }
                AnimatedVisibility(visible = visible, enter = staggerEnter(1)) {
                    CyberCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            // Energy bar
                            EnergyBar(
                                current = uiState.energyCurrent,
                                max = uiState.energyMax,
                                isMining = uiState.isMining
                            )
                            Text(
                                text = if (uiState.energyCurrent >= uiState.energyMax) {
                                    stringResource(R.string.mining_energy_hint_full)
                                } else if (!uiState.isMining && uiState.energyCurrent < uiState.energyMax) {
                                    stringResource(R.string.mining_energy_hint_recovery)
                                } else {
                                    stringResource(R.string.mining_energy_hint_rates)
                                },
                                fontSize = 11.sp,
                                color = CyberGray,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            // Divider
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = CyberGray.copy(alpha = 0.15f), thickness = 0.5.dp)
                            Spacer(Modifier.height(8.dp))
                            // Stake row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (uiState.stakedSkrHuman > 0)
                                        stringResource(R.string.mining_stake_format, String.format("%,.0f", uiState.stakedSkrHuman))
                                    else
                                        stringResource(R.string.mining_stake_none),
                                    fontSize = 13.sp,
                                    color = CyberWhite
                                )
                                Text(
                                    text = if (uiState.stakeMultiplier > 1.0)
                                        stringResource(R.string.mining_stake_bonus, ((uiState.stakeMultiplier - 1.0) * 100).toInt())
                                    else "x1.0",
                                    fontSize = 13.sp,
                                    color = CyberGreen,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // Genesis NFT badge — index 3, animated in/out
            item {
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }
                AnimatedVisibility(visible = visible, enter = staggerEnter(3)) {
                    AnimatedVisibility(
                        visible = uiState.hasGenesisNft,
                        enter = fadeIn(tween(AppDuration.Normal)) + expandVertically(),
                        exit = fadeOut(tween(AppDuration.Fast)) + shrinkVertically()
                    ) {
                        CyberCard(
                            modifier = Modifier.fillMaxWidth(),
                            strokeColor = CyberYellow,
                            glowColor = accentGold
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
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
                }
            }

            // Low energy warning — index 4, animated in/out
            item {
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }
                AnimatedVisibility(visible = visible, enter = staggerEnter(4)) {
                    AnimatedVisibility(
                        visible = uiState.showLowEnergyWarning,
                        enter = fadeIn(tween(AppDuration.Normal)) + expandVertically(),
                        exit = fadeOut(tween(AppDuration.Fast)) + shrinkVertically()
                    ) {
                        CyberCard(modifier = Modifier.fillMaxWidth(), strokeColor = CyberYellow) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Default.Warning, null, tint = CyberYellow, modifier = Modifier.size(24.dp))
                                Text(
                                    text = stringResource(R.string.mining_low_energy_warning),
                                    fontSize = 14.sp,
                                    color = CyberWhite,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // Token verification (3 states via Crossfade) — index 5
            item {
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }
                AnimatedVisibility(visible = visible, enter = staggerEnter(5)) {
                    val tokenKey = "${uiState.walletConnected}_${uiState.isTokenVerified}_${uiState.skrUsername}"
                    Crossfade(targetState = tokenKey, animationSpec = tween(AppDuration.Normal), label = "tokenState") {
                        when {
                            uiState.walletConnected && uiState.isTokenVerified && uiState.skrUsername != null -> {
                                CyberCard(modifier = Modifier.fillMaxWidth(), strokeColor = CyberGreen, glowColor = accentGreen) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CheckCircle, "Verified", tint = CyberGreen, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Column {
                                                Text(stringResource(R.string.skr_mining_authorized), fontSize = 12.sp, color = CyberGreen, fontWeight = FontWeight.Bold)
                                                Spacer(Modifier.height(2.dp))
                                                Text(uiState.skrUsername ?: "", fontSize = 13.sp, color = CyberWhite, fontWeight = FontWeight.ExtraBold)
                                            }
                                        }
                                    }
                                }
                            }
                            uiState.walletConnected -> {
                                CyberCard(modifier = Modifier.fillMaxWidth(), strokeColor = CyberYellow) {
                                    Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.Lock, "Locked", tint = CyberYellow, modifier = Modifier.size(24.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text(stringResource(R.string.skr_required), fontSize = 13.sp, color = CyberYellow, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                        Spacer(Modifier.height(4.dp))
                                        Text(stringResource(R.string.skr_required_hint), fontSize = 12.sp, color = CyberWhite.copy(alpha = 0.8f), textAlign = TextAlign.Center)
                                        Spacer(Modifier.height(8.dp))
                                        CyberButton(text = stringResource(R.string.skr_verify_button), onClick = { viewModel.verifyTokenForMining() }, strokeColor = CyberYellow)
                                    }
                                }
                            }
                            else -> {
                                CyberCard(modifier = Modifier.fillMaxWidth(), strokeColor = CyberRed) {
                                    Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.Warning, "Warning", tint = CyberRed, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.height(8.dp))
                                        Text(stringResource(R.string.wallet_not_connected), fontSize = 13.sp, color = CyberRed, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                        Spacer(Modifier.height(4.dp))
                                        Text(stringResource(R.string.wallet_connect_hint), fontSize = 12.sp, color = CyberWhite.copy(alpha = 0.8f), textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Boosts (collapsible with animation) — index 7
            item {
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }
                AnimatedVisibility(visible = visible, enter = staggerEnter(7)) {
                    var boostsExpanded by remember { mutableStateOf(false) }
                    val firstBoost = SkrBoostCatalog.legacyBoosts.firstOrNull()
                    CyberCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { boostsExpanded = !boostsExpanded }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.mining_boosts), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = CyberWhite)
                                Icon(
                                    imageVector = if (boostsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = CyberGray,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            AnimatedVisibility(
                                visible = boostsExpanded && firstBoost != null,
                                enter = fadeIn(tween(AppDuration.Normal)) + expandVertically(),
                                exit = fadeOut(tween(AppDuration.Fast)) + shrinkVertically()
                            ) {
                                if (firstBoost != null) {
                                    Column {
                                        Spacer(Modifier.height(10.dp))
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = stringResource(R.string.mining_boost_reward_line, ((firstBoost.multiplier - 1.0) * 100).toInt(), firstBoost.durationDisplay()),
                                                    fontSize = 14.sp, color = CyberWhite, fontWeight = FontWeight.Medium
                                                )
                                                Text(stringResource(R.string.mining_boost_one_tx), fontSize = 12.sp, color = CyberGray, modifier = Modifier.padding(top = 4.dp))
                                            }
                                            Surface(shape = RoundedCornerShape(8.dp), color = com.sleeper.app.ui.theme.surface) {
                                                Text(
                                                    text = "%.1f SKR".format(firstBoost.priceSkrRaw / 1_000_000.0),
                                                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CyberYellow,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Mining stats перенесены внутрь орба (uptimeMins показывается там)

            // Sleep Points перенесены в Block карточку как мини-чип (index 8 удалён)

            // Device fingerprint — DEBUG builds only
            if (BuildConfig.DEBUG && uiState.deviceFingerprint.isNotEmpty()) {
                item {
                    Text(
                        text = "Device: ${uiState.deviceFingerprint.take(16)}...",
                        fontSize = 12.sp,
                        color = CyberGray.copy(alpha = 0.4f)
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/**
 * Shows current Acceleration Mining tier and season speed info.
 * More active miners = shorter season = earlier token liquidity.
 */
/**
 * Shows current Acceleration Mining tier and season speed info.
 * Season duration is exponential — the more miners, the faster it goes.
 * 100K+ miners → ~1 week/season. 130K+ → exponential cliff. 150K → ~1 day.
 */
@Composable
private fun AccelerationTierCard(
    tier: String,
    activeDevices: Int,
    seasonDays: Int,
    daysRemaining: Int,
    seasonNumber: Int,
    referralCode: String = "",
    onInviteClick: () -> Unit = {},
) {
    val (tierIcon, tierColor) = when (tier) {
        "Global"  -> "💫" to CyberGreen
        "Mass"    -> "🚀" to CyberGreen
        "Viral"   -> "⚡" to CyberYellow
        "Active"  -> "🔥" to CyberYellow
        "Growing" -> "🏃" to CyberWhite
        "Pioneer" -> "🚶" to CyberGray
        else      -> "🐌" to CyberGray   // Genesis
    }
    val nextTierHint = when (tier) {
        "Genesis"  -> "500 miners → ~90 days/season"
        "Pioneer"  -> "5K miners → ~56 days/season"
        "Growing"  -> "15K miners → ~28 days/season"
        "Active"   -> "40K miners → ~14 days/season"
        "Viral"    -> "80K miners → ~7 days/season"
        "Mass"     -> "130K+ → exponential cliff ⚡"
        else       -> "Max speed — all 10B SPR mining at full throttle!"
    }
    val durationLabel = when {
        seasonDays >= 60 -> "${seasonDays / 7} weeks"
        seasonDays >= 2  -> "$seasonDays days"
        else             -> "~${seasonDays * 24}h"
    }
    val remainingLabel = when {
        daysRemaining <= 0 -> null
        daysRemaining == 1 -> "~${daysRemaining * 24}h left"
        else               -> "$daysRemaining days left"
    }

    // Season progress (0.0 → 1.0)
    val progress = if (seasonDays > 0)
        (1f - daysRemaining.toFloat() / seasonDays).coerceIn(0f, 1f)
    else 0f

    CyberCard(modifier = Modifier.fillMaxWidth(), strokeColor = tierColor) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "$tierIcon $tier  •  Season $seasonNumber",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = tierColor
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${String.format("%,d", activeDevices)} miners online",
                        fontSize = 12.sp,
                        color = CyberGray
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = durationLabel,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CyberWhite
                    )
                    if (remainingLabel != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(text = remainingLabel, fontSize = 12.sp, color = CyberGray)
                    }
                }
            }

            // Season progress bar
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = tierColor,
                trackColor = tierColor.copy(alpha = 0.15f),
            )

            // Invite row (clickable)
            if (tier != "Global") {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = CyberGray.copy(alpha = 0.15f), thickness = 0.5.dp)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onInviteClick() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Invite",
                        tint = tierColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Invite: $nextTierHint",
                        fontSize = 11.sp,
                        color = tierColor,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiningStatItem(label: String, color: androidx.compose.ui.graphics.Color) {
    Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = color)
}

private fun formatUptime(minutes: Long): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return if (hours > 0) "${hours}:${String.format("%02d", mins)}" else "${mins}m"
}
