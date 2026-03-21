import { NextFunction, Request, Response } from 'express';
import { getRedis } from '../redis';

type RateLimitOptions = {
  windowMs: number;
  max: number;
  methods?: string[];
  keyFn?: (req: Request) => string;
};

type CounterEntry = {
  count: number;
  resetAt: number;
};

// In-memory fallback store (used when Redis is not available)
const memoryCounters = new Map<string, CounterEntry>();

// Periodically clean up expired in-memory entries to prevent memory leak
const CLEANUP_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes
setInterval(() => {
  const now = Date.now();
  for (const [key, entry] of memoryCounters) {
    if (entry.resetAt <= now) {
      memoryCounters.delete(key);
    }
  }
}, CLEANUP_INTERVAL_MS).unref(); // .unref() so it doesn't block process exit

/**
 * Check rate limit using Redis if available, otherwise in-memory.
 * Returns true if request is allowed, false if limit exceeded.
 */
async function checkRateLimit(
  key: string,
  windowMs: number,
  max: number
): Promise<{ allowed: boolean; retryAfterSeconds: number }> {
  const redis = getRedis();

  if (redis) {
    // Redis path: atomic INCR + conditional PEXPIRE
    try {
      const count = await redis.incr(key);
      if (count === 1) {
        // First request in window — set TTL
        await redis.pexpire(key, windowMs);
      }
      if (count > max) {
        const ttlMs = await redis.pttl(key);
        const retryAfterSeconds = Math.max(1, Math.ceil(ttlMs / 1000));
        return { allowed: false, retryAfterSeconds };
      }
      return { allowed: true, retryAfterSeconds: 0 };
    } catch (err) {
      // Redis error — fall through to in-memory
    }
  }

  // In-memory fallback
  const now = Date.now();
  const current = memoryCounters.get(key);

  if (!current || current.resetAt <= now) {
    memoryCounters.set(key, { count: 1, resetAt: now + windowMs });
    return { allowed: true, retryAfterSeconds: 0 };
  }

  if (current.count >= max) {
    const retryAfterSeconds = Math.max(1, Math.ceil((current.resetAt - now) / 1000));
    return { allowed: false, retryAfterSeconds };
  }

  current.count += 1;
  return { allowed: true, retryAfterSeconds: 0 };
}

export function createSimpleRateLimit(options: RateLimitOptions) {
  const methods = new Set((options.methods || ['POST']).map((m) => m.toUpperCase()));

  return function simpleRateLimit(req: Request, res: Response, next: NextFunction): void {
    if (!methods.has(req.method.toUpperCase())) {
      next();
      return;
    }

    const key = options.keyFn
      ? options.keyFn(req)
      : `rl:${req.ip}:${req.method}:${req.path}`;

    checkRateLimit(key, options.windowMs, options.max)
      .then(({ allowed, retryAfterSeconds }) => {
        if (!allowed) {
          res.setHeader('Retry-After', String(retryAfterSeconds));
          res.status(429).json({
            error: 'Too many requests',
            retryAfterSeconds,
          });
          return;
        }
        next();
      })
      .catch(() => {
        // Rate limit check failed entirely — let the request through
        next();
      });
  };
}
