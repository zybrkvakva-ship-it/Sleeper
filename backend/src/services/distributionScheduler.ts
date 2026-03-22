import { query, transaction } from '../database';
import { poolPerNight, distributeSleepTokens } from '../economy';
import { broadcast } from '../websocket';
import { logger } from '../utils/logger';
import { invalidateLeaderboardCache } from '../routes/leaderboard';

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
    
    // Get pool for night (base)
    const activeDevices = sessions.length;
    const basePoolNight = poolPerNight(activeDevices);

    // Add uncarried dust from previous nights
    const dustRow = await query<{ dust: string }>(
      `SELECT COALESCE(SUM(dust_carried), 0) AS dust
       FROM night_distributions
       WHERE dust_carried > 0`
    );
    const prevDust = parseInt(dustRow[0]?.dust ?? '0', 10);
    const poolNight = basePoolNight + prevDust;

    logger.info(`Total NP: ${totalNp.toFixed(2)}, Base pool: ${basePoolNight}, Dust carry-in: ${prevDust}, Effective pool: ${poolNight} SLEEP`);
    
    // Distribute tokens
    const rewards = distributeSleepTokens(usersNp, poolNight);
    
    // Calculate total distributed and dust (Math.floor rounding remainder)
    const totalDistributed = Object.values(rewards).reduce((sum, tokens) => sum + tokens, 0);
    const dustCarried = poolNight - totalDistributed;
    
    // Prepare batch arrays
    const wallets = Object.keys(rewards);
    const tokenAmounts = Object.values(rewards).map(String); // bigint-safe as text

    // Get active season info
    const seasonRow = await query<{ season_number: number; current_week: number }>(
      `SELECT season_number, COALESCE(current_week, 1) AS current_week
       FROM season_stats WHERE status = 'ACTIVE' ORDER BY season_number DESC LIMIT 1`
    );
    const seasonNumber = seasonRow[0]?.season_number ?? 1;
    const weekIndex = seasonRow[0]?.current_week ?? 1;

    // Update database in a single transaction with batch operations
    await transaction(async (client) => {
      // Batch update night_sessions — one query instead of N
      await client.query(
        `UPDATE night_sessions
         SET sleep_tokens = v.tokens::bigint, processed = true, processed_at = NOW()
         FROM (
           SELECT unnest($1::text[]) AS wallet, unnest($2::text[]) AS tokens
         ) v
         WHERE night_sessions.wallet_address = v.wallet
           AND night_sessions.night_date = $3
           AND night_sessions.processed = false`,
        [wallets, tokenAmounts, yesterdayStr]
      );

      // Batch update users.total_sleep_earned — one query instead of N
      await client.query(
        `UPDATE users
         SET total_sleep_earned = users.total_sleep_earned + v.tokens::bigint
         FROM (
           SELECT unnest($1::text[]) AS wallet, unnest($2::text[]) AS tokens
         ) v
         WHERE users.wallet_address = v.wallet`,
        [wallets, tokenAmounts]
      );

      // Record distribution with real season data + dust
      await client.query(
        `INSERT INTO night_distributions (
          night_date, total_np, pool_night, total_distributed, users_count, active_devices, season_number, week_index, dust_carried
        ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
        [yesterdayStr, totalNp, poolNight, totalDistributed, sessions.length, activeDevices, seasonNumber, weekIndex, dustCarried]
      );

      // Refresh leaderboard materialized view
      await client.query('SELECT refresh_leaderboard()');
    });
    
    logger.info(`✅ Distribution completed: ${totalDistributed} SLEEP to ${sessions.length} users, dust carried: ${dustCarried}`);

    // Invalidate leaderboard cache so next request gets fresh data
    await invalidateLeaderboardCache();

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
