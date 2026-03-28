import express from 'express';
import request from 'supertest';

import miningRouter from '../src/routes/mining';
import { errorHandler } from '../src/middleware/errorHandler';

function makeApp() {
  const app = express();
  app.use(express.json());
  app.use('/api/v1/mining', miningRouter);
  app.use(errorHandler);
  return app;
}

describe('Mining session route (deprecated)', () => {
  it('POST /session returns 410 Gone', async () => {
    const app = makeApp();
    const res = await request(app).post('/api/v1/mining/session').send({
      wallet: '9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin',
      auth_token: 'any-token',
    });
    expect(res.status).toBe(410);
    expect(res.body.success).toBe(false);
    expect(res.body.migration).toBe('/api/v1/night/end');
  });
});
