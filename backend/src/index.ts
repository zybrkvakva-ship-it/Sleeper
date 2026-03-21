import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import { createServer } from 'http';
import { WebSocketServer } from 'ws';
import dotenv from 'dotenv';

import { errorHandler } from './middleware/errorHandler';
import { createSimpleRateLimit } from './middleware/rateLimit';
import { logger } from './utils/logger';
import { db } from './database';
import { connectRedis, disconnectRedis } from './redis';

// Routes
import nightRouter from './routes/night';
import userRouter from './routes/user';
import miningRouter from './routes/mining';
import leaderboardRouter from './routes/leaderboard';
import nftRouter from './routes/nft';
import paymentRouter from './routes/payment';
import seasonRouter from './routes/season';
import economyRouter from './routes/economy';
import claimRouter from './routes/claim';

// WebSocket handlers
import { setupWebSocket } from './websocket';

// Load environment variables
dotenv.config();

const PORT = parseInt(String(process.env.PORT || 3000), 10);
const WS_PORT = parseInt(String(process.env.WS_PORT || 3001), 10);

// Create Express app
export const app = express();

// Middleware
app.use(helmet());

// CORS — explicit allowlist only; '*' wildcard is never used as a fallback
const allowedOrigins = process.env.ALLOWED_ORIGINS
  ? process.env.ALLOWED_ORIGINS.split(',').map((o) => o.trim()).filter(Boolean)
  : [];
if (allowedOrigins.length === 0 && process.env.NODE_ENV === 'production') {
  logger.warn('ALLOWED_ORIGINS not set in production — CORS will reject all cross-origin requests');
}
app.use(cors({
  origin: allowedOrigins.length > 0 ? allowedOrigins : false,
  credentials: true,
}));
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

const writeRouteLimiter = createSimpleRateLimit({
  windowMs: 60_000,
  max: 20,
  methods: ['POST'],
  keyFn: (req) => `${req.ip}:${req.method}:${req.path}`,
});

app.use([
  '/api/v1/user/auth/challenge',
  '/api/v1/user/auth/verify',
  '/api/v1/mining/session',
  '/api/v1/night/start',
  '/api/v1/night/end',
  '/api/v1/payment/activate-boost',
  '/api/v1/payment/verify-skr',
], writeRouteLimiter);

// Request logging
app.use((req, res, next) => {
  logger.info(`${req.method} ${req.path}`, {
    ip: req.ip,
    userAgent: req.get('user-agent')
  });
  next();
});

// Health check
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    timestamp: Date.now(),
    uptime: process.uptime(),
    version: process.env.API_VERSION || 'v1'
  });
});

// API Routes
app.use('/api/v1/night', nightRouter);
app.use('/api/v1/user', userRouter);
app.use('/api/v1/mining', miningRouter);
app.use('/api/v1/leaderboard', leaderboardRouter);
app.use('/api/v1/nft', nftRouter);
app.use('/api/v1/payment', paymentRouter);
app.use('/api/v1/season', seasonRouter);
app.use('/api/v1/mining/season', seasonRouter);
app.use('/api/v1/economy', economyRouter);
app.use('/api/v1/claim', claimRouter);

// 404 handler
app.use((req, res) => {
  res.status(404).json({
    error: 'Not Found',
    path: req.path
  });
});

// Error handler (must be last)
app.use(errorHandler);

// Create HTTP server
const server = createServer(app);

// Setup WebSocket server
const wss = new WebSocketServer({ port: WS_PORT });
setupWebSocket(wss);

// Start server
async function start() {
  try {
    // Test database connection
    await db.query('SELECT NOW()');
    logger.info('✅ Database connected');

    // Connect to Redis (optional — app continues without it)
    await connectRedis();

    // Start HTTP server
    server.listen(PORT, () => {
      logger.info(`🚀 Sleeper Backend started`);
      logger.info(`📡 HTTP Server: http://localhost:${PORT}`);
      logger.info(`🔌 WebSocket Server: ws://localhost:${WS_PORT}`);
      logger.info(`🌍 Environment: ${process.env.NODE_ENV || 'development'}`);
    });

    // Start daily SLEEP distribution scheduler
    const { startDistributionScheduler } = await import('./services/distributionScheduler');
    startDistributionScheduler();
    logger.info('⏰ Distribution scheduler started');

  } catch (error) {
    logger.error('❌ Failed to start server:', error);
    process.exit(1);
  }
}

// Graceful shutdown
async function gracefulShutdown(signal: string) {
  logger.info(`${signal} received, shutting down gracefully`);
  server.close(async () => {
    logger.info('HTTP server closed');
    await disconnectRedis();
    await db.end();
    process.exit(0);
  });
}

process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
process.on('SIGINT', () => gracefulShutdown('SIGINT'));

// Start the server (skip in tests)
if (process.env.NODE_ENV !== 'test') {
  start();
}
