import { Router } from 'express';
import { query, transaction as dbTransaction } from '../database';
import { AppError } from '../middleware/errorHandler';
import { logger } from '../utils/logger';
import { isValidSolanaAddress, pickFirstString, pickWallet } from '../utils/solanaAddress';
import { mintGenesisNft } from '../utils/candyMachine';

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

    if (!walletAddress || !authToken) {
      throw new AppError(400, 'Missing required fields: walletAddress, authToken');
    }
    if (!isValidSolanaAddress(walletAddress)) {
      throw new AppError(400, 'Invalid wallet address format');
    }

    // Validate auth token
    const tokenRows = await query(
      `SELECT wallet_address FROM auth_challenges
       WHERE wallet_address = $1 AND token = $2 AND expires_at > NOW()
       LIMIT 1`,
      [walletAddress, authToken]
    );
    if (tokenRows.length === 0) {
      throw new AppError(401, 'Invalid or expired auth token');
    }

    // Re-check eligibility (idempotent guard)
    const users = await query(
      'SELECT has_genesis_nft, skr_username, genesis_nft_mint FROM users WHERE wallet_address = $1',
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
        message: 'You already own a Genesis NFT',
      });
    }
    if (!user.skr_username) {
      throw new AppError(403, 'You must own a .skr domain to mint Genesis NFT');
    }

    // Check supply
    const nftCount = await query('SELECT COUNT(*) as count FROM users WHERE has_genesis_nft = true');
    const minted = parseInt(nftCount[0]?.count || '0');
    if (minted >= 10000) {
      throw new AppError(410, 'Genesis NFT supply exhausted (10,000/10,000)');
    }

    // Verify Candy Machine is configured
    if (!process.env.CANDY_MACHINE_ID || !process.env.TREASURY_KEYPAIR) {
      throw new AppError(503, 'NFT minting is not configured on this server');
    }

    // Execute mint
    const { txHash, nftMint, nftNumber } = await mintGenesisNft(walletAddress);

    // Persist NFT ownership atomically
    await dbTransaction(async (client) => {
      await client.query(
        `UPDATE users SET
           has_genesis_nft = true,
           genesis_nft_mint = $1,
           genesis_nft_number = $2,
           genesis_nft_purchased_at = NOW()
         WHERE wallet_address = $3`,
        [nftMint, nftNumber, walletAddress]
      );
      // Record payment entry for audit trail
      await client.query(
        `INSERT INTO payments (wallet_address, tx_hash, payment_type, amount, nft_mint, nft_number, verified, verified_at)
         VALUES ($1, $2, 'GENESIS_NFT', 0, $3, $4, true, NOW())
         ON CONFLICT (tx_hash) DO NOTHING`,
        [walletAddress, txHash, nftMint, nftNumber]
      );
    });

    logger.info('Genesis NFT minted and recorded', {
      wallet: walletAddress.slice(0, 8) + '...',
      nftMint,
      nftNumber,
    });

    res.json({
      success: true,
      txHash,
      nftMint,
      nftNumber,
      message: 'Genesis NFT minted successfully',
    });

  } catch (error) {
    next(error);
  }
});

export default router;
