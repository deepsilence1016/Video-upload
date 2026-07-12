package com.visionagent

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.visionagent.core.memory.ShortTermMemory
import com.visionagent.core.memory.MemoryItem
import com.visionagent.core.event.MemoryType
import com.visionagent.core.screen.FPSController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

// ============================================================
// BenchmarkSuite — Production Performance Benchmarks
//
// Run with: ./gradlew connectedBenchmarkAndroidTest
//
// Targets:
// - STM read:  < 0.1ms
// - STM write: < 0.1ms
// - FPS check: < 0.01ms
// - Frame hash: < 5ms for 1080p
// ============================================================

@RunWith(AndroidJUnit4::class)
class BenchmarkSuite {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // ── Short-Term Memory Benchmarks ──────────────────────────────────────

    @Test
    fun benchmark_stm_write_single() {
        val stm = ShortTermMemory(maxSize = 500)
        benchmarkRule.measureRepeated {
            stm.put(MemoryItem("key_bench", "value_bench", MemoryType.SHORT_TERM, "s1"))
        }
    }

    @Test
    fun benchmark_stm_read_hit() {
        val stm = ShortTermMemory(maxSize = 500)
        stm.put(MemoryItem("key_read", "value_read", MemoryType.SHORT_TERM, "s1"))
        benchmarkRule.measureRepeated {
            stm.get("key_read")
        }
    }

    @Test
    fun benchmark_stm_read_miss() {
        val stm = ShortTermMemory(maxSize = 500)
        benchmarkRule.measureRepeated {
            stm.get("key_that_doesnt_exist_${System.nanoTime()}")
        }
    }

    @Test
    fun benchmark_stm_1000_writes() {
        val stm = ShortTermMemory(maxSize = 1000)
        var counter = 0
        benchmarkRule.measureRepeated {
            repeat(1000) {
                stm.put(MemoryItem("key_$it", "value_$it", MemoryType.SHORT_TERM, "s1"))
            }
        }
    }

    // ── FPS Controller Benchmark ──────────────────────────────────────────

    @Test
    fun benchmark_fps_controller_should_capture() {
        val fps = FPSController(15)
        benchmarkRule.measureRepeated {
            fps.shouldCapture()
        }
    }

    // ── Frame Hash Benchmark ──────────────────────────────────────────────

    @Test
    fun benchmark_frame_hash_small_100x100() {
        val frameData = ByteArray(100 * 100 * 4) { it.toByte() }
        benchmarkRule.measureRepeated {
            // Simulate perceptual hash
            var hash = 0L
            for (i in frameData.indices step 100) {
                hash = hash * 31 + frameData[i].toLong()
            }
            hash
        }
    }

    @Test
    fun benchmark_frame_hash_1080p() {
        val frameData = ByteArray(1080 * 1920 * 4).also { Random.nextBytes(it) }
        benchmarkRule.measureRepeated {
            var hash = 0L
            for (i in frameData.indices step 100) {
                hash = hash * 31 + frameData[i].toLong()
            }
            hash
        }
    }

    // ── Concurrent STM Benchmark ──────────────────────────────────────────

    @Test
    fun benchmark_stm_concurrent_reads() {
        val stm = ShortTermMemory(maxSize = 500)
        repeat(100) {
            stm.put(MemoryItem("key_$it", "val_$it", MemoryType.SHORT_TERM, "s1"))
        }

        benchmarkRule.measureRepeated {
            // Simulate concurrent reads from multiple coroutines
            val keys = (0 until 50).map { "key_${it % 100}" }
            keys.forEach { key -> stm.get(key) }
        }
    }

    // ── Rule Evaluation Simulation ────────────────────────────────────────

    @Test
    fun benchmark_rule_condition_evaluation() {
        val testText = "An error occurred. Please retry your request."
        val keywords = setOf("error", "failed", "retry", "something went wrong")

        benchmarkRule.measureRepeated {
            keywords.any { keyword -> testText.contains(keyword, ignoreCase = true) }
        }
    }

    // ── JSON Serialization ────────────────────────────────────────────────

    @Test
    fun benchmark_config_serialization() {
        val json = kotlinx.serialization.json.Json
        val config = com.visionagent.core.config.AgentMasterConfig()

        benchmarkRule.measureRepeated {
            val encoded = json.encodeToString(
                com.visionagent.core.config.AgentMasterConfig.serializer(), config)
            json.decodeFromString(
                com.visionagent.core.config.AgentMasterConfig.serializer(), encoded)
        }
    }
}
