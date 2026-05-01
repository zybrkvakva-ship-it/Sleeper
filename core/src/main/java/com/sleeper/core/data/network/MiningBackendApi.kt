package com.sleeper.app.data.network

import com.sleeper.app.BuildConfig
import com.sleeper.app.utils.DevLog
import com.sleeper.app.data.local.PendingSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Ответ GET /leaderboard. */
data class LeaderboardApiResponse(
    val top: List<LeaderboardApiEntry>,
    val userRank: Int,
    val userBlocks: Int,
    val totalPoints: Long,
    val totalBlocks: Int
)

data class LeaderboardApiEntry(
    val rank: Int,
    val username: String,
    val blocks: Int
)

data class AuthChallengeApiResponse(
    val nonce: String,
    val message: String,
    val expiresAt: String
)

data class GenesisNftMintResponse(
    val nftMint: String,
    val nftNumber: Int,
    val txHash: String
)

data class TaskStatusResponse(
    val taskDate: String,
    val dailyBonusPercent: Double,
    val specialBonusPercent: Double,
    val totalBonusPercent: Double,
    val referralCount: Int,
    val nightStreak: Int,
    val tasks: List<RemoteTaskItem>
)

data class RemoteTaskItem(
    val id: String,
    val type: String,           // "DAILY" | "SPECIAL"
    val title: String,
    val description: String,
    val rewardPts: Int,
    val bonusPercent: Double,
    val actionType: String,     // "CHECKIN" | "SHARE" | "STORY" | "OPEN_URL" | "AUTO" | "REFERRAL"
    val actionUrl: String?,
    val isCompleted: Boolean,
    val canComplete: Boolean
)

data class UserProfileResponse(
    val walletAddress: String,
    val referralCode: String,
    val referralCount: Int,
    val skrUsername: String?,
    val totalNp: Double,
    val totalNightsMined: Int,
    val bonusNightsRemaining: Int = 0
)

data class ClaimResult(
    val amount: Long,
    val txHash: String,
    val message: String
)

data class SeasonTierResponse(
    val tier: String,           // "Genesis" | "Pioneer" | "Growing" | "Active" | "Viral" | "Mass" | "Global"
    val seasonDays: Int,        // current season duration in days (exponential)
    val poolPerNight: Long,     // SPR distributed per night
    val activeDevices: Int,     // miners online today
    val daysRemaining: Int,
    val nightsRemaining: Int,
    val seasonNumber: Int
)

data class TaskCompleteResponse(
    val taskId: String,
    val rewardPts: Int,
    val bonusPercent: Double,
    val newDailyBonusPercent: Double,
    val newSpecialBonusPercent: Double
)

/**
 * Клиент к бэкенду майнинга (POST /night/end, GET /user/balance, GET /leaderboard).
 * Если BuildConfig.API_BASE_URL пустой — вызовы не выполняются (no-op).
 */
class MiningBackendApi {

    companion object {
        private const val TAG = "MiningBackendApi"
    }

    private val baseUrl: String = BuildConfig.API_BASE_URL.trim().removeSuffix("/")

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean = baseUrl.isNotEmpty()

    /**
     * Отправляет сессию на сервер. По контракту: POST /night/end.
     * @return новый баланс с сервера при успехе.
     */
    suspend fun postSession(session: PendingSessionEntity): Result<Long> = withContext(Dispatchers.IO) {
        DevLog.d(TAG, "postSession ENTRY wallet=${DevLog.mask(session.walletAddress)} skr=${session.skrUsername} uptime=${session.uptimeMinutes}")
        if (baseUrl.isEmpty()) {
            DevLog.w(TAG, "postSession SKIP: API_BASE_URL not set")
            return@withContext Result.failure(IllegalStateException("API_BASE_URL not set"))
        }
        val body = JSONObject().apply {
            put("wallet", session.walletAddress)
            put("auth_token", session.authToken)
            put("skr", session.skrUsername)
            put("uptime_minutes", session.uptimeMinutes)
            put("duration_seconds", session.durationSeconds)
            put("staked_skr_human", session.stakedSkrHuman)
            put("stake_multiplier", session.stakeMultiplier)
            put("human_checks_passed", session.humanChecksPassed)
            put("human_checks_failed", session.humanChecksFailed)
            put("human_multiplier", session.humanMultiplier)
            put("daily_social_bonus_percent", session.dailySocialBonusPercent)
            put("paid_boost_multiplier", session.paidBoostMultiplier)
            put("daily_social_multiplier", session.dailySocialMultiplier)
            put("points_per_second", session.pointsPerSecond)
            put("points_balance", session.pointsBalance)
            put("session_started_at", session.sessionStartedAt)
            put("session_ended_at", session.sessionEndedAt)
            put("device_fingerprint", session.deviceFingerprint)
            put("has_genesis_nft", session.hasGenesisNft)
            put("genesis_nft_multiplier", session.genesisNftMultiplier)
            put("active_skr_boost_id", session.activeSkrBoostId)
        }.toString()
        val url = "$baseUrl/night/end"
        DevLog.d(TAG, "postSession POST url=$url bodyLen=${body.length}")
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        runCatching {
            val response = client.newCall(request).execute()
            val code = response.code
            val bodyStr = response.body?.string() ?: ""
            DevLog.d(TAG, "postSession response code=$code bodyLen=${bodyStr.length} preview=${bodyStr.take(200)}")
            if (!response.isSuccessful) {
                val msg = "HTTP $code: ${bodyStr.take(500)}"
                DevLog.e(TAG, "postSession failed: $msg")
                throw Exception(msg)
            }
            val json = bodyStr.let { JSONObject(it) }
            val balance = json.optLong("balance", session.pointsBalance)
            val duplicate = json.optBoolean("duplicate", false)
            val pointsEarned = json.optLong("points_earned", -1L)
            val pps = json.optDouble("points_per_second", session.pointsPerSecond)
            val ppsServer = json.optDouble("points_per_second_server", pps)
            val multipliers = json.optJSONObject("multipliers")
            DevLog.i(
                TAG,
                "postSession SUCCESS newBalance=$balance duplicate=$duplicate pointsEarned=$pointsEarned pps=$pps ppsServer=$ppsServer multipliers=${multipliers?.toString()}"
            )
            balance
        }.onFailure { e ->
            DevLog.e(TAG, "postSession error: ${e.message} cause=${e.cause?.message}", e)
            DevLog.w(TAG, "postSession error", e)
        }
    }

    /**
     * Запрашивает баланс пользователя. GET /user/balance?wallet=...
     */
    suspend fun getBalance(walletAddress: String): Result<Long> = withContext(Dispatchers.IO) {
        DevLog.d(TAG, "getBalance ENTRY wallet=${DevLog.mask(walletAddress)}")
        if (baseUrl.isEmpty()) {
            DevLog.w(TAG, "getBalance SKIP: API_BASE_URL not set")
            return@withContext Result.failure(IllegalStateException("API_BASE_URL not set"))
        }
        val url = "$baseUrl/user/balance?wallet=${java.net.URLEncoder.encode(walletAddress, "UTF-8")}"
        DevLog.d(TAG, "getBalance GET url=${url.take(80)}...")
        val request = Request.Builder().url(url).get().build()
        runCatching {
            val response = client.newCall(request).execute()
            val code = response.code
            val bodyStr = response.body?.string() ?: ""
            DevLog.d(TAG, "getBalance response code=$code bodyLen=${bodyStr.length}")
            if (!response.isSuccessful) {
                val msg = "HTTP $code: ${bodyStr.take(300)}"
                DevLog.e(TAG, "getBalance failed: $msg")
                throw Exception(msg)
            }
            val json = bodyStr.let { JSONObject(it) }
            val balance = json.optLong("balance", 0L)
            DevLog.i(TAG, "getBalance SUCCESS balance=$balance")
            balance
        }.onFailure { e ->
            DevLog.e(TAG, "getBalance error: ${e.message} cause=${e.cause?.message}", e)
            DevLog.w(TAG, "getBalance error", e)
        }
    }

    /**
     * Запрашивает лидерборд. GET /leaderboard?wallet=...
     */
    suspend fun getLeaderboard(walletAddress: String? = null): Result<LeaderboardApiResponse> = withContext(Dispatchers.IO) {
        DevLog.d(TAG, "getLeaderboard ENTRY wallet=${walletAddress?.let { DevLog.mask(it) } ?: "null"}")
        if (baseUrl.isEmpty()) {
            DevLog.w(TAG, "getLeaderboard SKIP: API_BASE_URL not set")
            return@withContext Result.failure(IllegalStateException("API_BASE_URL not set"))
        }
        val query = if (!walletAddress.isNullOrBlank()) "?wallet=${java.net.URLEncoder.encode(walletAddress, "UTF-8")}" else ""
        val url = "$baseUrl/leaderboard$query"
        DevLog.d(TAG, "getLeaderboard GET url=${url.take(80)}...")
        val request = Request.Builder().url(url).get().build()
        runCatching {
            val response = client.newCall(request).execute()
            val code = response.code
            val bodyStr = response.body?.string() ?: ""
            DevLog.d(TAG, "getLeaderboard response code=$code bodyLen=${bodyStr.length}")
            if (!response.isSuccessful) {
                DevLog.e(TAG, "getLeaderboard failed: HTTP $code ${bodyStr.take(200)}")
                throw Exception("HTTP $code: ${bodyStr.take(500)}")
            }
            val json = bodyStr.let { JSONObject(it) }
            val topArray = json.optJSONArray("top") ?: org.json.JSONArray()
            val top = List(topArray.length()) { i ->
                val obj = topArray.getJSONObject(i)
                LeaderboardApiEntry(
                    rank = obj.optInt("rank", i + 1),
                    username = obj.optString("username", ""),
                    blocks = obj.optInt("total_np", 0)   // backend: total_np = Night Points
                )
            }
            val resp = LeaderboardApiResponse(
                top = top,
                userRank = json.optInt("user_rank", 0),
                userBlocks = json.optInt("user_np", 0),       // backend: user_np
                totalPoints = json.optLong("total_np", 0L),   // backend: total_np (global sum)
                totalBlocks = json.optInt("total_users", 0)   // backend: total_users
            )
            DevLog.i(TAG, "getLeaderboard SUCCESS topSize=${top.size} userRank=${resp.userRank} totalPoints=${resp.totalPoints}")
            resp
        }.onFailure { e ->
            DevLog.e(TAG, "getLeaderboard error: ${e.message} cause=${e.cause?.message}", e)
            DevLog.w(TAG, "getLeaderboard error", e)
        }
    }

    /**
     * Request one-time auth challenge for wallet signature.
     */
    suspend fun requestAuthChallenge(walletAddress: String): Result<AuthChallengeApiResponse> = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("API_BASE_URL not set"))
        }
        val body = JSONObject().apply {
            put("walletAddress", walletAddress)
        }.toString()
        val request = Request.Builder()
            .url("$baseUrl/user/auth/challenge")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        runCatching {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${responseBody.take(400)}")
            val json = JSONObject(responseBody)
            AuthChallengeApiResponse(
                nonce = json.optString("nonce"),
                message = json.optString("message"),
                expiresAt = json.optString("expiresAt")
            )
        }
    }

    /**
     * Минтит Genesis NFT после подтверждённого on-chain платежа SKR.
     * POST /nft/mint
     */
    suspend fun mintGenesisNft(
        walletAddress: String,
        authToken: String,
        paymentTxHash: String
    ): Result<GenesisNftMintResponse> = withContext(Dispatchers.IO) {
        DevLog.d(TAG, "mintGenesisNft ENTRY wallet=${DevLog.mask(walletAddress)} tx=${paymentTxHash.take(16)}...")
        if (baseUrl.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("API_BASE_URL not set"))
        }
        val body = JSONObject().apply {
            put("walletAddress", walletAddress)
            put("authToken", authToken)
            put("txHash", paymentTxHash)
        }.toString()
        val request = Request.Builder()
            .url("$baseUrl/nft/mint")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        runCatching {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            DevLog.d(TAG, "mintGenesisNft response code=${response.code} bodyLen=${responseBody.length}")
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${responseBody.take(400)}")
            val json = JSONObject(responseBody)
            if (json.has("error")) {
                val msg = json.optJSONObject("error")?.optString("message")
                    ?: json.optString("message", "NFT mint failed")
                throw Exception(msg)
            }
            val result = GenesisNftMintResponse(
                nftMint = json.optString("nftMint"),
                nftNumber = json.optInt("nftNumber"),
                txHash = json.optString("txHash")
            )
            DevLog.i(TAG, "mintGenesisNft SUCCESS nftMint=${result.nftMint.take(16)}... #${result.nftNumber}")
            result
        }.onFailure { e ->
            DevLog.e(TAG, "mintGenesisNft error: ${e.message}", e)
        }
    }

    /**
     * Загружает профиль пользователя (referralCode, referralCount, stats).
     * GET /user/:walletAddress
     */
    suspend fun getUserProfile(walletAddress: String): Result<UserProfileResponse> = withContext(Dispatchers.IO) {
        DevLog.d(TAG, "getUserProfile ENTRY wallet=${DevLog.mask(walletAddress)}")
        if (baseUrl.isEmpty()) return@withContext Result.failure(IllegalStateException("API_BASE_URL not set"))
        val url = "$baseUrl/user/${java.net.URLEncoder.encode(walletAddress, "UTF-8")}"
        val request = Request.Builder().url(url).get().build()
        runCatching {
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${bodyStr.take(400)}")
            val json = JSONObject(bodyStr)
            val user = json.optJSONObject("user") ?: throw Exception("Missing 'user' field in response")
            UserProfileResponse(
                walletAddress = user.optString("wallet_address", walletAddress),
                referralCode  = user.optString("referral_code", walletAddress.take(8)),
                referralCount = user.optInt("referral_count", 0),
                skrUsername   = user.optString("skr_username").takeIf { it.isNotEmpty() },
                totalNp       = user.optDouble("total_np", 0.0),
                totalNightsMined = user.optInt("total_nights_mined", 0),
                bonusNightsRemaining = user.optInt("bonusNightsRemaining", 0)
            ).also { DevLog.i(TAG, "getUserProfile SUCCESS referralCode=${it.referralCode} referrals=${it.referralCount}") }
        }.onFailure { DevLog.e(TAG, "getUserProfile error: ${it.message}", it) }
    }

    /**
     * Получает список заданий с сервера + статус выполнения для данного кошелька.
     * GET /tasks?wallet=...
     */
    suspend fun getTasksStatus(walletAddress: String): Result<TaskStatusResponse> = withContext(Dispatchers.IO) {
        DevLog.d(TAG, "getTasksStatus ENTRY wallet=${DevLog.mask(walletAddress)}")
        if (baseUrl.isEmpty()) return@withContext Result.failure(IllegalStateException("API_BASE_URL not set"))
        val url = "$baseUrl/tasks?wallet=${java.net.URLEncoder.encode(walletAddress, "UTF-8")}"
        val request = Request.Builder().url(url).get().build()
        runCatching {
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${bodyStr.take(400)}")
            val json = JSONObject(bodyStr)
            val tasksArray = json.optJSONArray("tasks") ?: org.json.JSONArray()
            val tasks = List(tasksArray.length()) { i ->
                val t = tasksArray.getJSONObject(i)
                RemoteTaskItem(
                    id = t.optString("id"),
                    type = t.optString("type", "DAILY"),
                    title = t.optString("title"),
                    description = t.optString("description"),
                    rewardPts = t.optInt("rewardPts", 0),
                    bonusPercent = t.optDouble("bonusPercent", 0.0),
                    actionType = t.optString("actionType", "CHECKIN"),
                    actionUrl = t.optString("actionUrl").takeIf { it.isNotEmpty() },
                    isCompleted = t.optBoolean("isCompleted", false),
                    canComplete = t.optBoolean("canComplete", false)
                )
            }
            TaskStatusResponse(
                taskDate = json.optString("taskDate"),
                dailyBonusPercent = json.optDouble("dailyBonusPercent", 0.0),
                specialBonusPercent = json.optDouble("specialBonusPercent", 0.0),
                totalBonusPercent = json.optDouble("totalBonusPercent", 0.0),
                referralCount = json.optInt("referralCount", 0),
                nightStreak = json.optInt("nightStreak", 0),
                tasks = tasks
            ).also { DevLog.i(TAG, "getTasksStatus SUCCESS tasks=${tasks.size} bonus=${it.totalBonusPercent}") }
        }.onFailure { DevLog.e(TAG, "getTasksStatus error: ${it.message}", it) }
    }

    /**
     * Отправляет выполнение задания на сервер.
     * POST /tasks/complete
     * @return TaskCompleteResponse при успехе.
     */
    suspend fun completeTaskOnServer(
        walletAddress: String,
        authToken: String,
        taskId: String
    ): Result<TaskCompleteResponse> = withContext(Dispatchers.IO) {
        DevLog.d(TAG, "completeTaskOnServer ENTRY wallet=${DevLog.mask(walletAddress)} taskId=$taskId")
        if (baseUrl.isEmpty()) return@withContext Result.failure(IllegalStateException("API_BASE_URL not set"))
        val body = JSONObject().apply {
            put("walletAddress", walletAddress)
            put("authToken", authToken)
            put("taskId", taskId)
        }.toString()
        val request = Request.Builder()
            .url("$baseUrl/tasks/complete")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        runCatching {
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${bodyStr.take(400)}")
            val json = JSONObject(bodyStr)
            TaskCompleteResponse(
                taskId = json.optString("taskId", taskId),
                rewardPts = json.optInt("rewardPts", 0),
                bonusPercent = json.optDouble("bonusPercent", 0.0),
                newDailyBonusPercent = json.optDouble("newDailyBonusPercent", 0.0),
                newSpecialBonusPercent = json.optDouble("newSpecialBonusPercent", 0.0)
            ).also { DevLog.i(TAG, "completeTaskOnServer SUCCESS taskId=$taskId rewardPts=${it.rewardPts}") }
        }.onFailure { DevLog.e(TAG, "completeTaskOnServer error: ${it.message}", it) }
    }

    /**
     * Claims SLEEP tokens post-TGE. POST /claim
     * @return ClaimResult with amount + txHash on success.
     */
    suspend fun postClaim(walletAddress: String, authToken: String): Result<ClaimResult> = withContext(Dispatchers.IO) {
        DevLog.d(TAG, "postClaim ENTRY wallet=${DevLog.mask(walletAddress)}")
        if (baseUrl.isEmpty()) return@withContext Result.failure(IllegalStateException("API_BASE_URL not set"))
        val body = JSONObject().apply {
            put("walletAddress", walletAddress)
            put("authToken", authToken)
        }.toString()
        val request = Request.Builder()
            .url("$baseUrl/claim")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        runCatching {
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            DevLog.d(TAG, "postClaim response code=${response.code} bodyLen=${bodyStr.length}")
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${bodyStr.take(400)}")
            val json = JSONObject(bodyStr)
            ClaimResult(
                amount  = json.optLong("amount", 0L),
                txHash  = json.optString("txHash", ""),
                message = json.optString("message", "Claimed successfully")
            ).also { DevLog.i(TAG, "postClaim SUCCESS amount=${it.amount} txHash=${it.txHash.take(16)}...") }
        }.onFailure { DevLog.e(TAG, "postClaim error: ${it.message}", it) }
    }

    /**
     * Получает текущий тир ускорения сезона. GET /season/current
     */
    suspend fun getSeasonTier(): Result<SeasonTierResponse> = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty()) return@withContext Result.failure(IllegalStateException("API_BASE_URL not set"))
        val request = Request.Builder().url("$baseUrl/season/current").get().build()
        runCatching {
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${bodyStr.take(400)}")
            val json = JSONObject(bodyStr)
            val season = json.optJSONObject("season") ?: throw Exception("Missing 'season' in response")
            SeasonTierResponse(
                tier            = season.optString("tier", "Genesis"),
                seasonDays      = season.optInt("seasonDays", 112),
                poolPerNight    = season.optLong("poolPerNight", 0L),
                activeDevices   = season.optInt("active_devices", 0),
                daysRemaining   = season.optInt("daysRemaining", 0),
                nightsRemaining = season.optInt("nightsRemaining", 0),
                seasonNumber    = season.optInt("season_number", 1)
            ).also { DevLog.i(TAG, "getSeasonTier SUCCESS tier=${it.tier} active=${it.activeDevices} days=${it.seasonDays}") }
        }.onFailure { DevLog.e(TAG, "getSeasonTier error: ${it.message}", it) }
    }

    /** Apply a referral code for the given wallet. Returns true on success. */
    suspend fun applyReferral(walletAddress: String, referralCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty()) return@withContext Result.failure(IllegalStateException("API_BASE_URL not set"))
        val body = JSONObject().apply {
            put("walletAddress", walletAddress)
            put("referralCode", referralCode)
        }.toString()
        val request = Request.Builder()
            .url("$baseUrl/user/apply-referral")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        runCatching {
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${bodyStr.take(400)}")
        }.also { r -> r.onFailure { DevLog.e(TAG, "applyReferral error: ${it.message}", it) } }
    }

    /**
     * Server-side .skr domain verification. POST /user/verify-skr
     * Returns skrUsername on success, throws on failure.
     */
    suspend fun verifySkrOnServer(
        walletAddress: String,
        authToken: String
    ): Result<String> = withContext(Dispatchers.IO) {
        DevLog.d(TAG, "verifySkrOnServer ENTRY wallet=${DevLog.mask(walletAddress)}")
        if (baseUrl.isEmpty()) return@withContext Result.failure(IllegalStateException("API_BASE_URL not set"))
        val body = JSONObject().apply {
            put("walletAddress", walletAddress)
            put("authToken", authToken)
        }.toString()
        val request = Request.Builder()
            .url("$baseUrl/user/verify-skr")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        runCatching {
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: ""
            DevLog.d(TAG, "verifySkrOnServer response code=${response.code} bodyLen=${bodyStr.length}")
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${bodyStr.take(400)}")
            val json = JSONObject(bodyStr)
            val username = json.optString("skrUsername").takeIf { it.isNotEmpty() }
                ?: throw Exception("Missing skrUsername in response")
            DevLog.i(TAG, "verifySkrOnServer SUCCESS username=$username")
            username
        }.onFailure { DevLog.e(TAG, "verifySkrOnServer error: ${it.message}", it) }
    }

    /**
     * Verify wallet signature and receive backend auth token.
     */
    suspend fun verifyAuthChallenge(
        walletAddress: String,
        nonce: String,
        signatureHex: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (baseUrl.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("API_BASE_URL not set"))
        }
        val body = JSONObject().apply {
            put("walletAddress", walletAddress)
            put("nonce", nonce)
            put("signature", signatureHex)
        }.toString()
        val request = Request.Builder()
            .url("$baseUrl/user/auth/verify")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        runCatching {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${responseBody.take(400)}")
            val json = JSONObject(responseBody)
            val token = json.optString("authToken")
            if (token.isNullOrBlank()) throw Exception("authToken is missing in response")
            token
        }
    }
}
