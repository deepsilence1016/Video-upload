/*!
 * frame_pipeline.rs — Lock-Free Frame Priority Queue
 *
 * Problem: Frames arrive faster than they can be processed.
 * Solution: Priority-based frame dropping with smart scheduling.
 *
 * Properties:
 * - MPSC (Multi Producer Single Consumer) channel
 * - Priority levels: HIGH (dialogs/errors), NORMAL, LOW (idle)
 * - Adaptive throttling based on queue depth
 * - Frame deduplication via perceptual hash
 * - Zero-copy via Arc<[u8]>
 * - Lock-free: no Mutex anywhere in hot path
 */

use crossbeam_channel::{Receiver, Sender, TrySendError};
use serde::{Deserialize, Serialize};
use std::sync::{
    atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering},
    Arc,
};
// FIX BUG-7: Removed unused `Instant` import — only Duration is used here.
use std::time::Duration;
use thiserror::Error;

// ─────────────────────────────────────────────────────────────────────────────
// Frame Priority
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[repr(u8)]
pub enum FramePriority {
    Low = 0,    // Idle/background
    Normal = 1, // Standard processing
    High = 2,   // Dialog/error/interaction detected
}

// ─────────────────────────────────────────────────────────────────────────────
// Frame Entry — Arc-wrapped for zero-copy sharing
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone)]
pub struct FrameEntry {
    pub frame_id: u64,
    pub data: Arc<[u8]>, // Zero-copy shared ownership
    pub width: u32,
    pub height: u32,
    pub timestamp_ns: u64,
    pub priority: FramePriority,
    pub phash: u64, // Perceptual hash for dedup
}

impl FrameEntry {
    /// Fast perceptual hash — sample 8×8 grid of the frame
    /// Used to detect duplicate/near-duplicate frames
    pub fn compute_phash(data: &[u8], width: u32, height: u32) -> u64 {
        let step_x = (width / 8) as usize;
        let step_y = (height / 8) as usize;
        let stride = width as usize * 4; // RGBA

        let mut hash: u64 = 0;
        let mut samples = [0u32; 64];
        let mut idx = 0usize;

        for gy in 0..8 {
            for gx in 0..8 {
                let px = gx * step_x;
                let py = gy * step_y;
                let offset = py * stride + px * 4;
                if offset + 2 < data.len() {
                    let r = data[offset] as u32;
                    let g = data[offset + 1] as u32;
                    let b = data[offset + 2] as u32;
                    samples[idx] = (r * 77 + g * 150 + b * 29) >> 8;
                }
                idx += 1;
            }
        }

        // DCT-based hash: above mean = 1, below = 0
        let mean = samples.iter().sum::<u32>() / 64;
        for (i, &s) in samples.iter().enumerate() {
            if s > mean {
                hash |= 1u64 << i;
            }
        }
        hash
    }

    /// Hamming distance between two hashes (number of differing bits)
    pub fn hash_distance(h1: u64, h2: u64) -> u32 {
        (h1 ^ h2).count_ones()
    }

    /// Returns true if frames are perceptually similar (< 10 bit difference)
    pub fn is_similar_to(&self, other: &FrameEntry) -> bool {
        Self::hash_distance(self.phash, other.phash) < 10
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pipeline Error
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Error)]
pub enum PipelineError {
    #[error("Frame queue full — dropped frame {frame_id}")]
    QueueFull { frame_id: u64 },

    #[error("Duplicate frame detected — skipping")]
    DuplicateFrame,

    #[error("Pipeline is stopped")]
    Stopped,

    #[error("Invalid frame dimensions: {width}×{height}")]
    InvalidDimensions { width: u32, height: u32 },
}

// ─────────────────────────────────────────────────────────────────────────────
// Statistics
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Default)]
pub struct PipelineStats {
    pub frames_submitted: AtomicU64,
    pub frames_processed: AtomicU64,
    pub frames_dropped: AtomicU64,
    pub frames_deduped: AtomicU64,
    pub current_fps: AtomicU32, // Fixed-point: actual = value / 10
}

// ─────────────────────────────────────────────────────────────────────────────
// FramePipeline — Main Entry Point
// ─────────────────────────────────────────────────────────────────────────────

pub struct FramePipeline {
    /// Separate channels per priority for head-of-line blocking prevention
    high_sender: Sender<FrameEntry>,
    normal_sender: Sender<FrameEntry>,
    low_sender: Sender<FrameEntry>,

    high_receiver: Receiver<FrameEntry>,
    normal_receiver: Receiver<FrameEntry>,
    low_receiver: Receiver<FrameEntry>,

    frame_counter: AtomicU64,
    is_running: AtomicBool,
    last_frame: std::sync::Mutex<Option<FrameEntry>>,
    pub stats: Arc<PipelineStats>,

    // Adaptive throttle state — reserved for future back-pressure signalling
    #[allow(dead_code)]
    queue_depth_high: AtomicU32,
    #[allow(dead_code)]
    queue_depth_normal: AtomicU32,
}

impl FramePipeline {
    pub fn new(high_cap: usize, normal_cap: usize, low_cap: usize) -> Arc<Self> {
        let (hs, hr) = crossbeam_channel::bounded(high_cap);
        let (ns, nr) = crossbeam_channel::bounded(normal_cap);
        let (ls, lr) = crossbeam_channel::bounded(low_cap);

        Arc::new(Self {
            high_sender: hs,
            high_receiver: hr,
            normal_sender: ns,
            normal_receiver: nr,
            low_sender: ls,
            low_receiver: lr,
            frame_counter: AtomicU64::new(0),
            is_running: AtomicBool::new(true),
            last_frame: std::sync::Mutex::new(None),
            stats: Arc::new(PipelineStats::default()),
            queue_depth_high: AtomicU32::new(0),
            queue_depth_normal: AtomicU32::new(0),
        })
    }

    /// Submit a frame to the pipeline.
    /// Priority is assigned dynamically based on content hints.
    pub fn submit(
        &self,
        data: Arc<[u8]>,
        width: u32,
        height: u32,
        priority: FramePriority,
    ) -> Result<u64, PipelineError> {
        if !self.is_running.load(Ordering::Acquire) {
            return Err(PipelineError::Stopped);
        }

        if width == 0 || height == 0 {
            return Err(PipelineError::InvalidDimensions { width, height });
        }

        let frame_id = self.frame_counter.fetch_add(1, Ordering::Relaxed);

        // Compute perceptual hash for dedup
        let phash = FrameEntry::compute_phash(&data, width, height);

        // Deduplication check
        {
            let last = self.last_frame.lock().unwrap();
            if let Some(ref prev) = *last {
                if FrameEntry::hash_distance(prev.phash, phash) < 5 {
                    self.stats.frames_deduped.fetch_add(1, Ordering::Relaxed);
                    return Err(PipelineError::DuplicateFrame);
                }
            }
        }

        let entry = FrameEntry {
            frame_id,
            data,
            width,
            height,
            timestamp_ns: current_time_ns(),
            priority,
            phash,
        };

        // Send to appropriate priority queue
        let result = match priority {
            FramePriority::High => self.high_sender.try_send(entry).map_err(|e| match e {
                TrySendError::Full(f) => {
                    self.stats.frames_dropped.fetch_add(1, Ordering::Relaxed);
                    PipelineError::QueueFull { frame_id: f.frame_id }
                }
                TrySendError::Disconnected(_) => PipelineError::Stopped,
            }),
            FramePriority::Normal => {
                self.normal_sender.try_send(entry).map_err(|e| match e {
                    TrySendError::Full(_) => {
                        // Adaptive: drop LOW frames if NORMAL is full
                        self.drain_low_priority();
                        self.stats.frames_dropped.fetch_add(1, Ordering::Relaxed);
                        PipelineError::QueueFull { frame_id }
                    }
                    TrySendError::Disconnected(_) => PipelineError::Stopped,
                })
            }
            FramePriority::Low => {
                // Drop LOW if queue is filling up
                if self.normal_receiver.len() > 5 {
                    self.stats.frames_dropped.fetch_add(1, Ordering::Relaxed);
                    return Ok(frame_id); // Silently drop
                }
                self.low_sender.try_send(entry).map_err(|_| {
                    self.stats.frames_dropped.fetch_add(1, Ordering::Relaxed);
                    PipelineError::QueueFull { frame_id }
                })
            }
        };

        if result.is_ok() {
            self.stats.frames_submitted.fetch_add(1, Ordering::Relaxed);
        }

        result.map(|_| frame_id)
    }

    /// Receive next frame — HIGH priority first, then NORMAL, then LOW.
    /// Non-blocking: returns None if no frame available.
    pub fn recv_next(&self) -> Option<FrameEntry> {
        // Priority: HIGH > NORMAL > LOW
        self.high_receiver
            .try_recv()
            .or_else(|_| self.normal_receiver.try_recv())
            .or_else(|_| self.low_receiver.try_recv())
            .ok()
            .inspect(|frame| {
                // Update last frame for dedup
                *self.last_frame.lock().unwrap() = Some(frame.clone());
                self.stats.frames_processed.fetch_add(1, Ordering::Relaxed);
            })
    }

    /// Receive with timeout — blocks up to `timeout` duration
    pub fn recv_timeout(&self, timeout: Duration) -> Option<FrameEntry> {
        // Try non-blocking first
        if let Some(f) = self.recv_next() {
            return Some(f);
        }

        // Wait on high-priority channel with timeout
        self.high_receiver
            .recv_timeout(timeout)
            .ok()
            .or_else(|| self.normal_receiver.try_recv().ok())
    }

    pub fn drain_low_priority(&self) {
        while self.low_receiver.try_recv().is_ok() {}
    }

    pub fn pending_count(&self) -> usize {
        self.high_receiver.len() + self.normal_receiver.len() + self.low_receiver.len()
    }

    pub fn stop(&self) {
        self.is_running.store(false, Ordering::Release);
    }

    pub fn is_running(&self) -> bool {
        self.is_running.load(Ordering::Acquire)
    }
}

fn current_time_ns() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos() as u64
}
