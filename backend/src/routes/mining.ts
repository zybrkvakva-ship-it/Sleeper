import { Router } from 'express';
import { query, transaction } from '../database';
import { AppError } from '../middleware/errorHandler';
import { logger } from '../utils/logger';
import { isValidSolanaAddress, pickFirstString, pickWallet } from '../utils/solanaAddress';

const router = Router();
const BASE_POINTS_PER_SECOND = 0.2;
const MAX_SESSION_MINUTES = 480;
const REQUIRE_MINING_AUTH = (process.env.REQUIRE_MINING_AUTH || 'true').toLowerCase() !== 'false';

/**
 * POST /api/v1/mining/session
 * Register a mining session (Sleeper app). Creates user if not exists.
 * Contract: API_MINING_CONTRACT.md
 */
router.post('/session', async (req, res, next) => {
  try {
    const body = (req.body || {}) as Record<string, unknown>;

    const walletRaw = pickWallet(body);
    if (!walletRaw) {
      throw new AppError(400, 'wallet (or walletAddress) is required');
    }
    const wallet = walletRaw.trim();
    if (!isValidSolanaAddress(wallet)) {
      throw new AppError(400, 'invalid wallet format');
    }

    const authToken = pickFirstString(body, ['auth_token', 'authToken']) || '';
    if (REQUIRE_MINING_AUTH && !authToken) {
      throw new AppError(401, 'auth_token (or authToken) is required');
    }

    const startTs = Math.floor(pickNumber(body, ['session_started_at', 'sessionStartedAt'], 0));
    const endTs = Math.floor(pickNumber(body, ['session_ended_at', 'sessionEndedAt'], 0));
    if (!Number.isFinite(startTs) || !Number.isFinite(endTs) || endTs <= startTs) {
      throw new AppError(400, 'invalid session timestamps');
    }

    const durationSecondsByTs = Math.floor((endTs - startTs) / 1000);
    const durationSeconds = Math.max(0, durationSecondsByTs);
    const durationMinutesByTs = Math.floor(durationSeconds / 60);
    const uptimeMinutesRaw = Math.floor(pickNumber(body, ['uptime_minutes', 'uptimeMinutes'], 0));
    const uptimeMinutes = Math.max(
      0,
      Math.min(MAX_SESSION_MINUTES, Math.min(uptimeMinutesRaw, durationMinutesByTs + 1))
    );

    const storageMb = Math.max(0, Math.floor(pickNumber(body, ['storage_mb', 'storageMb'], 0)));
    const storageMultiplier = calcStorageMultiplier(storageMb);
    const stakeMultiplier = calcStakeMultiplier(pickNumber(body, ['staked_skr_human', 'stakedSkrHuman'], 0));
    const humanMultiplier = calcHumanMultiplier(
      Math.max(0, Math.floor(pickNumber(body, ['human_checks_passed', 'humanChecksPassed'], 0))),
      Math.max(0, Math.floor(pickNumber(body, ['human_checks_failed', 'humanChecksFailed'], 0)))
    );
    const dailySocialMultiplier = 1.0 + clamp(pickNumber(body, ['daily_social_bonus_percent', 'dailySocialBonusPercent'], 0), 0.0, 0.15);
    const paidBoostMultiplier = normalizePaidBoostMultiplier(pickNumber(body, ['paid_boost_multiplier', 'paidBoostMultiplier'], 1));
    const hasGenesisNft = pickBoolean(body, ['has_genesis_nft', 'hasGenesisNft'], false);
    const genesisNftMultiplier = hasGenesisNft
      ? clamp(pickNumber(body, ['genesis_nft_multiplier', 'genesisNftMultiplier'], 1.1), 1.0, 1.5)
      : 1.0;

    const pointsPerSecondServer =
      BASE_POINTS_PER_SECOND *
      storageMultiplier *
      stakeMultiplier *
      humanMultiplier *
      dailySocialMultiplier *
      paidBoostMultiplier *
      genesisNftMultiplier;

    const pointsPerSecondClient = clamp(pickNumber(body, ['points_per_second', 'pointsPerSecond'], 0), 0, 3.2);
    const effectivePointsPerSecond = pointsPerSecondClient > 0
      ? Math.min(pointsPerSecondClient, pointsPerSecondServer)
      : pointsPerSecondServer;

    const sessionPointsEarned = Math.floor(effectivePointsPerSecond * durationSeconds);

    const txResult = await transaction(async (client) => {
      if (REQUIRE_MINING_AUTH) {
        const tokenRows = await client.query(
          `SELECT token
           FROM wallet_auth_tokens
           WHERE token = $1
             AND wallet_address = $2
             AND revoked_at IS NULL
             AND expires_at > NOW()
           LIMIT 1`,
          [authToken, wallet]
        );
        if (tokenRows.rows.length === 0) {
          throw new AppError(401, 'invalid or expired auth_token');
        }
      }

      // Ensure user exists (create with 0 balance if not)
      const referralCode = wallet.substring(0, 8);
      const skr = pickFirstString(body, ['skr', 'skrUsername']) || null;
      await client.query(
        `INSERT INTO users (wallet_address, skr_username, referral_code, points_balance, total_blocks_mined, last_active_at)
         VALUES ($1, $2, $3, 0, 0, NOW())
         ON CONFLICT (wallet_address) DO UPDATE SET
           skr_username = COALESCE(EXCLUDED.skr_username, users.skr_username),
           last_active_at = NOW()`,
        [wallet, skr, referralCode]
      );

      // Idempotency: if the same session window already exists, return existing row and keep balance unchanged.
      const existingSession = await client.query<{ id: string }>(
        `SELECT id FROM mining_sessions
         WHERE wallet_address = $1 AND session_started_at = $2 AND session_ended_at = $3
         LIMIT 1`,
        [wallet, startTs, endTs]
      );
      if (existingSession.rows.length > 0) {
        return {
          sessionId: existingSession.rows[0].id,
          duplicate: true,
        };
      }

      const currentBalanceRows = await client.query<{ points_balance: string }>(
        `SELECT COALESCE(points_balance, 0)::bigint AS points_balance
         FROM users WHERE wallet_address = $1
         FOR UPDATE`,
        [wallet]
      );
      const currentBalance = currentBalanceRows.rows.length > 0
        ? parseInt(currentBalanceRows.rows[0].points_balance, 10)
        : 0;
      const nextBalance = currentBalance + Math.max(0, sessionPointsEarned);

      // Insert mining session (store server-validated values)
      const sessionResult = await client.query(
        `INSERT INTO mining_sessions (
          wallet_address, skr, uptime_minutes, storage_mb, storage_multiplier,
          stake_multiplier, paid_boost_multiplier, daily_social_multiplier,
          points_balance, session_started_at, session_ended_at, device_fingerprint,
          genesis_nft_multiplier, active_skr_boost_id
        ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)
        RETURNING id`,
        [
          wallet,
          skr,
          uptimeMinutes,
          storageMb,
          storageMultiplier,
          stakeMultiplier,
          paidBoostMultiplier,
          dailySocialMultiplier,
          nextBalance,
          startTs,
          endTs,
          pickFirstString(body, ['device_fingerprint', 'deviceFingerprint']) ?? null,
          genesisNftMultiplier,
          pickFirstString(body, ['active_skr_boost_id', 'activeSkrBoostId']) ?? null,
        ]
      );
      const sessionId = sessionResult.rows[0]?.id;

      // Update user balance from server-calculated reward only
      await client.query(
        `UPDATE users SET points_balance = $1, total_blocks_mined = COALESCE(total_blocks_mined, 0) + 1, last_active_at = NOW() WHERE wallet_address = $2`,
        [nextBalance, wallet]
      );

      return {
        sessionId,
        duplicate: false,
      };
    });

    const balanceRows = await query<{ points_balance: string }>(
      'SELECT points_balance FROM users WHERE wallet_address = $1',
      [wallet]
    );
    const balance = balanceRows.length > 0 ? parseInt(balanceRows[0].points_balance, 10) : 0;

    logger.info('Mining session recorded', {
      wallet: wallet.slice(0, 8) + '…',
      sessionId: txResult.sessionId,
      balance,
      duplicate: txResult.duplicate,
    });

    res.status(200).json({
      session_id: txResult.sessionId,
      balance,
      points_earned: txResult.duplicate ? 0 : Math.max(0, sessionPointsEarned),
      points_per_second: effectivePointsPerSecond,
      points_per_second_server: pointsPerSecondServer,
      multipliers: {
        storage_multiplier: storageMultiplier,
        stake_multiplier: stakeMultiplier,
        human_multiplier: humanMultiplier,
        daily_social_multiplier: dailySocialMultiplier,
        paid_boost_multiplier: paidBoostMultiplier,
        genesis_nft_multiplier: genesisNftMultiplier,
      },
      duplicate: txResult.duplicate,
    });
  } catch (error) {
    next(error);
  }
});

function clamp(v: number, min: number, max: number): number {
  if (!Number.isFinite(v)) return min;
  return Math.max(min, Math.min(max, v));
}

function calcStorageMultiplier(storageMb: number): number {
  const minStorage = 100;
  const maxStorage = 600;
  const minMult = 1.0;
  const maxMult = 3.0;
  if (storageMb <= minStorage) return minMult;
  if (storageMb >= maxStorage) return maxMult;
  const progress = Math.log(storageMb - minStorage + 1) / Math.log(maxStorage - minStorage + 1);
  return minMult + progress * (maxMult - minMult);
}

function calcStakeMultiplier(stakedSkrHuman: number): number {
  if (stakedSkrHuman >= 10_000) return 1.5;
  if (stakedSkrHuman >= 1_000) return 1.2;
  return 1.0;
}

function calcHumanMultiplier(passed: number, failed: number): number {
  const total = passed + failed;
  if (total <= 0) return 1.0;
  const passRate = passed / total;
  return 0.5 + passRate * 0.5;
}

function normalizePaidBoostMultiplier(v: number): number {
  const allowed = [1.0, 1.05, 1.1, 1.5, 2.0];
  const nearest = allowed.reduce((best, cur) => {
    const bestDiff = Math.abs(best - v);
    const curDiff = Math.abs(cur - v);
    return curDiff < bestDiff ? cur : best;
  }, 1.0);
  return nearest;
}

function pickNumber(body: Record<string, unknown>, keys: string[], fallback: number): number {
  for (const key of keys) {
    const v = body[key];
    if (typeof v === 'number' && Number.isFinite(v)) return v;
    if (typeof v === 'string' && v.trim().length > 0) {
      const parsed = Number(v);
      if (Number.isFinite(parsed)) return parsed;
    }
  }
  return fallback;
}

function pickBoolean(body: Record<string, unknown>, keys: string[], fallback: boolean): boolean {
  for (const key of keys) {
    const v = body[key];
    if (typeof v === 'boolean') return v;
  }
  return fallback;
}

export default router;
