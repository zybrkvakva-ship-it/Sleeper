import crypto from 'crypto';
import { PublicKey } from '@solana/web3.js';

export function buildAuthMessage(walletAddress: string, nonce: string): string {
  return [
    'Sleeper Authentication',
    `Wallet: ${walletAddress}`,
    `Nonce: ${nonce}`,
    'Purpose: authorize backend mining sync',
  ].join('\n');
}

export function verifySolanaSignatureHex(
  walletAddress: string,
  message: string,
  signatureHex: string
): boolean {
  try {
    if (!/^[0-9a-fA-F]{128}$/.test(signatureHex)) {
      return false;
    }

    const publicKeyBytes = new PublicKey(walletAddress).toBytes();
    const signatureBytes = Buffer.from(signatureHex, 'hex');

    // RFC8410 SubjectPublicKeyInfo prefix for Ed25519 + 32-byte raw public key
    const spkiPrefix = Buffer.from('302a300506032b6570032100', 'hex');
    const derPublicKey = Buffer.concat([spkiPrefix, Buffer.from(publicKeyBytes)]);

    const publicKey = crypto.createPublicKey({
      key: derPublicKey,
      format: 'der',
      type: 'spki',
    });

    return crypto.verify(null, Buffer.from(message, 'utf8'), publicKey, signatureBytes);
  } catch {
    return false;
  }
}
