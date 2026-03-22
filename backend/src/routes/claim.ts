/**
 * POST /api/v1/claim
 * Pre-TGE: claims are locked. Users accumulate in-game points/NP.
 * Real SPL token distribution happens at TGE after season end.
 * sendSleepTokens() is preserved in solanaTransfer.ts for TGE use.
 */

import { Router, Request, Response, NextFunction } from 'express';
import { query } from '../database';
import { AppError } from '../middleware/errorHandler';
import { isValidSolanaAddress } from '../utils/solanaAddress';

const router = Router();

/**
 * POST /api/v1/claim
 * Locked until TGE. Returns current accumulated balance.
 */
router.post('/', async (req: Request, res: Response, next: NextFunction) => {
  try {
    const walletAddress = (req.body?.walletAddress || req.body?.wallet) as string | undefined;

    if (!walletAddress) {
      throw new AppError(400, 'walletAddress is required');
    }
    if (!isValidSolanaAddress(walletAddress)) {
      throw new AppError(400, 'Invalid wallet address format');
    }

    const userRows = await query(
      'SELECT COALESCE(total_sleep_earned, 0) AS balance FROM users WHERE wallet_address = $1',
      [walletAddress]
    );

    res.status(503).json({
      success: false,
      error: 'Claims are locked until TGE. Your balance is preserved.',
      balance: userRows[0] ? parseInt(String(userRows[0].balance), 10) : 0,
      tgeInfo: 'Token Generation Event will open claims at season end.',
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
