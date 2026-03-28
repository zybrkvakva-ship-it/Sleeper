import { Router } from 'express';
import { query, transaction as dbTransaction } from '../database';
import { AppError } from '../middleware/errorHandler';
import { logger } from '../utils/logger';
import { isValidSolanaAddress, pickFirstString, pickWallet } from '../utils/solanaAddress';
import { mintGenesisNft } from '../utils/candyMachine';
import { verifyTokenTransfer } from '../utils/verifyTokenTransfer';
import { getRedis } from '../redis';
import { CacheKeys, CacheTTL } from '../utils/cacheKeys';

const TREASURY_WALLET = process.env.TREASURY_WALLET;
const SKR_TOKEN_MINT = process.env.SKR_TOKEN_MINT;
const GENESIS_NFT_PRICE_SKR = parseFloat(process.env.GENESIS_NFT_PRICE_SKR || '500');
const GENESIS_NFT_PRICE_RAW = BigInt(Math.floor(GENESIS_NFT_PRICE_SKR * 1_000_000));

const router = Router();

/**
 * POST /api/v1/nft/check-eligibility
 * Check if user can mint Genesis NFT
 * REQUIREMENTS:
 * - Wallet connected & approved
 * - Owns .skr domain
 * - Doesn't already own Genesis NFT
 * - NFT supply not exhausted
 */
router.post('/check-eligibility', async (req, res, next) => {
  try {
    const walletAddress = pickWallet(req.body);
    const skrUsername = pickFirstString(req.body as Record<string, unknown>, ['skrUsername', 'skr_username']);
    
    if (!walletAddress) {
      throw new AppError(400, 'walletAddress (or wallet) is required');
    }
    if (!isValidSolanaAddress(walletAddress)) {
      throw new AppError(400, 'invalid wallet format');
    }
    
    // Check if user exists and has .skr domain
    const users = await query(
      'SELECT has_genesis_nft, skr_username FROM users WHERE wallet_address = $1',
      [walletAddress]
    );
    
    if (users.length === 0) {
      throw new AppError(404, 'User not found. Please register first.');
    }
    
    const user = users[0];
    
    // Check if already owns NFT
    if (user.has_genesis_nft) {
      return res.json({
        success: false,
        eligible: false,
        reason: 'You already own a Genesis NFT'
      });
    }
    
    // Check if has .skr domain
    if (!user.skr_username || !skrUsername) {
      return res.json({
        success: false,
        eligible: false,
        reason: 'You must own a .skr domain to mint Genesis NFT'
      });
    }
    
    // Check NFT supply
    const nftCount = await query(
      'SELECT COUNT(*) as count FROM users WHERE has_genesis_nft = true'
    );
    const minted = parseInt(nftCount[0]?.count || '0');
    
    if (minted >= 10000) {
      return res.json({
        success: false,
        eligible: false,
        reason: 'Genesis NFT supply exhausted (10,000/10,000)'
      });
    }
    
    res.json({
      success: true,
      eligible: true,
      skrUsername: user.skr_username,
      nftsMinted: minted,
      nftsRemaining: 10000 - minted
    });
    
  } catch (error) {
    next(error);
  }
});

/**
 * GET /api/v1/nft/supply
 * Get current NFT supply stats
 */
router.get('/supply', async (req, res, next) => {
  try {
    const result = await query(
      'SELECT COUNT(*) as count FROM users WHERE has_genesis_nft = true'
    );
    const minted = parseInt(result[0]?.count || '0');
    
    res.json({
      success: true,
      total: 10000,
      minted,
      remaining: 10000 - minted
    });
    
  } catch (error) {
    next(error);
  }
});

/**
 * POST /api/v1/nft/mint
 * Mint Genesis NFT via Metaplex Candy Machine.
 *
 * Requires CANDY_MACHINE_ID and TREASURY_KEYPAIR to be set.
 * The wallet must pass eligibility checks (owns .skr domain, no existing NFT, supply remaining).
 */
router.post('/mint', async (req, res, next) => {
  try {
    const walletAddress = pickWallet(req.body);
    const authToken = pickFirstString(req.body as Record<string, unknown>, ['authToken', 'auth_token']);
    const paymentTxHash = pickFirstString(req.body as Record<string, unknown>, ['txHash', 'tx_hash', 'paymentTxHash']);

    if (!walletAddress || !authToken || !paymentTxHash) {
      throw new AppError(400, 'Missing required fields: walletAddress, authToken, txHash (payment proof)');
    }
    if (!/^[1-9A-HJ-NP-Za-km-z]{87,88}$/.test(paymentTxHash)) {
      throw new AppError(400, 'Invalid payment transaction hash format');
    }
    if (!isValidSolanaAddress(walletAddress)) {
      throw new AppError(400, 'Invalid wallet address format');
    }

    // Validate auth token (long-lived token issued after signature verification)
    const tokenRows = await query(
      `SELECT wallet_address FROM wallet_auth_tokens
       WHERE wallet_address = $1 AND token = $2
         AND revoked_at IS NULL AND expires_at > NOW()
       LIMIT 1`,
      [walletAddress, authToken]
    );
    if (tokenRows.length === 0) {
      throw new AppError(401, 'Invalid or expired auth token');
    }

    // Re-check eligibility (idempotent guard)
    const users = await query(
      'SELECT has_genesis_nft, skr_username, genesis_nft_mint, genesis_nft_number FROM users WHERE wallet_address = $1',
      [walletAddress]
    );
    if (users.length === 0) {
      throw new AppError(404, 'User not found. Please register first.');
    }
    const user = users[0];

    // Already minted — return existing data (idempotent)
    if (user.has_genesis_nft && user.genesis_nft_mint) {
      return res.json({
        success: true,
        alreadyMinted: true,
        nftMint: user.genesis_nft_mint,
        nftNumber: user.genesis_nft_number ?? 0,
        txHash: '',
        message: 'You already own a Genesis NFT',
      });
    }
    if (!user.skr_username) {
      throw new AppError(403, 'You must own a .skr domain to mint Genesis NFT');
    }

    // Verify Candy Machine is configured (before acquiring lock)
    if (!process.env.CANDY_MACHINE_ID || !process.env.TREASURY_KEYPAIR) {
      throw new AppError(503, 'NFT minting is not configured on this server');
    }
    if (!TREASURY_WALLET || !SKR_TOKEN_MINT) {
      throw new AppError(503, 'Payment verification is not configured on this server');
    }

    // Verify on-chain SKR payment before minting
    await verifyTokenTransfer({
      txHash: paymentTxHash,
      signerWallet: walletAddress,
      treasuryWallet: TREASURY_WALLET,
      tokenMint: SKR_TOKEN_MINT,
      expectedAmountRaw: GENESIS_NFT_PRICE_RAW,
      tolerancePercent: 1,
      commitment: 'confirmed',
    });

    // Execute mint + persist atomically with advisory lock to prevent supply race
    const { txHash: mintTxHash, nftMint, nftNumber } = await dbTransaction(async (client) => {
      // Advisory lock serialises all concurrent mint attempts
      await client.query(`SELECT pg_advisory_xact_lock(hashtext('genesis_nft_mint'))`);

      // Re-check supply under lock
      const countRow = await client.query<{ count: string }>(
        'SELECT COUNT(*)::text AS count FROM users WHERE has_genesis_nft = true'
      );
      const minted = parseInt(countRow.rows[0]?.count || '0');
      if (minted >= 10000) {
        throw new AppError(410, 'Genesis NFT supply exhausted (10,000/10,000)');
      }

      // Mint happens outside DB but inside the lock window
      const mintResult = await mintGenesisNft(walletAddress);

      await client.query(
        `UPDATE users SET
           has_genesis_nft = true,
           genesis_nft_mint = $1,
           genesis_nft_number = $2,
           genesis_nft_purchased_at = NOW()
         WHERE wallet_address = $3`,
        [mintResult.nftMint, mintResult.nftNumber, walletAddress]
      );
      await client.query(
        `INSERT INTO payments (wallet_address, tx_hash, payment_type, amount, nft_mint, nft_number, verified, verified_at)
         VALUES ($1, $2, 'GENESIS_NFT', $3, $4, $5, true, NOW())
         ON CONFLICT (tx_hash) DO NOTHING`,
        [walletAddress, paymentTxHash, GENESIS_NFT_PRICE_SKR, mintResult.nftMint, mintResult.nftNumber]
      );

      return mintResult;
    });

    logger.info('Genesis NFT minted and recorded', {
      wallet: walletAddress.slice(0, 8) + '...',
      nftMint,
      nftNumber,
      paymentTxHash: paymentTxHash.slice(0, 16) + '...',
    });

    res.json({
      success: true,
      txHash: mintTxHash,
      nftMint,
      nftNumber,
      message: 'Genesis NFT minted successfully',
    });

  } catch (error) {
    next(error);
  }
});

/**
 * GET /api/v1/nft/device-status/:walletAddress
 * Check if a wallet holds a Seeker hardware device NFT (issued by Solana Mobile).
 * Returns cached result when available (TTL 1h).
 */
router.get('/device-status/:walletAddress', async (req, res, next) => {
  try {
    const { walletAddress } = req.params;
    if (!isValidSolanaAddress(walletAddress)) {
      throw new AppError(400, 'Invalid wallet address format');
    }

    const { verifyDeviceNft } = await import('../utils/verifyDeviceNft');

    // Check if we have a cached value in Redis first (avoid re-querying RPC)
    const redis = getRedis();
    let cached = false;
    let hasDeviceNft = false;

    if (redis) {
      try {
        const { CacheKeys } = await import('../utils/cacheKeys');
        const cachedVal = await redis.get(CacheKeys.deviceNft(walletAddress));
        if (cachedVal !== null) {
          hasDeviceNft = cachedVal === 'true';
          cached = true;
        }
      } catch {
        // proceed to RPC
      }
    }

    if (!cached) {
      hasDeviceNft = await verifyDeviceNft(walletAddress);
    }

    // Also update users DB if user exists
    if (!cached) {
      await query(
        `UPDATE users SET has_device_nft = $1, device_nft_verified_at = NOW()
         WHERE wallet_address = $2`,
        [hasDeviceNft, walletAddress]
      ).catch(() => {
        // user may not be registered — non-fatal
      });
    }

    res.json({ success: true, hasDeviceNft, cached });
  } catch (error) {
    next(error);
  }
});

export default router;
