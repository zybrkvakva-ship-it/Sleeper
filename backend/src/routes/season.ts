import { NextFunction, Request, Response, Router } from 'express';
import { query } from '../database';
import { poolPerNight, accelerationTier, currentSeasonDays } from '../economy';
import { SEASON_COUNT } from '../economy/constants';
import { adminAuth } from '../middleware/adminAuth';
import { endSeasonAndStartNext } from '../services/distributionScheduler';

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
 */
router.get('/current', getActiveSeason);

/**
 * GET /api/v1/season/active — backward-compat alias
 */
router.get('/active', getActiveSeason);

/**
 * GET /api/v1/season/admin/status
 * Full season debug info: days since start, days remaining, tier, pool/night.
 * Requires X-Admin-Secret header.
 */
router.get('/admin/status', adminAuth, async (req: Request, res: Response, next: NextFunction) => {
  try {
    const seasons = await query(
      `SELECT season_number, start_date, end_date, status, active_devices,
              total_np, total_sleep_distributed
       FROM season_stats ORDER BY season_number DESC LIMIT 5`
    );

    const active = seasons.find((s: any) => s.status === 'ACTIVE');
    if (!active) {
      return res.json({ success: true, activeSeason: null, recentSeasons: seasons });
    }

    const seasonDays = currentSeasonDays(active.active_devices);
    const tier = accelerationTier(active.active_devices);
    const daysSinceStart = Math.floor(
      (Date.now() - new Date(active.start_date).getTime()) / (1000 * 60 * 60 * 24)
    );
    const daysRemaining = Math.max(0, seasonDays - daysSinceStart);
    const poolNight = poolPerNight(active.active_devices);

    res.json({
      success: true,
      activeSeason: {
        ...active,
        tier,
        seasonDays,
        daysSinceStart,
        daysRemaining,
        poolPerNight: poolNight,
        progressPercent: Math.min(100, Math.round((daysSinceStart / seasonDays) * 100)),
        totalSeasons: SEASON_COUNT,
        seasonsRemaining: SEASON_COUNT - active.season_number,
      },
      recentSeasons: seasons,
    });
  } catch (error) {
    next(error);
  }
});

/**
 * POST /api/v1/season/admin/advance
 * Force-complete current season and start the next one.
 * Requires X-Admin-Secret header.
 */
router.post('/admin/advance', adminAuth, async (req: Request, res: Response, next: NextFunction) => {
  try {
    const active = await query<{ season_number: number }>(
      `SELECT season_number FROM season_stats WHERE status = 'ACTIVE' LIMIT 1`
    );

    if (active.length === 0) {
      return res.status(404).json({ success: false, error: 'No active season to advance' });
    }

    const currentNumber = active[0].season_number;

    if (currentNumber >= SEASON_COUNT) {
      return res.status(400).json({
        success: false,
        error: `Season ${currentNumber} is the final season (SEASON_COUNT=${SEASON_COUNT}). Cannot advance further.`
      });
    }

    await endSeasonAndStartNext(currentNumber);

    res.json({
      success: true,
      message: `Season ${currentNumber} completed → Season ${currentNumber + 1} started`,
      completedSeason: currentNumber,
      nextSeason: currentNumber + 1,
    });
  } catch (error) {
    next(error);
  }
});

export default router;
