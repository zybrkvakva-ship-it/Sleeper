/**
 * Sleeper Economy Constants
 * Sleep-Based Mining: users earn NP while phone is idle during sleep.
 * No storage/Proof-of-Storage concept — that was a previous model.
 */

/**
 * Total SPR supply allocated for sleep mining: 10% of 10B SKR-matched supply.
 * Remaining 90% = team, investors, liquidity, ecosystem.
 */
export const TOTAL_MINING_SUPPLY = 1_000_000_000;
/** 20 seasons × 50M SPR = 1B total mined over ~5 years */
export const SEASON_COUNT = 20;
export const SEASON_POOL = 50_000_000;
export const MAX_DEVICES = 150_000;
/** Social boost cap: referrals (up to +25%) + tasks (up to +30%) can stack, capped at 40% */
export const MAX_SOCIAL_BOOST = 0.40;
export const MAX_TOTAL_MULTIPLIER = 6.0;
export const BASE_RATE_PER_MINUTE = 1.0;
export const MAX_SLEEP_MINUTES = 480;
export const GENESIS_NFT_PRICE = 500.0;
export const GENESIS_NFT_LIMIT = 10_000;

export enum SkrBoostLevel {
  NONE = 'NONE',
  LITE = 'LITE',
  PLUS = 'PLUS',
  PRO = 'PRO',
  ULTRA = 'ULTRA'
}

/**
 * SKR Boost hierarchy (weakest → strongest, all stronger than free social +20%):
 * LITE +10% → PLUS +25% → PRO +50% → ULTRA +100% → Genesis NFT ×3.0
 */
export const SKR_BOOST_CONFIG: Record<SkrBoostLevel, { boost: number; price: number }> = {
  [SkrBoostLevel.NONE]:  { boost: 0.00, price: 0.0  },
  [SkrBoostLevel.LITE]:  { boost: 0.10, price: 1.0  },
  [SkrBoostLevel.PLUS]:  { boost: 0.25, price: 2.5  },
  [SkrBoostLevel.PRO]:   { boost: 0.50, price: 10.0 },
  [SkrBoostLevel.ULTRA]: { boost: 1.00, price: 20.0 },
};

export interface NightContext {
  minutesSlept: number;
  humanFactor: number;
  weekIndex: number;
  activeDevices: number;
  referralCount: number;
  dailyTasksPercent: number;
  skrBoostLevel: SkrBoostLevel;
  hasGenesisNft: boolean;
  stakedSkrHuman?: number;
  /** +0.10 welcome bonus for referred users on their first 3 nights */
  newUserBonus?: number;
}

export interface NightReward {
  baseNp: number;
  socialBoost: number;
  skrBoost: number;
  stakingBoost: number;
  nftMultiplier: number;
  totalMultiplier: number;
  finalNp: number;
  sleepTokens: number;
  newUserBonus: number;
}
