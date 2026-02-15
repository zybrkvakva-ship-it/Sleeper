package com.sleeper.app.data.network

import android.util.Base64
import com.sleeper.app.BuildConfig
import com.sleeper.app.utils.DevLog
import com.sleeper.app.utils.Base58
import com.sleeper.app.utils.decodeBase58
import com.sleeper.app.utils.SolanaPda
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Solana RPC Client для работы с AllDomains ANS (.skr usernames)
 * 
 * AllDomains - децентрализованный сервис доменных имён на Solana.
 * .skr домены хранятся on-chain в аккаунтах ANS программы.
 */
class SolanaRpcClient(
    private val cluster: SolanaCluster = SolanaCluster.MAINNET_HELIUS
) {
    /** RPC URL: Helius when HELIUS_API_KEY is set in local.properties, else cluster default. */
    private val rpcUrl: String = BuildConfig.HELIUS_API_KEY?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { "https://mainnet.helius-rpc.com/?api-key=$it" }
        ?: cluster.rpcUrl

    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    
    companion object {
        private const val TAG = "SolanaRpcClient"
        
        // AllDomains ANS Program ID (mainnet)
        const val ANS_PROGRAM_ID = "ALTNSZ46uaAUU7XUV6awvdorLGqAsPwa9shm7h4uP2FK"
        
        // Name House Program (для NFT wrapping доменов)
        const val NAME_HOUSE_PROGRAM_ID = "NH3uX6FtVE2fNREAioP7hm5RaozotZxeL6khU1EHx51"
        
        // Solana Name Service (SNS) — fallback для .skr, если домен зарегистрирован через SNS
        private const val SNS_PROGRAM_ID = "namesLPvUudkx4YtC7vsyvwRrR3S52M8d8c"
        private const val SNS_OWNER_OFFSET = 32
        
        // NameRecordHeader: owner по offset 40 (8 discriminator + 32 parent TLD). Для только .skr можно добавить
        // второй фильтр memcmp offset=8, bytes=skrParentAccount (parent для TLD "skr" из TLD House).
        private const val ANS_OWNER_OFFSET = 40
        private const val ANS_PARENT_OFFSET = 8
        // @onsol/tldparser NameRecordHeader: discriminator 8 + parent 32 + owner 32 + nclass 32 + expiresAt 8 + createdAt 8 + nonTransferable 1 + padding 79 = 200
        private const val NAME_RECORD_HEADER_BYTE_SIZE = 200
        
        // Solana Mobile Guardian — стейкинг SKR (on-chain). Официально: blog Solana Mobile.
        const val SKR_STAKING_PROGRAM_ID = "SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ"
        /** Адрес стража «Solana Mobile Guardian» из ответа API stake.solanamobile.com (для PDA-диагностики). */
        private const val GUARDIAN_VALIDATOR_ADDRESS = "SKRGdBwzb1AtFW2chhBnZpGFnFLj6Mi7HM7iwjXALvw"
        /** Mint-адрес токена SKR (Solana Mobile). */
        const val SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
        private const val SKR_DECIMALS = 6
        // Layout без дискриминатора: owner 32 b @ 0, amount 8 b u64 LE @ 32. См. docs/SKR_STAKING_LAYOUT_FINDINGS.md
        private const val STAKING_ACCOUNT_OWNER_OFFSET = 0
        private const val STAKING_ACCOUNT_AMOUNT_OFFSET = 32
        private const val STAKING_ACCOUNT_AMOUNT_SIZE = 8

        /** Layout B (Anchor 8-byte discriminator): owner @ 8, amount @ 40. Используется в тестах и при необходимости в проде. */
        const val STAKING_ACCOUNT_OWNER_OFFSET_WITH_DISCRIMINATOR = 8
        const val STAKING_ACCOUNT_AMOUNT_OFFSET_WITH_DISCRIMINATOR = 40

        /**
         * Пары (owner offset, amount offset) для пробного запроса getProgramAccounts.
         * memcmp.bytes = base58(wallet); RPC декодирует base58 для сравнения.
         * Первый offset, по которому вернутся аккаунты, определяет layout.
         * (41, 73): реальный layout Guardian (подтверждён 2026-02-12): owner@41, data 169 байт; amount пробуем @73.
         */
        private val STAKING_MEMCMP_OFFSET_PAIRS: List<Pair<Int, Int>> = listOf(
            Pair(41, 73),  // Guardian layout: owner@41, amount@73 (169-byte accounts; found via dataSlice scan)
            Pair(0, 32),   // no discriminator: owner@0, amount@32
            Pair(8, 40),   // Anchor 8b disc: owner@8, amount@40
            Pair(24, 56),
            Pair(32, 64),
            Pair(40, 72)
        )
        
        /**
         * Парсит сумму стейка (u64 LE) из данных аккаунта. Для unit-тестов и единообразного расчёта.
         * @return raw amount или null если данных недостаточно
         */
        fun parseStakeAmountFromAccountData(data: ByteArray, amountOffset: Int): Long? {
            if (data.size < amountOffset + 8) return null
            return (data[amountOffset].toLong() and 0xFF) or
                ((data[amountOffset + 1].toLong() and 0xFF) shl 8) or
                ((data[amountOffset + 2].toLong() and 0xFF) shl 16) or
                ((data[amountOffset + 3].toLong() and 0xFF) shl 24) or
                ((data[amountOffset + 4].toLong() and 0xFF) shl 32) or
                ((data[amountOffset + 5].toLong() and 0xFF) shl 40) or
                ((data[amountOffset + 6].toLong() and 0xFF) shl 48) or
                ((data[amountOffset + 7].toLong() and 0xFF) shl 56)
        }

        /** Размер данных аккаунта Guardian (169 байт) — для точечного getProgramAccounts. */
        private const val STAKING_GUARDIAN_DATA_SIZE = 169

        /** TTL кэша .skr и стейка (2 мин), чтобы не дёргать RPC при повторных действиях. */
        private const val CACHE_TTL_MS = 2 * 60 * 1000L
        private val cacheSkr = ConcurrentHashMap<String, Pair<List<SkrDomainInfo>, Long>>()
        private val cacheStake = ConcurrentHashMap<String, Pair<StakedBalance, Long>>()

        // --- Задержки и ретраи при 429 (ужесточены после массовых 429 в проде) ---
        /** Ретраи при 429 для ANS getProgramAccounts (base58/base64). */
        private const val RPC_RETRY_ANS = 6
        /** Ретраи при 429 для Guardian getProgramAccounts. */
        private const val RPC_RETRY_STAKING = 7
        /** Базовая задержка при 429 (мс). */
        private const val RPC_BASE_DELAY_MS = 2200L
        /** Макс. задержка при 429 (мс). */
        private const val RPC_MAX_DELAY_MS = 14000L
        /** Пауза между шагами детекции .skr (снижает вероятность 429). */
        private const val DELAY_BETWEEN_ANS_STEPS_MS = 450L
        /** Пауза между попытками offset cascade в стейкинге (мс). */
        private const val DELAY_BETWEEN_STAKING_OFFSETS_MS = 280L
    }
    
    /**
     * Один запрос getProgramAccounts к Guardian с memcmp(offset=ownerOffset, bytes=…).
     * @param memcmpBytes значение для memcmp.bytes (base58 адрес или base64 от 32 байт pubkey — для RPC с строгим бинарным сравнением)
     * @param dataSize опциональный фильтр по размеру данных (40, 48, 56, 72, 80, 88, 96 — отсекает 2-byte accounts; per official answer)
     * @param commitment "confirmed" или "finalized"
     * @return result array или null при HTTP/RPC ошибке
     */
    private fun getProgramAccountsStakingWithMemcmp(
        memcmpBytes: String,
        ownerOffset: Int,
        dataSize: Int? = null,
        commitment: String = "confirmed"
    ): JSONArray? {
        val filters = JSONArray().apply {
            put(JSONObject().apply {
                put("memcmp", JSONObject().apply {
                    put("offset", ownerOffset)
                    put("bytes", memcmpBytes)
                })
            })
            if (dataSize != null) put(JSONObject().apply { put("dataSize", dataSize) })
        }
        val params = JSONArray().apply {
            put(SKR_STAKING_PROGRAM_ID)
            put(JSONObject().apply {
                put("encoding", "base64")
                put("commitment", commitment)
                put("filters", filters)
            })
        }
        val requestBody = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "getProgramAccounts")
            put("params", params)
        }.toString()
        val request = Request.Builder()
            .url(rpcUrl)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
            .build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: "{}"
        if (!response.isSuccessful) return null
        val jsonResponse = try { JSONObject(responseBody) } catch (_: Exception) { return null }
        if (jsonResponse.has("error")) return null
        return jsonResponse.optJSONArray("result") ?: JSONArray()
    }

    /** Вызов getProgramAccounts(Guardian) с memcmp.bytes = walletAddress (base58). */
    private fun getProgramAccountsStaking(
        walletAddress: String,
        ownerOffset: Int,
        dataSize: Int? = null,
        commitment: String = "confirmed"
    ): JSONArray? = getProgramAccountsStakingWithMemcmp(walletAddress, ownerOffset, dataSize, commitment)

    /**
     * Один getProgramAccounts(Guardian) с ретраем при 429. memcmp.bytes = base58 или base64 (32 байт).
     * @return result array или null при ошибке / пустом ответе
     */
    private suspend fun getProgramAccountsStakingWithRetry(
        memcmpBytes: String,
        ownerOffset: Int,
        dataSize: Int?
    ): JSONArray? = withContext(Dispatchers.IO) {
        getProgramAccountsStakingWithRetryInternal(memcmpBytes, ownerOffset, dataSize)
    }

    private suspend fun getProgramAccountsStakingWithRetryInternal(
        memcmpBytes: String,
        ownerOffset: Int,
        dataSize: Int?
    ): JSONArray? = withContext(Dispatchers.IO) {
        val filters = JSONArray().apply {
            put(JSONObject().apply {
                put("memcmp", JSONObject().apply {
                    put("offset", ownerOffset)
                    put("bytes", memcmpBytes)
                })
            })
            if (dataSize != null) put(JSONObject().apply { put("dataSize", dataSize) })
        }
        val params = JSONArray().apply {
            put(SKR_STAKING_PROGRAM_ID)
            put(JSONObject().apply {
                put("encoding", "base64")
                put("commitment", "confirmed")
                put("filters", filters)
            })
        }
        val requestBody = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "getProgramAccounts")
            put("params", params)
        }.toString()
        val request = Request.Builder()
            .url(rpcUrl)
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
            .build()
        val (response, responseBody) = executeRpcWithRetry(request, "STAKING_PA", RPC_RETRY_STAKING)
        if (!response.isSuccessful) return@withContext null
        val jsonResponse = try { JSONObject(responseBody) } catch (_: Exception) { return@withContext null }
        if (jsonResponse.has("error")) return@withContext null
        jsonResponse.optJSONArray("result") ?: JSONArray()
    }

    /**
     * Выполняет RPC POST; при HTTP 429 или RPC error code 429 повторяет запрос с экспоненциальной задержкой.
     * Использует RPC_BASE_DELAY_MS / RPC_MAX_DELAY_MS для баланса надёжность–время.
     * @param maxRetries сколько раз повторять после 429 (для ANS=4, Staking=5)
     */
    private suspend fun executeRpcWithRetry(
        request: Request,
        logTag: String,
        maxRetries: Int = 2
    ): Pair<okhttp3.Response, String> = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "{}"
            val isRateLimited = response.code == 429 || try {
                val j = JSONObject(body)
                j.has("error") && j.getJSONObject("error").optInt("code", -1) == 429
            } catch (_: Exception) {
                false
            }
            if (!isRateLimited || attempt >= maxRetries) {
                return@withContext Pair(response, body)
            }
            attempt++
            val backoffMs = minOf(RPC_BASE_DELAY_MS * (1 shl (attempt - 1)), RPC_MAX_DELAY_MS)
            DevLog.w(TAG, "[$logTag] 429 rate limit, retry $attempt/$maxRetries in ${backoffMs}ms")
            delay(backoffMs)
        }
        error("unreachable")
    }

    /**
     * Сумма застейканных SKR по кошельку (программа Solana Mobile Guardian).
     * Приоритет (см. docs/SKR_STAKING_QUICK_REFERENCE.md): стейк в program-owned аккаунтах, не в SPL delegate.
     * 1) getProgramAccounts(Guardian) с memcmp(owner=wallet) по offset 0, 8, 24, 32, 40 (+ finalized, dataSize).
     * 2) При отсутствии: getTokenAccountsByDelegate(GuardianProgramId, mint=SKR) (RPC может не индексировать delegate=programId).
     * 3) Fallback: getTokenAccountsByDelegate(wallet, mint=SKR).
     * @param walletAddress base58 публичный ключ кошелька
     * @return StakedBalance(rawAmount, humanReadable); при ошибке или отсутствии стейка — (0, 0.0)
     */
    suspend fun getStakedBalance(walletAddress: String): StakedBalance = withContext(Dispatchers.IO) {
        val walletShort = "${walletAddress.take(8)}...${walletAddress.takeLast(6)}"
        try {
            cacheStake[walletAddress]?.let { (cached, ts) ->
                if (System.currentTimeMillis() - ts < CACHE_TTL_MS) {
                    DevLog.i(TAG, "[STAKING] cache HIT for staked balance raw=${cached.rawAmount} human=${cached.humanReadable}")
                    return@withContext cached
                }
                cacheStake.remove(walletAddress)
            }
            val looksLikeHex = walletAddress.length == 64 && walletAddress.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            DevLog.i(TAG, "[STAKING] getStakedBalance ENTRY")
            DevLog.i(TAG, "[STAKING] wallet: len=${walletAddress.length} preview=${walletAddress.take(12)}...${walletAddress.takeLast(8)} short=$walletShort")
            DevLog.i(TAG, "[STAKING] wallet looksLikeHex=$looksLikeHex (expect false for base58; if true, RPC may fail like .skr)")
            DevLog.i(TAG, "[STAKING] programId=$SKR_STAKING_PROGRAM_ID mint=$SKR_MINT")
            if (BuildConfig.DEBUG) logStakingProgramAccountsDiagnostic(walletAddress)

            var resultArray: JSONArray? = null
            var amountOffsetUsed = STAKING_ACCOUNT_AMOUNT_OFFSET
            var ownerOffsetUsed = STAKING_ACCOUNT_OWNER_OFFSET

            // 1) Быстрый путь: getProgramAccounts(Guardian, offset=41, dataSize=169) с ретраем (base58)
            DevLog.i(TAG, "[STAKING] Priority: getProgramAccounts(Guardian, owner=41, dataSize=169) with retry")
            resultArray = getProgramAccountsStakingWithRetry(walletAddress, 41, STAKING_GUARDIAN_DATA_SIZE)
            if (resultArray != null && resultArray!!.length() > 0) {
                amountOffsetUsed = 73
                ownerOffsetUsed = 41
                DevLog.i(TAG, "[STAKING] Found ${resultArray!!.length()} accounts (priority path)")
            }
            if (resultArray == null || resultArray!!.length() == 0) {
                // 1b) Тот же запрос с memcmp base64 (RPC может требовать бинарное сравнение 32 байт)
                delay(DELAY_BETWEEN_STAKING_OFFSETS_MS)
                val walletBytes = try { walletAddress.decodeBase58() } catch (_: Exception) { null }
                if (walletBytes != null && walletBytes.size == 32) {
                    val base64Bytes = Base64.encodeToString(walletBytes, Base64.NO_WRAP)
                    DevLog.i(TAG, "[STAKING] Trying memcmp base64 (owner=41, dataSize=169)")
                    resultArray = getProgramAccountsStakingWithRetryInternal(base64Bytes, 41, STAKING_GUARDIAN_DATA_SIZE)
                    if (resultArray != null && resultArray!!.length() > 0) {
                        amountOffsetUsed = 73
                        ownerOffsetUsed = 41
                        DevLog.i(TAG, "[STAKING] Found ${resultArray!!.length()} accounts (base64 memcmp)")
                    }
                }
            }
            if (resultArray == null || resultArray!!.length() == 0) {
                // 2) Лёгкие методы: getTokenAccountsByDelegate (меньше нагрузка на RPC)
                DevLog.i(TAG, "[STAKING] No priority hit; trying getTokenAccountsByDelegate(Guardian, mint=SKR)")
                val guardianDelegateBalance = getStakedBalanceViaGuardianDelegate(walletAddress)
                if (guardianDelegateBalance != null) {
                    DevLog.i(TAG, "[STAKING] Using getTokenAccountsByDelegate(Guardian, mint=SKR): raw=${guardianDelegateBalance.first} human=${guardianDelegateBalance.second}")
                    val balance = StakedBalance(guardianDelegateBalance.first, guardianDelegateBalance.second)
                    cacheStake[walletAddress] = Pair(balance, System.currentTimeMillis())
                    return@withContext balance
                }
                val delegateBalance = getStakedBalanceViaDelegate(walletAddress)
                if (delegateBalance != null) {
                    DevLog.i(TAG, "[STAKING] Using delegate(wallet) fallback: raw=${delegateBalance.first} human=${delegateBalance.second}")
                    val balance = StakedBalance(delegateBalance.first, delegateBalance.second)
                    cacheStake[walletAddress] = Pair(balance, System.currentTimeMillis())
                    return@withContext balance
                }
            }
            if (resultArray == null || resultArray!!.length() == 0) {
                // 3) Fallback: перебор offset/dataSize (короткие паузы 80ms)
                DevLog.d(TAG, "[STAKING] Delegates empty; trying getProgramAccounts offset cascade")
                for ((ownerOff, amountOff) in STAKING_MEMCMP_OFFSET_PAIRS) {
                    val arr = getProgramAccountsStaking(walletAddress, ownerOff)
                    if (arr != null && arr.length() > 0) {
                        resultArray = arr
                        amountOffsetUsed = amountOff
                        ownerOffsetUsed = ownerOff
                        DevLog.i(TAG, "[STAKING] Found ${arr.length()} accounts with memcmp offset=$ownerOff")
                        break
                    }
                    delay(DELAY_BETWEEN_STAKING_OFFSETS_MS)
                }
            }
            if (resultArray == null || resultArray!!.length() == 0) {
                DevLog.d(TAG, "[STAKING] Trying commitment=finalized for offsets 0, 8")
                for ((ownerOff, amountOff) in listOf(Pair(0, 32), Pair(8, 40))) {
                    val arr = getProgramAccountsStaking(walletAddress, ownerOff, commitment = "finalized")
                    if (arr != null && arr.length() > 0) {
                        resultArray = arr
                        amountOffsetUsed = amountOff
                        ownerOffsetUsed = ownerOff
                        break
                    }
                    delay(DELAY_BETWEEN_STAKING_OFFSETS_MS)
                }
            }
            if (resultArray == null || resultArray!!.length() == 0) {
                DevLog.d(TAG, "[STAKING] Trying memcmp + dataSize for offsets 0, 8")
                for ((ownerOff, amountOff) in listOf(Pair(0, 32), Pair(8, 40))) {
                    for (dataSize in listOf(40, 48, 56, 72, 80, 88, 96)) {
                        val arr = getProgramAccountsStaking(walletAddress, ownerOff, dataSize = dataSize)
                        if (arr != null && arr.length() > 0) {
                            resultArray = arr
                            amountOffsetUsed = amountOff
                            ownerOffsetUsed = ownerOff
                            break
                        }
                    }
                    if (resultArray != null && resultArray!!.length() > 0) break
                    delay(DELAY_BETWEEN_STAKING_OFFSETS_MS)
                }
            }
            if (resultArray == null || resultArray!!.length() == 0) {
                val walletBytesLegacy = try { walletAddress.decodeBase58() } catch (_: Exception) { null }
                if (walletBytesLegacy != null && walletBytesLegacy.size == 32) {
                    val base64Bytes = Base64.encodeToString(walletBytesLegacy, Base64.NO_WRAP)
                    for ((ownerOff, amountOff) in listOf(Pair(0, 32), Pair(8, 40), Pair(32, 64))) {
                        val arr = getProgramAccountsStakingWithMemcmp(base64Bytes, ownerOff, dataSize = null)
                        if (arr != null && arr.length() > 0) {
                            resultArray = arr
                            amountOffsetUsed = amountOff
                            ownerOffsetUsed = ownerOff
                            break
                        }
                    }
                }
            }
            if (resultArray != null && resultArray!!.length() > 0) {
                val result = resultArray!!
                var totalRaw = 0L
                val amountOffsetsToTry = if (ownerOffsetUsed == 41) {
                    // Guardian 169-byte layout: owner@41; amount может быть @73..105 или @120 (по дампу VCzFREKx...)
                    listOf(73, 81, 89, 97, 105, 120)
                } else {
                    listOf(amountOffsetUsed)
                }
                for (i in 0 until result.length()) {
                    val acc = result.optJSONObject(i) ?: continue
                    val account = acc.optJSONObject("account") ?: continue
                    val dataBase64Raw = when (val d = account.get("data")) {
                        is String -> d
                        is JSONArray -> {
                            val arr = d as JSONArray
                            if (arr.length() >= 1) {
                                val first = arr.opt(0)
                                if (first is String) first else null
                            } else null
                        }
                        else -> null
                    } ?: continue
                    val decoded = try { Base64.decode(dataBase64Raw, Base64.NO_WRAP) } catch (_: Exception) { continue }
                    var amount: Long? = null
                    var usedOffset = amountOffsetUsed
                    for (ao in amountOffsetsToTry) {
                        val a = parseStakeAmountFromAccountData(decoded, ao)
                        if (a != null && a > 0 && a < 1_000_000_000_000_000L) {
                            amount = a
                            usedOffset = ao
                            break
                        }
                    }
                    if (amount != null) {
                        totalRaw += amount
                        DevLog.d(TAG, "[STAKING] account[$i] pubkey=${acc.optString("pubkey")} amount(raw)=$amount amountOffset=$usedOffset")
                    } else {
                        DevLog.d(TAG, "[STAKING] account[$i] pubkey=${acc.optString("pubkey")} no valid amount at offsets $amountOffsetsToTry")
                    }
                }
                var divisor = 1.0
                repeat(SKR_DECIMALS) { divisor *= 10 }
                val humanReadable = totalRaw / divisor
                DevLog.i(TAG, "[STAKING] getStakedBalance EXIT (from getProgramAccounts) totalRaw=$totalRaw human=$humanReadable accounts=${result.length()} memcmpOffset=$ownerOffsetUsed amountOffset=$amountOffsetUsed")
                DevLog.i(TAG, "[STAKING] getStakedBalance EXIT")
                val balance = StakedBalance(totalRaw, humanReadable)
                cacheStake[walletAddress] = Pair(balance, System.currentTimeMillis())
                return@withContext balance
            }
            if (BuildConfig.DEBUG) {
                logStakingPdaDiagnostic(walletAddress)
                logStakingDiagnostics(walletAddress)
            }
            DevLog.w(TAG, "[STAKING] No program accounts, no Guardian delegate, no delegate(wallet); staked balance = 0")
            DevLog.i(TAG, "[STAKING] getStakedBalance EXIT (zero)")
            val zeroBalance = StakedBalance(0L, 0.0)
            cacheStake[walletAddress] = Pair(zeroBalance, System.currentTimeMillis())
            return@withContext zeroBalance
        } catch (e: Exception) {
            DevLog.e(TAG, "[STAKING] getStakedBalance exception: ${e.message}", e)
            DevLog.i(TAG, "[STAKING] getStakedBalance EXIT (exception)")
            return@withContext StakedBalance(0L, 0.0)
        }
    }

    /**
     * Канонический метод (по ответу Solana Mobile): getTokenAccountsByDelegate(GuardianProgramId, mint=SKR).
     * Возвращает все SKR токен-аккаунты, где delegate = Guardian; фильтруем по owner == wallet, суммируем delegatedAmount.
     * @return Pair(rawAmount, humanReadable) или null при ошибке / пустом ответе
     */
    private fun getStakedBalanceViaGuardianDelegate(walletAddress: String): Pair<Long, Double>? {
        val logTag = "[STAKING_GUARDIAN_DELEGATE]"
        return try {
            val body = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getTokenAccountsByDelegate")
                put("params", JSONArray().apply {
                    put(SKR_STAKING_PROGRAM_ID)
                    put(JSONObject().apply { put("mint", SKR_MINT) })
                    put(JSONObject().apply { put("encoding", "jsonParsed"); put("commitment", "confirmed") })
                })
            }.toString()
            DevLog.i(TAG, "$logTag REQUEST")
            DevLog.i(TAG, "$logTag method=getTokenAccountsByDelegate delegate=$SKR_STAKING_PROGRAM_ID mint=$SKR_MINT encoding=jsonParsed commitment=confirmed")
            DevLog.i(TAG, "$logTag url=${rpcUrl.take(50)}... bodyLen=${body.length}")
            val request = Request.Builder()
                .url(rpcUrl)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            DevLog.i(TAG, "$logTag RESPONSE")
            DevLog.i(TAG, "$logTag code=${response.code} bodyLen=${responseBody.length}")
            if (!response.isSuccessful) {
                DevLog.w(TAG, "$logTag HTTP not successful; body(500)=${responseBody.take(500)}")
                return null
            }
            val json = try { JSONObject(responseBody) } catch (e: Exception) {
                DevLog.w(TAG, "$logTag JSON parse error: ${e.message} body(300)=${responseBody.take(300)}")
                return null
            }
            if (json.has("error")) {
                val err = json.optJSONObject("error")
                DevLog.w(TAG, "$logTag RPC error: code=${err?.opt("code")} message=${err?.optString("message")} data=${err?.opt("data")}")
                return null
            }
            val result = json.optJSONObject("result")
            val value = result?.optJSONArray("value")
            val valueLen = value?.length() ?: 0
            DevLog.i(TAG, "$logTag result.value.length=$valueLen (total token accounts where delegate=Guardian, mint=SKR)")
            if (value == null || valueLen == 0) {
                DevLog.i(TAG, "$logTag RETURN null: no accounts (RPC may not index delegate=programId)")
                return null
            }
            var totalRaw = 0L
            val maxLogAccounts = 5
            for (i in 0 until value.length()) {
                val item = value.optJSONObject(i) ?: continue
                val pubkey = item.optString("pubkey", "")
                val acc = item.optJSONObject("account") ?: continue
                val data = acc.optJSONObject("data") ?: continue
                val parsed = data.optJSONObject("parsed") ?: continue
                val info = parsed.optJSONObject("info") ?: continue
                val owner = info.optString("owner", "")
                val delegate = info.optString("delegate", "")
                val delegatedAmount = info.optJSONObject("delegatedAmount")
                val amountStr = delegatedAmount?.optString("amount")
                    ?: info.optJSONObject("tokenAmount")?.optString("amount", "0")
                    ?: "0"
                val amountLong = amountStr.toLongOrNull() ?: 0L
                val ownerMatches = owner == walletAddress
                if (i < maxLogAccounts) {
                    DevLog.i(TAG, "$logTag [$i] pubkey=$pubkey owner=${owner.take(12)}... ownerMatch=$ownerMatches delegate=${delegate.take(12)}... amount=$amountStr")
                }
                if (!ownerMatches) continue
                totalRaw += amountLong
            }
            if (totalRaw == 0L) {
                DevLog.i(TAG, "$logTag RETURN null: totalRaw=0 (no account with owner==wallet; check owner format in logs above)")
                return null
            }
            var div = 1.0
            repeat(SKR_DECIMALS) { div *= 10 }
            val human = totalRaw / div
            DevLog.i(TAG, "$logTag RETURN Pair(raw=$totalRaw human=$human)")
            Pair(totalRaw, human)
        } catch (e: Exception) {
            DevLog.e(TAG, "$logTag EXCEPTION: ${e.message}", e)
            null
        }
    }

    /**
     * Fallback: getTokenAccountsByDelegate(wallet, mint=SKR) — когда delegate = кошелёк (альтернативный вариант RPC).
     * @return Pair(rawAmount, humanReadable) или null при ошибке / пустом ответе
     */
    private fun getStakedBalanceViaDelegate(walletAddress: String): Pair<Long, Double>? {
        val logTag = "[STAKING_DELEGATE_FALLBACK]"
        return try {
            val body = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getTokenAccountsByDelegate")
                put("params", JSONArray().apply {
                    put(walletAddress)
                    put(JSONObject().apply { put("mint", SKR_MINT) })
                    put(JSONObject().apply { put("encoding", "jsonParsed"); put("commitment", "confirmed") })
                })
            }.toString()
            DevLog.i(TAG, "$logTag REQUEST: delegate=wallet mint=$SKR_MINT url(50)=${rpcUrl.take(50)}... bodyLen=${body.length}")
            val request = Request.Builder()
                .url(rpcUrl)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            DevLog.i(TAG, "$logTag RESPONSE: code=${response.code} bodyLen=${responseBody.length}")
            if (!response.isSuccessful) {
                DevLog.w(TAG, "$logTag HTTP not successful; body(300)=${responseBody.take(300)}")
                return null
            }
            val json = try { JSONObject(responseBody) } catch (e: Exception) {
                DevLog.w(TAG, "$logTag JSON parse error: ${e.message}")
                return null
            }
            if (json.has("error")) {
                DevLog.w(TAG, "$logTag RPC error: ${json.optJSONObject("error")?.toString()}")
                return null
            }
            val value = json.optJSONObject("result")?.optJSONArray("value") ?: run {
                DevLog.i(TAG, "$logTag result.value is null or missing")
                return null
            }
            DevLog.i(TAG, "$logTag result.value.length=${value.length()}")
            var totalRaw = 0L
            for (i in 0 until value.length()) {
                val item = value.optJSONObject(i) ?: continue
                val acc = item.optJSONObject("account") ?: continue
                val data = acc.optJSONObject("data") ?: continue
                val parsed = data.optJSONObject("parsed") ?: continue
                val info = parsed.optJSONObject("info") ?: continue
                val ta = info.optJSONObject("tokenAmount") ?: continue
                val amountStr = ta.optString("amount", "0")
                totalRaw += amountStr.toLongOrNull() ?: 0L
            }
            if (totalRaw == 0L) {
                DevLog.i(TAG, "$logTag RETURN null: totalRaw=0")
                return null
            }
            var div = 1.0
            repeat(SKR_DECIMALS) { div *= 10 }
            DevLog.i(TAG, "$logTag RETURN Pair(raw=$totalRaw human=${totalRaw / div})")
            Pair(totalRaw, totalRaw / div)
        } catch (e: Exception) {
            DevLog.e(TAG, "$logTag EXCEPTION: ${e.message}", e)
            null
        }
    }

    /**
     * Диагностика getProgramAccounts(Guardian): без dataSize, только memcmp(owner=wallet).
     * Логирует число аккаунтов и для каждого — pubkey, размер data, первые байты (hex).
     * Пробует memcmp.bytes = base58 и base64(32-байт pubkey) на offset 8 (и 0, 32) для выявления формата RPC.
     */
    private fun logStakingProgramAccountsDiagnostic(walletAddress: String) {
        try {
            DevLog.i(TAG, "[STAKING_PA_DIAG] ========== getProgramAccounts(Guardian) WITHOUT dataSize ==========")
            for (ownerOffset in listOf(41, 8, 0, 32)) {
                val arrBase58 = getProgramAccountsStakingWithMemcmp(walletAddress, ownerOffset, dataSize = null)
                val count = arrBase58?.length() ?: 0
                DevLog.i(TAG, "[STAKING_PA_DIAG] memcmp(offset=$ownerOffset, bytes=base58) -> count=$count")
                if (count > 0 && arrBase58 != null) {
                    for (i in 0 until minOf(arrBase58.length(), 5)) {
                        val acc = arrBase58.optJSONObject(i) ?: continue
                        val pubkey = acc.optString("pubkey", "")
                        val account = acc.optJSONObject("account") ?: continue
                        val dataRaw = when (val d = account.get("data")) {
                            is String -> d
                            is JSONArray -> if (d.length() > 0) d.optString(0) else null
                            else -> null
                        }
                        if (dataRaw != null) {
                            val decoded = try { Base64.decode(dataRaw, Base64.NO_WRAP) } catch (_: Exception) { byteArrayOf() }
                            val hexPreview = decoded.take(48).joinToString("") { "%02x".format(it.toInt() and 0xFF) } + if (decoded.size > 48) "..." else ""
                            DevLog.i(TAG, "[STAKING_PA_DIAG]   account[$i] pubkey=$pubkey dataSize=${decoded.size} dataHex(48)=$hexPreview")
                        }
                    }
                    if (arrBase58.length() > 5) DevLog.i(TAG, "[STAKING_PA_DIAG]   ... and ${arrBase58.length() - 5} more")
                }
            }
            val walletBytes = try { walletAddress.decodeBase58() } catch (_: Exception) { null }
            if (walletBytes != null && walletBytes.size == 32) {
                val base64Bytes = Base64.encodeToString(walletBytes, Base64.NO_WRAP)
                DevLog.i(TAG, "[STAKING_PA_DIAG] Trying memcmp.bytes=base64(32) for offsets 41, 8, 0, 32")
                for (ownerOffset in listOf(41, 8, 0, 32)) {
                    val arr = getProgramAccountsStakingWithMemcmp(base64Bytes, ownerOffset, dataSize = null)
                    val count = arr?.length() ?: 0
                    DevLog.i(TAG, "[STAKING_PA_DIAG] memcmp(offset=$ownerOffset, bytes=base64) -> count=$count")
                    if (count > 0 && arr != null) {
                        for (i in 0 until minOf(arr.length(), 3)) {
                            val acc = arr.optJSONObject(i) ?: continue
                            val pubkey = acc.optString("pubkey", "")
                            val account = acc.optJSONObject("account") ?: continue
                            val dataRaw = when (val d = account.get("data")) {
                                is String -> d
                                is JSONArray -> if (d.length() > 0) d.optString(0) else null
                                else -> null
                            }
                            if (dataRaw != null) {
                                val decoded = try { Base64.decode(dataRaw, Base64.NO_WRAP) } catch (_: Exception) { byteArrayOf() }
                                DevLog.i(TAG, "[STAKING_PA_DIAG]   account[$i] pubkey=$pubkey dataSize=${decoded.size}")
                            }
                        }
                    }
                }
            } else {
                DevLog.w(TAG, "[STAKING_PA_DIAG] wallet decodeBase58 failed or len!=32, skip base64 memcmp")
            }
            DevLog.i(TAG, "[STAKING_PA_DIAG] ========== End getProgramAccounts diagnostic ==========")
        } catch (e: Exception) {
            DevLog.e(TAG, "[STAKING_PA_DIAG] failed: ${e.message}", e)
        }
    }

    /**
     * Диагностика PDA: считает возможные стейк-PDA для Guardian (wallet + guardian address),
     * запрашивает getAccountInfo и логирует размер данных и превью (для вывода layout).
     */
    private suspend fun logStakingPdaDiagnostic(walletAddress: String) {
        try {
            val programIdBytes = try { SKR_STAKING_PROGRAM_ID.decodeBase58() } catch (_: Exception) { null } ?: return
            val guardianBytes = try { GUARDIAN_VALIDATOR_ADDRESS.decodeBase58() } catch (_: Exception) { null } ?: return
            val walletBytes = try { walletAddress.decodeBase58() } catch (_: Exception) { null } ?: return
            if (walletBytes.size != 32) return
            DevLog.i(TAG, "[STAKING_PDA_DIAG] ========== Guardian PDA diagnostic ==========")
            val seedsList = listOf(
                listOf("stake".toByteArray(Charsets.UTF_8), walletBytes, guardianBytes),
                listOf("user_stake".toByteArray(Charsets.UTF_8), walletBytes),
                listOf("delegation".toByteArray(Charsets.UTF_8), walletBytes, guardianBytes),
                listOf("stake".toByteArray(Charsets.UTF_8), walletBytes),
                listOf("user_delegation".toByteArray(Charsets.UTF_8), walletBytes, guardianBytes)
            )
            for (seeds in seedsList) {
                val pda = SolanaPda.findProgramAddress(seeds, programIdBytes)?.first ?: continue
                val pdaBase58 = Base58.encode(pda)
                val base64Data = getAccountInfoBase64(pdaBase58)
                if (base64Data != null) {
                    val decoded = Base64.decode(base64Data, Base64.NO_WRAP)
                    val hexPreview = decoded.take(64).joinToString("") { "%02x".format(it.toInt() and 0xFF) } + if (decoded.size > 64) "..." else ""
                    DevLog.i(TAG, "[STAKING_PDA_DIAG] FOUND PDA seeds=${seeds.map { it.size }} pda=$pdaBase58 dataSize=${decoded.size} dataHex(64)=$hexPreview")
                }
            }
            DevLog.i(TAG, "[STAKING_PDA_DIAG] ========== End PDA diagnostic ==========")
        } catch (e: Exception) {
            DevLog.e(TAG, "[STAKING_PDA_DIAG] failed: ${e.message}", e)
        }
    }

    /**
     * Диагностика: логирует getTokenAccountsByOwner и getTokenAccountsByDelegate для SKR,
     * чтобы видеть, приходят ли стейк-токены как owner/delegate аккаунты.
     */
    private suspend fun logStakingDiagnostics(walletAddress: String) {
        try {
            DevLog.i(TAG, "[STAKING_DIAG] ========== Token accounts diagnostic (SKR mint) ==========")
            // getTokenAccountsByOwner(wallet, mint=SKR)
            val bodyOwner = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getTokenAccountsByOwner")
                put("params", JSONArray().apply {
                    put(walletAddress)
                    put(JSONObject().apply { put("mint", SKR_MINT) })
                    put(JSONObject().apply { put("encoding", "jsonParsed"); put("commitment", "confirmed") })
                })
            }.toString()
            DevLog.i(TAG, "[STAKING_DIAG] Request getTokenAccountsByOwner: owner=$walletAddress mint=$SKR_MINT")
            val reqOwner = Request.Builder().url(rpcUrl).post(bodyOwner.toRequestBody(JSON_MEDIA_TYPE)).addHeader("Content-Type", "application/json").build()
            val respOwner = client.newCall(reqOwner).execute()
            val bodyOwnerResp = respOwner.body?.string() ?: "{}"
            DevLog.i(TAG, "[STAKING_DIAG] getTokenAccountsByOwner response: code=${respOwner.code} bodyLen=${bodyOwnerResp.length}")
            val jsonOwner = try { JSONObject(bodyOwnerResp) } catch (_: Exception) { null }
            if (jsonOwner != null && jsonOwner.has("error")) {
                DevLog.w(TAG, "[STAKING_DIAG] getTokenAccountsByOwner RPC error: ${jsonOwner.optJSONObject("error")?.toString()}")
            } else {
                val resOwner = jsonOwner?.optJSONObject("result")
                val valueOwner = resOwner?.optJSONArray("value") ?: JSONArray()
                DevLog.i(TAG, "[STAKING_DIAG] getTokenAccountsByOwner result.value.length=${valueOwner.length()} (token accounts owned by wallet, mint=SKR)")
                for (i in 0 until valueOwner.length()) {
                    val item = valueOwner.optJSONObject(i) ?: continue
                    val pubkey = item.optString("pubkey", "")
                    val acc = item.optJSONObject("account") ?: continue
                    val data = acc.optJSONObject("data") ?: continue
                    val parsed = data.optJSONObject("parsed") ?: continue
                    val info = parsed.optJSONObject("info") ?: continue
                    val ta = info.optJSONObject("tokenAmount") ?: continue
                    val amount = ta.optString("amount", "0")
                    val decimals = ta.optInt("decimals", -1)
                    val owner = info.optString("owner", "")
                    val delegate = info.optString("delegate", "").takeIf { it.isNotEmpty() } ?: "(none)"
                    DevLog.i(TAG, "[STAKING_DIAG] getTokenAccountsByOwner[$i] pubkey=$pubkey owner=$owner delegate=$delegate amount=$amount decimals=$decimals")
                }
            }
            // getTokenAccountsByDelegate(wallet, mint=SKR)
            val bodyDelegate = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 2)
                put("method", "getTokenAccountsByDelegate")
                put("params", JSONArray().apply {
                    put(walletAddress)
                    put(JSONObject().apply { put("mint", SKR_MINT) })
                    put(JSONObject().apply { put("encoding", "jsonParsed"); put("commitment", "confirmed") })
                })
            }.toString()
            DevLog.i(TAG, "[STAKING_DIAG] Request getTokenAccountsByDelegate: delegate=$walletAddress mint=$SKR_MINT")
            val reqDelegate = Request.Builder().url(rpcUrl).post(bodyDelegate.toRequestBody(JSON_MEDIA_TYPE)).addHeader("Content-Type", "application/json").build()
            val respDelegate = client.newCall(reqDelegate).execute()
            val bodyDelegateResp = respDelegate.body?.string() ?: "{}"
            DevLog.i(TAG, "[STAKING_DIAG] getTokenAccountsByDelegate response: code=${respDelegate.code} bodyLen=${bodyDelegateResp.length}")
            val jsonDelegate = try { JSONObject(bodyDelegateResp) } catch (_: Exception) { null }
            if (jsonDelegate != null && jsonDelegate.has("error")) {
                DevLog.w(TAG, "[STAKING_DIAG] getTokenAccountsByDelegate RPC error: ${jsonDelegate.optJSONObject("error")?.toString()}")
            } else {
                val resDelegate = jsonDelegate?.optJSONObject("result")
                val valueDelegate = resDelegate?.optJSONArray("value") ?: JSONArray()
                DevLog.i(TAG, "[STAKING_DIAG] getTokenAccountsByDelegate result.value.length=${valueDelegate.length()} (token accounts where wallet is DELEGATE, mint=SKR)")
                for (i in 0 until valueDelegate.length()) {
                    val item = valueDelegate.optJSONObject(i) ?: continue
                    val pubkey = item.optString("pubkey", "")
                    val acc = item.optJSONObject("account") ?: continue
                    val data = acc.optJSONObject("data") ?: continue
                    val parsed = data.optJSONObject("parsed") ?: continue
                    val info = parsed.optJSONObject("info") ?: continue
                    val ta = info.optJSONObject("tokenAmount") ?: continue
                    val amount = ta.optString("amount", "0")
                    val decimals = ta.optInt("decimals", -1)
                    val owner = info.optString("owner", "")
                    val delegate = info.optString("delegate", "")
                    DevLog.i(TAG, "[STAKING_DIAG] getTokenAccountsByDelegate[$i] pubkey=$pubkey owner=$owner delegate=$delegate amount=$amount decimals=$decimals")
                }
            }
            DevLog.i(TAG, "[STAKING_DIAG] ========== End token accounts diagnostic ==========")
        } catch (e: Exception) {
            DevLog.e(TAG, "[STAKING_DIAG] diagnostic failed: ${e.message}", e)
        }
    }

    /**
     * Свободный баланс SKR в кошельке (для покупки бустов). Только непозалоченные токены.
     * @param walletAddress base58 публичный ключ кошелька
     * @return Сумма в наименьших единицах (6 decimals); при ошибке — 0
     */
    suspend fun getAvailableSkrForPurchase(walletAddress: String): Long = withContext(Dispatchers.IO) {
        try {
            DevLog.d(TAG, "[SKR_BALANCE] getAvailableSkrForPurchase wallet=${walletAddress.take(12)}...")
            val requestBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getTokenAccountsByOwner")
                put("params", JSONArray().apply {
                    put(walletAddress)
                    put(JSONObject().apply { put("mint", SKR_MINT) })
                    put(JSONObject().apply { put("encoding", "jsonParsed") })
                })
            }.toString()
            val request = Request.Builder()
                .url(rpcUrl)
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                DevLog.w(TAG, "[SKR_BALANCE] HTTP failed: ${response.code}")
                return@withContext 0L
            }
            val jsonResponse = JSONObject(responseBody)
            if (jsonResponse.has("error")) {
                DevLog.w(TAG, "[SKR_BALANCE] RPC error: ${jsonResponse.optJSONObject("error")?.toString()?.take(200)}")
                return@withContext 0L
            }
            val result = jsonResponse.optJSONObject("result")
            val value = result?.optJSONArray("value") ?: JSONArray()
            var totalRaw = 0L
            for (i in 0 until value.length()) {
                val item = value.optJSONObject(i) ?: continue
                val account = item.optJSONObject("account") ?: continue
                val data = account.optJSONObject("data") ?: continue
                val parsed = data.optJSONObject("parsed") ?: continue
                val info = parsed.optJSONObject("info") ?: continue
                val tokenAmount = info.optJSONObject("tokenAmount") ?: continue
                val amountStr = tokenAmount.optString("amount", "0")
                totalRaw += amountStr.toLongOrNull() ?: 0L
            }
            DevLog.d(TAG, "[SKR_BALANCE] totalRaw=$totalRaw accounts=${value.length()}")
            totalRaw
        } catch (e: Exception) {
            DevLog.e(TAG, "[SKR_BALANCE] getAvailableSkrForPurchase exception", e)
            0L
        }
    }
    
    /**
     * Получает все .skr домены, принадлежащие wallet
     * 
     * STRATEGY:
     * 1. Сначала ищем через getProgramAccounts с фильтром owner
     * 2. Если не нашли - пробуем getTokenAccountsByOwner (для wrapped NFT domains)
     * 3. Парсим и фильтруем только .skr домены
     * 
     * @param ownerPubkey - публичный ключ кошелька (base58)
     * @return список .skr доменов владельца
     */
    suspend fun getAllSkrDomains(ownerPubkey: String): List<SkrDomainInfo> = 
        withContext(Dispatchers.IO) {
            try {
                // Кэш: повторные запросы в течение 2 мин возвращаются без RPC
                cacheSkr[ownerPubkey]?.let { (cached, ts) ->
                    if (System.currentTimeMillis() - ts < CACHE_TTL_MS) {
                        DevLog.i(TAG, "[DETECT] cache HIT for .skr domains count=${cached.size}")
                        return@withContext cached
                    }
                    cacheSkr.remove(ownerPubkey)
                }
                DevLog.i(TAG, "########################################")
                DevLog.i(TAG, "######## .skr DETECTION START ########")
                DevLog.i(TAG, "########################################")
                DevLog.i(TAG, "[DETECT] ownerPubkey (full)=$ownerPubkey")
                DevLog.i(TAG, "[DETECT] ownerPubkey length=${ownerPubkey.length} sample=${ownerPubkey.take(12)}...${ownerPubkey.takeLast(8)}")
                DevLog.i(TAG, "[DETECT] cluster=${cluster.name} rpcUrl=${rpcUrl.take(60)}...")
                DevLog.i(TAG, "[DETECT] ANS_PROGRAM_ID=$ANS_PROGRAM_ID")
                DevLog.i(TAG, "[DETECT] ANS_OWNER_OFFSET=$ANS_OWNER_OFFSET")
                // Оптимизация: один точечный getProgramAccounts(ANS) первым, затем SNS, wrapped — fallback (меньше RPC, быстрее).
                // STEP 1: один getProgramAccounts(ANS) с memcmp base58 — основной сценарий
                DevLog.i(TAG, "[DETECT] STEP 1 START: ANS getProgramAccounts (memcmp base58)...")
                var allDomains = queryStandardAnsDomains(ownerPubkey)
                DevLog.i(TAG, "[DETECT] STEP 1 END: ANS count=${allDomains.size}")
                
                if (allDomains.isEmpty()) {
                    // STEP 1b: ANS с memcmp base64 (многие RPC ожидают 32 байта в base64, не base58)
                    delay(DELAY_BETWEEN_ANS_STEPS_MS)
                    DevLog.i(TAG, "[DETECT] STEP 1b START: ANS getProgramAccounts (memcmp base64)...")
                    allDomains = queryStandardAnsDomainsWithBase64Memcmp(ownerPubkey)
                    DevLog.i(TAG, "[DETECT] STEP 1b END: ANS count=${allDomains.size}")
                    if (allDomains.isNotEmpty()) {
                        DevLog.i(TAG, "[DETECT] ✅ Using .skr from ANS base64: ${allDomains.map { it.domainName }}")
                    }
                }
                
                if (allDomains.isEmpty()) {
                    delay(DELAY_BETWEEN_ANS_STEPS_MS)
                    // STEP 2: SNS getProgramAccounts (base58 only)
                    DevLog.i(TAG, "[DETECT] STEP 2 START: SNS fallback (base58 only)...")
                    val snsDomains = querySkrViaSnsWithBytes(ownerPubkey, ownerPubkey, "base58") ?: emptyList()
                    DevLog.i(TAG, "[DETECT] STEP 2 END: SNS count=${snsDomains.size}")
                    if (snsDomains.isNotEmpty()) {
                        allDomains = snsDomains
                        DevLog.i(TAG, "[DETECT] ✅ Using .skr from SNS: ${snsDomains.map { it.domainName }}")
                    }
                }
                
                if (allDomains.isEmpty()) {
                    delay(DELAY_BETWEEN_ANS_STEPS_MS)
                    // STEP 3: wrapped .skr через getTokenAccountsByOwner + ограниченный getAsset (fallback)
                    DevLog.i(TAG, "[DETECT] STEP 3 START: wrapped (token accounts + limited getAsset)...")
                    allDomains = queryWrappedAnsDomains(ownerPubkey)
                    DevLog.i(TAG, "[DETECT] STEP 3 END: wrapped count=${allDomains.size}")
                }
                
                DevLog.i(TAG, "######## .skr DETECTION END: total=${allDomains.size} ########")
                allDomains.forEachIndexed { i, domain ->
                    DevLog.i(TAG, "   [DETECT] domain[$i]: name=${domain.domainName} pubkey=${domain.pubkey} owner=${domain.owner.take(12)}...")
                }
                cacheSkr[ownerPubkey] = Pair(allDomains, System.currentTimeMillis())
                allDomains
                
            } catch (e: Exception) {
                DevLog.e(TAG, "❌ getAllSkrDomains exception", e)
                DevLog.e(TAG, "Exception message: ${e.message} cause: ${e.cause?.message}")
                emptyList()
            }
        }
    
    /**
     * Проверка .skr через Solana Name Service (SNS).
     * Используется как fallback, если AllDomains не вернул доменов.
     * Пробуем: (1) base58, (2) base64 от 32 байт, (3) hex от 32 байт (64 символа) — для RPC с WrongSize.
     */
    private suspend fun querySkrViaSns(publicKeyBase58: String): List<SkrDomainInfo> = withContext(Dispatchers.IO) {
        DevLog.d(TAG, "[SNS] querySkrViaSns ENTRY")
        DevLog.d(TAG, "[SNS] publicKeyBase58=$publicKeyBase58 length=${publicKeyBase58.length}")
        var list = querySkrViaSnsWithBytes(publicKeyBase58, snsBytes = publicKeyBase58, bytesKind = "base58")
        if (list != null) {
            DevLog.d(TAG, "[SNS] base58 attempt OK: ${list.size} domains")
            return@withContext list
        }
        DevLog.d(TAG, "[SNS] base58 returned null, trying base64...")
        val ownerBytes32 = try {
            publicKeyBase58.decodeBase58()
        } catch (e: Exception) {
            DevLog.w(TAG, "[SNS] decodeBase58 failed: ${e.message}")
            return@withContext emptyList()
        }
        if (ownerBytes32.size != 32) {
            DevLog.w(TAG, "[SNS] ownerBytes32.size=${ownerBytes32.size} expected 32")
            return@withContext emptyList()
        }
        val ownerBase64 = Base64.encodeToString(ownerBytes32, Base64.NO_WRAP)
        DevLog.d(TAG, "[SNS] ownerBase64 length=${ownerBase64.length} preview=${ownerBase64.take(20)}...")
        list = querySkrViaSnsWithBytes(publicKeyBase58, snsBytes = ownerBase64, bytesKind = "base64")
        if (list != null) {
            DevLog.d(TAG, "[SNS] base64 attempt OK: ${list.size} domains")
            return@withContext list
        }
        DevLog.d(TAG, "[SNS] base64 returned null, trying hex...")
        val ownerHex = ownerBytes32.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        DevLog.d(TAG, "[SNS] ownerHex length=${ownerHex.length} preview=${ownerHex.take(16)}...")
        list = querySkrViaSnsWithBytes(publicKeyBase58, snsBytes = ownerHex, bytesKind = "hex")
        if (list != null) {
            DevLog.d(TAG, "[SNS] hex attempt OK: ${list.size} domains")
            return@withContext list
        }
        DevLog.w(TAG, "[SNS] All 3 attempts (base58, base64, hex) returned no .skr")
        emptyList()
    }

    /**
     * Один запрос getProgramAccounts к SNS с заданным memcmp.bytes.
     * @param snsBytes значение для memcmp.bytes (base58 строка или base64 строка)
     * @param bytesKind метка для логов ("base58" / "base64")
     * @return список .skr или null при RPC ошибке (чтобы можно было повторить с другим encoding)
     */
    private suspend fun querySkrViaSnsWithBytes(
        publicKeyBase58: String,
        snsBytes: String,
        bytesKind: String
    ): List<SkrDomainInfo>? = withContext(Dispatchers.IO) {
        try {
            DevLog.d(TAG, "[SNS_$bytesKind] request: program=$SNS_PROGRAM_ID offset=$SNS_OWNER_OFFSET bytesKind=$bytesKind snsBytesLength=${snsBytes.length} preview=${snsBytes.take(20)}...")
            val requestBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getProgramAccounts")
                put("params", JSONArray().apply {
                    put(SNS_PROGRAM_ID)
                    put(JSONObject().apply {
                        put("encoding", "base64")
                        put("commitment", "confirmed")
                        put("filters", JSONArray().apply {
                            put(JSONObject().apply {
                                put("memcmp", JSONObject().apply {
                                    put("offset", SNS_OWNER_OFFSET)
                                    put("bytes", snsBytes)
                                })
                            })
                        })
                    })
                })
            }.toString()
            DevLog.d(TAG, "[SNS_$bytesKind] request bodyLen=${requestBody.length}")
            val request = Request.Builder()
                .url(rpcUrl)
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()
            val (response, responseBody) = executeRpcWithRetry(request, "SNS_$bytesKind", RPC_RETRY_ANS)
            DevLog.d(TAG, "[SNS_$bytesKind] response code=${response.code} bodyLen=${responseBody.length} preview=${responseBody.take(400)}")
            if (!response.isSuccessful) return@withContext null
            val json = JSONObject(responseBody)
            if (json.has("error")) {
                val err = json.getJSONObject("error")
                val code = err.optInt("code", -1)
                val message = err.optString("message", "")
                DevLog.w(TAG, "[SNS_$bytesKind] RPC error: code=$code message=$message")
                return@withContext null
            }
            val accounts = json.optJSONArray("result") ?: JSONArray()
            DevLog.d(TAG, "[SNS_$bytesKind] result: ${accounts.length()} accounts")
            val list = mutableListOf<SkrDomainInfo>()
            for (i in 0 until accounts.length()) {
                val acc = accounts.getJSONObject(i)
                val pubkey = acc.optString("pubkey", "")
                val account = acc.optJSONObject("account")
                val dataField = account?.get("data")
                val base64Data = when (dataField) {
                    is String -> dataField
                    is JSONArray -> if (dataField.length() > 0) dataField.getString(0) else null
                    else -> null
                }
                DevLog.d(TAG, "[SNS_$bytesKind] account[$i] pubkey=$pubkey dataFieldType=${dataField?.javaClass?.simpleName} base64Len=${base64Data?.length ?: 0}")
                if (base64Data.isNullOrEmpty()) {
                    DevLog.d(TAG, "[SNS_$bytesKind] account[$i] skip: no data")
                    continue
                }
                val decoded = try {
                    Base64.decode(base64Data, Base64.NO_WRAP)
                } catch (e: Exception) {
                    DevLog.d(TAG, "[SNS_$bytesKind] account[$i] base64 decode failed: ${e.message}")
                    continue
                }
                DevLog.d(TAG, "[SNS_$bytesKind] account[$i] decodedSize=${decoded.size} hex(32)=${decoded.take(32).joinToString("") { "%02x".format(it.toInt() and 0xFF) }}...")
                val asStr = String(decoded, Charsets.UTF_8)
                val hasSkr = asStr.contains(".skr")
                DevLog.d(TAG, "[SNS_$bytesKind] account[$i] asStr(100)=${asStr.take(100).replace(Char(0), '.')}... hasSkr=$hasSkr")
                if (hasSkr) {
                    val match = Regex("[a-zA-Z0-9._-]+\\.skr").find(asStr)
                    val name = match?.value ?: "unknown.skr"
                    list.add(SkrDomainInfo(pubkey = pubkey, domainName = name, owner = publicKeyBase58))
                    DevLog.d(TAG, "[SNS_$bytesKind] account[$i] ✅ added .skr: $name")
                }
            }
            DevLog.d(TAG, "[SNS_$bytesKind] EXIT: ${list.size} .skr domains")
            list
        } catch (e: Exception) {
            DevLog.e(TAG, "[SNS_$bytesKind] query failed", e)
            null
        }
    }
    
    /**
     * METHOD 1a: memcmp с base64 от 32 байт владельца (наиболее надёжный вариант для многих RPC).
     */
    private suspend fun queryStandardAnsDomainsWithBase64Memcmp(ownerPubkey: String): List<SkrDomainInfo> {
        return try {
            DevLog.d(TAG, "[M1_B64] queryStandardAnsDomainsWithBase64Memcmp ENTRY")
            val ownerBytes32 = try {
                ownerPubkey.decodeBase58()
            } catch (e: Exception) {
                DevLog.e(TAG, "[M1_B64] ❌ decodeBase58 failed", e)
                return emptyList()
            }
            if (ownerBytes32.size != 32) {
                DevLog.e(TAG, "[M1_B64] ❌ ownerBytes32.size=${ownerBytes32.size} expected 32")
                return emptyList()
            }
            val ownerBase64 = Base64.encodeToString(ownerBytes32, Base64.NO_WRAP)
            DevLog.d(TAG, "[M1_B64] ownerBase64 length=${ownerBase64.length} preview=${ownerBase64.take(24)}...")
            val requestBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getProgramAccounts")
                put("params", JSONArray().apply {
                    put(ANS_PROGRAM_ID)
                    put(JSONObject().apply {
                        put("encoding", "base64")
                        put("commitment", "confirmed")
                        put("filters", JSONArray().apply {
                            put(JSONObject().apply {
                                put("memcmp", JSONObject().apply {
                                    put("offset", ANS_OWNER_OFFSET)
                                    put("bytes", ownerBase64)
                                })
                            })
                        })
                    })
                })
            }.toString()
            DevLog.d(TAG, "[M1_B64] Request bodyLen=${requestBody.length} offset=$ANS_OWNER_OFFSET bytes=base64")
            val request = Request.Builder()
                .url(rpcUrl)
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()
            val (response, responseBody) = executeRpcWithRetry(request, "M1_B64", RPC_RETRY_ANS)
            DevLog.d(TAG, "[M1_B64] Response code=${response.code} bodyLen=${responseBody.length} preview=${responseBody.take(600)}")
            if (!response.isSuccessful) return emptyList()
            val jsonResponse = JSONObject(responseBody)
            if (jsonResponse.has("error")) {
                val err = jsonResponse.getJSONObject("error")
                DevLog.e(TAG, "[M1_B64] RPC error: ${err.optString("message")} full=${err.toString().take(200)}")
                return emptyList()
            }
            val result = jsonResponse.optJSONArray("result") ?: JSONArray()
            DevLog.i(TAG, "[M1_B64] result length=${result.length()}")
            for (i in 0 until result.length()) {
                val pubkey = result.optJSONObject(i)?.optString("pubkey", "") ?: ""
                DevLog.d(TAG, "[M1_B64] result[$i] pubkey=$pubkey")
            }
            parseAnsResultToSkrDomains(result, ownerPubkey)
        } catch (e: Exception) {
            DevLog.e(TAG, "[M1_B64] ❌ Exception", e)
            emptyList()
        }
    }

    /**
     * METHOD 1b: Стандартные ANS домены через getProgramAccounts (memcmp = base58 строка).
     * AllDomains NameRecordHeader: 8 (discriminator) + 32 (parent TLD) = 40, затем owner по offset 40 (32 байта).
     */
    private suspend fun queryStandardAnsDomains(ownerPubkey: String): List<SkrDomainInfo> {
        return try {
            DevLog.d(TAG, "[M1_B58] queryStandardAnsDomains ENTRY")
            DevLog.d(TAG, "[M1_B58] ownerPubkey=$ownerPubkey length=${ownerPubkey.length}")

            val ownerBytes32 = try {
                ownerPubkey.decodeBase58()
            } catch (e: Exception) {
                DevLog.e(TAG, "[M1_B58] ❌ decodeBase58 failed", e)
                return emptyList()
            }
            DevLog.d(TAG, "[M1_B58] ownerBytes32.length=${ownerBytes32.size} hex(8)=${ownerBytes32.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }}...")
            if (ownerBytes32.size != 32) {
                DevLog.e(TAG, "[M1_B58] ❌ expected 32 bytes got ${ownerBytes32.size}")
                return emptyList()
            }

            val requestBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getProgramAccounts")
                put("params", JSONArray().apply {
                    put(ANS_PROGRAM_ID)
                    put(JSONObject().apply {
                        put("encoding", "base64")
                        put("commitment", "confirmed")
                        put("filters", JSONArray().apply {
                            put(JSONObject().apply {
                                put("memcmp", JSONObject().apply {
                                    put("offset", ANS_OWNER_OFFSET)
                                    put("bytes", ownerPubkey)
                                })
                            })
                        })
                    })
                })
            }.toString()
            
            DevLog.d(TAG, "[M1_B58] Request: method=getProgramAccounts program=$ANS_PROGRAM_ID offset=$ANS_OWNER_OFFSET bytes=base58(len=${ownerPubkey.length}) bodyLen=${requestBody.length}")
            
            val request = Request.Builder()
                .url(rpcUrl)
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()

            val (response, responseBody) = executeRpcWithRetry(request, "M1_B58", RPC_RETRY_ANS)
            
            DevLog.d(TAG, "[M1_B58] Response: code=${response.code} bodyLen=${responseBody.length}")
            DevLog.d(TAG, "[M1_B58] Response body (first 800): ${responseBody.take(800)}")
            if (responseBody.length > 800) DevLog.d(TAG, "[M1_B58] ... (truncated)")
            
            if (!response.isSuccessful) {
                DevLog.e(TAG, "[M1_B58] ❌ HTTP failed: ${response.code}")
                return emptyList()
            }
            
            val jsonResponse = JSONObject(responseBody)
            if (jsonResponse.has("error")) {
                val error = jsonResponse.getJSONObject("error")
                val code = error.optInt("code", -1)
                val msg = error.optString("message", "")
                DevLog.e(TAG, "[M1_B58] ❌ RPC error: code=$code message=$msg fullError=${error.toString().take(300)}")
                return emptyList()
            }
            
            val result = jsonResponse.optJSONArray("result") ?: JSONArray()
            DevLog.i(TAG, "[M1_B58] result array length=${result.length()}")
            for (i in 0 until result.length()) {
                val acc = result.optJSONObject(i)
                val pubkey = acc?.optString("pubkey", "") ?: ""
                DevLog.d(TAG, "[M1_B58] result[$i] pubkey=$pubkey")
            }
            if (result.length() == 0) {
                DevLog.w(TAG, "[M1_B58] ⚠️ 0 accounts returned (owner may not match or domain wrapped)")
            }
            parseAnsResultToSkrDomains(result, ownerPubkey)
            
        } catch (e: Exception) {
            DevLog.e(TAG, "[M1_B58] ❌ Exception", e)
            emptyList()
        }
    }
    
    /**
     * Fallback: memcmp.bytes = [1,2,3,...] (JSON array байтов, если base58 не сработал)
     */
    private suspend fun queryStandardAnsDomainsWithBase58Memcmp(ownerPubkey: String): List<SkrDomainInfo> {
        return try {
            DevLog.d(TAG, "[M1_ARR] queryStandardAnsDomainsWithBase58Memcmp ENTRY")
            val ownerBytes32 = ownerPubkey.decodeBase58()
            DevLog.d(TAG, "[M1_ARR] ownerBytes32.length=${ownerBytes32.size} first4=[${ownerBytes32.take(4).joinToString(",") { (it.toInt() and 0xFF).toString() }}]")
            val ownerBytesArray = JSONArray()
            ownerBytes32.forEach { byte -> ownerBytesArray.put(byte.toInt() and 0xFF) }
            val requestBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getProgramAccounts")
                put("params", JSONArray().apply {
                    put(ANS_PROGRAM_ID)
                    put(JSONObject().apply {
                        put("encoding", "base64")
                        put("commitment", "confirmed")
                        put("filters", JSONArray().apply {
                            put(JSONObject().apply {
                                put("memcmp", JSONObject().apply {
                                    put("offset", ANS_OWNER_OFFSET)
                                    put("bytes", ownerBytesArray)
                                })
                            })
                        })
                    })
                })
            }.toString()
            DevLog.d(TAG, "[M1_ARR] Request bodyLen=${requestBody.length} offset=$ANS_OWNER_OFFSET bytes=JSON array[32]")
            val request = Request.Builder()
                .url(rpcUrl)
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"
            DevLog.d(TAG, "[M1_ARR] Response code=${response.code} bodyLen=${responseBody.length} preview=${responseBody.take(500)}")
            if (!response.isSuccessful) return emptyList()
            val jsonResponse = JSONObject(responseBody)
            if (jsonResponse.has("error")) {
                val error = jsonResponse.getJSONObject("error")
                DevLog.e(TAG, "[M1_ARR] RPC error: ${error.optString("message")}")
                return emptyList()
            }
            val result = jsonResponse.optJSONArray("result") ?: JSONArray()
            DevLog.i(TAG, "[M1_ARR] result length=${result.length()}")
            for (i in 0 until result.length()) {
                DevLog.d(TAG, "[M1_ARR] result[$i] pubkey=${result.optJSONObject(i)?.optString("pubkey", "")}")
            }
            parseAnsResultToSkrDomains(result, ownerPubkey)
        } catch (e: Exception) {
            DevLog.e(TAG, "[M1_ARR] ❌ Exception", e)
            emptyList()
        }
    }
    
    /**
     * METHOD 2: Wrapped NFT домены через Name House
     * 
     * Если домен обёрнут в NFT через Name House, он хранится как SPL Token.
     * Ищем Token Accounts владельца, где mint = Name House NFT для .skr доменов.
     * 
     * STRATEGY:
     * 1. getTokenAccountsByOwner с programId = TOKEN_PROGRAM_ID
     * 2. Фильтруем по mint (если знаем mint .skr доменов) ИЛИ
     * 3. Проходим все токены и проверяем metadata через Metaplex
     * 
     * Упрощённая версия: ищем все токены владельца с amount=1 (NFT признак),
     * затем через RPC getAccountInfo для каждого mint проверяем metadata.
     */
    private suspend fun queryWrappedAnsDomains(ownerPubkey: String): List<SkrDomainInfo> {
        return try {
            DevLog.d(TAG, "[M2] queryWrappedAnsDomains ENTRY")
            DevLog.d(TAG, "[M2] ownerPubkey=${ownerPubkey.take(12)}...${ownerPubkey.takeLast(8)}")
            val tokenProgramId = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
            val requestBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getTokenAccountsByOwner")
                put("params", JSONArray().apply {
                    put(ownerPubkey)
                    put(JSONObject().apply { put("programId", tokenProgramId) })
                    put(JSONObject().apply {
                        put("encoding", "jsonParsed")
                        put("commitment", "confirmed")
                    })
                })
            }.toString()
            DevLog.d(TAG, "[M2] Request: getTokenAccountsByOwner owner=$ownerPubkey bodyLen=${requestBody.length}")
            val request = Request.Builder()
                .url(rpcUrl)
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"
            DevLog.d(TAG, "[M2] Response code=${response.code} bodyLen=${responseBody.length} preview=${responseBody.take(400)}")
            if (!response.isSuccessful) {
                DevLog.e(TAG, "[M2] ❌ HTTP failed: ${response.code}")
                return emptyList()
            }
            val jsonResponse = JSONObject(responseBody)
            if (jsonResponse.has("error")) {
                val error = jsonResponse.getJSONObject("error")
                DevLog.e(TAG, "[M2] RPC error: ${error.optString("message")}")
                return emptyList()
            }
            val result = jsonResponse.optJSONObject("result")
            val tokenAccounts = result?.optJSONArray("value") ?: JSONArray()
            DevLog.i(TAG, "[M2] token accounts count=${tokenAccounts.length()}")
            val skrDomains = mutableListOf<SkrDomainInfo>()
            var nftCount = 0
            var getAssetCalls = 0
            val maxGetAssetForWrapped = 5
            for (i in 0 until tokenAccounts.length()) {
                if (getAssetCalls >= maxGetAssetForWrapped) break
                val tokenAccount = tokenAccounts.getJSONObject(i)
                val accountData = tokenAccount.optJSONObject("account")?.optJSONObject("data")
                val parsed = accountData?.optJSONObject("parsed")
                val info = parsed?.optJSONObject("info")
                if (info == null) {
                    DevLog.d(TAG, "[M2] token[$i] no parsed info keys=${accountData?.keys()?.asSequence()?.toList()?.joinToString() ?: "null"}")
                    continue
                }
                val tokenAmount = info.optJSONObject("tokenAmount")
                val amount = tokenAmount?.optString("amount", "?") ?: "?"
                val decimals = tokenAmount?.optInt("decimals", -1)
                val mint = info.optString("mint", "")
                DevLog.d(TAG, "[M2] token[$i] mint=$mint amount=$amount decimals=$decimals")
                val isNft = amount == "1" && decimals == 0 && mint.isNotEmpty()
                if (isNft) nftCount++
                if (mint.isNotEmpty() && getAssetCalls < maxGetAssetForWrapped) {
                    getAssetCalls++
                    DevLog.d(TAG, "[M2] token[$i] querying Metaplex metadata for mint=$mint ($getAssetCalls/$maxGetAssetForWrapped)...")
                    val domainName = queryMetaplexMetadata(mint)
                    DevLog.d(TAG, "[M2] token[$i] metadata name='$domainName' endsWith(.skr)=${domainName.endsWith(".skr")}")
                    if (domainName.isNotEmpty() && domainName.endsWith(".skr")) {
                        DevLog.i(TAG, "[M2] token[$i] ✅ .skr: $domainName")
                        skrDomains.add(SkrDomainInfo(pubkey = mint, domainName = domainName, owner = ownerPubkey))
                    }
                }
            }
            DevLog.i(TAG, "[M2] EXIT: NFTs=$nftCount getAssetCalls=$getAssetCalls .skr domains=${skrDomains.size}")
            skrDomains
        } catch (e: Exception) {
            DevLog.e(TAG, "[M2] ❌ Exception", e)
            emptyList()
        }
    }
    
    /**
     * Получает имя домена из Metaplex metadata для NFT mint
     * 
     * Metaplex metadata PDA = ["metadata", METAPLEX_PROGRAM_ID, mint]
     * Metadata structure содержит поле name с именем NFT (например, "faa.skr")
     */
    private suspend fun queryMetaplexMetadata(mint: String): String {
        return try {
            DevLog.d(TAG, "[META] queryMetaplexMetadata ENTRY mint=$mint")
            val requestBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getAsset")
                put("params", JSONObject().apply { put("id", mint) })
            }.toString()
            val request = Request.Builder()
                .url(rpcUrl)
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"
            DevLog.d(TAG, "[META] getAsset response code=${response.code} bodyLen=${responseBody.length}")
            if (!response.isSuccessful) {
                DevLog.w(TAG, "[META] getAsset HTTP failed: ${response.code}")
                return ""
            }
            val jsonResponse = JSONObject(responseBody)
            if (jsonResponse.has("error")) {
                DevLog.w(TAG, "[META] getAsset RPC error: ${jsonResponse.getJSONObject("error").optString("message")}")
                return ""
            }
            val result = jsonResponse.optJSONObject("result")
            if (result == null) {
                DevLog.d(TAG, "[META] getAsset result=null")
                return ""
            }
            val content = result.optJSONObject("content")
            val metadata = content?.optJSONObject("metadata")
            var name = metadata?.optString("name", "") ?: ""
            if (name.isEmpty()) name = metadata?.optString("title", "") ?: ""
            if (name.isEmpty() && content != null) name = content.optString("name", "") ?: content.optString("title", "") ?: ""
            DevLog.d(TAG, "[META] EXIT mint=$mint name='$name' contentNull=${content == null} metadataNull=${metadata == null}")
            name
        } catch (e: Exception) {
            DevLog.w(TAG, "[META] Exception: ${e.message}", e)
            ""
        }
    }
    
    /**
     * RPC getAccountInfo: returns account data as base64 or null.
     */
    private suspend fun getAccountInfoBase64(pubkeyBase58: String): String? = withContext(Dispatchers.IO) {
        try {
            DevLog.d(TAG, "getAccountInfoBase64: request for pubkey=$pubkeyBase58")
            val requestBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getAccountInfo")
                put("params", JSONArray().apply {
                    put(pubkeyBase58)
                    put(JSONObject().apply {
                        put("encoding", "base64")
                        put("commitment", "confirmed")
                    })
                })
            }.toString()
            val request = Request.Builder()
                .url(rpcUrl)
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"
            DevLog.d(TAG, "getAccountInfoBase64: response code=${response.code}, bodyLength=${responseBody.length}")
            if (!response.isSuccessful) {
                DevLog.w(TAG, "getAccountInfoBase64: ❌ HTTP not successful, returning null")
                return@withContext null
            }
            val json = JSONObject(responseBody)
            if (json.has("error")) {
                DevLog.w(TAG, "getAccountInfoBase64: ❌ RPC error: ${json.optJSONObject("error")?.toString()?.take(200)}")
                return@withContext null
            }
            val result = json.optJSONObject("result")
            if (result == null) {
                DevLog.w(TAG, "getAccountInfoBase64: ❌ result is null (account may not exist)")
                return@withContext null
            }
            val value = result.optJSONObject("value")
            if (value == null) {
                DevLog.w(TAG, "getAccountInfoBase64: ❌ result.value is null (account closed or not found)")
                return@withContext null
            }
            val data = value.get("data")
            val base64 = when (data) {
                is String -> data
                is JSONArray -> if (data.length() > 0) data.getString(0) else null
                else -> null
            }
            if (base64 == null) {
                DevLog.w(TAG, "getAccountInfoBase64: ❌ data field null or unsupported type")
            } else {
                DevLog.d(TAG, "getAccountInfoBase64: ✅ ok, base64Length=${base64.length}")
            }
            base64
        } catch (e: Exception) {
            DevLog.w(TAG, "getAccountInfoBase64: failed for $pubkeyBase58: ${e.message}", e)
            null
        }
    }

    /**
     * Reverse lookup domain name for a .skr name account (per @onsol/tldparser).
     * Computes reverse-lookup PDA, fetches account, reads domain from data after header (200 bytes).
     */
    private suspend fun performReverseLookupForSkr(nameAccountPubkeyBase58: String): String? = withContext(Dispatchers.IO) {
        try {
            DevLog.d(TAG, "reverseLookup: START for nameAccount=$nameAccountPubkeyBase58")
            val tldHouseBytes = SolanaPda.findTldHouse(".skr")
            if (tldHouseBytes == null) {
                DevLog.w(TAG, "reverseLookup: ❌ TLD House for .skr not found")
                return@withContext null
            }
            DevLog.d(TAG, "reverseLookup: TLD House PDA ok (32 bytes)")
            val pdaBytes = SolanaPda.getReverseLookupPda(nameAccountPubkeyBase58, tldHouseBytes)
            if (pdaBytes == null) {
                DevLog.w(TAG, "reverseLookup: ❌ Reverse lookup PDA for $nameAccountPubkeyBase58 not found")
                return@withContext null
            }
            val pdaBase58 = Base58.encode(pdaBytes)
            DevLog.d(TAG, "reverseLookup: fetching account $pdaBase58 for name $nameAccountPubkeyBase58")
            val base64Data = getAccountInfoBase64(pdaBase58)
            if (base64Data == null) {
                DevLog.w(TAG, "reverseLookup: ❌ getAccountInfoBase64 returned null (account missing or RPC error)")
                return@withContext null
            }
            DevLog.d(TAG, "reverseLookup: got account data, base64Length=${base64Data.length}")
            val decoded = Base64.decode(base64Data, Base64.NO_WRAP)
            DevLog.d(TAG, "reverseLookup: decoded size=${decoded.size}, headerSize=$NAME_RECORD_HEADER_BYTE_SIZE")
            if (decoded.size <= NAME_RECORD_HEADER_BYTE_SIZE) {
                DevLog.w(TAG, "reverseLookup: ❌ account data too small (${decoded.size}), need > $NAME_RECORD_HEADER_BYTE_SIZE")
                return@withContext null
            }
            val nameBytes = decoded.copyOfRange(NAME_RECORD_HEADER_BYTE_SIZE, decoded.size)
            var end = 0
            while (end < nameBytes.size && nameBytes[end].toInt() != 0) end++
            DevLog.d(TAG, "reverseLookup: nameBytes length=${nameBytes.size}, nullTerminator at end=$end")
            if (end > 0) {
                val rawPart = nameBytes.copyOf(end)
                val hexPreview = rawPart.take(64).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                DevLog.d(TAG, "reverseLookup: raw name bytes (hex first 64)= $hexPreview")
            }
            // If no null terminator, use whole tail as string (per tldparser .toString() on subarray)
            val effectiveEnd = if (end > 0) end else nameBytes.size
            val domainPart = String(nameBytes.copyOf(effectiveEnd), Charsets.UTF_8).trim()
            DevLog.d(TAG, "reverseLookup: effectiveEnd=$effectiveEnd domainPartLen=${domainPart.length}")
            DevLog.d(TAG, "reverseLookup: domainPart='$domainPart' (length=${domainPart.length})")
            if (domainPart.isEmpty()) {
                DevLog.w(TAG, "reverseLookup: ❌ domainPart is empty after trim")
                return@withContext null
            }
            val fullDomain = if (domainPart.endsWith(".skr")) domainPart else "$domainPart.skr"
            val regex = Regex("^[a-zA-Z0-9._-]+\\.skr$")
            val matches = fullDomain.matches(regex)
            DevLog.d(TAG, "reverseLookup: fullDomain='$fullDomain', regexMatch=$matches")
            if (matches) {
                DevLog.i(TAG, "   ✅ Reverse lookup .skr domain: $fullDomain")
                fullDomain
            } else {
                DevLog.w(TAG, "reverseLookup: ❌ fullDomain did not match .skr regex")
                null
            }
        } catch (e: Exception) {
            DevLog.w(TAG, "reverseLookup: ❌ exception for $nameAccountPubkeyBase58: ${e.message}", e)
            null
        }
    }

    /**
     * Парсит JSON result getProgramAccounts в список .skr доменов.
     * Если parseAnsDomainName не извлёк имя (например DHX-аккаунт), пробует reverse lookup (@onsol/tldparser).
     */
    private suspend fun parseAnsResultToSkrDomains(result: JSONArray, ownerPubkey: String): List<SkrDomainInfo> = withContext(Dispatchers.IO) {
        val skrDomains = mutableListOf<SkrDomainInfo>()
        DevLog.d(TAG, "[PARSE_ANS] parseAnsResultToSkrDomains ENTRY")
        DevLog.d(TAG, "[PARSE_ANS] result.length=${result.length()} ownerPubkey=${ownerPubkey.take(12)}...")
        for (i in 0 until result.length()) {
            DevLog.d(TAG, "[PARSE_ANS] ---------- Account[$i] ----------")
            val accountEntry = result.getJSONObject(i)
            val pubkey = accountEntry.getString("pubkey")
            DevLog.d(TAG, "[PARSE_ANS] Account[$i] pubkey=$pubkey")
            val account = accountEntry.getJSONObject("account")
            val dataField = account.get("data")
            val base64Data: String = when (dataField) {
                is String -> dataField
                is JSONArray -> if (dataField.length() > 0) dataField.getString(0) else ""
                else -> ""
            }
            if (base64Data.isEmpty()) {
                DevLog.w(TAG, "[PARSE_ANS] Account[$i] data field empty or wrong type: ${dataField?.javaClass?.simpleName}")
                continue
            }
            DevLog.d(TAG, "[PARSE_ANS] Account[$i] base64DataLength=${base64Data.length} base64Preview(60)=${base64Data.take(60)}...")
            DevLog.d(TAG, "[PARSE_ANS] Account[$i] calling parseAnsDomainName...")
            var domainName = parseAnsDomainName(base64Data)
            DevLog.d(TAG, "[PARSE_ANS] Account[$i] parseAnsDomainName returned: '${domainName.take(80)}' length=${domainName.length}")
            if (domainName.isEmpty()) {
                DevLog.d(TAG, "[PARSE_ANS] Account[$i] parseAnsDomainName empty -> calling performReverseLookupForSkr(pubkey=$pubkey)...")
                domainName = performReverseLookupForSkr(pubkey) ?: ""
                DevLog.d(TAG, "[PARSE_ANS] Account[$i] performReverseLookupForSkr returned: '${domainName.take(80)}' length=${domainName.length}")
            }
            if (domainName.isNotEmpty()) {
                DevLog.d(TAG, "[PARSE_ANS] Account[$i] final domainName='$domainName' endsWith(.skr)=${domainName.endsWith(".skr")}")
                if (domainName.endsWith(".skr")) {
                    skrDomains.add(SkrDomainInfo(pubkey = pubkey, domainName = domainName, owner = ownerPubkey))
                    DevLog.i(TAG, "[PARSE_ANS] Account[$i] ✅ ADDED .skr: $domainName")
                } else {
                    DevLog.d(TAG, "[PARSE_ANS] Account[$i] skip: not .skr")
                }
            } else {
                DevLog.d(TAG, "[PARSE_ANS] Account[$i] ❌ both parseAnsDomainName and reverse lookup returned empty")
            }
        }
        DevLog.d(TAG, "[PARSE_ANS] parseAnsResultToSkrDomains EXIT: ${skrDomains.size} .skr domains")
        skrDomains
    }

    private fun isDomainChar(c: Int): Boolean {
        return (c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) ||
            (c in '0'.code..'9'.code) || c == '.'.code || c == '_'.code || c == '-'.code
    }

    /**
     * Парсит имя домена из base64 encoded ANS account data.
     *
     * AllDomains NameRecordHeader (docs.alldomains.id): parent 32, owner 32, nclass 32, expires_at 8, created_at 8, non_transferable 1 = 113 bytes;
     * затем data: Vec<u8> = 4 bytes (length LE) + UTF-8 имя. Вариант с 8-byte discriminator: header at 8, data length at 121.
     */
    private fun parseAnsDomainName(base64Data: String): String {
        return try {
            DevLog.d(TAG, "[PARSE_NAME] parseAnsDomainName ENTRY")
            DevLog.d(TAG, "[PARSE_NAME] base64Data.length=${base64Data.length}")
            val decoded = Base64.decode(base64Data, Base64.NO_WRAP)
            if (decoded.isEmpty()) {
                DevLog.w(TAG, "[PARSE_NAME] Base64 decoded to empty")
                return ""
            }
            DevLog.d(TAG, "[PARSE_NAME] decoded.size=${decoded.size} bytes")
            DevLog.d(TAG, "[PARSE_NAME] decoded hex [0..32]=${decoded.take(32).joinToString("") { "%02x".format(it.toInt() and 0xFF) }}")
            if (decoded.size < 80) {
                DevLog.w(TAG, "[PARSE_NAME] account too small (${decoded.size}), need >= 80")
                return ""
            }
            val hex0 = decoded.take(80).joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
            DevLog.d(TAG, "[PARSE_NAME] Hex [0..80)= $hex0")
            if (decoded.size > 80) {
                val hex80 = decoded.drop(80).take(80).joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
                DevLog.d(TAG, "[PARSE_NAME] Hex [80..${minOf(160, decoded.size)})= $hex80")
            }
            // First 32 bytes as ASCII (debug layout)
            val ascii0 = decoded.take(32).joinToString("") { b ->
                val c = b.toInt() and 0xFF
                if (c in 32..126) c.toChar().toString() else "."
            }
            DevLog.d(TAG, "[PARSE_NAME] first 32 bytes as ASCII: $ascii0")
            // Bytes at 32..64 (possible owner) in hex
            if (decoded.size >= 64) {
                val hex32_64 = decoded.sliceArray(32 until 64).joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
                DevLog.d(TAG, "[PARSE_NAME] Hex [32..64) (owner?)= $hex32_64")
            }

            // AllDomains NameRecordHeader: 200 bytes then data (null-term or 4-byte len + name)
            if (decoded.size > NAME_RECORD_HEADER_BYTE_SIZE + 2) {
                val dataStart = NAME_RECORD_HEADER_BYTE_SIZE
                var end = dataStart
                while (end < decoded.size && decoded[end].toInt() != 0) end++
                if (end > dataStart && end - dataStart <= 128) {
                    val name = String(decoded.sliceArray(dataStart until end), Charsets.UTF_8)
                    if (name.matches(Regex("^[a-zA-Z0-9._-]+$")) || name.matches(Regex("^[a-zA-Z0-9._-]+\\.skr$"))) {
                        DevLog.d(TAG, "[PARSE_NAME] ✅ NameRecordHeader data at offset $dataStart (null-term): $name")
                        return if (name.endsWith(".skr")) name else "$name.skr"
                    }
                }
                if (dataStart + 4 <= decoded.size) {
                    val len = (decoded[dataStart].toInt() and 0xFF) or
                        ((decoded[dataStart + 1].toInt() and 0xFF) shl 8) or
                        ((decoded[dataStart + 2].toInt() and 0xFF) shl 16) or
                        ((decoded[dataStart + 3].toInt() and 0xFF) shl 24)
                    if (len in 1..128 && dataStart + 4 + len <= decoded.size) {
                        val name = String(decoded.sliceArray(dataStart + 4 until dataStart + 4 + len), Charsets.UTF_8)
                        if (name.matches(Regex("^[a-zA-Z0-9._-]+$")) || name.matches(Regex("^[a-zA-Z0-9._-]+\\.skr$"))) {
                            DevLog.d(TAG, "[PARSE_NAME] ✅ NameRecordHeader data at $dataStart (u32 LE len=$len): $name")
                            return if (name.endsWith(".skr")) name else "$name.skr"
                        }
                    }
                }
            }

            // AllDomains с префиксом "DHX,": 4 (DHX,) + 32 parent + 32 owner + 32 class = 100, затем длина+имя
            val hasDhxPrefix = decoded.size >= 4 &&
                decoded[0].toInt() and 0xFF == 'D'.code &&
                decoded[1].toInt() and 0xFF == 'H'.code &&
                decoded[2].toInt() and 0xFF == 'X'.code &&
                decoded[3].toInt() and 0xFF == ','.code
            if (hasDhxPrefix) {
                DevLog.d(TAG, "[PARSE_NAME] detected DHX prefix — trying DHX layout (data at 100, 104, 72)")
                for (lenOffset in listOf(100, 72, 104)) {
                    if (lenOffset + 4 > decoded.size) continue
                    val len = (decoded[lenOffset].toInt() and 0xFF) or
                        ((decoded[lenOffset + 1].toInt() and 0xFF) shl 8) or
                        ((decoded[lenOffset + 2].toInt() and 0xFF) shl 16) or
                        ((decoded[lenOffset + 3].toInt() and 0xFF) shl 24)
                    if (len in 1..128) {
                        val nameStart = lenOffset + 4
                        if (nameStart + len <= decoded.size) {
                            val name = String(decoded.sliceArray(nameStart until nameStart + len), Charsets.UTF_8)
                            if (name.matches(Regex("^[a-zA-Z0-9._-]+\\.skr$"))) {
                                DevLog.d(TAG, "[PARSE_NAME] ✅ DHX layout at lenOffset=$lenOffset: $name")
                                return name
                            }
                            DevLog.d(TAG, "[PARSE_NAME] DHX lenOffset=$lenOffset len=$len name=$name")
                        }
                    }
                }
                // DHX: имя может идти null-terminated после заголовка (например с 104)
                for (dataStart in listOf(104, 100, 72)) {
                    if (dataStart >= decoded.size) continue
                    var end = dataStart
                    while (end < decoded.size && decoded[end].toInt() != 0) end++
                    if (end > dataStart && end - dataStart <= 128) {
                        val name = String(decoded.sliceArray(dataStart until end), Charsets.UTF_8)
                        if (name.matches(Regex("^[a-zA-Z0-9._-]+\\.skr$"))) {
                            DevLog.d(TAG, "[PARSE_NAME] ✅ DHX null-term at $dataStart: $name")
                            return name
                        }
                    }
                }
            }

            val possibleOffsets = listOf(NAME_RECORD_HEADER_BYTE_SIZE, 113, 121, 96, 100, 104)
            for (offset in possibleOffsets) {
                if (offset + 4 > decoded.size) continue
                val nameLength4Le = (decoded[offset].toInt() and 0xFF) or
                    ((decoded[offset + 1].toInt() and 0xFF) shl 8) or
                    ((decoded[offset + 2].toInt() and 0xFF) shl 16) or
                    ((decoded[offset + 3].toInt() and 0xFF) shl 24)
                val nameLength4Be = (decoded[offset].toInt() and 0xFF) shl 24 or
                    ((decoded[offset + 1].toInt() and 0xFF) shl 16) or
                    ((decoded[offset + 2].toInt() and 0xFF) shl 8) or
                    (decoded[offset + 3].toInt() and 0xFF)
                val nameLength2 = if (offset + 2 <= decoded.size) {
                    (decoded[offset].toInt() and 0xFF) or ((decoded[offset + 1].toInt() and 0xFF) shl 8)
                } else -1
                DevLog.d(TAG, "[PARSE_NAME] offset=$offset u32LE=$nameLength4Le u32BE=$nameLength4Be u16=$nameLength2")
                if (nameLength4Le in 1..256) {
                    val nameStart = offset + 4
                    val nameEnd = nameStart + nameLength4Le
                    if (nameEnd <= decoded.size) {
                        val domainName = String(decoded.sliceArray(nameStart until nameEnd), Charsets.UTF_8)
                        if (domainName.matches(Regex("^[a-zA-Z0-9._-]+$"))) {
                            DevLog.d(TAG, "[PARSE_NAME] ✅ valid (u32 LE) at $offset: $domainName")
                            return domainName
                        }
                        DevLog.d(TAG, "[PARSE_NAME] u32 LE at $offset name not domain regex: ${domainName.take(50)}")
                    } else DevLog.d(TAG, "[PARSE_NAME] u32 LE at $offset nameEnd=$nameEnd > size=${decoded.size}")
                }
                if (nameLength4Be in 1..256) {
                    val nameStart = offset + 4
                    val nameEnd = nameStart + nameLength4Be
                    if (nameEnd <= decoded.size) {
                        val domainName = String(decoded.sliceArray(nameStart until nameEnd), Charsets.UTF_8)
                        if (domainName.matches(Regex("^[a-zA-Z0-9._-]+$"))) {
                            DevLog.d(TAG, "[PARSE_NAME] ✅ valid (u32 BE) at $offset: $domainName")
                            return domainName
                        }
                    }
                }
                if (nameLength2 in 1..256) {
                    val nameStart = offset + 2
                    val nameEnd = nameStart + nameLength2
                    if (nameEnd <= decoded.size) {
                        val domainName = String(decoded.sliceArray(nameStart until nameEnd), Charsets.UTF_8)
                        if (domainName.matches(Regex("^[a-zA-Z0-9._-]+$"))) {
                            DevLog.d(TAG, "[PARSE_NAME] ✅ valid (u16) at $offset: $domainName")
                            return domainName
                        }
                    }
                }
            }
            for (dataStart in listOf(NAME_RECORD_HEADER_BYTE_SIZE, 113, 121, 117, 72, 100, 104)) {
                if (dataStart >= decoded.size) continue
                var end = dataStart
                while (end < decoded.size && decoded[end].toInt() != 0) end++
                if (end > dataStart && end - dataStart <= 256) {
                    val domainName = String(decoded.sliceArray(dataStart until end), Charsets.UTF_8)
                    if (domainName.matches(Regex("^[a-zA-Z0-9._-]+$"))) {
                        DevLog.d(TAG, "[PARSE_NAME] ✅ valid (null-term) at $dataStart: $domainName")
                        return domainName
                    }
                }
            }
            for (dataStart in listOf(NAME_RECORD_HEADER_BYTE_SIZE, 72, 100, 104, 113, 121)) {
                if (dataStart >= decoded.size) continue
                val rest = decoded.sliceArray(dataStart until decoded.size)
                val asStr = String(rest, Charsets.UTF_8)
                val trimmed = asStr.replace('\u0000', ' ').trim().takeWhile { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
                DevLog.d(TAG, "[PARSE_NAME] rest-of-data start=$dataStart trimmed(100)=${trimmed.take(100)}")
                if (trimmed.endsWith(".skr") && trimmed.matches(Regex("^[a-zA-Z0-9._-]+\\.skr$"))) {
                    DevLog.d(TAG, "[PARSE_NAME] ✅ valid (rest-of-data): $trimmed")
                    return trimmed
                }
            }
            val skrSuffix = ".skr".toByteArray(Charsets.UTF_8)
            var idx = 0
            var skrFoundAt = -1
            while (idx <= decoded.size - skrSuffix.size) {
                if (decoded[idx] == skrSuffix[0] &&
                    decoded.getOrNull(idx + 1) == skrSuffix[1] &&
                    decoded.getOrNull(idx + 2) == skrSuffix[2] &&
                    decoded.getOrNull(idx + 3) == skrSuffix[3]) {
                    if (skrFoundAt < 0) skrFoundAt = idx
                    var start = idx
                    while (start > 0 && isDomainChar(decoded[start - 1].toInt())) start--
                    val nameBytes = decoded.sliceArray(start until idx + skrSuffix.size)
                    val domainName = String(nameBytes, Charsets.UTF_8)
                    if (domainName.matches(Regex("^[a-zA-Z0-9._-]+\\.skr$"))) {
                        DevLog.d(TAG, "[PARSE_NAME] ✅ .skr scan at idx=$idx: $domainName")
                        return domainName
                    }
                    val cleaned = nameBytes.filter { (it.toInt() and 0xFF) in 32..126 }
                    if (cleaned.isNotEmpty()) {
                        val domainCleaned = String(cleaned.toByteArray(), Charsets.UTF_8)
                        if (domainCleaned.matches(Regex("^[a-zA-Z0-9._-]+\\.skr$"))) {
                            DevLog.d(TAG, "[PARSE_NAME] ✅ .skr scan (cleaned) at $idx: $domainCleaned")
                            return domainCleaned
                        }
                    }
                }
                idx++
            }
            DevLog.d(TAG, "[PARSE_NAME] .skr byte scan: found '.skr' at index=$skrFoundAt (total bytes=${decoded.size})")
            val fullStr = String(decoded, Charsets.UTF_8)
            val fullStrPreview = fullStr.take(250).map { c -> if (c.code in 32..126) c else '.' }.joinToString("")
            DevLog.d(TAG, "[PARSE_NAME] full blob as UTF-8 (first 250 chars, non-printable=.): $fullStrPreview")
            val skrMatch = Regex("[a-zA-Z0-9._-]+\\.skr").find(fullStr)
            if (skrMatch != null) {
                val candidate = skrMatch.value
                DevLog.d(TAG, "[PARSE_NAME] regex found candidate: $candidate length=${candidate.length}")
                if (candidate.length in 5..128) {
                    DevLog.d(TAG, "[PARSE_NAME] ✅ full string scan: $candidate")
                    return candidate
                }
                DevLog.d(TAG, "[PARSE_NAME] candidate length out of range 5..128")
            } else {
                DevLog.d(TAG, "[PARSE_NAME] no [a-zA-Z0-9._-]+\\.skr match in full string")
            }
            DevLog.w(TAG, "[PARSE_NAME] ❌ all strategies failed (size=${decoded.size})")
            ""
        } catch (e: Exception) {
            DevLog.e(TAG, "[PARSE_NAME] exception", e)
            ""
        }
    }

    /**
     * Получить последний blockhash для сборки транзакции (RPC getLatestBlockhash).
     */
    suspend fun getLatestBlockhash(): String? = withContext(Dispatchers.IO) {
        DevLog.d(TAG, "[Sleeper] getLatestBlockhash ENTRY")
        try {
            val body = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getLatestBlockhash")
                put("params", JSONArray().apply {
                    put(JSONObject().apply {
                        put("commitment", "confirmed")
                    })
                })
            }.toString()
            val request = Request.Builder()
                .url(rpcUrl)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val response = client.newCall(request).execute()
            val json = response.body?.string()?.let { JSONObject(it) } ?: return@withContext null
            if (json.has("error")) return@withContext null
            val result = json.optJSONObject("result") ?: run {
                DevLog.w(TAG, "[Sleeper] getLatestBlockhash no result in response")
                return@withContext null
            }
            val value = result.optJSONObject("value") ?: run {
                DevLog.w(TAG, "[Sleeper] getLatestBlockhash no value")
                return@withContext null
            }
            val blockhash = value.optString("blockhash", null).takeIf { it.isNotEmpty() }
            DevLog.d(TAG, "[Sleeper] getLatestBlockhash EXIT success len=${blockhash?.length ?: 0}")
            blockhash
        } catch (e: Exception) {
            DevLog.e(TAG, "[Sleeper] getLatestBlockhash EXIT failed: ${e.message} cause=${e.cause?.message}", e)
            null
        }
    }
}

/**
 * .skr Domain информация из AllDomains ANS
 */
data class SkrDomainInfo(
    val pubkey: String,       // PDA адрес domain account
    val domainName: String,   // например, "maxseeker.skr"
    val owner: String         // wallet владельца (base58)
)

/**
 * Сумма застейканных SKR по кошельку (Solana Mobile Guardian).
 */
data class StakedBalance(
    val rawAmount: Long,
    val humanReadable: Double
)

/**
 * Solana cluster endpoints
 */
enum class SolanaCluster(val rpcUrl: String) {
    MAINNET("https://api.mainnet-beta.solana.com"),
    DEVNET("https://api.devnet.solana.com"),
    TESTNET("https://api.testnet.solana.com"),
    
    /** Helius: URL подставляется из BuildConfig.HELIUS_API_KEY (local.properties). Без ключа используется public mainnet. */
    MAINNET_HELIUS("https://api.mainnet-beta.solana.com"),
}
