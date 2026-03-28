import { Router } from 'express';
import { query, transaction } from '../database';
import { AppError } from '../middleware/errorHandler';
import { logger } from '../utils/logger';
import { isValidSolanaAddress, pickWallet } from '../utils/solanaAddress';

const router = Router();

// ─── Constants ────────────────────────────────────────────────────────────────
const MAX_DAILY_TASK_BONUS  = 0.20; // 20 % from daily tasks
const MAX_SPECIAL_TASK_BONUS = 0.10; // 10 % from special (one-time) tasks

// ─── Task Catalog ─────────────────────────────────────────────────────────────
type TaskType       = 'DAILY' | 'SPECIAL';
type TaskActionType = 'CHECKIN' | 'SHARE' | 'STORY' | 'OPEN_URL' | 'AUTO' | 'REFERRAL';

interface TaskDef {
  id: string;
  type: TaskType;
  title: string;
  description: string;
  rewardPts: number;
  bonusPercent: number;
  actionType: TaskActionType;
  actionUrl?: string;
  requiresReferral?: boolean;
  requiresStreak?: number;
}

export const TASK_CATALOG: TaskDef[] = [
  {
    id: 'daily_checkin',
    type: 'DAILY',
    title: 'Daily Check-in',
    description: 'Open the app every day',
    rewardPts: 50,
    bonusPercent: 0.03,
    actionType: 'CHECKIN',
  },
  {
    id: 'share',
    type: 'DAILY',
    title: 'Share Seeker Mining',
    description: 'Share the app with your friends',
    rewardPts: 200,
    bonusPercent: 0.05,
    actionType: 'SHARE',
    actionUrl: 'https://t.me/seekermining',
  },
  {
    id: 'watch_story',
    type: 'DAILY',
    title: 'Watch a Story',
    description: 'Check the latest story in our Telegram channel',
    rewardPts: 100,
    bonusPercent: 0.03,
    actionType: 'STORY',
    actionUrl: 'https://t.me/seekermining',
  },
  {
    id: 'invite_friend',
    type: 'DAILY',
    title: 'Invite a Friend',
    description: 'Invite someone using your referral link',
    rewardPts: 300,
    bonusPercent: 0.05,
    actionType: 'REFERRAL',
    requiresReferral: true,
  },
  {
    id: 'subscribe_telegram',
    type: 'SPECIAL',
    title: 'Join Telegram Channel',
    description: 'Subscribe to @SeekerMining on Telegram',
    rewardPts: 2000,
    bonusPercent: 0.02,
    actionType: 'OPEN_URL',
    actionUrl: 'https://t.me/seekermining',
  },
  {
    id: 'subscribe_twitter',
    type: 'SPECIAL',
    title: 'Follow on X',
    description: 'Follow @SeekerMining on X (Twitter)',
    rewardPts: 1000,
    bonusPercent: 0.02,
    actionType: 'OPEN_URL',
    actionUrl: 'https://x.com/seekermining',
  },
  {
    id: 'sleep_streak_3',
    type: 'SPECIAL',
    title: '3-Night Streak',
    description: 'Mine 3 nights in a row',
    rewardPts: 5000,
    bonusPercent: 0.05,
    actionType: 'AUTO',
    requiresStreak: 3,
  },
  {
    id: 'sleep_streak_7',
    type: 'SPECIAL',
    title: '7-Night Streak',
    description: 'Mine 7 nights in a row',
    rewardPts: 15000,
    bonusPercent: 0.10,
    actionType: 'AUTO',
    requiresStreak: 7,
  },
];

// ─── Auth helpers (inline, same pattern as night.ts) ─────────────────────────
function extractAuthToken(req: { headers: Record<string, any>; body: Record<string, unknown> }): string | undefined {
  return (
    (req.headers['x-auth-token'] as string | undefined) ||
    (req.body?.authToken as string | undefined) ||
    (req.body?.auth_token as string | undefined)
  );
}

async function requireAuth(walletAddress: string, authToken: string | undefined): Promise<void> {
  if (!authToken) throw new AppError(401, 'Authentication required');
  const tokenUuid = authToken.trim();
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(tokenUuid)) {
    throw new AppError(401, 'Invalid auth token format');
  }
  const rows = await query(
    `SELECT 1 FROM wallet_auth_tokens
     WHERE token = $1::uuid AND wallet_address = $2
       AND revoked_at IS NULL AND expires_at > NOW()
     LIMIT 1`,
    [tokenUuid, walletAddress],
  );
  if (rows.length === 0) throw new AppError(401, 'Invalid or expired auth token');
}

// ─── Streak calculator ────────────────────────────────────────────────────────
/** Calculate consecutive night streak from DB rows (unsorted). */
function calcStreak(nightDates: string[]): number {
  if (nightDates.length === 0) return 0;
  const sorted = [...nightDates].sort().reverse(); // most recent first
  const today = new Date().toISOString().split('T')[0];
  const mostRecent = sorted[0];
  const gapDays = Math.floor(
    (new Date(today).getTime() - new Date(mostRecent).getTime()) / 86_400_000,
  );
  // Last session was older than yesterday → streak broken
  if (gapDays > 1) return 0;

  let streak = 0;
  let expectedMs = new Date(mostRecent).getTime();
  for (const dateStr of sorted) {
    const dayMs = new Date(dateStr).getTime();
    const diff = Math.round((expectedMs - dayMs) / 86_400_000);
    if (diff === 0) {
      streak++;
      expectedMs = dayMs - 86_400_000;
    } else {
      break;
    }
  }
  return streak;
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/tasks?wallet=...
// Returns the full task catalog with today's completion status for this wallet.
// ─────────────────────────────────────────────────────────────────────────────
router.get('/', async (req, res, next) => {
  try {
    const walletAddress = req.query.wallet as string;
    if (!walletAddress) throw new AppError(400, 'wallet query param required');
    if (!isValidSolanaAddress(walletAddress)) throw new AppError(400, 'Invalid wallet format');

    const today = new Date().toISOString().split('T')[0];

    // Completed daily tasks for today
    const dailyRows = await query<{ task_id: string; completed_at: string }>(
      `SELECT task_id, completed_at FROM user_task_completions
       WHERE wallet_address = $1 AND task_date = $2`,
      [walletAddress, today],
    );
    const completedDailySet = new Map(dailyRows.map(r => [r.task_id, r.completed_at]));

    // Completed special (one-time) tasks
    const specialRows = await query<{ task_id: string; completed_at: string }>(
      `SELECT task_id, completed_at FROM user_task_completions
       WHERE wallet_address = $1 AND task_date IS NULL`,
      [walletAddress],
    );
    const completedSpecialSet = new Map(specialRows.map(r => [r.task_id, r.completed_at]));

    // Referral count
    const refRows = await query<{ count: string }>(
      `SELECT COUNT(*) AS count FROM referrals WHERE referrer = $1 AND is_active = true`,
      [walletAddress],
    );
    const referralCount = parseInt(refRows[0]?.count ?? '0', 10);

    // Night streak
    const streakRows = await query<{ night_date: string }>(
      `SELECT night_date FROM night_sessions WHERE wallet_address = $1
       ORDER BY night_date DESC LIMIT 10`,
      [walletAddress],
    );
    const nightStreak = calcStreak(streakRows.map(r => r.night_date));

    // Today's daily task bonus
    const bonusRows = await query<{ total_bonus_percent: string }>(
      `SELECT total_bonus_percent FROM daily_tasks WHERE wallet_address = $1 AND task_date = $2`,
      [walletAddress, today],
    );
    const dailyBonusPercent = parseFloat(bonusRows[0]?.total_bonus_percent ?? '0');

    // Permanent special task bonus
    const userRows = await query<{ special_task_bonus: string }>(
      `SELECT COALESCE(special_task_bonus, 0)::text AS special_task_bonus FROM users WHERE wallet_address = $1`,
      [walletAddress],
    );
    const specialBonusPercent = parseFloat(userRows[0]?.special_task_bonus ?? '0');

    // Build response
    const tasks = TASK_CATALOG.map(def => {
      const isCompleted =
        def.type === 'DAILY'
          ? completedDailySet.has(def.id)
          : completedSpecialSet.has(def.id);

      const completedAt =
        def.type === 'DAILY'
          ? completedDailySet.get(def.id) ?? null
          : completedSpecialSet.get(def.id) ?? null;

      // canComplete: not already done + requirements met
      let canComplete = !isCompleted;
      if (def.requiresReferral && referralCount === 0) canComplete = false;
      if (def.requiresStreak && nightStreak < def.requiresStreak) canComplete = false;

      return {
        id: def.id,
        type: def.type,
        title: def.title,
        description: def.description,
        rewardPts: def.rewardPts,
        bonusPercent: def.bonusPercent,
        actionType: def.actionType,
        actionUrl: def.actionUrl ?? null,
        requiresReferral: def.requiresReferral ?? false,
        requiresStreak: def.requiresStreak ?? null,
        isCompleted,
        canComplete,
        completedAt,
      };
    });

    res.json({
      success: true,
      tasks,
      taskDate: today,
      dailyBonusPercent,
      specialBonusPercent,
      totalBonusPercent: dailyBonusPercent + specialBonusPercent,
      referralCount,
      nightStreak,
    });
  } catch (err) {
    next(err);
  }
});

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/tasks/complete
// Body: { walletAddress, taskId, authToken }
// Verifies conditions, records completion, updates bonus.
// ─────────────────────────────────────────────────────────────────────────────
router.post('/complete', async (req, res, next) => {
  try {
    const body = (req.body || {}) as Record<string, unknown>;
    const walletAddress = pickWallet(body);
    const taskId = (body.taskId ?? body.task_id) as string | undefined;

    if (!walletAddress) throw new AppError(400, 'walletAddress is required');
    if (!isValidSolanaAddress(walletAddress)) throw new AppError(400, 'Invalid wallet format');
    if (!taskId) throw new AppError(400, 'taskId is required');

    await requireAuth(walletAddress, extractAuthToken(req as any));

    const def = TASK_CATALOG.find(t => t.id === taskId);
    if (!def) throw new AppError(404, `Unknown task: ${taskId}`);

    const today = new Date().toISOString().split('T')[0];
    const isDaily = def.type === 'DAILY';

    // ── Already completed? ──────────────────────────────────────────────────
    const existingRows = isDaily
      ? await query(
          `SELECT 1 FROM user_task_completions
           WHERE wallet_address = $1 AND task_id = $2 AND task_date = $3`,
          [walletAddress, taskId, today],
        )
      : await query(
          `SELECT 1 FROM user_task_completions
           WHERE wallet_address = $1 AND task_id = $2 AND task_date IS NULL`,
          [walletAddress, taskId],
        );

    if (existingRows.length > 0) throw new AppError(409, 'Task already completed');

    // ── Verify task-specific conditions ────────────────────────────────────
    if (def.requiresReferral) {
      const refRows = await query<{ count: string }>(
        `SELECT COUNT(*) AS count FROM referrals WHERE referrer = $1 AND is_active = true`,
        [walletAddress],
      );
      if (parseInt(refRows[0]?.count ?? '0', 10) === 0) {
        throw new AppError(400, 'No active referrals — invite a friend first');
      }
    }

    if (def.requiresStreak) {
      const streakRows = await query<{ night_date: string }>(
        `SELECT night_date FROM night_sessions WHERE wallet_address = $1
         ORDER BY night_date DESC LIMIT 10`,
        [walletAddress],
      );
      const streak = calcStreak(streakRows.map(r => r.night_date));
      if (streak < def.requiresStreak) {
        throw new AppError(
          400,
          `Streak requirement not met: ${streak}/${def.requiresStreak} nights`,
        );
      }
    }

    // ── Persist ─────────────────────────────────────────────────────────────
    let newDailyBonus = 0;
    let newSpecialBonus = 0;

    await transaction(async (client) => {
      if (isDaily) {
        // Record with today's date (unique per wallet+task+date)
        await client.query(
          `INSERT INTO user_task_completions (wallet_address, task_id, task_date, reward_pts, bonus_percent)
           VALUES ($1, $2, $3, $4, $5)
           ON CONFLICT (wallet_address, task_id, task_date) DO NOTHING`,
          [walletAddress, taskId, today, def.rewardPts, def.bonusPercent],
        );

        // Recompute total daily bonus from all completed tasks today
        const completedBonus = await client.query<{ total: string }>(
          `SELECT COALESCE(SUM(bonus_percent), 0)::text AS total
           FROM user_task_completions
           WHERE wallet_address = $1 AND task_date = $2`,
          [walletAddress, today],
        );
        newDailyBonus = Math.min(
          parseFloat(completedBonus.rows[0]?.total ?? '0'),
          MAX_DAILY_TASK_BONUS,
        );

        // Upsert daily_tasks (used by /night/end)
        await client.query(
          `INSERT INTO daily_tasks (wallet_address, task_date, total_bonus_percent)
           VALUES ($1, $2, $3)
           ON CONFLICT (wallet_address, task_date) DO UPDATE
             SET total_bonus_percent = $3,
                 updated_at = NOW()`,
          [walletAddress, today, newDailyBonus],
        );
      } else {
        // One-time special task — no task_date
        await client.query(
          `INSERT INTO user_task_completions (wallet_address, task_id, task_date, reward_pts, bonus_percent)
           VALUES ($1, $2, NULL, $3, $4)
           ON CONFLICT DO NOTHING`,
          [walletAddress, taskId, def.rewardPts, def.bonusPercent],
        );

        // Update cumulative special bonus in users table
        const specialResult = await client.query<{ special_task_bonus: string }>(
          `UPDATE users
             SET special_task_bonus = LEAST(
                   COALESCE(special_task_bonus, 0) + $1,
                   $2
                 ),
                 updated_at = NOW()
           WHERE wallet_address = $3
           RETURNING special_task_bonus`,
          [def.bonusPercent, MAX_SPECIAL_TASK_BONUS, walletAddress],
        );
        newSpecialBonus = parseFloat(specialResult.rows[0]?.special_task_bonus ?? '0');
      }
    });

    logger.info('Task completed', {
      wallet: walletAddress.slice(0, 8) + '...',
      taskId,
      rewardPts: def.rewardPts,
      bonusPercent: def.bonusPercent,
    });

    res.json({
      success: true,
      taskId,
      rewardPts: def.rewardPts,
      bonusPercent: def.bonusPercent,
      newDailyBonusPercent: newDailyBonus,
      newSpecialBonusPercent: newSpecialBonus,
    });
  } catch (err) {
    next(err);
  }
});

export default router;
