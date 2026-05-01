# SeekerMiner Android Project

A Kotlin + Jetpack Compose Android app that mines the SeekerMiner cryptocurrency.  
The project follows a clean modular architecture, uses Hilt for DI, Room for persistence, and Firebase/okhttp for backend communication.  

---

## Table of Contents
1. [Project Structure](#project-structure)
2. [Key Features](#key-features)
3. [Getting Started](#getting-started)
   - [Prerequisites](#prerequisites)
   - [Clone & Build](#clone--build)
   - [Run the App](#run-the-app)
4. [Architecture Overview](#architecture-overview)
5. [Dependency Injection (Hilt)](#dependency-injection-hilt)
6. [Testing](#testing)
7. [Release Signing](#release-signing)
8. [CI/CD Pipeline](#ci-cd-pipeline)
9. [Documentation](#documentation)
10. [License](#license)

---

## Project Structure
```
SeekerMiner/
├─ app/                 # UI layer (Compose)
├─ core/                # Business logic, managers, repository, DI modules
├─ data/                # Room DB, DAOs, network models (may become a separate module)
├─ di/                  # Hilt modules (ManagerModule, NetworkModule, DatabaseModule)
├─ res/                 # Resources (themes, colors, splash, icons)
├─ build.gradle.kts     # Root Gradle script
├─ settings.gradle.kts  # Includes :app, :core, :data
└─ keystore.properties  # Keystore placeholders (fill before release)
```

---

## Key Features
- **Modular architecture** – `core` contains all business logic, `app` only UI.  
- **Material 3** theme with dynamic colors, adaptive icons, and splash‑screen.  
- **Hilt DI** – singleton managers (`EnergyManager`, `WalletManager`) and repository.  
- **Room persistence** – local storage of tasks, user stats, and mining state.  
- **Coroutines + Flow** – asynchronous operations and reactive UI state.  
- **Splash‑screen + Lottie‑ready** – custom animation and adaptive icon support.  
- **Unit & UI tests** – in‑memory Room database, Robolectric tests.  
- **GitHub Actions CI** – lint, unit tests, debug & signed APK builds.

---

## Getting Started

### Prerequisites
- JDK 17 (or newer)  
- Android Studio Flamingo (or later)  
- Gradle 8.2+ (bundled with Android Studio)  
- Git  

### Clone & Build
```bash
git clone https://github.com/your‑org/SeekerMiner.git
cd SeekerMiner
# If you use the Gradle wrapper (recommended)
./gradlew assembleDebug          # builds debug APK
./gradlew assembleRelease        # builds release APK (unsigned)
```

### Run the App
1. Open the project in Android Studio.  
2. Select a virtual device (API 21+) or connect a physical device.  
3. Press **Run** ► **app**.  
4. The first launch shows the splash‑screen, then the main UI with **Start Mining** button.

---

## Architecture Overview
```
┌───────────────────────┐
│        UI (Compose)    │
│   – MainActivity       │
│   – MiningScreen       │
└─────────▲─────────────┘
          │
          │   (ViewModel)
          │   Hilt @HiltViewModel
          │
┌─────────▼─────────────┐
│   core Layer          │
│   – Managers (Energy,│
│     Wallet)           │
│   – Repository        │
│   – DI Modules        │
└─────────▲─────────────┘
          │
          │   (Repository)
          │
┌─────────▼─────────────┐
│   data Layer          │
│   – Room DB           │
│   – DAOs              │
│   – Network API       │
│   – Retrofit/Moshi    │
└───────────────────────┘
```

---

## Dependency Injection (Hilt)

- **`ManagerModule`** – provides singleton `EnergyManager` & `WalletManager`.  
- **`NetworkModule`** – provides `OkHttpClient`, `Moshi`, `MiningBackendApi`, `SolanaRpcClient`.  
- **`DatabaseModule`** – creates `AppDatabase`, DAOs, and the concrete `MiningRepository`.  

All modules are declared in `core/src/main/java/com/sleeper/core/di/*.kt`.

---

## Testing

Unit tests live under `core/src/test/java/com/sleeper/core/data/repository/MiningRepositoryTest.kt`.  
They use:

- **JUnit4** + **Robolectric** (in‑memory DB)  
- **kotlinx‑coroutines‑test** for coroutine control  

Run them with:
```bash
./gradlew testDebugUnitTest
```

Add more tests under the same package as needed.

---

## Release Signing

1. Fill **`keystore.properties`** at the project root with real values.  
2. The `app/build.gradle.kts` already reads these properties via `signingConfigs`.  
3. Build a signed release APK:
   ```bash
   ./gradlew assembleRelease
   ```
   The unsigned APK will be located at `app/build/outputs/apk/release/`.  
   (Signing occurs automatically when the keystore properties are populated.)

---

## CI/CD Pipeline

The workflow is defined in `.github/workflows/ci.yml`.  
It performs:

- `./gradlew lintDebug`  
- `./gradlew testDebugUnitTest`  
- `./gradlew assembleDebug` (debug APK)  
- `./gradlew assembleRelease` (release APK)  
- Uploads the built APKs as artifacts.  

When `keystore.properties` is filled, the release build will be **automatically signed**.

---

## Documentation

- **`README.md`** – this file.  
- **`DESIGN.md`** – architectural diagram and deep‑dive into each layer.  
- **`SKILL.md`** files inside each `skill/` folder – reusable procedural knowledge (e.g., “how to add a new Hilt module”).  

Path: `/docs/DESIGN.md` (created below).

---

## License
```text
© 2026 SeekerMiner Project
 SPDX‑License-Identifier: MIT
```

---  

*Prepared with ❤️ by the autonomous engineering team.*  