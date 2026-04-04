/**
 * POST /api/v1/claim
 * Pre-TGE: locked. Set TGE_ENABLED=true + TGE_DATE=ISO8601 in .env to open.
 * Post-TGE: atomically creates a pending record, sends SLEEP tokens on-chain,
 *           then confirms (or marks failed).
 */

import { Router, Request, Response, NextFunction } from 'express';
import { query, transaction } from '../database';
import { AppError } from '../middleware/errorHandler';
import { logger } from '../utils/logger';
import { isValidSolanaAddress } from '../utils/solanaAddress';
import { sendSleepTokens } from '../utils/solanaTransfer';

const router = Router();

// ── TGE configuration ────────────────────────────────────────────────────────
const TGE_ENABLED = process.env.TGE_ENABLED === 'true';
const TGE_DATE = process.env.TGE_DATE ? new Date(process.env.TGE_DATE) : null;
const isClaimsOpen = (): boolean =>
  TGE_ENABLED && TGE_DATE != null && new Date() >= TGE_DATE;

// ── Auth helper (mirrors night.ts) ───────────────────────────────────────────
function extractAuthToken(req: Request): string | undefined {
  return (
    (req.headers['x-auth-token'] as string | undefined) ||
    (req.body?.authToken as string | undefined) ||
    (req.body?.auth_token as string | undefined)
  );
}

async function requireAuth(walletAddress: string, authToken: string | undefined): Promise<void> {
  if (!authToken) {
    throw new AppError(401, 'Authentication required: provide X-Auth-Token header or authToken in body');
  }
  const tokenUuid = authToken.trim();
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(tokenUuid)) {
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
  const banned = await query<{ is_banned: boolean }>(
    'SELECT COALESCE(is_banned, false) AS is_banned FROM users WHERE wallet_address = $1',
    [walletAddress]
  );
  if (banned[0]?.is_banned) throw new AppError(403, 'Account suspended');
}

// ── POST /api/v1/claim ────────────────────────────────────────────────────────
router.post('/', async (req: Request, res: Response, next: NextFunction) => {
  try {
    const walletAddress = (req.body?.walletAddress || req.body?.wallet) as string | undefined;
    if (!walletAddress) throw new AppError(400, 'walletAddress is required');
    if (!isValidSolanaAddress(walletAddress)) throw new AppError(400, 'Invalid wallet address format');

    // Fetch claimable balance (total_sleep_earned accumulated by distribution scheduler)
    const userRows = await query<{ balance: string; total_np: string }>(
      'SELECT COALESCE(total_sleep_earned, 0) AS balance, COALESCE(total_np, 0) AS total_np FROM users WHERE wallet_address = $1',
      [walletAddress]
    );
    const balance = userRows[0] ? parseInt(String(userRows[0].balance), 10) : 0;
    const totalNp = userRows[0] ? parseFloat(String(userRows[0].total_np)) : 0;

    if (!isClaimsOpen()) {
      const tgeDateStr = TGE_DATE ? TGE_DATE.toISOString() : 'TBD';
      return res.status(503).json({
        success: false,
        error: `Claims open at TGE: ${tgeDateStr}`,
        balance,
        totalNp,
        tgeDate: tgeDateStr,
        tgeEnabled: TGE_ENABLED,
      });
    }

    // Auth required when claims are open
    await requireAuth(walletAddress, extractAuthToken(req));

    if (balance <= 0) {
      return res.status(400).json({
        success: false,
        error: 'No SLEEP tokens to claim',
        balance: 0,
      });
    }

    // Idempotency: reject if a non-failed claim already exists
    const existingClaims = await query<{ id: string; status: string; tx_hash: string | null }>(
      `SELECT id, status, tx_hash FROM token_claims
       WHERE wallet_address = $1 AND status IN ('pending', 'confirmed')
       ORDER BY created_at DESC LIMIT 1`,
      [walletAddress]
    );
    if (existingClaims.length > 0) {
      const existing = existingClaims[0];
      if (existing.status === 'confirmed') {
        return res.status(409).json({
          success: false,
          error: 'Already claimed. All SLEEP tokens have been sent.',
          txHash: existing.tx_hash,
        });
      }
      if (existing.status === 'pending') {
        return res.status(409).json({
          success: false,
          error: 'Claim already in progress. Please wait.',
          claimId: existing.id,
        });
      }
    }

    // Record claim as pending (atomic: lock user row to prevent race conditions)
    let claimId: string;
    await transaction(async (client) => {
      // Lock user row
      await client.query(
        'SELECT wallet_address FROM users WHERE wallet_address = $1 FOR UPDATE',
        [walletAddress]
      );

      // Re-check balance under lock
      const locked = await client.query<{ balance: string }>(
        'SELECT COALESCE(total_sleep_earned, 0) AS balance FROM users WHERE wallet_address = $1',
        [walletAddress]
      );
      const lockedBalance = parseInt(String(locked.rows[0]?.balance ?? '0'), 10);
      if (lockedBalance <= 0) throw new AppError(400, 'No SLEEP tokens to claim');

      const row = await client.query<{ id: string }>(
        'INSERT INTO token_claims (wallet_address, amount) VALUES ($1, $2) RETURNING id',
        [walletAddress, lockedBalance]
      );
      claimId = row.rows[0].id;
    });

    // Send tokens on-chain (outside transaction — can take time)
    let txSignature: string;
    try {
      txSignature = await sendSleepTokens(walletAddress, BigInt(balance));
    } catch (sendError: any) {
      // Mark claim as failed; user can retry
      await query(
        "UPDATE token_claims SET status = 'failed' WHERE id = $1",
        [claimId!]
      );
      logger.error('sendSleepTokens failed', { wallet: walletAddress.slice(0, 8), error: sendError?.message });
      throw new AppError(500, 'Failed to send SLEEP tokens. Please try again later.');
    }

    // Confirm claim and zero out balance
    await transaction(async (client) => {
      await client.query(
        "UPDATE token_claims SET tx_hash = $1, status = 'confirmed' WHERE id = $2",
        [txSignature, claimId!]
      );
      await client.query(
        'UPDATE users SET total_sleep_earned = 0 WHERE wallet_address = $1',
        [walletAddress]
      );
    });

    logger.info('SLEEP claim confirmed', {
      wallet: walletAddress.slice(0, 8) + '...',
      amount: balance,
      txSignature: txSignature.slice(0, 16) + '...',
    });

    res.json({
      success: true,
      amount: balance,
      txHash: txSignature,
      message: `${balance} SLEEP tokens sent to your wallet!`,
    });

  } catch (error) {
    next(error);
  }
});

// ── GET /api/v1/claim/status/:walletAddress ───────────────────────────────────
router.get('/status/:walletAddress', async (req: Request, res: Response, next: NextFunction) => {
  try {
    const { walletAddress } = req.params;
    if (!isValidSolanaAddress(walletAddress)) throw new AppError(400, 'Invalid wallet address format');

    const userRows = await query<{ balance: string; total_np: string }>(
      'SELECT COALESCE(total_sleep_earned, 0) AS balance, COALESCE(total_np, 0) AS total_np FROM users WHERE wallet_address = $1',
      [walletAddress]
    );
    const balance = userRows[0] ? parseInt(String(userRows[0].balance), 10) : 0;
    const totalNp = userRows[0] ? parseFloat(String(userRows[0].total_np)) : 0;
    const tgeDateStr = TGE_DATE ? TGE_DATE.toISOString() : null;

    res.json({
      success: true,
      claimsOpen: isClaimsOpen(),
      tgeDate: tgeDateStr,
      tgeEnabled: TGE_ENABLED,
      balance,
      totalNp,
    });
  } catch (error) {
    next(error);
  }
});

// ── GET /api/v1/claim/history/:walletAddress ──────────────────────────────────
router.get('/history/:walletAddress', async (req: Request, res: Response, next: NextFunction) => {
  try {
    const { walletAddress } = req.params;
    if (!isValidSolanaAddress(walletAddress)) throw new AppError(400, 'Invalid wallet address format');

    const claims = await query(
      `SELECT id, amount, tx_hash, status, created_at
       FROM token_claims
       WHERE wallet_address = $1
       ORDER BY created_at DESC
       LIMIT 20`,
      [walletAddress]
    );

    res.json({ success: true, claims });
  } catch (error) {
    next(error);
  }
});

export default router;
