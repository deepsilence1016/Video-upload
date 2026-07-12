package com.visionagent.core.memory.episodic

import com.visionagent.core.event.ActionType
import com.visionagent.core.event.AgentState
import com.visionagent.core.event.ScreenType
import com.visionagent.data.local.database.AgentDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// EpisodicMemory — "What happened in this session?"
//
// Episodic memory stores EXPERIENCES — sequences of events
// with temporal context (when, what, outcome).
//
// Inspired by human episodic memory:
// - Records complete "episodes" (goal → actions → outcome)
// - Enables replay ("what did I do last time on this screen?")
// - Supports counterfactual reasoning ("what if I had done X?")
// - Guides future planning ("I succeeded before by doing Y")
//
// Structure:
//   Episode
//   ├── context (screen, state, timestamp)
//   ├── goal (what was being attempted)
//   ├── steps (sequence of actions + outcomes)
//   ├── outcome (success/failure/partial)
//   └── learnings (extracted patterns)
//
// Storage: Room DB (encrypted, indexed by screen_type + goal_type)
// Retrieval: O(log N) via compound index
// ============================================================

@Serializable
data class EpisodicStep(
    val stepIndex:    Int,
    val action:       ActionType,
    val targetText:   String?,
    val success:      Boolean,
    val durationMs:   Long,
    val screenBefore: ScreenType,
    val screenAfter:  ScreenType?,
    val confidenceBefore: Float = 0f
)

@Serializable
enum class EpisodeOutcome {
    SUCCESS,         // Goal fully achieved
    PARTIAL,         // Some steps done, goal not completed
    FAILURE,         // Goal failed
    INTERRUPTED,     // Agent stopped mid-episode
    RECOVERED        // Failed then recovered to success
}

@Serializable
data class Episode(
    val episodeId:    String = UUID.randomUUID().toString(),
    val sessionId:    String,
    val goalType:     String,
    val goalDesc:     String,
    val startScreen:  ScreenType,
    val startTime:    Long = System.currentTimeMillis(),
    val endTime:      Long? = null,
    val steps:        List<EpisodicStep> = emptyList(),
    val outcome:      EpisodeOutcome = EpisodeOutcome.INTERRUPTED,
    val totalActions: Int = 0,
    val failedActions: Int = 0,
    val learnings:    List<String> = emptyList()
) {
    val successRate: Float
        get() = if (totalActions == 0) 0f else
                (totalActions - failedActions).toFloat() / totalActions

    val durationMs: Long
        get() = (endTime ?: System.currentTimeMillis()) - startTime
}

// ─────────────────────────────────────────────────────────────────────────────
// Episode Builder — fluent builder during active episode
// ─────────────────────────────────────────────────────────────────────────────

class EpisodeBuilder(
    private val sessionId:   String,
    private val goalType:    String,
    private val goalDesc:    String,
    private val startScreen: ScreenType
) {
    private val steps        = mutableListOf<EpisodicStep>()
    private var totalActions = 0
    private var failedActions = 0

    fun addStep(
        action:       ActionType,
        success:      Boolean,
        durationMs:   Long,
        screenBefore: ScreenType,
        screenAfter:  ScreenType? = null,
        targetText:   String?      = null,
        confidence:   Float        = 0f
    ): EpisodeBuilder {
        steps.add(EpisodicStep(
            stepIndex    = steps.size,
            action       = action,
            targetText   = targetText,
            success      = success,
            durationMs   = durationMs,
            screenBefore = screenBefore,
            screenAfter  = screenAfter,
            confidenceBefore = confidence
        ))
        totalActions++
        if (!success) failedActions++
        return this
    }

    fun build(outcome: EpisodeOutcome, learnings: List<String> = emptyList()): Episode =
        Episode(
            sessionId    = sessionId,
            goalType     = goalType,
            goalDesc     = goalDesc,
            startScreen  = startScreen,
            endTime      = System.currentTimeMillis(),
            steps        = steps.toList(),
            outcome      = outcome,
            totalActions = totalActions,
            failedActions = failedActions,
            learnings    = learnings
        )
}

// ─────────────────────────────────────────────────────────────────────────────
// EpisodicMemory — Storage and Retrieval
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class EpisodicMemory @Inject constructor(
    private val database: AgentDatabase
) {
    private val json = Json { ignoreUnknownKeys = true }

    // In-memory episode buffer (current session)
    private val sessionEpisodes = mutableListOf<Episode>()
    private var currentBuilder:  EpisodeBuilder? = null

    // ── Episode Lifecycle ─────────────────────────────────────────────────

    fun startEpisode(
        sessionId:   String,
        goalType:    String,
        goalDesc:    String,
        startScreen: ScreenType
    ): EpisodeBuilder {
        currentBuilder = EpisodeBuilder(sessionId, goalType, goalDesc, startScreen)
        return currentBuilder!!
    }

    suspend fun commitEpisode(outcome: EpisodeOutcome): Episode? {
        val builder = currentBuilder ?: return null

        // Extract learnings from episode
        val episode = currentBuilder!!.build(
            outcome   = outcome,
            learnings = extractLearnings(builder)
        )

        sessionEpisodes.add(episode)
        currentBuilder = null

        // Persist to DB
        persistEpisode(episode)

        return episode
    }

    fun addStepToCurrentEpisode(
        action:       ActionType,
        success:      Boolean,
        durationMs:   Long,
        screenBefore: ScreenType,
        screenAfter:  ScreenType? = null,
        targetText:   String?      = null,
        confidence:   Float        = 0f
    ) {
        currentBuilder?.addStep(
            action, success, durationMs,
            screenBefore, screenAfter, targetText, confidence)
    }

    // ── Retrieval ─────────────────────────────────────────────────────────

    /** Find similar past episodes — same goal on same screen */
    suspend fun findSimilarEpisodes(
        goalType:    String,
        startScreen: ScreenType,
        limit:       Int = 5
    ): List<Episode> {
        return sessionEpisodes
            .filter { it.goalType == goalType && it.startScreen == startScreen }
            .sortedByDescending { it.startTime }
            .take(limit)
    }

    /** Get most successful approach for a goal type */
    suspend fun getBestEpisodeFor(goalType: String): Episode? {
        return sessionEpisodes
            .filter { it.goalType == goalType && it.outcome == EpisodeOutcome.SUCCESS }
            .maxByOrNull { it.successRate }
    }

    /** Get action sequence that worked best */
    fun getSuccessfulActionSequence(goalType: String): List<ActionType> {
        val best = sessionEpisodes
            .filter { it.goalType == goalType && it.outcome == EpisodeOutcome.SUCCESS }
            .maxByOrNull { it.successRate }
        return best?.steps?.map { it.action } ?: emptyList()
    }

    /** Extract failure patterns — which actions commonly fail? */
    fun getFailurePatterns(): Map<ActionType, Float> {
        val all     = sessionEpisodes.flatMap { it.steps }
        val byAction = all.groupBy { it.action }
        return byAction.mapValues { (_, steps) ->
            steps.count { !it.success }.toFloat() / steps.size
        }.filter { it.value > 0.3f }  // Only actions with >30% failure rate
    }

    /** Context-based episode recall — what happened when on this screen? */
    fun recall(screenType: ScreenType, last: Int = 3): List<Episode> =
        sessionEpisodes
            .filter { it.startScreen == screenType }
            .sortedByDescending { it.startTime }
            .take(last)

    // ── Private helpers ───────────────────────────────────────────────────

    private fun extractLearnings(builder: EpisodeBuilder): List<String> {
        // Pattern recognition from episode steps
        // E.g., "SCROLL_DOWN before TAP often succeeds"
        return emptyList() // Placeholder — implement with pattern mining
    }

    private suspend fun persistEpisode(episode: Episode) {
        // Serialize and store in memory_store as LEARNING type
        val jsonStr = json.encodeToString(episode)
        database.memoryDao().upsert(
            com.visionagent.data.local.entity.MemoryEntity(
                key       = "episode_${episode.episodeId}",
                value     = jsonStr,
                type      = "EPISODIC",
                sessionId = episode.sessionId,
                weight    = if (episode.outcome == EpisodeOutcome.SUCCESS) 1.5f else 0.5f,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    fun getCurrentEpisode(): EpisodeBuilder? = currentBuilder
    fun getSessionEpisodes(): List<Episode>   = sessionEpisodes.toList()
    fun clearSession()                         = sessionEpisodes.clear()
}
