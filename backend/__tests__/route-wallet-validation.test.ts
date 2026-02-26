import express from 'express';
import request from 'supertest';

jest.mock('../src/database', () => ({
  query: jest.fn(),
  transaction: jest.fn(),
}));

import nightRouter from '../src/routes/night';
import nftRouter from '../src/routes/nft';
import paymentRouter from '../src/routes/payment';
import { errorHandler } from '../src/middleware/errorHandler';

function makeApp() {
  const app = express();
  app.use(express.json());
  app.use('/api/v1/night', nightRouter);
  app.use('/api/v1/nft', nftRouter);
  app.use('/api/v1/payment', paymentRouter);
  app.use(errorHandler);
  return app;
}

describe('Route wallet validation', () => {
  it('night/start should reject invalid wallet format', async () => {
    const app = makeApp();
    const res = await request(app).post('/api/v1/night/start').send({
      walletAddress: 'invalid-wallet',
    });
    expect(res.status).toBe(400);
    expect(res.body.error).toContain('invalid wallet format');
  });

  it('nft/check-eligibility should reject invalid wallet format', async () => {
    const app = makeApp();
    const res = await request(app).post('/api/v1/nft/check-eligibility').send({
      wallet: 'invalid-wallet',
      skrUsername: 'demo.skr',
    });
    expect(res.status).toBe(400);
    expect(res.body.error).toContain('invalid wallet format');
  });

  it('payment/activate-boost should reject invalid wallet format before RPC checks', async () => {
    const app = makeApp();
    const res = await request(app).post('/api/v1/payment/activate-boost').send({
      txHash: '11111111111111111111111111111111111111111111111111111111111111111111111111111111',
      wallet: 'invalid-wallet',
      boostId: 'boost_7h',
    });
    expect(res.status).toBe(400);
    expect(res.body.error).toContain('Invalid wallet address format');
  });
});
