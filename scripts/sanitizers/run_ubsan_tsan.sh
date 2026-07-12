#!/usr/bin/env bash
# ============================================================
# run_ubsan_tsan.sh — UBSan + TSan Build
#
# UBSan detects:
#  - Integer overflow/underflow
#  - Null pointer dereference
#  - Array out-of-bounds
#  - Signed integer overflow
#  - Division by zero
#  - Unaligned memory access
#
# TSan detects:
#  - Data races (concurrent read/write without lock)
#  - Deadlocks (in some cases)
#  NOTE: Cannot use ASan + TSan together
# ============================================================
set -euo pipefail

MODE="${1:-ubsan}"  # ubsan or tsan
ABI="${2:-arm64-v8a}"
PACKAGE="com.visionagent.app"

echo "=== $MODE Build: $ABI ==="

cd android
if [ "$MODE" = "ubsan" ]; then
  ./gradlew app:buildCMakeDebug \
    -Pandroid.ndk.abiFilters="$ABI" \
    -DCMAKE_CXX_FLAGS="-fsanitize=undefined,integer,bounds,null,alignment -fno-sanitize-recover=all" \
    -DCMAKE_SHARED_LINKER_FLAGS="-fsanitize=undefined" \
    --stacktrace
  adb shell setprop wrap.$PACKAGE "UBSAN_OPTIONS=print_stacktrace=1:log_path=/data/local/tmp/ubsan_report"

elif [ "$MODE" = "tsan" ]; then
  ./gradlew app:buildCMakeDebug \
    -Pandroid.ndk.abiFilters="$ABI" \
    -DCMAKE_CXX_FLAGS="-fsanitize=thread" \
    -DCMAKE_SHARED_LINKER_FLAGS="-fsanitize=thread" \
    --stacktrace
  adb shell setprop wrap.$PACKAGE "TSAN_OPTIONS=detect_deadlocks=1:second_deadlock_stack=1:log_path=/data/local/tmp/tsan_report"
fi

APK=$(find app/build/outputs/apk/debug -name "*.apk" | head -1)
[ -n "$APK" ] && adb install -r "$APK" && echo "✅ $MODE APK installed"

adb shell am start -n "$PACKAGE/.presentation.MainActivity"
echo "Running $MODE for 60 seconds..."
sleep 60

# Pull reports
REPORT_DIR="/tmp/${MODE}_reports"
mkdir -p "$REPORT_DIR"
adb pull /data/local/tmp/${MODE}_report* "$REPORT_DIR/" 2>/dev/null || \
  echo "No $MODE reports (no bugs found)"

REPORT_COUNT=$(ls "$REPORT_DIR" 2>/dev/null | wc -l)
echo ""
echo "=== $MODE Results ==="
echo "Reports found: $REPORT_COUNT"
[ "$REPORT_COUNT" -gt 0 ] && cat "$REPORT_DIR"/*

# Cleanup
adb shell setprop wrap.$PACKAGE ""
