-- NightMiner Database Schema
-- PostgreSQL 14+

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================================================
-- USERS TABLE
-- ============================================================================
CREATE TABLE IF NOT EXISTS users (
    wallet_address VARCHAR(44) PRIMARY KEY,
    skr_username VARCHAR(100),
    skr_domain_verified BOOLEAN DEFAULT FALSE,
    
    -- NFT ownership
    has_genesis_nft BOOLEAN DEFAULT FALSE,
    genesis_nft_mint VARCHAR(44),
    genesis_nft_number INTEGER,
    genesis_nft_purchased_at TIMESTAMP,
    
    -- Stats
    total_np DECIMAL(20, 2) DEFAULT 0,
    total_sleep_earned BIGINT DEFAULT 0,
    total_nights_mined INTEGER DEFAULT 0,
    
    -- Referral system
    referral_code VARCHAR(20) UNIQUE,
    referred_by VARCHAR(44) REFERENCES users(wallet_address),
    referral_count INTEGER DEFAULT 0,
    
    -- Metadata
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    last_active_at TIMESTAMP DEFAULT NOW(),
    
    -- Indexes
    CONSTRAINT valid_wallet CHECK (length(wallet_address) BETWEEN 32 AND 44),
    CONSTRAINT valid_nft_number CHECK (genesis_nft_number BETWEEN 1 AND 10000)
);

CREATE INDEX idx_users_skr_username ON users(skr_username);
CREATE INDEX idx_users_total_np ON users(total_np DESC);
CREATE INDEX idx_users_referral_code ON users(referral_code);
CREATE INDEX idx_users_referred_by ON users(referred_by);

-- ============================================================================
-- NIGHT SESSIONS TABLE
-- ============================================================================
CREATE TABLE IF NOT EXISTS night_sessions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    wallet_address VARCHAR(44) NOT NULL REFERENCES users(wallet_address) ON DELETE CASCADE,
    
    -- Session metadata
    night_date DATE NOT NULL,
    week_index INTEGER NOT NULL,
    session_started_at TIMESTAMP DEFAULT NOW(),
    session_ended_at TIMESTAMP,
    
    -- Sleep data
    minutes_slept INTEGER NOT NULL,
    storage_mb INTEGER NOT NULL,
    human_factor DECIMAL(3, 2) NOT NULL,
    movement_violations INTEGER DEFAULT 0,
    screen_on_count INTEGER DEFAULT 0,
    
    -- Boosts applied
    referral_count INTEGER DEFAULT 0,
    daily_tasks_percent DECIMAL(3, 2) DEFAULT 0,
    skr_boost_level VARCHAR(20) DEFAULT 'NONE',
    has_genesis_nft BOOLEAN DEFAULT FALSE,
    
    -- Calculated rewards
    base_np DECIMAL(20, 2) NOT NULL,
    social_boost DECIMAL(3, 2) NOT NULL,
    skr_boost DECIMAL(3, 2) NOT NULL,
    nft_multiplier DECIMAL(3, 2) NOT NULL,
    total_multiplier DECIMAL(3, 2) NOT NULL,
    final_np DECIMAL(20, 2) NOT NULL,
    sleep_tokens BIGINT DEFAULT 0,
    
    -- Processing status
    processed BOOLEAN DEFAULT FALSE,
    processed_at TIMESTAMP,
    
    -- Metadata
    created_at TIMESTAMP DEFAULT NOW(),
    
    -- Constraints
    CONSTRAINT unique_wallet_night UNIQUE(wallet_address, night_date),
    CONSTRAINT valid_minutes CHECK (minutes_slept BETWEEN 0 AND 480),
    CONSTRAINT valid_storage CHECK (storage_mb BETWEEN 0 AND 600),
    CONSTRAINT valid_human_factor CHECK (human_factor BETWEEN 0 AND 1),
    CONSTRAINT valid_boosts CHECK (
        social_boost BETWEEN 0 AND 1 AND
        skr_boost BETWEEN 0 AND 1 AND
        nft_multiplier BETWEEN 1 AND 3
    )
);

CREATE INDEX idx_night_wallet ON night_sessions(wallet_address);
CREATE INDEX idx_night_date ON night_sessions(night_date DESC);
CREATE INDEX idx_night_processed ON night_sessions(processed, night_date);
CREATE INDEX idx_night_week ON night_sessions(week_index);

-- ============================================================================
-- SEASON STATS TABLE
-- ============================================================================
CREATE TABLE IF NOT EXISTS season_stats (
    season_number INTEGER PRIMARY KEY,
    
    -- Season timing
    start_date DATE NOT NULL,
    end_date DATE,
    current_week INTEGER DEFAULT 1,
    total_weeks INTEGER NOT NULL,
    
    -- Stats
    active_devices INTEGER DEFAULT 0,
    total_np DECIMAL(30, 2) DEFAULT 0,
    total_sleep_distributed BIGINT DEFAULT 0,
    total_nights_processed INTEGER DEFAULT 0,
    
    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE', -- ACTIVE, COMPLETED, PAUSED
    
    -- Metadata
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    
    CONSTRAINT valid_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'PAUSED'))
);

CREATE INDEX idx_season_status ON season_stats(status);
CREATE INDEX idx_season_dates ON season_stats(start_date, end_date);

-- ============================================================================
-- PAYMENTS TABLE
-- ============================================================================
CREATE TABLE IF NOT EXISTS payments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    wallet_address VARCHAR(44) NOT NULL REFERENCES users(wallet_address) ON DELETE CASCADE,
    
    -- Transaction details
    tx_hash VARCHAR(88) UNIQUE NOT NULL,
    payment_type VARCHAR(20) NOT NULL, -- GENESIS_NFT, SKR_BOOST
    amount DECIMAL(20, 2) NOT NULL,
    
    -- Boost specific (if applicable)
    boost_level VARCHAR(20),
    boost_expires_at TIMESTAMP,
    
    -- NFT specific (if applicable)
    nft_mint VARCHAR(44),
    nft_number INTEGER,
    
    -- Verification
    verified BOOLEAN DEFAULT FALSE,
    verified_at TIMESTAMP,
    verification_error TEXT,
    
    -- Metadata
    created_at TIMESTAMP DEFAULT NOW(),
    
    CONSTRAINT valid_payment_type CHECK (payment_type IN ('GENESIS_NFT', 'SKR_BOOST')),
    CONSTRAINT valid_tx_hash CHECK (length(tx_hash) BETWEEN 87 AND 88)
);

CREATE INDEX idx_payments_wallet ON payments(wallet_address);
CREATE INDEX idx_payments_tx_hash ON payments(tx_hash);
CREATE INDEX idx_payments_type ON payments(payment_type);
CREATE INDEX idx_payments_verified ON payments(verified);

-- ============================================================================
-- REFERRALS TABLE
-- ============================================================================
CREATE TABLE IF NOT EXISTS referrals (
    referrer VARCHAR(44) NOT NULL REFERENCES users(wallet_address) ON DELETE CASCADE,
    referee VARCHAR(44) NOT NULL REFERENCES users(wallet_address) ON DELETE CASCADE,
    
    -- Status
    is_active BOOLEAN DEFAULT TRUE,
    referee_first_night DATE,
    
    -- Metadata
    created_at TIMESTAMP DEFAULT NOW(),
    
    PRIMARY KEY (referrer, referee),
    CONSTRAINT no_self_referral CHECK (referrer != referee)
);

CREATE INDEX idx_referrals_referrer ON referrals(referrer);
CREATE INDEX idx_referrals_referee ON referrals(referee);
CREATE INDEX idx_referrals_active ON referrals(is_active);

-- ============================================================================
-- DAILY TASKS TABLE
-- ============================================================================
CREATE TABLE IF NOT EXISTS daily_tasks (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    wallet_address VARCHAR(44) NOT NULL REFERENCES users(wallet_address) ON DELETE CASCADE,
    task_date DATE NOT NULL,
    
    -- Task completion
    daily_checkin BOOLEAN DEFAULT FALSE,
    social_share BOOLEAN DEFAULT FALSE,
    sleep_streak_days INTEGER DEFAULT 0,
    
    -- Calculated bonus
    total_bonus_percent DECIMAL(3, 2) DEFAULT 0,
    
    -- Metadata
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    
    CONSTRAINT unique_wallet_date UNIQUE(wallet_address, task_date)
);

CREATE INDEX idx_daily_tasks_wallet ON daily_tasks(wallet_address);
CREATE INDEX idx_daily_tasks_date ON daily_tasks(task_date DESC);

-- ============================================================================
-- LEADERBOARD (Materialized View)
-- ============================================================================
CREATE MATERIALIZED VIEW leaderboard AS
SELECT 
    u.wallet_address,
    u.skr_username,
    u.has_genesis_nft,
    u.total_np,
    u.total_sleep_earned,
    u.total_nights_mined,
    u.referral_count,
    RANK() OVER (ORDER BY u.total_np DESC) as rank,
    u.last_active_at
FROM users u
WHERE u.total_np > 0
ORDER BY u.total_np DESC
LIMIT 1000;

CREATE UNIQUE INDEX idx_leaderboard_rank ON leaderboard(rank);
CREATE INDEX idx_leaderboard_wallet ON leaderboard(wallet_address);

-- ============================================================================
-- DAILY NIGHT DISTRIBUTION (Tracking)
-- ============================================================================
CREATE TABLE IF NOT EXISTS night_distributions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    night_date DATE UNIQUE NOT NULL,
    
    -- Distribution stats
    total_np DECIMAL(30, 2) NOT NULL,
    pool_night BIGINT NOT NULL,
    total_distributed BIGINT NOT NULL,
    users_count INTEGER NOT NULL,
    active_devices INTEGER NOT NULL,
    
    -- Season context
    season_number INTEGER NOT NULL,
    week_index INTEGER NOT NULL,
    
    -- Status
    distributed_at TIMESTAMP DEFAULT NOW(),
    
    -- Metadata
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_distributions_date ON night_distributions(night_date DESC);

-- ============================================================================
-- FUNCTIONS
-- ============================================================================

-- Update user's updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Apply trigger to users table
CREATE TRIGGER users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at();

-- Apply trigger to season_stats table
CREATE TRIGGER season_stats_updated_at
    BEFORE UPDATE ON season_stats
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at();

-- Refresh leaderboard function
CREATE OR REPLACE FUNCTION refresh_leaderboard()
RETURNS void AS $$
BEGIN
    REFRESH MATERIALIZED VIEW CONCURRENTLY leaderboard;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- INITIAL DATA
-- ============================================================================

-- Create initial season
INSERT INTO season_stats (season_number, start_date, total_weeks, status)
VALUES (1, CURRENT_DATE, 16, 'ACTIVE')
ON CONFLICT (season_number) DO NOTHING;

-- ============================================================================
-- GRANTS (adjust as needed for your user)
-- ============================================================================

-- GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO nightminer;
-- GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO nightminer;
-- GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO nightminer;
