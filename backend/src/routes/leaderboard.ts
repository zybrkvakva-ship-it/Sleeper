import { Router } from 'express';
import { query } from '../database';
import { adminAuth } from '../middleware/adminAuth';
import { getRedis } from '../redis';

const router = Router();

const LEADERBOARD_CACHE_KEY = 'cache:leaderboard:top100';
const LEADERBOARD_TTL_SECONDS = 60;

/** Invalidate cached leaderboard (call after refresh or distribution). */
export async function invalidateLeaderboardCache(): Promise<void> {
  const redis = getRedis();
  if (redis) {
    await redis.del(LEADERBOARD_CACHE_KEY).catch(() => {});
  }
}

/**
 * GET /api/v1/leaderboard
 * Get global leaderboard sorted by total_np (Night Points — primary sleep mining metric).
 * Top-100 is cached in Redis for 60 seconds.
 */
router.get('/', async (req, res, next) => {
  try {
    const limit = Math.min(parseInt(req.query.limit as string) || 100, 500);
    const wallet = (req.query.wallet as string)?.trim();
    const redis = getRedis();

    // Try Redis cache for top-100 requests (no wallet filter)
    if (redis && !wallet && limit <= 100) {
      const cached = await redis.get(LEADERBOARD_CACHE_KEY).catch(() => null);
      if (cached) {
        const data = JSON.parse(cached);
        return res.json(data);
      }
    }

    const topRows = await query<{ rank: number; username: string; total_np: string; total_nights_mined: string }>(
      `SELECT
        ROW_NUMBER() OVER (ORDER BY COALESCE(total_np, 0) DESC)::int AS rank,
        COALESCE(skr_username, LEFT(wallet_address, 8) || '…') AS username,
        COALESCE(total_np, 0)::bigint AS total_np,
        COALESCE(total_nights_mined, 0)::int AS total_nights_mined
      FROM users
      WHERE COALESCE(total_np, 0) > 0
      ORDER BY total_np DESC
      LIMIT $1`,
      [limit]
    );
    const top = topRows.map((r) => ({
      rank: r.rank,
      username: r.username || '',
      total_np: parseInt(r.total_np, 10),
      nights: parseInt(r.total_nights_mined, 10),
    }));

    let userRank = 0;
    let userNp = 0;
    if (wallet) {
      const u = await query<{ rn: number; total_np: string }>(
        `WITH ranked AS (
           SELECT wallet_address, ROW_NUMBER() OVER (ORDER BY COALESCE(total_np, 0) DESC)::int AS rn
           FROM users
         )
         SELECT r.rn, COALESCE(u.total_np, 0)::text AS total_np
         FROM users u
         JOIN ranked r ON r.wallet_address = u.wallet_address
         WHERE u.wallet_address = $1`,
        [wallet]
      );
      if (u.length > 0) {
        userRank = u[0].rn;
        userNp = parseInt(u[0].total_np, 10);
      }
    }

    const totals = await query<{ total_np_sum: string; total_users: string }>(
      `SELECT COALESCE(SUM(COALESCE(total_np, 0)), 0)::bigint AS total_np_sum,
              COUNT(*)::bigint AS total_users
       FROM users WHERE COALESCE(total_np, 0) > 0`
    ).then((r) => r[0] || { total_np_sum: '0', total_users: '0' });

    const response = {
      top,
      user_rank: userRank,
      user_np: userNp,
      total_np: parseInt(totals.total_np_sum, 10),
      total_users: parseInt(totals.total_users, 10),
    };

    // Cache top-100 (no wallet filter) for 60 seconds
    if (redis && !wallet && limit <= 100) {
      await redis.setex(LEADERBOARD_CACHE_KEY, LEADERBOARD_TTL_SECONDS, JSON.stringify(response)).catch(() => {});
    }

    return res.json(response);
  } catch (error) {
    next(error);
  }
});

/**
 * GET /api/v1/leaderboard/rank/:walletAddress
 * Get user's rank
 */
router.get('/rank/:walletAddress', async (req, res, next) => {
  try {
    const { walletAddress } = req.params;

    const result = await query(
      'SELECT rank, total_np FROM leaderboard WHERE wallet_address = $1',
      [walletAddress]
    );

    if (result.length === 0) {
      return res.json({
        success: true,
        rank: null,
        message: 'User not on leaderboard yet'
      });
    }

    res.json({
      success: true,
      rank: result[0].rank,
      totalNp: result[0].total_np
    });

  } catch (error) {
    next(error);
  }
});

/**
 * POST /api/v1/leaderboard/refresh
 * Manually refresh leaderboard (admin only — requires X-Admin-Secret header)
 */
router.post('/refresh', adminAuth, async (req, res, next) => {
  try {
    await query('SELECT refresh_leaderboard()');
    await invalidateLeaderboardCache();

    res.json({
      success: true,
      message: 'Leaderboard refreshed'
    });

  } catch (error) {
    next(error);
  }
});

export default router;
