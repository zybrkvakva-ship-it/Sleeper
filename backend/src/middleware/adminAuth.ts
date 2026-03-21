import { NextFunction, Request, Response } from 'express';

/**
 * Admin authentication middleware.
 * Checks X-Admin-Secret header against ADMIN_SECRET env variable.
 * Protects sensitive admin-only endpoints (leaderboard refresh, distribution trigger).
 */
export function adminAuth(req: Request, res: Response, next: NextFunction): void {
  const adminSecret = process.env.ADMIN_SECRET;

  if (!adminSecret) {
    res.status(503).json({
      success: false,
      error: { code: 503, message: 'Admin access not configured on this server' },
    });
    return;
  }

  const provided = req.headers['x-admin-secret'];
  if (!provided || provided !== adminSecret) {
    res.status(401).json({
      success: false,
      error: { code: 401, message: 'Unauthorized: invalid or missing X-Admin-Secret header' },
    });
    return;
  }

  next();
}
