# 🧠 SeekerMiner - Pseudo Mining App for Solana Seeker

**MVP мобильного приложения-майнера в стиле Memhash для Solana Seeker телефонов**

---

> 📚 **Вся документация** (гайды, отчёты, инструкции) — в папке **[docs/](docs/)**. В корне только этот README и конфиги.

---

## 🎯 Что это?

SeekerMiner — это игровое приложение для владельцев Solana Seeker, где пользователи фармят **SKR Points** (будущие тикеты в дропы) через:
- **Proof-of-Presence**: время работы приложения
- **Proof-of-Storage**: выделенная память телефона (100-600MB)
- **Human Checks**: периодические проверки активности (раз в 30 мин)

## 🏗️ Архитектура

```
Tech Stack:
- Kotlin 1.9.22
- Jetpack Compose (Material 3)
- Room Database
- WorkManager (periodic human checks)
- ForegroundService (майнинг)
- Firebase (leaderboard, analytics)
- Mobile Wallet Adapter (Seed Vault), Solana RPC (.skr, стейкинг)
```

**Бусты и механика:** стейкинг SKR (+20% / +50%), платные бусты за SKR (1×/7×/49×), Genesis NFT (+10%) — см. [docs/STORE_AND_HACKATHON.md](docs/STORE_AND_HACKATHON.md).

**Структура:**
```
app/
├── data/           # Room Database, DAO, Repository
├── domain/         # Business logic (EnergyManager, StorageManager)
├── security/       # DeviceVerifier (anti-abuse)
├── service/        # MiningService, HumanCheckWorker
└── ui/
    ├── theme/      # Memhash-style (чёрный фон, зелёные акценты)
    ├── components/ # MiningButton, EnergyBar
    ├── screen/     # 5 вкладок (Майнинг, Апгрейд, Задания, Топ, Кошелёк)
    └── navigation/ # Bottom navigation
```

---

## 🚀 Как запустить (Development)

### 1. Требования
- **Android Studio** Hedgehog (2023.1.1) или новее
- **JDK 17**
- **Android SDK 34**
- **minSdk 26** (Android 8.0+)

### 2. local.properties (секреты, не коммитить)
В корне проекта создай или допиши `local.properties` (уже в .gitignore):
```properties
sdk.dir=/path/to/android-sdk
API_BASE_URL=https://your-backend.com
BOOST_TREASURY=YourSolanaTreasuryAddress
HELIUS_API_KEY=your-helius-api-key
```
Без `HELIUS_API_KEY` используется публичный Solana RPC. С ключом Helius — быстрее и стабильнее для .skr и стейкинга.

### 3. Firebase Setup (опционально)
```bash
# 1. Перейди на https://console.firebase.google.com/
# 2. Создай проект "SeekerMiner"
# 3. Добавь Android app: Package com.seekerminer.app
# 4. Скачай google-services.json в app/
```

### 4. Сборка и запуск
```bash
# Открой проект в Android Studio
# File > Open > Seeker_Mining/

# Sync Gradle
# Build > Clean Project
# Build > Rebuild Project

# Запусти на эмуляторе или реальном устройстве
# Run > Run 'app'
```

---

## 🎨 UI Концепция (1:1 Memhash)

### Цветовая палитра:
```kotlin
Фон:       #000000 (чёрный)
Акцент 1:  #00FF41 (неоновый зелёный) - кнопки, прогресс
Акцент 2:  #FF8C00 (яркий оранжевый) - поинты, награды
Текст:     #FFFFFF (белый)
Карточки:  #1A1A1A, #2A2A2A (тёмно-серые)
```

### 5 вкладок (Bottom Navigation):
1. **🏭 МАЙНИНГ** — главный экран (80% времени):
   - Блок статистики (номер блока, награда, сложность)
   - Энергия бар
   - Кнопка "НАЧАТЬ МАЙНИНГ" (60% ширины, 72dp высоты)
   - Текущая скорость фарма (pts/s, uptime, storage)

2. **⚡ АПГРЕЙД** — покупка усилений:
   - Турбо x4, Супер x10 (speed)
   - Storage увеличение (100MB → 600MB)
   - Auto-check (автоматические human checks)

3. **✅ ЗАДАНИЯ** — ежедневные и специальные:
   - Пригласи друга (+300 pts)
   - Поделись (+500 pts)
   - Подписка на @SeekerMiner (+5000 pts)

4. **👑 ТОП** — лидерборд:
   - TOP 100 майнеров (mock data пока)
   - Твоя позиция (#847)
   - Глобальная статистика (всего поинтов, блоков)

5. **💰 КОШЕЛЁК** — баланс:
   - SKR Points
   - Звёзды
   - Подключение Seed Vault (TODO)
   - Claim (TODO)

---

## ⚙️ Игровая механика

### Награда за майнинг:
```
НАГРАДА = BASE × STORAGE × HUMAN_CHECK

BASE = 0.2 pts/сек × uptime_минуты
STORAGE = 1.0 + (выделено_MB / 100 - 1)  // x1.0 до x6.0
HUMAN_CHECK = 1.0 (80%+ успеха) | 0.7 (50-80%) | 0.3 (<50%)
```

**Пример:**
- 150MB storage, 60 минут uptime, 2 пинга пройдено:
  - BASE = 0.2 × 60 = 12 pts/мин
  - STORAGE = 1.5x
  - HUMAN_CHECK = 1.0x
  - **Итого: 864 pts за час**

### Энергия:
- **Макс:** 1200 единиц (может увеличиваться апгрейдами)
- **Трата:** 1 ед/сек при майнинге
- **Восстановление:** 10 ед/мин при простое

### Storage plots:
- **1 плот = 100MB** реального файла на диске
- **Макс:** 6 плотов (600MB)
- Файлы хранятся в `app_filesDir/mining_storage/`
- Валидация: периодическая проверка целостности

---

## 🛡️ Anti-Abuse System

### 1. Seeker Device Check
```kotlin
// Проверяет Build.MODEL, Build.DEVICE, Build.MANUFACTURER
// ДЛЯ РАЗРАБОТКИ: временно отключено (вернуть в продакшене!)
isSeekerDevice() // сейчас всегда true
```

### 2. Emulator Detection
- `Build.FINGERPRINT == "generic"`
- Отсутствие сенсоров (gyroscope, accelerometer)

### 3. Root Detection
- Проверка test-keys
- Файлы su в системе
- Runtime exec check

### 4. Clone App Detection
- UserHandle != 0 (multi-profile)
- Package signature mismatch

### 5. Device Fingerprint
```kotlin
hash(Build.MODEL + Build.DEVICE + Build.ID + Android_ID)
// Сохраняется в SharedPreferences
// Лимит 1 аккаунт на fingerprint (в будущем - через backend)
```

---

## 📚 Документация

Документация и черновики хранятся локально в папке `docs/` (в репозиторий не коммитится). В GitHub только код и этот README.

---

## 📋 TODO (Day 3-5)

### День 3: Storage + Mining Logic
- [ ] Реализовать полную логику storage allocation
- [ ] Тестирование MiningService с реальными файлами
- [ ] Добавить уведомления о низкой энергии

### День 4: Anti-abuse
- [ ] **ВАЖНО:** Включить реальную Seeker проверку
- [ ] Sign In with Solana (SIWS) для enhanced verification
- [ ] Backend API для валидации fingerprint
- [ ] Ban-система для нарушителей

### День 5: Firebase + Polish + **MWA Integration** 🔥
- [x] **Mobile Wallet Adapter** ✅
- [x] **.skr Token (Triple Verification)** ✅
- [ ] **Full guide:** [docs/MWA_INTEGRATION_GUIDE.md](docs/MWA_INTEGRATION_GUIDE.md)
- [ ] Firebase Firestore для leaderboard
- [ ] Firebase Analytics события
- [ ] Demo video
- [ ] APK подпись и релиз

---

## 🔥 Известные проблемы (Development mode)

1. **Device Verification временно отключена!**
   ```kotlin
   // В DeviceVerifier.kt:49
   return true  // TODO: В продакшене вернуть проверку!
   ```

2. **Firebase заглушка**
   - `google-services.json` — временный файл
   - Нужно создать настоящий Firebase проект

3. **Leaderboard mock data**
   - TOP 100 — захардкоженные данные
   - Нужно подключить Firestore

4. **Storage не очищается при удалении апа**
   - Плоты остаются в `filesDir` (нормально для тестов)

---

## 🎯 MVP Roadmap

| День | Задачи | Статус |
|------|--------|--------|
| **Day 1-2** | UI + Navigation + Room + Services | ✅ DONE |
| **Day 3** | Storage plots + Energy system | 🔜 NEXT |
| **Day 4** | Anti-abuse + Human checks | ⏳ TODO |
| **Day 5** | Firebase + Leaderboard + APK | ⏳ TODO |

---

## 🧪 Testing

### Эмулятор:
```bash
# Создай эмулятор с:
# - API 34 (Android 14)
# - 4GB RAM
# - 2GB storage
```

### Реальное устройство (Seeker):
```bash
# Включи USB Debugging
# adb devices
# Run > Run on > Твой Seeker
```

### Проверка майнинга:
1. Запусти апп
2. Тап "НАЧАТЬ МАЙНИНГ"
3. Проверь notification (foreground service)
4. Свайпни вниз статус-бар → видишь "Майним SKR Points"
5. Открой апп → поинты растут
6. Через 30 мин → push notification "Проверка активности"

---

## 📱 APK Build

```bash
# Release APK:
./gradlew assembleRelease

# Output:
# app/build/outputs/apk/release/app-release.apk
```

---

## 🤝 Contributing

Пока закрытая разработка (MVP). После демо — open source.

---

## 📄 License

Proprietary (пока). После релиза — MIT/Apache 2.0.

---

## 📞 Контакты

- **Telegram:** @SeekerMiner
- **Twitter:** @SeekerMinerApp
- **Discord:** discord.gg/seekerminer

---

**Built with ❤️ for Solana Seeker community**
