/*!
 * jni_exports.rs — JNI Entry Points for Rust Engine
 *
 * These functions are called from Kotlin via System.loadLibrary("vision_agent_core")
 *
 * FIX LIB-1: This is the ONLY module that uses unsafe. All unsafe is confined here
 * at the FFI boundary. Safe Rust modules (frame_pipeline, perf_monitor, etc.) have
 * no unsafe code — enforced by their own module-level #![deny(unsafe_code)] or by
 * the absence of any unsafe blocks.
 *
 * All JNI functions use `unsafe` because:
 * - JNIEnv raw pointer operations require unsafe
 * - #[no_mangle] with extern "system" is unsafe context
 * - Direct JVM memory access (get_array_elements) is unsafe
 */

// FIX BUG-2: Removed #![allow(unsafe_code)] — was conflicting with crate-level
// #![forbid(unsafe_code)] in lib.rs. Now lib.rs does NOT forbid unsafe, so unsafe
// blocks here compile without conflict.
// FIX BUG-3: #[no_mangle] lint suppressed — required for JNI symbol export.
// Without no_mangle the Kotlin System.loadLibrary() cannot find JNI symbols.
#![allow(non_snake_case)]
#![allow(unsafe_code)]
#![allow(clippy::missing_safety_doc)]

use crate::{
    frame_pipeline::{FramePipeline, FramePriority},
    metrics::{create_agent_metrics, Registry},
    perf_monitor::PerfMonitor,
    rule_cache::RuleCache,
    session_tracker::SessionTracker,
    state_manager::{AgentState, StateManager},
};
use jni::{
    objects::{JByteArray, JClass, JObject, JString},
    // FIX BUG-7: Removed unused imports: jbyte
    sys::{jboolean, jfloat, jint, jlong, JNI_FALSE, JNI_TRUE},
    JNIEnv,
};
use std::sync::OnceLock;

// ── Global singletons (initialized once) ──────────────────────────────────────
static PIPELINE: OnceLock<std::sync::Arc<FramePipeline>> = OnceLock::new();
static PERF_MON: OnceLock<std::sync::Arc<PerfMonitor>> = OnceLock::new();
static STATE_MGR: OnceLock<std::sync::Arc<StateManager>> = OnceLock::new();
static RULE_CACHE: OnceLock<std::sync::Arc<RuleCache>> = OnceLock::new();
static SESSION_TRACKER: OnceLock<std::sync::Arc<SessionTracker>> = OnceLock::new();
static METRICS_REGISTRY: OnceLock<std::sync::Arc<Registry>> = OnceLock::new();

// ─────────────────────────────────────────────────────────────────────────────
// Initialization
// ─────────────────────────────────────────────────────────────────────────────

#[no_mangle]
pub unsafe extern "system" fn Java_com_visionagent_core_RustEngine_initialize(
    _env: JNIEnv,
    _class: JClass,
    high_cap: jint,
    normal_cap: jint,
    low_cap: jint,
) -> jboolean {
    // FIX BUG-4: with_min_level() does not exist — correct method is with_max_level().
    // with_max_level(Debug) means: log everything at Debug level and above.
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("RustEngine"),
    );

    PIPELINE.get_or_init(|| FramePipeline::new(high_cap as usize, normal_cap as usize, low_cap as usize));
    PERF_MON.get_or_init(PerfMonitor::new);
    STATE_MGR.get_or_init(StateManager::new);
    RULE_CACHE.get_or_init(|| RuleCache::new(30, 200));
    SESSION_TRACKER.get_or_init(SessionTracker::new);
    METRICS_REGISTRY.get_or_init(|| {
        let reg = Registry::new();
        create_agent_metrics(&reg);
        reg
    });

    log::info!("RustEngine initialized");
    JNI_TRUE
}

// ─────────────────────────────────────────────────────────────────────────────
// Frame Pipeline
// ─────────────────────────────────────────────────────────────────────────────

#[no_mangle]
pub unsafe extern "system" fn Java_com_visionagent_core_RustEngine_submitFrame(
    // FIX BUG-5: env must be mut — JNIEnv methods like get_array_length and
    // get_array_elements take &mut self in jni 0.21+.
    mut env: JNIEnv,
    _class: JClass,
    frame_data: JByteArray,
    width: jint,
    height: jint,
    priority: jint,
) -> jlong {
    let pipeline = match PIPELINE.get() {
        Some(p) => p,
        None => return -1,
    };

    // FIX BUG-7: unsafe blocks removed from inner positions — the entire fn is
    // already `unsafe extern "system"`, so inner unsafe blocks are redundant and
    // trigger the `unsafe_op_in_unsafe_fn` lint on some Rust versions.
    let len = env.get_array_length(&frame_data).unwrap_or(0) as usize;

    let elements = env.get_array_elements(&frame_data, jni::objects::ReleaseMode::NoCopyBack);
    let elements = match elements {
        Ok(e) => e,
        Err(_) => return -2,
    };

    let data: std::sync::Arc<[u8]> = std::slice::from_raw_parts(elements.as_ptr() as *const u8, len).into();

    let prio = match priority {
        0 => FramePriority::Low,
        2 => FramePriority::High,
        _ => FramePriority::Normal,
    };

    match pipeline.submit(data, width as u32, height as u32, prio) {
        Ok(id) => id as jlong,
        Err(_) => -3,
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_visionagent_core_RustEngine_getPipelineDepth(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    PIPELINE.get().map(|p| p.pending_count() as jint).unwrap_or(0)
}

// ─────────────────────────────────────────────────────────────────────────────
// Performance Monitor
// ─────────────────────────────────────────────────────────────────────────────

#[no_mangle]
pub unsafe extern "system" fn Java_com_visionagent_core_RustEngine_recordFrame(_env: JNIEnv, _class: JClass) -> jfloat {
    PERF_MON.get().map(|m| m.record_frame()).unwrap_or(0.0)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_visionagent_core_RustEngine_getPerfSnapshot<'a>(
    // FIX BUG-1: JObject<'_> return requires explicit lifetime parameter.
    // The returned JObject borrows from `env`, so we name the lifetime 'a and
    // annotate both env: JNIEnv<'a> and the return type JObject<'a>.
    // Note: env does not need mut here — new_string takes &self in jni 0.21
    env: JNIEnv<'a>,
    _class: JClass<'a>,
) -> JObject<'a> {
    let snap = match PERF_MON.get() {
        Some(m) => m.snapshot(),
        None => return JObject::null(),
    };

    let json = serde_json::to_string(&snap).unwrap_or_default();
    // FIX BUG-6: new_string returns JString<'a>; into() converts to JObject<'a>.
    env.new_string(json)
        .map(JObject::from)
        .unwrap_or_else(|_| JObject::null())
}

// ─────────────────────────────────────────────────────────────────────────────
// State Manager
// ─────────────────────────────────────────────────────────────────────────────

#[no_mangle]
pub unsafe extern "system" fn Java_com_visionagent_core_RustEngine_transitionState(
    // FIX BUG-5: mut env — get_string() requires &mut self in jni 0.21+.
    mut env: JNIEnv,
    _class: JClass,
    target_state: jint,
    trigger: JString,
    session_id: JString,
) -> jboolean {
    let mgr = match STATE_MGR.get() {
        Some(m) => m,
        None => return JNI_FALSE,
    };

    let trigger_str: String = env.get_string(&trigger).map(|s| s.into()).unwrap_or_default();
    let session_str: String = env.get_string(&session_id).map(|s| s.into()).unwrap_or_default();

    let target = match target_state {
        0 => AgentState::Idle,
        1 => AgentState::Capturing,
        2 => AgentState::Analyzing,
        3 => AgentState::Planning,
        4 => AgentState::Executing,
        5 => AgentState::Waiting,
        6 => AgentState::Recovering,
        7 => AgentState::Paused,
        8 => AgentState::Error,
        9 => AgentState::Terminated,
        _ => return JNI_FALSE,
    };

    match mgr.transition(target, &trigger_str, &session_str) {
        Ok(_) => JNI_TRUE,
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_visionagent_core_RustEngine_getCurrentState(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    STATE_MGR.get().map(|m| m.current() as jint).unwrap_or(8) // Error state if not initialized
}

// ─────────────────────────────────────────────────────────────────────────────
// Rule Cache
// ─────────────────────────────────────────────────────────────────────────────

#[no_mangle]
pub unsafe extern "system" fn Java_com_visionagent_core_RustEngine_getRuleCacheHitRatio(
    _env: JNIEnv,
    _class: JClass,
) -> jfloat {
    RULE_CACHE.get().map(|c| c.stats().hit_ratio).unwrap_or(0.0)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_visionagent_core_RustEngine_invalidateRuleCache(_env: JNIEnv, _class: JClass) {
    if let Some(cache) = RULE_CACHE.get() {
        cache.invalidate_all();
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Cleanup
// ─────────────────────────────────────────────────────────────────────────────

#[no_mangle]
pub unsafe extern "system" fn Java_com_visionagent_core_RustEngine_stop(_env: JNIEnv, _class: JClass) {
    if let Some(p) = PIPELINE.get() {
        p.stop();
    }
    if let Some(m) = STATE_MGR.get() {
        m.reset_to_idle();
    }
    log::info!("RustEngine stopped");
}
