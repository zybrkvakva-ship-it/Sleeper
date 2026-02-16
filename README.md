# Sleeper

Sleep-to-earn app for Solana Seeker: turn sleep into on-chain rewards. Mine **Sleep Points** — points for verified sleep.

---

## Overview

Sleeper is a mobile-first sleep-to-earn app for Solana Seeker and Solana dApp Store. The user starts a night session, places the Seeker nearby, does not use it at night, and earns **Sleep Points** for “honest” sleep verified by sensors and anti-abuse logic. Daily sleep becomes a gamified DePIN experience in the Solana Mobile ecosystem.

---

## What Sleeper does (flow)

1. User opens Sleeper on Solana Seeker — sees the main screen with night status and potential rewards.
2. Before sleep, starts the night session and leaves the app running in the background.
3. At night Sleeper uses sensors and device state to verify session legitimacy (stillness, screen off).
4. In the morning — result: session confirmed or flagged as suspicious, **Sleep Points** accrued, streak progress, and active boosts.

**Economy:** you mine **Sleep Points** first (for sleep duration, meeting conditions, and boosts).

---

## Economy: Sleep Points

Accrual only for verified sleep (phone still, user asleep).

- **Sleep Points** depend on:
  - sleep time (session uptime),
  - allocated storage (Storage, 100–600 MB),
  - passing human checks (periodic activity checks).

Reward formula:
```
REWARD (Sleep Points) = BASE × STORAGE × HUMAN_CHECK
BASE = 0.2 pts/sec × uptime_minutes
STORAGE = 1.0 + (allocated_MB / 100 - 1)   // x1.0 to x6.0
HUMAN_CHECK = 1.0 (80%+ success) | 0.7 (50–80%) | 0.3 (<50%)
```

Boosts: SKR token staking (+20% / +50%), paid boosts for SKR (1×/7×/49×), Genesis NFT (+10%).

---

## Mobile-first and Seeker focus

Sleeper only makes sense as mobile-first: the phone is physically next to the user during sleep. It uses:

- **Accelerometer** — stillness / natural nighttime movement;
- **Screen state** — screen inactive during the session;
- **WorkManager / foreground service** — long night tracking with minimal battery use.

Distribution: Solana dApp Store; target device — Solana Seeker.

---

## Anti-abuse and integrity

- **Accelerometer** — distinguish natural movement from scripts and artificial motion.
- **Screen** — most of the session with screen off.
- **Time** — protection against timezone/time changes for farming.
- **Device fingerprint** — bound to a specific Seeker; device limit per user.
- **Seeker Device Check** — device/model check (release: real Seekers only).

---

## Tech stack

- **Language:** Kotlin 1.9.22  
- **UI:** Jetpack Compose (Material 3)  
- **Data:** Room, DataStore  
- **Background:** WorkManager, ForegroundService  
- **Platform:** Solana Seeker, Solana Mobile; MWA (Seed Vault), Solana RPC (.skr, staking)  
- **Sensors:** Android API (accelerometer, screen state, battery)

App structure:
```
app/
├── data/       # Room, DAO, Repository
├── domain/     # EnergyManager, StorageManager
├── security/   # DeviceVerifier (anti-abuse)
├── service/    # MiningService, HumanCheckWorker
└── ui/         # theme, components, screen, navigation
```

---

## How to run

### Run via APK

- Download the latest APK from Releases.
- Install on Solana Seeker, launch Sleeper, complete onboarding, and open the night session screen.
- For testing: a real night with the device nearby or debug/test mode (if enabled).

### Build from source

**Requirements:** Android Studio Hedgehog (2023.1.1)+, JDK 17, Android SDK 34, minSdk 26.

1. Clone the repo:
   ```bash
   git clone https://github.com/zybrkvakva-ship-it/Sleeper.git
   cd Sleeper
   ```
2. Open the project in Android Studio.
3. Create `local.properties` in the project root (see template below). This file is in .gitignore — do not commit it.
   ```properties
   sdk.dir=/path/to/android-sdk
   API_BASE_URL=https://your-backend.com
   BOOST_TREASURY=YourSolanaTreasuryAddress
   HELIUS_API_KEY=your-helius-api-key
   ```
4. Build: Build → Make Project or `./gradlew assembleDebug` / `assembleRelease`.
5. Install the APK on Solana Seeker and repeat the flow above.

**Firebase (optional):** project "Sleeper", Android app with package `com.sleeper.app`; place `google-services.json` in `app/`.

---

## UI

- **Theme:** dark (black background, green and orange accents).
- **5 tabs:** Mining (main), Upgrade, Tasks, Top, Wallet.
- On the mining screen: session status, energy, start/stop button, Sleep Points rate, storage.

---

## APK Build

```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

---

## License

MIT. See [LICENSE](LICENSE).

---

## Contact

- **Telegram:** @Sleeper  
- **Twitter:** @SleeperApp  
- **Discord:** discord.gg/sleeper  

**Built with ❤️ for Solana Seeker community**
