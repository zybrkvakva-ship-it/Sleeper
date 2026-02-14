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

/**
 * Клиент к бэкенду майнинга (POST /mining/session, GET /user/balance, GET /leaderboard).
 * Если BuildConfig.API_BASE_URL пустой — вызовы не выполняются (no-op).
 */
class MiningBackendApi {

    companion object {
        private const val TAG = "MiningBackendApi"
    }

    private val baseUrl: String = BuildConfig.API_BASE_URL?.trim()?.removeSuffix("/") ?: ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean = baseUrl.isNotEmpty()

    /**
     * Отправляет сессию на сервер. По контракту: POST /mining/session.
     * @return Pair(success, newBalance?) — при успехе возвращается новый баланс с сервера.
     */
    suspend fun postSession(session: PendingSessionEntity): Result<Long> = withContext(Dispatchers.IO) {
        DevLog.d(TAG, "postSession ENTRY wallet=${DevLog.mask(session.walletAddress)} skr=${session.skrUsername} uptime=${session.uptimeMinutes} storageMB=${session.storageMB}")
        if (baseUrl.isEmpty()) {
            DevLog.w(TAG, "postSession SKIP: API_BASE_URL not set")
            return@withContext Result.failure(IllegalStateException("API_BASE_URL not set"))
        }
        val body = JSONObject().apply {
            put("wallet", session.walletAddress)
            put("skr", session.skrUsername)
            put("uptime_minutes", session.uptimeMinutes)
            put("storage_mb", session.storageMB)
            put("storage_multiplier", session.storageMultiplier)
            put("stake_multiplier", session.stakeMultiplier)
            put("paid_boost_multiplier", session.paidBoostMultiplier)
            put("daily_social_multiplier", session.dailySocialMultiplier)
            put("points_balance", session.pointsBalance)
            put("session_started_at", session.sessionStartedAt)
            put("session_ended_at", session.sessionEndedAt)
            put("device_fingerprint", session.deviceFingerprint)
            put("genesis_nft_multiplier", session.genesisNftMultiplier)
            put("active_skr_boost_id", session.activeSkrBoostId)
        }.toString()
        val url = "$baseUrl/mining/session"
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
            DevLog.i(TAG, "postSession SUCCESS newBalance=$balance")
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
                    blocks = obj.optInt("blocks", 0)
                )
            }
            val resp = LeaderboardApiResponse(
                top = top,
                userRank = json.optInt("user_rank", 0),
                userBlocks = json.optInt("user_blocks", 0),
                totalPoints = json.optLong("total_points", 0L),
                totalBlocks = json.optInt("total_blocks", 0)
            )
            DevLog.i(TAG, "getLeaderboard SUCCESS topSize=${top.size} userRank=${resp.userRank} totalPoints=${resp.totalPoints}")
            resp
        }.onFailure { e ->
            DevLog.e(TAG, "getLeaderboard error: ${e.message} cause=${e.cause?.message}", e)
            DevLog.w(TAG, "getLeaderboard error", e)
        }
    }
}
