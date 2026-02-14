package com.seekerminer.app.data.network

import com.seekerminer.app.utils.DevLog
import com.seekerminer.app.utils.Base58
import com.seekerminer.app.utils.SolanaPda
import com.seekerminer.app.utils.decodeBase58

/**
 * Сборка сериализованной неподписанной Solana-транзакции с одной или несколькими
 * SPL Token Transfer инструкциями (user → destination). Для бустов: 1, 7 или 49 переводов в одной tx.
 */
object SplTransferBuilder {

    private const val TAG = "SplTransferBuilder"

    const val SKR_MINT = SolanaRpcClient.SKR_MINT
    private const val TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    private const val SPL_TRANSFER_INSTRUCTION_INDEX = 3

    /**
     * Строит неподписанную транзакцию (message + 1 placeholder signature) для подписи через MWA.
     * @param userPubkeyBytes 32 bytes владельца (fee payer и owner переводов)
     * @param destinationBase58 адрес получателя (казначейство или минт Genesis)
     * @param amountsPerTransfer список сумм в raw (6 decimals) — одна инструкция Transfer на элемент
     * @param blockhashBase58 последний blockhash с RPC
     * @return serialized transaction bytes (1 signature slot + message) или null при ошибке
     */
    fun buildUnsignedSerialized(
        userPubkeyBytes: ByteArray,
        destinationBase58: String,
        amountsPerTransfer: List<Long>,
        blockhashBase58: String
    ): ByteArray? {
        DevLog.d(TAG, "buildUnsignedSerialized ENTRY destination=${DevLog.mask(destinationBase58)} amountsCount=${amountsPerTransfer.size} amountsSum=${amountsPerTransfer.sum()} blockhashLen=${blockhashBase58.length}")
        if (userPubkeyBytes.size != 32 || amountsPerTransfer.isEmpty()) {
            DevLog.w(TAG, "buildUnsignedSerialized INVALID: userPubkeySize=${userPubkeyBytes.size} amountsEmpty=${amountsPerTransfer.isEmpty()}")
            return null
        }
        val blockhash = blockhashBase58.decodeBase58()
        if (blockhash.size != 32) {
            DevLog.w(TAG, "buildUnsignedSerialized blockhash decode size=${blockhash.size} expected 32")
            return null
        }
        val destBytes = destinationBase58.decodeBase58()
        if (destBytes.size != 32) {
            DevLog.w(TAG, "buildUnsignedSerialized destination decode size=${destBytes.size} expected 32")
            return null
        }
        val mintBytes = SKR_MINT.decodeBase58()
        if (mintBytes.size != 32) {
            DevLog.w(TAG, "buildUnsignedSerialized mint decode size=${mintBytes.size} expected 32")
            return null
        }

        val sourceAta = SolanaPda.getAssociatedTokenAddress(userPubkeyBytes, mintBytes) ?: run {
            DevLog.w(TAG, "buildUnsignedSerialized ATA (source) not found")
            DevLog.w(TAG, "ATA (source) not found")
            return null
        }
        val destAta = SolanaPda.getAssociatedTokenAddress(destBytes, mintBytes) ?: run {
            DevLog.w(TAG, "buildUnsignedSerialized ATA (destination) not found")
            DevLog.w(TAG, "ATA (destination) not found")
            return null
        }

        val tokenProgramBytes = TOKEN_PROGRAM_ID.decodeBase58()
        val accountKeys = listOf(
            userPubkeyBytes,      // 0: fee payer & owner
            sourceAta,            // 1: source
            destAta,              // 2: destination
            tokenProgramBytes     // 3: token program
        )

        val instructions = amountsPerTransfer.map { amount ->
            (byteArrayOf(SPL_TRANSFER_INSTRUCTION_INDEX.toByte()) + amountToLeBytes(amount)) to listOf(1, 2, 0) // source, dest, owner indices
        }

        val message = buildLegacyMessage(accountKeys, blockhash, 3, instructions) // program_id_index = 3
            ?: run {
                DevLog.w(TAG, "buildUnsignedSerialized buildLegacyMessage returned null")
                return null
            }

        val txBytes = serializeTransaction(1, message)
        DevLog.d(TAG, "buildUnsignedSerialized EXIT SUCCESS txLen=${txBytes.size}")
        return txBytes
    }

    private fun amountToLeBytes(amount: Long): ByteArray {
        val buf = ByteArray(8)
        for (i in 0..7) buf[i] = (amount shr (i * 8)).toByte()
        return buf
    }

    private fun compactU16(n: Int): ByteArray {
        require(n in 0..0xFFFF)
        return when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x4000 -> byteArrayOf((0x80 or (n and 0x7F)).toByte(), (n shr 7).toByte())
            else -> byteArrayOf(
                (0x80 or (n and 0x7F)).toByte(),
                (0x80 or ((n shr 7) and 0x7F)).toByte(),
                (n shr 14).toByte()
            )
        }
    }

    /**
     * Legacy message: header 3 bytes, account keys, blockhash 32, instructions.
     * instruction: program_id_index (1), num_accounts compact, account indices, data length compact, data.
     */
    private fun buildLegacyMessage(
        accountKeys: List<ByteArray>,
        blockhash: ByteArray,
        tokenProgramIndex: Int,
        instructions: List<Pair<ByteArray, List<Int>>>
    ): ByteArray? {
        val out = mutableListOf<Byte>()
        out.add(1)   // num_required_signatures
        out.add(0)   // num_readonly_signed
        out.add(1)   // num_readonly_unsigned (token program)
        out.addAll(compactU16(accountKeys.size).toList())
        accountKeys.forEach { key -> require(key.size == 32); out.addAll(key.toList()) }
        require(blockhash.size == 32)
        out.addAll(blockhash.toList())
        out.addAll(compactU16(instructions.size).toList())
        for ((data, accountIndices) in instructions) {
            out.add(tokenProgramIndex.toByte())
            out.addAll(compactU16(accountIndices.size).toList())
            accountIndices.forEach { out.add(it.toByte()) }
            out.addAll(compactU16(data.size).toList())
            out.addAll(data.toList())
        }
        return out.toByteArray()
    }

    /** Transaction = num_signatures (compact) + 64 bytes per signature + message. */
    private fun serializeTransaction(numSignatures: Int, message: ByteArray): ByteArray {
        val sigBytes = ByteArray(64 * numSignatures) // zeros for wallet to fill
        return compactU16(numSignatures) + sigBytes + message
    }
}
