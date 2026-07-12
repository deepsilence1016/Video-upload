package com.visionagent.data.local.dao

import androidx.room.*
import com.visionagent.data.local.entity.*
import kotlinx.coroutines.flow.Flow

// ============================================================
// DAOs — Data Access Objects for all tables
// ============================================================

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE session_id = :sessionId LIMIT 1")
    suspend fun getById(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY started_at DESC LIMIT :limit")
    fun getRecentSessions(limit: Int = 20): Flow<List<SessionEntity>>

    @Query("DELETE FROM sessions WHERE started_at < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Long
}

@Dao
interface ScreenStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(state: ScreenStateEntity): Long

    @Query("SELECT * FROM screen_states WHERE session_id = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getBySession(sessionId: String, limit: Int = 50): List<ScreenStateEntity>

    @Query("SELECT * FROM screen_states WHERE screen_type = :type ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByType(type: String, limit: Int = 20): List<ScreenStateEntity>

    @Query("DELETE FROM screen_states WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM screen_states WHERE session_id = :sessionId")
    suspend fun countBySession(sessionId: String): Int
}

@Dao
interface UIElementDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(elements: List<UIElementEntity>)

    @Query("SELECT * FROM ui_elements WHERE screen_state_id = :screenStateId")
    suspend fun getByScreenState(screenStateId: Long): List<UIElementEntity>

    @Query("""
        SELECT * FROM ui_elements 
        WHERE element_type = :type AND confidence >= :minConfidence 
        ORDER BY confidence DESC LIMIT :limit
    """)
    suspend fun getByType(type: String, minConfidence: Float = 0.75f, limit: Int = 10): List<UIElementEntity>

    @Query("DELETE FROM ui_elements WHERE screen_state_id IN (SELECT id FROM screen_states WHERE timestamp < :before)")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface OCRResultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: OCRResultEntity): Long

    @Query("SELECT * FROM ocr_results WHERE session_id = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getBySession(sessionId: String, limit: Int = 50): List<OCRResultEntity>

    @Query("SELECT * FROM ocr_results WHERE full_text LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun searchText(query: String, limit: Int = 20): List<OCRResultEntity>

    @Query("DELETE FROM ocr_results WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface RuleExecutionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(execution: RuleExecutionEntity): Long

    @Query("SELECT * FROM rule_executions WHERE session_id = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getBySession(sessionId: String, limit: Int = 50): List<RuleExecutionEntity>

    @Query("SELECT * FROM rule_executions WHERE rule_id = :ruleId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByRule(ruleId: String, limit: Int = 20): List<RuleExecutionEntity>

    @Query("""
        SELECT rule_id, rule_name, COUNT(*) as match_count 
        FROM rule_executions WHERE matched = 1 
        GROUP BY rule_id ORDER BY match_count DESC
    """)
    suspend fun getRuleStats(): List<RuleStats>

    @Delete
    suspend fun delete(execution: RuleExecutionEntity)
}

data class RuleStats(
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "rule_name") val ruleName: String,
    @ColumnInfo(name = "match_count") val matchCount: Int
)

@Dao
interface ActionHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(action: ActionHistoryEntity): Long

    @Query("SELECT * FROM action_history WHERE session_id = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    fun getBySession(sessionId: String, limit: Int = 100): Flow<List<ActionHistoryEntity>>

    @Query("SELECT * FROM action_history WHERE success = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getFailedActions(limit: Int = 20): List<ActionHistoryEntity>

    @Query("""
        SELECT action_type, 
               COUNT(*) as total, 
               SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) as successes,
               AVG(duration_ms) as avg_duration
        FROM action_history 
        GROUP BY action_type
    """)
    suspend fun getActionStats(): List<ActionStats>

    @Query("DELETE FROM action_history WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}

data class ActionStats(
    @ColumnInfo(name = "action_type") val actionType: String,
    @ColumnInfo(name = "total") val total: Int,
    @ColumnInfo(name = "successes") val successes: Int,
    @ColumnInfo(name = "avg_duration") val avgDuration: Double
)

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity): Long

    // FIX R3-7: @Transaction + @Query("INSERT...") with an entity parameter is an
    // invalid Room annotation combination — the annotation processor binds query
    // parameters by name (:key, :value, ...) but the method only has one param named
    // 'memory', so kapt throws "Cannot find setter for field key in parameter memory".
    // Fix: use @Insert(onConflict = REPLACE) which Room handles correctly for entities.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: MemoryEntity): Long

    @Query("SELECT * FROM memory_store WHERE key = :key LIMIT 1")
    suspend fun getByKey(key: String): MemoryEntity?

    @Query("SELECT * FROM memory_store WHERE type = :type ORDER BY weight DESC LIMIT :limit")
    suspend fun getByType(type: String, limit: Int = 100): List<MemoryEntity>

    @Query("SELECT * FROM memory_store ORDER BY weight DESC, timestamp DESC LIMIT :limit")
    suspend fun getTopMemories(limit: Int = 50): List<MemoryEntity>

    @Query("DELETE FROM memory_store WHERE key = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM memory_store WHERE type = :type AND session_id = :sessionId")
    suspend fun deleteSessionMemory(type: String, sessionId: String)

    @Query("DELETE FROM memory_store WHERE ttl_ms > 0 AND timestamp + ttl_ms < :now")
    suspend fun deleteExpired(now: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM memory_store")
    suspend fun count(): Long
}

@Dao
interface PerformanceLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: PerformanceLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<PerformanceLogEntity>)

    @Query("""
        SELECT module, operation, 
               AVG(duration_ms) as avg_ms,
               MAX(duration_ms) as max_ms,
               COUNT(*) as count
        FROM performance_logs 
        WHERE session_id = :sessionId
        GROUP BY module, operation
    """)
    suspend fun getSessionSummary(sessionId: String): List<PerformanceSummary>

    @Query("SELECT * FROM performance_logs WHERE duration_ms > :thresholdMs ORDER BY duration_ms DESC LIMIT :limit")
    suspend fun getSlowOperations(thresholdMs: Long, limit: Int = 20): List<PerformanceLogEntity>

    @Query("DELETE FROM performance_logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}

data class PerformanceSummary(
    val module: String,
    val operation: String,
    @ColumnInfo(name = "avg_ms") val avgMs: Double,
    @ColumnInfo(name = "max_ms") val maxMs: Long,
    val count: Int
)

@Dao
interface ErrorLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(error: ErrorLogEntity): Long

    @Query("SELECT * FROM error_logs WHERE session_id = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getBySession(sessionId: String, limit: Int = 50): List<ErrorLogEntity>

    @Query("SELECT * FROM error_logs WHERE is_fatal = 1 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getFatalErrors(limit: Int = 20): List<ErrorLogEntity>

    @Query("SELECT * FROM error_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecent(limit: Int = 100): Flow<List<ErrorLogEntity>>

    @Query("DELETE FROM error_logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface ConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: ConfigEntity): Long

    @Query("SELECT * FROM config_store WHERE config_key = :key LIMIT 1")
    suspend fun getByKey(key: String): ConfigEntity?

    @Query("SELECT * FROM config_store")
    suspend fun getAll(): List<ConfigEntity>

    @Query("DELETE FROM config_store WHERE config_key = :key")
    suspend fun deleteByKey(key: String)

    @Transaction
    suspend fun upsertConfig(key: String, value: String) {
        insert(ConfigEntity(configKey = key, configValue = value, updatedAt = System.currentTimeMillis()))
    }
}
