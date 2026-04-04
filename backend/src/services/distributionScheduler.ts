import { query, transaction } from '../database';
import { poolPerNight, distributeSleepTokens, currentSeasonDays } from '../economy';
import { SEASON_COUNT } from '../economy/constants';
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

    // Carry dust only from yesterday's distribution (not cumulative total)
    const dustRow = await query<{ dust: string }>(
      `SELECT COALESCE(dust_carried, 0) AS dust
       FROM night_distributions
       WHERE night_date = CURRENT_DATE - INTERVAL '1 day'
       LIMIT 1`
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

    // ── Season-end detection (Acceleration Mining) ──────────────────────────
    // Season duration is dynamic (exponential: 112 → 1 day based on active_devices).
    // After each distribution, check if the season has run its course.
    const seasonForCheck = await query<{
      season_number: number;
      start_date: string;
      active_devices: number;
    }>(
      `SELECT season_number, start_date, active_devices
       FROM season_stats WHERE status = 'ACTIVE' LIMIT 1`
    );

    if (seasonForCheck.length > 0) {
      const s = seasonForCheck[0];
      const seasonDays = currentSeasonDays(s.active_devices);
      const daysSinceStart = Math.floor(
        (Date.now() - new Date(s.start_date).getTime()) / (1000 * 60 * 60 * 24)
      );

      logger.info(`Season ${s.season_number}: day ${daysSinceStart}/${seasonDays} (${s.active_devices} active devices)`);

      if (daysSinceStart >= seasonDays && s.season_number < SEASON_COUNT) {
        await endSeasonAndStartNext(s.season_number);
      } else if (s.season_number >= SEASON_COUNT && daysSinceStart >= seasonDays) {
        // All 20 seasons mined — mark final season complete, no new season
        await query(
          `UPDATE season_stats SET status = 'COMPLETED', end_date = CURRENT_DATE
           WHERE season_number = $1 AND status = 'ACTIVE'`,
          [s.season_number]
        );
        broadcast({ type: 'all-seasons-complete', totalSeasons: SEASON_COUNT });
        logger.info('🏁 All seasons complete — 10B SPR fully mined!');
      }
    }

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

/**
 * End current season and start the next one atomically.
 * Called automatically by runDailyDistribution() when season duration is exceeded.
 * Also exposed for admin manual trigger.
 */
export async function endSeasonAndStartNext(currentSeasonNumber: number): Promise<void> {
  logger.info(`Ending season ${currentSeasonNumber}, starting season ${currentSeasonNumber + 1}...`);

  await transaction(async (client) => {
    // 1. Archive current season
    await client.query(
      `UPDATE season_stats
       SET status = 'COMPLETED', end_date = CURRENT_DATE
       WHERE season_number = $1 AND status = 'ACTIVE'`,
      [currentSeasonNumber]
    );

    // 2. Start next season (active_devices resets to 0)
    await client.query(
      `INSERT INTO season_stats (season_number, start_date, total_weeks, status, active_devices, total_np, total_sleep_distributed)
       VALUES ($1, CURRENT_DATE, 16, 'ACTIVE', 0, 0, 0)
       ON CONFLICT (season_number) DO NOTHING`,
      [currentSeasonNumber + 1]
    );
  });

  broadcast({
    type: 'season-complete',
    completedSeason: currentSeasonNumber,
    nextSeason: currentSeasonNumber + 1,
  });

  logger.info(`✅ Season ${currentSeasonNumber} → COMPLETED. Season ${currentSeasonNumber + 1} → ACTIVE`);
}
