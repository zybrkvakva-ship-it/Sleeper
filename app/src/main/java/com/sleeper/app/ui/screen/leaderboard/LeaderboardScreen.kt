package com.sleeper.app.ui.screen.leaderboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sleeper.app.R
import com.sleeper.app.ui.components.CyberCard
import com.sleeper.app.ui.components.LeaderboardSkeletonItem
import com.sleeper.app.ui.theme.CyberGray
import com.sleeper.app.ui.theme.CyberGreen
import com.sleeper.app.ui.theme.CyberWhite
import com.sleeper.app.ui.theme.CyberYellow
import com.sleeper.app.ui.theme.GoldGlow
import com.sleeper.app.ui.theme.Stroke
import com.sleeper.app.ui.theme.accentGold
import com.sleeper.app.ui.theme.accentGreen
import com.sleeper.app.ui.theme.AppDuration

private val GoldStroke   = Color(0xFFFFD700)
private val SilverStroke = Color(0xFFC0C0C0)
private val BronzeStroke = Color(0xFFCD7F32)

@Composable
fun LeaderboardScreen(
    viewModel: LeaderboardViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header
        item {
            Text(
                text = stringResource(R.string.leaderboard_screen_title).uppercase(),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = CyberWhite
            )
            Spacer(Modifier.height(8.dp))
        }

        if (uiState.isLoading) {
            // Skeleton loading state
            items(7) {
                LeaderboardSkeletonItem()
            }
        } else {
            // Staggered entry list
            itemsIndexed(uiState.topPlayers) { index, entry ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(tween(AppDuration.Normal, delayMillis = index * AppDuration.Stagger)) +
                            slideInVertically(tween(AppDuration.Normal, delayMillis = index * AppDuration.Stagger)) { it / 3 }
                ) {
                    LeaderboardItem(entry)
                }
            }

            // Current user row
            item {
                Spacer(Modifier.height(4.dp))
                CyberCard(
                    modifier = Modifier.fillMaxWidth(),
                    strokeColor = CyberYellow,
                    glowColor = accentGold
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "#${uiState.userRank}: ${uiState.currentUserSkrUsername ?: stringResource(R.string.leaderboard_you)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = CyberYellow
                        )
                        Text(
                            text = stringResource(R.string.leaderboard_blocks_format, String.format("%,d", uiState.userBlocks)),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = CyberYellow
                        )
                    }
                }
            }

            // Demo indicator
            if (uiState.isDemoData) {
                item {
                    Text(
                        text = stringResource(R.string.leaderboard_demo_data),
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberGray,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            // Global stats
            item {
                Spacer(Modifier.height(8.dp))
                CyberCard(modifier = Modifier.fillMaxWidth(), strokeColor = Stroke) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.leaderboard_global).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = CyberWhite
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.leaderboard_total_in_network), style = MaterialTheme.typography.bodyMedium, color = CyberGray)
                            Text(
                                "${String.format("%,d", uiState.globalStats.totalPoints)} pts",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = CyberGreen
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.leaderboard_blocks_found), style = MaterialTheme.typography.bodyMedium, color = CyberGray)
                            Text(
                                String.format("%,d", uiState.globalStats.totalBlocks),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = CyberGreen
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun LeaderboardItem(entry: LeaderboardEntry) {
    val (strokeColor, glowColor, medalEmoji) = when (entry.rank) {
        1    -> Triple(GoldStroke,   accentGold,  "🥇")
        2    -> Triple(SilverStroke, null,         "🥈")
        3    -> Triple(BronzeStroke, null,         "🥉")
        else -> Triple(Stroke,       null,         null)
    }

    CyberCard(
        modifier = Modifier.fillMaxWidth(),
        strokeColor = strokeColor,
        glowColor = glowColor
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Rank / medal
                if (medalEmoji != null) {
                    Text(text = medalEmoji, fontSize = 20.sp, modifier = Modifier.width(32.dp))
                } else {
                    Text(
                        text = "#${entry.rank}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyberWhite,
                        modifier = Modifier.width(32.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = entry.username,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (entry.rank <= 3) CyberWhite else CyberWhite.copy(alpha = 0.85f)
                )
            }
            Text(
                text = stringResource(R.string.leaderboard_blocks_format, String.format("%,d", entry.blocks)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = when (entry.rank) {
                    1 -> CyberYellow
                    2 -> SilverStroke
                    3 -> BronzeStroke
                    else -> CyberGray
                }
            )
        }
    }
}
