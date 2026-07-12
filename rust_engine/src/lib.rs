/*!
 * Vision Agent — Rust Core Engine
 *
 * Rust handles what C++ cannot easily guarantee:
 * - Memory safety without GC (borrow checker)
 * - Fearless concurrency (compile-time race condition prevention)
 * - Zero-cost abstractions
 * - Safe FFI boundary to JNI
 *
 * Modules:
 * - frame_pipeline  : Lock-free frame scheduling & priority queue
 * - perf_monitor    : Real-time CPU/memory/battery metrics
 * - state_manager   : Atomic state machine with history
 * - rule_cache      : Fast rule matching cache (DashMap)
 * - session_tracker : Session lifecycle management
 * - metrics         : Prometheus-compatible metrics export
 * - jni_exports     : JNI-exposed functions (unsafe FFI boundary)
 */

// FIX LIB-1: #![forbid(unsafe_code)] conflicts with #[allow(unsafe_code)] on the
// jni_exports module AND with #[no_mangle] lint AND with unsafe blocks needed for
// JNI FFI. JNI requires unsafe by definition (raw pointers, C ABI).
// Policy: safe Rust everywhere EXCEPT jni_exports, which is isolated unsafe FFI.
// Use #![deny(unsafe_code)] on safe modules instead of crate-wide forbid.
#![deny(clippy::all)]
#![warn(clippy::pedantic)]
#![allow(clippy::module_name_repetitions)]

pub mod frame_pipeline;
pub mod metrics;
pub mod perf_monitor;
pub mod rule_cache;
pub mod session_tracker;
pub mod state_manager;

// jni_exports is the ONLY module that may use unsafe (JNI FFI boundary)
pub mod jni_exports;

// Re-export public API
pub use frame_pipeline::{FrameEntry, FramePipeline, FramePriority};
pub use perf_monitor::{PerfMonitor, PerfSnapshot};
pub use rule_cache::{RuleCache, RuleEntry};
pub use session_tracker::{SessionInfo, SessionTracker};
pub use state_manager::{AgentState, StateManager};
