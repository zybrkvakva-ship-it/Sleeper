import { NextFunction, Request, Response, Router } from 'express';
import { query } from '../database';
import { poolPerNight, accelerationTier, currentSeasonDays } from '../economy';

const router = Router();

async function getActiveSeason(_req: Request, res: Response, next: NextFunction) {
  try {
    const seasons = await query(
      `SELECT 
        season_number,
        start_date,
        current_week,
        total_weeks,
        active_devices,
        total_np,
        total_sleep_distributed,
        status
      FROM season_stats
      WHERE status = 'ACTIVE'
      LIMIT 1`
    );
    
    if (seasons.length === 0) {
      return res.status(404).json({
        success: false,
        error: 'No active season'
      });
    }
    
    const season = seasons[0];
    
    // Dynamic values based on current active miners (Exponential Acceleration Mining)
    const poolNight = poolPerNight(season.active_devices);
    const seasonDays = currentSeasonDays(season.active_devices);
    const tier = accelerationTier(season.active_devices);
    const daysSinceStart = Math.floor(
      (Date.now() - new Date(season.start_date).getTime()) / (1000 * 60 * 60 * 24)
    );
    const daysRemaining = Math.max(0, seasonDays - daysSinceStart);

    res.json({
      success: true,
      season: {
        ...season,
        tier,
        seasonDays,
        seasonWeeks: Math.round(seasonDays / 7 * 10) / 10,  // display only
        poolPerNight: poolNight,
        daysRemaining,
        nightsRemaining: daysRemaining,
      }
    });
    
  } catch (error) {
    next(error);
  }
}

/**
 * GET /api/v1/season/current
 * Get current season info
 */
router.get('/current', getActiveSeason);

/**
 * GET /api/v1/mining/season/active
 * Backward-compatible alias for legacy clients/scripts.
 */
router.get('/active', getActiveSeason);

export default router;
