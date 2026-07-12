#!/usr/bin/env bash
# ============================================================
# run_simpleperf.sh — Native CPU Profiling via Simpleperf
#
# Profiles native C++ code (VisionCore, OCRCore, FrameProcessor)
# with symbol-level precision.
#
# Output: perf_report_<timestamp>.html (flamegraph)
#
# Usage:
#   ./scripts/profiling/run_simpleperf.sh [duration_seconds]
# ============================================================
set -euo pipefail

PACKAGE="com.visionagent.app"
DURATION="${1:-30}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
REPORT_DIR="simpleperf_${TIMESTAMP}"
mkdir -p "$REPORT_DIR"

NDK="${ANDROID_NDK_ROOT:-$ANDROID_HOME/ndk/26.1.10909125}"
SIMPLEPERF="$NDK/simpleperf/bin/linux/x86_64/simpleperf"

echo "=== Simpleperf Native Profiling: ${DURATION}s ==="
echo "Package: $PACKAGE"

# Push simpleperf to device
adb push "$NDK/simpleperf/bin/android/arm64/simpleperf" /data/local/tmp/simpleperf
adb shell chmod +x /data/local/tmp/simpleperf

# Start app if needed
adb shell am start -n "$PACKAGE/.presentation.MainActivity" --ez "perf_mode" true
sleep 3

# Get app PID
APP_PID=$(adb shell pidof "$PACKAGE" | tr -d ' ')
echo "App PID: $APP_PID"

# Record CPU profile
echo "Recording for ${DURATION}s..."
adb shell "/data/local/tmp/simpleperf record \
  -p $APP_PID \
  -e task-clock:u \
  --duration $DURATION \
  -f 1000 \
  --call-graph dwarf \
  -o /data/local/tmp/perf.data \
  2>&1" &

sleep "$((DURATION + 3))"

# Pull data
adb pull /data/local/tmp/perf.data "$REPORT_DIR/"

# Generate flamegraph HTML
"$NDK/simpleperf/scripts/report_html.py" \
  --record_file "$REPORT_DIR/perf.data" \
  --symdir android/app/build/intermediates/cmake/debug/obj \
  -o "$REPORT_DIR/flamegraph.html" 2>/dev/null || true

# Generate top functions report
"$NDK/simpleperf/scripts/report.py" \
  --record_file "$REPORT_DIR/perf.data" \
  --sort pid,tid,comm,dso,symbol \
  -n 50 > "$REPORT_DIR/top_functions.txt" 2>/dev/null || true

echo ""
echo "=== Simpleperf Complete ==="
echo "Flamegraph: $REPORT_DIR/flamegraph.html"
echo "Top Functions: $REPORT_DIR/top_functions.txt"
ls -lh "$REPORT_DIR/"
