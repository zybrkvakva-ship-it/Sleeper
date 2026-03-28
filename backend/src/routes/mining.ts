import { Router } from 'express';

const router = Router();

/**
 * POST /api/v1/mining/session — DEPRECATED (410 Gone)
 *
 * This endpoint used a storage-based mining model that is no longer part of Sleeper.
 * All sleep mining is now handled via POST /api/v1/night/end.
 */
router.post('/session', (_req, res) => {
  res.status(410).json({
    success: false,
    error: 'This endpoint has been removed. Use POST /api/v1/night/end for sleep sessions.',
    migration: '/api/v1/night/end',
  });
});

export default router;
