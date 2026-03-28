/**
 * Seeker Device NFT verification utility.
 *
 * Checks if a wallet holds the Seeker hardware device NFT issued by Solana Mobile.
 * These are soulbound Token-2022 NFTs — one per Seeker device.
 *
 * Mint Authority: GT2zuHVaZQYZSyQMgJPLzvkmyztfyXg2NJunqFp4p3A4
 * Program: Token-2022 (TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb)
 */

import { Connection, PublicKey } from '@solana/web3.js';
import { logger } from './logger';
import { getRedis } from '../redis';
import { CacheKeys, CacheTTL } from './cacheKeys';
import { query } from '../database';

const SOLANA_RPC_URL = process.env.SOLANA_RPC_URL || 'https://api.mainnet-beta.solana.com';
const TOKEN_2022_PROGRAM_ID = 'TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb';

const SEEKER_DEVICE_MINT_AUTHORITY =
  process.env.SEEKER_DEVICE_NFT_MINT_AUTHORITY || 'GT2zuHVaZQYZSyQMgJPLzvkmyztfyXg2NJunqFp4p3A4';

const connection = new Connection(SOLANA_RPC_URL, 'finalized');

/**
 * Verify if the wallet holds a Seeker device NFT (Token-2022, non-transferable).
 * Checks Redis cache first; on miss, queries Solana RPC and caches the result.
 */
export async function verifyDeviceNft(walletAddress: string): Promise<boolean> {
  // Check cache
  const redis = getRedis();
  if (redis) {
    try {
      const cached = await redis.get(CacheKeys.deviceNft(walletAddress));
      if (cached !== null) {
        return cached === 'true';
      }
    } catch {
      // Redis unavailable — proceed to RPC
    }
  }

  const result = await queryDeviceNft(walletAddress);

  // Cache the result
  if (redis) {
    try {
      await redis.setex(CacheKeys.deviceNft(walletAddress), CacheTTL.DEVICE_NFT_S, result ? 'true' : 'false');
    } catch {
      // Cache write failure is non-fatal
    }
  }

  return result;
}

async function queryDeviceNft(walletAddress: string): Promise<boolean> {
  try {
    const response = await (connection as any)._rpcRequest('getTokenAccountsByOwner', [
      walletAddress,
      { programId: TOKEN_2022_PROGRAM_ID },
      { encoding: 'jsonParsed', commitment: 'finalized' },
    ]);

    const accounts: any[] = response?.result?.value ?? [];

    for (const account of accounts) {
      const info = account?.account?.data?.parsed?.info;
      if (!info) continue;

      const extensions: any[] = info.extensions ?? [];
      const isNonTransferable = extensions.some(
        (ext: any) => ext.extension === 'nonTransferable'
      );
      if (!isNonTransferable) continue;

      // Verify mint authority matches Seeker device NFT
      const mintAddress = info.mint as string;
      if (!mintAddress) continue;

      const mintInfo = await (connection as any)._rpcRequest('getAccountInfo', [
        mintAddress,
        { encoding: 'jsonParsed', commitment: 'finalized' },
      ]);

      const mintAuthority = mintInfo?.result?.value?.data?.parsed?.info?.mintAuthority;
      if (mintAuthority === SEEKER_DEVICE_MINT_AUTHORITY) {
        logger.info('Seeker device NFT verified', {
          wallet: walletAddress.slice(0, 8) + '...',
          mint: mintAddress.slice(0, 8) + '...',
        });
        return true;
      }
    }

    return false;
  } catch (err) {
    logger.error('Solana RPC failed for device NFT check — trying DB fallback', {
      wallet: walletAddress.slice(0, 8) + '...',
      err,
    });
    // Fallback: use last known DB value (updated by GET /nft/device-status)
    try {
      const rows = await query<{ has_device_nft: boolean }>(
        'SELECT COALESCE(has_device_nft, false) AS has_device_nft FROM users WHERE wallet_address = $1',
        [walletAddress]
      );
      const cached = rows[0]?.has_device_nft ?? false;
      logger.warn('Using DB-cached device NFT status (RPC unavailable)', {
        wallet: walletAddress.slice(0, 8) + '...',
        cached,
      });
      return cached;
    } catch (dbErr) {
      logger.error('DB fallback also failed for device NFT check', { dbErr });
      throw err; // re-throw original RPC error
    }
  }
}

/**
 * Invalidate the cached device NFT status for a wallet.
 */
export async function invalidateDeviceNftCache(walletAddress: string): Promise<void> {
  const redis = getRedis();
  if (redis) {
    try {
      await redis.del(CacheKeys.deviceNft(walletAddress));
    } catch {
      // Non-fatal
    }
  }
}
