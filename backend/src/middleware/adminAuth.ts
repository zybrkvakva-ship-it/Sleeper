import { NextFunction, Request, Response } from 'express';
import { timingSafeEqual } from 'crypto';
import { logger } from '../utils/logger';

/**
 * Admin authentication middleware.
 * Checks X-Admin-Secret header against ADMIN_SECRET env variable.
 * Uses timingSafeEqual to prevent timing-based attacks.
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

  let authorized = false;
  if (provided && typeof provided === 'string') {
    try {
      const providedBuf = Buffer.from(provided);
      const expectedBuf = Buffer.from(adminSecret);
      authorized = providedBuf.length === expectedBuf.length &&
        timingSafeEqual(providedBuf, expectedBuf);
    } catch {
      authorized = false;
    }
  }

  if (!authorized) {
    logger.warn('Admin auth failed', { ip: req.ip, path: req.path });
    res.status(401).json({
      success: false,
      error: { code: 401, message: 'Unauthorized: invalid or missing X-Admin-Secret header' },
    });
    return;
  }

  logger.warn('Admin endpoint accessed', { ip: req.ip, path: req.path, method: req.method });
  next();
}
