/**
 * Solana SPL token transfer utility
 * Signs and sends SLEEP token transfers from the treasury to user wallets.
 */

import {
  Connection,
  Keypair,
  PublicKey,
  Transaction,
  sendAndConfirmTransaction,
} from '@solana/web3.js';
import {
  getOrCreateAssociatedTokenAccount,
  createTransferInstruction,
  getMint,
} from '@solana/spl-token';
import bs58 from 'bs58';
import { logger } from './logger';

const SOLANA_RPC_URL = process.env.SOLANA_RPC_URL || 'https://api.mainnet-beta.solana.com';
const connection = new Connection(SOLANA_RPC_URL, 'confirmed');

function getTreasuryKeypair(): Keypair {
  const raw = process.env.TREASURY_KEYPAIR;
  if (!raw) throw new Error('TREASURY_KEYPAIR env var not set');
  return Keypair.fromSecretKey(bs58.decode(raw));
}

function getSleepMint(): PublicKey {
  const raw = process.env.SLEEP_TOKEN_MINT;
  if (!raw) throw new Error('SLEEP_TOKEN_MINT env var not set');
  return new PublicKey(raw);
}

/**
 * Send SLEEP tokens from the treasury wallet to a recipient.
 * @param recipientWallet  Base58 recipient wallet address
 * @param amount           Raw token amount (already in smallest units)
 * @returns                Confirmed transaction signature
 */
export async function sendSleepTokens(
  recipientWallet: string,
  amount: bigint
): Promise<string> {
  const treasury = getTreasuryKeypair();
  const sleepMint = getSleepMint();
  const recipient = new PublicKey(recipientWallet);

  // Fetch mint info to validate decimals
  const mintInfo = await getMint(connection, sleepMint);
  logger.info('Sending SLEEP tokens', {
    recipient: recipientWallet.slice(0, 8) + '...',
    amount: amount.toString(),
    decimals: mintInfo.decimals,
  });

  // Get/create treasury ATA
  const treasuryAta = await getOrCreateAssociatedTokenAccount(
    connection,
    treasury,
    sleepMint,
    treasury.publicKey
  );

  // Get/create recipient ATA (treasury pays for creation)
  const recipientAta = await getOrCreateAssociatedTokenAccount(
    connection,
    treasury,
    sleepMint,
    recipient
  );

  // Build transaction
  const tx = new Transaction().add(
    createTransferInstruction(
      treasuryAta.address,
      recipientAta.address,
      treasury.publicKey,
      amount
    )
  );

  const signature = await sendAndConfirmTransaction(connection, tx, [treasury], {
    commitment: 'confirmed',
  });

  logger.info('SLEEP tokens sent', {
    recipient: recipientWallet.slice(0, 8) + '...',
    amount: amount.toString(),
    signature: signature.slice(0, 16) + '...',
  });

  return signature;
}
