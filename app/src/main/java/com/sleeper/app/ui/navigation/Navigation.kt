package com.sleeper.app.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
import com.sleeper.app.ui.components.cyberGlow
import com.sleeper.app.ui.theme.*
import com.sleeper.app.ui.theme.AppDuration

sealed class Screen(val route: String, @StringRes val titleResId: Int, val icon: ImageVector) {
    object Mining      : Screen("mining",      R.string.nav_mining,      Icons.Default.Bedtime)
    object Upgrade     : Screen("upgrade",     R.string.nav_upgrade,     Icons.AutoMirrored.Filled.TrendingUp)
    object Tasks       : Screen("tasks",       R.string.nav_tasks,       Icons.Default.CheckCircle)
    object Leaderboard : Screen("leaderboard", R.string.nav_leaderboard, Icons.Default.EmojiEvents)
    object Wallet      : Screen("wallet",      R.string.nav_wallet,      Icons.Default.AccountBalanceWallet)
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
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopBar(statusCenter = "ONLINE", statusRight = null)
        },
        bottomBar = {
            BottomNavBar(navController = navController)
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
                modifier = Modifier.fillMaxSize(),
                enterTransition = {
                    fadeIn(tween(AppDuration.Normal)) + slideInVertically(tween(AppDuration.Normal)) { it / 24 }
                },
                exitTransition = {
                    fadeOut(tween(AppDuration.Fast))
                },
                popEnterTransition = {
                    fadeIn(tween(AppDuration.Normal))
                },
                popExitTransition = {
                    fadeOut(tween(AppDuration.Fast))
                }
            ) {
                composable(Screen.Mining.route)      { MiningScreen() }
                composable(Screen.Upgrade.route)     { UpgradeScreen() }
                composable(Screen.Tasks.route)       { TasksScreen() }
                composable(Screen.Leaderboard.route) { LeaderboardScreen() }
                composable(Screen.Wallet.route)      { WalletScreen(navController = navController) }
                composable("privacy") {
                    PrivacyScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

@Composable
private fun BottomNavBar(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(BottomBarBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            bottomNavItems.forEach { screen ->
                val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                NavItem(
                    screen = screen,
                    selected = selected,
                    onClick = {
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NavItem(
    screen: Screen,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "navIconScale"
    )
    val iconColor by animateColorAsState(
        targetValue = if (selected) CyberGreen else CyberGray,
        animationSpec = tween(AppDuration.ColorTransition),
        label = "navIconColor"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) CyberGreen else CyberGray,
        animationSpec = tween(AppDuration.ColorTransition),
        label = "navTextColor"
    )

    Column(
        modifier = modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) CyberGreen else CyberGreen.copy(alpha = 0f))
                .then(
                    if (selected)
                        Modifier.cyberGlow(CyberGreen, radius = 6.dp, alpha = 0.5f, cornerRadius = 2.dp)
                    else Modifier
                )
        )
        Spacer(Modifier.height(6.dp))
        Icon(
            imageVector = screen.icon,
            contentDescription = stringResource(screen.titleResId),
            modifier = Modifier
                .size(24.dp)
                .scale(iconScale),
            tint = iconColor
        )
        Spacer(Modifier.height(3.dp))
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
