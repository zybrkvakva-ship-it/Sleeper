package com.sleeper.app.utils

import com.sleeper.app.utils.DevLog
import java.security.MessageDigest

/**
 * Solana Program Derived Address (PDA) and ANS helpers for .skr reverse lookup.
 * Matches @onsol/tldparser: getHashedName, findProgramAddress, findTldHouse.
 */
object SolanaPda {

    private const val TAG = "SolanaPda"
    private const val PDA_MARKER = "ProgramDerivedAddress"
    private const val HASH_PREFIX = "ALT Name Service"

    private val ANS_PROGRAM_ID_BYTES = "ALTNSZ46uaAUU7XUV6awvdorLGqAsPwa9shm7h4uP2FK".decodeBase58()
    private val TLD_HOUSE_PROGRAM_ID_BYTES = "TLDHkysf5pCnKsVA4gXpNvmy7psXLPEu4LAdDJthT9S".decodeBase58()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private fun ByteArray.hexPreview(maxBytes: Int = 16): String =
        copyOfRange(0, minOf(size, maxBytes)).hex() + if (size > maxBytes) "..." else ""

    /** SHA256 hash */
    fun sha256(input: ByteArray): ByteArray {
        val out = MessageDigest.getInstance("SHA-256").digest(input)
        DevLog.d(TAG, "sha256: inputLen=${input.size} outputLen=${out.size} outputHex=${out.hexPreview(8)}...")
        return out
    }

    /** ANS getHashedName: SHA256("ALT Name Service" + name) as 32 bytes (first 32 of hash). */
    fun getHashedName(name: String): ByteArray {
        val input = (HASH_PREFIX + name).toByteArray(Charsets.UTF_8)
        DevLog.d(TAG, "getHashedName: name='$name' inputLen=${input.size} inputHex(32)=${input.copyOfRange(0, minOf(32, input.size)).hex()}")
        val hash = sha256(input).copyOf(32)
        DevLog.d(TAG, "getHashedName: resultLen=${hash.size} resultHex=${hash.hex()}")
        return hash
    }

    /**
     * Returns true if the 32-byte value is on the Ed25519 curve (invalid for PDA).
     * Uses Bouncy Castle: Ed25519PublicKeyParameters throws for invalid/off-curve bytes.
     */
    fun isOnCurve(pubkey: ByteArray): Boolean {
        if (pubkey.size != 32) {
            DevLog.d(TAG, "isOnCurve: size=${pubkey.size} (expected 32) -> false")
            return false
        }
        return try {
            org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(pubkey, 0)
            DevLog.d(TAG, "isOnCurve: on-curve (invalid for PDA) pubkeyHex=${pubkey.hexPreview(8)}...")
            true
        } catch (e: Exception) {
            DevLog.d(TAG, "isOnCurve: off-curve (valid PDA) pubkeyHex=${pubkey.hexPreview(8)}...")
            false
        }
    }

    /**
     * createProgramAddress(seeds, programId). Returns PDA bytes or null if on curve (invalid).
     */
    fun createProgramAddress(seeds: List<ByteArray>, programId: ByteArray): ByteArray? {
        DevLog.d(TAG, "createProgramAddress: seedsCount=${seeds.size} programIdLen=${programId.size}")
        val out = mutableListOf<Byte>()
        for (i in seeds.indices) {
            val seed = seeds[i]
            if (seed.size > 32) {
                DevLog.w(TAG, "createProgramAddress: seed[$i] length ${seed.size} > 32 -> null")
                return null
            }
            out.addAll(seed.asList())
            DevLog.d(TAG, "createProgramAddress: seed[$i] len=${seed.size} hex=${seed.hexPreview(8)}...")
        }
        out.addAll(programId.asList())
        out.addAll(PDA_MARKER.toByteArray(Charsets.UTF_8).toList())
        val hash = sha256(out.toByteArray())
        val onCurve = isOnCurve(hash)
        DevLog.d(TAG, "createProgramAddress: totalInputLen=${out.size} hashHex=${hash.hex()} onCurve=$onCurve -> ${if (onCurve) "null" else "PDA"}")
        return if (onCurve) null else hash
    }

    /**
     * findProgramAddress: try bump 255 down to 0; return (PDA bytes, bump) or null.
     */
    fun findProgramAddress(seeds: List<ByteArray>, programId: ByteArray): Pair<ByteArray, Int>? {
        DevLog.d(TAG, "findProgramAddress: seedsCount=${seeds.size} programIdLen=${programId.size} trying bump 255..0")
        for (bump in 255 downTo 0) {
            val seedsWithBump = seeds + byteArrayOf(bump.toByte())
            val pda = createProgramAddress(seedsWithBump, programId)
            if (pda != null) {
                DevLog.d(TAG, "findProgramAddress: found at bump=$bump pdaHex=${pda.hex()} pdaBase58=${com.sleeper.app.utils.Base58.encode(pda)}")
                return Pair(pda, bump)
            }
        }
        DevLog.w(TAG, "findProgramAddress: no valid PDA found for seeds")
        return null
    }

    /** TLD House PDA for a TLD string (e.g. ".skr"). Seeds: "tld_house", tldString. */
    fun findTldHouse(tldString: String): ByteArray? {
        val tld = tldString.lowercase()
        DevLog.d(TAG, "findTldHouse: tldString='$tldString' normalized='$tld'")
        val seeds = listOf(
            "tld_house".toByteArray(Charsets.UTF_8),
            tld.toByteArray(Charsets.UTF_8)
        )
        DevLog.d(TAG, "findTldHouse: seed0='tld_house' len=${seeds[0].size} seed1='$tld' len=${seeds[1].size}")
        val result = findProgramAddress(seeds, TLD_HOUSE_PROGRAM_ID_BYTES)?.first
        DevLog.d(TAG, "findTldHouse: result=${if (result != null) "ok len=${result.size} hex=${result.hex()}" else "null"}")
        return result
    }

    /** Origin name account for ANS (parent of root TLDs). Seeds: getHashedName("ANS"). */
    fun getOriginNameAccountKey(): ByteArray? {
        DevLog.d(TAG, "getOriginNameAccountKey: computing ANS origin...")
        val hashed = getHashedName("ANS")
        val result = findProgramAddress(listOf(hashed), ANS_PROGRAM_ID_BYTES)?.first
        DevLog.d(TAG, "getOriginNameAccountKey: result=${if (result != null) "ok" else "null"}")
        return result
    }

    /**
     * Parent name account for a TLD (e.g. ".skr"). Seeds: getHashedName(".skr"), origin.
     */
    fun getParentAccountForTld(tldString: String): ByteArray? {
        DevLog.d(TAG, "getParentAccountForTld: tldString='$tldString'")
        val origin = getOriginNameAccountKey() ?: return null.also { DevLog.w(TAG, "getParentAccountForTld: origin=null") }
        val hashedTld = getHashedName(tldString)
        val result = findProgramAddress(listOf(hashedTld, origin), ANS_PROGRAM_ID_BYTES)?.first
        DevLog.d(TAG, "getParentAccountForTld: result=${if (result != null) "ok" else "null"}")
        return result
    }

    /** SPL Token Program ID. */
    private val TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA".decodeBase58()
    /** Associated Token Program ID. */
    private val ASSOCIATED_TOKEN_PROGRAM_ID = "ATAPGPF9Hb8qagD216Nujnu3d2GQumd4K1gJZJvz3VnA".decodeBase58()

    /**
     * Associated Token Account (ATA) PDA для owner + mint.
     * Seeds: owner (32), TOKEN_PROGRAM_ID (32), mint (32). Program: ASSOCIATED_TOKEN_PROGRAM_ID.
     */
    fun getAssociatedTokenAddress(ownerBytes: ByteArray, mintBytes: ByteArray): ByteArray? {
        if (ownerBytes.size != 32 || mintBytes.size != 32) return null
        val seeds = listOf(
            ownerBytes,
            TOKEN_PROGRAM_ID,
            mintBytes
        )
        return findProgramAddress(seeds, ASSOCIATED_TOKEN_PROGRAM_ID)?.first
    }

    /**
     * Reverse lookup PDA: account that stores domain name for a name account.
     * Seeds: getHashedName(nameAccountPubkeyBase58), tldHouse (32 bytes), 32 zero bytes.
     * Program: ANS.
     */
    fun getReverseLookupPda(nameAccountPubkeyBase58: String, tldHouseBytes: ByteArray): ByteArray? {
        DevLog.d(TAG, "getReverseLookupPda: nameAccountBase58='$nameAccountPubkeyBase58' len=${nameAccountPubkeyBase58.length} tldHouseBytesLen=${tldHouseBytes.size}")
        val hashedName = getHashedName(nameAccountPubkeyBase58)
        DevLog.d(TAG, "getReverseLookupPda: hashedNameHex=${hashedName.hex()}")
        val zero32 = ByteArray(32)
        val tldHouse32 = if (tldHouseBytes.size == 32) tldHouseBytes else {
            if (tldHouseBytes.size < 32) ByteArray(32).also { System.arraycopy(tldHouseBytes, 0, it, 32 - tldHouseBytes.size, tldHouseBytes.size) }
            else tldHouseBytes.copyOf(32)
        }
        DevLog.d(TAG, "getReverseLookupPda: tldHouse32Hex=${tldHouse32.hex()} zero32 ok")
        val seeds = listOf(hashedName, tldHouse32, zero32)
        val result = findProgramAddress(seeds, ANS_PROGRAM_ID_BYTES)?.first
        DevLog.d(TAG, "getReverseLookupPda: result=${if (result != null) "ok base58=${com.sleeper.app.utils.Base58.encode(result)}" else "null"}")
        return result
    }
}
