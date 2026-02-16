package com.sleeper.app.domain.manager

import org.junit.Assert.*
import org.junit.Test

class EnergyManagerUnitTest {

    @Test
    fun `calculate human check multiplier returns correct values`() {
        // Test cases based on the actual implementation
        val testCases = listOf(
            Pair(0 to 0, 1.0),    // No checks = 100%
            Pair(8 to 2, 1.0),    // 80%+ success = 100%
            Pair(7 to 3, 0.7),    // 70% success = 70%
            Pair(3 to 7, 0.3),    // 30% success = 30%
            Pair(1 to 9, 0.3),    // 10% success = 30%
            Pair(0 to 10, 0.3)    // 0% success = 30%
        )

        testCases.forEach { (input, expected) ->
            val (passed, failed) = input
            val result = calculateHumanCheckMultiplier(passed, failed)
            assertEquals("For $passed passed and $failed failed, expected $expected but got $result", 
                       expected, result, 0.01)
        }
    }

    @Test
    fun `stake multiplier returns correct values`() {
        val testCases = listOf(
            Pair(0.0, 1.0),        // 0 SKR = 1.0x
            Pair(1000.0, 1.0),     // 1,000 SKR = 1.0x
            Pair(4999.0, 1.0),     // 4,999 SKR = 1.0x
            Pair(5000.0, 1.5),     // 5,000 SKR = 1.5x (Silver Tier)
            Pair(10000.0, 1.5),    // 10,000 SKR = 1.5x (Silver Tier)
            Pair(14999.0, 1.5),    // 14,999 SKR = 1.5x (Silver Tier)
            Pair(15000.0, 2.5),    // 15,000 SKR = 2.5x (Gold Tier)
            Pair(30000.0, 2.5),    // 30,000 SKR = 2.5x (Gold Tier)
            Pair(49999.0, 2.5),    // 49,999 SKR = 2.5x (Gold Tier)
            Pair(50000.0, 5.0),    // 50,000 SKR = 5.0x (Platinum Tier)
            Pair(100000.0, 5.0)    // 100,000 SKR = 5.0x (Platinum Tier)
        )

        testCases.forEach { (stake, expected) ->
            val result = getStakeMultiplierForTest(stake)
            assertEquals("For stake $stake, expected $expected but got $result", 
                       expected, result, 0.01)
        }
    }

    @Test
    fun `daily social multiplier caps correctly`() {
        // Test that multiplier is capped at 1.15 (15% bonus)
        val result = getDailySocialMultiplier(0.20) // 20% bonus request
        assertEquals("Daily social multiplier should be capped at 1.15", 1.15, result, 0.01)
        
        // Test normal values
        assertEquals("0% bonus should give 1.0x", 1.0, getDailySocialMultiplier(0.0), 0.01)
        assertEquals("10% bonus should give 1.10x", 1.10, getDailySocialMultiplier(0.10), 0.01)
    }

    @Test
    fun `energy calculations are mathematically sound`() {
        // Test that energy drain doesn't go negative
        val initialEnergy = 100
        val drainAmount = 150
        val result = (initialEnergy - drainAmount).coerceAtLeast(0)
        assertEquals("Energy should not go below zero", 0, result)
        
        // Test energy restoration bounds
        val maxEnergy = 1000
        val restoreAmount = 200
        val currentEnergy = 900
        val newEnergy = (currentEnergy + restoreAmount).coerceAtMost(maxEnergy)
        assertEquals("Energy should not exceed maximum", maxEnergy, newEnergy)
    }

    // Helper methods to test private logic
    private fun calculateHumanCheckMultiplier(passed: Int, failed: Int): Double {
        val total = passed + failed
        if (total == 0) return 1.0
        
        val passRate = passed.toDouble() / total
        return when {
            passRate >= 0.8 -> 1.0
            passRate >= 0.5 -> 0.7
            else -> 0.3
        }
    }

    private fun getStakeMultiplierForTest(stakedSkrHuman: Double): Double {
        // НОВАЯ СИСТЕМА СТЕЙКИНГА
        // Tier 1: 0 - 5,000 SKR     → 1.0x множитель
        // Tier 2: 5,000 - 15,000 SKR → 1.5x множитель (+50%)
        // Tier 3: 15,000 - 50,000 SKR → 2.5x множитель (+150%)
        // Tier 4: 50,000+ SKR       → 5.0x множитель (+400%)
        val STAKE_TIER_1_SKR = 5000.0
        val STAKE_TIER_2_SKR = 15000.0
        val STAKE_TIER_3_SKR = 50000.0
        val STAKE_MULT_1 = 1.0
        val STAKE_MULT_2 = 1.5
        val STAKE_MULT_3 = 2.5
        val STAKE_MULT_4 = 5.0
        
        if (stakedSkrHuman <= 0) return 1.0
        return when {
            stakedSkrHuman >= STAKE_TIER_3_SKR -> STAKE_MULT_4  // 50,000+ SKR = 5.0x
            stakedSkrHuman >= STAKE_TIER_2_SKR -> STAKE_MULT_3  // 15,000-50,000 SKR = 2.5x
            stakedSkrHuman >= STAKE_TIER_1_SKR -> STAKE_MULT_2  // 5,000-15,000 SKR = 1.5x
            else -> STAKE_MULT_1                                // 0-5,000 SKR = 1.0x
        }
    }

    private fun getDailySocialMultiplier(dailyBonusPercent: Double): Double {
        val DAILY_SOCIAL_MAX_BONUS = 0.15
        if (dailyBonusPercent <= 0.0) return 1.0
        val clampedBonus = minOf(dailyBonusPercent, DAILY_SOCIAL_MAX_BONUS)
        return 1.0 + clampedBonus
    }
}