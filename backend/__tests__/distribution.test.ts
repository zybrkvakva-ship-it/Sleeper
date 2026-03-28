/**
 * Distribution scheduler tests
 * Tests runDailyDistribution() and distributeSleepTokens economy function
 */

jest.mock('../src/database', () => ({
  query: jest.fn(),
  transaction: jest.fn(),
}));

jest.mock('../src/websocket', () => ({
  broadcast: jest.fn(),
}));

// Mock leaderboard cache invalidation
jest.mock('../src/routes/leaderboard', () => ({
  invalidateLeaderboardCache: jest.fn().mockResolvedValue(undefined),
}));

import { runDailyDistribution } from '../src/services/distributionScheduler';
import { distributeSleepTokens } from '../src/economy';
import { query, transaction } from '../src/database';
import { broadcast } from '../src/websocket';
import { invalidateLeaderboardCache } from '../src/routes/leaderboard';

const WALLET_A = '9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin';
const WALLET_B = '4rBnMjXNMVK7PqQCjCqM9oXJhWtrFjbW6kUE3UKWdpnk';

describe('runDailyDistribution', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('skips if already distributed today', async () => {
    // First query (check existing) returns a row → already done
    (query as jest.Mock).mockResolvedValueOnce([{ id: 'existing-id' }]);

    await runDailyDistribution();

    // Should not proceed to sessions query
    expect(query).toHaveBeenCalledTimes(1);
    expect(transaction).not.toHaveBeenCalled();
    expect(broadcast).not.toHaveBeenCalled();
  });

  it('skips if no unprocessed sessions', async () => {
    // First query: no existing distribution
    (query as jest.Mock).mockResolvedValueOnce([]);
    // Second query: no sessions
    (query as jest.Mock).mockResolvedValueOnce([]);

    await runDailyDistribution();

    expect(transaction).not.toHaveBeenCalled();
    expect(broadcast).not.toHaveBeenCalled();
  });

  it('runs distribution and broadcasts on success', async () => {
    // 1. No existing distribution
    (query as jest.Mock).mockResolvedValueOnce([]);
    // 2. Return 2 sessions
    (query as jest.Mock).mockResolvedValueOnce([
      { wallet_address: WALLET_A, final_np: 200 },
      { wallet_address: WALLET_B, final_np: 100 },
    ]);
    // 3. Dust carry-over query
    (query as jest.Mock).mockResolvedValueOnce([{ dust: '0' }]);
    // 4. Season stats query (returns active season)
    (query as jest.Mock).mockResolvedValueOnce([{ season_number: 1, current_week: 3 }]);

    // transaction mock runs callback
    (transaction as jest.Mock).mockImplementation(async (cb: (client: any) => Promise<void>) => {
      const client = { query: jest.fn().mockResolvedValue({ rows: [] }) };
      await cb(client);
    });

    await runDailyDistribution();

    expect(transaction).toHaveBeenCalledTimes(1);
    expect(broadcast).toHaveBeenCalledTimes(1);
    expect(broadcast).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'sleep-distributed' })
    );
    expect(invalidateLeaderboardCache).toHaveBeenCalledTimes(1);
  });

  it('uses batch UPDATE (unnest) instead of per-row queries', async () => {
    // 1. No existing distribution
    (query as jest.Mock).mockResolvedValueOnce([]);
    // 2. Sessions
    (query as jest.Mock).mockResolvedValueOnce([
      { wallet_address: WALLET_A, final_np: 300 },
      { wallet_address: WALLET_B, final_np: 300 },
    ]);
    // 3. Dust carry-over
    (query as jest.Mock).mockResolvedValueOnce([{ dust: '0' }]);
    // 4. Season stats
    (query as jest.Mock).mockResolvedValueOnce([{ season_number: 1, current_week: 1 }]);

    let batchUpdateCallCount = 0;
    (transaction as jest.Mock).mockImplementation(async (cb: (client: any) => Promise<void>) => {
      const client = {
        query: jest.fn().mockImplementation((sql: string) => {
          // Count UPDATE queries inside transaction
          if (sql.includes('UPDATE') && sql.includes('unnest')) {
            batchUpdateCallCount++;
          }
          return { rows: [] };
        }),
      };
      await cb(client);
    });

    await runDailyDistribution();

    // Both night_sessions and users should be updated in one query each via unnest
    expect(batchUpdateCallCount).toBe(2);
  });

  it('broadcasts correct distribution data', async () => {
    (query as jest.Mock)
      .mockResolvedValueOnce([]) // no existing
      .mockResolvedValueOnce([{ wallet_address: WALLET_A, final_np: 500 }]) // sessions
      .mockResolvedValueOnce([{ dust: '0' }]) // dust carry-over
      .mockResolvedValueOnce([{ season_number: 2, current_week: 5 }]); // season

    (transaction as jest.Mock).mockImplementation(async (cb: (client: any) => Promise<void>) => {
      await cb({ query: jest.fn().mockResolvedValue({ rows: [] }) });
    });

    await runDailyDistribution();

    const broadcastCall = (broadcast as jest.Mock).mock.calls[0][0];
    expect(broadcastCall.type).toBe('sleep-distributed');
    expect(broadcastCall.usersCount).toBe(1);
    expect(typeof broadcastCall.totalNp).toBe('number');
    expect(typeof broadcastCall.poolNight).toBe('number');
  });
});

// ─── distributeSleepTokens (economy) ─────────────────────────────────────────

describe('distributeSleepTokens formula', () => {
  it('formula: user_np / total_np × pool = user_tokens', () => {
    const result = distributeSleepTokens({ [WALLET_A]: 200, [WALLET_B]: 100 }, 3000);
    // walletA: 200/300 * 3000 = 2000
    // walletB: 100/300 * 3000 = 1000
    expect(result[WALLET_A]).toBe(2000);
    expect(result[WALLET_B]).toBe(1000);
  });

  it('rounds down to integer (floor)', () => {
    // 1/3 of 100 = 33.33 → floor 33
    const result = distributeSleepTokens({ a: 1, b: 1, c: 1 }, 100);
    for (const tokens of Object.values(result)) {
      expect(Number.isInteger(tokens)).toBe(true);
    }
    // Total should be ≤ pool due to flooring
    const total = Object.values(result).reduce((s, v) => s + v, 0);
    expect(total).toBeLessThanOrEqual(100);
  });

  it('single user gets 100% of pool', () => {
    const result = distributeSleepTokens({ [WALLET_A]: 999 }, 5000);
    expect(result[WALLET_A]).toBe(5000);
  });

  it('returns {} for empty input', () => {
    expect(distributeSleepTokens({}, 5000)).toEqual({});
  });
});
