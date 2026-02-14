import { Router } from 'express';
import { v4 as uuidv4 } from 'uuid';
import { query, transaction } from '../database';
import { calculateNightReward, currentWeeks } from '../economy';
import { SkrBoostLevel } from '../economy/constants';
import { AppError } from '../middleware/errorHandler';
import { logger } from '../utils/logger';

const router = Router();

/**
 * POST /api/v1/night/start
 * Start a new night session
 */
router.post('/start', async (req, res, next) => {
  try {
    const { walletAddress } = req.body;
    
    if (!walletAddress) {
      throw new AppError(400, 'walletAddress is required');
    }
    
    // Check if session already exists for today
    const today = new Date().toISOString().split('T')[0];
    const existing = await query(
      'SELECT id FROM night_sessions WHERE wallet_address = $1 AND night_date = $2',
      [walletAddress, today]
    );
    
    if (existing.length > 0) {
      throw new AppError(400, 'Night session already exists for today');
    }
    
    // Create session
    const sessionId = uuidv4();
    
    res.json({
      success: true,
      sessionId,
      nightDate: today,
      message: 'Night session started'
    });
    
  } catch (error) {
    next(error);
  }
});

/**
 * POST /api/v1/night/end
 * Complete and process night session
 */
router.post('/end', async (req, res, next) => {
  try {
    const {
      walletAddress,
      minutesSlept,
      storageMb,
      movementViolations = 0,
      screenOnCount = 0
    } = req.body;
    
    // Validate inputs
    if (!walletAddress || minutesSlept == null || storageMb == null) {
      throw new AppError(400, 'Missing required fields');
    }
    
    // Calculate human factor based on violations
    let humanFactor = 1.0;
    if (movementViolations > 10 || screenOnCount > 5) {
      humanFactor = 0.3;
    } else if (movementViolations > 3 || screenOnCount > 2) {
      humanFactor = 0.7;
    }
    
    // Get user data
    const users = await query(
      `SELECT 
        wallet_address,
        has_genesis_nft,
        referred_by
      FROM users 
      WHERE wallet_address = $1`,
      [walletAddress]
    );
    
    if (users.length === 0) {
      throw new AppError(404, 'User not found');
    }
    
    const user = users[0];
    
    // Get active season
    const seasons = await query(
      'SELECT season_number, start_date, total_weeks, active_devices FROM season_stats WHERE status = $1',
      ['ACTIVE']
    );
    
    if (seasons.length === 0) {
      throw new AppError(500, 'No active season');
    }
    
    const season = seasons[0];
    
    // Calculate week index
    const startDate = new Date(season.start_date);
    const today = new Date();
    const daysDiff = Math.floor((today.getTime() - startDate.getTime()) / (1000 * 60 * 60 * 24));
    const weekIndex = Math.max(1, Math.floor(daysDiff / 7) + 1);
    
    // Get referral count
    const referrals = await query(
      'SELECT COUNT(*) as count FROM referrals WHERE referrer = $1 AND is_active = true',
      [walletAddress]
    );
    const referralCount = parseInt(referrals[0]?.count || '0');
    
    // Get daily tasks bonus
    const todayStr = new Date().toISOString().split('T')[0];
    const tasks = await query(
      'SELECT total_bonus_percent FROM daily_tasks WHERE wallet_address = $1 AND task_date = $2',
      [walletAddress, todayStr]
    );
    const dailyTasksPercent = tasks[0]?.total_bonus_percent || 0;
    
    // Get active SKR boost
    const boosts = await query(
      `SELECT boost_level FROM payments 
       WHERE wallet_address = $1 
       AND payment_type = 'SKR_BOOST' 
       AND verified = true 
       AND boost_expires_at > NOW()
       ORDER BY created_at DESC
       LIMIT 1`,
      [walletAddress]
    );
    const skrBoostLevel = (boosts[0]?.boost_level as SkrBoostLevel) || SkrBoostLevel.NONE;
    
    // Calculate rewards
    const reward = calculateNightReward({
      minutesSlept,
      storageMb,
      humanFactor,
      weekIndex,
      activeDevices: season.active_devices,
      referralCount,
      dailyTasksPercent,
      skrBoostLevel,
      hasGenesisNft: user.has_genesis_nft
    });
    
    // Save to database
    await transaction(async (client) => {
      // Insert night session
      await client.query(
        `INSERT INTO night_sessions (
          wallet_address, night_date, week_index,
          minutes_slept, storage_mb, human_factor, movement_violations, screen_on_count,
          referral_count, daily_tasks_percent, skr_boost_level, has_genesis_nft,
          base_np, social_boost, skr_boost, nft_multiplier, total_multiplier, final_np,
          session_ended_at
        ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, NOW())`,
        [
          walletAddress,
          todayStr,
          weekIndex,
          minutesSlept,
          storageMb,
          humanFactor,
          movementViolations,
          screenOnCount,
          referralCount,
          dailyTasksPercent,
          skrBoostLevel,
          user.has_genesis_nft,
          reward.baseNp,
          reward.socialBoost,
          reward.skrBoost,
          reward.nftMultiplier,
          reward.totalMultiplier,
          reward.finalNp
        ]
      );
      
      // Update user total NP
      await client.query(
        `UPDATE users 
         SET total_np = total_np + $1, 
             total_nights_mined = total_nights_mined + 1,
             last_active_at = NOW()
         WHERE wallet_address = $2`,
        [reward.finalNp, walletAddress]
      );
    });
    
    logger.info(`Night session completed for ${walletAddress}`, {
      finalNp: reward.finalNp,
      multiplier: reward.totalMultiplier
    });
    
    res.json({
      success: true,
      reward: {
        ...reward,
        message: 'Night session completed! SLEEP tokens will be distributed at 9 AM.'
      }
    });
    
  } catch (error) {
    next(error);
  }
});

/**
 * GET /api/v1/night/history/:walletAddress
 * Get user's night history
 */
router.get('/history/:walletAddress', async (req, res, next) => {
  try {
    const { walletAddress } = req.params;
    const limit = parseInt(req.query.limit as string) || 30;
    
    const sessions = await query(
      `SELECT 
        night_date,
        minutes_slept,
        storage_mb,
        human_factor,
        base_np,
        social_boost,
        skr_boost,
        nft_multiplier,
        total_multiplier,
        final_np,
        sleep_tokens,
        processed
      FROM night_sessions
      WHERE wallet_address = $1
      ORDER BY night_date DESC
      LIMIT $2`,
      [walletAddress, limit]
    );
    
    res.json({
      success: true,
      sessions,
      count: sessions.length
    });
    
  } catch (error) {
    next(error);
  }
});

export default router;
