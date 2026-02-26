import { Router } from 'express';
import { query, transaction } from '../database';
import { AppError } from '../middleware/errorHandler';
import { logger } from '../utils/logger';
import { buildAuthMessage, verifySolanaSignatureHex } from '../utils/solanaAuth';
import { isValidSolanaAddress, pickFirstString, pickWallet } from '../utils/solanaAddress';

const router = Router();
const AUTH_CHALLENGE_TTL_SECONDS = parseInt(process.env.AUTH_CHALLENGE_TTL_SECONDS || '600', 10);
const AUTH_TOKEN_TTL_SECONDS = parseInt(process.env.AUTH_TOKEN_TTL_SECONDS || '604800', 10); // 7 days

/**
 * POST /api/v1/user/register
 * Register or update user
 */
router.post('/register', async (req, res, next) => {
  try {
    const walletAddress = pickWallet(req.body);
    const skrUsername = pickFirstString(req.body as Record<string, unknown>, ['skrUsername', 'skr']);
    
    if (!walletAddress) {
      throw new AppError(400, 'walletAddress (or wallet) is required');
    }
    if (!isValidSolanaAddress(walletAddress)) {
      throw new AppError(400, 'invalid wallet format');
    }
    
    // Generate referral code (8 chars from wallet)
    const referralCode = walletAddress.substring(0, 8);
    
    // Upsert user
    await query(
      `INSERT INTO users (wallet_address, skr_username, referral_code, last_active_at)
       VALUES ($1, $2, $3, NOW())
       ON CONFLICT (wallet_address) 
       DO UPDATE SET 
         skr_username = EXCLUDED.skr_username,
         last_active_at = NOW()`,
      [walletAddress, skrUsername, referralCode]
    );
    
    logger.info(`User registered: ${walletAddress}`);
    
    res.json({
      success: true,
      walletAddress,
      referralCode,
      message: 'User registered successfully'
    });
    
  } catch (error) {
    next(error);
  }
});

/**
 * POST /api/v1/user/auth/challenge
 * Create one-time challenge for wallet signature.
 */
router.post('/auth/challenge', async (req, res, next) => {
  try {
    const walletAddress = pickWallet(req.body);
    if (!walletAddress) {
      throw new AppError(400, 'walletAddress (or wallet) is required');
    }
    if (!isValidSolanaAddress(walletAddress)) {
      throw new AppError(400, 'invalid wallet format');
    }

    await query(
      `DELETE FROM wallet_auth_challenges
       WHERE wallet_address = $1
         AND (used_at IS NOT NULL OR expires_at < NOW())`,
      [walletAddress]
    );

    const created = await query<{ nonce: string; expires_at: string }>(
      `INSERT INTO wallet_auth_challenges (wallet_address, message, expires_at)
       VALUES ($1, '', NOW() + ($2 || ' seconds')::interval)
       RETURNING nonce, expires_at`,
      [walletAddress, AUTH_CHALLENGE_TTL_SECONDS]
    );

    const nonce = created[0].nonce;
    const message = buildAuthMessage(walletAddress, nonce);

    await query(
      `UPDATE wallet_auth_challenges
       SET message = $1
       WHERE nonce = $2`,
      [message, nonce]
    );

    res.json({
      success: true,
      nonce,
      message,
      expiresAt: created[0].expires_at,
    });
  } catch (error) {
    next(error);
  }
});

/**
 * POST /api/v1/user/auth/verify
 * Verify challenge signature and issue backend auth token.
 */
router.post('/auth/verify', async (req, res, next) => {
  try {
    const walletAddress = pickWallet(req.body);
    const nonce = (req.body?.nonce as string | undefined)?.trim();
    const signature = (req.body?.signature as string | undefined)?.trim();

    if (!walletAddress || !nonce || !signature) {
      throw new AppError(400, 'walletAddress (or wallet), nonce and signature are required');
    }
    if (!isValidSolanaAddress(walletAddress)) {
      throw new AppError(400, 'invalid wallet format');
    }

    const tokenRows = await transaction(async (client) => {
      const rows = await client.query<{
        nonce: string;
        message: string;
        expires_at: string;
        used_at: string | null;
      }>(
        `SELECT nonce, message, expires_at, used_at
         FROM wallet_auth_challenges
         WHERE nonce = $1 AND wallet_address = $2
         FOR UPDATE`,
        [nonce, walletAddress]
      );

      if (rows.rows.length === 0) {
        throw new AppError(401, 'invalid or unknown auth challenge');
      }

      const challenge = rows.rows[0];
      if (challenge.used_at) {
        throw new AppError(401, 'auth challenge already used');
      }
      if (new Date(challenge.expires_at).getTime() <= Date.now()) {
        throw new AppError(401, 'auth challenge expired');
      }

      const expectedMessage = buildAuthMessage(walletAddress, nonce);
      if (challenge.message !== expectedMessage) {
        throw new AppError(401, 'auth challenge mismatch');
      }

      const isValid = verifySolanaSignatureHex(walletAddress, challenge.message, signature);
      if (!isValid) {
        throw new AppError(401, 'invalid wallet signature');
      }

      const referralCode = walletAddress.substring(0, 8);
      await client.query(
        `INSERT INTO users (wallet_address, referral_code, points_balance, total_blocks_mined, last_active_at)
         VALUES ($1, $2, 0, 0, NOW())
         ON CONFLICT (wallet_address) DO UPDATE SET last_active_at = NOW()`,
        [walletAddress, referralCode]
      );

      await client.query(
        `UPDATE wallet_auth_challenges
         SET used_at = NOW()
         WHERE nonce = $1`,
        [nonce]
      );

      const tokenRows = await client.query<{ token: string; expires_at: string }>(
        `INSERT INTO wallet_auth_tokens (wallet_address, expires_at)
         VALUES ($1, NOW() + ($2 || ' seconds')::interval)
         RETURNING token, expires_at`,
        [walletAddress, AUTH_TOKEN_TTL_SECONDS]
      );

      return tokenRows.rows;
    });

    res.json({
      success: true,
      authToken: tokenRows[0].token,
      expiresAt: tokenRows[0].expires_at,
    });
  } catch (error) {
    next(error);
  }
});

/**
 * GET /api/v1/user/balance?wallet=...
 * Get user points balance (Sleeper). Returns 0 if user not found.
 * Contract: API_MINING_CONTRACT.md
 */
router.get('/balance', async (req, res, next) => {
  try {
    const wallet = pickFirstString(req.query as Record<string, unknown>, ['wallet', 'walletAddress']);
    if (!wallet) {
      throw new AppError(400, 'wallet (or walletAddress) query parameter is required');
    }
    if (!isValidSolanaAddress(wallet)) {
      throw new AppError(400, 'invalid wallet format');
    }
    const users = await query<{ points_balance: string }>(
      `SELECT COALESCE(points_balance, 0)::bigint AS points_balance FROM users WHERE wallet_address = $1`,
      [wallet]
    );
    if (users.length === 0) {
      // Contract: "при первом запросе по wallet — инициализация пользователя с балансом 0"
      const referralCode = wallet.substring(0, 8);
      await query(
        `INSERT INTO users (wallet_address, referral_code, points_balance, total_blocks_mined, last_active_at)
         VALUES ($1, $2, 0, 0, NOW())
         ON CONFLICT (wallet_address) DO NOTHING`,
        [wallet, referralCode]
      );
    }
    const after = await query<{ points_balance: string }>(
      `SELECT COALESCE(points_balance, 0)::bigint AS points_balance FROM users WHERE wallet_address = $1`,
      [wallet]
    );
    const balance = after.length > 0 ? parseInt(after[0].points_balance, 10) : 0;
    res.json({ balance });
  } catch (error) {
    next(error);
  }
});

/**
 * GET /api/v1/user/:walletAddress
 * Get user profile
 */
router.get('/:walletAddress', async (req, res, next) => {
  try {
    const { walletAddress } = req.params;
    
    const users = await query(
      `SELECT 
        wallet_address,
        skr_username,
        has_genesis_nft,
        genesis_nft_number,
        total_np,
        total_sleep_earned,
        total_nights_mined,
        referral_code,
        referral_count,
        created_at
      FROM users
      WHERE wallet_address = $1`,
      [walletAddress]
    );
    
    if (users.length === 0) {
      throw new AppError(404, 'User not found');
    }
    
    res.json({
      success: true,
      user: users[0]
    });
    
  } catch (error) {
    next(error);
  }
});

/**
 * POST /api/v1/user/apply-referral
 * Apply referral code
 */
router.post('/apply-referral', async (req, res, next) => {
  try {
    const walletAddress = pickWallet(req.body);
    const referralCode = pickFirstString(req.body as Record<string, unknown>, ['referralCode', 'referral_code']);
    
    if (!walletAddress || !referralCode) {
      throw new AppError(400, 'walletAddress (or wallet) and referralCode are required');
    }
    
    // Find referrer
    const referrers = await query(
      'SELECT wallet_address FROM users WHERE referral_code = $1',
      [referralCode]
    );
    
    if (referrers.length === 0) {
      throw new AppError(404, 'Referral code not found');
    }
    
    const referrer = referrers[0].wallet_address;
    
    // Cannot refer yourself
    if (referrer === walletAddress) {
      throw new AppError(400, 'Cannot use your own referral code');
    }
    
    // Check if already referred
    const existing = await query(
      'SELECT * FROM referrals WHERE referee = $1',
      [walletAddress]
    );
    
    if (existing.length > 0) {
      throw new AppError(400, 'Already used a referral code');
    }
    
    // Create referral
    await query(
      'INSERT INTO referrals (referrer, referee) VALUES ($1, $2)',
      [referrer, walletAddress]
    );
    
    // Update user
    await query(
      'UPDATE users SET referred_by = $1 WHERE wallet_address = $2',
      [referrer, walletAddress]
    );
    
    // Increment referrer count
    await query(
      'UPDATE users SET referral_count = referral_count + 1 WHERE wallet_address = $1',
      [referrer]
    );
    
    logger.info(`Referral applied: ${walletAddress} -> ${referrer}`);
    
    res.json({
      success: true,
      message: 'Referral code applied successfully'
    });
    
  } catch (error) {
    next(error);
  }
});

export default router;
