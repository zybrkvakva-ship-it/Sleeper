import { Router } from 'express';
import { query } from '../database';
import { AppError } from '../middleware/errorHandler';
import { logger } from '../utils/logger';

const router = Router();

/**
 * POST /api/v1/user/register
 * Register or update user
 */
router.post('/register', async (req, res, next) => {
  try {
    const { walletAddress, skrUsername } = req.body;
    
    if (!walletAddress) {
      throw new AppError(400, 'walletAddress is required');
    }
    
    // Generate referral code (8 chars from wallet)
    const referralCode = walletAddress.substring(0, 8);
    
    // Upsert user
    await query(
      `INSERT INTO users (wallet_address, skr_username, referral_code, last_active_at)
       VALUES ($1, $2, $3, NOW())
       ON CONFLICT (wallet_address) 
       DO UPDATE SET 
         skr_username = EXCLUDED.skr_username,
         last_active_at = NOW()`,
      [walletAddress, skrUsername, referralCode]
    );
    
    logger.info(`User registered: ${walletAddress}`);
    
    res.json({
      success: true,
      walletAddress,
      referralCode,
      message: 'User registered successfully'
    });
    
  } catch (error) {
    next(error);
  }
});

/**
 * GET /api/v1/user/balance?wallet=...
 * Get user points balance (SeekerMiner). Returns 0 if user not found.
 * Contract: API_MINING_CONTRACT.md
 */
router.get('/balance', async (req, res, next) => {
  try {
    const wallet = (req.query.wallet as string)?.trim();
    if (!wallet) {
      throw new AppError(400, 'wallet query parameter is required');
    }
    if (wallet.length < 32 || wallet.length > 44) {
      throw new AppError(400, 'invalid wallet format');
    }
    const users = await query<{ points_balance: string }>(
      `SELECT COALESCE(points_balance, 0)::bigint AS points_balance FROM users WHERE wallet_address = $1`,
      [wallet]
    );
    if (users.length === 0) {
      // Contract: "при первом запросе по wallet — инициализация пользователя с балансом 0"
      const referralCode = wallet.substring(0, 8);
      await query(
        `INSERT INTO users (wallet_address, referral_code, points_balance, total_blocks_mined, last_active_at)
         VALUES ($1, $2, 0, 0, NOW())
         ON CONFLICT (wallet_address) DO NOTHING`,
        [wallet, referralCode]
      );
    }
    const after = await query<{ points_balance: string }>(
      `SELECT COALESCE(points_balance, 0)::bigint AS points_balance FROM users WHERE wallet_address = $1`,
      [wallet]
    );
    const balance = after.length > 0 ? parseInt(after[0].points_balance, 10) : 0;
    res.json({ balance });
  } catch (error) {
    next(error);
  }
});

/**
 * GET /api/v1/user/:walletAddress
 * Get user profile
 */
router.get('/:walletAddress', async (req, res, next) => {
  try {
    const { walletAddress } = req.params;
    
    const users = await query(
      `SELECT 
        wallet_address,
        skr_username,
        has_genesis_nft,
        genesis_nft_number,
        total_np,
        total_sleep_earned,
        total_nights_mined,
        referral_code,
        referral_count,
        created_at
      FROM users
      WHERE wallet_address = $1`,
      [walletAddress]
    );
    
    if (users.length === 0) {
      throw new AppError(404, 'User not found');
    }
    
    res.json({
      success: true,
      user: users[0]
    });
    
  } catch (error) {
    next(error);
  }
});

/**
 * POST /api/v1/user/apply-referral
 * Apply referral code
 */
router.post('/apply-referral', async (req, res, next) => {
  try {
    const { walletAddress, referralCode } = req.body;
    
    if (!walletAddress || !referralCode) {
      throw new AppError(400, 'walletAddress and referralCode are required');
    }
    
    // Find referrer
    const referrers = await query(
      'SELECT wallet_address FROM users WHERE referral_code = $1',
      [referralCode]
    );
    
    if (referrers.length === 0) {
      throw new AppError(404, 'Referral code not found');
    }
    
    const referrer = referrers[0].wallet_address;
    
    // Cannot refer yourself
    if (referrer === walletAddress) {
      throw new AppError(400, 'Cannot use your own referral code');
    }
    
    // Check if already referred
    const existing = await query(
      'SELECT * FROM referrals WHERE referee = $1',
      [walletAddress]
    );
    
    if (existing.length > 0) {
      throw new AppError(400, 'Already used a referral code');
    }
    
    // Create referral
    await query(
      'INSERT INTO referrals (referrer, referee) VALUES ($1, $2)',
      [referrer, walletAddress]
    );
    
    // Update user
    await query(
      'UPDATE users SET referred_by = $1 WHERE wallet_address = $2',
      [referrer, walletAddress]
    );
    
    // Increment referrer count
    await query(
      'UPDATE users SET referral_count = referral_count + 1 WHERE wallet_address = $1',
      [referrer]
    );
    
    logger.info(`Referral applied: ${walletAddress} -> ${referrer}`);
    
    res.json({
      success: true,
      message: 'Referral code applied successfully'
    });
    
  } catch (error) {
    next(error);
  }
});

export default router;
