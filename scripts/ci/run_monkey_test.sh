#!/usr/bin/env bash
# ============================================================
# run_monkey_test.sh — Android Monkey Stress Test
# Sends 10,000 random events to the app and checks for crashes
# ============================================================
set -euo pipefail

PACKAGE="${1:-com.visionagent.app}"
EVENTS="${2:-10000}"
SEED="${3:-42}"

echo "=== Monkey Test: $PACKAGE ($EVENTS events, seed=$SEED) ==="

# Wait for device
adb wait-for-device
adb shell input keyevent 82   # Unlock screen

# Clear existing logs
adb logcat -c

# Run Monkey
adb shell monkey \
    -p "$PACKAGE" \
    -s "$SEED" \
    --throttle 50 \
    --ignore-crashes \
    --ignore-timeouts \
    --ignore-security-exceptions \
    --monitor-native-crashes \
    --kill-process-after-error \
    -v -v \
    "$EVENTS" 2>&1 | tee /tmp/monkey_output.txt

MONKEY_EXIT=${PIPESTATUS[0]}

# Capture logcat
adb logcat -d > /tmp/monkey_logcat.txt

# Analyze results
CRASHES=$(grep -c "CRASH:" /tmp/monkey_output.txt 2>/dev/null || echo "0")
ANRS=$(grep -c "ANR:" /tmp/monkey_output.txt 2>/dev/null || echo "0")
NATIVE_CRASHES=$(grep -c "NATIVE CRASH" /tmp/monkey_logcat.txt 2>/dev/null || echo "0")
COMPLETED=$(grep -c "Events injected:" /tmp/monkey_output.txt 2>/dev/null || echo "0")

echo ""
echo "=== Monkey Test Results ==="
echo "Events sent:      $EVENTS"
echo "Crashes:          $CRASHES"
echo "ANRs:             $ANRS"
echo "Native crashes:   $NATIVE_CRASHES"

if [ "$CRASHES" -gt 0 ] || [ "$ANRS" -gt 0 ] || [ "$NATIVE_CRASHES" -gt 0 ]; then
    echo "FAIL: Monkey test found issues"
    exit 1
fi

echo "PASS: No crashes or ANRs"
