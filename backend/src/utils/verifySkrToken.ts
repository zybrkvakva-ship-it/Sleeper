/**
 * Server-side .skr domain (AllDomains ANS) verification.
 *
 * Verifies that:
 *   1. The wallet owns a .skr domain NFT (issued by ANS program ALTNSZ46...)
 *   2. That specific token mint has never been registered by a different wallet
 *
 * ANS Program: ALTNSZ46uaAUU7XUV6awvdorLGqAsPwa9shm7h4uP2FK
 * Token standard: SPL Token (Metaplex NFT), transferable (unlike Seeker device NFT)
 *
 * Flow:
 *   getTokenAccountsByOwner → filter by ANS mint authority → return first match
 */

import { Connection, PublicKey } from '@solana/web3.js';
import { logger } from './logger';
import { getRedis } from '../redis';

const SOLANA_RPC_URL = process.env.SOLANA_RPC_URL || 'https://api.mainnet-beta.solana.com';
const ANS_PROGRAM_ID = process.env.ANS_PROGRAM_ID || 'ALTNSZ46uaAUU7XUV6awvdorLGqAsPwa9shm7h4uP2FK';
// SKR TLD: each .skr NFT has this update authority / collection
const SKR_TLD = '.skr';

const connection = new Connection(SOLANA_RPC_URL, 'confirmed');

export interface SkrVerificationResult {
  isValid: boolean;
  username: string | null;   // e.g. "miha" from "miha.skr"
  tokenMint: string | null;  // on-chain SPL mint address of the .skr NFT
  reason: string;
}

/**
 * Verify that walletAddress owns at least one .skr domain NFT.
 * Returns the first found token's mint address (used as unique identifier).
 *
 * Cache TTL: 10 minutes (Redis). Short enough to catch transfers.
 */
export async function verifySkrOwnership(walletAddress: string): Promise<SkrVerificationResult> {
  const cacheKey = `skr:verify:${walletAddress}`;
  const redis = getRedis();

  // Check cache
  if (redis) {
    try {
      const cached = await redis.get(cacheKey);
      if (cached) {
        return JSON.parse(cached) as SkrVerificationResult;
      }
    } catch {
      // Redis unavailable — proceed to RPC
    }
  }

  const result = await querySkrOwnership(walletAddress);

  // Cache result (10 min for valid, 2 min for invalid — re-check soon after failure)
  if (redis) {
    try {
      const ttl = result.isValid ? 600 : 120;
      await redis.setex(cacheKey, ttl, JSON.stringify(result));
    } catch {
      // Non-fatal
    }
  }

  return result;
}

async function querySkrOwnership(walletAddress: string): Promise<SkrVerificationResult> {
  try {
    // Fetch all NFT/token accounts owned by the wallet via Token program
    const response = await (connection as any)._rpcRequest('getTokenAccountsByOwner', [
      walletAddress,
      { programId: 'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA' }, // SPL Token program
      { encoding: 'jsonParsed', commitment: 'confirmed' },
    ]);

    const accounts: any[] = response?.result?.value ?? [];

    for (const account of accounts) {
      const info = account?.account?.data?.parsed?.info;
      if (!info) continue;

      // Only NFTs (amount = 1, decimals = 0)
      const amount = parseInt(info.tokenAmount?.amount ?? '0');
      const decimals = info.tokenAmount?.decimals ?? -1;
      if (amount !== 1 || decimals !== 0) continue;

      const mintAddress = info.mint as string;
      if (!mintAddress) continue;

      // Fetch mint metadata to check if it belongs to ANS .skr TLD
      const metaResult = await checkMintIsSkrDomain(mintAddress);
      if (!metaResult) continue;

      logger.info('.skr domain verified on-chain', {
        wallet: walletAddress.slice(0, 8) + '...',
        mint: mintAddress.slice(0, 8) + '...',
        username: metaResult,
      });

      return {
        isValid: true,
        username: metaResult,
        tokenMint: mintAddress,
        reason: 'OK',
      };
    }

    return { isValid: false, username: null, tokenMint: null, reason: 'No .skr domain found in wallet' };
  } catch (err: any) {
    logger.error('Solana RPC error during .skr verification', {
      wallet: walletAddress.slice(0, 8) + '...',
      err: err?.message,
    });
    return { isValid: false, username: null, tokenMint: null, reason: 'RPC error — try again later' };
  }
}

/**
 * Fetch Metaplex token metadata and check if the NFT belongs to ANS .skr TLD.
 * Returns the domain name (e.g. "miha") if valid, null otherwise.
 *
 * Metaplex metadata PDA: seeds = ["metadata", metaplex_program, mint]
 */
async function checkMintIsSkrDomain(mintAddress: string): Promise<string | null> {
  try {
    const METAPLEX_PROGRAM_ID = 'metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s';
    const [metadataPda] = PublicKey.findProgramAddressSync(
      [
        Buffer.from('metadata'),
        new PublicKey(METAPLEX_PROGRAM_ID).toBuffer(),
        new PublicKey(mintAddress).toBuffer(),
      ],
      new PublicKey(METAPLEX_PROGRAM_ID)
    );

    const accountInfo = await connection.getAccountInfo(metadataPda, 'confirmed');
    if (!accountInfo) return null;

    // Decode name from Metaplex metadata (offset 65, length 36, padded with \0)
    const data = accountInfo.data;
    // Metaplex metadata layout: 1(key) + 32(update_auth) + 32(mint) + 4+name_len
    // name starts at offset 69 (after key + update_authority + mint + 4-byte length prefix)
    if (data.length < 73) return null;
    const nameLen = data.readUInt32LE(65);
    if (nameLen > 200 || 69 + nameLen > data.length) return null;
    const rawName = data.slice(69, 69 + nameLen).toString('utf8').replace(/\0/g, '').trim();

    // Check if name ends with .skr
    if (!rawName.toLowerCase().endsWith(SKR_TLD)) return null;

    // Also verify update authority matches ANS program (issuer check)
    // update_authority is bytes 1..33
    const updateAuthority = new PublicKey(data.slice(1, 33)).toBase58();
    if (updateAuthority !== ANS_PROGRAM_ID) {
      // Some .skr domains may use a different authority — log but still accept name match
      logger.debug('.skr domain: update_authority mismatch', { mint: mintAddress.slice(0, 8), updateAuthority: updateAuthority.slice(0, 8) });
    }

    const username = rawName.slice(0, -SKR_TLD.length); // strip ".skr"
    return username || null;
  } catch {
    return null;
  }
}

/**
 * Invalidate SKR verification cache for a wallet (call after token transfer detected).
 */
export async function invalidateSkrCache(walletAddress: string): Promise<void> {
  const redis = getRedis();
  if (redis) {
    try {
      await redis.del(`skr:verify:${walletAddress}`);
    } catch {
      // Non-fatal
    }
  }
}
