import { Router } from 'express';
import { query } from '../database';
import { AppError } from '../middleware/errorHandler';
import { logger } from '../utils/logger';

const router = Router();

/**
 * POST /api/v1/nft/check-eligibility
 * Check if user can mint Genesis NFT
 * REQUIREMENTS:
 * - Wallet connected & approved
 * - Owns .skr domain
 * - Doesn't already own Genesis NFT
 * - NFT supply not exhausted
 */
router.post('/check-eligibility', async (req, res, next) => {
  try {
    const { walletAddress, skrUsername } = req.body;
    
    if (!walletAddress) {
      throw new AppError(400, 'walletAddress is required');
    }
    
    // Check if user exists and has .skr domain
    const users = await query(
      'SELECT has_genesis_nft, skr_username FROM users WHERE wallet_address = $1',
      [walletAddress]
    );
    
    if (users.length === 0) {
      throw new AppError(404, 'User not found. Please register first.');
    }
    
    const user = users[0];
    
    // Check if already owns NFT
    if (user.has_genesis_nft) {
      return res.json({
        success: false,
        eligible: false,
        reason: 'You already own a Genesis NFT'
      });
    }
    
    // Check if has .skr domain
    if (!user.skr_username || !skrUsername) {
      return res.json({
        success: false,
        eligible: false,
        reason: 'You must own a .skr domain to mint Genesis NFT'
      });
    }
    
    // Check NFT supply
    const nftCount = await query(
      'SELECT COUNT(*) as count FROM users WHERE has_genesis_nft = true'
    );
    const minted = parseInt(nftCount[0]?.count || '0');
    
    if (minted >= 10000) {
      return res.json({
        success: false,
        eligible: false,
        reason: 'Genesis NFT supply exhausted (10,000/10,000)'
      });
    }
    
    res.json({
      success: true,
      eligible: true,
      skrUsername: user.skr_username,
      nftsMinted: minted,
      nftsRemaining: 10000 - minted
    });
    
  } catch (error) {
    next(error);
  }
});

/**
 * GET /api/v1/nft/supply
 * Get current NFT supply stats
 */
router.get('/supply', async (req, res, next) => {
  try {
    const result = await query(
      'SELECT COUNT(*) as count FROM users WHERE has_genesis_nft = true'
    );
    const minted = parseInt(result[0]?.count || '0');
    
    res.json({
      success: true,
      total: 10000,
      minted,
      remaining: 10000 - minted
    });
    
  } catch (error) {
    next(error);
  }
});

/**
 * POST /api/v1/nft/mint
 * Mint Genesis NFT (to be implemented with Solana smart contract)
 * This endpoint will be completed in Phase 2
 */
router.post('/mint', async (req, res, next) => {
  try {
    // TODO: Implement full minting logic with Solana smart contract
    res.status(501).json({
      success: false,
      error: 'NFT minting not yet implemented. Coming in Phase 2.'
    });
    
  } catch (error) {
    next(error);
  }
});

export default router;
