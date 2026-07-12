package com.visionagent

import com.visionagent.core.memory.ActionMemory
import com.visionagent.core.memory.ScreenMemory
import com.visionagent.core.memory.ShortTermMemory
import com.visionagent.core.memory.MemoryItem
import com.visionagent.core.event.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

// ============================================================
// MemoryEngineTest — Unit Tests for Memory Layers
// ============================================================

class MemoryEngineTest {

    // ---- Short-Term Memory Tests ----

    @Test
    fun `STM stores and retrieves items correctly`() {
        val stm = ShortTermMemory(maxSize = 10)
        val item = MemoryItem(
            key = "test_key",
            value = "test_value",
            type = MemoryType.SHORT_TERM,
            sessionId = "session1"
        )
        stm.put(item)
        assertEquals("test_value", stm.get("test_key")?.value)
    }

    @Test
    fun `STM evicts oldest when at max capacity`() {
        val stm = ShortTermMemory(maxSize = 3)
        repeat(4) { i ->
            stm.put(MemoryItem("key_$i", "value_$i", MemoryType.SHORT_TERM, "s1"))
        }
        // First item should be evicted
        assertNull(stm.get("key_0"))
        assertNotNull(stm.get("key_3"))
    }

    @Test
    fun `STM returns null for expired items`() {
        val stm = ShortTermMemory(maxSize = 10)
        val item = MemoryItem(
            key = "expiring",
            value = "value",
            type = MemoryType.SHORT_TERM,
            sessionId = "s1",
            timestamp = System.currentTimeMillis() - 10000L,  // 10 seconds ago
            ttlMs = 1000L  // 1 second TTL — already expired
        )
        stm.put(item)
        assertNull(stm.get("expiring"))
    }

    @Test
    fun `STM contains() works correctly`() {
        val stm = ShortTermMemory(maxSize = 10)
        stm.put(MemoryItem("key1", "val1", MemoryType.SHORT_TERM, "s1"))
        assertTrue(stm.contains("key1"))
        assertFalse(stm.contains("key2"))
    }

    @Test
    fun `STM clear() removes all items`() {
        val stm = ShortTermMemory(maxSize = 10)
        repeat(5) { stm.put(MemoryItem("key_$it", "val", MemoryType.SHORT_TERM, "s1")) }
        stm.clear()
        assertEquals(0, stm.size())
    }

    // ---- Screen Memory Tests ----

    @Test
    fun `ScreenMemory records and retrieves latest screen`() {
        val sm = ScreenMemory(capacity = 5)
        sm.record(com.visionagent.core.memory.ScreenMemoryItem(
            screenType = ScreenType.HOME,
            elements = emptyList(),
            ocrText = "Home Screen",
            sessionId = "s1"
        ))
        assertEquals(ScreenType.HOME, sm.getLast()?.screenType)
    }

    @Test
    fun `ScreenMemory respects capacity limit`() {
        val sm = ScreenMemory(capacity = 3)
        repeat(5) {
            sm.record(com.visionagent.core.memory.ScreenMemoryItem(
                ScreenType.HOME, emptyList(), "text_$it", "s1"
            ))
        }
        assertEquals(3, sm.getHistory(10).size)
    }

    @Test
    fun `ScreenMemory findByType returns correct screen`() {
        val sm = ScreenMemory(capacity = 10)
        sm.record(com.visionagent.core.memory.ScreenMemoryItem(ScreenType.HOME, emptyList(), "", "s1"))
        sm.record(com.visionagent.core.memory.ScreenMemoryItem(ScreenType.DIALOG, emptyList(), "", "s1"))
        sm.record(com.visionagent.core.memory.ScreenMemoryItem(ScreenType.HOME, emptyList(), "", "s1"))

        val dialog = sm.findByType(ScreenType.DIALOG)
        assertEquals(ScreenType.DIALOG, dialog?.screenType)
    }

    // ---- Action Memory Tests ----

    @Test
    fun `ActionMemory records and retrieves actions`() {
        val am = ActionMemory(capacity = 10)
        am.record(com.visionagent.core.memory.ActionMemoryItem(
            actionType = ActionType.TAP,
            target = null,
            success = true,
            durationMs = 100L,
            screenContextBefore = ScreenType.HOME,
            sessionId = "s1"
        ))
        assertEquals(1, am.size())
        assertEquals(ActionType.TAP, am.getRecentActions(1).first().actionType)
    }

    @Test
    fun `ActionMemory correctly separates successful and failed actions`() {
        val am = ActionMemory(capacity = 10)
        am.record(com.visionagent.core.memory.ActionMemoryItem(ActionType.TAP, null, true, 100L, ScreenType.HOME, "s1"))
        am.record(com.visionagent.core.memory.ActionMemoryItem(ActionType.SCROLL_DOWN, null, false, 200L, ScreenType.HOME, "s1"))
        am.record(com.visionagent.core.memory.ActionMemoryItem(ActionType.TEXT_INPUT, null, true, 50L, ScreenType.FORM, "s1"))

        assertEquals(2, am.getSuccessfulActions().size)
        assertEquals(1, am.getFailedActions().size)
    }
}
