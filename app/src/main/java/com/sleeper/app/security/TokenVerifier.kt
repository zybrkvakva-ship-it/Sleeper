package com.sleeper.app.security

import com.sleeper.app.utils.DevLog
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.sleeper.app.data.network.SolanaCluster
import com.sleeper.app.data.network.SolanaRpcClient
import com.sleeper.app.domain.manager.WalletManager

/**
 * Верификатор .skr доменов (AllDomains ANS на Solana)
 * 
 * .skr домены - это уникальные username на Solana через AllDomains для Seeker.
 * Проверяем, что подключённый wallet владеет .skr доменом.
 */
class TokenVerifier(
    private val walletManager: WalletManager
) {
    
    // Solana RPC клиент для AllDomains queries (используем Helius для лучшей производительности)
    private val rpcClient = SolanaRpcClient(SolanaCluster.MAINNET_HELIUS)
    
    companion object {
        private const val TAG = "TokenVerifier"
        
        // .skr Domain suffix
        private const val SKR_DOMAIN = ".skr"
    }
    
    data class TokenVerificationResult(
        val isValid: Boolean,
        val username: String?,
        val tokenAddress: String?,
        val reason: String,
        /** Код для локализации в UI: NO_WALLET, NO_SKR_TOKEN, null = использовать reason как есть */
        val reasonCode: String? = null
    )
    
    /**
     * Проверяет, владеет ли подключённый wallet .skr токеном
     */
    suspend fun verifySkrToken(): TokenVerificationResult {
        DevLog.d(TAG, "[VERIFY] verifySkrToken ENTRY")
        // 1. Проверяем, подключён ли wallet
        val walletAddress = walletManager.getSavedWalletAddress()
        DevLog.d(TAG, "[VERIFY] getSavedWalletAddress: ${if (walletAddress != null) "ok full=$walletAddress len=${walletAddress.length}" else "null"}")
        if (walletAddress == null) {
            DevLog.w(TAG, "[VERIFY] Wallet not connected -> return invalid")
            val result = TokenVerificationResult(
                isValid = false,
                username = null,
                tokenAddress = null,
                reason = "Wallet not connected",
                reasonCode = "NO_WALLET"
            )
            DevLog.d(TAG, "[VERIFY] verifySkrToken EXIT -> valid=false username=null reason=${result.reason}")
            return result
        }
        
        // 2. Проверяем наличие .skr домена у wallet (через AllDomains ANS)
        DevLog.d(TAG, "[VERIFY] Verifying .skr for wallet: FULL_ADDRESS=$walletAddress length=${walletAddress.length} sample=${walletAddress.take(12)}...${walletAddress.takeLast(8)}")
        DevLog.d(TAG, "[VERIFY] Calling checkSkrTokenOwnership(wallet)...")
        val tokenCheck = checkSkrTokenOwnership(walletAddress)
        DevLog.d(TAG, "[VERIFY] checkSkrTokenOwnership returned: hasToken=${tokenCheck.hasToken} username=${tokenCheck.username} tokenAddress=${tokenCheck.tokenAddress}")
        
        return if (tokenCheck.hasToken) {
            DevLog.i(TAG, "[VERIFY] ✅ .skr token verified: ${tokenCheck.username} pubkey=${tokenCheck.tokenAddress}")
            val result = TokenVerificationResult(
                isValid = true,
                username = tokenCheck.username,
                tokenAddress = tokenCheck.tokenAddress,
                reason = "OK"
            )
            DevLog.d(TAG, "[VERIFY] verifySkrToken EXIT -> valid=true username=${result.username} reason=${result.reason}")
            result
        } else {
            DevLog.w(TAG, "[VERIFY] ❌ No .skr token found for wallet.")
            val result = TokenVerificationResult(
                isValid = false,
                username = null,
                tokenAddress = null,
                reason = "No .skr token",
                reasonCode = "NO_SKR_TOKEN"
            )
            DevLog.d(TAG, "[VERIFY] verifySkrToken EXIT -> valid=false username=null reason=${result.reason}")
            result
        }
    }
    
    /**
     * Проверяет ownership .skr домена через AllDomains ANS на Solana
     * 
     * Использует RPC запрос getProgramAccounts к ANS программе с фильтром по владельцу.
     */
    private suspend fun checkSkrTokenOwnership(walletAddress: String): SkrTokenCheck {
        DevLog.d(TAG, "[CHECK] checkSkrTokenOwnership ENTRY")
        DevLog.d(TAG, "[CHECK] walletAddress=$walletAddress length=${walletAddress.length}")
        return try {
            DevLog.d(TAG, "[CHECK] Calling rpcClient.getAllSkrDomains(wallet)...")
            val skrDomains = rpcClient.getAllSkrDomains(walletAddress)
            DevLog.d(TAG, "[CHECK] getAllSkrDomains returned: count=${skrDomains.size}")
            skrDomains.forEachIndexed { i, d ->
                DevLog.d(TAG, "[CHECK] domain[$i]: name=${d.domainName} pubkey=${d.pubkey} owner=${d.owner.take(12)}...")
            }
            
            if (skrDomains.isNotEmpty()) {
                val primaryDomain = skrDomains.first()
                DevLog.i(TAG, "[CHECK] ✅ Primary .skr: ${primaryDomain.domainName} pubkey=${primaryDomain.pubkey}")
                DevLog.d(TAG, "[CHECK] EXIT -> hasToken=true username=${primaryDomain.domainName}")
                SkrTokenCheck(
                    hasToken = true,
                    username = primaryDomain.domainName,
                    tokenAddress = primaryDomain.pubkey
                )
            } else {
                DevLog.w(TAG, "[CHECK] ❌ No .skr domain found. See SolanaRpcClient/SolanaPda logs for STEP 1..5, reverseLookup, parseAnsDomainName.")
                DevLog.d(TAG, "[CHECK] EXIT -> hasToken=false")
                SkrTokenCheck(hasToken = false, username = null, tokenAddress = null)
            }
        } catch (e: Exception) {
            DevLog.e(TAG, "[CHECK] Exception:", e)
            DevLog.e(TAG, "[CHECK] message=${e.message} cause=${e.cause?.message}")
            DevLog.d(TAG, "[CHECK] EXIT -> hasToken=false (exception)")
            SkrTokenCheck(hasToken = false, username = null, tokenAddress = null)
        }
    }
    
    // Removed: parseSkrUsername - парсинг теперь в SolanaRpcClient!
    
    /**
     * Сохраняет verified .skr token для mining session
     */
    fun saveVerifiedToken(username: String, tokenAddress: String, walletAddress: String) {
        // Сохраняем в БД для аудита
        DevLog.i(TAG, "Verified mining session: $username @ $walletAddress")
        // TODO: Сохранить в Room Database
    }
    
    private data class SkrTokenCheck(
        val hasToken: Boolean,
        val username: String?,
        val tokenAddress: String?
    )
}
