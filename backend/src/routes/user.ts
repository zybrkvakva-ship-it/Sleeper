import { Router } from 'express';
import { query, transaction } from '../database';
import { AppError } from '../middleware/errorHandler';
import { logger } from '../utils/logger';
import { buildAuthMessage, verifySolanaSignatureHex } from '../utils/solanaAuth';
import { isValidSolanaAddress, pickFirstString, pickWallet } from '../utils/solanaAddress';
import { fetchStakingInfo } from '../utils/stakingInfo';
import { verifySkrOwnership, invalidateSkrCache } from '../utils/verifySkrToken';

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
    if (skrUsername && skrUsername.length > 50) {
      throw new AppError(400, 'skr_username must be 50 characters or less');
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
    
    // Every new registration accelerates the season for everyone
    await query(
      `UPDATE season_stats
       SET active_devices = (SELECT COUNT(*)::int FROM users)
       WHERE status = 'ACTIVE'`
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
    
    const users = await query<{
      wallet_address: string; skr_username: string | null;
      has_genesis_nft: boolean; genesis_nft_number: number | null;
      total_np: string; total_sleep_earned: string; total_nights_mined: number;
      referral_code: string; referral_count: number; referred_by: string | null;
      created_at: string;
    }>(
      `SELECT
        wallet_address, skr_username, has_genesis_nft, genesis_nft_number,
        total_np, total_sleep_earned, total_nights_mined,
        referral_code, referral_count, referred_by, created_at
      FROM users
      WHERE wallet_address = $1`,
      [walletAddress]
    );

    if (users.length === 0) {
      throw new AppError(404, 'User not found');
    }

    const user = users[0];

    // Compute welcome bonus nights remaining for referred users
    const nightRows = await query<{ count: string }>(
      'SELECT COUNT(*) AS count FROM night_sessions WHERE wallet_address = $1',
      [walletAddress]
    );
    const completedNights = parseInt(nightRows[0]?.count ?? '0', 10);
    const bonusNightsRemaining = (user.referred_by !== null && completedNights < 3)
      ? (3 - completedNights) : 0;

    res.json({
      success: true,
      user: { ...user, bonusNightsRemaining },
    });
    
  } catch (error) {
    next(error);
  }
});

/**
 * POST /api/v1/user/apply-referral
 * Apply referral code. Atomic — uses transaction + row lock to prevent race conditions.
 */
router.post('/apply-referral', async (req, res, next) => {
  try {
    const walletAddress = pickWallet(req.body);
    const referralCode = pickFirstString(req.body as Record<string, unknown>, ['referralCode', 'referral_code']);

    if (!walletAddress || !referralCode) {
      throw new AppError(400, 'walletAddress (or wallet) and referralCode are required');
    }
    if (!isValidSolanaAddress(walletAddress)) {
      throw new AppError(400, 'invalid wallet format');
    }

    await transaction(async (client) => {
      // Lock the referee row to prevent concurrent apply-referral for same user
      const refereeRows = await client.query(
        'SELECT wallet_address, referred_by FROM users WHERE wallet_address = $1 FOR UPDATE',
        [walletAddress]
      );
      if (refereeRows.rows.length === 0) {
        throw new AppError(404, 'User not found. Please register first.');
      }
      if (refereeRows.rows[0].referred_by !== null) {
        throw new AppError(409, 'Already used a referral code');
      }

      // Find referrer
      const referrerRows = await client.query(
        'SELECT wallet_address FROM users WHERE referral_code = $1',
        [referralCode]
      );
      if (referrerRows.rows.length === 0) {
        throw new AppError(404, 'Referral code not found');
      }
      const referrer = referrerRows.rows[0].wallet_address as string;
      if (referrer === walletAddress) {
        throw new AppError(400, 'Cannot use your own referral code');
      }

      // Insert referral (UNIQUE PK prevents duplicates even under concurrency)
      await client.query(
        'INSERT INTO referrals (referrer, referee) VALUES ($1, $2) ON CONFLICT DO NOTHING',
        [referrer, walletAddress]
      );

      // Update referee + increment referrer atomically
      await client.query(
        'UPDATE users SET referred_by = $1 WHERE wallet_address = $2',
        [referrer, walletAddress]
      );
      await client.query(
        'UPDATE users SET referral_count = referral_count + 1 WHERE wallet_address = $1',
        [referrer]
      );
    });

    logger.info(`Referral applied: ${walletAddress.slice(0, 8)} -> referrer`);
    res.json({ success: true, message: 'Referral code applied successfully' });
  } catch (error) {
    next(error);
  }
});

/**
 * GET /api/v1/user/staking-info/:walletAddress
 * Fetch on-chain SKR staking balance from Guardian program.
 * Redis cache 5min. Side-effect: updates staked_skr_raw in users table.
 */
router.get('/staking-info/:walletAddress', async (req, res, next) => {
  try {
    const { walletAddress } = req.params;
    if (!isValidSolanaAddress(walletAddress)) {
      throw new AppError(400, 'Invalid wallet address format');
    }

    const info = await fetchStakingInfo(walletAddress);

    // Update DB side-effect (non-fatal)
    query(
      `UPDATE users SET staked_skr_raw = $1, staked_skr_verified_at = NOW()
       WHERE wallet_address = $2`,
      [info.stakedAmountRaw.toString(), walletAddress]
    ).catch(() => {
      // user may not be registered — non-fatal
    });

    res.json({
      success: true,
      walletAddress,
      stakedAmountHuman: info.stakedAmountHuman,
      stakedAmountRaw: info.stakedAmountRaw.toString(),
      accountsFound: info.accountsFound,
      fetchedAt: info.fetchedAt,
    });
  } catch (error) {
    next(error);
  }
});

/**
 * POST /api/v1/user/verify-skr
 *
 * Server-side .skr domain ownership verification.
 * Requires: walletAddress + valid authToken (wallet must be authenticated first).
 *
 * Rules enforced:
 *   1. Wallet must own a .skr domain NFT (verified on-chain via Solana RPC)
 *   2. That specific token mint must not already be registered to another wallet
 *      (UNIQUE constraint on users.skr_token_mint)
 *   3. Once verified, the (wallet ↔ token_mint) binding is permanent
 *      — if the .skr token is transferred, the wallet loses mining access on next re-verify
 *
 * On success:
 *   - users.skr_username = domain name (e.g. "miha")
 *   - users.skr_token_mint = on-chain mint address (unique index)
 *   - users.skr_verified_at = NOW()
 *   - users.skr_domain_verified = true
 *   - season_stats.active_devices incremented (user enters acceleration counter)
 */
router.post('/verify-skr', async (req, res, next) => {
  try {
    const walletAddress = pickWallet(req.body);
    const authToken = (req.headers['x-auth-token'] as string | undefined)
      || (req.body?.authToken as string | undefined);

    if (!walletAddress) throw new AppError(400, 'walletAddress (or wallet) is required');
    if (!isValidSolanaAddress(walletAddress)) throw new AppError(400, 'invalid wallet format');
    if (!authToken) throw new AppError(401, 'X-Auth-Token header required');

    // Auth check
    const tokenUuid = authToken.trim();
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(tokenUuid)) {
      throw new AppError(401, 'Invalid auth token format');
    }
    const authRows = await query(
      `SELECT 1 FROM wallet_auth_tokens
       WHERE token = $1::uuid AND wallet_address = $2
         AND revoked_at IS NULL AND expires_at > NOW()
       LIMIT 1`,
      [tokenUuid, walletAddress]
    );
    if (authRows.length === 0) throw new AppError(401, 'Invalid or expired auth token');

    // Check if already verified (idempotent — return existing data)
    const existing = await query<{
      skr_username: string | null;
      skr_token_mint: string | null;
      skr_verified_at: string | null;
    }>(
      `SELECT skr_username, skr_token_mint, skr_verified_at FROM users WHERE wallet_address = $1`,
      [walletAddress]
    );

    if (existing[0]?.skr_verified_at && existing[0]?.skr_token_mint) {
      // Already verified — re-validate on-chain to confirm still owns the token
      const recheck = await verifySkrOwnership(walletAddress);
      if (!recheck.isValid || recheck.tokenMint !== existing[0].skr_token_mint) {
        // Token transferred to another wallet — revoke verification
        await query(
          `UPDATE users
           SET skr_domain_verified = false, skr_verified_at = NULL, skr_token_mint = NULL, skr_username = NULL
           WHERE wallet_address = $1`,
          [walletAddress]
        );
        await invalidateSkrCache(walletAddress);
        throw new AppError(403, 'Your .skr domain is no longer in this wallet. Verification revoked.');
      }
      return res.json({
        success: true,
        alreadyVerified: true,
        username: existing[0].skr_username,
        tokenMint: existing[0].skr_token_mint,
        verifiedAt: existing[0].skr_verified_at,
      });
    }

    // On-chain verification
    const result = await verifySkrOwnership(walletAddress);
    if (!result.isValid || !result.tokenMint || !result.username) {
      throw new AppError(403, result.reason || 'No .skr domain found in this wallet');
    }

    // Atomically: check uniqueness of token_mint + save
    await transaction(async (client) => {
      // Check if this token_mint is already claimed by another wallet
      const clash = await client.query(
        `SELECT wallet_address FROM users
         WHERE skr_token_mint = $1 AND wallet_address != $2
         LIMIT 1`,
        [result.tokenMint, walletAddress]
      );
      if (clash.rows.length > 0) {
        throw new AppError(409,
          'This .skr domain is already registered to another account. ' +
          'Each .skr token can only be used by one wallet.'
        );
      }

      // Save verification
      await client.query(
        `UPDATE users
         SET skr_username = $1,
             skr_token_mint = $2,
             skr_verified_at = NOW(),
             skr_domain_verified = true
         WHERE wallet_address = $3`,
        [result.username, result.tokenMint, walletAddress]
      );

      // Add to acceleration counter — only verified .skr users count
      await client.query(
        `UPDATE season_stats
         SET active_devices = (
           SELECT COUNT(*)::int FROM users WHERE skr_verified_at IS NOT NULL
         )
         WHERE status = 'ACTIVE'`
      );
    });

    logger.info('.skr verification success', {
      wallet: walletAddress.slice(0, 8) + '...',
      username: result.username,
      mint: result.tokenMint.slice(0, 8) + '...',
    });

    res.json({
      success: true,
      username: result.username,
      tokenMint: result.tokenMint,
      message: `Welcome, ${result.username}.skr! You are now in the mining acceleration counter.`,
    });
  } catch (error) {
    next(error);
  }
});

export default router;
