package com.sleeper.app.data.network

import com.sleeper.app.utils.DevLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class NetworkStats(
    val onlineMiners: Int = 0,
    val pointsPerSecond: Double = 0.0,
    val effectiveMultiplier: Double = 1.0
)

/** Клиент WebSocket для real-time связи с бэкендом майнинга. */
class WebSocketManager(private val wsUrl: String) {

    companion object {
        private const val TAG = "WebSocketManager"
        private const val PING_INTERVAL_MS = 25_000L
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
        private const val RECONNECT_MAX_DELAY_MS = 60_000L
        private const val MAX_RECONNECT_ATTEMPTS = 8
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)  // no timeout for WebSocket
        .build()

    private var webSocket: WebSocket? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var shouldReconnect = true

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _networkStats = MutableStateFlow(NetworkStats())
    val networkStats: StateFlow<NetworkStats> = _networkStats.asStateFlow()

    // Last NP score from server (populated after night:update)
    private val _lastNightScore = MutableStateFlow<NightScore?>(null)
    val lastNightScore: StateFlow<NightScore?> = _lastNightScore.asStateFlow()

    data class NightScore(
        val pointsEarned: Int,
        val pointsPerSecond: Double,
        val effectiveMultiplier: Double,
        val uptimeSeconds: Int
    )

    fun connect() {
        if (wsUrl.isEmpty()) {
            DevLog.w(TAG, "WS URL not configured — skipping connect")
            return
        }
        shouldReconnect = true
        reconnectAttempt = 0
        openConnection()
    }

    fun disconnect() {
        shouldReconnect = false
        pingJob?.cancel()
        reconnectJob?.cancel()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _isConnected.value = false
        DevLog.d(TAG, "disconnect: WebSocket closed")
    }

    /** Отправить night:register при старте сессии майнинга. */
    fun registerMiningSession(walletAddress: String, sessionId: String) {
        sendJson(JSONObject().apply {
            put("type", "night:register")
            put("walletAddress", walletAddress)
            put("nightId", sessionId)
        })
        DevLog.d(TAG, "registerMiningSession wallet=${walletAddress.take(8)}")
    }

    /** Отправить night:update для server-side NP расчёта. */
    fun sendNightUpdate(
        walletAddress: String,
        sessionId: String,
        uptimeSeconds: Int,
        humanChecksPassed: Int,
        humanChecksFailed: Int,
        socialBoostPercent: Double
    ) {
        sendJson(JSONObject().apply {
            put("type", "night:update")
            put("wallet", walletAddress)
            put("sessionId", sessionId)
            put("uptimeSeconds", uptimeSeconds)
            put("humanChecksPassed", humanChecksPassed)
            put("humanChecksFailed", humanChecksFailed)
            put("socialBoostPercent", socialBoostPercent)
        })
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }

    // -------------------------------------------------------------------------

    private fun openConnection() {
        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                DevLog.i(TAG, "WebSocket connected to $wsUrl")
                _isConnected.value = true
                reconnectAttempt = 0
                startPing(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                DevLog.w(TAG, "WebSocket failure: ${t.message}")
                _isConnected.value = false
                pingJob?.cancel()
                scheduleReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                DevLog.d(TAG, "WebSocket closed: code=$code reason=$reason")
                _isConnected.value = false
                pingJob?.cancel()
                if (shouldReconnect && code != 1000) scheduleReconnect()
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (val type = json.optString("type")) {
                "pong" -> DevLog.d(TAG, "pong received")
                "connected" -> DevLog.d(TAG, "Server ack: clientId=${json.optString("clientId")}")
                "network:stats" -> {
                    _networkStats.value = NetworkStats(
                        onlineMiners = json.optInt("activeMiners", 0),
                        pointsPerSecond = json.optDouble("pointsPerSecond", 0.0),
                        effectiveMultiplier = json.optDouble("averageMultiplier", 1.0)
                    )
                }
                "night:score" -> {
                    _lastNightScore.value = NightScore(
                        pointsEarned = json.optInt("pointsEarned", 0),
                        pointsPerSecond = json.optDouble("pointsPerSecond", 0.0),
                        effectiveMultiplier = json.optDouble("effectiveMultiplier", 1.0),
                        uptimeSeconds = json.optInt("uptimeSeconds", 0)
                    )
                }
                else -> DevLog.d(TAG, "Unhandled WS message type=$type")
            }
        } catch (e: Exception) {
            DevLog.w(TAG, "Failed to parse WS message: ${e.message}")
        }
    }

    private fun startPing(ws: WebSocket) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(PING_INTERVAL_MS)
                if (_isConnected.value) {
                    ws.send(JSONObject().apply { put("type", "ping") }.toString())
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect || reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            DevLog.w(TAG, "scheduleReconnect: max attempts reached or stopped")
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = (RECONNECT_BASE_DELAY_MS * (1L shl reconnectAttempt))
                .coerceAtMost(RECONNECT_MAX_DELAY_MS)
            DevLog.d(TAG, "scheduleReconnect attempt=${reconnectAttempt + 1} delay=${delayMs}ms")
            delay(delayMs)
            reconnectAttempt++
            openConnection()
        }
    }

    private fun sendJson(json: JSONObject) {
        val ws = webSocket ?: return
        if (_isConnected.value) {
            ws.send(json.toString())
        }
    }
}

/** Деривирует WS URL из API_BASE_URL: http://host:3000/api/v1 → ws://host:3001 */
fun deriveWsUrl(apiBaseUrl: String): String {
    return try {
        val uri = java.net.URI(apiBaseUrl)
        val scheme = if (uri.scheme == "https") "wss" else "ws"
        "$scheme://${uri.host}:3001"
    } catch (e: Exception) {
        ""
    }
}
