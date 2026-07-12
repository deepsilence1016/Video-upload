#!/usr/bin/env bash
# ============================================================
# run_asan_build.sh — Build + Run with AddressSanitizer
#
# Detects:
#  - Buffer overflow (heap + stack)
#  - Use-after-free
#  - Use-after-scope
#  - Memory leak (with detect_leaks=1)
#  - Double-free
#  - Initialization order bugs
#
# Usage:
#   ./scripts/sanitizers/run_asan_build.sh [arm64-v8a|x86_64]
#
# Requirements:
#   - Android NDK 26+
#   - Device with Android 8.0+
#   - Rooted device OR asan_device_setup.sh
# ============================================================
set -euo pipefail

ABI="${1:-arm64-v8a}"
PACKAGE="com.visionagent.app"
NDK="${ANDROID_NDK_ROOT:-$ANDROID_HOME/ndk/26.1.10909125}"

echo "=== ASan Build: $ABI ==="

# Build with ASan flags
cd android
./gradlew app:buildCMakeDebug \
  -Pandroid.ndk.abiFilters="$ABI" \
  -DCMAKE_CXX_FLAGS="-fsanitize=address -fno-omit-frame-pointer" \
  -DCMAKE_SHARED_LINKER_FLAGS="-fsanitize=address" \
  --stacktrace

echo "✅ ASan build complete"

# Setup ASan on device
ASAN_RT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/17.0.2/lib/linux/${ABI}/libclang_rt.asan.so"
if [ -f "$ASAN_RT" ]; then
  adb push "$ASAN_RT" /data/local/tmp/
  echo "✅ ASan runtime pushed to device"
fi

# Set ASan environment variables
adb shell setprop wrap.$PACKAGE "ASAN_OPTIONS=detect_leaks=1:check_initialization_order=true:detect_stack_use_after_return=true:strict_init_order=true:log_path=/data/local/tmp/asan_report"

# Install APK
APK=$(find app/build/outputs/apk/debug -name "*.apk" | head -1)
if [ -n "$APK" ]; then
  adb install -r "$APK"
  echo "✅ APK installed with ASan"
fi

# Launch and monitor
adb shell am start -n "$PACKAGE/.presentation.MainActivity"
echo "Monitoring for ASan reports (30 seconds)..."
sleep 30

# Pull ASan reports
adb shell ls /data/local/tmp/asan_report* 2>/dev/null && \
  adb pull /data/local/tmp/asan_report* /tmp/asan_reports/ 2>/dev/null || \
  echo "No ASan reports generated (no bugs found)"

echo ""
echo "=== ASan Session Complete ==="
echo "Reports: /tmp/asan_reports/ (if any)"
echo "Clean app: adb shell setprop wrap.$PACKAGE ''"
