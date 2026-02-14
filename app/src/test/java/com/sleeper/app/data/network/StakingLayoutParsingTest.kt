package com.sleeper.app.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit-тесты парсинга layout стейк-аккаунтов Guardian (SKR).
 * Проверяют оба варианта: без дискриминатора (amount @ 32) и с 8-byte discriminator (amount @ 40).
 */
class StakingLayoutParsingTest {

    @Test
    fun parseStakeAmount_layoutA_amountAt32() {
        // Layout A: owner 32 bytes, amount 8 bytes u64 LE @ offset 32
        // amount = 1_000_000 (1 SKR with 6 decimals) = 0x0F4240 in LE: 40 42 0F 00 00 00 00 00
        val data = ByteArray(48).apply {
            // owner bytes 0..31 = zeros for test
            // offset 32: u64 LE 1_000_000
            this[32] = 0x40
            this[33] = 0x42
            this[34] = 0x0F
            this[35] = 0
            this[36] = 0
            this[37] = 0
            this[38] = 0
            this[39] = 0
        }
        val amount = SolanaRpcClient.parseStakeAmountFromAccountData(data, 32)
        assertEquals(1_000_000L, amount)
    }

    @Test
    fun parseStakeAmount_layoutB_amountAt40() {
        // Layout B: 8-byte discriminator, owner 32 bytes, amount 8 bytes @ offset 40
        val data = ByteArray(56).apply {
            // offset 40: u64 LE 10_000_000 (10 SKR)
            this[40] = 0x80.toByte()
            this[41] = 0x96.toByte()
            this[42] = 0x98.toByte()
            this[43] = 0
            this[44] = 0
            this[45] = 0
            this[46] = 0
            this[47] = 0
        }
        val amount = SolanaRpcClient.parseStakeAmountFromAccountData(data, SolanaRpcClient.STAKING_ACCOUNT_AMOUNT_OFFSET_WITH_DISCRIMINATOR)
        assertEquals(10_000_000L, amount)
    }

    @Test
    fun parseStakeAmount_returnsNull_whenDataTooShort() {
        val data = ByteArray(35) // only 35 bytes, need 32+8=40 for offset 32
        assertNull(SolanaRpcClient.parseStakeAmountFromAccountData(data, 32))
    }

    @Test
    fun parseStakeAmount_zero() {
        val data = ByteArray(48) // zeros
        val amount = SolanaRpcClient.parseStakeAmountFromAccountData(data, 32)
        assertEquals(0L, amount)
    }

    @Test
    fun parseStakeAmount_largeValue_u64LE() {
        // 1_000_000_000_000 = 0xE8D4A51000 → LE bytes: 00 10 A5 D4 E8 00 00 00
        val data = ByteArray(48).apply {
            this[32] = 0
            this[33] = 0x10
            this[34] = 0xA5.toByte()
            this[35] = 0xD4.toByte()
            this[36] = 0xE8.toByte()
            this[37] = 0
            this[38] = 0
            this[39] = 0
        }
        val amount = SolanaRpcClient.parseStakeAmountFromAccountData(data, 32)
        assertEquals(1_000_000_000_000L, amount)
    }
}
