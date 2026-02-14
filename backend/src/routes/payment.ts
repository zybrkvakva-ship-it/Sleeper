import { Router } from 'express';
import { query } from '../database';
import { AppError } from '../middleware/errorHandler';
import { logger } from '../utils/logger';

const router = Router();

/**
 * POST /api/v1/payment/verify-skr
 * Verify SKR boost payment
 * This endpoint will be fully implemented in Phase 2
 */
router.post('/verify-skr', async (req, res, next) => {
  try {
    const { txHash, walletAddress, boostLevel } = req.body;
    
    if (!txHash || !walletAddress || !boostLevel) {
      throw new AppError(400, 'Missing required fields');
    }
    
    // TODO: Verify transaction on Solana blockchain
    // - Get transaction by hash
    // - Verify sender is walletAddress
    // - Verify amount matches boost price
    // - Verify recipient is TREASURY_WALLET
    
    logger.info(`SKR payment verification requested`, {
      txHash,
      walletAddress,
      boostLevel
    });
    
    res.status(501).json({
      success: false,
      error: 'SKR payment verification not yet implemented. Coming in Phase 2.'
    });
    
  } catch (error) {
    next(error);
  }
});

/**
 * GET /api/v1/payment/active-boost/:walletAddress
 * Get user's active SKR boost
 */
router.get('/active-boost/:walletAddress', async (req, res, next) => {
  try {
    const { walletAddress } = req.params;
    
    const boosts = await query(
      `SELECT 
        boost_level,
        boost_expires_at,
        created_at
      FROM payments
      WHERE wallet_address = $1
        AND payment_type = 'SKR_BOOST'
        AND verified = true
        AND boost_expires_at > NOW()
      ORDER BY created_at DESC
      LIMIT 1`,
      [walletAddress]
    );
    
    if (boosts.length === 0) {
      return res.json({
        success: true,
        hasActiveBoost: false
      });
    }
    
    res.json({
      success: true,
      hasActiveBoost: true,
      boost: boosts[0]
    });
    
  } catch (error) {
    next(error);
  }
});

export default router;
