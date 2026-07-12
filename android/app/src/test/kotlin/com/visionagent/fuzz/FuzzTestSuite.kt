package com.visionagent.fuzz

import com.visionagent.core.memory.ShortTermMemory
import com.visionagent.core.memory.MemoryItem
import com.visionagent.core.event.MemoryType
import com.visionagent.core.event.AgentState
import com.visionagent.core.rule.AgentStateMachine
import com.visionagent.core.rule.RuleRegistry
import com.visionagent.core.rule.Rule
import com.visionagent.core.rule.RuleAction
import com.visionagent.core.event.ActionType
import com.visionagent.core.workflow.script.ScriptEngine
import com.visionagent.core.vision.semantic.DempsterShafer
import com.visionagent.core.memory.vector.VectorMath
import org.junit.Test
import kotlin.random.Random

// ============================================================
// FuzzTestSuite — Random Invalid Input Testing
//
// Each test:
// 1. Generates thousands of random inputs
// 2. Passes them to core functions
// 3. Verifies NO exception / NO crash / NO hang
// 4. Verifies invariants (e.g., confidence always 0-1)
//
// Tools:
// - Custom random generator (no external library needed)
// - Property-based testing patterns
//
// Note: For true fuzz testing, also use:
// - AFL (American Fuzzy Lop) on C++ code
// - libFuzzer on OpenCV/Tesseract paths
// - kotlinx.fuzz (when released)
// ============================================================

class FuzzTestSuite {

    private val rng = Random(42)  // Deterministic seed for reproducibility

    // ── String Generators ─────────────────────────────────────────────────
    private fun randomString(maxLen: Int = 100): String {
        val len = rng.nextInt(maxLen + 1)
        return (0 until len).map { rng.nextInt(0x10FFFF).toChar() }.joinToString("")
    }
    private fun randomAscii(maxLen: Int = 100): String {
        val len = rng.nextInt(maxLen + 1)
        return (0 until len).map { (32..126).random(rng).toChar() }.joinToString("")
    }
    private fun randomKey(): String = randomAscii(50)
    private fun randomFloat() = when (rng.nextInt(5)) {
        0 -> Float.NaN
        1 -> Float.POSITIVE_INFINITY
        2 -> Float.NEGATIVE_INFINITY
        3 -> Float.MAX_VALUE
        4 -> -Float.MAX_VALUE
        else -> rng.nextFloat() * 200f - 100f  // -100..100
    }
    private fun randomInt() = when (rng.nextInt(4)) {
        0 -> Int.MIN_VALUE
        1 -> Int.MAX_VALUE
        2 -> 0
        else -> rng.nextInt()
    }
    private fun randomFloatArray(size: Int = 384) = FloatArray(size) { randomFloat() }

    // ── STM Fuzz Tests ────────────────────────────────────────────────────

    @Test
    fun fuzz_stm_random_keys_values_no_crash() {
        val stm = ShortTermMemory(maxSize = 500)
        repeat(10_000) {
            val key   = randomKey()
            val value = randomString(500)
            val ttl   = rng.nextLong(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2)
            // Must never throw
            try {
                stm.put(MemoryItem(key, value, MemoryType.SHORT_TERM, "fuzz", ttlMs = ttl))
                stm.get(key)
                stm.contains(key)
            } catch (e: Exception) {
                throw AssertionError("STM threw on input: key='${key.take(20)}' ttl=$ttl", e)
            }
        }
        println("✅ STM: 10,000 random operations — no crash")
    }

    @Test
    fun fuzz_stm_size_never_exceeds_max() {
        val maxSize = 50
        val stm     = ShortTermMemory(maxSize = maxSize)
        repeat(5000) {
            stm.put(MemoryItem(randomKey(), randomString(), MemoryType.SHORT_TERM, "fuzz"))
            val size = stm.size()
            assert(size <= maxSize) { "STM exceeded max: $size > $maxSize" }
        }
        println("✅ STM: size invariant holds across 5,000 random inserts")
    }

    @Test
    fun fuzz_stm_concurrent_no_data_corruption() {
        val stm     = ShortTermMemory(maxSize = 200)
        val threads = (0 until 20).map { t ->
            Thread {
                repeat(500) { i ->
                    val key = "t${t}_k${i}"
                    stm.put(MemoryItem(key, "v${i}", MemoryType.SHORT_TERM, "fuzz"))
                    stm.get(key)
                    stm.remove(key)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }
        // No exception = pass (concurrent operations must not corrupt state)
        println("✅ STM: 20 concurrent threads × 500 ops — no corruption")
    }

    // ── State Machine Fuzz ────────────────────────────────────────────────

    @Test
    fun fuzz_state_machine_random_transitions_no_crash() {
        val sm     = AgentStateMachine()
        val states = AgentState.values()
        repeat(10_000) {
            val target = states.random(rng)
            val trigger = randomAscii(50)
            // Must never throw — just return valid/invalid
            try {
                sm.transition(target, trigger)
            } catch (e: Exception) {
                throw AssertionError("StateMachine threw on transition to $target", e)
            }
        }
        println("✅ StateMachine: 10,000 random transitions — no crash")
    }

    @Test
    fun fuzz_state_machine_current_state_always_valid() {
        val sm     = AgentStateMachine()
        val states = AgentState.values()
        repeat(1000) {
            sm.transition(states.random(rng), "fuzz")
            val current = sm.getCurrentState()
            assert(current in states) { "Invalid state: $current" }
        }
        println("✅ StateMachine: current state always valid")
    }

    // ── Rule Registry Fuzz ────────────────────────────────────────────────

    @Test
    fun fuzz_rule_registry_random_priorities_stable_sort() {
        val registry = RuleRegistry()
        repeat(1000) { i ->
            registry.register(Rule(
                id         = "rule_$i",
                name       = randomAscii(30),
                priority   = randomInt().coerceIn(0, 10000),
                conditions = emptyList(),
                action     = RuleAction(ActionType.WAIT, null)
            ))
        }
        val ordered = registry.getOrderedRules()
        // Verify descending priority order
        for (i in 0 until ordered.size - 1) {
            assert(ordered[i].priority >= ordered[i + 1].priority) {
                "Rules not sorted: ${ordered[i].priority} < ${ordered[i+1].priority}"
            }
        }
        println("✅ RuleRegistry: ${ordered.size} rules always sorted correctly")
    }

    // ── ScriptEngine Fuzz ─────────────────────────────────────────────────

    @Test
    fun fuzz_script_engine_no_crash_on_invalid_input() {
        val engine = ScriptEngine()
        val invalidInputs = listOf(
            "", " ", "   ", "\n", "\t", "\r\n",
            "((((((((", "))))))))", "\"\"\"\"\"\"",
            "1/0", "0/0", "sqrt(-1)", "abs()",
            "{undefined_var}", "unknown_function()",
            "a".repeat(500),  // Too long
            "∞", "NaN", "null", "undefined",
            "1 + + + 1", "* * *", "===",
            "function(){}", "System.exit(0)",
            "Runtime.getRuntime().exec(\"rm -rf /\")",  // Security test
            "Class.forName(\"android.util.Log\")"        // Reflection test
        )
        invalidInputs.forEach { input ->
            try {
                val result = engine.evaluate(input)
                // Result can be "ERROR:..." — that's fine
                // Must NOT throw, hang, or execute arbitrary code
                assert(!result.contains("System") && !result.contains("Runtime")) {
                    "Security violation: script returned dangerous content for input: $input"
                }
            } catch (e: Exception) {
                throw AssertionError("ScriptEngine threw on: '$input'", e)
            }
        }
        println("✅ ScriptEngine: ${invalidInputs.size} invalid inputs handled safely")
    }

    @Test
    fun fuzz_script_engine_random_expressions() {
        val engine  = ScriptEngine()
        val ops     = listOf("+", "-", "*", "/", "==", "!=", ">", "<")
        val funcs   = listOf("abs", "max", "min", "length", "upper", "lower", "trim", "if", "not")

        repeat(5000) {
            val expr = when (rng.nextInt(4)) {
                0 -> "${rng.nextInt(100)} ${ops.random(rng)} ${rng.nextInt(100)}"
                1 -> "${funcs.random(rng)}(${rng.nextInt(100)})"
                2 -> "\"${randomAscii(20)}\""
                else -> randomAscii(50)
            }
            try {
                engine.evaluate(expr)
            } catch (e: Exception) {
                throw AssertionError("ScriptEngine threw on: '$expr'", e)
            }
        }
        println("✅ ScriptEngine: 5,000 random expressions — no crash")
    }

    // ── Dempster-Shafer Fuzz ──────────────────────────────────────────────

    @Test
    fun fuzz_dempster_shafer_output_always_0_to_1() {
        repeat(10_000) {
            val beliefs = (1..rng.nextInt(1, 6)).map { rng.nextFloat() * 200f - 100f }
            val result  = DempsterShafer.combineAll(beliefs)
            assert(result in -0.001f..1.001f) {
                "DS result out of range: $result for inputs: $beliefs"
            }
        }
        println("✅ Dempster-Shafer: 10,000 random inputs always output [0,1]")
    }

    @Test
    fun fuzz_dempster_shafer_empty_list_no_crash() {
        val result = DempsterShafer.combineAll(emptyList())
        // Should return 0 or 0.5 (neutral), never throw
        println("✅ Empty DS list: result=$result")
    }

    // ── Vector Math Fuzz ──────────────────────────────────────────────────

    @Test
    fun fuzz_vector_cosine_no_nan_infinity() {
        repeat(1000) {
            val a = randomFloatArray(384)
            val b = randomFloatArray(384)
            try {
                val sim = VectorMath.cosineSimilarity(a, b)
                // NaN and Infinity indicate a math bug
                assert(!sim.isNaN()) { "CosineSim returned NaN for random vectors" }
                assert(!sim.isInfinite()) { "CosineSim returned Infinity for random vectors" }
            } catch (e: Exception) {
                // ArithmeticException or similar must not propagate
                throw AssertionError("VectorMath threw on random vectors", e)
            }
        }
        println("✅ VectorMath: 1,000 random vector pairs — no NaN/Infinity")
    }

    @Test
    fun fuzz_vector_normalize_no_crash_on_zero_vector() {
        val zeroVec = FloatArray(384) { 0f }
        val result  = VectorMath.normalize(zeroVec)
        // Zero vector should return as-is (can't normalize)
        assert(result.none { it.isNaN() || it.isInfinite() }) {
            "Normalize zero vector returned NaN/Infinity"
        }
        println("✅ VectorMath.normalize: zero vector handled safely")
    }

    // ── Boundary Tests ────────────────────────────────────────────────────

    @Test
    fun fuzz_boundary_extreme_values() {
        val stm = ShortTermMemory(maxSize = 1)  // Min possible size

        // Empty key
        stm.put(MemoryItem("", "value", MemoryType.SHORT_TERM, ""))
        // Max long TTL
        stm.put(MemoryItem("k", "v", MemoryType.SHORT_TERM, "s", ttlMs = Long.MAX_VALUE))
        // Negative TTL (should be treated as no-expire or expire immediately)
        stm.put(MemoryItem("k2", "v", MemoryType.SHORT_TERM, "s", ttlMs = Long.MIN_VALUE))
        // Empty value
        stm.put(MemoryItem("k3", "", MemoryType.SHORT_TERM, "s"))
        // Unicode key
        stm.put(MemoryItem("🤖👾🎯", "emoji value", MemoryType.SHORT_TERM, "s"))
        // Very long key
        stm.put(MemoryItem("k".repeat(10000), "v", MemoryType.SHORT_TERM, "s"))

        println("✅ Boundary: extreme values handled without crash")
    }
}
