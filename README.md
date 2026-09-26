# UTracker 🚀

**UTracker** is a high-performance market feed, algorithmic options trading workstation, and technical chart dashboard built for the Upstox API. It features both a modern Android application (Jetpack Compose) and a live Desktop Trading Dashboard.

---

## 🌟 Key Features

### 📱 Android Application (`UpstoxMarketDataApp`)
- **Instant Live Feed**: Real-time streaming for **NIFTY 50**, **BANK NIFTY**, and **SENSEX** via Upstox Market Data Feed.
- **Dual-Layer Candlestick & Pivot Charts**:
  - 1-minute intraday historical candles.
  - Multi-timeframe Support & Resistance levels with touch-triggered yellow glow (`#FFD600`).
  - True WebSocket tick arrival timestamps.
- **Option Chain & Rapid Execution**:
  - Dynamic strike intervals (50 for NIFTY, 100 for BANKNIFTY & SENSEX).
  - Fast Call (CE) & Put (PE) order buttons with configurable lot sizes and stop-loss targets.
- **Dual Mode Trading (Real & Virtual)**:
  - Seamlessly switch between Virtual Paper Trading and Live Upstox Real Trading.
  - Global P&L tracker with turnover calculation (`Exec: ₹... | Entry | Qty`).
  - **Reset Ledger**: One-tap wipe for both in-memory ledger and persistent device database.
- **Automated Equity Screener**:
  - Live technical & fundamental screener synced with Firebase Cloud Engine.
  - Automated win/loss tracking and breakout volume detection.

### 🖥️ Desktop Live Trading Dashboard
- **Web-based GUI**: Clean, dark-mode terminal running locally on `http://localhost:8080`.
- **Firebase Auto-Token Sync**: Reads your authenticated Upstox JWT token automatically—no manual token copy-pasting required.
- **One-Click Mac Launcher**: Double-click `Open_UTracker_Desktop.command` on your Desktop to boot the server and open your browser automatically.
- **Zero External Dependencies**: Powered by Python's built-in `http.server` standard library.

---

## 🚀 Quick Start

### 1. Android Installation
Download the latest APK release:
- **Download**: [UTracker-mobile-debug.apk](https://github.com/nasar21april/UTracker/releases/latest)
- Install on your Android phone (enable *Install from Unknown Sources* if prompted).

### 2. Desktop Dashboard Launch
Run directly from your Mac Desktop:
```bash
./Open_UTracker_Desktop.command
```
Or start manually via Python:
```bash
python3 desktop_dashboard.py
```
Then navigate to `http://localhost:8080` in Safari or Chrome.

### 3. Building From Source
```bash
cd UpstoxMarketDataApp
./gradlew assembleMobileDebug
```
The compiled APK will be output at:
`UpstoxMarketDataApp/app/build/outputs/apk/mobile/debug/app-mobile-debug.apk`

---

## 📂 Repository Structure

```
UTracker/
├── UpstoxMarketDataApp/          # Complete Android Studio project (Jetpack Compose)
│   ├── app/                      # Application module & UI screens
│   ├── gradle/                   # Gradle wrapper configuration
│   └── build.gradle.kts          # Top-level build script
├── desktop_dashboard.html        # Desktop Live Trading Dashboard GUI
├── desktop_dashboard.py          # Desktop bridge server & API proxy
├── utracker_mac.sh               # Mac build, watch, and auto-deploy script
├── UTracker-mobile-debug.apk     # Pre-compiled Android Debug APK
└── README.md                     # Documentation
```

---

## 📜 License
Private & Proprietary. All rights reserved.
