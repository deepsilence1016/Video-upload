package com.visionagent.data.local.entity

import androidx.room.*

// ============================================================
// Room Entities — All database table definitions
//
// FIX ROOM-1: Entity classes were declared inside AgentDatabase.kt
// (package: com.visionagent.data.local.database) but DAOs.kt imported
// them from com.visionagent.data.local.entity.* — that package did
// not exist → Room kapt processor: "MissingType" on every DAO.
//
// Fix: move all @Entity classes here into the correct package.
// AgentDatabase.kt imports com.visionagent.data.local.entity.*
// DAOs.kt imports com.visionagent.data.local.entity.*
// Both now resolve correctly.
// ============================================================

@Entity(
    tableName = "sessions",
    indices = [Index("session_id", unique = true)]
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    @ColumnInfo(name = "total_frames") val totalFrames: Long = 0,
    @ColumnInfo(name = "total_actions") val totalActions: Long = 0,
    @ColumnInfo(name = "is_successful") val isSuccessful: Boolean = true,
    @ColumnInfo(name = "app_package") val appPackage: String = "",
    @ColumnInfo(name = "agent_version") val agentVersion: String = ""
)

@Entity(
    tableName = "screen_states",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("session_id"),
        Index("timestamp"),
        Index("screen_type")
    ]
)
data class ScreenStateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "screen_type") val screenType: String,
    @ColumnInfo(name = "element_count") val elementCount: Int,
    @ColumnInfo(name = "ocr_text_preview") val ocrTextPreview: String,
    @ColumnInfo(name = "confidence") val confidence: Float,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "frame_id") val frameId: Long = 0
)

@Entity(
    tableName = "ui_elements",
    foreignKeys = [
        ForeignKey(
            entity = ScreenStateEntity::class,
            parentColumns = ["id"],
            childColumns = ["screen_state_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("screen_state_id"),
        Index("element_type"),
        Index("confidence")
    ]
)
data class UIElementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "screen_state_id") val screenStateId: Long,
    @ColumnInfo(name = "element_type") val elementType: String,
    @ColumnInfo(name = "text") val text: String?,
    @ColumnInfo(name = "bounds_left") val boundsLeft: Int,
    @ColumnInfo(name = "bounds_top") val boundsTop: Int,
    @ColumnInfo(name = "bounds_right") val boundsRight: Int,
    @ColumnInfo(name = "bounds_bottom") val boundsBottom: Int,
    @ColumnInfo(name = "confidence") val confidence: Float,
    @ColumnInfo(name = "attributes_json") val attributesJson: String = "{}"
)

@Entity(
    tableName = "ocr_results",
    foreignKeys = [
        ForeignKey(
            entity = ScreenStateEntity::class,
            parentColumns = ["id"],
            childColumns = ["screen_state_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("screen_state_id"), Index("session_id")]
)
data class OCRResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "screen_state_id") val screenStateId: Long,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "full_text") val fullText: String,
    @ColumnInfo(name = "confidence") val confidence: Float,
    @ColumnInfo(name = "language") val language: String,
    @ColumnInfo(name = "is_cached") val isCached: Boolean,
    @ColumnInfo(name = "processing_ms") val processingMs: Long,
    @ColumnInfo(name = "timestamp") val timestamp: Long
)

@Entity(
    tableName = "rule_executions",
    indices = [Index("session_id"), Index("rule_id"), Index("timestamp")]
)
data class RuleExecutionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "rule_name") val ruleName: String,
    @ColumnInfo(name = "matched") val matched: Boolean,
    @ColumnInfo(name = "priority") val priority: Int,
    @ColumnInfo(name = "action_type") val actionType: String,
    @ColumnInfo(name = "decision_json") val decisionJson: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long
)

@Entity(
    tableName = "action_history",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("session_id"),
        Index("action_type"),
        Index("timestamp"),
        Index("success")
    ]
)
data class ActionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "action_id") val actionId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "action_type") val actionType: String,
    @ColumnInfo(name = "success") val success: Boolean,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "target_type") val targetType: String?,
    @ColumnInfo(name = "target_text") val targetText: String?,
    @ColumnInfo(name = "error_message") val errorMessage: String?,
    @ColumnInfo(name = "timestamp") val timestamp: Long
)

@Entity(
    tableName = "memory_store",
    indices = [
        Index("key", unique = true),
        Index("type"),
        Index("session_id")
    ]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "encrypted") val encrypted: Boolean = false,
    @ColumnInfo(name = "weight") val weight: Float = 1.0f,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "ttl_ms") val ttlMs: Long = -1L
)

@Entity(
    tableName = "performance_logs",
    indices = [Index("module"), Index("session_id"), Index("timestamp")]
)
data class PerformanceLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "module") val module: String,
    @ColumnInfo(name = "operation") val operation: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "memory_bytes") val memoryBytes: Long,
    @ColumnInfo(name = "cpu_percent") val cpuPercent: Float,
    @ColumnInfo(name = "timestamp") val timestamp: Long
)

@Entity(
    tableName = "error_logs",
    indices = [Index("session_id"), Index("error_code"), Index("timestamp")]
)
data class ErrorLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "error_code") val errorCode: String,
    @ColumnInfo(name = "message") val message: String,
    @ColumnInfo(name = "stack_trace") val stackTrace: String?,
    @ColumnInfo(name = "is_fatal") val isFatal: Boolean,
    @ColumnInfo(name = "recovery_attempted") val recoveryAttempted: Boolean = false,
    @ColumnInfo(name = "timestamp") val timestamp: Long
)

@Entity(
    tableName = "config_store",
    indices = [Index("config_key", unique = true)]
)
data class ConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "config_key") val configKey: String,
    @ColumnInfo(name = "config_value") val configValue: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

// ============================================================
// Type Converters
// ============================================================

class AgentTypeConverters {
    @TypeConverter fun fromList(list: List<String>): String = list.joinToString(",")
    @TypeConverter fun toList(str: String): List<String> = str.split(",").filter { it.isNotEmpty() }
}
