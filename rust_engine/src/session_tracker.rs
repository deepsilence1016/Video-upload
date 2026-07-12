/*!
 * session_tracker.rs — Session Lifecycle Management
 *
 * Tracks:
 * - Session start/end times
 * - Session metadata (agent version, device info)
 * - Active session count
 * - Per-session statistics (frames, actions, errors)
 *
 * FIX RUST-1: This file was declared in lib.rs (`pub mod session_tracker;`)
 * but the source file did not exist → Rust compiler error:
 * "file not found for module `session_tracker`"
 */

use serde::{Deserialize, Serialize};
use std::sync::{
    atomic::{AtomicU32, AtomicU64, Ordering},
    Arc, Mutex,
};
use std::time::{SystemTime, UNIX_EPOCH};

// ─────────────────────────────────────────────────────────────────────────────
// SessionInfo — Metadata for one agent session
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SessionInfo {
    pub session_id: String,
    pub started_at_ms: u64,
    pub ended_at_ms: Option<u64>,
    pub agent_version: String,
    pub frames_total: u64,
    pub actions_total: u64,
    pub errors_total: u32,
    pub is_active: bool,
}

impl SessionInfo {
    pub fn new(session_id: String, agent_version: String) -> Self {
        Self {
            session_id,
            started_at_ms: epoch_ms(),
            ended_at_ms: None,
            agent_version,
            frames_total: 0,
            actions_total: 0,
            errors_total: 0,
            is_active: true,
        }
    }

    pub fn duration_ms(&self) -> u64 {
        let end = self.ended_at_ms.unwrap_or_else(epoch_ms);
        end.saturating_sub(self.started_at_ms)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SessionTracker — Manages all sessions
// ─────────────────────────────────────────────────────────────────────────────

pub struct SessionTracker {
    /// Current active session (None if no session started)
    current: Mutex<Option<SessionInfo>>,
    /// Historical session summaries (last 10)
    history: Mutex<std::collections::VecDeque<SessionInfo>>,
    history_limit: usize,

    // Atomic counters for the active session — no lock needed for hot path
    frame_counter: AtomicU64,
    action_counter: AtomicU64,
    error_counter: AtomicU32,
}

impl SessionTracker {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            current: Mutex::new(None),
            history: Mutex::new(std::collections::VecDeque::with_capacity(10)),
            history_limit: 10,
            frame_counter: AtomicU64::new(0),
            action_counter: AtomicU64::new(0),
            error_counter: AtomicU32::new(0),
        })
    }

    // ── Session Lifecycle ─────────────────────────────────────────────────

    /// Start a new session. If a session is already active, it is closed first.
    pub fn start_session(&self, session_id: String, agent_version: String) {
        // Close existing session if any
        self.end_session_internal();

        // Reset atomic counters for the new session
        self.frame_counter.store(0, Ordering::Release);
        self.action_counter.store(0, Ordering::Release);
        self.error_counter.store(0, Ordering::Release);

        let info = SessionInfo::new(session_id.clone(), agent_version);
        *self.current.lock().unwrap() = Some(info);
    }

    /// End the current session and archive it to history.
    pub fn end_session(&self) -> Option<SessionInfo> {
        self.end_session_internal()
    }

    fn end_session_internal(&self) -> Option<SessionInfo> {
        let mut current_guard = self.current.lock().unwrap();
        let session = current_guard.take()?;

        let mut closed = session.clone();
        closed.ended_at_ms = Some(epoch_ms());
        closed.is_active = false;
        closed.frames_total = self.frame_counter.load(Ordering::Acquire);
        closed.actions_total = self.action_counter.load(Ordering::Acquire);
        closed.errors_total = self.error_counter.load(Ordering::Acquire);

        // Archive in history
        let mut hist = self.history.lock().unwrap();
        if hist.len() >= self.history_limit {
            hist.pop_front();
        }
        hist.push_back(closed.clone());

        Some(closed)
    }

    // ── Hot-path Counters (atomic, no lock) ──────────────────────────────

    pub fn record_frame(&self) {
        self.frame_counter.fetch_add(1, Ordering::Relaxed);
    }
    pub fn record_action(&self) {
        self.action_counter.fetch_add(1, Ordering::Relaxed);
    }
    pub fn record_error(&self) {
        self.error_counter.fetch_add(1, Ordering::Relaxed);
    }

    // ── Queries ───────────────────────────────────────────────────────────

    pub fn current_session_id(&self) -> Option<String> {
        self.current.lock().unwrap().as_ref().map(|s| s.session_id.clone())
    }

    pub fn is_session_active(&self) -> bool {
        self.current.lock().unwrap().is_some()
    }

    pub fn current_stats(&self) -> Option<SessionInfo> {
        let mut info = self.current.lock().unwrap().clone()?;
        info.frames_total = self.frame_counter.load(Ordering::Acquire);
        info.actions_total = self.action_counter.load(Ordering::Acquire);
        info.errors_total = self.error_counter.load(Ordering::Acquire);
        Some(info)
    }

    pub fn session_history(&self) -> Vec<SessionInfo> {
        self.history.lock().unwrap().iter().cloned().collect()
    }

    pub fn total_frames(&self) -> u64 {
        self.frame_counter.load(Ordering::Acquire)
    }
    pub fn total_actions(&self) -> u64 {
        self.action_counter.load(Ordering::Acquire)
    }
    pub fn total_errors(&self) -> u32 {
        self.error_counter.load(Ordering::Acquire)
    }
}

impl Default for SessionTracker {
    fn default() -> Self {
        Self {
            current: Mutex::new(None),
            history: Mutex::new(std::collections::VecDeque::with_capacity(10)),
            history_limit: 10,
            frame_counter: AtomicU64::new(0),
            action_counter: AtomicU64::new(0),
            error_counter: AtomicU32::new(0),
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

fn epoch_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

// ─────────────────────────────────────────────────────────────────────────────
// Unit Tests
// ─────────────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_session_start_and_end() {
        let tracker = SessionTracker::new();
        assert!(!tracker.is_session_active());

        tracker.start_session("sess-001".to_owned(), "1.0.0".to_owned());
        assert!(tracker.is_session_active());
        assert_eq!(tracker.current_session_id(), Some("sess-001".to_owned()));

        tracker.record_frame();
        tracker.record_frame();
        tracker.record_action();

        let closed = tracker.end_session().expect("session should be closed");
        assert_eq!(closed.session_id, "sess-001");
        assert_eq!(closed.frames_total, 2);
        assert_eq!(closed.actions_total, 1);
        assert!(!closed.is_active);
        assert!(!tracker.is_session_active());
    }

    #[test]
    fn test_session_history_bounded() {
        let tracker = SessionTracker::new();
        for i in 0..15 {
            tracker.start_session(format!("sess-{}", i), "1.0.0".to_owned());
            tracker.end_session();
        }
        let history = tracker.session_history();
        assert!(
            history.len() <= 10,
            "history should be capped at 10, got {}",
            history.len()
        );
    }

    #[test]
    fn test_atomic_counters_no_lock() {
        let tracker = SessionTracker::new();
        tracker.start_session("sess-x".to_owned(), "1.0.0".to_owned());
        // These must be callable without deadlock
        tracker.record_frame();
        tracker.record_action();
        tracker.record_error();
        assert_eq!(tracker.total_frames(), 1);
        assert_eq!(tracker.total_actions(), 1);
        assert_eq!(tracker.total_errors(), 1);
    }

    #[test]
    fn test_new_session_resets_counters() {
        let tracker = SessionTracker::new();
        tracker.start_session("sess-a".to_owned(), "1.0.0".to_owned());
        tracker.record_frame();
        tracker.record_frame();
        assert_eq!(tracker.total_frames(), 2);

        // Starting a new session resets counters
        tracker.start_session("sess-b".to_owned(), "1.0.0".to_owned());
        assert_eq!(tracker.total_frames(), 0, "counters should reset on new session");
    }
}
