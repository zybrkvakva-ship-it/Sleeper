package com.sleeper.app.ui.screen.leaderboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sleeper.app.R
import com.sleeper.app.ui.components.CyberCard
import com.sleeper.app.ui.theme.BgMain
import com.sleeper.app.ui.theme.CyberGray
import com.sleeper.app.ui.theme.CyberGreen
import com.sleeper.app.ui.theme.CyberWhite
import com.sleeper.app.ui.theme.CyberYellow
import com.sleeper.app.ui.theme.Stroke

@Composable
fun LeaderboardScreen(
    viewModel: LeaderboardViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgMain)
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.leaderboard_screen_title).uppercase(),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = CyberWhite
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = CyberGreen)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f)
            ) {
                items(uiState.topPlayers) { entry ->
                    LeaderboardItem(entry)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    CyberCard(
                        modifier = Modifier.fillMaxWidth(),
                        strokeColor = CyberYellow,
                        cornerRadius = 12.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "#${uiState.userRank}: ${uiState.currentUserSkrUsername ?: stringResource(R.string.leaderboard_you)}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberYellow
                            )
                            Text(
                                text = stringResource(R.string.leaderboard_blocks_format, String.format("%,d", uiState.userBlocks)),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberYellow
                            )
                        }
                    }
                }
            }
            
            if (uiState.isDemoData) {
                Text(
                    text = stringResource(R.string.leaderboard_demo_data),
                    fontSize = 12.sp,
                    color = CyberGray,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            CyberCard(
                modifier = Modifier.fillMaxWidth(),
                strokeColor = Stroke,
                cornerRadius = 12.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.leaderboard_global).uppercase(),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberWhite
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.leaderboard_total_in_network),
                            fontSize = 14.sp,
                            color = CyberGray
                        )
                        Text(
                            text = "${String.format("%,d", uiState.globalStats.totalPoints)} pts",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberGreen
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.leaderboard_blocks_found),
                            fontSize = 14.sp,
                            color = CyberGray
                        )
                        Text(
                            text = String.format("%,d", uiState.globalStats.totalBlocks),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberGreen
                        )
                    }
                }
            }
        }
    }
}

private val GoldStroke = androidx.compose.ui.graphics.Color(0xFFFFD700)
private val SilverStroke = androidx.compose.ui.graphics.Color(0xFFC0C0C0)
private val BronzeStroke = androidx.compose.ui.graphics.Color(0xFFCD7F32)

@Composable
private fun LeaderboardItem(entry: LeaderboardEntry) {
    val strokeColor = when (entry.rank) {
        1 -> GoldStroke
        2 -> SilverStroke
        3 -> BronzeStroke
        else -> Stroke
    }
    CyberCard(
        modifier = Modifier.fillMaxWidth(),
        strokeColor = strokeColor,
        cornerRadius = 12.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (entry.rank) {
                        1 -> "1"
                        2 -> "2"
                        3 -> "3"
                        else -> "#${entry.rank}"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberWhite
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = entry.username,
                    fontSize = 16.sp,
                    color = CyberWhite
                )
            }
            Text(
                text = stringResource(R.string.leaderboard_blocks_format, String.format("%,d", entry.blocks)),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = CyberGray
            )
        }
    }
}
