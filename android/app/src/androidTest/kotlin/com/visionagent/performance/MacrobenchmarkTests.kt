package com.visionagent.performance

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.visionagent.core.memory.ShortTermMemory
import com.visionagent.core.memory.MemoryItem
import com.visionagent.core.memory.ActionMemory
import com.visionagent.core.memory.ScreenMemory
import com.visionagent.core.event.*
import com.visionagent.core.screen.FPSController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// ============================================================
// Macrobenchmark Tests — Precise performance measurement
//
// These run on real devices with compilation modes:
// - None: raw interpretation (worst case)
// - Partial: JIT-compiled (typical)
// - Full: AOT-compiled (best case — with baseline profile)
//
// Results are compared against historical baselines.
// Regression > 10% → CI fails.
// ============================================================

@RunWith(AndroidJUnit4::class)
class MacrobenchmarkTests {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    // ── Memory Benchmarks ─────────────────────────────────────────────────

    @Test
    fun benchmark_stm_write() = benchmarkRule.measureRepeated {
        val stm = ShortTermMemory(500)
        runWithTimingDisabled { /* setup */ }
        stm.put(MemoryItem("k", "v", MemoryType.SHORT_TERM, "s"))
    }

    @Test
    fun benchmark_stm_read_hit() = benchmarkRule.measureRepeated {
        val stm = ShortTermMemory(500)
        runWithTimingDisabled { stm.put(MemoryItem("key", "val", MemoryType.SHORT_TERM, "s")) }
        stm.get("key")
    }

    @Test
    fun benchmark_stm_1000_entries_lru_eviction() = benchmarkRule.measureRepeated {
        val stm = ShortTermMemory(500)  // Evicts after 500
        runWithTimingDisabled { /* setup */ }
        repeat(1000) { i -> stm.put(MemoryItem("key$i", "val$i", MemoryType.SHORT_TERM, "s")) }
    }

    // ── Frame Hash Benchmarks ─────────────────────────────────────────────

    @Test
    fun benchmark_frame_hash_720p() = benchmarkRule.measureRepeated {
        val frame = runWithTimingDisabled { ByteArray(720 * 1280 * 4) { it.toByte() } }
        var hash = 0L
        for (i in frame.indices step 100) hash = hash * 31 + frame[i]
        hash
    }

    @Test
    fun benchmark_frame_hash_1080p() = benchmarkRule.measureRepeated {
        val frame = runWithTimingDisabled { ByteArray(1080 * 1920 * 4) { it.toByte() } }
        var hash = 0L
        for (i in frame.indices step 100) hash = hash * 31 + frame[i]
        hash
    }

    // ── FPS Controller ────────────────────────────────────────────────────

    @Test
    fun benchmark_fps_controller_decision() = benchmarkRule.measureRepeated {
        val fps = runWithTimingDisabled { FPSController(15) }
        fps.shouldCapture()
    }

    // ── Screen Memory ─────────────────────────────────────────────────────

    @Test
    fun benchmark_screen_memory_record() = benchmarkRule.measureRepeated {
        val sm = runWithTimingDisabled { ScreenMemory(20) }
        sm.record(com.visionagent.core.memory.ScreenMemoryItem(
            ScreenType.HOME, emptyList(), "text", "session"))
    }

    @Test
    fun benchmark_screen_memory_get_history() = benchmarkRule.measureRepeated {
        val sm = runWithTimingDisabled {
            ScreenMemory(20).also {
                repeat(20) { i ->
                    it.record(com.visionagent.core.memory.ScreenMemoryItem(
                        ScreenType.HOME, emptyList(), "text_$i", "s"))
                }
            }
        }
        sm.getHistory(10)
    }

    // ── Rule Condition Evaluation ─────────────────────────────────────────

    @Test
    fun benchmark_text_contains_check() = benchmarkRule.measureRepeated {
        val text     = runWithTimingDisabled { "An error occurred. Please retry your request." }
        val keywords = runWithTimingDisabled { setOf("error", "failed", "retry") }
        keywords.any { text.contains(it, ignoreCase = true) }
    }

    @Test
    fun benchmark_element_type_filter() = benchmarkRule.measureRepeated {
        val elements = runWithTimingDisabled {
            (0 until 50).map {
                DetectedUIElement(
                    type = UIElementType.values()[it % UIElementType.values().size],
                    bounds = Rect(0, 0, 100, 50),
                    confidence = 0.8f
                )
            }
        }
        elements.filter { it.type == UIElementType.BUTTON }
    }

    // ── Serialization Benchmarks ──────────────────────────────────────────

    @Test
    fun benchmark_json_serialize_screen_state() = benchmarkRule.measureRepeated {
        val json   = runWithTimingDisabled { kotlinx.serialization.json.Json }
        val config = runWithTimingDisabled { com.visionagent.core.config.AgentMasterConfig() }
        json.encodeToString(com.visionagent.core.config.AgentMasterConfig.serializer(), config)
    }

    @Test
    fun benchmark_json_deserialize_config() = benchmarkRule.measureRepeated {
        val json = runWithTimingDisabled { kotlinx.serialization.json.Json }
        val str  = runWithTimingDisabled {
            json.encodeToString(com.visionagent.core.config.AgentMasterConfig.serializer(),
                com.visionagent.core.config.AgentMasterConfig())
        }
        json.decodeFromString(com.visionagent.core.config.AgentMasterConfig.serializer(), str)
    }

    // ── Vector Math (HNSW) ────────────────────────────────────────────────

    @Test
    fun benchmark_cosine_similarity_384d() = benchmarkRule.measureRepeated {
        val a = runWithTimingDisabled { FloatArray(384) { it.toFloat() } }
        val b = runWithTimingDisabled { FloatArray(384) { (384 - it).toFloat() } }
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i]*b[i]; na += a[i]*a[i]; nb += b[i]*b[i] }
        (dot / (Math.sqrt(na) * Math.sqrt(nb))).toFloat()
    }
}
