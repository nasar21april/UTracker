#!/bin/bash
# ============================================================
#  ARTEMIS for Mac — Auto-Build + Auto-Install Watcher
#  Mac equivalent of artemis.bat + artemis_watch.ps1
#  Watches .kt/.xml/.json files → Gradle build → ADB install
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR/UpstoxMarketDataApp"
APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/mobile/debug/app-mobile-debug.apk"
APP_PACKAGE="com.UpstoxTracker.app"
APP_ACTIVITY="com.example.upstoxmarketdataapp.MainActivity"
GRADLE_TASK="assembleMobileDebug"
DEBOUNCE_SECS=3

# Try to find ADB
ADB=""
for candidate in \
    "$HOME/Library/Android/sdk/platform-tools/adb" \
    "/opt/homebrew/bin/adb" \
    "/usr/local/bin/adb" \
    "$(which adb 2>/dev/null)"; do
    if [ -x "$candidate" ]; then
        ADB="$candidate"
        break
    fi
done

if [ -z "$ADB" ]; then
    echo "❌ ADB not found. Make sure Android Studio is installed."
    echo "   Expected at: ~/Library/Android/sdk/platform-tools/adb"
    echo ""
    read -p "Press Enter to exit..."
    exit 1
fi

# ---- Helpers ----
print_header() {
    clear
    echo ""
    echo "  +------------------------------------------+"
    echo "  |         A R T E M I S  v1.0  (Mac)       |"
    echo "  |   Auto-Build + Auto-Install Watcher       |"
    echo "  +------------------------------------------+"
    echo ""
    echo "  App     : Upstox Tracker"
    echo "  Watching: $PROJECT_ROOT"
    echo "  ADB     : $ADB"
    echo "  Debounce: ${DEBOUNCE_SECS}s after last change"
    echo ""
}

check_device() {
    STATE=$("$ADB" get-state 2>/dev/null)
    [ "$STATE" = "device" ]
}

build_apk() {
    echo "  [$(date +%H:%M:%S)] [*] Building $GRADLE_TASK..."
    START=$SECONDS
    "$PROJECT_ROOT/gradlew" -p "$PROJECT_ROOT" "$GRADLE_TASK" --quiet 2>&1
    RESULT=$?
    ELAPSED=$((SECONDS - START))
    if [ $RESULT -eq 0 ]; then
        echo "  [$(date +%H:%M:%S)] [+] Build SUCCESS in ${ELAPSED}s ✅"
        return 0
    else
        echo "  [$(date +%H:%M:%S)] [-] Build FAILED in ${ELAPSED}s ❌"
        return 1
    fi
}

install_apk() {
    echo "  [$(date +%H:%M:%S)] [>] Installing APK on device..."
    RESULT=$("$ADB" install -r "$APK_PATH" 2>&1)
    if echo "$RESULT" | grep -q "Success"; then
        echo "  [$(date +%H:%M:%S)] [OK] Installed successfully ✅"
        return 0
    fi
    if echo "$RESULT" | grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE"; then
        echo "  [$(date +%H:%M:%S)] [WARN] Signature mismatch — uninstalling old version..."
        "$ADB" uninstall "$APP_PACKAGE" > /dev/null 2>&1
        RESULT2=$("$ADB" install "$APK_PATH" 2>&1)
        if echo "$RESULT2" | grep -q "Success"; then
            echo "  [$(date +%H:%M:%S)] [OK] Installed after uninstall ✅"
            return 0
        fi
    fi
    echo "  [$(date +%H:%M:%S)] [-] Install FAILED: $RESULT ❌"
    return 1
}

launch_app() {
    echo "  [$(date +%H:%M:%S)] [RUN] Launching app on device... 🚀"
    "$ADB" shell am start -n "${APP_PACKAGE}/${APP_ACTIVITY}" > /dev/null 2>&1
    echo "  [$(date +%H:%M:%S)] [+] App launched! Ready. ✅"
    echo ""
}

run_pipeline() {
    echo ""
    echo "  ============================================"
    if ! check_device; then
        echo "  [$(date +%H:%M:%S)] [WARN] ⚠️  Phone not found! Plug in your Xiaomi and enable USB Debugging."
        echo ""
        return
    fi
    build_apk && install_apk && launch_app
}

# ---- File Watcher (using fswatch if available, else fallback) ----
watch_with_fswatch() {
    echo "  [$(date +%H:%M:%S)] [WAIT] Watching for .kt/.xml/.json changes... (Ctrl+C to stop)"
    echo ""
    LAST_CHANGE=0
    BUILD_PENDING=0

    fswatch -r -e ".*" -i "\\.kt$" -i "\\.kts$" -i "\\.xml$" -i "\\.json$" "$PROJECT_ROOT/app/src" | while read -r event; do
        LAST_CHANGE=$(date +%s)
        BUILD_PENDING=1
        echo "  [$(date +%H:%M:%S)] [~] Change detected: $(basename "$event")"

        # Debounce
        (
            sleep "$DEBOUNCE_SECS"
            CURRENT_TIME=$(date +%s)
            SINCE=$((CURRENT_TIME - LAST_CHANGE))
            if [ "$BUILD_PENDING" -eq 1 ] && [ $SINCE -ge $DEBOUNCE_SECS ]; then
                BUILD_PENDING=0
                run_pipeline
                echo "  [$(date +%H:%M:%S)] [WAIT] Watching for changes... (Ctrl+C to stop)"
                echo ""
            fi
        ) &
    done
}

watch_with_polling() {
    echo "  [$(date +%H:%M:%S)] [WAIT] Watching for .kt/.xml/.json changes (polling mode)... (Ctrl+C to stop)"
    echo ""
    get_hash() {
        find "$PROJECT_ROOT/app/src" \( -name "*.kt" -o -name "*.kts" -o -name "*.xml" -o -name "*.json" \) -exec stat -f "%m %N" {} \; 2>/dev/null | sort | md5
    }
    LAST_HASH=$(get_hash)
    LAST_CHANGE=0
    BUILD_PENDING=0

    while true; do
        sleep 1
        CURRENT_HASH=$(get_hash)
        if [ "$CURRENT_HASH" != "$LAST_HASH" ]; then
            LAST_HASH=$CURRENT_HASH
            LAST_CHANGE=$(date +%s)
            BUILD_PENDING=1
            echo "  [$(date +%H:%M:%S)] [~] Change detected..."
        fi
        if [ "$BUILD_PENDING" -eq 1 ]; then
            CURRENT_TIME=$(date +%s)
            SINCE=$((CURRENT_TIME - LAST_CHANGE))
            if [ $SINCE -ge $DEBOUNCE_SECS ]; then
                BUILD_PENDING=0
                run_pipeline
                echo "  [$(date +%H:%M:%S)] [WAIT] Watching for changes... (Ctrl+C to stop)"
                echo ""
            fi
        fi
    done
}

# ---- Entry Point ----
print_header

# Check gradlew
if [ ! -f "$PROJECT_ROOT/gradlew" ]; then
    echo "❌ gradlew not found at $PROJECT_ROOT"
    read -p "Press Enter to exit..."
    exit 1
fi
chmod +x "$PROJECT_ROOT/gradlew"

# Run initial build
echo "  [$(date +%H:%M:%S)] Running initial build..."
run_pipeline

# Watch for changes
if command -v fswatch &>/dev/null; then
    watch_with_fswatch
else
    echo "  [INFO] fswatch not found — using polling mode (1s interval)"
    echo "  [TIP]  Install fswatch for better performance: brew install fswatch"
    echo ""
    watch_with_polling
fi
