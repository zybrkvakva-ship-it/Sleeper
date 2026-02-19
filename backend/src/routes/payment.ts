import { Router } from 'express';
import { query, transaction as dbTransaction } from '../database';
import { AppError } from '../middleware/errorHandler';
import { logger } from '../utils/logger';
import { Connection, PublicKey, Transaction } from '@solana/web3.js';
import { SkrBoostLevel, SKR_BOOST_CONFIG } from '../economy/constants';

const router = Router();

// Solana connection
const SOLANA_RPC_URL = process.env.SOLANA_RPC_URL || 'https://api.mainnet-beta.solana.com';
const connection = new Connection(SOLANA_RPC_URL, 'confirmed');

// Treasury wallet from env
const TREASURY_WALLET = process.env.TREASURY_WALLET;

/**
 * POST /api/v1/payment/activate-boost
 * Activate SKR boost after on-chain payment
 * 
 * Request:
 * - txHash: transaction hash (base58, 88 chars)
 * - walletAddress: user's wallet (base58, 44 chars)
 * - boostId: boost ID (e.g., "boost_7h", "boost_7x", "boost_49x")
 * - signature: optional, for future use
 * 
 * Flow:
 * 1. Verify transaction exists on Solana
 * 2. Verify sender is walletAddress
 * 3. Verify recipient is TREASURY_WALLET
 * 4. Verify amount matches boost price
 * 5. Record payment and activate boost
 */
router.post('/activate-boost', async (req, res, next) => {
  const client = new AbortController();
  const timeoutId = setTimeout(() => client.abort(), 30000); // 30s timeout

  try {
    const { txHash, walletAddress, boostId } = req.body as {
      txHash: string;
      walletAddress: string;
      boostId: string;
      signature?: string;
    };

    // Validate required fields
    if (!txHash || !walletAddress || !boostId) {
      throw new AppError(400, 'Missing required fields: txHash, walletAddress, boostId');
    }

    // Validate txHash format (88 chars base58)
    if (txHash.length !== 88) {
      throw new AppError(400, 'Invalid transaction hash format');
    }

    // Validate wallet address format (32-44 chars base58)
    if (walletAddress.length < 32 || walletAddress.length > 44) {
      throw new AppError(400, 'Invalid wallet address format');
    }

    // Validate boostId exists in catalog
    const boostConfig = getBoostConfig(boostId);
    if (!boostConfig) {
      throw new AppError(400, `Unknown boost ID: ${boostId}`);
    }

    logger.info('Boost activation request', {
      txHash: txHash.slice(0, 16) + '...',
      walletAddress: walletAddress.slice(0, 8) + '...',
      boostId,
      expectedAmount: boostConfig.price
    });

    // Step 1: Verify transaction on Solana
    const txInfo = await connection.getTransaction(txHash, {
      maxSupportedTransactionVersion: 0,
      commitment: 'confirmed'
    });

    if (!txInfo) {
      throw new AppError(404, 'Transaction not found on Solana');
    }

    // Step 2: Verify transaction was successful
    if (txInfo.meta?.err) {
      throw new AppError(400, 'Transaction failed on Solana: ' + JSON.stringify(txInfo.meta.err));
    }

    // Step 3: Verify sender is walletAddress
    const accountKeys = txInfo.transaction.message.getAccountKeys();
    const signerKeys = accountKeys.keySegments().flat();
    
    const senderIndex = signerKeys.findIndex(key => key.toBase58() === walletAddress);
    if (senderIndex === -1) {
      throw new AppError(400, 'Wallet address is not a signer in the transaction');
    }

    // Step 4: Verify recipient is TREASURY_WALLET (if configured)
    if (TREASURY_WALLET) {
      const treasuryKey = new PublicKey(TREASURY_WALLET);
      const treasuryIndex = signerKeys.findIndex(key => key.equals(treasuryKey));
      if (treasuryIndex === -1) {
        throw new AppError(400, 'Treasury wallet not found in transaction');
      }
    }

    // Step 5: Verify amount matches boost price
    // Parse pre/post token balances for SKR token
    const skrMint = process.env.SKR_TOKEN_MINT;
    let transferredAmount = 0;

    if (txInfo.meta?.preTokenBalances && txInfo.meta?.postTokenBalances) {
      for (let i = 0; i < txInfo.meta.preTokenBalances.length; i++) {
        const preBalance = txInfo.meta.preTokenBalances[i];
        const postBalance = txInfo.meta.postTokenBalances[i];
        
        // Check if this is SKR token account
        if (preBalance.mint === skrMint) {
          const preAmount = parseInt(preBalance.uiTokenAmount.amount);
          const postAmount = parseInt(postBalance?.uiTokenAmount.amount || '0');
          const diff = preAmount - postAmount;
          
          if (diff > 0) {
            transferredAmount = diff;
            break;
          }
        }
      }
    }

    // Convert expected price to token units (6 decimals)
    const expectedAmountRaw = Math.floor(boostConfig.price * 1_000_000);
    
    // Allow 1% tolerance for rounding
    const tolerance = Math.floor(expectedAmountRaw * 0.01);
    if (Math.abs(transferredAmount - expectedAmountRaw) > tolerance) {
      throw new AppError(
        400,
        `Amount mismatch: expected ${expectedAmountRaw}, got ${transferredAmount}`
      );
    }

    logger.info('Transaction verified successfully', {
      txHash: txHash.slice(0, 16) + '...',
      transferredAmount,
      expectedAmountRaw
    });

    // Step 6: Calculate boost duration
    const boostDurationMs = getBoostDurationMs(boostId);
    const boostExpiresAt = new Date(Date.now() + boostDurationMs);

    // Step 7: Record payment and activate boost in database
    await dbTransaction(async (client) => {
      // Insert payment record
      await client.query(
        `INSERT INTO payments (
          wallet_address, tx_hash, payment_type, amount,
          boost_level, boost_expires_at, verified, verified_at
        ) VALUES ($1, $2, $3, $4, $5, $6, $7, NOW())
        ON CONFLICT (tx_hash) DO UPDATE SET
          verified = EXCLUDED.verified,
          verified_at = EXCLUDED.verified_at`,
        [
          walletAddress,
          txHash,
          'SKR_BOOST',
          boostConfig.price,
          boostId,
          boostExpiresAt,
          true
        ]
      );

      // Update user's active boost (if they have a better boost, keep it)
      const existingBoost = await client.query(
        `SELECT boost_level, boost_expires_at FROM payments
         WHERE wallet_address = $1 AND payment_type = 'SKR_BOOST'
         AND boost_expires_at > NOW()
         ORDER BY boost_expires_at DESC LIMIT 1`,
        [walletAddress]
      );

      // Only update if new boost expires later
      if (existingBoost.rows.length === 0 || 
          new Date(existingBoost.rows[0].boost_expires_at) < boostExpiresAt) {
        await client.query(
          `UPDATE users SET 
            last_active_at = NOW()
           WHERE wallet_address = $1`,
          [walletAddress]
        );
      }
    });

    logger.info('Boost activated successfully', {
      walletAddress: walletAddress.slice(0, 8) + '...',
      boostId,
      expiresAt: boostExpiresAt.toISOString()
    });

    res.status(200).json({
      success: true,
      boostId,
      boostLevel: boostConfig.boost,
      expiresAt: boostExpiresAt.toISOString(),
      message: 'Boost activated successfully'
    });

  } catch (error) {
    if (error instanceof AppError) {
      next(error);
    } else if (error instanceof Error) {
      logger.error('Boost activation failed', {
        error: error.message,
        stack: error.stack
      });
      next(new AppError(500, 'Boost activation failed: ' + error.message));
    } else {
      next(error);
    }
  } finally {
    clearTimeout(timeoutId);
  }
});

/**
 * GET /api/v1/payment/active-boost/:walletAddress
 * Get user's active SKR boost
 */
router.get('/active-boost/:walletAddress', async (req, res, next) => {
  try {
    const { walletAddress } = req.params;

    const boosts = await query(
      `SELECT
        boost_level,
        boost_expires_at,
        created_at
      FROM payments
      WHERE wallet_address = $1
        AND payment_type = 'SKR_BOOST'
        AND verified = true
        AND boost_expires_at > NOW()
      ORDER BY created_at DESC
      LIMIT 1`,
      [walletAddress]
    );

    if (boosts.length === 0) {
      return res.json({
        success: true,
        hasActiveBoost: false
      });
    }

    res.json({
      success: true,
      hasActiveBoost: true,
      boost: boosts[0]
    });

  } catch (error) {
    next(error);
  }
});

// ============================================================================
// HELPERS
// ============================================================================

/**
 * Get boost config by ID (maps to microTxBoosts and legacyBoosts)
 */
function getBoostConfig(boostId: string): { boost: number; price: number } | null {
  // Micro-transaction boosts
  const microBoosts: Record<string, { boost: number; price: number }> = {
    'boost_7h': { boost: 0.05, price: 1.0 },
    'boost_7x': { boost: 0.05, price: 6.0 },
    'boost_49x': { boost: 0.10, price: 49.0 }
  };

  // Legacy boosts (from SKR_BOOST_CONFIG)
  const legacyMapping: Record<string, string> = {
    'skr_lite': SkrBoostLevel.LITE,
    'skr_plus': SkrBoostLevel.PLUS,
    'skr_pro': SkrBoostLevel.PRO,
    'skr_ultra': SkrBoostLevel.ULTRA
  };

  if (microBoosts[boostId]) {
    return microBoosts[boostId];
  }

  const legacyLevel = legacyMapping[boostId];
  if (legacyLevel && SKR_BOOST_CONFIG[legacyLevel as SkrBoostLevel]) {
    return SKR_BOOST_CONFIG[legacyLevel as SkrBoostLevel];
  }

  return null;
}

/**
 * Get boost duration in milliseconds by ID
 */
function getBoostDurationMs(boostId: string): number {
  const durations: Record<string, number> = {
    'boost_7h': 7 * 60 * 60 * 1000,           // 7 hours
    'boost_7x': 49 * 60 * 60 * 1000,          // 49 hours
    'boost_49x': 7 * 24 * 60 * 60 * 1000,     // 7 days
    'skr_lite': 24 * 60 * 60 * 1000,          // 1 day
    'skr_plus': 24 * 60 * 60 * 1000,          // 1 day
    'skr_pro': 3 * 24 * 60 * 60 * 1000,       // 3 days
    'skr_ultra': 7 * 24 * 60 * 60 * 1000      // 7 days
  };

  return durations[boostId] || 24 * 60 * 60 * 1000; // default 1 day
}

/**
 * GET /api/v1/payment/active-boost/:walletAddress
 * Get user's active SKR boost
 */
router.get('/active-boost/:walletAddress', async (req, res, next) => {
  try {
    const { walletAddress } = req.params;
    
    const boosts = await query(
      `SELECT 
        boost_level,
        boost_expires_at,
        created_at
      FROM payments
      WHERE wallet_address = $1
        AND payment_type = 'SKR_BOOST'
        AND verified = true
        AND boost_expires_at > NOW()
      ORDER BY created_at DESC
      LIMIT 1`,
      [walletAddress]
    );
    
    if (boosts.length === 0) {
      return res.json({
        success: true,
        hasActiveBoost: false
      });
    }
    
    res.json({
      success: true,
      hasActiveBoost: true,
      boost: boosts[0]
    });
    
  } catch (error) {
    next(error);
  }
});

export default router;
