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

// TGE configuration from environment
const TGE_ENABLED = process.env.TGE_ENABLED === 'true';
const TGE_DATE = process.env.TGE_DATE ? new Date(process.env.TGE_DATE) : null;
const isClaimsOpen = (): boolean =>
  TGE_ENABLED && TGE_DATE != null && new Date() >= TGE_DATE;

/**
 * POST /api/v1/claim
 * Locked until TGE. Set TGE_ENABLED=true and TGE_DATE=ISO8601 in .env to open.
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
    const balance = userRows[0] ? parseInt(String(userRows[0].balance), 10) : 0;

    if (!isClaimsOpen()) {
      const tgeDateStr = TGE_DATE ? TGE_DATE.toISOString() : 'TBD';
      return res.status(503).json({
        success: false,
        error: `Claims open at TGE: ${tgeDateStr}`,
        balance,
        tgeDate: tgeDateStr,
        tgeEnabled: TGE_ENABLED,
      });
    }

    // TGE is open — real claim logic goes here
    // TODO: implement sendSleepTokens() flow when TGE goes live
    res.status(503).json({
      success: false,
      error: 'Claim processing not yet implemented',
      balance,
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
