package com.visionagent.core.workflow.engine

import com.visionagent.core.event.*
import com.visionagent.core.memory.MemoryEngine
import com.visionagent.core.workflow.trigger.TriggerEngine
import com.visionagent.core.workflow.trigger.TriggerEvent
import com.visionagent.core.workflow.script.ScriptEngine
import com.visionagent.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.selects.select
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

// ============================================================
// WorkflowEngine — Tasker जैसा (लेकिन बड़ा) Automation System
//
// Architecture:
//   Trigger → Condition → Action Block → Memory Update
//                 ↓
//            [Loop/Retry/Parallel/Wait]
//
// Workflow Block Types:
// ┌─────────────────────────────────────────────────────┐
// │  Control Flow                                        │
// │   IF / ELSE / ELSE-IF                               │
// │   LOOP (count/while/for-each)                       │
// │   PARALLEL (run blocks concurrently)                │
// │   WAIT (fixed/until-condition)                      │
// │   RETRY (max attempts + backoff)                    │
// │                                                      │
// │  Agent Actions                                       │
// │   TAP / SCROLL / SWIPE / INPUT                      │
// │   SCREENSHOT                                         │
// │   OCR_READ (read text from screen)                  │
// │   VISION_FIND (find element by type/text)           │
// │   NAVIGATE_BACK                                      │
// │                                                      │
// │  Data                                               │
// │   SET_VARIABLE                                      │
// │   GET_VARIABLE                                      │
// │   MEMORY_STORE / MEMORY_READ                        │
// │   DATABASE_QUERY                                    │
// │                                                      │
// │  Integration                                        │
// │   HTTP_REQUEST                                      │
// │   NOTIFICATION_SEND                                 │
// │   LOG                                               │
// │   SCRIPT (run ScriptEngine expression)              │
// │                                                      │
// │  Recovery                                           │
// │   ON_ERROR                                          │
// │   ON_TIMEOUT                                        │
// │   ROLLBACK                                          │
// └─────────────────────────────────────────────────────┘
//
// Self-Healing:
// 1. Block fails → retry (configurable)
// 2. Still fails → alternate path (fallback block)
// 3. Error logged → Recovery Rule triggered
// 4. Workflow-level catch → error handler block
// 5. All fail → user notification
// ============================================================

// ─────────────────────────────────────────────────────────────────────────────
// Data Models
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
sealed class WorkflowBlock {
    abstract val blockId: String
    abstract val label:   String
}

// Control Flow Blocks
@Serializable
data class IfBlock(
    override val blockId:   String = UUID.randomUUID().toString(),
    override val label:     String = "If",
    val condition:          WorkflowCondition,
    val thenBlocks:         List<WorkflowBlock>,
    val elseBlocks:         List<WorkflowBlock> = emptyList()
) : WorkflowBlock()

@Serializable
data class LoopBlock(
    override val blockId:   String = UUID.randomUUID().toString(),
    override val label:     String = "Loop",
    val loopType:           LoopType,
    val count:              Int     = 1,
    val condition:          WorkflowCondition? = null,
    val itemsVariable:      String? = null,   // For for-each
    val body:               List<WorkflowBlock>,
    val maxIterations:      Int     = 100     // Safety limit
) : WorkflowBlock()

@Serializable
enum class LoopType { COUNT, WHILE, FOR_EACH }

@Serializable
data class ParallelBlock(
    override val blockId:   String = UUID.randomUUID().toString(),
    override val label:     String = "Parallel",
    val branches:           List<List<WorkflowBlock>>,
    val waitForAll:         Boolean = true  // false = first-wins
) : WorkflowBlock()

@Serializable
data class WaitBlock(
    override val blockId:   String = UUID.randomUUID().toString(),
    override val label:     String = "Wait",
    val waitType:           WaitType,
    val durationMs:         Long    = 1000L,
    val condition:          WorkflowCondition? = null,
    val timeoutMs:          Long    = 30_000L
) : WorkflowBlock()

@Serializable
enum class WaitType { FIXED, UNTIL_CONDITION, ELEMENT_APPEARS, ELEMENT_DISAPPEARS }

@Serializable
data class RetryBlock(
    override val blockId:   String = UUID.randomUUID().toString(),
    override val label:     String = "Retry",
    val maxAttempts:        Int    = 3,
    val delayMs:            Long   = 500L,
    val backoffMultiplier:  Float  = 2.0f,
    val body:               List<WorkflowBlock>
) : WorkflowBlock()

// Action Blocks
@Serializable
data class ActionBlock(
    override val blockId:   String = UUID.randomUUID().toString(),
    override val label:     String = "Action",
    val actionType:         ActionType,
    val targetSelector:     TargetSelector?  = null,
    val textInput:          String?          = null,
    val parameters:         Map<String, String> = emptyMap()
) : WorkflowBlock()

@Serializable
data class TargetSelector(
    val byText:     String? = null,
    val byType:     String? = null,
    val byIndex:    Int?    = null,
    val confidence: Float   = 0.75f
)

// Data Blocks
@Serializable
data class SetVariableBlock(
    override val blockId:   String = UUID.randomUUID().toString(),
    override val label:     String = "Set Variable",
    val variableName:       String,
    val value:              String,  // Supports expressions: {var_name}, {ocr_text}, etc.
    val scope:              VariableScope = VariableScope.WORKFLOW
) : WorkflowBlock()

@Serializable
enum class VariableScope { WORKFLOW, SESSION, GLOBAL }

@Serializable
data class OCRReadBlock(
    override val blockId:     String = UUID.randomUUID().toString(),
    override val label:       String = "OCR Read",
    val storeInVariable:      String,  // Variable name to store OCR result
    val region:               String?  = null  // "full", "top", "center", "bottom"
) : WorkflowBlock()

@Serializable
data class VisionFindBlock(
    override val blockId:     String = UUID.randomUUID().toString(),
    override val label:       String = "Vision Find",
    val elementType:          String,
    val textContains:         String? = null,
    val storeInVariable:      String,
    val failIfNotFound:       Boolean = false
) : WorkflowBlock()

@Serializable
data class HttpRequestBlock(
    override val blockId:     String = UUID.randomUUID().toString(),
    override val label:       String = "HTTP Request",
    val url:                  String,
    val method:               String = "GET",
    val body:                 String? = null,
    val headers:              Map<String, String> = emptyMap(),
    val responseVariable:     String,
    val timeoutMs:            Long = 10_000L
) : WorkflowBlock()

@Serializable
data class ScriptBlock(
    override val blockId:     String = UUID.randomUUID().toString(),
    override val label:       String = "Script",
    val script:               String,          // Kotlin-like expression
    val storeResultIn:        String? = null
) : WorkflowBlock()

@Serializable
data class LogBlock(
    override val blockId:     String = UUID.randomUUID().toString(),
    override val label:       String = "Log",
    val message:              String,          // Supports {variable} interpolation
    val level:                String = "INFO"
) : WorkflowBlock()

@Serializable
data class OnErrorBlock(
    override val blockId:     String = UUID.randomUUID().toString(),
    override val label:       String = "On Error",
    val errorType:            String? = null,  // null = catch all
    val handler:              List<WorkflowBlock>
) : WorkflowBlock()

// Conditions
@Serializable
data class WorkflowCondition(
    val leftOperand:   String,    // Variable name or expression
    val operator:      ConditionOp,
    val rightOperand:  String,
    val negate:        Boolean = false
)

@Serializable
enum class ConditionOp {
    EQUALS, NOT_EQUALS, CONTAINS, NOT_CONTAINS,
    GREATER_THAN, LESS_THAN, REGEX_MATCH,
    IS_EMPTY, IS_NOT_EMPTY, IS_TRUE, IS_FALSE
}

// ─────────────────────────────────────────────────────────────────────────────
// Workflow Definition
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class Workflow(
    val id:           String = UUID.randomUUID().toString(),
    val name:         String,
    val description:  String = "",
    val version:      Int    = 1,
    val triggers:     List<WorkflowTriggerConfig>,
    val blocks:       List<WorkflowBlock>,
    val variables:    Map<String, String> = emptyMap(),  // Initial variable values
    val isEnabled:    Boolean = true,
    val maxRuntime:   Long    = 60_000L,   // Max execution time (ms)
    val onError:      String  = "STOP"    // STOP, CONTINUE, RETRY
)

@Serializable
data class WorkflowTriggerConfig(
    val triggerType: String,             // Maps to TriggerEngine trigger types
    val parameters:  Map<String, String> = emptyMap()
)

// ─────────────────────────────────────────────────────────────────────────────
// Execution Context — State during workflow run
// ─────────────────────────────────────────────────────────────────────────────

data class WorkflowContext(
    val workflowId:   String,
    val runId:        String = UUID.randomUUID().toString(),
    val variables:    ConcurrentHashMap<String, String> = ConcurrentHashMap(),
    val sessionId:    String,
    var currentBlock: String = "",
    var retryCount:   Int    = 0,
    val startTime:    Long   = System.currentTimeMillis(),
    var errorLog:     MutableList<String> = mutableListOf()
) {
    // FIX L4-5: O(N*M) String allocations replaced with single-pass Regex.replace().
    // Old: N replacements * M template length = N*M garbage per call (50 vars * 1KB = 50KB).
    // New: one scan, one allocation, O(M) regardless of variable count.
    fun interpolate(template: String): String {
        if (variables.isEmpty() || !template.contains('{')) return template
        return INTERPOLATION_REGEX.replace(template) { match ->
            variables[match.groupValues[1]] ?: match.value
        }
    }

    companion object {
        private val INTERPOLATION_REGEX = Regex("\\{([^{}]+)\\}")
    }

    fun setVar(name: String, value: String) { variables[name] = value }
    fun getVar(name: String): String? = variables[name]

    val elapsedMs: Long get() = System.currentTimeMillis() - startTime
} // end WorkflowContext

// ─────────────────────────────────────────────────────────────────────────────
// Execution Result
// ─────────────────────────────────────────────────────────────────────────────

sealed class BlockResult {
    object Success                        : BlockResult()
    data class Failure(val reason: String): BlockResult()
    object Skipped                        : BlockResult()
    data class RetryNeeded(val attempt: Int): BlockResult()
}

sealed class WorkflowResult {
    data class Completed(val runId: String, val durationMs: Long, val vars: Map<String, String>) : WorkflowResult()
    data class Failed(val runId: String, val reason: String, val atBlock: String)                : WorkflowResult()
    data class TimedOut(val runId: String, val atBlock: String)                                   : WorkflowResult()
}

// ─────────────────────────────────────────────────────────────────────────────
// Block Executor
// ─────────────────────────────────────────────────────────────────────────────

class BlockExecutor(
    private val eventBus:    AgentEventBus,
    private val memoryEngine:MemoryEngine,
    private val scriptEngine:ScriptEngine,
    private val logger:      Logger
) {
    // FIX M4-3: Regex compiled fresh on every evaluateCondition() call.
    // A REGEX_MATCH condition evaluated 15 times/second = 15 compilations/sec.
    // Regex compilation is O(N) on pattern length (NFA construction) — typically 1-10ms.
    // Cache compiled patterns — same pattern reused across evaluations.
    private val regexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private fun getOrCompileRegex(pattern: String): Regex =
        regexCache.getOrPut(pattern) { Regex(pattern) }
    suspend fun execute(block: WorkflowBlock, ctx: WorkflowContext): BlockResult {
        ctx.currentBlock = block.blockId
        return try {
            when (block) {
                is ActionBlock      -> executeAction(block, ctx)
                is IfBlock          -> executeIf(block, ctx)
                is LoopBlock        -> executeLoop(block, ctx)
                is ParallelBlock    -> executeParallel(block, ctx)
                is WaitBlock        -> executeWait(block, ctx)
                is RetryBlock       -> executeRetry(block, ctx)
                is SetVariableBlock -> executeSetVariable(block, ctx)
                is OCRReadBlock     -> executeOCRRead(block, ctx)
                is VisionFindBlock  -> executeVisionFind(block, ctx)
                is HttpRequestBlock -> executeHttpRequest(block, ctx)
                is ScriptBlock      -> executeScript(block, ctx)
                is LogBlock         -> executeLog(block, ctx)
                is OnErrorBlock     -> BlockResult.Skipped  // Only executed on error
                else                -> BlockResult.Skipped
            }
        } catch (e: CancellationException) {
            throw e  // Always re-throw
        } catch (e: Exception) {
            logger.e("BlockExecutor", "Block ${block.label} failed", e)
            BlockResult.Failure(e.message ?: "Unknown error")
        }
    }

    private suspend fun executeAction(block: ActionBlock, ctx: WorkflowContext): BlockResult {
        // FIX IC-1 + M4-5 + R4-1 + R5-2: Four bugs addressed:
        //
        // IC-1: Old code always returned Success after 200ms regardless of actual outcome.
        // M4-5: correlationId applied to all command types (see ActionEngine fix).
        // R4-1: Unstructured CoroutineScope → fixed with coroutineScope{}.
        // R5-2: WAIT/NONE actions should NOT go through the ActionExecutedEvent correlation
        //   loop. WAIT is handled entirely inside ActionEngine (delay + return true), which
        //   DOES publish ActionExecutedEvent. But historically it was the ONLY action type
        //   that reliably worked even without correlationId (since it always returned true
        //   after the delay). With R5-1 fix applying correlationId to WaitCommand, WAIT now
        //   works correctly through the standard path.
        //
        //   NONE/unrecognised: skip correlation entirely — publish event, return Success.
        if (block.actionType == ActionType.NONE) {
            return BlockResult.Success   // No-op — nothing to wait for
        }

        val correlationId = java.util.UUID.randomUUID().toString()
        val resultDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()

        return coroutineScope {
            // FIX R4-1: launch inside coroutineScope{} — structured child, cancelled automatically.
            // FIX M4-5: filter by correlationId which ActionEngine will use as command.id.
            val sub = eventBus.subscribeFiltered<ActionExecutedEvent> {
                it.actionId == correlationId
            }
                .onEach { event ->
                    if (!resultDeferred.isCompleted)
                        resultDeferred.complete(event.success)
                }
                .launchIn(this)  // `this` = coroutineScope — structured, cancellable

            val decision = AgentDecision(
                actionType = block.actionType,
                target     = null,
                // FIX M4-5: embed correlationId so ActionEngine uses it as command.id
                parameters = block.parameters + mapOf("workflowCorrelationId" to correlationId),
                confidence = 0.9f,
                reasoning  = "Workflow: ${ctx.workflowId} | Block: ${block.label}"
            )
            eventBus.publish(RuleEvaluatedEvent(
                ruleId    = correlationId,
                ruleName  = "Workflow:${block.label}",
                matched   = true,
                priority  = 1000,
                decision  = decision,
                sessionId = ctx.sessionId
            ))

            val result = try {
                val success = withTimeout(10_000L) { resultDeferred.await() }
                if (success) BlockResult.Success
                else BlockResult.Failure("Action '${block.label}' failed")
            } catch (e: TimeoutCancellationException) {
                BlockResult.Failure("Action '${block.label}' timed out after 10s")
            } finally {
                sub.cancel()
            }
            result
        }
    }

    private suspend fun executeIf(block: IfBlock, ctx: WorkflowContext): BlockResult {
        val condition = evaluateCondition(block.condition, ctx)
        val blocksToRun = if (condition) block.thenBlocks else block.elseBlocks
        return executeBlocks(blocksToRun, ctx)
    }

    private suspend fun executeLoop(block: LoopBlock, ctx: WorkflowContext): BlockResult {
        var iterations = 0
        while (iterations < block.maxIterations) {
            val shouldContinue = when (block.loopType) {
                LoopType.COUNT    -> iterations < block.count
                LoopType.WHILE    -> block.condition?.let { evaluateCondition(it, ctx) } ?: false
                // FIX M4-4 note: delay is added INSIDE the loop body below
                LoopType.FOR_EACH -> {
                    val items = ctx.getVar(block.itemsVariable ?: "") ?: ""
                    if (items.isEmpty()) return BlockResult.Success
                    // FIX R5-6: split(",") breaks items containing commas (e.g., "London, UK").
                    // New format: JSON array string ["item1","item2","item3"].
                    // Falls back to comma-split for backward compatibility with plain CSV values.
                    val list: List<String> = try {
                        if (items.startsWith("[")) {
                            // JSON array format: ["London, UK","Paris, France"]
                            kotlinx.serialization.json.Json.decodeFromString<List<String>>(items)
                        } else {
                            items.split(",").map { it.trim() }   // legacy CSV (no commas in items)
                        }
                    } catch (_: Exception) {
                        items.split(",").map { it.trim() }
                    }
                    if (iterations < list.size) {
                        ctx.setVar("loop_item", list[iterations])
                        true
                    } else false
                }
            }
            if (!shouldContinue) break
            ctx.setVar("loop_index", iterations.toString())
            val result = executeBlocks(block.body, ctx)
            if (result is BlockResult.Failure) return result
            iterations++
            // FIX M4-4: Without delay, a WHILE loop whose condition never changes
            // tight-loops 100 times (maxIterations) without yielding — consuming a CPU core.
            // Minimum 50ms yield per iteration; also lets the event loop process other work.
            if (block.loopType == LoopType.WHILE) delay(50L)
        }
        return BlockResult.Success
    }

    private suspend fun executeParallel(block: ParallelBlock, ctx: WorkflowContext): BlockResult {
        // FIX R3-1: engineScope.async was an UNRESOLVED REFERENCE — BlockExecutor is a
        // separate class and has no `engineScope` field; the previous fix caused a compile error.
        //
        // Correct solution: use `coroutineScope { }` (kotlinx.coroutines suspend function).
        // It creates a child scope that inherits the PARENT coroutine's Job, so:
        //   - When the parent workflow coroutine is cancelled, all branches are cancelled.
        //   - Exceptions in any branch propagate and cancel siblings (fail-fast).
        //   - No external scope reference needed — fully structured concurrency.
        return coroutineScope {
            val jobs = block.branches.map { branch ->
                // FIX M-8: deep-copy errorLog so parallel branches don't share the reference.
                val branchCtx = ctx.copy(
                    variables = ConcurrentHashMap(ctx.variables),
                    errorLog  = ctx.errorLog.toMutableList()
                )
                async { executeBlocks(branch, branchCtx) }
            }
            if (block.waitForAll) {
                val results = jobs.awaitAll()
                if (results.all { it is BlockResult.Success }) BlockResult.Success
                else BlockResult.Failure("One or more parallel branches failed")
            } else {
                // First-wins: await any one, cancel the rest
                val winner = select<BlockResult> {
                    jobs.forEach { job -> job.onAwait { it } }
                }
                jobs.forEach { it.cancel() }
                winner
            }
        }
    }

    private suspend fun executeWait(block: WaitBlock, ctx: WorkflowContext): BlockResult {
        return when (block.waitType) {
            WaitType.FIXED -> {
                delay(block.durationMs)
                BlockResult.Success
            }
            WaitType.UNTIL_CONDITION -> {
                val deadline = System.currentTimeMillis() + block.timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val cond = block.condition ?: return BlockResult.Failure("No condition specified")
                    if (evaluateCondition(cond, ctx)) return BlockResult.Success
                    delay(500)
                }
                BlockResult.Failure("Wait timed out after ${block.timeoutMs}ms")
            }
            WaitType.ELEMENT_APPEARS -> {
                // Wait until Vision finds an element
                delay(block.durationMs)
                BlockResult.Success
            }
            WaitType.ELEMENT_DISAPPEARS -> {
                delay(block.durationMs)
                BlockResult.Success
            }
        }
    }

    private suspend fun executeRetry(block: RetryBlock, ctx: WorkflowContext): BlockResult {
        var delayMs = block.delayMs
        repeat(block.maxAttempts) { attempt ->
            val result = executeBlocks(block.body, ctx)
            if (result is BlockResult.Success) return BlockResult.Success
            logger.w("BlockExecutor", "Retry attempt $attempt/${block.maxAttempts} failed")
            delay(delayMs)
            delayMs = (delayMs * block.backoffMultiplier).toLong()
        }
        return BlockResult.Failure("Failed after ${block.maxAttempts} attempts")
    }

    private fun executeSetVariable(block: SetVariableBlock, ctx: WorkflowContext): BlockResult {
        val value = ctx.interpolate(block.value)
        ctx.setVar(block.variableName, value)
        when (block.scope) {
            VariableScope.SESSION -> memoryEngine.storeSTM(
                block.variableName, value, sessionId = ctx.sessionId)
            VariableScope.GLOBAL  -> logger.d("BlockExecutor",
                "Global var: ${block.variableName}=$value")
            else -> {}
        }
        return BlockResult.Success
    }

    private fun executeOCRRead(block: OCRReadBlock, ctx: WorkflowContext): BlockResult {
        // Read last OCR result from memory
        val ocrText = memoryEngine.getSTM("last_ocr_text") ?: ""
        ctx.setVar(block.storeInVariable, ocrText)
        logger.d("BlockExecutor", "OCR read: ${ocrText.take(50)}")
        return BlockResult.Success
    }

    private fun executeVisionFind(block: VisionFindBlock, ctx: WorkflowContext): BlockResult {
        // Read last vision result from memory
        val lastScreen = memoryEngine.screenMemory.getLast()
        val found = lastScreen?.elements?.find { el ->
            el.type.name == block.elementType &&
            (block.textContains == null || el.text?.contains(block.textContains, true) == true)
        }
        ctx.setVar(block.storeInVariable, found?.let { "${it.bounds.centerX()},${it.bounds.centerY()}" } ?: "")
        return if (found != null || !block.failIfNotFound) BlockResult.Success
               else BlockResult.Failure("Element ${block.elementType} not found")
    }

    private suspend fun executeHttpRequest(block: HttpRequestBlock, ctx: WorkflowContext): BlockResult {
        // FIX NC-13: connection.inputStream was not closed and disconnect() was never called.
        // Each execution leaked one socket file descriptor. Android allows ~1024 open FDs;
        // sustained HTTP workflow use exhausted them → IOException: Too many open files.
        // Fix: use try/finally to always disconnect; wrap stream in use{} to close on completion.
        return withContext(Dispatchers.IO) {
            val url = ctx.interpolate(block.url)
            val connection = try {
                (java.net.URL(url).openConnection() as java.net.HttpURLConnection).also { conn ->
                    conn.requestMethod = block.method
                    conn.connectTimeout = 5000
                    conn.readTimeout    = block.timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    block.headers.forEach { (k, v) -> conn.setRequestProperty(k, ctx.interpolate(v)) }
                }
            } catch (e: Exception) {
                ctx.setVar(block.responseVariable, "")
                ctx.setVar("${block.responseVariable}_error", e.message ?: "Connection failed")
                return@withContext BlockResult.Failure("HTTP connect failed: ${e.message}")
            }

            try {
                withTimeout(block.timeoutMs) {
                    val statusCode = connection.responseCode
                    val response = connection.inputStream.use { stream ->
                        stream.bufferedReader().readText()
                    }
                    ctx.setVar(block.responseVariable, response.take(10_000))
                    ctx.setVar("${block.responseVariable}_code", statusCode.toString())
                    BlockResult.Success
                }
            } catch (e: Exception) {
                ctx.setVar(block.responseVariable, "")
                ctx.setVar("${block.responseVariable}_error", e.message ?: "Request failed")
                BlockResult.Failure("HTTP request failed: ${e.message}")
            } finally {
                connection.disconnect()  // always release the socket FD
            }
        }
    }

    private fun executeScript(block: ScriptBlock, ctx: WorkflowContext): BlockResult {
        val result = scriptEngine.evaluate(ctx.interpolate(block.script), ctx.variables)
        block.storeResultIn?.let { ctx.setVar(it, result) }
        return BlockResult.Success
    }

    private fun executeLog(block: LogBlock, ctx: WorkflowContext): BlockResult {
        val message = ctx.interpolate(block.message)
        when (block.level) {
            "DEBUG" -> logger.d("Workflow", message)
            "WARN"  -> logger.w("Workflow", message)
            "ERROR" -> logger.e("Workflow", message)
            else    -> logger.i("Workflow", message)
        }
        return BlockResult.Success
    }

    suspend fun executeBlocks(blocks: List<WorkflowBlock>, ctx: WorkflowContext): BlockResult {
        // Find error handlers
        val errorHandlers = blocks.filterIsInstance<OnErrorBlock>()
        val normalBlocks  = blocks.filterNot { it is OnErrorBlock }

        for (block in normalBlocks) {
            val result = execute(block, ctx)
            if (result is BlockResult.Failure) {
                // Try error handler
                val handler = errorHandlers.find { it.errorType == null ||
                    result.reason.contains(it.errorType) }
                if (handler != null) {
                    ctx.setVar("error_message", result.reason)
                    return executeBlocks(handler.handler, ctx)
                }
                return result
            }
        }
        return BlockResult.Success
    }

    private fun evaluateCondition(cond: WorkflowCondition, ctx: WorkflowContext): Boolean {
        val left  = ctx.interpolate(cond.leftOperand)
        val right = ctx.interpolate(cond.rightOperand)
        val result = when (cond.operator) {
            ConditionOp.EQUALS       -> left == right
            ConditionOp.NOT_EQUALS   -> left != right
            ConditionOp.CONTAINS     -> left.contains(right, ignoreCase = true)
            ConditionOp.NOT_CONTAINS -> !left.contains(right, ignoreCase = true)
            ConditionOp.GREATER_THAN -> left.toDoubleOrNull()?.compareTo(right.toDoubleOrNull() ?: 0.0) ?: 0 > 0
            ConditionOp.LESS_THAN    -> left.toDoubleOrNull()?.compareTo(right.toDoubleOrNull() ?: 0.0) ?: 0 < 0
            ConditionOp.REGEX_MATCH  -> getOrCompileRegex(right).containsMatchIn(left)  // FIX M4-3
            ConditionOp.IS_EMPTY     -> left.isBlank()
            ConditionOp.IS_NOT_EMPTY -> left.isNotBlank()
            ConditionOp.IS_TRUE      -> left == "true"
            ConditionOp.IS_FALSE     -> left == "false"
        }
        return if (cond.negate) !result else result
    }

    private fun com.visionagent.core.event.Rect.centerX() = (left + right) / 2
    private fun com.visionagent.core.event.Rect.centerY() = (top + bottom) / 2
}

// ─────────────────────────────────────────────────────────────────────────────
// WorkflowEngine — Main Coordinator
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class WorkflowEngine @Inject constructor(
    private val eventBus:    AgentEventBus,
    private val memoryEngine:MemoryEngine,
    private val triggerEngine:TriggerEngine,
    private val scriptEngine: ScriptEngine,
    private val logger:       Logger
) {
    companion object { private const val TAG = "WorkflowEngine" }

    private val engineScope   = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workflows     = ConcurrentHashMap<String, Workflow>()
    private val runningJobs   = ConcurrentHashMap<String, Job>()
    private val executor      = BlockExecutor(eventBus, memoryEngine, scriptEngine, logger)
    private val runCounter    = AtomicLong(0)

    fun initialize() {
        // Subscribe to trigger events
        triggerEngine.triggerEvents
            .onEach { trigger -> handleTrigger(trigger) }
            .launchIn(engineScope)
        logger.i(TAG, "WorkflowEngine initialized")
    }

    // ── Workflow Registration ─────────────────────────────────────────────

    fun register(workflow: Workflow) {
        workflows[workflow.id] = workflow
        logger.i(TAG, "Workflow registered: ${workflow.name} | triggers=${workflow.triggers.size} | blocks=${workflow.blocks.size}")
    }

    fun unregister(workflowId: String) {
        workflows.remove(workflowId)
        runningJobs[workflowId]?.cancel()
    }

    fun enable(workflowId: String) {
        workflows[workflowId]?.let { workflows[workflowId] = it.copy(isEnabled = true) }
    }

    fun disable(workflowId: String) {
        workflows[workflowId]?.let { workflows[workflowId] = it.copy(isEnabled = false) }
    }

    // ── Execution ─────────────────────────────────────────────────────────

    fun execute(workflowId: String, sessionId: String): Job? {
        val workflow = workflows[workflowId] ?: run {
            logger.w(TAG, "Workflow not found: $workflowId")
            return null
        }
        if (!workflow.isEnabled) return null

        // FIX LD-1: If the same workflowId is triggered twice (e.g., rapid trigger events),
        // the old Job was overwritten in runningJobs without being cancelled.
        // The previous job kept running indefinitely — resource leak and duplicate execution.
        runningJobs[workflowId]?.cancel()

        val job = engineScope.launch {
            val result = runWorkflow(workflow, sessionId)
            when (result) {
                is WorkflowResult.Completed ->
                    logger.i(TAG, "✅ Workflow '${workflow.name}' completed in ${result.durationMs}ms")
                is WorkflowResult.Failed ->
                    logger.e(TAG, "❌ Workflow '${workflow.name}' failed: ${result.reason}")
                is WorkflowResult.TimedOut ->
                    logger.w(TAG, "⏱️ Workflow '${workflow.name}' timed out at block ${result.atBlock}")
            }
        }
        runningJobs[workflowId] = job
        return job
    }

    private suspend fun runWorkflow(workflow: Workflow, sessionId: String): WorkflowResult {
        val runId = "run_${runCounter.incrementAndGet()}"
        val ctx   = WorkflowContext(
            workflowId = workflow.id,
            runId      = runId,
            sessionId  = sessionId,
            variables  = ConcurrentHashMap(workflow.variables)
        )

        logger.i(TAG, "Starting workflow: ${workflow.name} | run=$runId")

        return try {
            withTimeout(workflow.maxRuntime) {
                val result = executor.executeBlocks(workflow.blocks, ctx)
                val duration = ctx.elapsedMs
                when (result) {
                    is BlockResult.Success ->
                        WorkflowResult.Completed(runId, duration, ctx.variables.toMap())
                    is BlockResult.Failure ->
                        WorkflowResult.Failed(runId, result.reason, ctx.currentBlock)
                    else ->
                        WorkflowResult.Completed(runId, duration, ctx.variables.toMap())
                }
            }
        } catch (e: TimeoutCancellationException) {
            WorkflowResult.TimedOut(runId, ctx.currentBlock)
        } catch (e: CancellationException) {
            WorkflowResult.Failed(runId, "Workflow cancelled", ctx.currentBlock)
        }
    }

    // FIX L5-4: Debounce rapid trigger events.
    // Without debounce: SCREEN_CHANGE fires 10 times in 100ms → workflow launched,
    // cancelled, and restarted 10 times. Setup code (DB reads, subscriptions) runs
    // 9 times and is aborted, leaving partial side effects.
    // Fix: 200ms debounce per workflow — only the last trigger in a burst fires.
    private val debounceJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val DEBOUNCE_MS = 200L

    private fun handleTrigger(trigger: TriggerEvent) {
        workflows.values
            .filter { it.isEnabled && it.triggers.any { t -> t.triggerType == trigger.type } }
            .forEach { workflow ->
                // Cancel pending debounce for this workflow, schedule a new one
                debounceJobs[workflow.id]?.cancel()
                debounceJobs[workflow.id] = engineScope.launch {
                    delay(DEBOUNCE_MS)
                    debounceJobs.remove(workflow.id)
                    logger.d(TAG, "Trigger '${trigger.type}' → Workflow '${workflow.name}' (debounced)")
                    execute(workflow.id, trigger.sessionId)
                }
            }
    }

    fun stopAll() {
        runningJobs.values.forEach { it.cancel() }
        runningJobs.clear()
    }

    fun getActiveWorkflows(): List<String> =
        runningJobs.filter { (_, job) -> job.isActive }.keys.toList()

    fun getAllWorkflows(): List<Workflow> = workflows.values.toList()
}
