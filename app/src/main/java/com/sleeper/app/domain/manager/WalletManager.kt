package com.sleeper.app.domain.manager

import android.content.Context
import android.net.Uri
import com.solana.mobilewalletadapter.clientlib.*
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import com.sleeper.app.data.network.SplTransferBuilder
import com.sleeper.app.utils.DevLog
import com.sleeper.app.utils.toBase58
import kotlinx.coroutines.delay

class WalletManager(private val context: Context) {

    companion object {
        private const val TAG = "WalletManager"
        private const val APP_NAME = "Sleeper"
        private const val APP_URI = "https://sleeper.app"
        private const val PREFS_NAME = "wallet_prefs"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_WALLET_ADDRESS = "wallet_address"
        private const val KEY_SKR_USERNAME = "skr_username"
        /** Сообщение при временной недоступности MWA-сервера кошелька (ECONNREFUSED) */
        private const val MWA_CONNECTION_REFUSED_HINT = "Кошелёк ещё не готов. Откройте приложение кошелька и нажмите «Подключить» снова."
        /** Сообщение при отказе/таймауте авторизации в кошельке (authorization request failed) */
        private const val MWA_AUTH_FAILED_HINT = "Кошелёк не подтвердил запрос. Откройте приложение кошелька, подтвердите действие и повторите."
        /** Повторы при ECONNREFUSED: кошелёк поднимает MWA WebSocket с задержкой */
        private const val MWA_RETRY_MAX_ATTEMPTS = 3
        private const val MWA_RETRY_INITIAL_DELAY_MS = 400L
    }
    
    private val walletAdapter: MobileWalletAdapter by lazy {
        MobileWalletAdapter(
            connectionIdentity = ConnectionIdentity(
                identityUri = Uri.parse(APP_URI),
                iconUri = Uri.parse("icon.png"),
                identityName = APP_NAME
            )
        ).apply {
            // Восстанавливаем сохранённый authToken
            authToken = getSavedAuthToken()
        }
    }
    
    /**
     * Подключиться к Solana wallet.
     * При ECONNREFUSED (кошелёк ещё не поднял MWA WebSocket) повторяет попытки с backoff.
     */
    suspend fun connectWallet(sender: ActivityResultSender): WalletConnectionResult {
        DevLog.d(TAG, "[CONNECT] ========== connectWallet() ENTRY ==========")
        var lastError: WalletConnectionResult.Error? = null
        for (attempt in 1..MWA_RETRY_MAX_ATTEMPTS) {
            if (attempt > 1) {
                val delayMs = MWA_RETRY_INITIAL_DELAY_MS * (1L shl (attempt - 2))
                DevLog.d(TAG, "[CONNECT] Retry $attempt/$MWA_RETRY_MAX_ATTEMPTS after ECONNREFUSED, delay ${delayMs}ms")
                delay(delayMs)
            }
            try {
                DevLog.d(TAG, "[CONNECT] Calling walletAdapter.connect(sender)... attempt=$attempt")
                val result = walletAdapter.connect(sender)
                DevLog.d(TAG, "[CONNECT] connect() returned: resultType=${result::class.simpleName}")
                when (result) {
                    is TransactionResult.Success -> {
                        val authResult = result.authResult
                        DevLog.d(TAG, "[CONNECT] Success: authResult.accounts.size=${authResult.accounts.size}")
                        val publicKeyBytes = authResult.accounts.firstOrNull()?.publicKey
                        if (publicKeyBytes != null) {
                            DevLog.d(TAG, "[CONNECT] publicKeyBytes.length=${publicKeyBytes.size} hex(16)=${publicKeyBytes.take(16).joinToString("") { "%02x".format(it) }}...")
                            val address = publicKeyBytes.toBase58()
                            DevLog.d(TAG, "[CONNECT] address(base58)=$address length=${address.length}")
                            saveAuthToken(walletAdapter.authToken)
                            val authLen = walletAdapter.authToken?.length ?: 0
                            DevLog.d(TAG, "[CONNECT] authToken saved length=$authLen")
                            saveWalletAddress(address)
                            DevLog.d(TAG, "[CONNECT] Wallet address saved to prefs. FULL_ADDRESS=$address")
                            DevLog.d(TAG, "[CONNECT] ========== connectWallet() EXIT Success ==========")
                            return WalletConnectionResult.Success(address)
                        } else {
                            DevLog.w(TAG, "[CONNECT] publicKeyBytes=null -> No account found")
                            return WalletConnectionResult.Error("No account found")
                        }
                    }
                    is TransactionResult.NoWalletFound -> {
                        DevLog.w(TAG, "[CONNECT] NoWalletFound (no MWA wallet on device)")
                        return WalletConnectionResult.NoWalletFound
                    }
                    is TransactionResult.Failure -> {
                        DevLog.e(TAG, "[CONNECT] Failure: message=${result.e.message} cause=${result.e.cause?.message}")
                        lastError = WalletConnectionResult.Error(userFriendlyConnectError(result.e.message, result.e))
                        if (isConnectionRefused(result.e) && attempt < MWA_RETRY_MAX_ATTEMPTS) continue
                        DevLog.e(TAG, "[CONNECT] Failure stackTrace:", result.e)
                        return lastError
                    }
                }
            } catch (e: Exception) {
                DevLog.e(TAG, "[CONNECT] Exception: message=${e.message}", e)
                lastError = WalletConnectionResult.Error(userFriendlyConnectError(e.message, e))
                if (isConnectionRefused(e) && attempt < MWA_RETRY_MAX_ATTEMPTS) continue
                return lastError
            }
        }
        DevLog.e(TAG, "[CONNECT] All $MWA_RETRY_MAX_ATTEMPTS attempts failed (ECONNREFUSED)")
        return lastError ?: WalletConnectionResult.Error(MWA_CONNECTION_REFUSED_HINT)
    }
    
    /**
     * Sign In with Solana (SIWS).
     * При ECONNREFUSED повторяет попытки с backoff.
     */
    suspend fun signInWithSolana(sender: ActivityResultSender): SignInResult {
        var lastError: SignInResult.Error? = null
        for (attempt in 1..MWA_RETRY_MAX_ATTEMPTS) {
            if (attempt > 1) {
                delay(MWA_RETRY_INITIAL_DELAY_MS * (1L shl (attempt - 2)))
            }
            try {
                // TODO: Uncomment when using SIWS in production
                // val payload = SignInWithSolana.Payload(...)
                // val result = walletAdapter.signIn(sender, payload)
                val result = walletAdapter.connect(sender)
                when (result) {
                    is TransactionResult.Success -> {
                        val publicKeyBytes = result.authResult.accounts.firstOrNull()?.publicKey
                        if (publicKeyBytes != null) {
                            val address = publicKeyBytes.toBase58()
                            saveAuthToken(walletAdapter.authToken)
                            saveWalletAddress(address)
                            DevLog.d(TAG, "Sign-in successful: $address")
                            return SignInResult.Success(address)
                        } else {
                            return SignInResult.Error("No account found")
                        }
                    }
                    is TransactionResult.NoWalletFound -> return SignInResult.NoWalletFound
                    is TransactionResult.Failure -> {
                        lastError = SignInResult.Error(result.e.message ?: "Unknown error")
                        if (isConnectionRefused(result.e) && attempt < MWA_RETRY_MAX_ATTEMPTS) continue
                        return lastError
                    }
                }
            } catch (e: Exception) {
                lastError = SignInResult.Error(e.message ?: "Sign-in failed")
                if (isConnectionRefused(e) && attempt < MWA_RETRY_MAX_ATTEMPTS) continue
                DevLog.e(TAG, "Exception during SIWS", e)
                return lastError
            }
        }
        return lastError ?: SignInResult.Error(MWA_CONNECTION_REFUSED_HINT)
    }
    
    /**
     * Подписать сообщение (для claim и др.).
     * При ECONNREFUSED повторяет попытки с backoff (кошелёк поднимает MWA WebSocket с задержкой).
     */
    suspend fun signMessage(
        sender: ActivityResultSender,
        message: String
    ): SignMessageResult {
        DevLog.d(TAG, "signMessage ENTRY messageLen=${message.length}")
        var lastError: SignMessageResult.Error? = null
        for (attempt in 1..MWA_RETRY_MAX_ATTEMPTS) {
            if (attempt > 1) {
                val delayMs = MWA_RETRY_INITIAL_DELAY_MS * (1L shl (attempt - 2))
                DevLog.d(TAG, "signMessage retry $attempt/$MWA_RETRY_MAX_ATTEMPTS after ECONNREFUSED, delay ${delayMs}ms")
                delay(delayMs)
            }
            try {
                val result = walletAdapter.transact(sender) { authResult ->
                    val address = authResult.accounts.firstOrNull()?.publicKey
                    if (address != null) {
                        signMessagesDetached(
                            arrayOf(message.toByteArray()),
                            arrayOf(address)
                        )
                    } else {
                        null
                    }
                }
                when (result) {
                    is TransactionResult.Success -> {
                        val signedMessage = result.successPayload?.messages?.firstOrNull()
                        val signature = signedMessage?.signatures?.firstOrNull()
                        if (signature != null) {
                            val sigHex = signature.toHexString()
                            DevLog.d(TAG, "Message signed: $sigHex")
                            DevLog.d(TAG, "signMessage SUCCESS sigLen=${sigHex.length}")
                            return SignMessageResult.Success(sigHex)
                        } else {
                            DevLog.w(TAG, "signMessage Success but no signature in payload")
                            return SignMessageResult.Error("No signature returned")
                        }
                    }
                    is TransactionResult.NoWalletFound -> {
                        DevLog.w(TAG, "signMessage NoWalletFound")
                        return SignMessageResult.NoWalletFound
                    }
                    is TransactionResult.Failure -> {
                        val friendly = userFriendlyConnectError(result.e.message, result.e)
                        DevLog.e(TAG, "signMessage Failure: raw=${result.e.message} -> friendly=$friendly")
                        lastError = SignMessageResult.Error(friendly)
                        if (isConnectionRefused(result.e) && attempt < MWA_RETRY_MAX_ATTEMPTS) continue
                        return lastError
                    }
                }
            } catch (e: Exception) {
                DevLog.e(TAG, "Exception during sign message", e)
                val friendly = userFriendlyConnectError(e.message, e)
                lastError = SignMessageResult.Error(friendly)
                DevLog.e(TAG, "signMessage Exception: ${e::class.simpleName} raw=${e.message} -> friendly=$friendly")
                if (isConnectionRefused(e) && attempt < MWA_RETRY_MAX_ATTEMPTS) continue
                return lastError
            }
        }
        DevLog.e(TAG, "signMessage all $MWA_RETRY_MAX_ATTEMPTS attempts failed")
        return lastError ?: SignMessageResult.Error(MWA_CONNECTION_REFUSED_HINT)
    }
    
    /**
     * Подписать и отправить одну или несколько SPL Token transfer (user → destination).
     * Используется для бустов (1/7/49 переводов) и минта Genesis NFT (1 перевод).
     * @param destinationBase58 адрес получателя (BOOST_TREASURY или Genesis mint address)
     * @param amountsPerTransfer список сумм в raw (6 decimals) — по одной инструкции на элемент
     * @param blockhashBase58 последний blockhash (получить через SolanaRpcClient.getLatestBlockhash())
     * @return Success(signatureBase58) или Error/NoWalletFound
     */
    /**
     * Подписать и отправить одну или несколько SPL Token transfer (user → destination).
     * Используется для бустов (1/7/49 переводов) и минта Genesis NFT (1 перевод).
     * При ECONNREFUSED повторяет попытки с backoff.
     * @param destinationBase58 адрес получателя (BOOST_TREASURY или Genesis mint address)
     * @param amountsPerTransfer список сумм в raw (6 decimals) — по одной инструкции на элемент
     * @param blockhashBase58 последний blockhash (получить через SolanaRpcClient.getLatestBlockhash())
     * @return Success(signatureBase58) или Error/NoWalletFound
     */
    suspend fun signAndSendSplTransfers(
        sender: ActivityResultSender,
        destinationBase58: String,
        amountsPerTransfer: List<Long>,
        blockhashBase58: String
    ): SplTransferResult {
        var lastError: SplTransferResult.Error? = null
        for (attempt in 1..MWA_RETRY_MAX_ATTEMPTS) {
            if (attempt > 1) {
                delay(MWA_RETRY_INITIAL_DELAY_MS * (1L shl (attempt - 2)))
            }
            try {
                val result = walletAdapter.transact(sender) { authResult ->
                    val userPubkey = authResult.accounts.firstOrNull()?.publicKey
                    if (userPubkey == null || userPubkey.size != 32) return@transact null
                    val txBytes = SplTransferBuilder.buildUnsignedSerialized(
                        userPubkey,
                        destinationBase58,
                        amountsPerTransfer,
                        blockhashBase58
                    ) ?: return@transact null
                    signAndSendTransactions(arrayOf(txBytes))
                }
                when (result) {
                    is TransactionResult.Success -> {
                        val sig = result.successPayload?.signatures?.firstOrNull()
                        if (sig != null) {
                            DevLog.d(TAG, "SPL transfer sent: ${sig.toHexString().take(16)}...")
                            return SplTransferResult.Success(sig.toBase58())
                        } else {
                            return SplTransferResult.Error("No signature returned")
                        }
                    }
                    is TransactionResult.NoWalletFound -> return SplTransferResult.NoWalletFound
                    is TransactionResult.Failure -> {
                        lastError = SplTransferResult.Error(userFriendlyConnectError(result.e.message, result.e))
                        if (isConnectionRefused(result.e) && attempt < MWA_RETRY_MAX_ATTEMPTS) continue
                        return lastError
                    }
                }
            } catch (e: Exception) {
                DevLog.e(TAG, "signAndSendSplTransfers failed", e)
                lastError = SplTransferResult.Error(userFriendlyConnectError(e.message, e))
                if (isConnectionRefused(e) && attempt < MWA_RETRY_MAX_ATTEMPTS) continue
                return lastError
            }
        }
        return lastError ?: SplTransferResult.Error(MWA_CONNECTION_REFUSED_HINT)
    }

    /**
     * Отключиться от wallet
     */
    suspend fun disconnectWallet(sender: ActivityResultSender): Boolean {
        return try {
            val result = walletAdapter.disconnect(sender)
            
            if (result is TransactionResult.Success) {
                clearSavedData()
                DevLog.d(TAG, "Disconnected from wallet")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            DevLog.e(TAG, "Exception during disconnect", e)
            false
        }
    }
    
    /**
     * Получить сохранённый адрес wallet
     */
    fun getSavedWalletAddress(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val address = prefs.getString(KEY_WALLET_ADDRESS, null)
        DevLog.d(TAG, "[GET_ADDRESS] getSavedWalletAddress: ${if (address != null) "ok len=${address.length} value=${address.take(12)}...${address.takeLast(8)}" else "null"}")
        return address
    }
    
    /**
     * Проверить, подключён ли wallet
     */
    fun isWalletConnected(): Boolean {
        val addr = getSavedWalletAddress()
        val token = getSavedAuthToken()
        val connected = addr != null && token != null
        DevLog.d(TAG, "[IS_CONNECTED] isWalletConnected: addr=${addr != null} authToken=${token != null} -> $connected")
        return connected
    }
    
    /**
     * Сохранить верифицированный .skr username (для майнинга и лидерборда)
     */
    fun saveSkrUsername(username: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (username != null) {
            prefs.edit().putString(KEY_SKR_USERNAME, username).apply()
        } else {
            prefs.edit().remove(KEY_SKR_USERNAME).apply()
        }
    }
    
    /**
     * Получить сохранённый .skr username (реальный с блокчейна)
     */
    fun getSavedSkrUsername(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SKR_USERNAME, null)
    }
    
    // Private helpers

    /**
     * Преобразует технические ошибки MWA (ECONNREFUSED, WebSocket, authorization request failed) в понятное пользователю сообщение.
     * MWA подключается к локальному серверу кошелька (127.0.0.1); если кошелёк ещё не поднял сервер — будет ECONNREFUSED.
     * Библиотека часто оборачивает ECONNREFUSED в "authorization request failed" — проверяем cause.
     */
    private fun userFriendlyConnectError(raw: String?, throwable: Throwable? = null): String {
        if (throwable != null && isConnectionRefused(throwable))
            return MWA_CONNECTION_REFUSED_HINT
        if (raw.isNullOrBlank()) return "Ошибка подключения"
        val lower = raw.lowercase()
        if (lower.contains("econnrefused") || lower.contains("connection refused") ||
            lower.contains("failed to connect") || lower.contains("websocket"))
            return MWA_CONNECTION_REFUSED_HINT
        if (lower.contains("authorization request failed"))
            return MWA_AUTH_FAILED_HINT
        if (lower.contains("jsonrpc20remoteexception") && lower.contains("authorization"))
            return MWA_AUTH_FAILED_HINT
        return raw
    }

    private fun isConnectionRefused(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            val msg = e.message?.lowercase() ?: ""
            if (e is java.net.ConnectException ||
                msg.contains("econnrefused") || msg.contains("connection refused") ||
                msg.contains("failed to connect") && (msg.contains("127.0.0.1") || msg.contains("localhost")))
                return true
            e = e.cause
        }
        return false
    }
    
    private fun saveAuthToken(token: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_AUTH_TOKEN, token).apply()
    }
    
    private fun getSavedAuthToken(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AUTH_TOKEN, null)
    }
    
    private fun saveWalletAddress(address: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_WALLET_ADDRESS, address).apply()
    }
    
    private fun clearSavedData() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_AUTH_TOKEN).remove(KEY_WALLET_ADDRESS).remove(KEY_SKR_USERNAME).apply()
    }
    
    // Extension: ByteArray to Hex String
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}

// Result types

sealed class WalletConnectionResult {
    data class Success(val address: String) : WalletConnectionResult()
    object NoWalletFound : WalletConnectionResult()
    data class Error(val message: String) : WalletConnectionResult()
}

sealed class SignInResult {
    data class Success(val address: String) : SignInResult()
    object NoWalletFound : SignInResult()
    data class Error(val message: String) : SignInResult()
}

sealed class SignMessageResult {
    data class Success(val signature: String) : SignMessageResult()
    object NoWalletFound : SignMessageResult()
    data class Error(val message: String) : SignMessageResult()
}

sealed class SplTransferResult {
    data class Success(val signatureBase58: String) : SplTransferResult()
    object NoWalletFound : SplTransferResult()
    data class Error(val message: String) : SplTransferResult()
}
