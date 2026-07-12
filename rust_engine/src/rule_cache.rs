/*!
 * rule_cache.rs — Concurrent Rule Matching Cache
 *
 * Problem: Rule evaluation is called on every frame (~15/sec).
 * The same screen state might repeat — cache the result.
 *
 * Design:
 * - DashMap (concurrent HashMap) — shard-based, low contention
 * - Cache key = screen_type + element_count + text_hash
 * - TTL-based eviction (30 second default)
 * - Hit ratio tracking
 * - Lock-free reads (DashMap readers don't block)
 */

use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::{
    collections::hash_map::DefaultHasher,
    hash::{Hash, Hasher},
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
    time::Instant,
};

// ─────────────────────────────────────────────────────────────────────────────
// Rule Entry
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RuleEntry {
    pub rule_id: String,
    pub action_type: u8,
    pub priority: i32,
    pub confidence: f32,
    pub reasoning: String,
}

// ─────────────────────────────────────────────────────────────────────────────
// Cache Entry (internal)
// ─────────────────────────────────────────────────────────────────────────────

struct CacheEntry {
    rules: Vec<RuleEntry>,
    cached_at: Instant,
    hit_count: u32,
}

// ─────────────────────────────────────────────────────────────────────────────
// Cache Key — hash of screen state
// ─────────────────────────────────────────────────────────────────────────────

fn compute_cache_key(screen_type: u8, element_count: u32, ocr_text_hash: u64, retry_count: u32) -> u64 {
    let mut h = DefaultHasher::new();
    screen_type.hash(&mut h);
    element_count.hash(&mut h);
    ocr_text_hash.hash(&mut h);
    retry_count.hash(&mut h);
    h.finish()
}

pub fn hash_text(text: &str) -> u64 {
    let mut h = DefaultHasher::new();
    text.hash(&mut h);
    h.finish()
}

// ─────────────────────────────────────────────────────────────────────────────
// RuleCache
// ─────────────────────────────────────────────────────────────────────────────

pub struct RuleCache {
    map: DashMap<u64, CacheEntry>,
    ttl_secs: u64,
    max_size: usize,
    hits: AtomicU64,
    misses: AtomicU64,
    evictions: AtomicU64,
}

impl RuleCache {
    pub fn new(ttl_secs: u64, max_size: usize) -> Arc<Self> {
        Arc::new(Self {
            map: DashMap::with_capacity(max_size),
            ttl_secs,
            max_size,
            hits: AtomicU64::new(0),
            misses: AtomicU64::new(0),
            evictions: AtomicU64::new(0),
        })
    }

    pub fn get(&self, screen_type: u8, element_count: u32, ocr_text: &str, retry_count: u32) -> Option<Vec<RuleEntry>> {
        let key = compute_cache_key(screen_type, element_count, hash_text(ocr_text), retry_count);

        if let Some(mut entry) = self.map.get_mut(&key) {
            let age = entry.cached_at.elapsed().as_secs();
            if age <= self.ttl_secs {
                entry.hit_count += 1;
                self.hits.fetch_add(1, Ordering::Relaxed);
                return Some(entry.rules.clone());
            } else {
                // Expired
                drop(entry);
                self.map.remove(&key);
                self.evictions.fetch_add(1, Ordering::Relaxed);
            }
        }

        self.misses.fetch_add(1, Ordering::Relaxed);
        None
    }

    pub fn put(&self, screen_type: u8, element_count: u32, ocr_text: &str, retry_count: u32, rules: Vec<RuleEntry>) {
        // Evict if at capacity
        if self.map.len() >= self.max_size {
            self.evict_lru();
        }

        let key = compute_cache_key(screen_type, element_count, hash_text(ocr_text), retry_count);

        self.map.insert(
            key,
            CacheEntry {
                rules,
                cached_at: Instant::now(),
                hit_count: 0,
            },
        );
    }

    /// Evict least-recently-used entries (those with oldest cached_at)
    fn evict_lru(&self) {
        let cutoff = self.ttl_secs / 2;
        let mut evicted = 0;

        self.map.retain(|_, v| {
            let keep = v.cached_at.elapsed().as_secs() < cutoff;
            if !keep {
                evicted += 1;
            }
            keep
        });

        self.evictions.fetch_add(evicted, Ordering::Relaxed);
    }

    pub fn invalidate_all(&self) {
        self.map.clear();
    }

    pub fn stats(&self) -> CacheStats {
        let hits = self.hits.load(Ordering::Relaxed);
        let misses = self.misses.load(Ordering::Relaxed);
        let total = hits + misses;

        CacheStats {
            hits,
            misses,
            evictions: self.evictions.load(Ordering::Relaxed),
            size: self.map.len(),
            hit_ratio: if total > 0 { hits as f32 / total as f32 } else { 0.0 },
        }
    }
}

#[derive(Debug, Serialize, Deserialize)]
pub struct CacheStats {
    pub hits: u64,
    pub misses: u64,
    pub evictions: u64,
    pub size: usize,
    pub hit_ratio: f32,
}
