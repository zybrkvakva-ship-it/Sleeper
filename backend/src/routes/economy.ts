import { Router } from 'express';
import { AppError } from '../middleware/errorHandler';
import { isValidSolanaAddress, pickWallet } from '../utils/solanaAddress';
import { query } from '../database';
import {
  SEASON_POOL,
  MAX_SLEEP_MINUTES,
  MAX_STORAGE_MB,
  MAX_SOCIAL_BOOST,
  MAX_TOTAL_MULTIPLIER,
  GENESIS_NFT_PRICE,
  GENESIS_NFT_LIMIT,
  SkrBoostLevel,
  SKR_BOOST_CONFIG,
} from '../economy/constants';
import {
  calcStorageMultiplier,
  calcSkrBoost,
  calcSocialBoost,
  calcFinalNp,
  poolPerNight,
  currentWeeks,
} from '../economy';

const router = Router();

// Points per second used in the mining session endpoint
const BASE_POINTS_PER_SECOND = 0.2;
// Min storage for the logarithmic scale in mining.ts
const MIN_STORAGE_MB = 100;
const MAX_STORAGE_MB_MINING = 600;

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
      base_rate_per_second: BASE_POINTS_PER_SECOND,
      max_session_minutes: MAX_SLEEP_MINUTES,
      season_pool: SEASON_POOL,
      storage: {
        min_mb: MIN_STORAGE_MB,
        max_mb: MAX_STORAGE_MB_MINING,
        min_multiplier: 1.0,
        max_multiplier: 3.0,
        note: 'Logarithmic scale between min and max',
      },
      skr_boosts: skrBoosts,
      genesis_nft: {
        multiplier: 1.1,
        max_multiplier: 1.5,
        price_skr: GENESIS_NFT_PRICE,
        max_supply: GENESIS_NFT_LIMIT,
      },
      social: {
        max_boost: MAX_SOCIAL_BOOST,
        max_referral_boost: 0.20,
        boost_per_referral: 0.01,
        max_task_boost: 0.30,
      },
      max_total_multiplier: MAX_TOTAL_MULTIPLIER,
      stake_multipliers: [
        { min_skr: 0, multiplier: 1.0 },
        { min_skr: 1000, multiplier: 1.2 },
        { min_skr: 10000, multiplier: 1.5 },
      ],
    },
  });
});

/**
 * POST /api/v1/economy/forecast
 * Returns a preview of points per second and breakdown for given session parameters.
 * Used by the Android app to show real-time earning estimates before/during a session.
 * Does NOT require auth — it's a stateless calculation.
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

    const storageMb = Math.max(0, Number(body.storage_mb ?? body.storageMb ?? 0));
    const uptimeMinutes = Math.max(0, Math.min(MAX_SLEEP_MINUTES, Number(body.uptime_minutes ?? body.uptimeMinutes ?? 480)));
    const skrBoostLevelRaw = String(body.skr_boost_level ?? body.skrBoostLevel ?? 'NONE').toUpperCase();
    const skrBoostLevel = Object.values(SkrBoostLevel).includes(skrBoostLevelRaw as SkrBoostLevel)
      ? (skrBoostLevelRaw as SkrBoostLevel)
      : SkrBoostLevel.NONE;
    const hasGenesisNft = Boolean(body.has_genesis_nft ?? body.hasGenesisNft ?? false);

    // Fetch user context from DB for accurate social boost (referrals, tasks)
    let referralCount = 0;
    let dailySocialBonusPercent = 0;
    let stakedSkr = 0;

    const userRow = await query<{ referral_count: number; staked_skr_human: number }>(
      `SELECT COALESCE(referral_count, 0) AS referral_count,
              COALESCE(0, 0) AS staked_skr_human
       FROM users WHERE wallet_address = $1`,
      [walletAddress]
    ).catch(() => []);

    if (userRow.length > 0) {
      referralCount = Number(userRow[0].referral_count) || 0;
      stakedSkr = Number(userRow[0].staked_skr_human) || 0;
    }

    // Logging multiplier (same formula as mining.ts)
    const logStorageMultiplier = (() => {
      const minS = MIN_STORAGE_MB, maxS = MAX_STORAGE_MB_MINING;
      if (storageMb <= minS) return 1.0;
      if (storageMb >= maxS) return 3.0;
      const progress = Math.log(storageMb - minS + 1) / Math.log(maxS - minS + 1);
      return 1.0 + progress * (3.0 - 1.0);
    })();

    const stakeMultiplier = stakedSkr >= 10000 ? 1.5 : stakedSkr >= 1000 ? 1.2 : 1.0;
    const socialBoost = calcSocialBoost(referralCount, dailySocialBonusPercent);
    const skrBoost = calcSkrBoost(skrBoostLevel);
    const nftMultiplier = hasGenesisNft ? 1.1 : 1.0;
    const paidBoostMultiplier = 1.0 + skrBoost;

    const pointsPerSecond =
      BASE_POINTS_PER_SECOND *
      logStorageMultiplier *
      stakeMultiplier *
      (1.0 + socialBoost) *
      paidBoostMultiplier *
      nftMultiplier;

    const effectiveMultiplier = parseFloat(
      Math.min(
        (logStorageMultiplier * stakeMultiplier * (1.0 + socialBoost) * paidBoostMultiplier * nftMultiplier),
        MAX_TOTAL_MULTIPLIER
      ).toFixed(4)
    );

    const totalPointsPreview = Math.floor(pointsPerSecond * uptimeMinutes * 60);

    res.json({
      success: true,
      data: {
        points_per_second: parseFloat(pointsPerSecond.toFixed(6)),
        total_points_preview: totalPointsPreview,
        uptime_minutes: uptimeMinutes,
        effective_multiplier: effectiveMultiplier,
        multipliers: {
          storage: parseFloat(logStorageMultiplier.toFixed(4)),
          stake: stakeMultiplier,
          social: parseFloat((1.0 + socialBoost).toFixed(4)),
          paid_boost: parseFloat(paidBoostMultiplier.toFixed(4)),
          genesis_nft: nftMultiplier,
        },
        boost_context: {
          referral_count: referralCount,
          staked_skr: stakedSkr,
          skr_boost_level: skrBoostLevel,
          has_genesis_nft: hasGenesisNft,
          storage_mb: storageMb,
        },
      },
    });
  } catch (error) {
    next(error);
  }
});

export default router;
