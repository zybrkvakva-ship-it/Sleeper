/**
 * Sleeper Economy Core
 * Ported from Kotlin to TypeScript
 */

import {
  SEASON_POOL,
  BASE_RATE_PER_MINUTE,
  MAX_SLEEP_MINUTES,
  MAX_STORAGE_MB,
  MAX_SOCIAL_BOOST,
  MAX_TOTAL_MULTIPLIER,
  DEVICE_NFT_BOOST,
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
 * Calculate time-decay difficulty multiplier
 */
export function difficultyByWeek(weekIndex: number, maxWeeks: number): number {
  const actualWeek = Math.min(weekIndex, maxWeeks);
  const progress = (actualWeek - 1) / Math.max(maxWeeks - 1, 1);
  const difficulty = 1.0 - (progress * 0.8);
  return Math.max(0.2, Math.min(1.0, difficulty));
}

/**
 * Calculate storage multiplier
 */
export function calcStorageMultiplier(storageMb: number): number {
  const clamped = Math.max(0, Math.min(storageMb, MAX_STORAGE_MB));
  return 1.0 + (clamped / 100.0);
}

/**
 * Calculate base Night Points
 */
export function calcBaseNp(
  minutesSlept: number,
  storageMb: number,
  humanFactor: number,
  weekIndex: number,
  maxWeeks: number
): number {
  const T = Math.max(0, Math.min(minutesSlept, MAX_SLEEP_MINUTES));
  const R = BASE_RATE_PER_MINUTE;
  const S = calcStorageMultiplier(storageMb);
  const H = Math.max(0, Math.min(humanFactor, 1.0));
  const D = difficultyByWeek(weekIndex, maxWeeks);
  
  return T * R * S * H * D;
}

/**
 * Calculate referral boost
 */
export function calcReferralBoost(referralCount: number): number {
  const MAX_REFERRAL_BOOST = 0.20;
  const BOOST_PER_REFERRAL = 0.01;
  return Math.min(referralCount * BOOST_PER_REFERRAL, MAX_REFERRAL_BOOST);
}

/**
 * Calculate social boost (referrals + daily tasks)
 */
export function calcSocialBoost(
  referralCount: number,
  dailyTasksPercent: number
): number {
  const refBoost = calcReferralBoost(referralCount);
  const taskBoost = Math.max(0, Math.min(dailyTasksPercent, 0.30));
  const totalBoost = refBoost + taskBoost;
  return Math.min(totalBoost, MAX_SOCIAL_BOOST);
}

/**
 * Calculate SKR boost
 */
export function calcSkrBoost(level: SkrBoostLevel): number {
  return SKR_BOOST_CONFIG[level].boost;
}

/**
 * Calculate staking boost based on staked SKR amount
 */
export function calcStakingBoost(stakedSkrHuman: number): number {
  for (const tier of SKR_STAKING_BOOST_TIERS) {
    if (stakedSkrHuman >= tier.minStakedSkr) return tier.boostAddend;
  }
  return 0;
}

/**
 * Calculate final NP with all boosts and cap
 * Formula: baseNp × min((1+social) × (1+skr+staking) × nftMult × (1+deviceNft), 6.0)
 */
export function calcFinalNp(
  baseNp: number,
  socialBoost: number,
  skrBoost: number,
  hasGenesisNft: boolean,
  stakingBoost: number = 0,
  hasDeviceNft: boolean = false
): {
  finalNp: number;
  nftMultiplier: number;
  stakingBoost: number;
  deviceNftBoost: number;
  totalMultiplier: number;
} {
  const bNft = hasGenesisNft ? 3.0 : 1.0;
  const deviceBoost = hasDeviceNft ? DEVICE_NFT_BOOST : 0;
  const rawMultiplier = (1.0 + socialBoost) * (1.0 + skrBoost + stakingBoost) * bNft * (1.0 + deviceBoost);
  const cappedMultiplier = Math.min(rawMultiplier, MAX_TOTAL_MULTIPLIER);
  const finalNp = baseNp * cappedMultiplier;

  return {
    finalNp,
    nftMultiplier: bNft,
    stakingBoost,
    deviceNftBoost: deviceBoost,
    totalMultiplier: cappedMultiplier
  };
}

/**
 * Calculate night reward (main function)
 */
export function calculateNightReward(ctx: NightContext): NightReward {
  // Season info
  const maxWeeks = currentWeeks(ctx.activeDevices);
  
  // Base NP
  const baseNp = calcBaseNp(
    ctx.minutesSlept,
    ctx.storageMb,
    ctx.humanFactor,
    ctx.weekIndex,
    maxWeeks
  );
  
  // Boosts
  const socialBoost = calcSocialBoost(ctx.referralCount, ctx.dailyTasksPercent);
  const skrBoost = calcSkrBoost(ctx.skrBoostLevel);
  const stakingBoost = calcStakingBoost(ctx.stakedSkrHuman ?? 0);

  // Final NP
  const { finalNp, nftMultiplier, deviceNftBoost, totalMultiplier } = calcFinalNp(
    baseNp,
    socialBoost,
    skrBoost,
    ctx.hasGenesisNft,
    stakingBoost,
    ctx.hasDeviceNft ?? false
  );

  return {
    baseNp,
    socialBoost,
    skrBoost,
    stakingBoost,
    nftMultiplier,
    deviceNftBoost,
    totalMultiplier,
    finalNp,
    sleepTokens: 0 // Calculated during distribution
  };
}

/**
 * Calculate SLEEP reward for user
 */
export function calcSleepRewardForUser(
  userNp: number,
  totalNp: number,
  poolNight: number
): number {
  if (totalNp <= 0 || userNp <= 0) return 0;
  if (userNp > totalNp) return 0;
  
  const share = userNp / totalNp;
  const reward = Math.floor(poolNight * share);
  return Math.max(0, reward);
}

/**
 * Distribute SLEEP tokens to all users
 */
export function distributeSleepTokens(
  usersNp: Record<string, number>,
  poolNight: number
): Record<string, number> {
  const totalNp = Object.values(usersNp).reduce((sum, np) => sum + np, 0);
  
  if (totalNp <= 0) {
    return Object.keys(usersNp).reduce((acc, userId) => {
      acc[userId] = 0;
      return acc;
    }, {} as Record<string, number>);
  }
  
  const rewards: Record<string, number> = {};
  
  for (const [userId, userNp] of Object.entries(usersNp)) {
    rewards[userId] = calcSleepRewardForUser(userNp, totalNp, poolNight);
  }
  
  return rewards;
}
