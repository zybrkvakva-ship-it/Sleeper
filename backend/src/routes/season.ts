import { NextFunction, Request, Response, Router } from 'express';
import { query } from '../database';
import { poolPerNight } from '../economy';

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
    
    // Calculate dynamic values
    const poolNight = poolPerNight(season.active_devices);
    const weeksRemaining = season.total_weeks - season.current_week;
    
    res.json({
      success: true,
      season: {
        ...season,
        poolPerNight: poolNight,
        weeksRemaining,
        nightsRemaining: weeksRemaining * 7
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
