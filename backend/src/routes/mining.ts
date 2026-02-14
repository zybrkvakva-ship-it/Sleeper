import { Router } from 'express';
import { query, transaction } from '../database';
import { AppError } from '../middleware/errorHandler';
import { logger } from '../utils/logger';

const router = Router();

/**
 * POST /api/v1/mining/session
 * Register a mining session (SeekerMiner app). Creates user if not exists.
 * Contract: API_MINING_CONTRACT.md
 */
router.post('/session', async (req, res, next) => {
  try {
    const body = req.body as {
      wallet: string;
      skr?: string | null;
      uptime_minutes: number;
      storage_mb: number;
      storage_multiplier: number;
      stake_multiplier: number;
      paid_boost_multiplier: number;
      daily_social_multiplier: number;
      points_balance: number;
      session_started_at: number;
      session_ended_at: number;
      device_fingerprint?: string | null;
      genesis_nft_multiplier?: number;
      active_skr_boost_id?: string | null;
    };

    if (!body.wallet || typeof body.wallet !== 'string') {
      throw new AppError(400, 'wallet is required');
    }
    const wallet = body.wallet.trim();
    if (wallet.length < 32 || wallet.length > 44) {
      throw new AppError(400, 'invalid wallet format');
    }

    const sessionId = await transaction(async (client) => {
      // Ensure user exists (create with 0 balance if not)
      const referralCode = wallet.substring(0, 8);
      await client.query(
        `INSERT INTO users (wallet_address, skr_username, referral_code, points_balance, total_blocks_mined, last_active_at)
         VALUES ($1, $2, $3, 0, 0, NOW())
         ON CONFLICT (wallet_address) DO UPDATE SET
           skr_username = COALESCE(EXCLUDED.skr_username, users.skr_username),
           last_active_at = NOW()`,
        [wallet, body.skr || null, referralCode]
      );

      // Insert mining session
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
          body.skr ?? null,
          body.uptime_minutes ?? 0,
          body.storage_mb ?? 0,
          body.storage_multiplier ?? 1,
          body.stake_multiplier ?? 1,
          body.paid_boost_multiplier ?? 1,
          body.daily_social_multiplier ?? 1,
          Math.max(0, Math.floor(body.points_balance ?? 0)),
          body.session_started_at ?? 0,
          body.session_ended_at ?? 0,
          body.device_fingerprint ?? null,
          body.genesis_nft_multiplier ?? 1,
          body.active_skr_boost_id ?? null,
        ]
      );
      const sessionId = sessionResult.rows[0]?.id;

      // Update user balance (source of truth: accept client-reported balance for MVP)
      const balance = Math.max(0, Math.floor(body.points_balance ?? 0));
      await client.query(
        `UPDATE users SET points_balance = $1, total_blocks_mined = COALESCE(total_blocks_mined, 0) + 1, last_active_at = NOW() WHERE wallet_address = $2`,
        [balance, wallet]
      );

      return sessionId;
    });

    const balanceRows = await query<{ points_balance: string }>(
      'SELECT points_balance FROM users WHERE wallet_address = $1',
      [wallet]
    );
    const balance = balanceRows.length > 0 ? parseInt(balanceRows[0].points_balance, 10) : 0;

    logger.info('Mining session recorded', { wallet: wallet.slice(0, 8) + '…', sessionId, balance });

    res.status(200).json({
      session_id: sessionId,
      balance,
    });
  } catch (error) {
    next(error);
  }
});

export default router;
