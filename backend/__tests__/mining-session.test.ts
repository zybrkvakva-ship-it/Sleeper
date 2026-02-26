import express from 'express';
import request from 'supertest';

jest.mock('../src/database', () => ({
  query: jest.fn(),
  transaction: jest.fn(),
}));

import miningRouter from '../src/routes/mining';
import { errorHandler } from '../src/middleware/errorHandler';
import { query, transaction } from '../src/database';

const wallet = '9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin';

function makeApp() {
  const app = express();
  app.use(express.json());
  app.use('/api/v1/mining', miningRouter);
  app.use(errorHandler);
  return app;
}

describe('Mining session route', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('POST /session should require auth_token', async () => {
    const app = makeApp();
    const res = await request(app).post('/api/v1/mining/session').send({
      wallet,
      uptime_minutes: 10,
      session_started_at: Date.now() - 60_000,
      session_ended_at: Date.now(),
    });
    expect(res.status).toBe(401);
    expect(res.body.error).toContain('auth_token (or authToken) is required');
  });

  it('POST /session should validate timestamps', async () => {
    const app = makeApp();
    const now = Date.now();
    const res = await request(app).post('/api/v1/mining/session').send({
      wallet,
      auth_token: 'token-1',
      uptime_minutes: 10,
      session_started_at: now,
      session_ended_at: now - 1_000,
    });
    expect(res.status).toBe(400);
    expect(res.body.error).toContain('invalid session timestamps');
  });

  it('POST /session should reject invalid wallet format', async () => {
    const app = makeApp();
    const res = await request(app).post('/api/v1/mining/session').send({
      wallet: 'not-a-solana-wallet-address-@@@@',
      auth_token: 'token-1',
      uptime_minutes: 10,
      session_started_at: Date.now() - 60_000,
      session_ended_at: Date.now(),
    });
    expect(res.status).toBe(400);
    expect(res.body.error).toContain('invalid wallet format');
  });

  it('POST /session should return server balance and points', async () => {
    const app = makeApp();
    const mockedQuery = query as jest.MockedFunction<typeof query>;
    const mockedTransaction = transaction as jest.MockedFunction<typeof transaction>;

    mockedTransaction.mockImplementation(async (callback: any) => {
      const client = {
        query: jest
          .fn()
          .mockResolvedValueOnce({ rows: [{ token: 'token-1' }] }) // validate auth token
          .mockResolvedValueOnce({ rows: [] }) // upsert user
          .mockResolvedValueOnce({ rows: [] }) // existing session check
          .mockResolvedValueOnce({ rows: [{ points_balance: '100' }] }) // current balance for update
          .mockResolvedValueOnce({ rows: [{ id: 'session-1' }] }) // insert session
          .mockResolvedValueOnce({ rows: [] }), // update user balance
      };
      return callback(client);
    });

    mockedQuery.mockResolvedValueOnce([{ points_balance: '150' }]); // final read after tx

    const now = Date.now();
    const res = await request(app).post('/api/v1/mining/session').send({
      wallet,
      auth_token: 'token-1',
      skr: 'alice.skr',
      uptime_minutes: 5,
      duration_seconds: 300,
      storage_mb: 200,
      storage_multiplier: 1.5,
      staked_skr_human: 2000,
      stake_multiplier: 1.2,
      human_checks_passed: 3,
      human_checks_failed: 1,
      human_multiplier: 0.875,
      daily_social_bonus_percent: 0.1,
      paid_boost_multiplier: 1.05,
      daily_social_multiplier: 1.1,
      points_per_second: 0.4,
      points_balance: 99999,
      session_started_at: now - 300_000,
      session_ended_at: now,
      has_genesis_nft: true,
      genesis_nft_multiplier: 1.1,
      active_skr_boost_id: 'boost_7h',
    });

    expect(res.status).toBe(200);
    expect(res.body.session_id).toBe('session-1');
    expect(res.body.balance).toBe(150);
    expect(res.body.points_earned).toBeGreaterThan(0);
    expect(res.body.points_per_second).toBeGreaterThan(0);
    expect(res.body.duplicate).toBe(false);
  });

  it('POST /session duplicate should return points_earned=0', async () => {
    const app = makeApp();
    const mockedQuery = query as jest.MockedFunction<typeof query>;
    const mockedTransaction = transaction as jest.MockedFunction<typeof transaction>;

    mockedTransaction.mockImplementation(async (callback: any) => {
      const client = {
        query: jest
          .fn()
          .mockResolvedValueOnce({ rows: [{ token: 'token-1' }] }) // validate auth token
          .mockResolvedValueOnce({ rows: [] }) // upsert user
          .mockResolvedValueOnce({ rows: [{ id: 'session-dup' }] }), // existing session check
      };
      return callback(client);
    });

    mockedQuery.mockResolvedValueOnce([{ points_balance: '540' }]); // final read after tx

    const now = Date.now();
    const res = await request(app).post('/api/v1/mining/session').send({
      wallet,
      auth_token: 'token-1',
      uptime_minutes: 60,
      storage_mb: 120,
      paid_boost_multiplier: 1.0,
      session_started_at: now - 3_600_000,
      session_ended_at: now,
    });

    expect(res.status).toBe(200);
    expect(res.body.session_id).toBe('session-dup');
    expect(res.body.balance).toBe(540);
    expect(res.body.points_earned).toBe(0);
    expect(res.body.duplicate).toBe(true);
  });

  it('POST /session should accept walletAddress and authToken aliases', async () => {
    const app = makeApp();
    const mockedQuery = query as jest.MockedFunction<typeof query>;
    const mockedTransaction = transaction as jest.MockedFunction<typeof transaction>;

    mockedTransaction.mockImplementation(async (callback: any) => {
      const client = {
        query: jest
          .fn()
          .mockResolvedValueOnce({ rows: [{ token: 'token-2' }] }) // validate auth token
          .mockResolvedValueOnce({ rows: [] }) // upsert user
          .mockResolvedValueOnce({ rows: [] }) // existing session check
          .mockResolvedValueOnce({ rows: [{ points_balance: '10' }] }) // current balance
          .mockResolvedValueOnce({ rows: [{ id: 'session-alias' }] }) // insert session
          .mockResolvedValueOnce({ rows: [] }), // update user balance
      };
      return callback(client);
    });

    mockedQuery.mockResolvedValueOnce([{ points_balance: '70' }]); // final read

    const now = Date.now();
    const res = await request(app).post('/api/v1/mining/session').send({
      walletAddress: wallet,
      authToken: 'token-2',
      uptimeMinutes: 5,
      storageMb: 120,
      paidBoostMultiplier: 1.0,
      sessionStartedAt: now - 300_000,
      sessionEndedAt: now,
    });

    expect(res.status).toBe(200);
    expect(res.body.session_id).toBe('session-alias');
    expect(res.body.balance).toBe(70);
    expect(res.body.duplicate).toBe(false);
  });
});
