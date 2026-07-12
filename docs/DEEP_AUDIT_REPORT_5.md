# Deep Audit — Round 5 (Maximum Depth)
## Vision Agent Framework — Exhaustive Execution Trace
**Date:** 2026-07-09 | All previous fixes verified as present and correct.

---

## SEVERITY
🔴 CRITICAL | 🟠 HIGH | 🟡 MEDIUM | 🔵 LOW

---

# PART 1 — CRITICAL / HIGH

---

## 🔴 R5-1: `enqueueDecision()` — `correlationId` Only Applied to `TapCommand`. All Other Commands Still Get Random UUID — M4-5 Fix Incomplete

**File:** `ActionEngine.kt` **Lines:** 235–310

**Mental execution trace:**

```kotlin
val correlationId: String =
    (decision.parameters["workflowCorrelationId"] as? String)
        ?: java.util.UUID.randomUUID().toString()

ActionType.TAP -> TapCommand(id = correlationId, ...)   // ✅ correlationId used
ActionType.LONG_PRESS -> LongPressCommand(              // ❌ no id parameter!
    x = ..., y = ..., sessionId = sessionId
)
ActionType.DOUBLE_TAP -> DoubleTapCommand(              // ❌ no id parameter!
    x = ..., y = ..., sessionId = sessionId
)
ActionType.SCROLL_DOWN -> ScrollCommand(                // ❌ default random UUID
    direction = ..., startX = 540f, ...
)
ActionType.WAIT -> WaitCommand(durationMs = ..., ...)   // ❌ default random UUID
ActionType.NAVIGATE_BACK -> NavigateBackCommand(...)    // ❌ default random UUID
```

Only `TapCommand` has `id = correlationId`. Every other action type uses its data class default `id = UUID.randomUUID().toString()`. The subscription in `executeAction()` filters `it.actionId == correlationId` — but for non-TAP workflow actions, the published `ActionExecutedEvent.actionId` is always a **different** random UUID.

**Result:** Workflow steps for `LONG_PRESS`, `DOUBLE_TAP`, `SCROLL`, `SWIPE`, `WAIT`, `NAVIGATE_BACK` still always time out after 10 seconds. Only `TAP` works correctly. WAIT is also special — it doesn't go through ActionEngine at all so no correlation needed, but it currently tries to wait for an `ActionExecutedEvent` that never comes.

**Fix:** All command constructors that represent workflow-triggered actions must receive `correlationId`. `WaitCommand` and simple internal actions should bypass the correlation entirely via direct completion.

---

## 🔴 R5-2: `executeAction()` — `WAIT` Action Type Should Not Go Through `ActionEngine` Correlation at All

**File:** `WorkflowEngine.kt` + `ActionEngine.kt`

When a workflow's `ActionBlock` has `actionType = ActionType.WAIT`, the flow is:

1. `executeAction()` creates `correlationId`, subscribes for `ActionExecutedEvent { it.actionId == correlationId }`
2. Publishes `RuleEvaluatedEvent(decision = AgentDecision(actionType = WAIT))`
3. `ActionEngine.enqueueDecision()` receives it → creates `WaitCommand(id = UUID.randomUUID())`
4. `WaitCommand` executes: `delay(durationMs)`, returns `true`
5. `ActionExecutedEvent` published with `actionId = command.id` (random UUID, not correlationId)
6. The subscription filter never matches
7. `withTimeout(10_000L)` expires → `BlockResult.Failure("Action timed out")`

So `WAIT` blocks always fail with timeout instead of completing after the wait duration.

**Fix:** For `WAIT` and `NONE` action types, `executeAction()` should directly delay and return `BlockResult.Success` without the correlation loop.

---

## 🔴 R5-3: `ScreenCaptureEngine.frameChannel` — Dead Code Channel Never Closed Properly Leaks Coroutines

**File:** `ScreenCaptureEngine.kt` **Lines:** 116–117, 400

```kotlin
private val frameChannel = Channel<CapturedFrame>(
    capacity = Channel.BUFFERED,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)
val frameFlow: Flow<CapturedFrame> = frameChannel.receiveAsFlow()
```

`frameChannel` is created and exposed as `frameFlow`. The R4-6 fix removed `frameChannel.trySend()` from the hot path — so the channel is **never written to**. But `stopCapture()` now calls `frameChannel.close()`.

The issue: `frameFlow` = `frameChannel.receiveAsFlow()`. Any coroutine that calls `frameFlow.collect()` (even future code) will block forever — the channel is never sent to, so `collect()` suspends indefinitely until `close()` is called. After `close()`, `collect()` exits. This is correct behavior.

But `frameChannel` itself is a `Channel` created at class construction. It can only be closed **once**. If `stopCapture()` is called, then `startCapture()` is called again (restart scenario), `frameChannel` is already closed and `frameChannel.trySend()` would return `ChannelResult.closed()` silently. If future code ever writes to it again, it silently drops all frames.

**More critically:** If `stopCapture()` → `startCapture()` cycle happens, the `engineScope` uses `cancelChildren()` (not `cancel()`), so the scope stays alive. But `frameChannel.close()` is permanent — the channel cannot be reopened. The architecture breaks on restart.

**Fix:** Remove `frameChannel` and `frameFlow` entirely (they're already dead code since R4-6 removed the only write). Or replace with a new `Channel` on each `startCapture()`.

---

## 🔴 R5-4: `SelfDiagnosticEngine` — `ruleRegistrySize` and `activePluginCount` Are Plain `var`, Set from External Threads Without `@Volatile`

**File:** `SelfDiagnosticEngine.kt` **Lines:** 156–157, 691–692

```kotlin
private var ruleRegistrySize = 0      // ← no @Volatile
private var activePluginCount = 0     // ← no @Volatile

fun setRuleCount(count: Int) { ruleRegistrySize = count }     // called from RuleEngine thread
fun setPluginCount(count: Int) { activePluginCount = count }  // called from PluginRegistry thread
```

`checkRuleEngine()` and `checkPlugins()` read these from `diagScope` (Dispatchers.Default). `setRuleCount()` is called from `RuleEngine.initialize()` which runs on whatever thread calls it. Without `@Volatile`, the diagnostic reads stale `0` values indefinitely, reporting "No rules registered" even when rules are loaded.

**Fix:** `@Volatile private var ruleRegistrySize = 0` and `@Volatile private var activePluginCount = 0`.

---

## 🔴 R5-5: `TriggerEngine.batteryReceiver` — Registered but Context Leak: `BroadcastReceiver` Registered on Context Without Checking Double-Registration

**File:** `TriggerEngine.kt` **Lines:** 144, 163, 212–214

```kotlin
private var batteryReceiver: BroadcastReceiver? = null

private fun setupBatteryTrigger() {
    batteryReceiver = object : BroadcastReceiver() { ... }
    context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
}

fun stop() {
    triggerScope.cancel()
    batteryReceiver?.let { context.unregisterReceiver(it) }
}
```

If `initialize()` is called twice (e.g., on session restart), `setupBatteryTrigger()` is called twice:
1. First call: creates receiver-A, registers it
2. Second call: creates receiver-B, registers it. `batteryReceiver` now points to receiver-B.
3. Old receiver-A is **still registered** but reference is lost → leaked BroadcastReceiver
4. `stop()` only unregisters receiver-B

Every re-initialization leaks one BroadcastReceiver. Each leaked receiver fires `fire()` on every battery event, polluting the trigger stream with duplicate events.

**Fix:** Unregister existing receiver before registering new one:
```kotlin
private fun setupBatteryTrigger() {
    batteryReceiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
    batteryReceiver = object : BroadcastReceiver() { ... }
    context.registerReceiver(batteryReceiver, ...)
}
```

---

## 🟠 R5-6: `WorkflowEngine.executeLoop()` — `FOR_EACH` Split on `","` Breaks Items Containing Commas

**File:** `WorkflowEngine.kt` **Lines:** 434–442

```kotlin
LoopType.FOR_EACH -> {
    val items = ctx.getVar(block.itemsVariable ?: "") ?: ""
    if (items.isEmpty()) return BlockResult.Success
    val list = items.split(",")     // ← splits on comma
    if (iterations < list.size) {
        ctx.setVar("loop_item", list[iterations])
        true
    } else false
}
```

If a variable contains comma-separated items like `"apple,banana,orange"`, this works. But if items contain commas themselves — e.g., `"London, UK,Paris, France"` — split produces `["London", " UK", "Paris", " France"]` instead of `["London, UK", "Paris, France"]`.

No escaping mechanism exists. Any item with a comma is silently corrupted. Also: `items.split(",")` creates a new `List<String>` on every loop iteration (called `maxIterations` times) — 100 allocations for a 100-iteration loop.

**Severity: MEDIUM** — Functional bug for any list item containing commas.

---

## 🟠 R5-7: `ActionEngine.enqueueDecision()` — Hardcoded Scroll Coordinates `540f, 1200f` — Breaks on Non-1080p Screens

**File:** `ActionEngine.kt` **Lines:** 263–280

```kotlin
ActionType.SCROLL_DOWN -> ScrollCommand(
    direction = ScrollDirection.DOWN,
    startX = 540f, startY = 1200f,   // ← hardcoded for 1080×1920 screen
    endX = 540f, endY = 400f,
    sessionId = sessionId
)
ActionType.SCROLL_UP -> ScrollCommand(
    direction = ScrollDirection.UP,
    startX = 540f, startY = 400f,
    endX = 540f, endY = 1200f,
    sessionId = sessionId
)
ActionType.SWIPE_LEFT -> SwipeCommand(
    startX = 900f, startY = 800f,   // ← hardcoded
    endX = 100f, endY = 800f,
    sessionId = sessionId
)
```

On a 1440×3200 Pixel 6 Pro:
- Screen width = 1440, height = 3200
- Correct scroll: startY ≈ 2400 (75% of height), endY ≈ 800 (25%)
- Actual: startY = 1200, endY = 400 → scrolls only the top 37% of the screen

On a 720×1560 budget device:
- startY = 1200 > screen height 1560 × ... actually still on screen but only just
- Swipe startX = 900 on a 720px screen → swipe starts **outside the screen** → gesture silently fails

**Fix:** Use `ScreenCaptureEngine.displayWidth/displayHeight` to compute proportional coordinates at dispatch time.

---

## 🟠 R5-8: `SelfDiagnosticEngine.checkJNILibraries()` — `System.loadLibrary()` in Diagnostic Throws `UnsatisfiedLinkError` But Continues as if Library Might Be Loaded

**File:** `SelfDiagnosticEngine.kt` **Lines:** 428–445

```kotlin
val loaded = try {
    System.loadLibrary(lib)    // ← if library not present, throws UnsatisfiedLinkError
    true
} catch (e: UnsatisfiedLinkError) {
    jniLoadStatus[lib] ?: false   // ← falls back to stored status
}
```

`jniLoadStatus` is populated by `setJNILoaded(lib, loaded)` which is never called anywhere in the codebase — there are no callers of `setJNILoaded()`. So if the library failed to load initially, `jniLoadStatus[lib]` is always `null`, and the fallback returns `false`. This part works correctly.

But if `System.loadLibrary(lib)` succeeds — meaning it was already loaded — it returns normally and `loaded = true`. This means the diagnostic always returns `true` for all libraries regardless of whether native initialization succeeded. A library that loaded but whose `vision_initialize()` returned `false` (OpenCV missing) would still report `OK`.

**Severity: LOW** — Misleading diagnostic, not a crash.

---

## 🟠 R5-9: `PerformanceTracker` — `trackerScope.launch{}` for Every `end()` Call Creates Unbounded Coroutine Count

**File:** `PerformanceTracker.kt` **Lines:** 123–133

```kotlin
fun end(operation: String, startTime: Long, sessionId: String): Long {
    ...
    // Async metric recording (don't block hot path)
    trackerScope.launch {
        recordMetric(...)
    }
    return durationMs
}
```

`end()` is called ~60 times/second. Each call launches a new coroutine. Each coroutine calls `recordMetric()` which:
1. Acquires `metricsLock` (brief)
2. Calls `eventBus.publish()` which calls `_events.tryEmit()` (also brief)

These coroutines complete quickly, but `SupervisorJob + Dispatchers.IO` does not automatically bound the number of running coroutines. Under sustained load: 60 launches/sec × 100ms IO thread acquisition = up to 6 coroutines in flight at once. This is acceptable.

**BUT:** `trackerScope` has no `cancel()` call anywhere. When the app shuts down, up to 60 pending coroutines per second continue running. No `stop()` method exists on `PerformanceTracker`. The `@Singleton` instance lives until process death, which is fine — but structured shutdown is missing.

**Severity: LOW** — Manageable in practice, but no clean shutdown.

---

# PART 2 — MEDIUM BUGS

---

## 🟡 M5-1: `WorkflowEngine.BlockExecutor` — `execute()` Sets `ctx.currentBlock` on a `var` Field from Inside `coroutineScope{}` Parallel Branches

**File:** `WorkflowEngine.kt` **Line:** 318

```kotlin
suspend fun execute(block: WorkflowBlock, ctx: WorkflowContext): BlockResult {
    ctx.currentBlock = block.blockId   // ← writes to mutable var
```

In parallel branches: `coroutineScope { val jobs = branches.map { async { executeBlocks(branch, branchCtx) } } }`. Each `branchCtx` is a **copy** so each branch has its own `currentBlock`. ✓

BUT: `executeBlocks(normalBlocks, ctx)` (the outer sequential execution) calls `execute(block, ctx)` where `ctx` is the **shared outer context**. Multiple sequential blocks write `ctx.currentBlock` one after another. Since these are sequential (not parallel), there's no race. ✓

**Actually safe** — not a bug in current code. Sequential outer execution, parallel branches use copies. ✓

---

## 🟡 M5-2: `ScriptEngine.evaluate()` — User Input Length Check Is Post-Interpolation, Not Pre-Interpolation

**File:** `ScriptEngine.kt` **Lines:** 47–55

```kotlin
fun evaluate(expression: String, variables: Map<String, String> = emptyMap()): String {
    if (expression.length > MAX_EXPR_LEN) return "ERROR: expression too long"

    // Variable substitution
    var expr = expression
    variables.forEach { (k, v) -> expr = expr.replace("{$k}", v) }
    expr = expr.trim()
```

`MAX_EXPR_LEN = 500`. The check is on the raw `expression`. After variable substitution, `expr` can be much longer. Example:

```
expression = "{huge_variable}"   // 16 chars — passes check
variables["huge_variable"] = "A".repeat(10000)  // 10KB value
expr after substitution = "A".repeat(10000)      // 10KB — no check!
```

The result: `eval()` processes a 10KB expression string, potentially allocating large intermediate strings in `splitArgs()`, `findOperator()`, etc.

**Fix:** Add a second length check after substitution:
```kotlin
if (expr.length > MAX_EXPR_LEN * 10) return "ERROR: expression too long after substitution"
```

---

## 🟡 M5-3: `WorkflowContext.interpolate()` — Regex Applied Even to Non-String WorkflowBlock Values

**File:** `WorkflowEngine.kt`

`executeSetVariable()` calls:
```kotlin
val value = ctx.interpolate(block.value)
```

`block.value` is a `String` that may contain `{varName}` placeholders. The `INTERPOLATION_REGEX` is `\{([^{}]+)\}`. This is correct.

However, if a variable value itself contains `{something}`, it will be recursively expanded:

```
variables: a="hello {b}", b="world"
template: "{a}"
→ match {a} → replace with "hello {b}"
→ result: "hello {b}"   ← NOT further expanded (single-pass)
```

Single-pass Regex.replace() does NOT recursively expand — the replacement string is not re-scanned. ✓ This is correct behavior.

**Not a bug.** ✓

---

## 🟡 M5-4: `MemoryEngine.storeLTM()` — Double Write-Through: STM and DB Out of Sync if DB Write Fails

**File:** `MemoryEngine.kt` **Lines:** 246–262

```kotlin
suspend fun storeLTM(key: String, value: String, ...) {
    val storedValue = if (encrypted) encryptionManager.encrypt(value) else value
    val item = MemoryItem(key=key, value=storedValue, ...)

    shortTermMemory.put(item)         // Write 1: STM (always succeeds)

    withContext(Dispatchers.IO) {
        database.memoryDao().upsert(  // Write 2: DB (can fail)
            MemoryEntity(key=key, value=storedValue, ...)
        )
    }                                  // Exception propagates to caller
    eventBus.publish(MemoryStoredEvent(...))
}
```

If `database.memoryDao().upsert()` throws (disk full, DB corruption), the exception propagates out of `storeLTM()`. The caller sees a failure. BUT: `shortTermMemory.put(item)` has **already succeeded**. The STM has the value, the DB does not.

On next app launch, `getLTM(key)` will find nothing in STM (cleared on restart), query DB → not found → returns `null`. Data is **silently lost** — caller thought the write failed, but actually the data existed in STM for the current session.

**Fix:** Either use a transaction-like approach (write DB first, then STM on success), or at minimum document this inconsistency clearly.

---

## 🟡 M5-5: `CrashReplaySystem.saveCrashSnapshotSync()` — Called Inside `try{}` of the Already-OOM Crash Handler After `writeEmergencyCrashInfo()`

**File:** `CrashReplaySystem.kt`

The crash handler now does:
```kotlin
writeEmergencyCrashInfo(thread, throwable)   // pre-allocated, safe

try {
    saveCrashSnapshotSync(...)    // ← allocates CrashSnapshot + JSON + file I/O
} catch (_: Throwable) { }
```

`saveCrashSnapshotSync()` calls `buildSnapshot()` which allocates:
- `CrashSnapshot` data class (many fields)  
- `eventBuffer.snapshot()` → `List<EventRecord>` (100 items)
- `memoryEngine.shortTermMemory.getAll()` → `Map<String, MemoryItem>` (up to 500 items)
- `json.encodeToString(snapshot)` → full JSON serialization (~10-100KB string)

If the crash was OOM, this entire block throws another OOM, caught by `catch (_: Throwable)`. The emergency file is saved (good), but the full snapshot is lost. This is the **intended fallback behavior** from the C-6 fix.

**But:** `memoryEngine.shortTermMemory.getAll()` calls `store.toMap()` which is `@Synchronized`. Inside a crash handler, acquiring a JVM monitor that might be held by a crashed thread can **deadlock**. If the thread that crashed was holding `shortTermMemory`'s lock (e.g., crashed inside `put()`), the crash handler blocks forever trying to acquire the same lock.

**Fix:** Don't call any `@Synchronized` method from the crash handler path. Use a separate `AtomicReference<Map<String, String>>` that is updated on a timer (every 5 seconds), readable without locking.

---

# PART 3 — LOW / CORRECTNESS

---

## 🔵 L5-1: `ActionEngine.executeDoubleTap()` — First Tap's `id` Is New UUID, Not `correlationId`

**File:** `ActionEngine.kt` **Lines:** 474–479

```kotlin
private suspend fun executeDoubleTap(command: DoubleTapCommand): Boolean {
    executeTap(TapCommand(x = command.x, y = command.y, sessionId = command.sessionId))
    delay(50)
    return executeTap(TapCommand(x = command.x, y = command.y, sessionId = command.sessionId))
}
```

Both inner `TapCommand` objects get default `id = UUID.randomUUID()`. The outer `DoubleTapCommand` carries `command.id` (the `correlationId` from workflow). But the inner taps use different IDs.

`executeWithRetry()` publishes `ActionExecutedEvent(actionId = command.id)` — which is `DoubleTapCommand.id` = `correlationId`. So the workflow correlation works correctly because `DoubleTapCommand` itself carries the `correlationId` (set by `DoubleTapCommand(id = correlationId, ...)`).

Wait — `DoubleTapCommand` is NOT given `id = correlationId` in `enqueueDecision()`:
```kotlin
ActionType.DOUBLE_TAP -> target?.let {
    DoubleTapCommand(    // ← no id parameter! Uses default UUID
        x = ..., y = ..., sessionId = sessionId
    )
}
```

This is the same R5-1 bug — `DOUBLE_TAP` doesn't use `correlationId`.

---

## 🔵 L5-2: `FrameMemoryPool` — `release()` Silently Drops Buffers When Pool Is Full

**File:** `PerformanceTracker.kt`

```kotlin
@Synchronized
fun release(buffer: ByteArray) {
    if (pool.size < poolSize) {
        pool.addLast(buffer)
    }
    // If pool full, let GC collect
}
```

If `release()` is called when pool is full (5 buffers), the buffer is silently dropped. This means future `acquire()` calls allocate new `ByteArray` objects rather than reusing. Over time this defeats the entire purpose of the pool. There is no logging or metric when this happens.

**Severity: LOW** — Pool degrades gracefully, just reduces efficiency.

---

## 🔵 L5-3: `LoopBlock.FOR_EACH` — When `items` Variable Is Empty String, Returns `Success` Without Running Body

**File:** `WorkflowEngine.kt` **Line:** 435

```kotlin
val items = ctx.getVar(block.itemsVariable ?: "") ?: ""
if (items.isEmpty()) return BlockResult.Success   // ← immediate success
```

If the variable holds an empty string `""`, the loop body never executes and the block silently succeeds. This is likely correct behavior (empty list = no iterations = success). But if the variable is not found (`getVar` returns `null`), `""` is substituted and same behavior. The workflow designer might expect `BlockResult.Failure` when the variable doesn't exist.

**Severity: LOW** — Design choice, not a crash.

---

## 🔵 L5-4: `WorkflowEngine.handleTrigger()` — No Debounce: Rapid Trigger Events Launch Multiple Workflow Instances

**File:** `WorkflowEngine.kt`

```kotlin
private fun handleTrigger(trigger: TriggerEvent) {
    workflows.values
        .filter { it.isEnabled && it.triggers.any { t -> t.triggerType == trigger.type } }
        .forEach { workflow ->
            execute(workflow.id, trigger.sessionId)   // ← launches job, cancels previous
        }
}
```

`execute()` cancels the previous job for the same `workflowId` before starting a new one (LD-1 fix). But if `SCREEN_CHANGE` fires 10 times in 100ms (rapid screen transitions), 10 consecutive `execute()` calls happen:

```
t=0ms:  execute("wf1") → cancel null, start job-1
t=10ms: execute("wf1") → cancel job-1 (mid-init), start job-2
t=20ms: execute("wf1") → cancel job-2, start job-3
...
```

Job-1 through job-9 are cancelled almost immediately. Only job-10 runs. This wastes resources on 9 aborted workflow starts. For a complex workflow, setup code (DB reads, subscriptions) may partially execute then be cancelled, leaving partial side effects.

**Fix:** Debounce trigger events by 200ms before launching.

---

## 🔵 L5-5: `AgentEventBus.publish()` — `busScope.launch` on Buffer-Full Creates Unbounded Coroutines

**File:** `EventBus.kt` **Lines:** 244–252

```kotlin
fun publish(event: AgentEvent) {
    if (!_events.tryEmit(event)) {
        // Buffer full — emit asynchronously, drop if really blocked
        busScope.launch {
            _events.emit(event)   // suspends until space available
        }
    }
}
```

If the EventBus buffer (256 capacity) is full and events keep arriving faster than they're consumed:

1. `tryEmit()` returns `false` for each event
2. `busScope.launch{}` is called for each → creates a new coroutine
3. Each coroutine calls `_events.emit()` which suspends until space is available
4. Backpressure is now `DROP_OLDEST` — but the suspended coroutines still hold references to their events in memory

In a pathological case: 100 events/second, buffer drains at 80/second → 20 suspended coroutines/second → after 30 seconds = 600 suspended coroutines holding event objects. Memory grows unboundedly.

**Fix:** The `onBufferOverflow = DROP_OLDEST` handles this at the Flow level, but the `busScope.launch` coroutines are not bounded. Should use a bounded channel or simply drop on full:
```kotlin
fun publish(event: AgentEvent) {
    _events.tryEmit(event)
    // DROP_OLDEST handles overflow — no need for fallback coroutine
}
```

---

# SUMMARY TABLE

| ID | Severity | File | Issue |
|----|----------|------|-------|
| R5-1 | 🔴 CRITICAL | ActionEngine.kt | `correlationId` only applied to TAP — all other actions still use random UUID → always timeout |
| R5-2 | 🔴 CRITICAL | WorkflowEngine.kt | WAIT action goes through correlation loop but never fires ActionExecutedEvent → always timeout |
| R5-3 | 🔴 CRITICAL | ScreenCaptureEngine.kt | `frameChannel` permanently closed on first stop → restart breaks channel irreparably |
| R5-4 | 🔴 CRITICAL | SelfDiagnosticEngine.kt | `ruleRegistrySize` and `activePluginCount` not `@Volatile` → always reads 0 |
| R5-5 | 🟠 HIGH | TriggerEngine.kt | Double `initialize()` leaks BroadcastReceiver → duplicate trigger events |
| R5-6 | 🟠 HIGH | WorkflowEngine.kt | `FOR_EACH` split on `","` breaks items containing commas |
| R5-7 | 🟠 HIGH | ActionEngine.kt | Hardcoded scroll/swipe coords `540f, 900f` → breaks on non-1080p screens |
| R5-8 | 🟠 HIGH | SelfDiagnosticEngine.kt | JNI check always returns true — can't detect init failures |
| R5-9 | 🟠 HIGH | PerformanceTracker.kt | No `stop()` / no shutdown path for `trackerScope` |
| M5-2 | 🟡 MEDIUM | ScriptEngine.kt | Length check before substitution — expanded string can be huge |
| M5-4 | 🟡 MEDIUM | MemoryEngine.kt | STM/DB write-through inconsistency on DB failure |
| M5-5 | 🟡 MEDIUM | CrashReplaySystem.kt | `@Synchronized` call in crash handler → deadlock if crash thread holds STM lock |
| L5-1 | 🔵 LOW | ActionEngine.kt | `DoubleTapCommand` (same as R5-1 — misses correlationId) |
| L5-2 | 🔵 LOW | PerformanceTracker.kt | `FrameMemoryPool.release()` silently drops buffers when full |
| L5-4 | 🔵 LOW | WorkflowEngine.kt | No debounce on triggers → 10 rapid events cancel and restart workflow 10 times |
| L5-5 | 🔵 LOW | EventBus.kt | `busScope.launch` on buffer-full creates unbounded coroutines |

---

## FIX PRIORITY

1. **R5-1** — Apply `correlationId` to ALL action command types (LONG_PRESS, DOUBLE_TAP, SCROLL, SWIPE, NAVIGATE_BACK). One-line fix per command.
2. **R5-2** — WAIT/NONE action types: skip correlation, execute inline.
3. **R5-3** — Remove `frameChannel` + `frameFlow` dead code entirely.
4. **R5-4** — `@Volatile` on `ruleRegistrySize` and `activePluginCount`.
5. **R5-5** — Unregister old `batteryReceiver` before re-registering.
6. **R5-7** — Use display dimensions for scroll/swipe coordinates.
7. **L5-5** — Remove fallback `busScope.launch` from `EventBus.publish()`.
8. **M5-4** — Write DB first, then STM.
9. **M5-5** — Don't call `@Synchronized` from crash handler path.
10. **R5-6** — Use a different delimiter or JSON encoding for FOR_EACH items.
