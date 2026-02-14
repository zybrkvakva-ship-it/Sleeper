import { query, transaction } from '../database';
import { poolPerNight, distributeSleepTokens } from '../economy';
import { broadcast } from '../websocket';
import { logger } from '../utils/logger';

/**
 * Daily SLEEP token distribution scheduler
 * Runs every day at 9:00 AM to distribute tokens for previous night
 */

let distributionInterval: NodeJS.Timeout | null = null;

export function startDistributionScheduler() {
  // Run distribution check every hour
  distributionInterval = setInterval(async () => {
    const now = new Date();
    const hour = now.getHours();
    
    // Run at 9 AM
    if (hour === 9) {
      await runDailyDistribution();
    }
  }, 60 * 60 * 1000); // Check every hour
  
  logger.info('Distribution scheduler started (runs daily at 9 AM)');
  
  // Also run on startup if needed (for testing)
  if (process.env.NODE_ENV === 'development') {
    logger.info('Development mode: distribution can be triggered manually');
  }
}

export function stopDistributionScheduler() {
  if (distributionInterval) {
    clearInterval(distributionInterval);
    distributionInterval = null;
    logger.info('Distribution scheduler stopped');
  }
}

/**
 * Run daily SLEEP distribution for previous night
 */
export async function runDailyDistribution(): Promise<void> {
  try {
    logger.info('🎁 Starting daily SLEEP distribution...');
    
    // Get yesterday's date
    const yesterday = new Date();
    yesterday.setDate(yesterday.getDate() - 1);
    const yesterdayStr = yesterday.toISOString().split('T')[0];
    
    // Check if already distributed
    const existing = await query(
      'SELECT id FROM night_distributions WHERE night_date = $1',
      [yesterdayStr]
    );
    
    if (existing.length > 0) {
      logger.info(`Distribution for ${yesterdayStr} already completed`);
      return;
    }
    
    // Get all unprocessed sessions for yesterday
    const sessions = await query(
      `SELECT 
        wallet_address,
        final_np
      FROM night_sessions
      WHERE night_date = $1 AND processed = false`,
      [yesterdayStr]
    );
    
    if (sessions.length === 0) {
      logger.info(`No sessions to process for ${yesterdayStr}`);
      return;
    }
    
    logger.info(`Processing ${sessions.length} night sessions for ${yesterdayStr}`);
    
    // Build users NP map
    const usersNp: Record<string, number> = {};
    let totalNp = 0;
    
    for (const session of sessions) {
      usersNp[session.wallet_address] = session.final_np;
      totalNp += session.final_np;
    }
    
    // Get pool for night
    const activeDevices = sessions.length;
    const poolNight = poolPerNight(activeDevices);
    
    logger.info(`Total NP: ${totalNp.toFixed(2)}, Pool: ${poolNight} SLEEP`);
    
    // Distribute tokens
    const rewards = distributeSleepTokens(usersNp, poolNight);
    
    // Calculate total distributed
    const totalDistributed = Object.values(rewards).reduce((sum, tokens) => sum + tokens, 0);
    
    // Update database in transaction
    await transaction(async (client) => {
      // Update night sessions with SLEEP tokens
      for (const [walletAddress, sleepTokens] of Object.entries(rewards)) {
        await client.query(
          `UPDATE night_sessions 
           SET sleep_tokens = $1, processed = true, processed_at = NOW()
           WHERE wallet_address = $2 AND night_date = $3`,
          [sleepTokens, walletAddress, yesterdayStr]
        );
        
        // Update user total SLEEP earned
        await client.query(
          'UPDATE users SET total_sleep_earned = total_sleep_earned + $1 WHERE wallet_address = $2',
          [sleepTokens, walletAddress]
        );
      }
      
      // Record distribution
      await client.query(
        `INSERT INTO night_distributions (
          night_date, total_np, pool_night, total_distributed, users_count, active_devices, season_number, week_index
        ) VALUES ($1, $2, $3, $4, $5, $6, 1, 1)`,
        [yesterdayStr, totalNp, poolNight, totalDistributed, sessions.length, activeDevices]
      );
      
      // Refresh leaderboard
      await client.query('SELECT refresh_leaderboard()');
    });
    
    logger.info(`✅ Distribution completed: ${totalDistributed} SLEEP to ${sessions.length} users`);
    
    // Broadcast to connected clients
    broadcast({
      type: 'sleep-distributed',
      date: yesterdayStr,
      totalNp,
      poolNight,
      totalDistributed,
      usersCount: sessions.length
    });
    
  } catch (error) {
    logger.error('❌ Distribution failed:', error);
    throw error;
  }
}

/**
 * Manual distribution trigger (for testing/admin)
 */
export async function triggerDistribution(date?: string): Promise<void> {
  logger.info('Manual distribution triggered');
  await runDailyDistribution();
}
