#!/usr/bin/env bash
# ============================================================
# run_perfetto.sh — Automated Perfetto Profiling
#
# Captures CPU, GPU, memory, and scheduling traces
# using Perfetto (Android's system-wide profiler).
#
# Output: perfetto_trace_<timestamp>.perfetto-trace
# View:   https://ui.perfetto.dev (drag and drop the file)
#
# Usage:
#   ./scripts/profiling/run_perfetto.sh [duration_seconds]
#   Default duration: 10 seconds
# ============================================================
set -euo pipefail

PACKAGE="com.visionagent.app"
DURATION="${1:-10}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
OUTPUT="perfetto_trace_${TIMESTAMP}.perfetto-trace"

echo "=== Perfetto Profiling: ${DURATION}s ==="

# Verify device connected
adb get-state > /dev/null || { echo "No device connected"; exit 1; }

# Perfetto config
PERFETTO_CONFIG=$(cat << 'PERFETTO_EOF'
buffers {
  size_kb: 131072
  fill_policy: RING_BUFFER
}

data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_wakeup"
      ftrace_events: "power/suspend_resume"
      ftrace_events: "power/cpu_frequency"
      ftrace_events: "power/cpu_idle"
      ftrace_events: "gpu_mem/gpu_mem_total"
      ftrace_events: "binder/binder_transaction"
      ftrace_events: "binder/binder_transaction_received"
      atrace_categories: "am"
      atrace_categories: "wm"
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "input"
      atrace_categories: "res"
      atrace_categories: "binder_driver"
      atrace_categories: "hal"
      atrace_categories: "camera"
      atrace_apps: "PACKAGE_TO_REPLACE"
      buffer_size_kb: 65536
      drain_period_ms: 500
    }
  }
}

data_sources {
  config {
    name: "linux.process_stats"
    process_stats_config {
      scan_all_processes_on_start: true
      proc_stats_poll_ms: 1000
    }
  }
}

data_sources {
  config {
    name: "android.heapprofd"
    heapprofd_config {
      sampling_interval_bytes: 4096
      process_cmdline: "PACKAGE_TO_REPLACE"
      shmem_size_bytes: 8388608
      block_client: false
    }
  }
}

data_sources {
  config {
    name: "android.power"
    android_power_config {
      battery_poll_ms: 1000
      battery_counters: BATTERY_COUNTER_CAPACITY_PERCENT
      battery_counters: BATTERY_COUNTER_CHARGE
      battery_counters: BATTERY_COUNTER_CURRENT
      collect_power_rails: true
    }
  }
}

data_sources {
  config {
    name: "android.gpu.memory"
  }
}

duration_ms: DURATION_TO_REPLACE
PERFETTO_EOF
)

# Substitute values
PERFETTO_CONFIG="${PERFETTO_CONFIG//PACKAGE_TO_REPLACE/$PACKAGE}"
PERFETTO_CONFIG="${PERFETTO_CONFIG//DURATION_TO_REPLACE/$((DURATION * 1000))}"

# Push config to device
echo "$PERFETTO_CONFIG" | adb shell -t "cat > /data/local/tmp/perfetto_config.pbtxt"

echo "Starting Perfetto trace for ${DURATION}s..."
echo "App package: $PACKAGE"

# Start Perfetto
adb shell "perfetto \
  -c /data/local/tmp/perfetto_config.pbtxt \
  --txt \
  -o /data/local/tmp/perfetto_output.trace \
  &"

PERFETTO_PID=$(adb shell "pgrep perfetto" | head -1)
echo "Perfetto PID: $PERFETTO_PID"

# Optional: trigger agent actions during profiling
if [ -n "${TRIGGER_ACTIONS:-}" ]; then
    echo "Triggering agent actions..."
    sleep 2
    adb shell am start -n "$PACKAGE/.presentation.MainActivity"
    sleep "$((DURATION - 3))"
else
    sleep "$DURATION"
fi

# Wait for trace to complete
sleep 2

# Pull trace
adb pull /data/local/tmp/perfetto_output.trace "$OUTPUT"
adb shell rm -f /data/local/tmp/perfetto_output.trace /data/local/tmp/perfetto_config.pbtxt

echo ""
echo "=== Perfetto Trace Complete ==="
echo "File: $OUTPUT ($(du -sh $OUTPUT | cut -f1))"
echo ""
echo "View at: https://ui.perfetto.dev"
echo "Or use: python3 -m http.server 8080 (then open UI in browser)"
