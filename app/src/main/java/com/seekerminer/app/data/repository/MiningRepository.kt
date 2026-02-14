package com.seekerminer.app.data.repository

import com.seekerminer.app.data.local.AppDatabase
import com.seekerminer.app.utils.DevLog
import com.seekerminer.app.data.local.PendingSessionEntity
import com.seekerminer.app.data.local.SkrBoostCatalog
import com.seekerminer.app.data.local.TaskEntity
import com.seekerminer.app.data.local.TaskType
import com.seekerminer.app.data.local.UpgradeEntity
import com.seekerminer.app.data.local.UpgradeType
import com.seekerminer.app.data.local.UserStatsEntity
import com.seekerminer.app.data.network.MiningBackendApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

class MiningRepository(private val database: AppDatabase) {

    companion object {
        private const val TAG = "MiningRepository"
    }

    val userStats: Flow<UserStatsEntity?> = database.userStatsDao().getUserStatsFlow()
    val upgrades: Flow<List<UpgradeEntity>> = database.upgradeDao().getAllUpgradesFlow()
    val tasks: Flow<List<TaskEntity>> = database.taskDao().getAllTasksFlow()
    
    suspend fun initializeDefaultData() {
        // Проверяем, есть ли уже данные
        val existingStats = database.userStatsDao().getUserStats()
        if (existingStats == null) {
            // Создаём начальную статистику
            database.userStatsDao().insert(UserStatsEntity())
            
            // Создаём апгрейды
            val defaultUpgrades = listOf(
                UpgradeEntity(
                    id = "turbo_x4",
                    name = "Турбо x4",
                    description = "Ускоряет фарм в 4 раза",
                    cost = 500,
                    multiplier = 4.0,
                    type = UpgradeType.SPEED
                ),
                UpgradeEntity(
                    id = "super_x10",
                    name = "Супер x10",
                    description = "Ускоряет фарм в 10 раз",
                    cost = 2000,
                    multiplier = 10.0,
                    type = UpgradeType.SPEED
                ),
                UpgradeEntity(
                    id = "storage_x3",
                    name = "Storage x3",
                    description = "Увеличивает storage до 300MB",
                    cost = 1000,
                    multiplier = 3.0,
                    type = UpgradeType.STORAGE
                ),
                UpgradeEntity(
                    id = "auto_check",
                    name = "Auto-check",
                    description = "Автоматические human checks",
                    cost = 1000,
                    multiplier = 1.0,
                    type = UpgradeType.AUTO
                )
            )
            database.upgradeDao().insertAll(defaultUpgrades)
            
            // Создаём задания
            val defaultTasks = listOf(
                TaskEntity(
                    id = "invite_friend",
                    title = "Пригласи друга",
                    reward = 300,
                    type = TaskType.DAILY
                ),
                TaskEntity(
                    id = "share",
                    title = "Поделись в соцсетях",
                    reward = 500,
                    type = TaskType.DAILY
                ),
                TaskEntity(
                    id = "watch_story",
                    title = "Смотри сторис",
                    reward = 100,
                    type = TaskType.DAILY
                ),
                TaskEntity(
                    id = "subscribe",
                    title = "Подпишись на @SeekerMiner",
                    reward = 5000,
                    type = TaskType.SPECIAL
                )
            )
            database.taskDao().insertAll(defaultTasks)
        }
    }
    
    suspend fun purchaseUpgrade(upgradeId: String): Boolean {
        DevLog.d(TAG, "purchaseUpgrade ENTRY upgradeId=$upgradeId")
        val stats = database.userStatsDao().getUserStats() ?: run {
            DevLog.w(TAG, "purchaseUpgrade no UserStats")
            return false
        }
        val upgrade = database.upgradeDao().getUpgrade(upgradeId) ?: run {
            DevLog.w(TAG, "purchaseUpgrade upgrade not found: $upgradeId")
            return false
        }
        if (upgrade.isPurchased || stats.pointsBalance < upgrade.cost) {
            DevLog.w(TAG, "purchaseUpgrade SKIP: isPurchased=${upgrade.isPurchased} balance=${stats.pointsBalance} cost=${upgrade.cost}")
            return false
        }
        
        // Списываем поинты
        database.userStatsDao().update(
            stats.copy(pointsBalance = stats.pointsBalance - upgrade.cost)
        )
        
        // Применяем апгрейд
        when (upgrade.type) {
            UpgradeType.STORAGE -> {
                database.userStatsDao().update(
                    stats.copy(
                        storageMB = (stats.storageMB * upgrade.multiplier).toInt(),
                        storageMultiplier = upgrade.multiplier
                    )
                )
            }
            else -> {
                // Для SPEED и AUTO просто помечаем купленным
            }
        }
        
        database.upgradeDao().markPurchased(upgradeId)
        DevLog.i(TAG, "purchaseUpgrade SUCCESS upgradeId=$upgradeId newBalance=${stats.pointsBalance - upgrade.cost}")
        return true
    }
    
    suspend fun completeTask(taskId: String): Boolean {
        val stats = database.userStatsDao().getUserStats() ?: return false
        val task = database.taskDao().getTasks().find { it.id == taskId } ?: return false
        
        if (task.isCompleted) return false
        val now = System.currentTimeMillis()

        // --- dailySocialMultiplier: обновление бонуса от дейликов/социалок ---
        // Считаем «день» как целое количество суток с эпохи, чтобы избежать сложного календаря
        val isNewDay = stats.lastDailyResetAt == 0L || !isSameDay(stats.lastDailyResetAt, now)

        // Если новый день — сбрасываем бонус и точку отсчёта
        val baseBonus = if (isNewDay) 0.0 else stats.dailySocialBonusPercent
        val lastReset = if (isNewDay) now else stats.lastDailyResetAt

        // Приращение бонуса по типу задания (примерные значения, кап на уровне ~+15%)
        val bonusIncrement = when (task.type) {
            TaskType.DAILY -> 0.05  // дейлики: +5%
            TaskType.SPECIAL -> 0.02 // special: +2%
        }

        // Максимальный суммарный бонус: 0.15 (мультипликатор до 1.15)
        val DAILY_SOCIAL_MAX_BONUS = 0.15
        val newBonus = (baseBonus + bonusIncrement).coerceAtMost(DAILY_SOCIAL_MAX_BONUS)

        // Начисляем награду и сохраняем обновлённый бонус
        database.userStatsDao().update(
            stats.copy(
                pointsBalance = stats.pointsBalance + task.reward,
                dailySocialBonusPercent = newBonus,
                lastDailyResetAt = lastReset
            )
        )
        
        // Помечаем выполненным
        database.taskDao().markCompleted(taskId, System.currentTimeMillis())
        
        return true
    }
    
    suspend fun recordHumanCheckResponse(passed: Boolean) {
        if (passed) {
            database.userStatsDao().recordHumanCheckPassed(System.currentTimeMillis())
        } else {
            database.userStatsDao().recordHumanCheckFailed()
        }
    }

    /** Синхронизирует storage в БД с реальным объёмом (после allocate или при старте). */
    suspend fun syncStorage(storageMB: Int, storageMultiplier: Double) {
        val stats = database.userStatsDao().getUserStats() ?: return
        database.userStatsDao().update(
            stats.copy(storageMB = storageMB, storageMultiplier = storageMultiplier)
        )
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

    suspend fun getUpgrade(id: String) = database.upgradeDao().getUpgrade(id)
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
     * Активация Genesis NFT после оплаты минта (MVP: локальная запись; ончейн — отдельно).
     * Устанавливает hasGenesisNft = true и genesisNftMultiplier = 1.1 (+10% навсегда).
     */
    suspend fun activateGenesisNft(): Boolean {
        DevLog.d(TAG, "activateGenesisNft ENTRY")
        val stats = database.userStatsDao().getUserStats() ?: run {
            DevLog.w(TAG, "activateGenesisNft no UserStats")
            return false
        }
        database.userStatsDao().update(
            stats.copy(
                hasGenesisNft = true,
                genesisNftMultiplier = 1.1
            )
        )
        DevLog.i(TAG, "activateGenesisNft SUCCESS hasGenesisNft=true multiplier=1.1")
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
