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
 * Acceleration tiers — season duration DECREASES as active miners grow.
 * More users → faster seasons → earlier token liquidity → viral incentive.
 *
 * Tier     | Active miners  | Season  | Pool/night
 * ---------|----------------|---------|------------
 * Genesis  | < 500          | 16 wks  | 4.46M SPR
 * Pioneer  | 500–2K         | 14 wks  | 5.10M SPR
 * Growing  | 2K–5K          | 12 wks  | 5.95M SPR
 * Active   | 5K–15K         | 10 wks  | 7.14M SPR
 * Viral    | 15K–50K        | 7 wks   | 10.2M SPR
 * Mass     | 50K–150K       | 5 wks   | 14.3M SPR
 * Global   | > 150K         | 3 wks   | 23.8M SPR
 */
export function currentWeeks(nActive: number): number {
  if (nActive >= 150_000) return 3;
  if (nActive >= 50_000)  return 5;
  if (nActive >= 15_000)  return 7;
  if (nActive >= 5_000)   return 10;
  if (nActive >= 2_000)   return 12;
  if (nActive >= 500)     return 14;
  return 16;
}

/**
 * Returns the current acceleration tier name based on active devices.
 * Useful for display in the app ("You are in Viral tier — season ends in 7 weeks").
 */
export function accelerationTier(nActive: number): string {
  if (nActive >= 150_000) return 'Global';
  if (nActive >= 50_000)  return 'Mass';
  if (nActive >= 15_000)  return 'Viral';
  if (nActive >= 5_000)   return 'Active';
  if (nActive >= 2_000)   return 'Growing';
  if (nActive >= 500)     return 'Pioneer';
  return 'Genesis';
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
 * Calculate referral boost.
 * +5% per referral, max 5 referrals → cap +25%.
 * Visible and achievable: 1 friend = noticeable, 5 friends = significant.
 */
export function calcReferralBoost(referralCount: number): number {
  const MAX_REFERRAL_BOOST = 0.25; // 5 referrals = max
  const BOOST_PER_REFERRAL = 0.05; // +5% each
  return Math.min(referralCount * BOOST_PER_REFERRAL, MAX_REFERRAL_BOOST);
}

/**
 * Calculate social boost (referrals + tasks in separate buckets, combined cap 40%).
 * Referrals and tasks no longer compete — both are worth doing.
 * Still weaker than paid SKR Pro (+50%) or Genesis NFT (×3).
 */
export function calcSocialBoost(
  referralCount: number,
  dailyTasksPercent: number
): number {
  const refBoost = calcReferralBoost(referralCount);           // 0–25%
  const taskBoost = Math.max(0, Math.min(dailyTasksPercent, 0.30)); // 0–30%
  return Math.min(refBoost + taskBoost, MAX_SOCIAL_BOOST);    // cap 40%
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

  const { finalNp: rawFinalNp, nftMultiplier, totalMultiplier } = calcFinalNp(
    baseNp,
    socialBoost,
    skrBoost,
    ctx.hasGenesisNft,
    stakingBoost
  );

  // New-user welcome bonus: flat +10% applied after all other multipliers
  const newUserBonus = ctx.newUserBonus ?? 0;
  const finalNp = Math.round(rawFinalNp * (1 + newUserBonus));

  return {
    baseNp,
    socialBoost,
    skrBoost,
    stakingBoost,
    nftMultiplier,
    totalMultiplier,
    finalNp,
    sleepTokens: 0,
    newUserBonus,
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
