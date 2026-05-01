package com.sleeper.core.data.repository

import com.sleeper.app.data.local.AppDatabase
import com.sleeper.app.utils.DevLog
import com.sleeper.app.data.local.PendingSessionEntity
import com.sleeper.app.data.local.SkrBoostCatalog
import com.sleeper.app.data.local.TaskActionType
import com.sleeper.app.data.local.TaskEntity
import com.sleeper.app.data.local.TaskType
import com.sleeper.app.data.local.UserStatsEntity
import com.sleeper.app.data.network.MiningBackendApi
import com.sleeper.app.data.network.UserProfileResponse
import com.sleeper.core.domain.manager.WalletManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

class MiningRepository(private val database: AppDatabase) {

    companion object {
        private const val TAG = "MiningRepository"
    }

    private val _backendApi by lazy { MiningBackendApi() }
    fun getBackendApi(): MiningBackendApi = _backendApi

    val userStats: Flow<UserStatsEntity?> = database.userStatsDao().getUserStatsFlow()
    val tasks: Flow<List<TaskEntity>> = database.taskDao().getAllTasksFlow()
    
    suspend fun initializeDefaultData() {
        val existingStats = database.userStatsDao().getUserStats()
        if (existingStats == null) {
            database.userStatsDao().insert(UserStatsEntity())
        }
        // Seed default tasks if table is empty (offline fallback until server sync)
        if (database.taskDao().getTasks().isEmpty()) {
            database.taskDao().upsertAll(buildDefaultTasks())
        }
    }

    /**
     * Синхронизирует список заданий с сервером.
     * Если API не настроен — молча возвращает false (офлайн режим).
     * @return true при успехе, false при ошибке или офлайн.
     */
    /**
     * Загружает профиль пользователя с сервера и сохраняет referralCode + referralCount
     * в WalletManager (SharedPreferences). Вызывается при запуске / подключении кошелька.
     * @return профиль при успехе, null при ошибке/офлайн.
     */
    suspend fun syncUserProfile(walletAddress: String, walletManager: WalletManager): UserProfileResponse? {
        DevLog.d(TAG, "syncUserProfile ENTRY wallet=${DevLog.mask(walletAddress)}")
        val api = MiningBackendApi()
        if (!api.isConfigured()) {
            // Derive locally: referral code = first 8 chars of wallet (matches server logic)
            val localCode = walletAddress.take(8)
            if (walletManager.getSavedReferralCode() == null) {
                walletManager.saveReferralCode(localCode)
                DevLog.d(TAG, "syncUserProfile: API not configured, derived local code=$localCode")
            }
            return null
        }
        val result = api.getUserProfile(walletAddress)
        val profile = result.getOrNull() ?: run {
            DevLog.w(TAG, "syncUserProfile FAILED: ${result.exceptionOrNull()?.message}")
            // Fallback to derived local code
            if (walletManager.getSavedReferralCode() == null) {
                walletManager.saveReferralCode(walletAddress.take(8))
            }
            return null
        }
        walletManager.saveReferralCode(profile.referralCode)
        walletManager.saveReferralCount(profile.referralCount)
        walletManager.saveBonusNightsRemaining(profile.bonusNightsRemaining)
        DevLog.i(TAG, "syncUserProfile SUCCESS code=${profile.referralCode} count=${profile.referralCount} bonusNights=${profile.bonusNightsRemaining}")
        return profile
    }

    /**
     * Sends walletAddress + referralCode to backend.
     * Returns null on success, error message on failure.
     */
    suspend fun applyReferral(walletAddress: String, referralCode: String): String? {
        val api = MiningBackendApi()
        if (!api.isConfigured()) return "Сервер недоступен — попробуй позже"
        val result = api.applyReferral(walletAddress, referralCode)
        return if (result.isSuccess) null
        else {
            val msg = result.exceptionOrNull()?.message ?: "Ошибка"
            when {
                msg.contains("409") || msg.contains("Already") -> "Реферальный код уже применён"
                msg.contains("404") && msg.contains("code") -> "Код не найден"
                msg.contains("400") && msg.contains("own") -> "Нельзя использовать свой код"
                msg.contains("404") && msg.contains("User") -> "Зарегистрируйся сначала"
                else -> "Ошибка: $msg"
            }
        }
    }

    suspend fun syncTasksFromServer(walletAddress: String, @Suppress("UNUSED_PARAMETER") authToken: String? = null): Boolean {
        DevLog.d(TAG, "syncTasksFromServer ENTRY wallet=${DevLog.mask(walletAddress)}")
        val api = MiningBackendApi()
        if (!api.isConfigured()) {
            DevLog.d(TAG, "syncTasksFromServer SKIP: API not configured")
            return false
        }
        val result = api.getTasksStatus(walletAddress)
        val serverData = result.getOrNull() ?: run {
            DevLog.w(TAG, "syncTasksFromServer FAILED: ${result.exceptionOrNull()?.message}")
            return false
        }
        val now = System.currentTimeMillis()
        val entities = serverData.tasks.map { t ->
            TaskEntity(
                id = t.id,
                title = t.title,
                description = t.description,
                reward = t.rewardPts,
                bonusPercent = t.bonusPercent,
                type = if (t.type == "DAILY") TaskType.DAILY else TaskType.SPECIAL,
                actionType = parseActionType(t.actionType),
                actionUrl = t.actionUrl,
                isCompleted = t.isCompleted,
                completedAt = if (t.isCompleted) now else 0L,
                canComplete = t.canComplete
            )
        }
        database.taskDao().upsertAll(entities)

        // Обновляем локальный бонус из ответа сервера
        database.userStatsDao().getUserStats()?.let { stats ->
            val isNewDay = stats.lastDailyResetAt == 0L || !isSameDay(stats.lastDailyResetAt, now)
            database.userStatsDao().update(
                stats.copy(
                    dailySocialBonusPercent = serverData.totalBonusPercent,
                    lastDailyResetAt = if (isNewDay) now else stats.lastDailyResetAt
                )
            )
        }
        DevLog.i(TAG, "syncTasksFromServer SUCCESS tasks=${entities.size} bonus=${serverData.totalBonusPercent}")
        return true
    }

    /**
     * Выполняет задание: сначала отправляет на сервер, затем обновляет локально.
     * В офлайн-режиме (API не настроен) — работает только локально.
     * @return true при успехе.
     */
    suspend fun completeTask(taskId: String, walletAddress: String? = null, authToken: String? = null): Boolean {
        val stats = database.userStatsDao().getUserStats() ?: return false
        val task = database.taskDao().getTasks().find { it.id == taskId } ?: return false
        if (task.isCompleted) return false

        val api = MiningBackendApi()
        val now = System.currentTimeMillis()

        // ── Server-first (if configured) ──────────────────────────────────────
        if (api.isConfigured() && walletAddress != null && authToken != null) {
            val serverResult = api.completeTaskOnServer(walletAddress, authToken, taskId)
            serverResult.onFailure { e ->
                DevLog.w(TAG, "completeTask server failed taskId=$taskId: ${e.message}")
                return false  // Reject if server says no (409 = already done, 400 = conditions not met)
            }
            val resp = serverResult.getOrNull() ?: return false
            // Server accepted — update local with server-authoritative bonus
            val isNewDay = stats.lastDailyResetAt == 0L || !isSameDay(stats.lastDailyResetAt, now)
            database.userStatsDao().update(
                stats.copy(
                    pointsBalance = stats.pointsBalance + resp.rewardPts,
                    dailySocialBonusPercent = resp.newDailyBonusPercent + resp.newSpecialBonusPercent,
                    lastDailyResetAt = if (isNewDay) now else stats.lastDailyResetAt
                )
            )
        } else {
            // ── Offline fallback: local-only ──────────────────────────────────
            val bonusIncrement = task.bonusPercent.takeIf { it > 0.0 } ?: when (task.type) {
                TaskType.DAILY -> 0.05
                TaskType.SPECIAL -> 0.02
            }
            val isNewDay = stats.lastDailyResetAt == 0L || !isSameDay(stats.lastDailyResetAt, now)
            val baseBonus = if (isNewDay) 0.0 else stats.dailySocialBonusPercent
            val newBonus = (baseBonus + bonusIncrement).coerceAtMost(0.30)
            database.userStatsDao().update(
                stats.copy(
                    pointsBalance = stats.pointsBalance + task.reward,
                    dailySocialBonusPercent = newBonus,
                    lastDailyResetAt = if (isNewDay) now else stats.lastDailyResetAt
                )
            )
        }

        database.taskDao().markCompleted(taskId, now)
        DevLog.i(TAG, "completeTask SUCCESS taskId=$taskId")
        return true
    }

    private fun parseActionType(raw: String) = when (raw.uppercase()) {
        "SHARE"    -> TaskActionType.SHARE
        "STORY"    -> TaskActionType.STORY
        "OPEN_URL" -> TaskActionType.OPEN_URL
        "AUTO"     -> TaskActionType.AUTO
        "REFERRAL" -> TaskActionType.REFERRAL
        else       -> TaskActionType.CHECKIN
    }

    private fun buildDefaultTasks() = listOf(
        TaskEntity(id = "daily_checkin",       title = "Daily Check-in",           description = "Open the app every day",                        reward = 50,    bonusPercent = 0.03, type = TaskType.DAILY,   actionType = TaskActionType.CHECKIN),
        TaskEntity(id = "share",               title = "Share Seeker Mining",       description = "Share the app with friends",                    reward = 200,   bonusPercent = 0.05, type = TaskType.DAILY,   actionType = TaskActionType.SHARE,    actionUrl = "https://t.me/seekermining"),
        TaskEntity(id = "watch_story",         title = "Watch a Story",             description = "Check the latest story in Telegram",            reward = 100,   bonusPercent = 0.03, type = TaskType.DAILY,   actionType = TaskActionType.STORY,    actionUrl = "https://t.me/seekermining"),
        TaskEntity(id = "invite_friend",       title = "Invite a Friend",           description = "Invite someone using your referral link",       reward = 300,   bonusPercent = 0.05, type = TaskType.DAILY,   actionType = TaskActionType.REFERRAL),
        TaskEntity(id = "subscribe_telegram",  title = "Join Telegram Channel",     description = "Subscribe to @SeekerMining on Telegram",        reward = 2000,  bonusPercent = 0.02, type = TaskType.SPECIAL, actionType = TaskActionType.OPEN_URL, actionUrl = "https://t.me/seekermining"),
        TaskEntity(id = "subscribe_twitter",   title = "Follow on X",               description = "Follow @SeekerMining on X (Twitter)",           reward = 1000,  bonusPercent = 0.02, type = TaskType.SPECIAL, actionType = TaskActionType.OPEN_URL, actionUrl = "https://x.com/seekermining"),
        TaskEntity(id = "sleep_streak_3",      title = "3-Night Streak",            description = "Mine 3 nights in a row",                        reward = 5000,  bonusPercent = 0.05, type = TaskType.SPECIAL, actionType = TaskActionType.AUTO,     canComplete = false),
        TaskEntity(id = "sleep_streak_7",      title = "7-Night Streak",            description = "Mine 7 nights in a row",                        reward = 15000, bonusPercent = 0.10, type = TaskType.SPECIAL, actionType = TaskActionType.AUTO,     canComplete = false),
    )
    
    suspend fun recordHumanCheckResponse(passed: Boolean) {
        if (passed) {
            database.userStatsDao().recordHumanCheckPassed(System.currentTimeMillis())
        } else {
            database.userStatsDao().recordHumanCheckFailed()
        }
    }

    /** Синхронизирует стейк SKR в БД (после RPC getStakedBalance). */
    suspend fun syncStake(stakedSkrRaw: Long, stakedSkrHuman: Double) {
        DevLog.i(TAG, "[STAKING_REPO] syncStake ENTRY stakedSkrRaw=$stakedSkrRaw stakedSkrHuman=$stakedSkrHuman")
        DevLog.d(TAG, "syncStake ENTRY stakedSkrRaw=$stakedSkrRaw stakedSkrHuman=$stakedSkrHuman")
        val stats = database.userStatsDao().getUserStats()
        if (stats == null) {
            DevLog.w(TAG, "[STAKING_REPO] syncStake no UserStats in DB skip")
            DevLog.w(TAG, "syncStake no UserStats in DB skip")
            return
        }
        DevLog.i(TAG, "[STAKING_REPO] before update: stakedSkrRaw=${stats.stakedSkrRaw} stakedSkrHuman=${stats.stakedSkrHuman}")
        database.userStatsDao().update(
            stats.copy(stakedSkrRaw = stakedSkrRaw, stakedSkrHuman = stakedSkrHuman)
        )
        DevLog.i(TAG, "[STAKING_REPO] syncStake EXIT DB updated to stakedSkrRaw=$stakedSkrRaw stakedSkrHuman=$stakedSkrHuman")
        DevLog.d(TAG, "syncStake EXIT updated DB stakedSkrRaw=$stakedSkrRaw stakedSkrHuman=$stakedSkrHuman")
    }

    suspend fun getUserStats() = database.userStatsDao().getUserStats()

    // ---------- Очередь сессий и бэкенд (Фаза 4.3) ----------

    /** Добавляет сессию в очередь для отправки на бэкенд при появлении сети. */
    suspend fun enqueuePendingSession(session: PendingSessionEntity) {
        DevLog.d(TAG, "enqueuePendingSession wallet=${DevLog.mask(session.walletAddress)} skr=${session.skrUsername} uptime=${session.uptimeMinutes} durationMs=${session.sessionEndedAt - session.sessionStartedAt}")
        database.pendingSessionDao().insert(session)
    }

    /**
     * Отправляет накопленные сессии на бэкенд. При успехе удаляет их из очереди.
     * При успешном ответе POST /mining/session обновляет локальный баланс значением с сервера.
     * @return количество успешно отправленных сессий
     */
    suspend fun syncPendingSessions(): Int {
        DevLog.d(TAG, "syncPendingSessions ENTRY")
        val api = MiningBackendApi()
        if (!api.isConfigured()) {
            DevLog.d(TAG, "syncPendingSessions SKIP: API not configured")
            return 0
        }
        val list = database.pendingSessionDao().getAllPending()
        DevLog.d(TAG, "syncPendingSessions pending count=${list.size}")
        var synced = 0
        for (session in list) {
            var success = false
            for (attempt in 1..3) {
                DevLog.d(TAG, "syncPendingSessions session id=${session.id} attempt=$attempt/3")
                api.postSession(session).onSuccess { newBalance ->
                    database.pendingSessionDao().deleteById(session.id)
                    database.userStatsDao().getUserStats()?.let { stats ->
                        database.userStatsDao().update(stats.copy(pointsBalance = newBalance))
                    }
                    synced++
                    success = true
                    DevLog.i(TAG, "syncPendingSessions session id=${session.id} SUCCESS newBalance=$newBalance")
                }.onFailure {
                    DevLog.w(TAG, "syncPendingSessions session id=${session.id} attempt=$attempt failed: ${it.message}")
                }
                if (success) break
                if (attempt < 3) delay(1000L * attempt)
            }
            if (!success) DevLog.e(TAG, "syncPendingSessions session id=${session.id} all 3 attempts failed")
        }
        DevLog.d(TAG, "syncPendingSessions EXIT synced=$synced")
        return synced
    }

    /**
     * Запрашивает баланс с бэкенда и перезаписывает локальный pointsBalance.
     * @return true если баланс успешно обновлён, false при ошибке или не настроенном API
     */
    suspend fun syncBalanceFromServer(walletAddress: String): Boolean {
        DevLog.d(TAG, "syncBalanceFromServer ENTRY wallet=${DevLog.mask(walletAddress)}")
        val api = MiningBackendApi()
        if (!api.isConfigured()) {
            DevLog.d(TAG, "syncBalanceFromServer SKIP: API not configured")
            return false
        }
        val result = api.getBalance(walletAddress)
        return result.getOrNull()?.let { balance ->
            val stats = database.userStatsDao().getUserStats() ?: return@let false.also { DevLog.w(TAG, "syncBalanceFromServer no UserStats") }
            database.userStatsDao().update(stats.copy(pointsBalance = balance))
            DevLog.i(TAG, "syncBalanceFromServer SUCCESS balance=$balance (was ${stats.pointsBalance})")
            true
        } ?: run {
            DevLog.w(TAG, "syncBalanceFromServer FAILED: ${result.exceptionOrNull()?.message}")
            false
        }
    }

    /**
     * Покупка буста за SKR (MVP: только запись в БД; ончейн 1/7/49 переводов — отдельный слой).
     * Проверка баланса — на стороне UI. Длительность из durationHours или durationDays (boost.durationMs()).
     */
    suspend fun purchaseSkrBoost(boostId: String): Boolean {
        DevLog.d(TAG, "purchaseSkrBoost ENTRY boostId=$boostId")
        val boost = SkrBoostCatalog.get(boostId) ?: run {
            DevLog.w(TAG, "purchaseSkrBoost boost not found: $boostId")
            return false
        }
        val stats = database.userStatsDao().getUserStats() ?: run {
            DevLog.w(TAG, "purchaseSkrBoost no UserStats")
            return false
        }
        val now = System.currentTimeMillis()
        val endsAt = now + boost.durationMs()
        database.userStatsDao().update(
            stats.copy(
                activeSkrBoostId = boostId,
                activeSkrBoostEndsAt = endsAt
            )
        )
        DevLog.i(TAG, "purchaseSkrBoost SUCCESS boostId=$boostId endsAt=$endsAt durationMs=${boost.durationMs()}")
        return true
    }

    /**
     * Активация Genesis NFT после оплаты минта.
     * Устанавливает hasGenesisNft = true, genesisNftMultiplier = 3.0.
     * Опционально сохраняет on-chain mint адрес и порядковый номер NFT.
     */
    suspend fun activateGenesisNft(
        nftMint: String? = null,
        nftNumber: Int? = null
    ): Boolean {
        DevLog.d(TAG, "activateGenesisNft ENTRY nftMint=${nftMint?.take(16)} nftNumber=$nftNumber")
        val stats = database.userStatsDao().getUserStats() ?: run {
            DevLog.w(TAG, "activateGenesisNft no UserStats")
            return false
        }
        database.userStatsDao().update(
            stats.copy(
                hasGenesisNft = true,
                genesisNftMultiplier = 3.0,
                genesisNftMint = nftMint,
                genesisNftNumber = nftNumber
            )
        )
        DevLog.i(TAG, "activateGenesisNft SUCCESS hasGenesisNft=true multiplier=3.0 mint=${nftMint?.take(16)} #$nftNumber")
        return true
    }
}

// Утилита для сравнения «дня» по миллисекундам с эпохи (UTC),
// без завязки на локальные календари и таймзоны.
private fun isSameDay(timestamp1: Long, timestamp2: Long): Boolean {
    val day1 = timestamp1 / 86_400_000L
    val day2 = timestamp2 / 86_400_000L
    return day1 == day2
}
