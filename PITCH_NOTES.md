# Sleeper Pitch Notes

## 60-second pitch (for judges)
Sleeper is a sleep-to-earn app built for Solana Seeker.  
At night, the user starts a session, keeps the phone nearby, and earns Sleep Points from verifiable session activity.  

Our key value is trustable reward logic:
- backend-authoritative point calculation,
- strict Solana wallet validation,
- replay-safe sessions (`duplicate=true` and no double credit),
- anti-spam rate limits on risky endpoints.

Economy is transparent:
- API returns effective points per second,
- server points per second,
- and full multiplier breakdown.

We also support SKR-native progression: stake effects, paid boosts, and Genesis NFT layer.  
Result: a Seeker-native, blockchain-connected experience that is easy to demo, hard to abuse casually, and ready for iterative hardening after hackathon.

## 2–3 minute technical pitch (for mentors/CTO)
Sleeper is a mobile-first architecture with Kotlin + Compose on client and Node/TypeScript + Postgres on backend.

### Client side
- Local-first model with Room: sessions are queued offline and synced later.
- Wallet flow uses Solana mobile stack.
- Mining session state is composed from uptime, energy, storage, trust-related factors, and boost state.
- UI now follows a normalized typography pass for readability.

### Backend side
- Core endpoints:
  - auth challenge/verify
  - mining session ingestion
  - season and balance
  - payment/night/nft support flows
- Contract compatibility:
  - supports `wallet` and `walletAddress`
  - supports `auth_token` and `authToken`
  - supports snake_case and camelCase field variants for main mining payloads

### Trust and anti-abuse baseline
- Strict Solana wallet format check (PublicKey parse), not only string length.
- Auth token gating for mining session sync.
- Idempotency on session window replay to prevent duplicate rewards.
- In-memory route rate limiter on high-risk POST endpoints.

### Transparent economics
- Mining response includes:
  - `points_per_second`
  - `points_per_second_server`
  - `points_earned`
  - `duplicate`
  - `multipliers` breakdown object

This removes “black-box reward” concerns during demo and simplifies debugging.

### Current status
- Backend test suite: green.
- Android unit tests: green.
- Live smoke checks executed for auth, invalid wallet rejection, rate limiting, and session sync behavior.

### Next hardening step
- Move rate limiting to distributed backend (Redis).
- Strengthen device-level trust and anomaly scoring.
- Extend in-app multiplier explainability UI.
