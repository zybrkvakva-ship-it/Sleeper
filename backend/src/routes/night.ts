import { Router } from 'express';
import { v4 as uuidv4 } from 'uuid';
import { query, transaction } from '../database';
import { calculateNightReward } from '../economy';
import { SkrBoostLevel } from '../economy/constants';
import { AppError } from '../middleware/errorHandler';
import { logger } from '../utils/logger';
import { isValidSolanaAddress, pickWallet } from '../utils/solanaAddress';

const router = Router();

/** Extract authToken from X-Auth-Token header or body fields. */
function extractAuthToken(req: { headers: Record<string, any>; body: Record<string, unknown> }): string | undefined {
  return (
    (req.headers['x-auth-token'] as string | undefined) ||
    (req.body?.authToken as string | undefined) ||
    (req.body?.auth_token as string | undefined)
  );
}

/** Validate that authToken belongs to walletAddress and is not expired. Throws AppError on failure. */
async function requireAuth(walletAddress: string, authToken: string | undefined): Promise<void> {
  if (!authToken) {
    throw new AppError(401, 'Authentication required: provide X-Auth-Token header or authToken in body');
  }
  let tokenUuid: string;
  try {
    // Validate UUID format before querying to avoid DB errors
    tokenUuid = authToken.trim();
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(tokenUuid)) {
      throw new Error('bad format');
    }
  } catch {
    throw new AppError(401, 'Invalid auth token format');
  }
  const rows = await query(
    `SELECT 1 FROM wallet_auth_tokens
     WHERE token = $1::uuid AND wallet_address = $2
       AND revoked_at IS NULL AND expires_at > NOW()
     LIMIT 1`,
    [tokenUuid, walletAddress]
  );
  if (rows.length === 0) {
    throw new AppError(401, 'Invalid or expired auth token');
  }
}

/**
 * POST /api/v1/night/start
 * Start a new night session. Issues a session token stored in DB.
 * Requires auth. Device NFT gate enforced.
 */
router.post('/start', async (req, res, next) => {
  try {
    const walletAddress = pickWallet(req.body);
    if (!walletAddress) throw new AppError(400, 'walletAddress (or wallet) is required');
    if (!isValidSolanaAddress(walletAddress)) throw new AppError(400, 'invalid wallet format');

    await requireAuth(walletAddress, extractAuthToken(req as any));

    // Device NFT gate
    const deviceCheck = await query<{ has_device_nft: boolean }>(
      'SELECT COALESCE(has_device_nft, false) AS has_device_nft FROM users WHERE wallet_address = $1',
      [walletAddress]
    );
    if (deviceCheck.length === 0 || !deviceCheck[0].has_device_nft) {
      throw new AppError(403, 'Mining requires a verified Seeker device NFT');
    }

    const today = new Date().toISOString().split('T')[0];

    // Check if already completed a session today
    const existingSession = await query(
      'SELECT id FROM night_sessions WHERE wallet_address = $1 AND night_date = $2',
      [walletAddress, today]
    );
    if (existingSession.length > 0) {
      throw new AppError(409, 'Night session already completed for today');
    }

    // Upsert session token (idempotent — same session_id returned if called twice)
    const sessionId = uuidv4();
    const tokenResult = await query<{ session_id: string }>(
      `INSERT INTO night_session_tokens (session_id, wallet_address, night_date)
       VALUES ($1, $2, $3)
       ON CONFLICT (wallet_address, night_date) DO UPDATE
         SET session_id = night_session_tokens.session_id   -- keep existing
       RETURNING session_id`,
      [sessionId, walletAddress, today]
    );

    res.json({
      success: true,
      sessionId: tokenResult[0].session_id,
      nightDate: today,
      message: 'Night session started',
    });
  } catch (error) {
    next(error);
  }
});

/**
 * POST /api/v1/night/end
 * Complete and process night session.
 * Requires auth + valid session token from /night/start.
 */
router.post('/end', async (req, res, next) => {
  try {
    const body = (req.body || {}) as Record<string, unknown>;
    const walletAddress = pickWallet(body);
    const sessionId = (body.sessionId as string | undefined) || (body.session_id as string | undefined);
    const minutesSlept = pickNumber(body, ['minutesSlept', 'minutes_slept']);
    const movementViolationsRaw = pickNumber(body, ['movementViolations', 'movement_violations']) ?? 0;
    const screenOnCountRaw = pickNumber(body, ['screenOnCount', 'screen_on_count']) ?? 0;

    if (!walletAddress || minutesSlept == null) {
      throw new AppError(400, 'Missing required fields: walletAddress, minutesSlept');
    }
    if (!isValidSolanaAddress(walletAddress)) throw new AppError(400, 'invalid wallet format');
    if (!sessionId) throw new AppError(400, 'sessionId is required (obtain from /night/start)');

    // Auth check
    await requireAuth(walletAddress, extractAuthToken(req as any));

    // Input validation — clamp to safe ranges, reject negative
    if (minutesSlept < 1 || minutesSlept > 480) {
      throw new AppError(400, 'minutesSlept must be between 1 and 480');
    }
    const movementViolations = Math.max(0, Math.floor(movementViolationsRaw));
    const screenOnCount = Math.max(0, Math.floor(screenOnCountRaw));

    // Validate and consume session token (prevents /night/end without /night/start)
    const tokenRows = await query<{ session_id: string; used_at: string | null }>(
      `SELECT session_id, used_at
       FROM night_session_tokens
       WHERE session_id = $1::uuid AND wallet_address = $2
       LIMIT 1`,
      [sessionId, walletAddress]
    );
    if (tokenRows.length === 0) {
      throw new AppError(403, 'Invalid session token — call /night/start first');
    }
    if (tokenRows[0].used_at !== null) {
      throw new AppError(409, 'Session already submitted');
    }

    // Human factor from violations
    let humanFactor = 1.0;
    if (movementViolations > 10 || screenOnCount > 5) {
      humanFactor = 0.3;
    } else if (movementViolations > 3 || screenOnCount > 2) {
      humanFactor = 0.7;
    }

    // Load user data (including staking)
    const users = await query<{
      wallet_address: string;
      has_genesis_nft: boolean;
      referred_by: string | null;
      staked_skr_raw: string | null;
    }>(
      `SELECT wallet_address, has_genesis_nft, referred_by,
              COALESCE(staked_skr_raw, 0)::text AS staked_skr_raw
       FROM users WHERE wallet_address = $1`,
      [walletAddress]
    );
    if (users.length === 0) throw new AppError(404, 'User not found');
    const user = users[0];

    // Active season
    const seasons = await query<{
      season_number: number;
      start_date: string;
      total_weeks: number;
      active_devices: number;
    }>('SELECT season_number, start_date, total_weeks, active_devices FROM season_stats WHERE status = $1', ['ACTIVE']);

    if (seasons.length === 0) {
      throw new AppError(503, 'No active season — mining rewards are paused');
    }
    const season = seasons[0];

    const startDate = new Date(season.start_date);
    const daysDiff = Math.floor((Date.now() - startDate.getTime()) / (1000 * 60 * 60 * 24));
    const weekIndex = Math.max(1, Math.floor(daysDiff / 7) + 1);

    const todayStr = new Date().toISOString().split('T')[0];

    // Referral count
    const referrals = await query<{ count: string }>(
      'SELECT COUNT(*) as count FROM referrals WHERE referrer = $1 AND is_active = true',
      [walletAddress]
    );
    const referralCount = parseInt(referrals[0]?.count || '0');

    // Daily tasks bonus
    const tasks = await query<{ total_bonus_percent: number }>(
      'SELECT total_bonus_percent FROM daily_tasks WHERE wallet_address = $1 AND task_date = $2',
      [walletAddress, todayStr]
    );
    const dailyTasksPercent = tasks[0]?.total_bonus_percent || 0;

    // Active SKR boost
    const boosts = await query<{ boost_level: string }>(
      `SELECT boost_level FROM payments
       WHERE wallet_address = $1
         AND payment_type = 'SKR_BOOST'
         AND verified = true
         AND boost_expires_at > NOW()
       ORDER BY created_at DESC LIMIT 1`,
      [walletAddress]
    );
    const skrBoostLevel = (boosts[0]?.boost_level as SkrBoostLevel) || SkrBoostLevel.NONE;

    const stakedSkrHuman = Number(user.staked_skr_raw ?? 0) / 1_000_000;

    const reward = calculateNightReward({
      minutesSlept,
      humanFactor,
      weekIndex,
      activeDevices: season.active_devices,
      referralCount,
      dailyTasksPercent,
      skrBoostLevel,
      hasGenesisNft: user.has_genesis_nft,
      stakedSkrHuman,
    });

    // Persist atomically: consume token + insert session + update NP
    await transaction(async (client) => {
      // Mark session token as used
      await client.query(
        'UPDATE night_session_tokens SET used_at = NOW() WHERE session_id = $1::uuid',
        [sessionId]
      );

      await client.query(
        `INSERT INTO night_sessions (
          wallet_address, night_date, week_index,
          minutes_slept, human_factor, movement_violations, screen_on_count,
          referral_count, daily_tasks_percent, skr_boost_level, has_genesis_nft,
          base_np, social_boost, skr_boost, nft_multiplier, total_multiplier, final_np,
          session_ended_at
        ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,NOW())
        ON CONFLICT (wallet_address, night_date) DO NOTHING`,
        [
          walletAddress, todayStr, weekIndex,
          minutesSlept, humanFactor, movementViolations, screenOnCount,
          referralCount, dailyTasksPercent, skrBoostLevel, user.has_genesis_nft,
          reward.baseNp, reward.socialBoost, reward.skrBoost,
          reward.nftMultiplier, reward.totalMultiplier, reward.finalNp,
        ]
      );

      await client.query(
        `UPDATE users
         SET total_np = total_np + $1,
             total_nights_mined = total_nights_mined + 1,
             last_active_at = NOW()
         WHERE wallet_address = $2`,
        [reward.finalNp, walletAddress]
      );
    });

    logger.info(`Night session completed`, {
      wallet: walletAddress.slice(0, 8) + '...',
      finalNp: reward.finalNp,
      multiplier: reward.totalMultiplier,
      stakedSkrHuman,
    });

    res.json({
      success: true,
      reward: {
        ...reward,
        message: 'Night session completed! SLEEP tokens will be distributed at 9 AM.',
      },
    });
  } catch (error) {
    next(error);
  }
});

/**
 * GET /api/v1/night/history/:walletAddress
 */
router.get('/history/:walletAddress', async (req, res, next) => {
  try {
    const { walletAddress } = req.params;
    if (!isValidSolanaAddress(walletAddress)) {
      throw new AppError(400, 'invalid wallet format');
    }
    const limit = Math.min(parseInt(req.query.limit as string) || 30, 100);

    const sessions = await query(
      `SELECT night_date, minutes_slept, human_factor,
              base_np, social_boost, skr_boost, nft_multiplier, total_multiplier, final_np,
              sleep_tokens, processed
       FROM night_sessions
       WHERE wallet_address = $1
       ORDER BY night_date DESC
       LIMIT $2`,
      [walletAddress, limit]
    );

    res.json({ success: true, sessions, count: sessions.length });
  } catch (error) {
    next(error);
  }
});

export default router;

function pickNumber(body: Record<string, unknown>, keys: string[]): number | undefined {
  for (const key of keys) {
    const v = body[key];
    if (typeof v === 'number' && Number.isFinite(v)) return v;
    if (typeof v === 'string' && v.trim().length > 0) {
      const parsed = Number(v);
      if (Number.isFinite(parsed)) return parsed;
    }
  }
  return undefined;
}
