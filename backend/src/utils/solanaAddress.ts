import { PublicKey } from '@solana/web3.js';

export function isValidSolanaAddress(address: string): boolean {
  const trimmed = address.trim();
  if (trimmed.length < 32 || trimmed.length > 44) return false;
  try {
    const key = new PublicKey(trimmed);
    return key.toBase58() === trimmed;
  } catch {
    return false;
  }
}

export function pickFirstString(
  input: Record<string, unknown> | undefined,
  keys: string[]
): string | undefined {
  if (!input || typeof input !== 'object') return undefined;
  for (const key of keys) {
    const value = input[key];
    if (typeof value === 'string' && value.trim().length > 0) {
      return value.trim();
    }
  }
  return undefined;
}

export function pickWallet(input: unknown): string | undefined {
  return pickFirstString(input as Record<string, unknown>, ['walletAddress', 'wallet']);
}
