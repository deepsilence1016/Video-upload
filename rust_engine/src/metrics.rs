/*!
 * metrics.rs — Prometheus-compatible Metrics for Rust Engine
 *
 * FIX RUST-2: This file was declared in lib.rs (`pub mod metrics;`)
 * but did not exist → Rust compiler error:
 * "failed to resolve mod `metrics`" / "metrics.rs does not exist"
 *
 * Provides:
 * - Counter: monotonically increasing u64 (frames, actions, errors)
 * - Gauge:   current value i64 (queue depth, active connections)
 * - Histogram: value distribution (latency buckets)
 * - Registry: central collection point for all metrics
 *
 * Design:
 * - No external crate dependency (prometheus crate not available in JNI context)
 * - All atomic — lock-free reads on hot path
 * - Exportable as JSON for the Remote Dashboard to consume
 */

use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicI64, AtomicU64, Ordering},
        Arc, Mutex,
    },
};

// ─────────────────────────────────────────────────────────────────────────────
// Counter — monotonically increasing (frames captured, actions executed)
// ─────────────────────────────────────────────────────────────────────────────

pub struct Counter {
    pub name: String,
    pub help: String,
    value: AtomicU64,
}

impl Counter {
    pub fn new(name: impl Into<String>, help: impl Into<String>) -> Arc<Self> {
        Arc::new(Self {
            name: name.into(),
            help: help.into(),
            value: AtomicU64::new(0),
        })
    }

    /// Increment by 1 — O(1), lock-free.
    #[inline]
    pub fn inc(&self) {
        self.value.fetch_add(1, Ordering::Relaxed);
    }

    /// Increment by n.
    #[inline]
    pub fn inc_by(&self, n: u64) {
        self.value.fetch_add(n, Ordering::Relaxed);
    }

    /// Read current value.
    #[inline]
    pub fn get(&self) -> u64 {
        self.value.load(Ordering::Relaxed)
    }

    /// Reset to zero (use sparingly — counters are typically never reset).
    pub fn reset(&self) {
        self.value.store(0, Ordering::Release);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Gauge — current value, can go up or down (queue depth, RAM usage)
// ─────────────────────────────────────────────────────────────────────────────

pub struct Gauge {
    pub name: String,
    pub help: String,
    value: AtomicI64,
}

impl Gauge {
    pub fn new(name: impl Into<String>, help: impl Into<String>) -> Arc<Self> {
        Arc::new(Self {
            name: name.into(),
            help: help.into(),
            value: AtomicI64::new(0),
        })
    }

    #[inline]
    pub fn set(&self, v: i64) {
        self.value.store(v, Ordering::Release);
    }
    #[inline]
    pub fn inc(&self) {
        self.value.fetch_add(1, Ordering::Relaxed);
    }
    #[inline]
    pub fn dec(&self) {
        self.value.fetch_sub(1, Ordering::Relaxed);
    }
    #[inline]
    pub fn inc_by(&self, n: i64) {
        self.value.fetch_add(n, Ordering::Relaxed);
    }
    #[inline]
    pub fn dec_by(&self, n: i64) {
        self.value.fetch_sub(n, Ordering::Relaxed);
    }
    #[inline]
    pub fn get(&self) -> i64 {
        self.value.load(Ordering::Relaxed)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Histogram — latency distribution in fixed buckets (ms)
// ─────────────────────────────────────────────────────────────────────────────

/// Default latency buckets in milliseconds:
/// 1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000
pub const DEFAULT_BUCKETS: &[f64] = &[1.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0];

pub struct Histogram {
    pub name: String,
    pub help: String,
    buckets: Vec<f64>,      // upper bounds
    counts: Vec<AtomicU64>, // count per bucket (cumulative)
    sum: Mutex<f64>,        // sum of all observed values
    total_count: AtomicU64,
}

impl Histogram {
    pub fn new(name: impl Into<String>, help: impl Into<String>, buckets: &[f64]) -> Arc<Self> {
        let mut b = buckets.to_vec();
        b.sort_by(|a, c| a.partial_cmp(c).unwrap());
        let counts = b.iter().map(|_| AtomicU64::new(0)).collect();
        Arc::new(Self {
            name: name.into(),
            help: help.into(),
            buckets: b,
            counts,
            sum: Mutex::new(0.0),
            total_count: AtomicU64::new(0),
        })
    }

    /// Record an observation (typically a latency in ms).
    pub fn observe(&self, value: f64) {
        self.total_count.fetch_add(1, Ordering::Relaxed);
        // Increment all bucket counters where upper_bound >= value (cumulative histogram)
        for (i, &ub) in self.buckets.iter().enumerate() {
            if value <= ub {
                self.counts[i].fetch_add(1, Ordering::Relaxed);
            }
        }
        *self.sum.lock().unwrap() += value;
    }

    pub fn sum(&self) -> f64 {
        *self.sum.lock().unwrap()
    }
    pub fn count(&self) -> u64 {
        self.total_count.load(Ordering::Relaxed)
    }
    pub fn mean(&self) -> f64 {
        let c = self.count();
        if c == 0 {
            0.0
        } else {
            self.sum() / c as f64
        }
    }

    /// Approximate percentile using bucket interpolation.
    pub fn percentile(&self, p: f64) -> f64 {
        let total = self.count();
        if total == 0 {
            return 0.0;
        }
        let target = (p / 100.0 * total as f64).ceil() as u64;
        for (i, cnt_atom) in self.counts.iter().enumerate() {
            if cnt_atom.load(Ordering::Relaxed) >= target {
                return self.buckets[i];
            }
        }
        *self.buckets.last().unwrap_or(&0.0)
    }

    pub fn p50(&self) -> f64 {
        self.percentile(50.0)
    }
    pub fn p95(&self) -> f64 {
        self.percentile(95.0)
    }
    pub fn p99(&self) -> f64 {
        self.percentile(99.0)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Snapshot — serialisable export for JSON API
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CounterSnapshot {
    pub name: String,
    pub help: String,
    pub value: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GaugeSnapshot {
    pub name: String,
    pub help: String,
    pub value: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistogramSnapshot {
    pub name: String,
    pub help: String,
    pub count: u64,
    pub sum: f64,
    pub mean: f64,
    pub p50: f64,
    pub p95: f64,
    pub p99: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricsSnapshot {
    pub timestamp_ms: u64,
    pub counters: Vec<CounterSnapshot>,
    pub gauges: Vec<GaugeSnapshot>,
    pub histograms: Vec<HistogramSnapshot>,
}

// ─────────────────────────────────────────────────────────────────────────────
// Registry — central collection point
// ─────────────────────────────────────────────────────────────────────────────

pub struct Registry {
    counters: Mutex<HashMap<String, Arc<Counter>>>,
    gauges: Mutex<HashMap<String, Arc<Gauge>>>,
    histograms: Mutex<HashMap<String, Arc<Histogram>>>,
}

impl Registry {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            counters: Mutex::new(HashMap::new()),
            gauges: Mutex::new(HashMap::new()),
            histograms: Mutex::new(HashMap::new()),
        })
    }

    pub fn register_counter(&self, c: Arc<Counter>) {
        self.counters.lock().unwrap().insert(c.name.clone(), c);
    }
    pub fn register_gauge(&self, g: Arc<Gauge>) {
        self.gauges.lock().unwrap().insert(g.name.clone(), g);
    }
    pub fn register_histogram(&self, h: Arc<Histogram>) {
        self.histograms.lock().unwrap().insert(h.name.clone(), h);
    }

    /// Export all metrics as a JSON-serialisable snapshot.
    pub fn snapshot(&self) -> MetricsSnapshot {
        let ts = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;

        let counters = self
            .counters
            .lock()
            .unwrap()
            .values()
            .map(|c| CounterSnapshot {
                name: c.name.clone(),
                help: c.help.clone(),
                value: c.get(),
            })
            .collect();

        let gauges = self
            .gauges
            .lock()
            .unwrap()
            .values()
            .map(|g| GaugeSnapshot {
                name: g.name.clone(),
                help: g.help.clone(),
                value: g.get(),
            })
            .collect();

        let histograms = self
            .histograms
            .lock()
            .unwrap()
            .values()
            .map(|h| HistogramSnapshot {
                name: h.name.clone(),
                help: h.help.clone(),
                count: h.count(),
                sum: h.sum(),
                mean: h.mean(),
                p50: h.p50(),
                p95: h.p95(),
                p99: h.p99(),
            })
            .collect();

        MetricsSnapshot {
            timestamp_ms: ts,
            counters,
            gauges,
            histograms,
        }
    }
}

impl Default for Registry {
    fn default() -> Self {
        Self {
            counters: Mutex::new(HashMap::new()),
            gauges: Mutex::new(HashMap::new()),
            histograms: Mutex::new(HashMap::new()),
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pre-built Agent Metrics — singletons used by other modules
// ─────────────────────────────────────────────────────────────────────────────

/// Create and register the standard Vision Agent metrics into a registry.
pub fn create_agent_metrics(registry: &Arc<Registry>) -> AgentMetrics {
    let frames_captured = Counter::new(
        "agent_frames_captured_total",
        "Total frames captured by ScreenCaptureEngine",
    );
    let frames_dropped = Counter::new(
        "agent_frames_dropped_total",
        "Frames dropped due to queue full or processing timeout",
    );
    let actions_executed = Counter::new(
        "agent_actions_executed_total",
        "Total actions dispatched by ActionEngine",
    );
    let action_failures = Counter::new(
        "agent_action_failures_total",
        "Actions that returned failure after all retries",
    );
    let rules_evaluated = Counter::new("agent_rules_evaluated_total", "Total rule evaluation cycles");
    let recovery_triggered = Counter::new("agent_recovery_triggered_total", "Times RecoveryEngine was invoked");
    let queue_depth = Gauge::new(
        "agent_frame_queue_depth",
        "Current number of frames in the processing queue",
    );
    let active_sessions = Gauge::new("agent_active_sessions", "Currently active agent sessions (0 or 1)");
    let vision_latency_ms = Histogram::new(
        "agent_vision_latency_ms",
        "VisionEngine frame processing latency in ms",
        DEFAULT_BUCKETS,
    );
    let ocr_latency_ms = Histogram::new(
        "agent_ocr_latency_ms",
        "OCREngine processing latency in ms",
        DEFAULT_BUCKETS,
    );
    let action_latency_ms = Histogram::new(
        "agent_action_latency_ms",
        "End-to-end action execution latency in ms",
        DEFAULT_BUCKETS,
    );

    registry.register_counter(Arc::clone(&frames_captured));
    registry.register_counter(Arc::clone(&frames_dropped));
    registry.register_counter(Arc::clone(&actions_executed));
    registry.register_counter(Arc::clone(&action_failures));
    registry.register_counter(Arc::clone(&rules_evaluated));
    registry.register_counter(Arc::clone(&recovery_triggered));
    registry.register_gauge(Arc::clone(&queue_depth));
    registry.register_gauge(Arc::clone(&active_sessions));
    registry.register_histogram(Arc::clone(&vision_latency_ms));
    registry.register_histogram(Arc::clone(&ocr_latency_ms));
    registry.register_histogram(Arc::clone(&action_latency_ms));

    AgentMetrics {
        frames_captured,
        frames_dropped,
        actions_executed,
        action_failures,
        rules_evaluated,
        recovery_triggered,
        queue_depth,
        active_sessions,
        vision_latency_ms,
        ocr_latency_ms,
        action_latency_ms,
    }
}

/// Typed handles to all standard agent metrics.
pub struct AgentMetrics {
    pub frames_captured: Arc<Counter>,
    pub frames_dropped: Arc<Counter>,
    pub actions_executed: Arc<Counter>,
    pub action_failures: Arc<Counter>,
    pub rules_evaluated: Arc<Counter>,
    pub recovery_triggered: Arc<Counter>,
    pub queue_depth: Arc<Gauge>,
    pub active_sessions: Arc<Gauge>,
    pub vision_latency_ms: Arc<Histogram>,
    pub ocr_latency_ms: Arc<Histogram>,
    pub action_latency_ms: Arc<Histogram>,
}

// ─────────────────────────────────────────────────────────────────────────────
// Unit Tests
// ─────────────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counter_increments_correctly() {
        let c = Counter::new("test_counter", "help");
        assert_eq!(c.get(), 0);
        c.inc();
        c.inc();
        c.inc_by(8);
        assert_eq!(c.get(), 10);
        c.reset();
        assert_eq!(c.get(), 0);
    }

    #[test]
    fn gauge_set_inc_dec() {
        let g = Gauge::new("test_gauge", "help");
        g.set(100);
        g.inc();
        g.dec_by(50);
        assert_eq!(g.get(), 51);
    }

    #[test]
    fn histogram_records_and_percentiles() {
        let h = Histogram::new("test_hist", "help", DEFAULT_BUCKETS);
        for v in [1.0, 5.0, 10.0, 50.0, 100.0, 500.0, 1000.0] {
            h.observe(v);
        }
        assert_eq!(h.count(), 7);
        assert!(h.mean() > 0.0);
        assert!(h.p50() <= h.p95());
        assert!(h.p95() <= h.p99());
    }

    #[test]
    fn registry_snapshot_serialises() {
        let reg = Registry::new();
        let _metrics = create_agent_metrics(&reg);
        let snap = reg.snapshot();
        let json = serde_json::to_string(&snap);
        assert!(json.is_ok(), "snapshot must serialise to JSON");
        assert!(snap.counters.len() >= 6);
        assert!(snap.gauges.len() >= 2);
        assert!(snap.histograms.len() >= 3);
    }
}
