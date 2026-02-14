# 🌙 NightMiner Backend API

Production-ready backend server for NightMiner sleep-to-earn application.

## 🚀 Features

- **Night Session Processing** - Calculate Night Points and rewards
- **SLEEP Token Distribution** - Automated daily distribution at 9 AM
- **Leaderboard System** - Real-time rankings with PostgreSQL
- **Economy Logic** - Full TypeScript port of Kotlin economy module
- **WebSocket Support** - Real-time sync for night sessions
- **Genesis NFT System** - Eligibility checks and supply tracking
- **SKR Payment Processing** - Boost purchases (Phase 2)
- **Referral System** - Track and reward referrals

## 📦 Tech Stack

- **Runtime:** Node.js 18+
- **Framework:** Express.js
- **Database:** PostgreSQL 14+
- **WebSocket:** ws
- **Language:** TypeScript

## 🏗️ Project Structure

```
backend/
├── src/
│   ├── index.ts                    # Main server entry
│   ├── database/
│   │   ├── index.ts                # Database connection
│   │   ├── schema.sql              # Database schema
│   │   └── migrate.ts              # Migration runner
│   ├── economy/
│   │   ├── constants.ts            # Economy constants
│   │   └── index.ts                # Economy calculations
│   ├── routes/
│   │   ├── night.ts                # Night session endpoints
│   │   ├── user.ts                 # User management
│   │   ├── leaderboard.ts          # Leaderboard endpoints
│   │   ├── season.ts               # Season info
│   │   ├── nft.ts                  # Genesis NFT
│   │   └── payment.ts              # SKR payments
│   ├── services/
│   │   └── distributionScheduler.ts # Daily SLEEP distribution
│   ├── websocket/
│   │   └── index.ts                # WebSocket server
│   ├── middleware/
│   │   └── errorHandler.ts         # Error handling
│   └── utils/
│       └── logger.ts               # Logging utility
├── package.json
├── tsconfig.json
└── .env.example
```

## 🔧 Installation

### 1. Install Dependencies

```bash
cd backend
npm install
```

### 2. Setup PostgreSQL

Create database:

```bash
createdb nightminer
```

Or using psql:

```sql
CREATE DATABASE nightminer;
```

### 3. Configure Environment

Copy `.env.example` to `.env` and configure:

```bash
cp .env.example .env
```

Edit `.env` with your values:

```env
DATABASE_URL=postgresql://user:password@localhost:5432/nightminer
SOLANA_RPC_URL=https://api.mainnet-beta.solana.com
PORT=3000
WS_PORT=3001
```

### 4. Run Migrations

```bash
npm run db:migrate
```

This will create all tables, indexes, and initial data.

### 5. Start Development Server

```bash
npm run dev
```

Server will start on:
- HTTP: `http://localhost:3000`
- WebSocket: `ws://localhost:3001`

## 📡 API Endpoints

### Health Check

```
GET /health
```

### User Management

```
POST   /api/v1/user/register
GET    /api/v1/user/:walletAddress
POST   /api/v1/user/apply-referral
```

### Night Sessions

```
POST   /api/v1/night/start
POST   /api/v1/night/end
GET    /api/v1/night/history/:walletAddress
```

### Leaderboard

```
GET    /api/v1/leaderboard
GET    /api/v1/leaderboard/rank/:walletAddress
POST   /api/v1/leaderboard/refresh
```

### Season

```
GET    /api/v1/season/current
```

### Genesis NFT

```
POST   /api/v1/nft/check-eligibility
GET    /api/v1/nft/supply
POST   /api/v1/nft/mint (Phase 2)
```

### Payments

```
POST   /api/v1/payment/verify-skr (Phase 2)
GET    /api/v1/payment/active-boost/:walletAddress
```

## 🔌 WebSocket Events

### Client → Server

```typescript
// Connect and register
{
  type: 'night:register',
  walletAddress: 'ABC...',
  nightId: 'uuid'
}

// Send updates
{
  type: 'night:update',
  data: { movements: 5, screenOns: 2 }
}

// Heartbeat
{
  type: 'ping'
}
```

### Server → Client

```typescript
// Connection established
{
  type: 'connected',
  clientId: 'client_123',
  timestamp: 1234567890
}

// SLEEP distributed
{
  type: 'sleep-distributed',
  date: '2026-02-06',
  totalNp: 100000,
  poolNight: 50000,
  usersCount: 100
}

// Heartbeat response
{
  type: 'pong',
  timestamp: 1234567890
}
```

## 🎯 Daily Distribution

SLEEP tokens are automatically distributed every day at **9:00 AM server time**.

Process:
1. Get all unprocessed night sessions from yesterday
2. Calculate total NP
3. Calculate pool for night based on active devices
4. Distribute SLEEP proportionally
5. Update database
6. Refresh leaderboard
7. Broadcast to WebSocket clients

Manual trigger (development):

```typescript
import { triggerDistribution } from './services/distributionScheduler';
await triggerDistribution();
```

## 🚀 Deployment

### Railway.app (Recommended)

1. Create account on [Railway.app](https://railway.app)

2. Install Railway CLI:

```bash
npm install -g @railway/cli
```

3. Login and initialize:

```bash
railway login
railway init
```

4. Add PostgreSQL:

```bash
railway add --plugin postgresql
```

5. Set environment variables:

```bash
railway variables set SOLANA_RPC_URL=https://api.mainnet-beta.solana.com
railway variables set NODE_ENV=production
```

6. Deploy:

```bash
railway up
```

Your backend will be available at: `https://your-app.up.railway.app`

### Environment Variables for Production

```env
NODE_ENV=production
PORT=3000
WS_PORT=3001
DATABASE_URL=<railway_provides>
SOLANA_RPC_URL=<your_rpc_endpoint>
ANS_PROGRAM_ID=ALTNSZ46uaAUU7XUV6awvdorLGqAsPwa9shm7h4uP2FK
JWT_SECRET=<generate_strong_secret>
ALLOWED_ORIGINS=https://your-app.com
```

## 📊 Database Maintenance

### Refresh Leaderboard

```sql
SELECT refresh_leaderboard();
```

### Check Distribution History

```sql
SELECT * FROM night_distributions ORDER BY night_date DESC LIMIT 10;
```

### View Active Season

```sql
SELECT * FROM season_stats WHERE status = 'ACTIVE';
```

## 🧪 Testing

### Manual API Testing

Using curl:

```bash
# Register user
curl -X POST http://localhost:3000/api/v1/user/register \
  -H "Content-Type: application/json" \
  -d '{"walletAddress":"ABC...","skrUsername":"test.skr"}'

# End night session
curl -X POST http://localhost:3000/api/v1/night/end \
  -H "Content-Type: application/json" \
  -d '{
    "walletAddress":"ABC...",
    "minutesSlept":480,
    "storageMb":100,
    "movementViolations":0,
    "screenOnCount":0
  }'

# Get leaderboard
curl http://localhost:3000/api/v1/leaderboard
```

## 📝 Logging

Logs are output to console with timestamps:

```
[2026-02-06T12:00:00.000Z] [INFO] NightMiner Backend started
[2026-02-06T12:00:01.000Z] [INFO] Database connected
[2026-02-06T09:00:00.000Z] [INFO] 🎁 Starting daily SLEEP distribution...
```

Log levels: `debug`, `info`, `warn`, `error`

Set log level in `.env`:

```env
LOG_LEVEL=info
```

## 🔐 Security

- CORS configured for allowed origins
- Helmet.js for security headers
- Input validation on all endpoints
- SQL injection prevention (parameterized queries)
- Rate limiting (to be added in Phase 2)

## 📈 Performance

- Connection pooling (max 20 connections)
- Materialized view for leaderboard
- Indexed queries for fast lookups
- WebSocket for real-time updates

## 🐛 Troubleshooting

### Database Connection Error

```
Error: connect ECONNREFUSED 127.0.0.1:5432
```

**Solution:** Ensure PostgreSQL is running and DATABASE_URL is correct.

### Migration Fails

```
Error: relation "users" already exists
```

**Solution:** Drop database and recreate, or modify migration script.

### Distribution Not Running

Check server logs at 9 AM. Ensure scheduler is started:

```typescript
// In index.ts
startDistributionScheduler();
```

## 📚 Next Steps

1. **Phase 2: NFT Minting** - Implement Solana smart contract integration
2. **Phase 2: SKR Payments** - Add full payment verification
3. **Phase 2: Anti-Cheat** - Enhanced validation and fraud detection
4. **Phase 3: Analytics** - Dashboard and metrics
5. **Phase 3: Admin Panel** - Management UI

## 🤝 Support

For issues or questions:
- Check logs first
- Review API documentation
- Contact: support@nightminer.app

---

**Built with ❤️ for NightMiner**
