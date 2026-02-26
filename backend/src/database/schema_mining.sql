-- Sleeper: mining sessions and points balance (API_MINING_CONTRACT)
-- Run after schema.sql (e.g. in migrate.ts).

-- Add mining points to users (idempotent)
ALTER TABLE users ADD COLUMN IF NOT EXISTS points_balance BIGINT DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS total_blocks_mined INTEGER DEFAULT 0;

-- Mining sessions (one row per POST /mining/session)
CREATE TABLE IF NOT EXISTS mining_sessions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    wallet_address VARCHAR(44) NOT NULL,
    
    skr VARCHAR(100),
    uptime_minutes INTEGER NOT NULL,
    storage_mb INTEGER NOT NULL,
    storage_multiplier DECIMAL(10, 4) NOT NULL,
    stake_multiplier DECIMAL(10, 4) NOT NULL,
    paid_boost_multiplier DECIMAL(10, 4) NOT NULL,
    daily_social_multiplier DECIMAL(10, 4) NOT NULL,
    points_balance BIGINT NOT NULL,
    session_started_at BIGINT NOT NULL,
    session_ended_at BIGINT NOT NULL,
    device_fingerprint TEXT,
    genesis_nft_multiplier DECIMAL(10, 4) NOT NULL DEFAULT 1,
    active_skr_boost_id VARCHAR(50),
    
    created_at TIMESTAMP DEFAULT NOW(),
    
    CONSTRAINT fk_mining_user FOREIGN KEY (wallet_address) REFERENCES users(wallet_address) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_mining_sessions_wallet ON mining_sessions(wallet_address);
CREATE INDEX IF NOT EXISTS idx_mining_sessions_ended ON mining_sessions(session_ended_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_mining_sessions_wallet_window
    ON mining_sessions(wallet_address, session_started_at, session_ended_at);

-- Wallet auth nonce challenges (anti-replay)
CREATE TABLE IF NOT EXISTS wallet_auth_challenges (
    nonce UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    wallet_address VARCHAR(44) NOT NULL,
    message TEXT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_wallet_auth_challenges_wallet_created
    ON wallet_auth_challenges(wallet_address, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_wallet_auth_challenges_expires
    ON wallet_auth_challenges(expires_at);

-- Wallet auth tokens for background-safe authenticated API calls
CREATE TABLE IF NOT EXISTS wallet_auth_tokens (
    token UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    wallet_address VARCHAR(44) NOT NULL REFERENCES users(wallet_address) ON DELETE CASCADE,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_wallet_auth_tokens_wallet_created
    ON wallet_auth_tokens(wallet_address, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_wallet_auth_tokens_expires
    ON wallet_auth_tokens(expires_at);
