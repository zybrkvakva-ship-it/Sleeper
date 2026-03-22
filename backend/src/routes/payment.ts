import { Router, Request, Response, NextFunction } from 'express';
import { query, transaction as dbTransaction } from '../database';
import { AppError } from '../middleware/errorHandler';
import { logger } from '../utils/logger';
import { SkrBoostLevel, SKR_BOOST_CONFIG } from '../economy/constants';
import { isValidSolanaAddress, pickWallet } from '../utils/solanaAddress';
import { verifyTokenTransfer } from '../utils/verifyTokenTransfer';

const router = Router();

const TREASURY_WALLET = process.env.TREASURY_WALLET;
const SKR_TOKEN_MINT = process.env.SKR_TOKEN_MINT;

/**
 * POST /api/v1/payment/activate-boost
 * Verifies on-chain SKR transfer and activates boost.
 */
const activateBoostHandler = async (req: Request, res: Response, next: NextFunction) => {
  try {
    const { txHash, boostId: rawBoostId, boostLevel } = req.body as {
      txHash: string;
      boostId?: string;
      boostLevel?: string;
    };
    const walletAddress = pickWallet(req.body);
    const boostId = rawBoostId || boostLevel;

    if (!txHash || !walletAddress || !boostId) {
      throw new AppError(400, 'Missing required fields: txHash, walletAddress, boostId');
    }
    if (!isValidSolanaAddress(walletAddress)) {
      throw new AppError(400, 'Invalid wallet address format');
    }
    if (!/^[1-9A-HJ-NP-Za-km-z]{87,88}$/.test(txHash)) {
      throw new AppError(400, 'Invalid transaction hash format');
    }

    if (boostId.length > 30) {
      throw new AppError(400, 'Invalid boost_id');
    }
    const boostConfig = getBoostConfig(boostId);
    if (!boostConfig) {
      throw new AppError(400, `Unknown boost ID: ${boostId}`);
    }
    if (!TREASURY_WALLET || !SKR_TOKEN_MINT) {
      throw new AppError(500, 'Payment env is not configured (TREASURY_WALLET, SKR_TOKEN_MINT)');
    }

    const expectedAmountRaw = BigInt(Math.floor(boostConfig.price * 1_000_000));
    const treasuryReceived = await verifyTokenTransfer({
      txHash,
      signerWallet: walletAddress,
      treasuryWallet: TREASURY_WALLET!,
      tokenMint: SKR_TOKEN_MINT!,
      expectedAmountRaw,
      tolerancePercent: 1,
      commitment: 'finalized',
    });

    const boostExpiresAt = new Date(Date.now() + getBoostDurationMs(boostId));

    await dbTransaction(async (client) => {
      const referralCode = walletAddress.substring(0, 8);
      await client.query(
        `INSERT INTO users (wallet_address, referral_code, points_balance, total_blocks_mined, last_active_at)
         VALUES ($1, $2, 0, 0, NOW())
         ON CONFLICT (wallet_address) DO UPDATE SET last_active_at = NOW()`,
        [walletAddress, referralCode]
      );

      const inserted = await client.query(
        `INSERT INTO payments (
          wallet_address, tx_hash, payment_type, amount,
          boost_level, boost_expires_at, verified, verified_at
        ) VALUES ($1, $2, 'SKR_BOOST', $3, $4, $5, true, NOW())
        ON CONFLICT (tx_hash) DO NOTHING
        RETURNING id`,
        [walletAddress, txHash, boostConfig.price, boostId, boostExpiresAt]
      );

      if (inserted.rows.length === 0) {
        const existing = await client.query(
          `SELECT wallet_address, boost_level FROM payments WHERE tx_hash = $1 LIMIT 1`,
          [txHash]
        );
        const row = existing.rows[0];
        if (!row || row.wallet_address !== walletAddress || row.boost_level !== boostId) {
          throw new AppError(409, 'txHash already used for another payment');
        }
      }
    });

    logger.info('Boost activated successfully', {
      walletAddress: walletAddress.slice(0, 8) + '...',
      boostId,
      txHash: txHash.slice(0, 16) + '...',
      treasuryReceived: treasuryReceived.toString(),
      expectedAmountRaw: expectedAmountRaw.toString(),
    });

    res.json({
      success: true,
      boostId,
      boostLevel: boostConfig.boost,
      expiresAt: boostExpiresAt.toISOString(),
      message: 'Boost activated successfully',
    });
  } catch (error) {
    next(error);
  }
};

router.post('/activate-boost', activateBoostHandler);

/**
 * Legacy alias.
 */
router.post('/verify-skr', activateBoostHandler);

/**
 * GET /api/v1/payment/active-boost/:walletAddress
 */
router.get('/active-boost/:walletAddress', async (req, res, next) => {
  try {
    const { walletAddress } = req.params;
    if (!isValidSolanaAddress(walletAddress)) {
      throw new AppError(400, 'Invalid wallet address format');
    }

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
        hasActiveBoost: false,
      });
    }

    res.json({
      success: true,
      hasActiveBoost: true,
      boost: boosts[0],
    });
  } catch (error) {
    next(error);
  }
});

function getBoostConfig(boostId: string): { boost: number; price: number } | null {
  const legacyMapping: Record<string, string> = {
    skr_lite: SkrBoostLevel.LITE,
    skr_plus: SkrBoostLevel.PLUS,
    skr_pro: SkrBoostLevel.PRO,
    skr_ultra: SkrBoostLevel.ULTRA,
  };

  const legacyLevel = legacyMapping[boostId];
  if (legacyLevel && SKR_BOOST_CONFIG[legacyLevel as SkrBoostLevel]) {
    return SKR_BOOST_CONFIG[legacyLevel as SkrBoostLevel];
  }
  return null;
}

function getBoostDurationMs(boostId: string): number {
  const durations: Record<string, number> = {
    skr_lite: 24 * 60 * 60 * 1000,
    skr_plus: 24 * 60 * 60 * 1000,
    skr_pro: 3 * 24 * 60 * 60 * 1000,
    skr_ultra: 7 * 24 * 60 * 60 * 1000,
  };
  return durations[boostId] || 24 * 60 * 60 * 1000;
}

export default router;
