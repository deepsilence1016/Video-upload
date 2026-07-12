package com.visionagent.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.visionagent.core.action.ActionRateLimiter
import com.visionagent.core.event.AgentEventBus
import com.visionagent.core.memory.MemoryEngine
import com.visionagent.core.ocr.ErrorCorrector
import com.visionagent.core.ocr.OCRNativeBridge
import com.visionagent.core.ocr.TextCache
import com.visionagent.core.performance.FrameMemoryPool
import com.visionagent.core.performance.PerformanceTracker
import com.visionagent.core.planner.RiskAnalyzer
import com.visionagent.core.rule.AgentStateMachine
import com.visionagent.core.rule.RuleEvaluator
import com.visionagent.core.rule.RuleRegistry
import com.visionagent.core.vision.ConfidenceScorer
import com.visionagent.core.vision.ScreenClassifier
import com.visionagent.core.vision.VisionNativeBridge
import com.visionagent.data.local.database.AgentDatabase
import com.visionagent.utils.Logger
// EncryptionManager import removed — @Inject constructor handles DI (FIX HILT-1)
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// ============================================================
// AppModule — Hilt Dependency Injection Module
//
// Provides all singleton dependencies for the agent.
// Follows Dependency Inversion Principle — high-level modules
// depend on abstractions, not concretions.
// ============================================================

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ---- Core Infrastructure ----

    @Provides
    @Singleton
    fun provideAgentDatabase(@ApplicationContext context: Context): AgentDatabase {
        return Room.databaseBuilder(
            context,
            AgentDatabase::class.java,
            "vision_agent.db"
        )
        .enableMultiInstanceInvalidation()
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)  // WAL for performance
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()
    }

    // provideLogger removed — class has @Inject constructor (Hilt handles it automatically)
    // provideAgentEventBus removed — class has @Inject constructor (Hilt handles it automatically)
    // FIX HILT-1: EncryptionManager has @Inject constructor(@ApplicationContext context: Context)
    // — Hilt can inject it automatically. Having a duplicate @Provides here creates a
    // "duplicate binding" error: Hilt sees two ways to satisfy EncryptionManager.
    // Fix: removed @Provides — let @Inject constructor handle DI automatically.
    // EncryptionManager is @Singleton + @Inject so it works without @Provides.

    // providePerformanceTracker removed — class has @Inject constructor

    @Provides
    @Singleton
    fun provideFrameMemoryPool(): FrameMemoryPool = FrameMemoryPool(poolSize = 5)

    // ---- Vision Engine ----

    // provideVisionNativeBridge removed — class has @Inject constructor

    // provideScreenClassifier removed — class has @Inject constructor (Hilt handles it automatically)
    // provideConfidenceScorer removed — class has @Inject constructor (Hilt handles it automatically)
    // ---- OCR Engine ----

    // provideOCRNativeBridge removed — class has @Inject constructor

    // provideTextCache removed — class has @Inject constructor (Hilt handles it automatically)
    // provideErrorCorrector removed — class has @Inject constructor (Hilt handles it automatically)
    // ---- Rule Engine ----

    @Provides
    @Singleton
    fun provideRuleRegistry(): RuleRegistry = RuleRegistry()

    @Provides
    @Singleton
    fun provideAgentStateMachine(): AgentStateMachine = AgentStateMachine()

    // provideRuleEvaluator removed — class has @Inject constructor

    // ---- Planner Engine ----

    @Provides
    @Singleton
    fun provideRiskAnalyzer(): RiskAnalyzer = RiskAnalyzer()

    // ---- Action Engine ----

    @Provides
    @Singleton
    fun provideActionRateLimiter(): ActionRateLimiter =
        ActionRateLimiter(maxActionsPerSecond = 5, burstCapacity = 10)
}
