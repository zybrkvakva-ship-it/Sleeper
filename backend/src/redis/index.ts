import Redis from 'ioredis';
import { logger } from '../utils/logger';

let redisClient: Redis | null = null;

/**
 * Returns the active Redis client, or null if Redis is not configured/connected.
 * All callers must handle the null case gracefully (fall back to in-memory or skip cache).
 */
export function getRedis(): Redis | null {
  return redisClient;
}

/**
 * Connect to Redis using REDIS_URL env variable.
 * If REDIS_URL is not set, Redis is silently disabled — the app continues
 * with in-memory fallbacks (rate limiter, no leaderboard cache).
 */
export async function connectRedis(): Promise<void> {
  const url = process.env.REDIS_URL;
  if (!url) {
    logger.warn('REDIS_URL not set — Redis disabled, falling back to in-memory implementations');
    return;
  }

  try {
    const client = new Redis(url, {
      lazyConnect: true,
      maxRetriesPerRequest: 3,
      connectTimeout: 5000,
      enableOfflineQueue: false,
    });

    client.on('error', (err) => {
      logger.error('Redis connection error', err);
    });

    client.on('reconnecting', () => {
      logger.warn('Redis reconnecting...');
    });

    await client.connect();
    redisClient = client;
    logger.info('✅ Redis connected');
  } catch (err) {
    logger.warn('Redis connection failed — continuing without Redis', err);
    redisClient = null;
  }
}

/**
 * Gracefully disconnect Redis on server shutdown.
 */
export async function disconnectRedis(): Promise<void> {
  if (redisClient) {
    await redisClient.quit();
    redisClient = null;
    logger.info('Redis disconnected');
  }
}
