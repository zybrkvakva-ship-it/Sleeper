/**
 * Sleeper Economy Core — Sleep-Based Mining
 * Formula: baseNp = minutesSlept × rate × humanFactor × difficulty
 * finalNp = baseNp × min((1+social) × (1+skr+staking) × genesisNft, 6.0)
 *
 * No storage multiplier — that was a legacy Proof-of-Storage concept.
 */

import {
  SEASON_POOL,
  BASE_RATE_PER_MINUTE,
  MAX_SLEEP_MINUTES,
  MAX_SOCIAL_BOOST,
  MAX_TOTAL_MULTIPLIER,
  SkrBoostLevel,
  SKR_BOOST_CONFIG,
  NightContext,
  NightReward
} from './constants';

export const SKR_STAKING_BOOST_TIERS = [
  { minStakedSkr: 1_000_000, boostAddend: 0.20 },
  { minStakedSkr: 500_000,   boostAddend: 0.10 },
  { minStakedSkr: 100_000,   boostAddend: 0.05 },
  { minStakedSkr: 0,         boostAddend: 0.00 },
];

/**
 * Calculate season duration in weeks based on active devices
 */
export function currentWeeks(nActive: number): number {
  if (nActive <= 1_000) return 16;
  if (nActive <= 5_000) return 15;
  if (nActive <= 10_000) return 14;
  if (nActive <= 25_000) return 12;
  if (nActive <= 50_000) return 10;
  return 8;
}

/**
 * Calculate SLEEP pool per night
 */
export function poolPerNight(nActive: number, seasonPool: number = SEASON_POOL): number {
  const weeks = currentWeeks(nActive);
  const nightsTotal = weeks * 7;
  return Math.floor(seasonPool / nightsTotal);
}

/**
 * Calculate time-decay difficulty multiplier (1.0 week 1 → 0.2 final week)
 */
export function difficultyByWeek(weekIndex: number, maxWeeks: number): number {
  const actualWeek = Math.min(weekIndex, maxWeeks);
  const progress = (actualWeek - 1) / Math.max(maxWeeks - 1, 1);
  const difficulty = 1.0 - (progress * 0.8);
  return Math.max(0.2, Math.min(1.0, difficulty));
}

/**
 * Calculate base Night Points.
 * Formula: T × R × H × D
 *   T = minutesSlept (capped at MAX_SLEEP_MINUTES)
 *   R = BASE_RATE_PER_MINUTE
 *   H = humanFactor (0.3–1.0, anti-abuse: movement/screen violations)
 *   D = difficultyByWeek (season decay)
 */
export function calcBaseNp(
  minutesSlept: number,
  humanFactor: number,
  weekIndex: number,
  maxWeeks: number
): number {
  const T = Math.max(0, Math.min(minutesSlept, MAX_SLEEP_MINUTES));
  const R = BASE_RATE_PER_MINUTE;
  const H = Math.max(0, Math.min(humanFactor, 1.0));
  const D = difficultyByWeek(weekIndex, maxWeeks);
  return T * R * H * D;
}

/**
 * Calculate referral boost (max +20% of social budget)
 */
export function calcReferralBoost(referralCount: number): number {
  const MAX_REFERRAL_BOOST = 0.20;
  const BOOST_PER_REFERRAL = 0.01;
  return Math.min(referralCount * BOOST_PER_REFERRAL, MAX_REFERRAL_BOOST);
}

/**
 * Calculate social boost (referrals + daily tasks), capped at MAX_SOCIAL_BOOST (0.20).
 * Intentionally weaker than any paid SKR boost (LITE starts at +10%).
 */
export function calcSocialBoost(
  referralCount: number,
  dailyTasksPercent: number
): number {
  const refBoost = calcReferralBoost(referralCount);
  const taskBoost = Math.max(0, Math.min(dailyTasksPercent, 0.20));
  return Math.min(refBoost + taskBoost, MAX_SOCIAL_BOOST);
}

/**
 * Calculate SKR paid boost value for a given tier
 */
export function calcSkrBoost(level: SkrBoostLevel): number {
  return SKR_BOOST_CONFIG[level].boost;
}

/**
 * Calculate staking boost based on staked SKR amount (additive, 0–0.20)
 */
export function calcStakingBoost(stakedSkrHuman: number): number {
  for (const tier of SKR_STAKING_BOOST_TIERS) {
    if (stakedSkrHuman >= tier.minStakedSkr) return tier.boostAddend;
  }
  return 0;
}

/**
 * Calculate final NP with all boosts and 6× cap.
 * Device NFT = gate (access control), NOT a boost.
 * Genesis NFT = 3.0× multiplier (crown jewel, 500 SKR).
 *
 * Formula: baseNp × min((1+social) × (1+skr+staking) × nftMult, MAX_TOTAL_MULTIPLIER)
 */
export function calcFinalNp(
  baseNp: number,
  socialBoost: number,
  skrBoost: number,
  hasGenesisNft: boolean,
  stakingBoost: number = 0
): {
  finalNp: number;
  nftMultiplier: number;
  stakingBoost: number;
  totalMultiplier: number;
} {
  const bNft = hasGenesisNft ? 3.0 : 1.0;
  const rawMultiplier = (1.0 + socialBoost) * (1.0 + skrBoost + stakingBoost) * bNft;
  const cappedMultiplier = Math.min(rawMultiplier, MAX_TOTAL_MULTIPLIER);
  return {
    finalNp: baseNp * cappedMultiplier,
    nftMultiplier: bNft,
    stakingBoost,
    totalMultiplier: cappedMultiplier,
  };
}

/**
 * Calculate night reward — main entry point
 */
export function calculateNightReward(ctx: NightContext): NightReward {
  const maxWeeks = currentWeeks(ctx.activeDevices);

  const baseNp = calcBaseNp(
    ctx.minutesSlept,
    ctx.humanFactor,
    ctx.weekIndex,
    maxWeeks
  );

  const socialBoost = calcSocialBoost(ctx.referralCount, ctx.dailyTasksPercent);
  const skrBoost = calcSkrBoost(ctx.skrBoostLevel);
  const stakingBoost = calcStakingBoost(ctx.stakedSkrHuman ?? 0);

  const { finalNp, nftMultiplier, totalMultiplier } = calcFinalNp(
    baseNp,
    socialBoost,
    skrBoost,
    ctx.hasGenesisNft,
    stakingBoost
  );

  return {
    baseNp,
    socialBoost,
    skrBoost,
    stakingBoost,
    nftMultiplier,
    totalMultiplier,
    finalNp,
    sleepTokens: 0,
  };
}

/**
 * Calculate SLEEP reward share for a single user
 */
export function calcSleepRewardForUser(
  userNp: number,
  totalNp: number,
  poolNight: number
): number {
  if (totalNp <= 0 || userNp <= 0) return 0;
  if (userNp > totalNp) return 0;
  return Math.max(0, Math.floor(poolNight * (userNp / totalNp)));
}

/**
 * Distribute SLEEP tokens proportionally across all users
 */
export function distributeSleepTokens(
  usersNp: Record<string, number>,
  poolNight: number
): Record<string, number> {
  const totalNp = Object.values(usersNp).reduce((sum, np) => sum + np, 0);
  if (totalNp <= 0) {
    return Object.keys(usersNp).reduce((acc, id) => { acc[id] = 0; return acc; }, {} as Record<string, number>);
  }
  const rewards: Record<string, number> = {};
  for (const [userId, userNp] of Object.entries(usersNp)) {
    rewards[userId] = calcSleepRewardForUser(userNp, totalNp, poolNight);
  }
  return rewards;
}
