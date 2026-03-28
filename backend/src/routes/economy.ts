import { Router } from 'express';
import { AppError } from '../middleware/errorHandler';
import { isValidSolanaAddress, pickWallet } from '../utils/solanaAddress';
import { query } from '../database';
import {
  SEASON_POOL,
  MAX_SLEEP_MINUTES,
  MAX_SOCIAL_BOOST,
  MAX_TOTAL_MULTIPLIER,
  GENESIS_NFT_PRICE,
  GENESIS_NFT_LIMIT,
  SkrBoostLevel,
  SKR_BOOST_CONFIG,
} from '../economy/constants';
import {
  calcSkrBoost,
  calcSocialBoost,
  calcStakingBoost,
  calcFinalNp,
  calcBaseNp,
  poolPerNight,
  currentWeeks,
  SKR_STAKING_BOOST_TIERS,
} from '../economy';

const router = Router();

const BASE_POINTS_PER_SECOND = 0.2;

/**
 * GET /api/v1/economy/config
 * Returns all economy constants so clients don't need to hardcode them.
 * Android app uses this to keep local preview calculations in sync.
 */
router.get('/config', (_req, res) => {
  const skrBoosts = Object.entries(SKR_BOOST_CONFIG).map(([level, cfg]) => ({
    level,
    multiplier: 1.0 + cfg.boost,
    boost_percent: cfg.boost * 100,
    price_skr: cfg.price,
  }));

  res.json({
    success: true,
    data: {
      base_rate_per_minute: 1.0,
      max_sleep_minutes: MAX_SLEEP_MINUTES,
      season_pool: SEASON_POOL,
      skr_boosts: skrBoosts,
      genesis_nft: {
        multiplier: 3.0,
        price_skr: GENESIS_NFT_PRICE,
        max_supply: GENESIS_NFT_LIMIT,
        note: 'Crown jewel — 3× night points multiplier',
      },
      social: {
        max_boost: MAX_SOCIAL_BOOST,
        max_referral_boost: 0.20,
        boost_per_referral: 0.01,
        max_task_boost: 0.20,
      },
      max_total_multiplier: MAX_TOTAL_MULTIPLIER,
      stake_multipliers: SKR_STAKING_BOOST_TIERS.map((t) => ({
        min_skr: t.minStakedSkr,
        boost_addend: t.boostAddend,
        multiplier: 1.0 + t.boostAddend,
      })),
    },
  });
});

/**
 * POST /api/v1/economy/forecast
 * Preview of Night Points for given session parameters (stateless, no auth required).
 * Used by the Android app to show real-time earning estimates.
 */
router.post('/forecast', async (req, res, next) => {
  try {
    const body = (req.body || {}) as Record<string, unknown>;

    const walletAddress = pickWallet(body);
    if (!walletAddress) {
      throw new AppError(400, 'wallet_address is required');
    }
    if (!isValidSolanaAddress(walletAddress)) {
      throw new AppError(400, 'invalid wallet format');
    }

    const minutesSlept = Math.max(0, Math.min(MAX_SLEEP_MINUTES, Number(body.minutes_slept ?? body.minutesSlept ?? 480)));
    const skrBoostLevelRaw = String(body.skr_boost_level ?? body.skrBoostLevel ?? 'NONE').toUpperCase();
    const skrBoostLevel = Object.values(SkrBoostLevel).includes(skrBoostLevelRaw as SkrBoostLevel)
      ? (skrBoostLevelRaw as SkrBoostLevel)
      : SkrBoostLevel.NONE;
    const hasGenesisNft = Boolean(body.has_genesis_nft ?? body.hasGenesisNft ?? false);
    const weekIndex = Math.max(1, Number(body.week_index ?? body.weekIndex ?? 1));

    // Fetch user context from DB
    let referralCount = 0;
    let dailySocialBonusPercent = 0;
    let stakedSkrHuman = 0;
    let activeDevices = 1000;

    const userRow = await query<{ referral_count: string; staked_skr_raw: string }>(
      `SELECT COALESCE(referral_count, 0)::text AS referral_count,
              COALESCE(staked_skr_raw, 0)::text AS staked_skr_raw
       FROM users WHERE wallet_address = $1`,
      [walletAddress]
    ).catch(() => []);

    if (userRow.length > 0) {
      referralCount = Number(userRow[0].referral_count) || 0;
      stakedSkrHuman = Number(userRow[0].staked_skr_raw) / 1_000_000;
    }

    const seasonRow = await query<{ active_devices: number; total_weeks: number }>(
      `SELECT active_devices, total_weeks FROM season_stats WHERE status = 'ACTIVE' LIMIT 1`
    ).catch(() => []);

    if (seasonRow.length > 0) {
      activeDevices = Number(seasonRow[0].active_devices) || 1000;
    }

    const maxWeeks = currentWeeks(activeDevices);
    const humanFactor = 1.0; // assume perfect for forecast

    const baseNp = calcBaseNp(minutesSlept, humanFactor, weekIndex, maxWeeks);
    const socialBoost = calcSocialBoost(referralCount, dailySocialBonusPercent);
    const skrBoost = calcSkrBoost(skrBoostLevel);
    const stakingBoost = calcStakingBoost(stakedSkrHuman);

    const { finalNp, nftMultiplier, totalMultiplier } = calcFinalNp(
      baseNp, socialBoost, skrBoost, hasGenesisNft, stakingBoost
    );

    const poolNight = poolPerNight(activeDevices);

    res.json({
      success: true,
      data: {
        base_np: parseFloat(baseNp.toFixed(2)),
        final_np: parseFloat(finalNp.toFixed(2)),
        total_multiplier: parseFloat(totalMultiplier.toFixed(4)),
        pool_night: poolNight,
        minutes_slept: minutesSlept,
        multipliers: {
          social: parseFloat((1.0 + socialBoost).toFixed(4)),
          skr_boost: parseFloat((1.0 + skrBoost).toFixed(4)),
          staking: parseFloat((1.0 + stakingBoost).toFixed(4)),
          genesis_nft: nftMultiplier,
        },
        boost_context: {
          referral_count: referralCount,
          staked_skr_human: stakedSkrHuman,
          skr_boost_level: skrBoostLevel,
          has_genesis_nft: hasGenesisNft,
        },
      },
    });
  } catch (error) {
    next(error);
  }
});

export default router;
