import { Router } from 'express';
import { query } from '../database';

const router = Router();

/**
 * GET /api/v1/leaderboard
 * Get global leaderboard. Contract format (API_MINING_CONTRACT): top, user_rank, user_blocks, total_points, total_blocks.
 * Uses points_balance (Sleeper) for ranking; falls back to leaderboard view if no points_balance column.
 */
router.get('/', async (req, res, next) => {
  try {
    const limit = Math.min(parseInt(req.query.limit as string) || 100, 500);
    const wallet = (req.query.wallet as string)?.trim();

    // Try mining leaderboard (points_balance) first
    const hasPointsBalance = await query(
      `SELECT column_name FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'points_balance'`
    ).then((r) => r.length > 0);

    if (hasPointsBalance) {
      const topRows = await query<{ rank: number; username: string; blocks: number; points_balance: string }>(
        `SELECT 
          ROW_NUMBER() OVER (ORDER BY COALESCE(points_balance, 0) DESC)::int AS rank,
          COALESCE(skr_username, LEFT(wallet_address, 8) || '…') AS username,
          COALESCE(total_blocks_mined, 0)::int AS blocks,
          COALESCE(points_balance, 0)::bigint AS points_balance
        FROM users
        WHERE COALESCE(points_balance, 0) > 0
        ORDER BY points_balance DESC
        LIMIT $1`,
        [limit]
      );
      const top = topRows.map((r) => ({ rank: r.rank, username: r.username || '', blocks: r.blocks }));

      let userRank = 0;
      let userBlocks = 0;
      if (wallet) {
        const u = await query<{ rn: number; total_blocks_mined: string }>(
          `WITH ranked AS (
             SELECT wallet_address, ROW_NUMBER() OVER (ORDER BY COALESCE(points_balance, 0) DESC)::int AS rn
             FROM users
           )
           SELECT r.rn, COALESCE(u.total_blocks_mined, 0)::text AS total_blocks_mined
           FROM users u
           JOIN ranked r ON r.wallet_address = u.wallet_address
           WHERE u.wallet_address = $1`,
          [wallet]
        );
        if (u.length > 0) {
          userRank = u[0].rn;
          userBlocks = parseInt(u[0].total_blocks_mined, 10);
        }
      }

      const totals = await query<{ total_points: string; total_blocks: string }>(
        `SELECT COALESCE(SUM(COALESCE(points_balance, 0)), 0)::bigint AS total_points,
                COALESCE(SUM(COALESCE(total_blocks_mined, 0)), 0)::bigint AS total_blocks FROM users`
      ).then((r) => r[0] || { total_points: '0', total_blocks: '0' });

      return res.json({
        top,
        user_rank: userRank,
        user_blocks: userBlocks,
        total_points: parseInt(totals.total_points, 10),
        total_blocks: parseInt(totals.total_blocks, 10),
      });
    }

    // Fallback: legacy leaderboard view (total_np)
    const leaderboard = await query(
      `SELECT rank, wallet_address, skr_username, total_np, total_sleep_earned, total_nights_mined, referral_count
       FROM leaderboard ORDER BY rank ASC LIMIT $1`,
      [limit]
    );
    res.json({
      success: true,
      leaderboard,
      count: leaderboard.length,
    });
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
 * Manually refresh leaderboard (admin only in production)
 */
router.post('/refresh', async (req, res, next) => {
  try {
    await query('SELECT refresh_leaderboard()');
    
    res.json({
      success: true,
      message: 'Leaderboard refreshed'
    });
    
  } catch (error) {
    next(error);
  }
});

export default router;
