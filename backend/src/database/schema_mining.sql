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
