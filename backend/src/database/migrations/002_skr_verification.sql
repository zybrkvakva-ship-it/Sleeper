-- Migration 002: SKR token verification — server-side ownership enforcement
-- One .skr token = one account. Prevents multi-account abuse.
--
-- Run once on the production database:
--   psql -h HOST -U sleeper -d sleeper -f 002_skr_verification.sql

-- Store the on-chain mint address of the verified .skr token (unique per account)
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS skr_token_mint VARCHAR(44),
  ADD COLUMN IF NOT EXISTS skr_verified_at TIMESTAMP;

-- Unique constraint: one .skr token mint → one wallet, ever
-- Prevents the same token being claimed by multiple wallets
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_skr_token_mint
  ON users(skr_token_mint)
  WHERE skr_token_mint IS NOT NULL;

-- Fast lookup: is this wallet skr-verified?
CREATE INDEX IF NOT EXISTS idx_users_skr_verified
  ON users(skr_verified_at)
  WHERE skr_verified_at IS NOT NULL;
