import { Pool, PoolClient } from 'pg';
import dotenv from 'dotenv';

// Load environment variables first (idempotent - safe to call multiple times)
dotenv.config();

import { logger } from '../utils/logger';

// Create PostgreSQL connection pool with explicit config
// This avoids issues with system PGDATABASE, PGUSER env vars
export const db = new Pool({
  host: process.env.DB_HOST || 'localhost',
  port: parseInt(process.env.DB_PORT || '5432'),
  database: process.env.DB_NAME || 'sleeper',
  user: process.env.DB_USER || 'sleeper',
  password: process.env.DB_PASSWORD || 'sleeper123',
  max: 20,
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 2000,
});

// Test connection on startup
db.on('connect', () => {
  logger.info('New database connection established');
});

db.on('error', (err) => {
  logger.error('Unexpected database error:', err);
});

// Helper to run queries with error handling
export async function query<T = any>(
  text: string,
  params?: any[]
): Promise<T[]> {
  const start = Date.now();
  try {
    const result = await db.query(text, params);
    const duration = Date.now() - start;
    
    logger.debug('Query executed', {
      query: text.substring(0, 100),
      duration: `${duration}ms`,
      rows: result.rowCount
    });
    
    return result.rows;
  } catch (error) {
    logger.error('Database query error:', {
      query: text,
      params,
      error
    });
    throw error;
  }
}

// Helper to run queries in a transaction
export async function transaction<T>(
  callback: (client: PoolClient) => Promise<T>
): Promise<T> {
  const client = await db.connect();
  
  try {
    await client.query('BEGIN');
    const result = await callback(client);
    await client.query('COMMIT');
    return result;
  } catch (error) {
    await client.query('ROLLBACK');
    logger.error('Transaction rolled back:', error);
    throw error;
  } finally {
    client.release();
  }
}

export default db;
