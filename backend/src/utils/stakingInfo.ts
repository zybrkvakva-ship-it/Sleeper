/**
 * SKR Guardian Staking info utility.
 *
 * Fetches staked SKR balance for a wallet from the Guardian staking program on Solana.
 *
 * Program: SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ
 * Account layout (from SolanaRpcClient.kt):
 *   owner pubkey @ offset 41 (32 bytes)
 *   amount u64 LE @ offset 73
 *   dataSize = 169
 */

import { Connection } from '@solana/web3.js';
import { logger } from './logger';
import { getRedis } from '../redis';
import { CacheKeys, CacheTTL } from './cacheKeys';

const SOLANA_RPC_URL = process.env.SOLANA_RPC_URL || 'https://api.mainnet-beta.solana.com';
const SKR_STAKING_PROGRAM_ID =
  process.env.SKR_STAKING_PROGRAM_ID || 'SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ';
const SKR_DECIMALS = 6;

const connection = new Connection(SOLANA_RPC_URL, 'confirmed');

export interface StakingInfo {
  stakedAmountRaw: bigint;
  stakedAmountHuman: number;
  accountsFound: number;
  fetchedAt: Date;
}

/**
 * Fetch staked SKR amount for a wallet.
 * Checks Redis cache first (5min TTL); on miss queries Solana RPC.
 */
export async function fetchStakingInfo(walletAddress: string): Promise<StakingInfo> {
  const redis = getRedis();
  if (redis) {
    try {
      const cached = await redis.get(CacheKeys.stakingInfo(walletAddress));
      if (cached !== null) {
        return JSON.parse(cached, (key, value) =>
          key === 'stakedAmountRaw' ? BigInt(value) :
          key === 'fetchedAt' ? new Date(value) : value
        ) as StakingInfo;
      }
    } catch {
      // proceed to RPC
    }
  }

  const info = await queryStakingInfo(walletAddress);

  if (redis) {
    try {
      const serializable = {
        ...info,
        stakedAmountRaw: info.stakedAmountRaw.toString(),
        fetchedAt: info.fetchedAt.toISOString(),
      };
      await redis.setex(CacheKeys.stakingInfo(walletAddress), CacheTTL.STAKING_INFO_S, JSON.stringify(serializable));
    } catch {
      // non-fatal
    }
  }

  return info;
}

async function queryStakingInfo(walletAddress: string): Promise<StakingInfo> {
  try {
    // Encode wallet address bytes as base58 for memcmp filter
    const { PublicKey } = await import('@solana/web3.js');
    const walletPubkey = new PublicKey(walletAddress);
    const walletBytes = walletPubkey.toBytes();

    const response = await (connection as any)._rpcRequest('getProgramAccounts', [
      SKR_STAKING_PROGRAM_ID,
      {
        encoding: 'base64',
        commitment: 'confirmed',
        filters: [
          { dataSize: 169 },
          { memcmp: { offset: 41, bytes: walletPubkey.toBase58() } },
        ],
      },
    ]);

    const accounts: any[] = response?.result ?? [];
    let totalRaw = BigInt(0);

    for (const account of accounts) {
      const dataBase64: string = account?.account?.data?.[0];
      if (!dataBase64) continue;

      const dataBytes = Buffer.from(dataBase64, 'base64');
      if (dataBytes.length < 81) continue; // offset 73 + 8 bytes for u64

      // Read u64 LE at offset 73
      const amountRaw = dataBytes.readBigUInt64LE(73);
      totalRaw += amountRaw;
    }

    return {
      stakedAmountRaw: totalRaw,
      stakedAmountHuman: Number(totalRaw) / Math.pow(10, SKR_DECIMALS),
      accountsFound: accounts.length,
      fetchedAt: new Date(),
    };
  } catch (err) {
    logger.error('Failed to fetch SKR staking info', {
      wallet: walletAddress.slice(0, 8) + '...',
      err,
    });
    throw err;
  }
}

/**
 * Invalidate cached staking info for a wallet.
 */
export async function invalidateStakingCache(walletAddress: string): Promise<void> {
  const redis = getRedis();
  if (redis) {
    try {
      await redis.del(CacheKeys.stakingInfo(walletAddress));
    } catch {
      // non-fatal
    }
  }
}
