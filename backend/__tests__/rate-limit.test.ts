import express from 'express';
import request from 'supertest';
import { createSimpleRateLimit } from '../src/middleware/rateLimit';

describe('simple rate limiter', () => {
  it('should return 429 after max requests in window', async () => {
    const app = express();
    app.use(
      createSimpleRateLimit({
        windowMs: 60_000,
        max: 2,
        methods: ['POST'],
        keyFn: (req) => `test:${req.path}`,
      })
    );
    app.post('/hit', (_req, res) => res.json({ ok: true }));

    const r1 = await request(app).post('/hit').send({});
    const r2 = await request(app).post('/hit').send({});
    const r3 = await request(app).post('/hit').send({});

    expect(r1.status).toBe(200);
    expect(r2.status).toBe(200);
    expect(r3.status).toBe(429);
    expect(r3.body.error).toBe('Too many requests');
    expect(Number(r3.headers['retry-after'])).toBeGreaterThan(0);
  });
});
