import express from 'express';
import request from 'supertest';

jest.mock('../src/database', () => ({
  query: jest.fn(),
  transaction: jest.fn(),
}));

jest.mock('../src/utils/solanaAuth', () => ({
  buildAuthMessage: jest.fn(),
  verifySolanaSignatureHex: jest.fn(),
}));

import userRouter from '../src/routes/user';
import { errorHandler } from '../src/middleware/errorHandler';
import { query, transaction } from '../src/database';
import { buildAuthMessage, verifySolanaSignatureHex } from '../src/utils/solanaAuth';

const wallet = '9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin';

function makeApp() {
  const app = express();
  app.use(express.json());
  app.use('/api/v1/user', userRouter);
  app.use(errorHandler);
  return app;
}

describe('User auth routes', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('POST /auth/challenge should reject missing wallet', async () => {
    const app = makeApp();
    const res = await request(app).post('/api/v1/user/auth/challenge').send({});
    expect(res.status).toBe(400);
    expect(res.body.error).toContain('walletAddress (or wallet) is required');
  });

  it('POST /auth/challenge should reject invalid wallet format', async () => {
    const app = makeApp();
    const res = await request(app)
      .post('/api/v1/user/auth/challenge')
      .send({ walletAddress: 'not-a-solana-wallet-address-@@@@' });
    expect(res.status).toBe(400);
    expect(res.body.error).toContain('invalid wallet format');
  });

  it('POST /auth/challenge should create nonce and message', async () => {
    const app = makeApp();
    const mockedQuery = query as jest.MockedFunction<typeof query>;
    const mockedBuildAuthMessage = buildAuthMessage as jest.MockedFunction<typeof buildAuthMessage>;

    mockedQuery
      .mockResolvedValueOnce([]) // delete expired/used
      .mockResolvedValueOnce([{ nonce: 'nonce-1', expires_at: '2099-01-01T00:00:00.000Z' }]) // insert
      .mockResolvedValueOnce([]); // update message
    mockedBuildAuthMessage.mockReturnValue('test-message');

    const res = await request(app)
      .post('/api/v1/user/auth/challenge')
      .send({ walletAddress: wallet });

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.nonce).toBe('nonce-1');
    expect(res.body.message).toBe('test-message');
  });

  it('POST /auth/challenge should accept wallet alias', async () => {
    const app = makeApp();
    const mockedQuery = query as jest.MockedFunction<typeof query>;
    const mockedBuildAuthMessage = buildAuthMessage as jest.MockedFunction<typeof buildAuthMessage>;

    mockedQuery
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([{ nonce: 'nonce-2', expires_at: '2099-01-01T00:00:00.000Z' }])
      .mockResolvedValueOnce([]);
    mockedBuildAuthMessage.mockReturnValue('test-message-2');

    const res = await request(app)
      .post('/api/v1/user/auth/challenge')
      .send({ wallet });

    expect(res.status).toBe(200);
    expect(res.body.success).toBe(true);
    expect(res.body.nonce).toBe('nonce-2');
  });

  it('POST /auth/verify should reject invalid signature', async () => {
    const app = makeApp();
    const mockedTransaction = transaction as jest.MockedFunction<typeof transaction>;
    const mockedBuildAuthMessage = buildAuthMessage as jest.MockedFunction<typeof buildAuthMessage>;
    const mockedVerify = verifySolanaSignatureHex as jest.MockedFunction<typeof verifySolanaSignatureHex>;

    mockedBuildAuthMessage.mockReturnValue('test-message');
    mockedVerify.mockReturnValue(false);

    mockedTransaction.mockImplementation(async (callback: any) => {
      const client = {
        query: jest
          .fn()
          .mockResolvedValueOnce({
            rows: [
              {
                nonce: 'nonce-1',
                message: 'test-message',
                expires_at: '2099-01-01T00:00:00.000Z',
                used_at: null,
              },
            ],
          }),
      };
      return callback(client);
    });

    const res = await request(app)
      .post('/api/v1/user/auth/verify')
      .send({
        walletAddress: wallet,
        nonce: 'nonce-1',
        signature: 'a'.repeat(128),
      });

    expect(res.status).toBe(401);
    expect(res.body.error).toContain('invalid wallet signature');
  });

  it('POST /auth/verify should accept wallet alias', async () => {
    const app = makeApp();
    const mockedTransaction = transaction as jest.MockedFunction<typeof transaction>;
    const mockedBuildAuthMessage = buildAuthMessage as jest.MockedFunction<typeof buildAuthMessage>;
    const mockedVerify = verifySolanaSignatureHex as jest.MockedFunction<typeof verifySolanaSignatureHex>;

    mockedBuildAuthMessage.mockReturnValue('test-message');
    mockedVerify.mockReturnValue(false);

    mockedTransaction.mockImplementation(async (callback: any) => {
      const client = {
        query: jest
          .fn()
          .mockResolvedValueOnce({
            rows: [
              {
                nonce: 'nonce-2',
                message: 'test-message',
                expires_at: '2099-01-01T00:00:00.000Z',
                used_at: null,
              },
            ],
          }),
      };
      return callback(client);
    });

    const res = await request(app)
      .post('/api/v1/user/auth/verify')
      .send({
        wallet,
        nonce: 'nonce-2',
        signature: 'b'.repeat(128),
      });

    expect(res.status).toBe(401);
    expect(res.body.error).toContain('invalid wallet signature');
  });
});
