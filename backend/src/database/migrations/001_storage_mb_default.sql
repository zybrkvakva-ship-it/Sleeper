-- Migration 001: Add DEFAULT 0 to night_sessions.storage_mb
-- Required for existing production databases created before this fix.
-- The column was NOT NULL without a DEFAULT, causing /night/end INSERT to fail.
--
-- Run once on the production database:
--   psql -h HOST -U sleeper -d sleeper -f 001_storage_mb_default.sql

ALTER TABLE night_sessions
  ALTER COLUMN storage_mb SET DEFAULT 0;

-- Backfill any existing NULL values (shouldn't exist due to NOT NULL, but just in case)
UPDATE night_sessions SET storage_mb = 0 WHERE storage_mb IS NULL;
