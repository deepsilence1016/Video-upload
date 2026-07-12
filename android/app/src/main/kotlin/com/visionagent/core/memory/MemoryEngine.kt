package com.visionagent.core.memory
import kotlin.collections.ArrayDeque  // Explicit: avoids Lint confusion with java.util.ArrayDeque (API 35)

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.visionagent.core.event.*
import com.visionagent.data.local.database.AgentDatabase
import com.visionagent.data.local.entity.MemoryEntity
import com.visionagent.utils.Logger
import com.visionagent.utils.security.EncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// MemoryEngine — Multi-layer Memory Architecture
//
// Layers:
// 1. Short-Term Memory   → ConcurrentHashMap (RAM, fast, volatile)
// 2. Session Memory      → DataStore (persists within session)
// 3. Screen Memory       → In-memory circular buffer
// 4. Action Memory       → Indexed by sessionId + timestamp
// 5. Long-Term Memory    → Room DB (encrypted, persistent)
// 6. Learning Memory     → Room DB (special table, weighted)
// 7. Preference Memory   → DataStore + EncryptedSharedPrefs
//
// Design:
// - Write-through cache (RAM → DB)
// - LRU eviction for Short-Term
// - Encrypted storage for sensitive data
// - Coroutine-based async operations
// - Flow-based reactive reads
//
// Time Complexity:
// - STM read/write: O(1)
// - LTM read: O(log N) with index
// - LTM write: O(log N)
// Space: O(K) where K = memory capacity limit
// ============================================================

// ---- Context Extension for DataStore ----
val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "agent_session")

// ============================================================
// Memory Models
// ============================================================

data class MemoryItem(
    val key: String,
    val value: String,
    val type: MemoryType,
    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val ttlMs: Long = -1L,    // -1 = never expire
    val encrypted: Boolean = false,
    val weight: Float = 1.0f  // For learning memory priority
)

data class ScreenMemoryItem(
    val screenType: ScreenType,
    val elements: List<DetectedUIElement>,
    val ocrText: String,
    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class ActionMemoryItem(
    val actionType: ActionType,
    val target: DetectedUIElement?,
    val success: Boolean,
    val durationMs: Long,
    val screenContextBefore: ScreenType,
    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis()
)

// ============================================================
// Short-Term Memory — Ultra-fast RAM cache
// ============================================================

class ShortTermMemory(private val maxSize: Int = 500) {

    // Thread-safe, LRU-ordered
    private val store = object : LinkedHashMap<String, MemoryItem>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, MemoryItem>): Boolean =
            size > maxSize
    }

    @Synchronized fun put(item: MemoryItem) {
        // Remove expired items proactively
        if (item.ttlMs > 0 && System.currentTimeMillis() > item.timestamp + item.ttlMs) return
        store[item.key] = item
    }

    @Synchronized fun get(key: String): MemoryItem? {
        val item = store[key] ?: return null
        // Check TTL
        if (item.ttlMs > 0 && System.currentTimeMillis() > item.timestamp + item.ttlMs) {
            store.remove(key)
            return null
        }
        return item
    }

    @Synchronized fun remove(key: String) = store.remove(key)
    @Synchronized fun clear() = store.clear()
    @Synchronized fun contains(key: String) = store.containsKey(key)
    @Synchronized fun size() = store.size

    @Synchronized fun getAll(): Map<String, MemoryItem> = store.toMap()
}

// ============================================================
// Screen Memory — Circular Buffer for recent screens
// ============================================================

class ScreenMemory(private val capacity: Int = 20) {
    private val buffer = ArrayDeque<ScreenMemoryItem>(capacity)

    @Synchronized fun record(item: ScreenMemoryItem) {
        if (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(item)
    }

    @Synchronized fun getLast(): ScreenMemoryItem? = buffer.lastOrNull()
    @Synchronized fun getHistory(n: Int): List<ScreenMemoryItem> =
        buffer.takeLast(minOf(n, buffer.size))
    @Synchronized fun clear() = buffer.clear()

    @Synchronized fun findByType(type: ScreenType): ScreenMemoryItem? =
        buffer.lastOrNull { it.screenType == type }
}

// ============================================================
// Action Memory — Stores executed action history
// ============================================================

class ActionMemory(private val capacity: Int = 100) {
    private val history = ArrayDeque<ActionMemoryItem>(capacity)

    @Synchronized fun record(item: ActionMemoryItem) {
        if (history.size >= capacity) history.removeFirst()
        history.addLast(item)
    }

    @Synchronized fun getRecentActions(n: Int): List<ActionMemoryItem> =
        history.takeLast(minOf(n, history.size))

    @Synchronized fun getSuccessfulActions(): List<ActionMemoryItem> =
        history.filter { it.success }

    @Synchronized fun getFailedActions(): List<ActionMemoryItem> =
        history.filter { !it.success }

    @Synchronized fun getActionsByType(type: ActionType): List<ActionMemoryItem> =
        history.filter { it.actionType == type }

    @Synchronized fun clear() = history.clear()
    @Synchronized fun size() = history.size
}

// ============================================================
// MemoryEngine — Central Memory Coordinator
// ============================================================

@Singleton
class MemoryEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AgentDatabase,
    private val eventBus: AgentEventBus,
    private val encryptionManager: EncryptionManager,
    private val logger: Logger
) {

    companion object {
        private const val TAG = "MemoryEngine"
    }

    // ---- Memory Layers ----
    val shortTermMemory = ShortTermMemory(maxSize = 500)
    val screenMemory = ScreenMemory(capacity = 20)
    val actionMemory = ActionMemory(capacity = 100)

    private val engineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("MemoryEngine")
    )

    // ---- DataStore keys ----
    private object SessionKeys {
        val SESSION_ID = stringPreferencesKey("session_id")
        val SESSION_START = longPreferencesKey("session_start")
        val TOTAL_FRAMES = longPreferencesKey("total_frames")
        val LAST_SCREEN_TYPE = stringPreferencesKey("last_screen_type")
    }

    // ============================================================
    // Core Memory Operations
    // ============================================================

    /**
     * Store to Short-Term Memory (fast, volatile)
     */
    fun storeSTM(key: String, value: String, ttlMs: Long = 30_000L, sessionId: String = "") {
        val item = MemoryItem(
            key = key,
            value = value,
            type = MemoryType.SHORT_TERM,
            sessionId = sessionId,
            ttlMs = ttlMs
        )
        shortTermMemory.put(item)
    }

    /**
     * Retrieve from Short-Term Memory
     */
    fun getSTM(key: String): String? = shortTermMemory.get(key)?.value

    /**
     * Store to Long-Term Memory (persistent, encrypted if needed)
     */
    suspend fun storeLTM(
        key: String,
        value: String,
        encrypted: Boolean = false,
        sessionId: String = "",
        weight: Float = 1.0f
    ) {
        val storedValue = if (encrypted) encryptionManager.encrypt(value) else value
        val item = MemoryItem(
            key = key,
            value = storedValue,
            type = MemoryType.LONG_TERM,
            sessionId = sessionId,
            encrypted = encrypted,
            weight = weight
        )

        // FIX M5-4: Write DB FIRST, then populate STM cache on success.
        // Old order: STM first, then DB. If DB write failed (disk full, corruption),
        // exception propagated but STM already had the value → inconsistency:
        // caller sees failure but data exists in STM for the current session,
        // then silently lost on next launch (STM not persisted).
        //
        // New order: DB write first. If it fails, exception propagates and STM is NOT
        // written — caller correctly sees failure and data is fully absent.
        // On success: populate STM as a cache (fast-path for subsequent getLTM calls).
        withContext(Dispatchers.IO) {
            database.memoryDao().upsert(
                MemoryEntity(
                    key       = key,
                    value     = storedValue,
                    type      = MemoryType.LONG_TERM.name,
                    sessionId = sessionId,
                    encrypted = encrypted,
                    weight    = weight,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
        // DB write succeeded — now safe to populate STM cache
        shortTermMemory.put(item)

        eventBus.publish(MemoryStoredEvent(key, MemoryType.LONG_TERM, sessionId = sessionId))
    }

    /**
     * Retrieve from Long-Term Memory
     * Strategy: STM first (cache hit) → DB (cache miss)
     */
    suspend fun getLTM(key: String): String? {
        // Cache hit
        // FIX R3-4: STM.get() is @Synchronized — it holds the STM lock.
        // The old code called encryptionManager.decrypt() WHILE holding the lock
        // (via the inline `?.let { ... }` lambda — the lock was released after the entire
        // expression, including the decrypt call, returned).
        // Android Keystore decrypt can take 50-100ms → STM lock held for 100ms →
        // all other STM operations blocked for that duration.
        //
        // Fix: get the item under lock (fast), release the lock, then decrypt outside.
        val cachedItem = shortTermMemory.get(key)   // lock acquired + released here
        if (cachedItem != null) {
            // Decrypt happens AFTER the lock is released
            return if (cachedItem.encrypted) encryptionManager.decrypt(cachedItem.value)
                   else cachedItem.value
        }

        // Cache miss — load from DB
        return withContext(Dispatchers.IO) {
            database.memoryDao().getByKey(key)?.let { entity ->
                val value = if (entity.encrypted) encryptionManager.decrypt(entity.value)
                            else entity.value
                // Populate STM cache
                shortTermMemory.put(MemoryItem(
                    key = key,
                    value = entity.value,
                    type = MemoryType.LONG_TERM,
                    sessionId = entity.sessionId,
                    encrypted = entity.encrypted,
                    weight = entity.weight,
                    timestamp = entity.timestamp
                ))
                value
            }
        }
    }

    /**
     * Record screen state to Screen Memory
     */
    fun recordScreen(
        screenType: ScreenType,
        elements: List<DetectedUIElement>,
        ocrText: String,
        sessionId: String
    ) {
        screenMemory.record(ScreenMemoryItem(screenType, elements, ocrText, sessionId))
    }

    /**
     * Record action to Action Memory
     */
    fun recordAction(
        actionType: ActionType,
        target: DetectedUIElement?,
        success: Boolean,
        durationMs: Long,
        sessionId: String
    ) {
        actionMemory.record(ActionMemoryItem(
            actionType = actionType,
            target = target,
            success = success,
            durationMs = durationMs,
            screenContextBefore = screenMemory.getLast()?.screenType ?: ScreenType.UNKNOWN,
            sessionId = sessionId
        ))

        // Persist to DB asynchronously
        engineScope.launch {
            persistActionToDb(actionType, target, success, durationMs, sessionId)
        }
    }

    /**
     * Store Learning Memory — weighted, affects future decisions
     */
    suspend fun storeLearnedPattern(
        pattern: String,
        outcome: String,
        weight: Float,
        sessionId: String
    ) {
        val key = "learn_${pattern.hashCode()}"
        storeLTM(key, outcome, encrypted = false, sessionId = sessionId, weight = weight)
        logger.d(TAG, "Learned pattern stored: $pattern → $outcome (weight=$weight)")
    }

    /**
     * Key existence check (STM only — fast path)
     */
    fun hasKey(key: String): Boolean = shortTermMemory.contains(key)

    /**
     * Store Session metadata
     */
    fun storeSessionData(key: String, value: String, sessionId: String) {
        storeSTM(key, value, ttlMs = -1L, sessionId = sessionId)  // Session-long TTL
    }

    /**
     * Get memory summary for debugging
     */
    fun getMemorySummary(): Map<String, Any> = mapOf(
        "stm_size" to shortTermMemory.size(),
        "screen_history_size" to screenMemory.getHistory(20).size,
        "action_history_size" to actionMemory.size(),
        "last_screen" to (screenMemory.getLast()?.screenType?.name ?: "none"),
        "last_action" to (actionMemory.getRecentActions(1).firstOrNull()?.actionType?.name ?: "none")
    )

    // FIX M-3: Was a no-op — only logged, never wrote to DB.
    // Action history in Room DB was always empty. Any analytics or replay
    // feature querying action_history would always return zero rows.
    private suspend fun persistActionToDb(
        actionType: ActionType,
        target: DetectedUIElement?,
        success: Boolean,
        durationMs: Long,
        sessionId: String
    ) {
        try {
            database.actionHistoryDao().insert(
                com.visionagent.data.local.entity.ActionHistoryEntity(
                    actionId     = java.util.UUID.randomUUID().toString(),
                    sessionId    = sessionId,
                    actionType   = actionType.name,
                    success      = success,
                    durationMs   = durationMs,
                    targetType   = target?.type?.name,
                    targetText   = target?.text?.take(100),
                    errorMessage = null,
                    timestamp    = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            // Non-fatal — in-memory actionMemory is still valid
            logger.w(TAG, "Failed to persist action to DB: ${e.message}")
        }
    }

    fun clearSession() {
        shortTermMemory.clear()
        screenMemory.clear()
        actionMemory.clear()
        logger.i(TAG, "Session memory cleared")
    }
}
