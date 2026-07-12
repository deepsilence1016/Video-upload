package com.visionagent.data.local.database

import androidx.room.*
import com.visionagent.data.local.dao.*
import com.visionagent.data.local.entity.*

// ============================================================
// AgentDatabase — Room Database (SQLite)
//
// FIX ROOM-1: Entity classes moved to com.visionagent.data.local.entity
// package (Entities.kt). Previously they were declared here causing
// DAOs.kt import of com.visionagent.data.local.entity.* to fail with
// "MissingType" — that package was empty.
//
// Tables:
// 1. sessions         — Agent sessions
// 2. screen_states    — Captured screen snapshots
// 3. ui_elements      — Detected UI elements
// 4. ocr_results      — OCR text extractions
// 5. rule_executions  — Rule engine decisions
// 6. action_history   — All executed actions
// 7. memory_store     — Long-term memory KV
// 8. performance_logs — Timing & resource metrics
// 9. error_logs       — Error & recovery records
// 10. config_store    — Key-value configuration
// ============================================================

@Database(
    entities = [
        SessionEntity::class,
        ScreenStateEntity::class,
        UIElementEntity::class,
        OCRResultEntity::class,
        RuleExecutionEntity::class,
        ActionHistoryEntity::class,
        MemoryEntity::class,
        PerformanceLogEntity::class,
        ErrorLogEntity::class,
        ConfigEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(AgentTypeConverters::class)
abstract class AgentDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun screenStateDao(): ScreenStateDao
    abstract fun uiElementDao(): UIElementDao
    abstract fun ocrResultDao(): OCRResultDao
    abstract fun ruleExecutionDao(): RuleExecutionDao
    abstract fun actionHistoryDao(): ActionHistoryDao
    abstract fun memoryDao(): MemoryDao
    abstract fun performanceLogDao(): PerformanceLogDao
    abstract fun errorLogDao(): ErrorLogDao
    abstract fun configDao(): ConfigDao
}
