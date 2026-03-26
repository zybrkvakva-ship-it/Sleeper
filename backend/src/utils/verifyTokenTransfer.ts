/**
 * Shared utility for verifying SPL token transfers on Solana.
 * Used by payment.ts (SKR boost) and nft.ts (Genesis NFT purchase).
 */

import { Connection } from '@solana/web3.js';
import { AppError } from '../middleware/errorHandler';
import { logger } from './logger';

const SOLANA_RPC_URL = process.env.SOLANA_RPC_URL || 'https://api.mainnet-beta.solana.com';
const connection = new Connection(SOLANA_RPC_URL, 'confirmed');

export interface VerifyTokenTransferOpts {
  txHash: string;
  signerWallet: string;
  treasuryWallet: string;
  tokenMint: string;
  expectedAmountRaw: bigint;   // raw token units (already includes decimals)
  tolerancePercent?: number;   // default 1
  commitment?: 'confirmed' | 'finalized';
}

/**
 * Verifies that a Solana transaction transferred the expected SPL token amount
 * from signerWallet to treasuryWallet.
 *
 * Throws AppError on any validation failure.
 * Returns the actual amount received (for logging/audit).
 */
export async function verifyTokenTransfer(
  opts: VerifyTokenTransferOpts
): Promise<bigint> {
  const {
    txHash,
    signerWallet,
    treasuryWallet,
    tokenMint,
    expectedAmountRaw,
    tolerancePercent = 1,
    commitment = 'confirmed',
  } = opts;

  const txInfo = await connection.getTransaction(txHash, {
    maxSupportedTransactionVersion: 0,
    commitment,
  });

  if (!txInfo) {
    throw new AppError(404, 'Transaction not found on Solana');
  }
  if (txInfo.meta?.err) {
    logger.warn('Transaction failed on-chain', { txHash: txHash.slice(0, 16) + '...', err: txInfo.meta.err });
    throw new AppError(400, 'Transaction failed on-chain. Please check the transaction status.');
  }

  // Verify signer
  const accountKeys = txInfo.transaction.message.getAccountKeys().keySegments().flat();
  const isSigner = accountKeys.some((k) => k.toBase58() === signerWallet);
  if (!isSigner) {
    throw new AppError(400, 'Wallet address is not a signer in the transaction');
  }

  // Calculate how much treasury received
  const preByIndex = new Map<number, bigint>();
  for (const b of txInfo.meta?.preTokenBalances ?? []) {
    if (b.mint !== tokenMint || b.owner !== treasuryWallet) continue;
    if (!b.uiTokenAmount?.amount) continue;
    preByIndex.set(b.accountIndex, BigInt(b.uiTokenAmount.amount));
  }

  let treasuryReceived = 0n;
  for (const b of txInfo.meta?.postTokenBalances ?? []) {
    if (b.mint !== tokenMint || b.owner !== treasuryWallet) continue;
    if (!b.uiTokenAmount?.amount) continue;
    const postAmount = BigInt(b.uiTokenAmount.amount);
    const preAmount = preByIndex.get(b.accountIndex) ?? 0n;
    if (postAmount > preAmount) {
      treasuryReceived += postAmount - preAmount;
    }
  }

  // Tolerance check
  const tolerance = (expectedAmountRaw * BigInt(tolerancePercent)) / 100n;
  const diff = treasuryReceived > expectedAmountRaw
    ? treasuryReceived - expectedAmountRaw
    : expectedAmountRaw - treasuryReceived;

  if (diff > tolerance) {
    throw new AppError(
      400,
      `Amount mismatch: expected ${expectedAmountRaw.toString()}, got ${treasuryReceived.toString()}`
    );
  }

  return treasuryReceived;
}
