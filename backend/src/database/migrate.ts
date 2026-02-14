// CRITICAL: Load .env BEFORE any other imports
import dotenv from 'dotenv';
dotenv.config();

// Now import everything else
import { readFileSync } from 'fs';
import { join } from 'path';
import { db } from './index';
import { logger } from '../utils/logger';

async function migrate() {
  try {
    logger.info('🔄 Running database migrations...');
    
    const schemaPath = join(__dirname, 'schema.sql');
    const schema = readFileSync(schemaPath, 'utf-8');
    await db.query(schema);
    logger.info('✅ schema.sql applied');

    const miningPath = join(__dirname, 'schema_mining.sql');
    const schemaMining = readFileSync(miningPath, 'utf-8');
    await db.query(schemaMining);
    logger.info('✅ schema_mining.sql applied (SeekerMiner mining sessions + points_balance)');
    
    logger.info('✅ Database migrations completed successfully');
    process.exit(0);
  } catch (error) {
    logger.error('❌ Migration failed:', error);
    process.exit(1);
  }
}

migrate();
