/**
 * Metaplex Candy Machine v3 minting utility
 * Mints Genesis NFTs to user wallets via a pre-configured Candy Machine.
 */

import {
  Metaplex,
  keypairIdentity,
  toBigNumber,
} from '@metaplex-foundation/js';
import { Connection, Keypair, PublicKey } from '@solana/web3.js';
import bs58 from 'bs58';
import { logger } from './logger';

const SOLANA_RPC_URL = process.env.SOLANA_RPC_URL || 'https://api.mainnet-beta.solana.com';
const connection = new Connection(SOLANA_RPC_URL, 'confirmed');

function getMintAuthority(): Keypair {
  const raw = process.env.TREASURY_KEYPAIR;
  if (!raw) throw new Error('TREASURY_KEYPAIR env var not set');
  return Keypair.fromSecretKey(bs58.decode(raw));
}

function getCandyMachineId(): PublicKey {
  const raw = process.env.CANDY_MACHINE_ID;
  if (!raw) throw new Error('CANDY_MACHINE_ID env var not set');
  return new PublicKey(raw);
}

export interface MintResult {
  txHash: string;
  nftMint: string;
  nftNumber: number;
}

/**
 * Mint a Genesis NFT to the recipient wallet via Candy Machine.
 */
export async function mintGenesisNft(recipientWallet: string): Promise<MintResult> {
  const authority = getMintAuthority();
  const candyMachineId = getCandyMachineId();
  const recipient = new PublicKey(recipientWallet);

  const metaplex = Metaplex.make(connection).use(keypairIdentity(authority));

  logger.info('Fetching candy machine', { candyMachineId: candyMachineId.toBase58() });

  const candyMachine = await metaplex.candyMachines().findByAddress({
    address: candyMachineId,
  });

  if (candyMachine.itemsRemaining.toNumber() === 0) {
    throw new Error('Candy Machine: no items remaining (supply exhausted)');
  }

  logger.info('Minting Genesis NFT', {
    recipient: recipientWallet.slice(0, 8) + '...',
    itemsMinted: candyMachine.itemsMinted.toNumber(),
    itemsRemaining: candyMachine.itemsRemaining.toNumber(),
  });

  const { nft, response } = await metaplex.candyMachines().mint({
    candyMachine,
    collectionUpdateAuthority: authority.publicKey,
    owner: recipient,
  });

  const nftNumber = candyMachine.itemsMinted.toNumber() + 1;

  logger.info('Genesis NFT minted', {
    recipient: recipientWallet.slice(0, 8) + '...',
    nftMint: nft.address.toBase58(),
    nftNumber,
    txHash: response.signature.slice(0, 16) + '...',
  });

  return {
    txHash: response.signature,
    nftMint: nft.address.toBase58(),
    nftNumber,
  };
}
