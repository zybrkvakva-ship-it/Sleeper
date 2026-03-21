/**
 * POST /api/v1/claim
 * Allows a user to claim their earned SLEEP tokens on-chain.
 *
 * Flow:
 *  1. Validate wallet + auth_token
 *  2. Check user balance >= MIN_CLAIM_AMOUNT
 *  3. Enforce 24-hour cooldown between claims
 *  4. Insert pending claim record (idempotency guard)
 *  5. Execute SPL transfer treasury → user wallet
 *  6. Mark claim confirmed and decrement user balance
 */

import { Router, Request, Response, NextFunction } from 'express';
import { query, transaction as dbTransaction } from '../database';
import { AppError } from '../middleware/errorHandler';
import { logger } from '../utils/logger';
import { isValidSolanaAddress } from '../utils/solanaAddress';
import { sendSleepTokens } from '../utils/solanaTransfer';

const router = Router();

const MIN_CLAIM_AMOUNT = parseInt(String(process.env.MIN_CLAIM_AMOUNT || '100'), 10);
const CLAIM_COOLDOWN_MS = parseInt(String(process.env.CLAIM_COOLDOWN_HOURS || '24'), 10) * 3_600_000;

/**
 * POST /api/v1/claim
 */
router.post('/', async (req: Request, res: Response, next: NextFunction) => {
  try {
    const { walletAddress, authToken } = req.body as {
      walletAddress?: string;
      authToken?: string;
    };

    if (!walletAddress || !authToken) {
      throw new AppError(400, 'Missing required fields: walletAddress, authToken');
    }
    if (!isValidSolanaAddress(walletAddress)) {
      throw new AppError(400, 'Invalid wallet address format');
    }

    // Validate auth token (must be a recent challenge-response token stored in DB)
    const tokenRows = await query(
      `SELECT wallet_address FROM auth_challenges
       WHERE wallet_address = $1 AND token = $2 AND expires_at > NOW()
       LIMIT 1`,
      [walletAddress, authToken]
    );
    if (tokenRows.length === 0) {
      throw new AppError(401, 'Invalid or expired auth token');
    }

    // Load user balance
    const userRows = await query(
      'SELECT total_sleep_earned FROM users WHERE wallet_address = $1',
      [walletAddress]
    );
    if (userRows.length === 0) {
      throw new AppError(404, 'User not found');
    }
    const balance = parseInt(String(userRows[0].total_sleep_earned), 10);
    if (balance < MIN_CLAIM_AMOUNT) {
      throw new AppError(400, `Minimum claim amount is ${MIN_CLAIM_AMOUNT} SLEEP. Current balance: ${balance}`);
    }

    // Enforce cooldown
    const recentClaim = await query(
      `SELECT created_at FROM token_claims
       WHERE wallet_address = $1 AND status IN ('pending', 'confirmed')
       ORDER BY created_at DESC LIMIT 1`,
      [walletAddress]
    );
    if (recentClaim.length > 0) {
      const lastClaimAt = new Date(recentClaim[0].created_at).getTime();
      const elapsed = Date.now() - lastClaimAt;
      if (elapsed < CLAIM_COOLDOWN_MS) {
        const remainingHours = Math.ceil((CLAIM_COOLDOWN_MS - elapsed) / 3_600_000);
        throw new AppError(429, `Claim cooldown active. Try again in ${remainingHours}h`);
      }
    }

    // Check SLEEP_TOKEN_MINT and TREASURY_KEYPAIR are configured before inserting
    if (!process.env.SLEEP_TOKEN_MINT || !process.env.TREASURY_KEYPAIR) {
      throw new AppError(503, 'Token claim is not configured on this server');
    }

    // Insert pending claim
    let claimId: string;
    await dbTransaction(async (client) => {
      const inserted = await client.query(
        `INSERT INTO token_claims (wallet_address, amount, status)
         VALUES ($1, $2, 'pending')
         RETURNING id`,
        [walletAddress, balance]
      );
      claimId = inserted.rows[0].id;
    });

    // Execute on-chain transfer (outside DB transaction to avoid long-held locks)
    let txSignature: string;
    try {
      txSignature = await sendSleepTokens(walletAddress, BigInt(balance));
    } catch (err) {
      // Mark claim as failed so user can retry
      await query(
        `UPDATE token_claims SET status = 'failed' WHERE id = $1`,
        [claimId!]
      );
      logger.error('SPL transfer failed', { walletAddress: walletAddress.slice(0, 8) + '...', err });
      throw new AppError(502, 'On-chain transfer failed. Please try again later.');
    }

    // Confirm claim and decrement user balance atomically
    await dbTransaction(async (client) => {
      await client.query(
        `UPDATE token_claims SET status = 'confirmed', tx_hash = $1 WHERE id = $2`,
        [txSignature, claimId!]
      );
      await client.query(
        `UPDATE users SET total_sleep_earned = total_sleep_earned - $1 WHERE wallet_address = $2`,
        [balance, walletAddress]
      );
    });

    logger.info('Claim confirmed', {
      wallet: walletAddress.slice(0, 8) + '...',
      amount: balance,
      txHash: txSignature.slice(0, 16) + '...',
    });

    res.json({
      success: true,
      txHash: txSignature,
      amountClaimed: balance,
      remainingBalance: 0,
    });

  } catch (error) {
    next(error);
  }
});

/**
 * GET /api/v1/claim/history/:walletAddress
 */
router.get('/history/:walletAddress', async (req: Request, res: Response, next: NextFunction) => {
  try {
    const { walletAddress } = req.params;
    if (!isValidSolanaAddress(walletAddress)) {
      throw new AppError(400, 'Invalid wallet address format');
    }

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
