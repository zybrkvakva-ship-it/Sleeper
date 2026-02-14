# Sleeper

Приложение формата sleep-to-earn для Solana Seeker: ночной сон превращается в ончейн-награды. Сначала майнятся **Sleep Points** — поинты за подтверждённый сон.

---

## Overview

Sleeper — mobile-first sleep-to-earn приложение под Solana Seeker и Solana dApp Store. Пользователь запускает ночную сессию, кладёт Seeker рядом, не использует его ночью и получает **Sleep Points** за «честный» сон, подтверждённый датчиками и анти-абуз-логикой. Ежедневный сон становится геймифицированным DePIN-опытом в экосистеме Solana Mobile.

---

## Что делает Sleeper (сценарий)

1. Пользователь открывает Sleeper на Solana Seeker — видит главный экран со статусом ночи и потенциальными наградами.
2. Перед сном запускает ночную сессию, оставляет приложение работать в фоне.
3. Ночью Sleeper по датчикам и состоянию устройства подтверждает легитимность сессии (неподвижность, экран выключен).
4. Утром — результат: сессия подтверждена или помечена как подозрительная, начислены **Sleep Points**, прогресс стрика и активные бусты.

**Экономика:** сначала майнятся **Sleep Points** (за длительность сна, соблюдение условий и бусты). Дальше возможна конвертация в токены/сезоны по дорожной карте.

---

## Экономика: Sleep Points

Начисление только за подтверждённый сон (телефон неподвижен, хозяин спит).

- **Sleep Points** зависят от:
  - времени сна (uptime сессии),
  - выделенного хранилища (Storage, 100–600 MB),
  - прохождения human checks (периодические проверки активности).

Формула награды:
```
НАГРАДА (Sleep Points) = BASE × STORAGE × HUMAN_CHECK
BASE = 0.2 pts/сек × uptime_минуты
STORAGE = 1.0 + (выделено_MB / 100 - 1)   // x1.0 до x6.0
HUMAN_CHECK = 1.0 (80%+ успеха) | 0.7 (50–80%) | 0.3 (<50%)
```

Бусты: стейкинг токена SKR (+20% / +50%), платные бусты за SKR (1×/7×/49×), Genesis NFT (+10%) — см. [docs/STORE_AND_HACKATHON.md](docs/STORE_AND_HACKATHON.md).

---

## Mobile-first и фокус на Seeker

Sleeper имеет смысл только как mobile-first: телефон физически рядом с человеком во время сна. Используются:

- **акселерометр** — неподвижность / естественные движения ночью;
- **состояние экрана** — экран не активен во время сессии;
- **WorkManager / foreground service** — длительный ночной трекинг с минимальным расходом батареи.

Дистрибуция: Solana dApp Store, целевое устройство — Solana Seeker.

---

## Anti-abuse и честность

- **Акселерометр** — отличие естественных движений от скриптов и искусственных колебаний.
- **Экран** — большая часть сессии с выключенным экраном.
- **Время** — защита от смены часового пояса/времени для накрутки.
- **Device fingerprint** — привязка к конкретному Seeker, лимит устройств на пользователя.
- **Seeker Device Check** — проверка модели/устройства (в релизе — только реальные Seeker).

---

## Tech stack

- **Язык:** Kotlin 1.9.22  
- **UI:** Jetpack Compose (Material 3)  
- **Данные:** Room, DataStore  
- **Фон:** WorkManager, ForegroundService  
- **Платформа:** Solana Seeker, Solana Mobile; MWA (Seed Vault), Solana RPC (.skr, стейкинг)  
- **Сенсоры:** Android API (акселерометр, состояние экрана, батарея)

Структура приложения:
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

### Запуск через APK

- Скачай актуальный APK из Releases или по ссылке сабмита.
- Установи на Solana Seeker, запусти Sleeper, пройди onboarding и открой экран ночной сессии.
- Для теста: реальная ночь с устройством рядом или debug/тестовый режим (если включён).

### Сборка из исходников

**Требования:** Android Studio Hedgehog (2023.1.1)+, JDK 17, Android SDK 34, minSdk 26.

1. Клонируй репозиторий:
   ```bash
   git clone https://github.com/zybrkvakva-ship-it/Sleeper.git
   cd Sleeper
   ```
2. Открой проект в Android Studio.
3. Создай `local.properties` в корне (по образцу ниже). Файл в .gitignore, не коммитить.
   ```properties
   sdk.dir=/path/to/android-sdk
   API_BASE_URL=https://your-backend.com
   BOOST_TREASURY=YourSolanaTreasuryAddress
   HELIUS_API_KEY=your-helius-api-key
   ```
4. Сборка: Build → Make Project или `./gradlew assembleDebug` / `assembleRelease`.
5. Установи APK на Solana Seeker и повтори сценарий выше.

**Firebase (опционально):** проект "Sleeper", Android app с package `com.sleeper.app`, скачай `google-services.json` в `app/`.

---

## UI

- **Тема:** тёмная (чёрный фон, зелёные и оранжевые акценты).
- **5 вкладок:** Майнинг (главный), Апгрейд, Задания, Топ, Кошелёк.
- На экране майнинга: статус сессии, энергия, кнопка старта/стопа, скорость начисления Sleep Points, storage.

---

## Документация

Гайды, отчёты и инструкции — в папке **[docs/](docs/)**. В корне только этот README и конфиги.

---

## APK Build

```bash
./gradlew assembleRelease
# Результат: app/build/outputs/apk/release/app-release.apk
```

---

## License

Proprietary (пока). После релиза — MIT/Apache 2.0.

---

## Контакты

- **Telegram:** @Sleeper  
- **Twitter:** @SleeperApp  
- **Discord:** discord.gg/sleeper  

**Built with ❤️ for Solana Seeker community**
