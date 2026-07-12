package com.visionagent.mutation

/**
 * ============================================================
 * Mutation Testing Guide — VisionAgent
 *
 * Tool: PITest (Mutation Testing for JVM)
 * https://pitest.org
 *
 * What is Mutation Testing?
 * ─────────────────────────────────────────────────────────────
 * Code coverage = "tests ran this code"
 * Mutation testing = "tests ACTUALLY VERIFY this code"
 *
 * PITest injects "mutations" (bugs) into code:
 * - Changes `>` to `>=`
 * - Changes `+` to `-`
 * - Removes null checks
 * - Negates boolean conditions
 * - Removes method calls
 *
 * If tests still pass → tests are WEAK (not verifying behavior)
 * If tests fail → mutation KILLED → tests are STRONG
 *
 * Goal: Kill rate > 80%
 *
 * Setup (build.gradle.kts):
 * ─────────────────────────────────────────────────────────────
 * plugins {
 *     id("info.solidsoft.pitest") version "1.15.0"
 * }
 *
 * pitest {
 *     targetClasses.set(listOf(
 *         "com.visionagent.core.rule.*",
 *         "com.visionagent.core.memory.*",
 *         "com.visionagent.core.vision.semantic.*",
 *         "com.visionagent.core.performance.*"
 *     ))
 *     excludedClasses.set(listOf(
 *         "com.visionagent.*Test",
 *         "com.visionagent.*Spec"
 *     ))
 *     mutators.set(listOf(
 *         "STRONGER"          // More thorough than DEFAULT
 *     ))
 *     avoidCallsTo.set(listOf(
 *         "kotlin.jvm.internal",
 *         "android.util.Log"
 *     ))
 *     outputFormats.set(listOf("XML", "HTML"))
 *     threads.set(4)
 *     mutationThreshold.set(80)   // Fail if kill rate < 80%
 *     coverageThreshold.set(80)
 *     timestampedReports.set(false)
 * }
 *
 * Run: ./gradlew pitest
 * Report: build/reports/pitest/index.html
 * ============================================================
 */

// ── Strong Tests that survive mutation ──────────────────────────────────────

import com.visionagent.core.memory.ShortTermMemory
import com.visionagent.core.memory.MemoryItem
import com.visionagent.core.event.MemoryType
import com.visionagent.core.rule.AgentStateMachine
import com.visionagent.core.event.AgentState
import com.visionagent.core.screen.FPSController
import org.junit.Assert.*
import org.junit.Test

/**
 * Mutation-resistant tests.
 * Each assertion verifies a specific BOUNDARY or BEHAVIOR,
 * not just "it ran without exception".
 */
class MutationResistantTests {

    // ── STM Capacity ─────────────────────────────────────────────────────

    @Test
    fun stm_exactly_at_capacity_evicts_oldest() {
        val stm = ShortTermMemory(maxSize = 3)
        stm.put(item("k0", "v0"))
        stm.put(item("k1", "v1"))
        stm.put(item("k2", "v2"))
        // At capacity — next insert must evict k0
        stm.put(item("k3", "v3"))
        // k0 MUST be gone (tests > vs >=)
        assertNull("k0 should be evicted", stm.get("k0"))
        // k3 MUST be present
        assertEquals("v3", stm.get("k3")?.value)
        // Size MUST not exceed max
        assertEquals(3, stm.size())
    }

    @Test
    fun stm_one_below_capacity_does_not_evict() {
        val stm = ShortTermMemory(maxSize = 3)
        stm.put(item("k0", "v0"))
        stm.put(item("k1", "v1"))
        // At 2 of 3 — k0 must NOT be evicted
        assertNotNull("k0 should NOT be evicted yet", stm.get("k0"))
        assertEquals(2, stm.size())
    }

    @Test
    fun stm_ttl_exactly_expired_returns_null() {
        val stm = ShortTermMemory(maxSize = 10)
        val past = System.currentTimeMillis() - 1001L  // 1001ms ago
        stm.put(MemoryItem("ttl_key", "val", MemoryType.SHORT_TERM, "s",
            timestamp = past, ttlMs = 1000L))  // TTL was 1000ms → now expired
        assertNull("Expired item must return null", stm.get("ttl_key"))
    }

    @Test
    fun stm_ttl_not_yet_expired_returns_value() {
        val stm = ShortTermMemory(maxSize = 10)
        stm.put(MemoryItem("ttl_key", "val", MemoryType.SHORT_TERM, "s",
            timestamp = System.currentTimeMillis(),
            ttlMs     = 60_000L))  // 60 seconds — not expired
        assertNotNull("Non-expired item must return value", stm.get("ttl_key"))
        assertEquals("val", stm.get("ttl_key")?.value)
    }

    // ── State Machine Transitions ─────────────────────────────────────────

    @Test
    fun state_machine_invalid_transition_preserves_state() {
        val sm = AgentStateMachine()
        assertEquals(AgentState.IDLE, sm.getCurrentState())
        // IDLE → EXECUTING is INVALID
        sm.transition(AgentState.EXECUTING, "bad_trigger")
        // State must remain IDLE
        assertEquals("State must not change on invalid transition",
            AgentState.IDLE, sm.getCurrentState())
    }

    @Test
    fun state_machine_valid_chain_all_succeed() {
        val sm = AgentStateMachine()
        // Valid chain: IDLE → CAPTURING → ANALYZING → PLANNING → EXECUTING
        listOf(AgentState.CAPTURING, AgentState.ANALYZING,
               AgentState.PLANNING, AgentState.EXECUTING).forEach { state ->
            sm.transition(state, "test")
            assertEquals("State must be $state", state, sm.getCurrentState())
        }
    }

    @Test
    fun state_machine_rollback_goes_exactly_one_step_back() {
        val sm = AgentStateMachine()
        sm.transition(AgentState.CAPTURING, "t1")
        sm.transition(AgentState.ANALYZING, "t2")
        val rolledBack = sm.rollback()
        assertEquals("Rollback must return to CAPTURING", AgentState.CAPTURING, rolledBack)
        assertEquals(AgentState.CAPTURING, sm.getCurrentState())
    }

    // ── FPS Controller ────────────────────────────────────────────────────

    @Test
    fun fps_controller_respects_interval_exactly() {
        // At 10 FPS, interval = 100ms
        val fps = FPSController(10)
        val first = fps.shouldCapture()
        assertTrue("First call must always capture", first)
        // Immediately after → must NOT capture (interval not passed)
        val second = fps.shouldCapture()
        assertFalse("Second call immediately after must NOT capture", second)
    }

    @Test
    fun fps_controller_min_fps_1_never_drops_everything() {
        val fps = FPSController(1)  // 1 FPS = 1000ms interval
        val first = fps.shouldCapture()
        assertTrue("Even at 1FPS, first call must capture", first)
    }

    // ── Confidence Fusion ─────────────────────────────────────────────────

    @Test
    fun dempster_shafer_high_agreement_gives_high_confidence() {
        val fused = com.visionagent.core.vision.semantic.DempsterShafer
            .combineAll(listOf(0.9f, 0.9f, 0.85f))
        assertTrue("High agreement should give confidence > 0.85", fused > 0.85f)
        assertTrue("Fused confidence should not exceed 1.0", fused <= 1.0f)
    }

    @Test
    fun dempster_shafer_conflict_gives_moderate_confidence() {
        val fused = com.visionagent.core.vision.semantic.DempsterShafer
            .combineAll(listOf(0.9f, 0.1f))  // Strong conflict
        assertTrue("Conflicting evidence should NOT give extreme confidence",
            fused in 0.3f..0.7f)
    }

    @Test
    fun dempster_shafer_all_zero_gives_zero() {
        val fused = com.visionagent.core.vision.semantic.DempsterShafer
            .combineAll(listOf(0.0f, 0.0f, 0.0f))
        assertEquals("All-zero evidence must give 0 confidence",
            0.0f, fused, 0.01f)
    }

    // ── HNSW Vector Search ────────────────────────────────────────────────

    @Test
    fun hnsw_cosine_similarity_identical_vectors_is_one() {
        val v = floatArrayOf(1f, 2f, 3f, 4f)
        val sim = com.visionagent.core.memory.vector.VectorMath.cosineSimilarity(v, v)
        assertEquals("Identical vectors must have similarity = 1.0",
            1.0f, sim, 0.001f)
    }

    @Test
    fun hnsw_cosine_similarity_opposite_vectors_is_negative() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(-1f, 0f, 0f)
        val sim = com.visionagent.core.memory.vector.VectorMath.cosineSimilarity(a, b)
        assertTrue("Opposite vectors must have negative similarity", sim < 0f)
    }

    @Test
    fun hnsw_normalize_gives_unit_vector() {
        val v     = floatArrayOf(3f, 4f)  // magnitude = 5
        val norm  = com.visionagent.core.memory.vector.VectorMath.normalize(v)
        val length = Math.sqrt((norm[0]*norm[0] + norm[1]*norm[1]).toDouble()).toFloat()
        assertEquals("Normalized vector must have length 1.0",
            1.0f, length, 0.001f)
    }

    // Helper
    private fun item(key: String, value: String) =
        MemoryItem(key, value, MemoryType.SHORT_TERM, "test_session")
}
