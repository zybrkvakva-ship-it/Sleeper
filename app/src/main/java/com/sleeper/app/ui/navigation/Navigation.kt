package com.sleeper.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import androidx.navigation.NavController
import com.sleeper.app.R
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sleeper.app.ui.screen.leaderboard.LeaderboardScreen
import com.sleeper.app.ui.screen.mining.MiningScreen
import com.sleeper.app.ui.screen.privacy.PrivacyScreen
import com.sleeper.app.ui.screen.tasks.TasksScreen
import com.sleeper.app.ui.screen.upgrade.UpgradeScreen
import com.sleeper.app.ui.screen.wallet.WalletScreen
import com.sleeper.app.ui.components.TopBar
import com.sleeper.app.ui.components.cyberOverlayModifier
import com.sleeper.app.ui.theme.*

sealed class Screen(val route: String, @StringRes val titleResId: Int, val icon: ImageVector) {
    object Mining : Screen("mining", R.string.nav_mining, Icons.Default.Build)
    object Upgrade : Screen("upgrade", R.string.nav_upgrade, Icons.Default.Star)
    object Tasks : Screen("tasks", R.string.nav_tasks, Icons.Default.CheckCircle)
    object Leaderboard : Screen("leaderboard", R.string.nav_leaderboard, Icons.Default.List)
    object Wallet : Screen("wallet", R.string.nav_wallet, Icons.Default.AccountBox)
}

val bottomNavItems = listOf(
    Screen.Mining,
    Screen.Upgrade,
    Screen.Tasks,
    Screen.Leaderboard,
    Screen.Wallet
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigation() {
    val navController = rememberNavController()

    Scaffold(
        containerColor = BgMain,
        topBar = {
            TopBar(
                statusCenter = "ONLINE",
                statusRight = null
            )
        },
        bottomBar = {
            Column(modifier = Modifier.background(BgMain)) {
                HorizontalDivider(color = Stroke, thickness = 1.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination

                    bottomNavItems.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                        val iconColor = if (selected) CyberGreen else CyberGray
                        val textColor = if (selected) CyberGreen else CyberGray

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = stringResource(screen.titleResId),
                                modifier = Modifier.size(26.dp),
                                tint = iconColor
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(screen.titleResId).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = textColor,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .cyberOverlayModifier(vignetteStrength = 0.06f)
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Mining.route,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(Screen.Mining.route) { MiningScreen() }
                composable(Screen.Upgrade.route) { UpgradeScreen() }
                composable(Screen.Tasks.route) { TasksScreen() }
                composable(Screen.Leaderboard.route) { LeaderboardScreen() }
                composable(Screen.Wallet.route) { WalletScreen(navController = navController) }
                composable("privacy") {
                    PrivacyScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
