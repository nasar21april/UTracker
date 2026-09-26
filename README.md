# UTracker 🚀 — Architecture & Developer Documentation

> **AI Assistant & Engineer Directive:**
> If you are an AI assistant instructed to *"Please study the repository and read the README at https://github.com/nasar21april/Utracker to understand the architecture, then help me with [your question / task]"*, start by reading this document thoroughly. It provides the authoritative specification of the entire UTracker system architecture, data models, state flows, invariant rules, and directory layout.

---

## 📌 Executive Summary

**UTracker** is a high-performance, real-time algorithmic trading workstation, options chain execution platform, and technical market dashboard designed for Indian financial markets (NSE/BSE). Powered by the **Upstox API v2**, it bridges a native **Android application (Jetpack Compose)**, a local **Desktop Trading Dashboard (HTTP + Server-Sent Events / WebSocket proxy)**, and **Firebase Realtime Database** for seamless cross-device synchronization.

---

## 🏛️ System Architecture

```
                    ┌──────────────────────────────────────────────┐
                    │            Upstox API v2 / WebSocket         │
                    │   - Market Feeds (Protobuf / JSON ticks)    │
                    │   - Historical Candle API (1-min Intraday)  │
                    │   - Order Placement (Regular / AMO)         │
                    └──────────────────────┬───────────────────────┘
                                           │
                     ┌─────────────────────┴─────────────────────┐
                     │                                           │
                     ▼                                           ▼
       ┌───────────────────────────┐               ┌───────────────────────────┐
       │   Android Application     │               │ Desktop Trading Dashboard │
       │  (Jetpack Compose App)    │               │ (Python Server + HTML UI) │
       │                           │               │                           │
       │ - FeedViewModel & StateFlow│               │ - desktop_dashboard.py    │
       │ - UpstoxService (OkHttp)  │               │ - desktop_dashboard.html  │
       │ - Real & Paper Trading    │               │ - Localhost HTTP (8080)   │
       │ - S/R Yellow Touch Engine │               │ - Live tick auto-refresh  │
       └─────────────┬─────────────┘               └─────────────▲─────────────┘
                     │                                           │
                     │         ┌───────────────────────┐         │
                     └────────►│   Firebase Cloud RTDB ├─────────┘
                               │ - Token Auto-Sync     │
                               │ - TV QR Pair Session  │
                               │ - Screener State Feed │
                               └───────────────────────┘
```

---

## 🧱 Component Breakdown

### 1. Android Application (`UpstoxMarketDataApp`)
- **Technology Stack**: Kotlin 1.9+, Android SDK 36 (minSdk 24), Jetpack Compose (BOM 2024+), Coroutines & StateFlow, Navigation 3, CameraX (QR scanning), ZXing, OkHttp3, Firebase SDK.
- **Product Flavors**:
  - `mobile`: Default smartphone portrait experience (`applicationId = "com.UpstoxTracker.app"`).
  - `tv`: Dedicated large-screen landscape layout for TV trading rooms (`applicationId = "com.UpstoxTracker.app.tv"`).
- **Core Packages**:
  - `com.example.upstoxmarketdataapp.data`:
    - `UpstoxService.kt`: Comprehensive network gateway. Manages OAuth2 access tokens, authenticated WebSocket streaming connections, protobuf feed parsing, 1-minute intraday candle retrieval, and live order placement.
    - `EquityLedgerStorage.kt`: Persistent device-level storage (SharedPreferences / JSON serialization) for closed and open trades, turnover, and P&L history.
  - `com.example.upstoxmarketdataapp.ui.feed`:
    - `FeedScreen.kt`: The main workstation screen. Houses the custom Canvas candlestick chart, multi-timeframe Support & Resistance overlays, rapid Call/Put execution buttons, dynamic lot sizes, strike selector, and order book.
    - `FeedViewModel.kt`: Central reactive state holder exposing `StateFlow` for index prices (NIFTY, BANK NIFTY, SENSEX), option chain ticks, connection states, and active positions.
  - `com.example.upstoxmarketdataapp.simulator`:
    - `SimulatorPlaybackEngine.kt`: Virtual paper trading engine. Simulates realistic fills, slippage, stop-losses, and profit targets against real-time market data without risking capital.
  - `com.example.upstoxmarketdataapp.ui.tvlink`:
    - `QRScannerScreen.kt`: CameraX-driven scanner for instant pairing with Android TV or desktop sessions.

---

### 2. Desktop Live Trading Dashboard
- **`desktop_dashboard.py`**:
  - Lightweight, dependency-free Python 3 server using the standard `http.server` library.
  - Runs locally on `http://localhost:8080`.
  - Proxies requests to Upstox APIs to eliminate browser CORS restrictions.
  - Automatically loads the authenticated access token directly from Firebase RTDB or local cache—zero manual token entry required.
- **`desktop_dashboard.html`**:
  - Ultra-clean, dark-themed responsive trading terminal.
  - Live charts, order ledger, position table, and one-click execution.
- **`Open_UTracker_Desktop.command`**:
  - One-click macOS executable script to start the backend daemon and automatically open the default web browser.

---

### 3. Cloud Sync & Firebase Backend
- **Cross-Device Authentication**: When the user authenticates on the Android app, the session token is securely written to Firebase Realtime Database. The desktop dashboard reads this token in real-time.
- **Cloud Screener**: Continuously aggregates technical indicators (EMA crossovers, breakout volume, RSI, and pivot touches) to notify the trader across devices.

---

## 🔑 Key Architectural Invariants & Mechanisms

When making any code modifications or debugging, keep these critical invariants in mind:

### 1. Instant F&O Auto-Load (Zero Tab Switching)
- **Problem Solved**: Earlier builds required navigating from F&O to Equity and back to populate market data.
- **Implementation**: The option chain and index price observers are automatically initialized upon startup in `FeedViewModel` via an eager subscription. On tab switch or initial screen render, `loadOptionChain()` triggers immediately with cached or fresh ticks without awaiting secondary touch events.

### 2. Dual-Layer Reset Ledger (Persistence Wipe)
- **Problem Solved**: Resetting the ledger previously cleared in-memory lists, but closing and reopening the app restored old historical trades from disk.
- **Implementation**: The "Reset Ledger" action performs an atomic dual wipe:
  1. Clears in-memory UI state: `viewModel.clearLedgerEntries()`.
  2. Clears persistent disk storage: `EquityLedgerStorage.clearAllEntries(context)`.
  Any new trade ledger additions must adhere to this dual-layer sync.

### 3. S/R Yellow Touch Glow Engine
- **Visual Feedback**: When the current price tick touches or breaches key multi-timeframe Support or Resistance pivot levels, the corresponding level on the canvas triggers a pure yellow glow (`#FFD600`) with a timed decay (1500ms) to alert the trader to potential breakouts or reversals.

### 4. Dynamic Lot Sizing & Strike Steps
- **NIFTY 50**: Strike step `50`, Lot size `75` (or configured custom multiple).
- **BANK NIFTY**: Strike step `100`, Lot size `30` / `15`.
- **SENSEX**: Strike step `100`, Lot size `10` / `20`.

---

## 📂 Repository Directory Map

```
UTracker/
├── .github/
│   └── workflows/
│       └── build-apk.yml               # Automated CI/CD (Cloud APK & Play Store AAB builds)
├── UpstoxMarketDataApp/                # Android Application Project Root
│   ├── app/
│   │   ├── build.gradle.kts            # App-level dependencies, SDK 36, signingConfig
│   │   └── src/main/
│   │       ├── AndroidManifest.xml     # App permissions, activities, orientations
│   │       ├── java/com/example/upstoxmarketdataapp/
│   │       │   ├── data/               # Network, UpstoxService, Storage, Firebase
│   │       │   ├── ui/                 # Jetpack Compose UI Screens, ViewModels, Themes
│   │       │   ├── simulator/          # Paper Trading Simulation Engine
│   │       │   └── MainActivity.kt     # App entry point
│   │       └── res/                    # Drawables, layouts, mipmap icons, values
│   ├── gradle/wrapper/                 # Gradle Wrapper distribution (9.1.0)
│   ├── build.gradle.kts                # Project-level Gradle build configuration
│   ├── gradle.properties               # JVM memory args, AndroidX settings
│   └── upstox_release.keystore         # Cryptographic Release Keystore for Play Store
├── desktop_dashboard.html              # Desktop Live Dashboard Frontend
├── desktop_dashboard.py                # Desktop API proxy and server
├── Open_UTracker_Desktop.command       # 1-Click macOS Desktop launcher
├── utracker_mac.sh                     # Local Mac build & test utility
└── README.md                           # Master Architecture & Developer Documentation
```

---

## 🛠️ Build, Release & CI/CD Pipeline

The repository includes a fully automated **GitHub Actions CI/CD Pipeline** (`.github/workflows/build-apk.yml`).

### What Triggers the Pipeline
- **Git Push**: Pushes to `main` involving files in `UpstoxMarketDataApp/**`.
- **Release Tags**: Pushing tags matching `v*` (e.g., `v1.2`, `v1.3`).
- **Manual Trigger**: Via GitHub Actions `workflow_dispatch` with custom release tag input.

### Artifacts Produced on Every Cloud Build
1. **Direct Android APK (`UTracker-mobile-debug.apk`)**:
   - Built via `./gradlew assembleMobileDebug`.
   - Attached to GitHub Releases for **1-tap direct download** and installation on Android devices.
   - Automatically increments `versionCode = 25 + GITHUB_RUN_NUMBER` for smooth in-place updates.
2. **Google Play Store App Bundle (`UTracker-release.aab`)**:
   - Built via `./gradlew bundleMobileRelease`.
   - Cryptographically signed using `upstox_release.keystore`.
   - Fully optimized and ready to upload directly to the **Google Play Console**.
3. **Automated Play Store Push (Optional)**:
   - If the repository secret `PLAY_STORE_JSON_KEY` (Google Cloud Service Account JSON) is provided in GitHub Secrets, the workflow automatically publishes the bundle to Google Play's **Internal Testing** track via `r0adkll/upload-google-play@v1`.

### Local Build Commands
```bash
cd UpstoxMarketDataApp

# 1. Compile Mobile Debug APK:
./gradlew assembleMobileDebug

# 2. Compile Play Store Release App Bundle (.aab):
./gradlew bundleMobileRelease
```

---

## 🤖 Instructions for AI Assistants Answering Questions

When a developer asks you to work on this repository:
1. **Check the Relevant Layer**:
   - UI / Layout changes $\rightarrow$ Inspect `UpstoxMarketDataApp/app/src/main/java/com/example/upstoxmarketdataapp/ui/feed/FeedScreen.kt`.
   - Trading Logic / Option Chain $\rightarrow$ Inspect `FeedViewModel.kt` and `UpstoxService.kt`.
   - Persistence / Trade History $\rightarrow$ Inspect `EquityLedgerStorage.kt`.
   - Desktop Interface $\rightarrow$ Inspect `desktop_dashboard.html` and `desktop_dashboard.py`.
2. **Preserve Invariants**:
   - Never remove the persistent wipe in `clearLedgerEntries`.
   - Keep `versionCode` dynamic or compatible with CI build numbers.
   - Maintain the `mobile` and `tv` product flavor structure.
3. **Verify Builds**:
   - Always verify Kotlin syntax and Gradle build tasks before submitting modifications.

---

## 📄 License
Private & Proprietary. Copyright © 2026 Nasar Md Alam. All rights reserved.
