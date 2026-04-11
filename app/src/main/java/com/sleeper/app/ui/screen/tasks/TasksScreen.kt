package com.sleeper.app.ui.screen.tasks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sleeper.app.R
import com.sleeper.app.data.local.TaskActionType
import com.sleeper.app.data.local.TaskEntity
import com.sleeper.app.data.local.TaskType
import com.sleeper.app.ui.components.CyberCard
import com.sleeper.app.ui.theme.*
import com.sleeper.app.ui.theme.AppDuration
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun TasksScreen(
    viewModel: TasksViewModel = viewModel()
) {
    val tasks by viewModel.tasks.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Launcher used after returning from share/external link action
    var pendingTaskId   by remember { mutableStateOf<String?>(null) }
    var launchTimeMs    by remember { mutableStateOf(0L) }

    // Minimum time the user must spend in the external app/browser to count as "done".
    // SHARE: 3 s, STORY/OPEN_URL: 5 s — prevents instant back-tap abuse.
    val minTimeMs = remember { mutableStateOf(3_000L) }

    val urlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        pendingTaskId?.let { id ->
            val elapsed = System.currentTimeMillis() - launchTimeMs
            if (elapsed >= minTimeMs.value) {
                viewModel.completeTask(id)
            } else {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        "Please complete the action before returning",
                        duration = SnackbarDuration.Short
                    )
                }
            }
            pendingTaskId = null
        }
    }

    // Show error/success snackbars
    LaunchedEffect(uiState.error) {
        uiState.error?.let { msg ->
            scope.launch { snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short) }
            viewModel.clearError()
        }
    }
    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let { msg ->
            scope.launch { snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short) }
            viewModel.clearSuccess()
        }
    }

    // Referral onboarding dialog
    if (uiState.showReferralOnboarding) {
        ReferralOnboardingDialog(
            preFillCode = uiState.referralOnboardingPreFill,
            error = uiState.referralOnboardingError,
            isLoading = uiState.isLoading,
            onSubmit = { viewModel.submitReferralCode(it) },
            onDismiss = { viewModel.dismissReferralOnboarding() }
        )
    }

    val dailyTasks   = tasks.filter { it.type == TaskType.DAILY }
    val specialTasks = tasks.filter { it.type == TaskType.SPECIAL }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = androidx.compose.ui.graphics.Color.Transparent
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ── Header: current boost ────────────────────────────────────
                item {
                    BonusBar(bonusPercent = uiState.totalBonusPercent, isSyncing = uiState.isSyncing)
                    Spacer(Modifier.height(8.dp))
                }

                // ── Referral card ────────────────────────────────────────────
                item {
                    ReferralCard(
                        referralCode = uiState.referralCode,
                        referralCount = uiState.referralCount,
                        onShare = { code ->
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Join Seeker Mining — earn SLEEP tokens while you sleep!\n" +
                                    "Use my referral code: $code\n" +
                                    "seekermining://invite?code=$code\n" +
                                    "https://t.me/seekermining"
                                )
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Invite a Friend"))
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // ── Welcome bonus (referred users only) ──────────────────────
                if (uiState.bonusNightsRemaining > 0) {
                    item {
                        WelcomeBonusCard(uiState.bonusNightsRemaining)
                        Spacer(Modifier.height(8.dp))
                    }
                }

                // ── Daily tasks ──────────────────────────────────────────────
                item {
                    Text(
                        text = stringResource(R.string.tasks_daily_energy).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyberWhite
                    )
                    Spacer(Modifier.height(8.dp))
                }
                itemsIndexed(dailyTasks) { index, task ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(AppDuration.Normal, delayMillis = index * AppDuration.Stagger)) +
                                slideInVertically(tween(AppDuration.Normal, delayMillis = index * AppDuration.Stagger)) { it / 3 }
                    ) {
                        TaskCard(
                            task = task,
                            isProcessing = uiState.isLoading,
                            onAction = { resolveTaskAction(context, task, urlLauncher, pendingTaskId, viewModel, uiState.referralCode, { pendingTaskId = it }, { launchTimeMs = it }, { minTimeMs.value = it }) }
                        )
                    }
                }

                // ── Special tasks ────────────────────────────────────────────
                item {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.tasks_special_energy).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyberYellow
                    )
                    Spacer(Modifier.height(8.dp))
                }
                itemsIndexed(specialTasks) { index, task ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(AppDuration.Normal, delayMillis = (dailyTasks.size + index) * AppDuration.Stagger)) +
                                slideInVertically(tween(AppDuration.Normal, delayMillis = (dailyTasks.size + index) * AppDuration.Stagger)) { it / 3 }
                    ) {
                        TaskCard(
                            task = task,
                            isProcessing = uiState.isLoading,
                            onAction = { resolveTaskAction(context, task, urlLauncher, pendingTaskId, viewModel, uiState.referralCode, { pendingTaskId = it }, { launchTimeMs = it }, { minTimeMs.value = it }) }
                        )
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }

            // Full-screen loading overlay while syncing
            if (uiState.isLoading) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = CyberGreen, modifier = Modifier.size(48.dp))
                }
            }
        }
    }
}

// ─── ReferralCard ─────────────────────────────────────────────────────────────
@Composable
private fun ReferralCard(
    referralCode: String?,
    referralCount: Int,
    onShare: (String) -> Unit
) {
    val context = LocalContext.current
    val code = referralCode ?: "Loading..."
    val isReady = referralCode != null

    CyberCard(strokeColor = StrokeGold, glowColor = GoldGlow) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = "YOUR REFERRAL CODE",
                style = MaterialTheme.typography.labelMedium,
                color = CyberGray,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyberYellow,
                    letterSpacing = androidx.compose.ui.unit.TextUnit(4f, androidx.compose.ui.unit.TextUnitType.Sp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Copy button
                    OutlinedButton(
                        onClick = {
                            if (isReady) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Referral Code", code))
                            }
                        },
                        enabled = isReady,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyberYellow)
                    ) {
                        Text("COPY", style = MaterialTheme.typography.labelSmall, color = CyberYellow, fontWeight = FontWeight.Bold)
                    }
                    // Share button
                    Button(
                        onClick = { if (isReady) onShare(code) },
                        enabled = isReady,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberYellow)
                    ) {
                        Text("INVITE", style = MaterialTheme.typography.labelSmall, color = NightDeep, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            val boostPct = minOf(referralCount * 5, 25)
            val nextTarget = when {
                referralCount >= 5 -> null
                else -> 5 - referralCount
            }
            Text(
                text = if (referralCount == 0)
                    "Invite friends — earn +5% mining boost each (max +25%)"
                else if (nextTarget != null)
                    "$referralCount invited · +$boostPct% boost · $nextTarget more → +${boostPct + nextTarget * 5}%"
                else
                    "$referralCount invited · +$boostPct% max boost unlocked",
                style = MaterialTheme.typography.labelSmall,
                color = if (referralCount >= 5) CyberYellow else if (referralCount > 0) CyberGreen else CyberGray
            )
        }
    }
}

// ─── BonusBar ─────────────────────────────────────────────────────────────────
@Composable
private fun BonusBar(bonusPercent: Double, isSyncing: Boolean) {
    CyberCard(strokeColor = if (bonusPercent > 0) accentGreen else Stroke) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Task Boost", style = MaterialTheme.typography.labelMedium, color = CyberGray)
                Text(
                    text = "+${(bonusPercent * 100).roundToInt()}% to night reward",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (bonusPercent > 0) CyberGreen else CyberWhite
                )
            }
            if (isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = CyberGreen,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

// ─── TaskCard ─────────────────────────────────────────────────────────────────
@Composable
private fun TaskCard(
    task: TaskEntity,
    isProcessing: Boolean,
    onAction: () -> Unit
) {
    val isActionable = !task.isCompleted && task.canComplete && !isProcessing
    val actionLabel = when {
        task.isCompleted           -> null
        !task.canComplete          -> null
        task.actionType == TaskActionType.AUTO -> null  // streak tasks complete automatically
        task.actionType == TaskActionType.SHARE   -> "SHARE"
        task.actionType == TaskActionType.STORY   -> "VIEW"
        task.actionType == TaskActionType.OPEN_URL -> "OPEN"
        task.actionType == TaskActionType.REFERRAL -> "INVITE"
        else                       -> "CLAIM"           // CHECKIN
    }

    CyberCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isActionable && actionLabel != null) { onAction() },
        strokeColor = when {
            task.isCompleted -> CyberGreen
            !task.canComplete -> Stroke.copy(alpha = 0.4f)
            else -> Stroke
        },
        glowColor = if (task.isCompleted) accentGreen else null
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: status icon + title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = when {
                            task.isCompleted  -> "✓"
                            !task.canComplete -> "🔒"
                            else              -> "○"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            task.isCompleted  -> CyberGreen
                            !task.canComplete -> CyberGray
                            else              -> CyberWhite
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = task.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (task.isCompleted || !task.canComplete) CyberGray else CyberWhite
                        )
                        if (task.description.isNotEmpty()) {
                            Text(
                                text = task.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberGray
                            )
                        }
                        if (task.isCompleted) {
                            Text(
                                text = stringResource(R.string.task_completed),
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberGreen
                            )
                        }
                    }
                }

                // Right: reward + bonus + action button
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "+${task.reward}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = CyberYellow
                    )
                    if (task.bonusPercent > 0) {
                        Text(
                            text = "+${(task.bonusPercent * 100).roundToInt()}% boost",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberGreen
                        )
                    }
                }
            }

            // Action button row (only if actionable and has label)
            if (isActionable && actionLabel != null) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onAction,
                    modifier = Modifier.align(Alignment.End).height(32.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyberGreen)
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = NightDeep,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ─── WelcomeBonusCard ─────────────────────────────────────────────────────────
@Composable
private fun WelcomeBonusCard(nightsRemaining: Int) {
    CyberCard(strokeColor = accentGreen, glowColor = GreenGlow) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("🎁", style = MaterialTheme.typography.titleLarge)
            Column {
                Text(
                    text = "Welcome Bonus Active",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyberGreen
                )
                Text(
                    text = "+10% mining boost · $nightsRemaining night${if (nightsRemaining != 1) "s" else ""} remaining",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberGray
                )
            }
        }
    }
}

// ─── ReferralOnboardingDialog ─────────────────────────────────────────────────
@Composable
private fun ReferralOnboardingDialog(
    preFillCode: String,
    error: String?,
    isLoading: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var code by remember { mutableStateOf(preFillCode) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        containerColor = NightDeep,
        title = {
            Text(
                text = "Реферальный код",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = CyberYellow
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Введи код пригласившего, чтобы дать ему +5% командный буст",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberGray
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase() },
                    singleLine = true,
                    enabled = !isLoading,
                    placeholder = { Text("XXXXXXXX", color = CyberGray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberYellow,
                        unfocusedBorderColor = Stroke,
                        focusedTextColor = CyberWhite,
                        unfocusedTextColor = CyberWhite,
                        cursorColor = CyberYellow
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (code.isNotBlank()) onSubmit(code) }),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(text = error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(code) },
                enabled = code.isNotBlank() && !isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = CyberGreen)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NightDeep, strokeWidth = 2.dp)
                } else {
                    Text("ПРИМЕНИТЬ", color = NightDeep, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Пропустить", color = CyberGray, style = MaterialTheme.typography.labelMedium)
            }
        }
    )
}

// ─── Action resolver ─────────────────────────────────────────────────────────
private fun resolveTaskAction(
    @Suppress("UNUSED_PARAMETER") context: android.content.Context,
    task: TaskEntity,
    urlLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
    @Suppress("UNUSED_PARAMETER") pendingTaskId: String?,
    viewModel: TasksViewModel,
    referralCode: String?,
    setPendingTaskId: (String?) -> Unit,
    setLaunchTimeMs: (Long) -> Unit,
    setMinTimeMs: (Long) -> Unit,
) {
    when (task.actionType) {
        TaskActionType.CHECKIN, TaskActionType.AUTO -> {
            // Server validates streak/referral counts — no time gate needed
            viewModel.completeTask(task.id)
        }
        TaskActionType.SHARE -> {
            val shareUrl = task.actionUrl ?: "https://t.me/seekermining"
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareUrl)
            }
            setMinTimeMs(3_000L)
            setLaunchTimeMs(System.currentTimeMillis())
            setPendingTaskId(task.id)
            urlLauncher.launch(Intent.createChooser(shareIntent, "Share Seeker Mining"))
        }
        TaskActionType.STORY,
        TaskActionType.OPEN_URL -> {
            val url = task.actionUrl ?: return
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            // Require at least 5 s in browser/Telegram to watch the story
            setMinTimeMs(5_000L)
            setLaunchTimeMs(System.currentTimeMillis())
            setPendingTaskId(task.id)
            urlLauncher.launch(intent)
        }
        TaskActionType.REFERRAL -> {
            // REFERRAL tasks are AUTO-completed server-side (requiresReferralCount check).
            // The share sheet is shown as a helper, but completion is not tied to returning.
            val code = referralCode ?: "?"
            val shareText = "Join Seeker Mining — earn SLEEP tokens while you sleep!\n" +
                "Use my referral code: $code\n" +
                "seekermining://invite?code=$code\n" +
                "https://t.me/seekermining"
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            // No pendingTaskId — server will auto-unlock this when referral is confirmed
            urlLauncher.launch(Intent.createChooser(shareIntent, "Invite a Friend"))
        }
    }
}
