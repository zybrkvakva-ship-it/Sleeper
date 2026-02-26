import { NextFunction, Request, Response } from 'express';

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

export function createSimpleRateLimit(options: RateLimitOptions) {
  const counters = new Map<string, CounterEntry>();
  const methods = new Set((options.methods || ['POST']).map((m) => m.toUpperCase()));

  return function simpleRateLimit(req: Request, res: Response, next: NextFunction) {
    if (!methods.has(req.method.toUpperCase())) {
      return next();
    }

    const now = Date.now();
    const key = options.keyFn ? options.keyFn(req) : `${req.ip}:${req.path}`;
    const current = counters.get(key);

    if (!current || current.resetAt <= now) {
      counters.set(key, { count: 1, resetAt: now + options.windowMs });
      return next();
    }

    if (current.count >= options.max) {
      const retryAfterSeconds = Math.max(1, Math.ceil((current.resetAt - now) / 1000));
      res.setHeader('Retry-After', String(retryAfterSeconds));
      return res.status(429).json({
        error: 'Too many requests',
        retryAfterSeconds,
      });
    }

    current.count += 1;
    counters.set(key, current);
    next();
  };
}
