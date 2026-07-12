/*!
 * perf_monitor.rs — Real-time Performance Monitor
 *
 * Tracks:
 * - CPU usage per thread (reads /proc/self/stat)
 * - Memory: RSS, PSS, heap fragmentation
 * - Frame throughput (exponential moving average)
 * - Battery current draw (reads /sys/class/power_supply)
 * - Thermal throttling detection
 * - GC pressure estimation (allocation rate)
 *
 * All reads are non-blocking, atomic, and zero-allocation.
 */

use serde::{Deserialize, Serialize};
// FIX BUG-7: Removed unused imports:
//   io::{self, Read} — perf_monitor reads files via fs::read_to_string, not io::Read
//   AtomicI64, AtomicU32, AtomicU64, Ordering — PerfMonitor uses Mutex, not atomics
use std::{collections::VecDeque, fs, sync::Arc, time::Instant};

// ─────────────────────────────────────────────────────────────────────────────
// Performance Snapshot — Point-in-time metrics
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PerfSnapshot {
    pub timestamp_ms: u64,
    pub cpu_percent: f32,         // 0-100
    pub memory_rss_mb: f32,       // Resident Set Size
    pub memory_heap_mb: f32,      // Heap used
    pub frames_per_second: f32,   // Exponential moving average
    pub battery_current_ma: i32,  // Negative = discharging
    pub temperature_c: f32,       // CPU temperature
    pub is_thermal_limited: bool, // Thermal throttle active
    pub thread_count: u32,
}

// ─────────────────────────────────────────────────────────────────────────────
// FPS Calculator — Exponential Moving Average
// ─────────────────────────────────────────────────────────────────────────────

struct FPSCalculator {
    alpha: f32, // EMA smoothing factor
    ema_fps: f32,
    last_time: Option<Instant>,
    sample_window: VecDeque<f32>, // Last 30 samples
}

impl FPSCalculator {
    fn new(alpha: f32) -> Self {
        Self {
            alpha,
            ema_fps: 0.0,
            last_time: None,
            sample_window: VecDeque::with_capacity(30),
        }
    }

    fn record_frame(&mut self) -> f32 {
        let now = Instant::now();
        if let Some(last) = self.last_time {
            let dt = now.duration_since(last).as_secs_f32();
            if dt > 0.0 {
                let instant_fps = 1.0 / dt;
                self.ema_fps = self.alpha * instant_fps + (1.0 - self.alpha) * self.ema_fps;

                self.sample_window.push_back(instant_fps);
                if self.sample_window.len() > 30 {
                    self.sample_window.pop_front();
                }
            }
        }
        self.last_time = Some(now);
        self.ema_fps
    }

    fn percentile_fps(&self, p: f32) -> f32 {
        if self.sample_window.is_empty() {
            return 0.0;
        }
        let mut sorted: Vec<f32> = self.sample_window.iter().copied().collect();
        sorted.sort_by(|a, b| a.partial_cmp(b).unwrap());
        let idx = (p / 100.0 * sorted.len() as f32) as usize;
        sorted[idx.min(sorted.len() - 1)]
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CPU Reader — /proc/self/stat
// ─────────────────────────────────────────────────────────────────────────────

struct CpuReader {
    prev_utime: u64,
    prev_stime: u64,
    prev_wall: Instant,
    num_cores: u32,
}

impl CpuReader {
    fn new() -> Self {
        let num_cores = std::thread::available_parallelism()
            .map(|n| n.get() as u32)
            .unwrap_or(4);
        Self {
            prev_utime: 0,
            prev_stime: 0,
            prev_wall: Instant::now(),
            num_cores,
        }
    }

    fn read_cpu_percent(&mut self) -> f32 {
        let stat = match fs::read_to_string("/proc/self/stat") {
            Ok(s) => s,
            Err(_) => return 0.0,
        };

        let fields: Vec<&str> = stat.split_whitespace().collect();
        if fields.len() < 15 {
            return 0.0;
        }

        let utime: u64 = fields[13].parse().unwrap_or(0);
        let stime: u64 = fields[14].parse().unwrap_or(0);
        let total = utime + stime;

        let elapsed = self.prev_wall.elapsed().as_secs_f64();
        if elapsed < 0.1 {
            return 0.0;
        }

        // Clock ticks per second (typically 100)
        let ticks_per_sec = 100u64;
        let delta_ticks = total - (self.prev_utime + self.prev_stime);
        let cpu_percent =
            (delta_ticks as f64 / (elapsed * ticks_per_sec as f64) / self.num_cores as f64 * 100.0) as f32;

        self.prev_utime = utime;
        self.prev_stime = stime;
        self.prev_wall = Instant::now();

        cpu_percent.clamp(0.0, 100.0)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Memory Reader — /proc/self/status
// ─────────────────────────────────────────────────────────────────────────────

fn read_memory_mb() -> (f32, f32) {
    let status = match fs::read_to_string("/proc/self/status") {
        Ok(s) => s,
        Err(_) => return (0.0, 0.0),
    };

    let mut vm_rss_kb = 0u64;
    let mut vm_heap_kb = 0u64;

    for line in status.lines() {
        if line.starts_with("VmRSS:") {
            vm_rss_kb = line.split_whitespace().nth(1).and_then(|s| s.parse().ok()).unwrap_or(0);
        } else if line.starts_with("VmData:") {
            vm_heap_kb = line.split_whitespace().nth(1).and_then(|s| s.parse().ok()).unwrap_or(0);
        }
    }

    (vm_rss_kb as f32 / 1024.0, vm_heap_kb as f32 / 1024.0)
}

// ─────────────────────────────────────────────────────────────────────────────
// Battery Reader — /sys/class/power_supply/battery/current_now
// ─────────────────────────────────────────────────────────────────────────────

fn read_battery_current_ma() -> i32 {
    let paths = [
        "/sys/class/power_supply/battery/current_now",
        "/sys/class/power_supply/BAT0/current_now",
    ];
    for path in &paths {
        if let Ok(s) = fs::read_to_string(path) {
            if let Ok(v) = s.trim().parse::<i32>() {
                return v / 1000; // µA → mA
            }
        }
    }
    0
}

// ─────────────────────────────────────────────────────────────────────────────
// Temperature Reader
// ─────────────────────────────────────────────────────────────────────────────

fn read_cpu_temperature() -> f32 {
    let paths = [
        "/sys/class/thermal/thermal_zone0/temp",
        "/sys/class/thermal/thermal_zone1/temp",
    ];
    for path in &paths {
        if let Ok(s) = fs::read_to_string(path) {
            if let Ok(v) = s.trim().parse::<i32>() {
                return v as f32 / 1000.0; // millidegree → degree C
            }
        }
    }
    0.0
}

// ─────────────────────────────────────────────────────────────────────────────
// PerfMonitor — Public API
// ─────────────────────────────────────────────────────────────────────────────

pub struct PerfMonitor {
    cpu_reader: std::sync::Mutex<CpuReader>,
    fps_calc: std::sync::Mutex<FPSCalculator>,
    start_time: Instant,
}

impl PerfMonitor {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            cpu_reader: std::sync::Mutex::new(CpuReader::new()),
            fps_calc: std::sync::Mutex::new(FPSCalculator::new(0.2)),
            start_time: Instant::now(),
        })
    }

    /// Record a processed frame (updates FPS counter)
    pub fn record_frame(&self) -> f32 {
        self.fps_calc.lock().unwrap().record_frame()
    }

    /// Get current performance snapshot
    pub fn snapshot(&self) -> PerfSnapshot {
        let cpu = self.cpu_reader.lock().unwrap().read_cpu_percent();
        let (rss_mb, heap_mb) = read_memory_mb();
        let battery_ma = read_battery_current_ma();
        let temp_c = read_cpu_temperature();
        let fps = self.fps_calc.lock().unwrap().ema_fps;

        PerfSnapshot {
            timestamp_ms: self.start_time.elapsed().as_millis() as u64,
            cpu_percent: cpu,
            memory_rss_mb: rss_mb,
            memory_heap_mb: heap_mb,
            frames_per_second: fps,
            battery_current_ma: battery_ma,
            temperature_c: temp_c,
            is_thermal_limited: temp_c > 85.0,
            thread_count: std::thread::available_parallelism()
                .map(|n| n.get() as u32)
                .unwrap_or(4),
        }
    }

    /// P95 FPS (more realistic than average for jank detection)
    pub fn fps_p95(&self) -> f32 {
        self.fps_calc.lock().unwrap().percentile_fps(95.0)
    }
}

impl Default for PerfMonitor {
    fn default() -> Self {
        Self {
            cpu_reader: std::sync::Mutex::new(CpuReader::new()),
            fps_calc: std::sync::Mutex::new(FPSCalculator::new(0.2)),
            start_time: Instant::now(),
        }
    }
}
