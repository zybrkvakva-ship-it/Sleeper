/**
 * Economy unit tests
 * Tests all core calculation functions in backend/src/economy/index.ts
 */

import {
  calcStorageMultiplier,
  calcSkrBoost,
  calcSocialBoost,
  calcReferralBoost,
  calcFinalNp,
  calcBaseNp,
  poolPerNight,
  difficultyByWeek,
  currentWeeks,
  distributeSleepTokens,
} from '../src/economy';
import { SkrBoostLevel } from '../src/economy/constants';

// ─── calcStorageMultiplier ────────────────────────────────────────────────────

describe('calcStorageMultiplier', () => {
  it('returns 1.0 for 0 MB', () => {
    expect(calcStorageMultiplier(0)).toBe(1.0);
  });

  it('returns 1.0 for negative MB (clamped)', () => {
    expect(calcStorageMultiplier(-100)).toBe(1.0);
  });

  it('returns 6.0 for 500 MB (max)', () => {
    // 1.0 + 500/100 = 6.0
    expect(calcStorageMultiplier(500)).toBe(6.0);
  });

  it('returns 3.5 for 250 MB', () => {
    expect(calcStorageMultiplier(250)).toBeCloseTo(3.5, 5);
  });

  it('clamps at max (600 MB returns same as 500 MB)', () => {
    expect(calcStorageMultiplier(600)).toBe(calcStorageMultiplier(500));
  });

  it('returns proportional values', () => {
    const at100 = calcStorageMultiplier(100);
    const at200 = calcStorageMultiplier(200);
    const at300 = calcStorageMultiplier(300);
    expect(at200).toBeGreaterThan(at100);
    expect(at300).toBeGreaterThan(at200);
  });
});

// ─── calcSkrBoost ────────────────────────────────────────────────────────────

describe('calcSkrBoost', () => {
  it('returns 0 for NONE', () => {
    expect(calcSkrBoost(SkrBoostLevel.NONE)).toBe(0.0);
  });

  it('returns 0.05 for LITE', () => {
    expect(calcSkrBoost(SkrBoostLevel.LITE)).toBe(0.05);
  });

  it('returns 0.10 for PLUS', () => {
    expect(calcSkrBoost(SkrBoostLevel.PLUS)).toBe(0.10);
  });

  it('returns 0.50 for PRO', () => {
    expect(calcSkrBoost(SkrBoostLevel.PRO)).toBe(0.50);
  });

  it('returns 1.0 for ULTRA', () => {
    expect(calcSkrBoost(SkrBoostLevel.ULTRA)).toBe(1.0);
  });
});

// ─── calcReferralBoost ───────────────────────────────────────────────────────

describe('calcReferralBoost', () => {
  it('returns 0 for 0 referrals', () => {
    expect(calcReferralBoost(0)).toBe(0);
  });

  it('returns 0.01 per referral', () => {
    expect(calcReferralBoost(5)).toBeCloseTo(0.05, 5);
  });

  it('caps at 0.20 (20%)', () => {
    expect(calcReferralBoost(100)).toBe(0.20);
  });

  it('caps exactly at 20 referrals', () => {
    expect(calcReferralBoost(20)).toBe(0.20);
    expect(calcReferralBoost(21)).toBe(0.20);
  });
});

// ─── calcSocialBoost ─────────────────────────────────────────────────────────

describe('calcSocialBoost', () => {
  it('returns 0 with no referrals and no tasks', () => {
    expect(calcSocialBoost(0, 0)).toBe(0);
  });

  it('combines referral and task boosts', () => {
    const result = calcSocialBoost(5, 0.10); // 5% referral + 10% tasks = 15%
    expect(result).toBeCloseTo(0.15, 5);
  });

  it('caps total at MAX_SOCIAL_BOOST (0.4)', () => {
    const result = calcSocialBoost(100, 0.30); // 20% + 30% = 50% → capped at 40%
    expect(result).toBe(0.4);
  });

  it('task boost is clamped at 0.30', () => {
    const result = calcSocialBoost(0, 0.50); // task > 0.30 → clamped at 0.30
    expect(result).toBeCloseTo(0.30, 5);
  });
});

// ─── calcFinalNp ─────────────────────────────────────────────────────────────

describe('calcFinalNp', () => {
  it('applies formula: base × (1+social) × (1+skr) × nftMultiplier', () => {
    const { finalNp } = calcFinalNp(100, 0.10, 0.05, false);
    // 100 × 1.10 × 1.05 × 1.0 = 115.5
    expect(finalNp).toBeCloseTo(115.5, 5);
  });

  it('NFT multiplier is 3.0 when hasGenesisNft = true', () => {
    const { nftMultiplier } = calcFinalNp(100, 0, 0, true);
    expect(nftMultiplier).toBe(3.0);
  });

  it('NFT multiplier is 1.0 when hasGenesisNft = false', () => {
    const { nftMultiplier } = calcFinalNp(100, 0, 0, false);
    expect(nftMultiplier).toBe(1.0);
  });

  it('caps total multiplier at 6.0x', () => {
    // Max possible: (1+0.4) × (1+1.0) × 3.0 = 1.4 × 2.0 × 3.0 = 8.4 → capped at 6.0
    const { totalMultiplier } = calcFinalNp(100, 0.4, 1.0, true);
    expect(totalMultiplier).toBe(6.0);
  });

  it('does not cap when multiplier is below 6x', () => {
    // 1.0 × 1.05 × 1.0 = 1.05 — no cap
    const { totalMultiplier } = calcFinalNp(100, 0, 0.05, false);
    expect(totalMultiplier).toBeCloseTo(1.05, 5);
  });

  it('returns correct totalMultiplier for uncapped scenario', () => {
    const { totalMultiplier } = calcFinalNp(100, 0.10, 0.10, false);
    // (1+0.10) × (1+0.10) × 1.0 = 1.21
    expect(totalMultiplier).toBeCloseTo(1.21, 5);
  });
});

// ─── calcBaseNp ──────────────────────────────────────────────────────────────

describe('calcBaseNp', () => {
  it('returns 0 for 0 minutes', () => {
    expect(calcBaseNp(0, 300, 1.0, 1, 16)).toBe(0);
  });

  it('increases with more sleep time', () => {
    const a = calcBaseNp(240, 300, 1.0, 1, 16);
    const b = calcBaseNp(480, 300, 1.0, 1, 16);
    expect(b).toBeGreaterThan(a);
  });

  it('caps at MAX_SLEEP_MINUTES (480)', () => {
    const at480 = calcBaseNp(480, 300, 1.0, 1, 16);
    const at600 = calcBaseNp(600, 300, 1.0, 1, 16);
    expect(at480).toBe(at600);
  });

  it('human factor of 0 yields 0 NP', () => {
    expect(calcBaseNp(480, 300, 0, 1, 16)).toBe(0);
  });

  it('human factor reduces NP proportionally', () => {
    const full = calcBaseNp(480, 300, 1.0, 1, 16);
    const half = calcBaseNp(480, 300, 0.5, 1, 16);
    expect(half).toBeCloseTo(full * 0.5, 5);
  });
});

// ─── difficultyByWeek ────────────────────────────────────────────────────────

describe('difficultyByWeek', () => {
  it('returns 1.0 at week 1 (start of season)', () => {
    expect(difficultyByWeek(1, 16)).toBe(1.0);
  });

  it('decreases over season', () => {
    const early = difficultyByWeek(2, 16);
    const late = difficultyByWeek(14, 16);
    expect(late).toBeLessThan(early);
  });

  it('never drops below 0.2', () => {
    expect(difficultyByWeek(16, 16)).toBeGreaterThanOrEqual(0.2);
    expect(difficultyByWeek(100, 16)).toBeGreaterThanOrEqual(0.2);
  });

  it('never exceeds 1.0', () => {
    expect(difficultyByWeek(1, 16)).toBeLessThanOrEqual(1.0);
    expect(difficultyByWeek(0, 16)).toBeLessThanOrEqual(1.0);
  });
});

// ─── poolPerNight ─────────────────────────────────────────────────────────────

describe('poolPerNight', () => {
  it('larger pool with fewer active devices (longer season)', () => {
    const smallCrowd = poolPerNight(500);   // 16 weeks → 112 nights
    const bigCrowd = poolPerNight(100000);  // 8 weeks → 56 nights
    expect(bigCrowd).toBeGreaterThan(smallCrowd);
  });

  it('returns integer (floor)', () => {
    const pool = poolPerNight(1000);
    expect(Number.isInteger(pool)).toBe(true);
  });

  it('sums to approximately SEASON_POOL over the full season', () => {
    const devices = 5000;
    const pool = poolPerNight(devices);
    const weeks = currentWeeks(devices);
    const nights = weeks * 7;
    // Allow ±nights rounding error due to Math.floor
    expect(pool * nights).toBeGreaterThanOrEqual(5_000_000 - nights);
    expect(pool * nights).toBeLessThanOrEqual(5_000_000);
  });
});

// ─── currentWeeks ────────────────────────────────────────────────────────────

describe('currentWeeks', () => {
  it('returns 16 for ≤1000 devices', () => {
    expect(currentWeeks(100)).toBe(16);
    expect(currentWeeks(1000)).toBe(16);
  });

  it('returns 8 for large device count', () => {
    expect(currentWeeks(100000)).toBe(8);
  });

  it('decreases as device count grows', () => {
    expect(currentWeeks(1001)).toBeLessThan(currentWeeks(1000));
  });
});

// ─── distributeSleepTokens ───────────────────────────────────────────────────

describe('distributeSleepTokens', () => {
  it('single user gets the entire pool', () => {
    const result = distributeSleepTokens({ 'walletA': 100 }, 5000);
    expect(result['walletA']).toBe(5000);
  });

  it('distributes proportionally to NP earned', () => {
    // walletA earned twice as much as walletB
    const result = distributeSleepTokens({ 'walletA': 200, 'walletB': 100 }, 3000);
    expect(result['walletA']).toBe(2000);
    expect(result['walletB']).toBe(1000);
  });

  it('total distributed does not exceed pool (floors)', () => {
    const result = distributeSleepTokens({ 'a': 1, 'b': 1, 'c': 1 }, 100);
    const total = Object.values(result).reduce((s, v) => s + v, 0);
    expect(total).toBeLessThanOrEqual(100);
  });

  it('returns integer token amounts', () => {
    const result = distributeSleepTokens({ 'a': 33, 'b': 67 }, 1000);
    for (const tokens of Object.values(result)) {
      expect(Number.isInteger(tokens)).toBe(true);
    }
  });

  it('returns empty object for empty input', () => {
    const result = distributeSleepTokens({}, 5000);
    expect(Object.keys(result)).toHaveLength(0);
  });
});
