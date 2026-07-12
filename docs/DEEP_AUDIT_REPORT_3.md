# Deep Code Audit — Round 3
## Vision Agent Framework — Full Mental Execution Trace
**Method:** Every function mentally executed, every edge-case traced.
**Scope:** All 122 files — Kotlin, C++, Rust, Python
**Date:** 2026-07-09

---

# SEVERITY LEGEND
| Level | Meaning |
|---|---|
| 🔴 CRITICAL | Crash, data corruption, security breach, or silent wrong behaviour |
| 🟠 HIGH | Likely failure under real load or edge conditions |
| 🟡 MEDIUM | Incorrect behaviour or logic flaw |
| 🔵 LOW | Reliability or correctness concern |

---

# PART 1 — NEW CRITICAL BUGS

---

## 🔴 R3-1 : `BlockExecutor.executeParallel()` references `engineScope` — **Compile Error: Unresolved Reference**

**File:** `android/app/src/main/kotlin/com/visionagent/core/workflow/engine/WorkflowEngine.kt`
**Line:** 410

```kotlin
class BlockExecutor(          // ← plain class, no engineScope
    private val eventBus: ...,
    ...
) {
    private suspend fun executeParallel(...): BlockResult {
        val jobs = block.branches.map { branch ->
            ...
            engineScope.async {   // ← engineScope does NOT exist in this class!
                executeBlocks(branch, branchCtx)
            }
        }
    }
}
```

`engineScope` is a **private field of `WorkflowEngine`**, declared at line 612. `BlockExecutor` is a separate class at line 310. It has no `engineScope` member and is not an inner class of `WorkflowEngine`.

**This is an unresolved reference — the project will not compile.**

The V-2 fix replaced `CoroutineScope(Dispatchers.Default).async {}` with `engineScope.async {}` correctly as a concept, but placed the reference in the wrong class.

**Fix required:** Pass `CoroutineScope` as a constructor parameter to `BlockExecutor`, or use `coroutineScope { }` (the suspend function from `kotlinx.coroutines`) which creates a child scope from the calling coroutine's context — the correct solution for structured concurrency:

```kotlin
private suspend fun executeParallel(...): BlockResult {
    val jobs = block.branches.map { branch ->
        val branchCtx = ctx.copy(
            variables = ConcurrentHashMap(ctx.variables),
            errorLog  = ctx.errorLog.toMutableList()
        )
        // coroutineScope{} inherits the parent coroutine's job — cancellable + structured
        async { executeBlocks(branch, branchCtx) }
    }
    ...
}
```

This requires wrapping in `coroutineScope { jobs.map { ... }.awaitAll() }` which is the idiomatic structured concurrency solution.

---

## 🔴 R3-2 : `dispatchGesture()` requires Main Looper — Will Silently Fail on `Dispatchers.Default`

**File:** `android/app/src/main/kotlin/com/visionagent/core/action/ActionEngine.kt`
**Lines:** 174–175, 412–420

The NC-3 fix moved `engineScope` from `Dispatchers.Main` to `Dispatchers.Default`. However, `AccessibilityService.dispatchGesture()` internally posts to the **Main Looper** via an internal `Handler`. It requires the **callback Handler** (the last parameter) to also be on the Main thread, or the callback is never delivered.

From AOSP source: `dispatchGesture(GestureDescription, GestureResultCallback, Handler)` — when `Handler` is `null`, it uses `mHandler` which is the **Main thread handler** of the AccessibilityService.

**The actual behaviour:**
- `dispatchGesture()` is called from `Dispatchers.Default` thread pool — this is fine, the call itself does not require Main.
- BUT `suspendCancellableCoroutine` will be resumed by the `GestureResultCallback.onCompleted()` callback.
- That callback is invoked on the Handler passed to `dispatchGesture()`. When `null` is passed, it uses the service's main Handler.
- The coroutine is then resumed **on the Main thread** (via `Handler.post`).
- When resumed, the coroutine continues execution on the **Main thread** despite `engineScope` being `Dispatchers.Default`.
- This means `executeWithRetry()` continues on Main thread after the gesture — potentially blocking Main while doing `delay()`, `recordAction()`, `eventBus.publish()` etc.

**More critically:** `continuation.resume(true) {}` called from a Main Handler while the coroutine's dispatcher is `Dispatchers.Default` means the coroutine resumes on a **different thread than expected**. The `{}` in `continuation.resume(true) {}` is the `onCancellation` lambda — if the coroutine was cancelled between dispatch and callback, this runs. The resumption dispatcher is non-deterministic.

**The real fix:** The `engineScope` should be `Dispatchers.Default` for CPU work, but `dispatchGesture` and its callback handling should explicitly work with a `Handler(Looper.getMainLooper())`:

```kotlin
service.dispatchGesture(gesture, object : GestureResultCallback() {
    override fun onCompleted(...) { continuation.resume(true) {} }
    override fun onCancelled(...) { continuation.resume(false) {} }
}, Handler(Looper.getMainLooper()))  // explicit handler, not null
```

This ensures the callback is always delivered to Main, the resume happens from Main, and the coroutine properly resumes on `Dispatchers.Default` (if that's the scope).

---

## 🔴 R3-3 : `FPSController.fpsSamples` — Not Thread-Safe, Written from HandlerThread

**File:** `android/app/src/main/kotlin/com/visionagent/core/screen/ScreenCaptureEngine.kt`
**Lines:** 413–430

```kotlin
class FPSController(private val targetFps: Int) {
    private val intervalMs = 1000L / targetFps.coerceIn(1, 60)
    private var lastCaptureTime = 0L
    private val fpsSamples = ArrayDeque<Long>(60)  // ← NOT synchronized

    fun shouldCapture(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return if (now - lastCaptureTime >= intervalMs) {
            fpsSamples.addLast(now)          // ← writes
            if (fpsSamples.size > 60) fpsSamples.removeFirst()  // ← writes
            lastCaptureTime = now            // ← writes
            true
        } else false
    }

    fun currentFps(): Float {
        if (fpsSamples.size < 2) return 0f  // ← reads size
        val duration = fpsSamples.last() - fpsSamples.first()  // ← reads elements
        ...
    }
}
```

`shouldCapture()` is called from the `HandlerThread` (ImageReader callback).
`currentFps()` is called from `processFrameQueue()` which runs on `Dispatchers.IO` (a different thread pool).

`fpsSamples` is a plain `ArrayDeque<Long>` — **not thread-safe**. Concurrent `addLast`/`removeFirst` from Thread-A and `last()`/`first()` from Thread-B can produce `ConcurrentModificationException` or return garbage values (e.g., `last()` reading a partially-written slot).

`lastCaptureTime` is a plain `var Long` — **not `@Volatile`**. On JVM, a `long` write (64-bit) is not guaranteed to be atomic on all JVM implementations (though in practice Android's ART does guarantee 64-bit long atomicity on ARM64). But it is not `@Volatile`, so visibility is not guaranteed.

**Fix required:**
1. Add `@Synchronized` to both `shouldCapture()` and `currentFps()`.
2. Or make `lastCaptureTime` an `AtomicLong` and `fpsSamples` a `CopyOnWriteArrayList`.
3. Simplest: since `FPSController` is only used from the HandlerThread for `shouldCapture()` — move `currentFps()` to also be called only from HandlerThread, or take a snapshot.

---

## 🔴 R3-4 : `MemoryEngine.getLTM()` — Cache Populates STM with Encrypted Ciphertext, then Returns Plaintext — Double-Decrypt Risk

**File:** `android/app/src/main/kotlin/com/visionagent/core/memory/MemoryEngine.kt`
**Lines:** 270–292

```kotlin
suspend fun getLTM(key: String): String? {
    // Cache hit
    shortTermMemory.get(key)?.let { item ->
        return if (item.encrypted) encryptionManager.decrypt(item.value) else item.value
        //                                                     ↑ decrypts ciphertext stored in STM
    }

    // Cache miss — load from DB
    return withContext(Dispatchers.IO) {
        database.memoryDao().getByKey(key)?.let { entity ->
            val value = if (entity.encrypted) encryptionManager.decrypt(entity.value)
                        else entity.value
            // Populate STM cache
            shortTermMemory.put(MemoryItem(
                key = key,
                value = entity.value,   // ← stores CIPHERTEXT in STM
                ...
                encrypted = entity.encrypted,
                ...
            ))
            value  // returns PLAINTEXT
        }
    }
}
```

**Trace the cache-miss path:**
1. DB returns entity with `encrypted = true`, `value = "IV:CipherText"`
2. `entity.value` (ciphertext) is decrypted → `plaintext`
3. STM is populated with `value = entity.value` = **ciphertext** (correct — STM stores encrypted form)
4. Returns `plaintext` ✓

**Now trace the cache-hit path on the SECOND call:**
1. STM hit: `item.value = "IV:CipherText"`, `item.encrypted = true`
2. Returns `encryptionManager.decrypt(item.value)` = `decrypt("IV:CipherText")` = **plaintext** ✓

Actually this is correct. The STM intentionally stores the ciphertext when `encrypted = true`. The cache-hit branch decrypts it. This is fine.

**BUT** — `storeLTM()` does this:
```kotlin
val storedValue = if (encrypted) encryptionManager.encrypt(value) else value
val item = MemoryItem(key=key, value=storedValue, ..., encrypted=encrypted)
shortTermMemory.put(item)  // STM gets ciphertext
```

And separately the same item is stored to DB with `value = storedValue` (ciphertext).

**The actual bug:** If `getLTM()` is called AFTER `storeLTM()` (cache hit from STM, where STM has ciphertext), it correctly decrypts. If two concurrent threads call `getLTM()` simultaneously for the same key with a cache miss:

1. Thread-A: cache miss → DB query starts
2. Thread-B: cache miss → DB query starts (same key!)
3. Thread-A: completes, populates STM with ciphertext
4. Thread-B: completes, tries to populate STM with same ciphertext → overwrite (benign)
5. Both return plaintext ✓

So not a correctness bug but a double DB read. More importantly:

**Real bug:** `getLTM()` calls `shortTermMemory.get(key)` which is `@Synchronized`, then calls `encryptionManager.decrypt()` which does **KeyStore access** — potentially slow (10-100ms). This is done inside a coroutine on `Dispatchers.IO` but the `@Synchronized` lock is held during the KeyStore call, blocking any other STM reader for those 100ms.

**Severity: MEDIUM** — Lock contention on STM during KeyStore decryption.

---

## 🔴 R3-5 : `RecoveryEngine` — `totalRecoveryAttempts` and `consecutiveErrors` Written by Concurrent Coroutines Without Synchronisation

**File:** `android/app/src/main/kotlin/com/visionagent/core/recovery/RecoveryEngine.kt`
**Lines:** 214–215, 227, 243, 263

```kotlin
private var totalRecoveryAttempts = 0   // ← plain var, no @Volatile
private var consecutiveErrors = 0       // ← plain var, no @Volatile

// In subscribeToErrors() — one coroutine flow:
consecutiveErrors++               // ← read-modify-write, not atomic
triggerRecovery(...)

// In subscribeToActions() — another coroutine flow:
consecutiveErrors = 0             // ← write
```

Both subscriptions run on `engineScope` which uses `Dispatchers.Default` (a thread pool). When multiple errors arrive quickly:

- Thread-A processes `AgentErrorEvent` → reads `consecutiveErrors = 2`, increments to 3, stores
- Thread-B processes `AgentErrorEvent` → reads `consecutiveErrors = 2` (stale!), increments to 3, stores
- Both threads think count is 3, but it should be 4

`totalRecoveryAttempts++` in `triggerRecovery()` has the same race — the max recovery check can be bypassed if two threads both read `9` and both increment to `10` before either checks `>= MAX`.

**Fix required:** Use `AtomicInteger` for both fields.

---

## 🔴 R3-6 : `Logger.writeToFile()` — `fileWriter` Is Not Thread-Safe — Concurrent Writes Corrupt Log File

**File:** `android/app/src/main/kotlin/com/visionagent/utils/Logger.kt`
**Lines:** 168, 182–210

```kotlin
private var fileWriter: java.io.BufferedWriter? = null

private fun getWriter(): java.io.BufferedWriter? {
    val file = logFile ?: return null
    if (fileWriter == null) {       // ← check without lock
        try {
            fileWriter = java.io.BufferedWriter(...)  // ← write without lock
        }
    }
    return fileWriter
}

private fun writeToFile(entry: LogEntry) {
    // ...
    val writer = getWriter() ?: return
    writer.write(...)   // ← no lock here!
    writer.newLine()
    writer.flush()
}
```

`writeToFile()` is called from `startAsyncWriter()` which runs a **single coroutine** on `logScope` (Dispatchers.IO). Since it's a single coroutine, there's only one writer at a time.

**BUT** `flushAll()` is called from the main thread (or test threads):
```kotlin
fun flushAll() {
    while (logBuffer.isNotEmpty()) {
        logBuffer.poll()?.let { writeToFile(it) }  // ← calls writeToFile from caller's thread
    }
    fileWriter?.flush()  // ← accesses fileWriter from caller's thread
}
```

`flushAll()` can be called concurrently with `startAsyncWriter()`'s loop. Both call `writeToFile()` → `getWriter()` → `writer.write()` on the **same `BufferedWriter`** from different threads. `BufferedWriter` is **not thread-safe**.

This causes: interleaved writes, corrupted log lines, `ArrayIndexOutOfBoundsException` in the internal char buffer.

**Fix required:** Synchronize `writeToFile()` and `getWriter()` on a dedicated lock, or ensure `flushAll()` also posts to `logScope` rather than directly calling.

---

## 🔴 R3-7 : `MemoryDao.upsert()` — `@Transaction + @Query` is an Invalid Room Annotation Combination

**File:** `android/app/src/main/kotlin/com/visionagent/data/local/dao/DAOs.kt`
**Lines:** 149–151

```kotlin
@Transaction
@Query("INSERT OR REPLACE INTO memory_store (...) VALUES (...)")
suspend fun upsert(memory: MemoryEntity)
```

`@Transaction` on a `@Query` method in Room does nothing useful here and is **semantically wrong**. `@Transaction` is intended for `@Insert`/`@Update`/`@Delete` methods or methods that call multiple DAO operations. When used with a raw `@Query`, Room wraps the single SQL statement in a transaction — but a single SQL statement is **already atomic** in SQLite.

More critically: the method signature declares `memory: MemoryEntity` as a parameter but the `@Query` uses named bindings (`:key`, `:value`, `:type`, etc.) which must match the **method parameter names exactly**. The parameter is named `memory`, not `key`, `value`, etc. Room cannot bind `memory.key` to `:key` — it looks for a parameter literally named `key`.

**This will fail to compile** with Room's annotation processor: `error: Cannot find setter for field key in the method parameter memory`.

**Fix required:** Either use `@Insert(onConflict = OnConflictStrategy.REPLACE)` (simplest and correct):
```kotlin
@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun upsert(memory: MemoryEntity): Long
```
Or use individual named parameters matching the query bindings:
```kotlin
@Query("INSERT OR REPLACE INTO memory_store (...) VALUES (:key, :value, ...)")
suspend fun upsert(key: String, value: String, type: String, ...)
```

---

## 🔴 R3-8 : `asyncio.Lock` Created at Module Level Before Event Loop Exists — Runtime Error

**File:** `backend/src/main.py`
**Line:** 126

```python
_rate_limit_lock = asyncio.Lock()   # ← module-level, before event loop starts
```

`asyncio.Lock()` must be created within a running event loop in Python 3.10+. When created at module import time (before `uvicorn` starts the event loop), this raises:

```
DeprecationWarning: There is no current event loop
```

In Python 3.12+ this is a hard error:
```
RuntimeError: no running event loop
```

Since `backend/src/main.py` is imported when uvicorn starts, and `asyncio.Lock()` is called at module level before the event loop is running, this **crashes the backend on startup in Python 3.12+**.

**Fix required:** Create the lock lazily or inside a startup event:
```python
_rate_limit_lock: asyncio.Lock | None = None

@app.on_event("startup")  # or use lifespan
async def create_lock():
    global _rate_limit_lock
    _rate_limit_lock = asyncio.Lock()
```

Or use `asyncio.Lock()` inside the middleware function (creates a new lock per request — wrong). The correct pattern is lazy init:
```python
def get_rate_limit_lock():
    global _rate_limit_lock
    if _rate_limit_lock is None:
        _rate_limit_lock = asyncio.Lock()
    return _rate_limit_lock
```

---

## 🔴 R3-9 : `redis_client._record_failure()` — Global Mutable State Without Async Lock — Race Condition in Async Context

**File:** `backend/src/cache/redis_client.py`
**Lines:** 108–116

```python
_circuit_open = False
_failure_count = 0

def _record_failure(e: Exception):
    global _circuit_open, _failure_count, _last_failure_time
    _failure_count += 1              # ← NOT atomic in async context
    _last_failure_time = time.time()
    if _failure_count >= CIRCUIT_THRESHOLD:
        _circuit_open = True
```

Python's asyncio is **single-threaded** but coroutines are interleaved at `await` points. `_record_failure()` is a **synchronous** function — it runs without any `await`, so it executes atomically within a single coroutine. No concurrent modification is possible in pure asyncio.

**HOWEVER:** If `uvicorn` is configured with `workers > 1` (which it is: `settings.WORKERS = 4`), each worker is a **separate process**. Global variables are not shared between processes. Each process has its own `_circuit_open` and `_failure_count`. The circuit breaker is **per-process, not global**.

This means: if 3 out of 4 worker processes experience Redis failures, only they open their circuits. The 4th process continues trying Redis. This is not a bug per se (it's expected in multi-process), but it means the circuit breaker comment is misleading — it protects individual workers, not the whole backend.

**Real bug:** `get_redis()` reads `_circuit_open` and also reads/writes `_failure_count` — both without locks. In a **multi-threaded** uvicorn worker (if using thread workers instead of process workers), these are concurrent writes. Not an issue with the default asyncio loop but worth noting.

**Severity: LOW** — Circuit breaker is per-process (intended limitation), not a crash.

---

## 🔴 R3-10 : `WorkflowContext.currentBlock` — Written by `BlockExecutor` Without Synchronisation, Read for Error Reporting

**File:** `android/app/src/main/kotlin/com/visionagent/core/workflow/engine/WorkflowEngine.kt`
**Line:** 318

```kotlin
data class WorkflowContext(
    ...
    var currentBlock: String = "",   // ← mutable, non-volatile
    var retryCount:   Int    = 0,    // ← mutable, non-volatile
    ...
)
```

`BlockExecutor.execute()` sets:
```kotlin
ctx.currentBlock = block.blockId
```

`WorkflowEngine.runWorkflow()` reads it on failure:
```kotlin
WorkflowResult.Failed(runId, result.reason, ctx.currentBlock)
```

When `executeParallel()` spawns multiple `async` branches, each branch writes `branchCtx.currentBlock` — but `branchCtx` is a **copy** so this is fine. However, `ctx.currentBlock` is modified by the main `executeBlocks()` call which runs on one coroutine, and `ctx` is shared with the `runWorkflow()` caller which also reads it from the same coroutine. Since there's no parallel access to the same `ctx`, this is not a race **in the current code**.

**However:** `var retryCount: Int = 0` and `var errorLog: MutableList<String>` in `WorkflowContext` are mutable. If passed to parallel branches (even via `copy()`), the `copy()` creates a new `WorkflowContext` but `errorLog = ctx.errorLog.toMutableList()` creates a deep copy. The fix (M-8) is correctly applied. ✓

**Severity: LOW** — Not a current race, but fragile design.

---

# PART 2 — IMPLEMENTATION CORRECTNESS ISSUES

---

## 🟠 IC-1 : `executeAction()` in `BlockExecutor` — `delay(200)` After Publishing Event — Action May Never Complete

**File:** `android/app/src/main/kotlin/com/visionagent/core/workflow/engine/WorkflowEngine.kt`
**Lines:** 344–360

```kotlin
private suspend fun executeAction(block: ActionBlock, ctx: WorkflowContext): BlockResult {
    val decision = AgentDecision(...)
    eventBus.publish(RuleEvaluatedEvent(
        ...decision = decision,
        sessionId = ctx.sessionId
    ))
    delay(200)  // Allow action to dispatch
    return BlockResult.Success   // ← always returns Success regardless of actual action result!
}
```

The workflow publishes a `RuleEvaluatedEvent` with the decision, then waits 200ms and returns `Success`. But:

1. The `ActionEngine` picks up the event and executes the action **asynchronously**.
2. The action may fail — but `BlockResult.Success` has already been returned.
3. The workflow has no way to know if the action actually succeeded.
4. 200ms is arbitrary — on a slow device, 200ms may not be enough for the action to even start.

**This means workflow action steps always "succeed" even when the underlying action fails.** A workflow designed to tap "Submit" and then check for a response will always proceed even if the tap missed.

**Fix required:** Subscribe to `ActionExecutedEvent` filtered by `actionId` and use a `CompletableDeferred` to wait for actual completion:
```kotlin
val deferred = CompletableDeferred<Boolean>()
val sub = eventBus.subscribeFiltered<ActionExecutedEvent> { it.actionId == decision.actionId }
    .onEach { deferred.complete(it.success) }.launchIn(scope)
eventBus.publish(...)
val success = withTimeout(5000) { deferred.await() }
sub.cancel()
return if (success) BlockResult.Success else BlockResult.Failure("Action failed")
```

---

## 🟠 IC-2 : `handleActionFailure()` — Recursive Call Bypasses `performanceTracker.end()`

**File:** `android/app/src/main/kotlin/com/visionagent/core/action/ActionEngine.kt`
**Lines:** 290–343

```kotlin
private suspend fun executeWithRetry(command: ActionCommand, attempt: Int = 1) {
    val startTime = performanceTracker.start("action_execution")

    try {
        val success = executeCommand(command)
        val durationMs = performanceTracker.end("action_execution", startTime, ...)
        if (success) { ... }
        else { handleActionFailure(command, attempt, "Execution returned false") }
                     // ↑ called BEFORE end() returns — but end() was already called
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        handleActionFailure(command, attempt, e.message ?: "Unknown error")
        // ↑ called WITHOUT calling performanceTracker.end()! → start() leak
    }
}
```

In the `catch (e: Exception)` branch, `performanceTracker.end()` is **never called**. `start()` was called but `end()` is skipped. The NC-5 fix removed the `startTimes` map so there's no leak there, but `operationCounts` and `totalDurations` are never incremented for failed actions. Stats are understated.

More importantly: `handleActionFailure()` calls `executeWithRetry(command, attempt + 1)` — a recursive call. Each recursive call calls `performanceTracker.start()` again. For 3 retries: 3 `start()` calls. On the final attempt (3), `end()` is called once, but the first 2 `start()` calls have no matching `end()`. **The average latency calculation is incorrect** — 3 starts but only 1 end means `totalDurations` is undercounted by 2/3.

**Fix required:** Wrap the entire retry loop in a single `start()`/`end()` pair, not per-attempt.

---

## 🟠 IC-3 : `ActionEngine.accessibilityService` — Written Without `@Volatile` — Visibility Gap

**File:** `android/app/src/main/kotlin/com/visionagent/core/action/ActionEngine.kt`
**Line:** 185

```kotlin
private var accessibilityService: AgentAccessibilityService? = null

fun initialize(service: AgentAccessibilityService) {
    this.accessibilityService = service  // ← written from Main thread (Activity)
    ...
}
```

`initialize()` is called from `AgentOrchestrator.startAgent()` which runs on `Dispatchers.Main`. The `accessibilityService` field is read from `Dispatchers.Default` (the fixed engineScope). Without `@Volatile`, the write on Main may not be visible on Default.

```kotlin
private suspend fun executeTap(command: TapCommand): Boolean {
    val service = accessibilityService ?: return false  // ← read from Dispatchers.Default
```

On multi-core ARM64, without a memory barrier, `accessibilityService` might remain `null` on the Default thread pool even after `initialize()` has completed.

**Fix required:** `@Volatile private var accessibilityService: AgentAccessibilityService? = null`

---

## 🟠 IC-4 : `OCRCore.cpp` — `preprocess_for_ocr()` Returns Reference to `g_ocr.pre_binary` — Dangling Reference Risk

**File:** `android/app/src/main/cpp/ocr/OCRCore.cpp`
**Lines:** 147, 156

```cpp
cv::Mat preprocess_for_ocr(...) {
    ...
    if (level == 2) return g_ocr.pre_binary;   // ← returns Mat by VALUE (copy of header)
    ...
    cv::Mat deskewed = deskew_image(g_ocr.pre_binary);
    return deskewed;
}
```

`cv::Mat` is a **reference-counted** header + shared data pointer. `return g_ocr.pre_binary` returns a **copy of the Mat header** that still points to the **same underlying pixel data** as `g_ocr.pre_binary`.

When `g_ocr_mutex` is held by `ocr_extract_text()` and `preprocess_for_ocr()` returns `g_ocr.pre_binary`:
```cpp
cv::Mat processed = preprocess_for_ocr(input, level);  // processed shares data with g_ocr.pre_binary
Pix* pix = mat_to_pix(processed);                      // reads from shared data
g_ocr.api->SetImage(pix);
```

Since the mutex is held for the entire `ocr_extract_text()` call, no concurrent `ocr_release()` can happen. **Within the mutex, this is safe.** ✓

**BUT:** `mat_to_pix()` creates a `Pix*` which copies pixels out. So even if `g_ocr.pre_binary` was modified after `mat_to_pix()`, the `Pix*` is independent. ✓

The mutex fix (NC-10) correctly guards this. The reference sharing is safe within the lock.

**Severity: NONE** — Not a bug given the mutex fix. ✓

---

## 🟠 IC-5 : `FPSController.shouldCapture()` — `lastCaptureTime` Write-Then-Read Gap Causes Double-Capture

**File:** `android/app/src/main/kotlin/com/visionagent/core/screen/ScreenCaptureEngine.kt`

```kotlin
fun shouldCapture(): Boolean {
    val now = SystemClock.elapsedRealtime()
    return if (now - lastCaptureTime >= intervalMs) {
        fpsSamples.addLast(now)
        if (fpsSamples.size > 60) fpsSamples.removeFirst()
        lastCaptureTime = now    // ← written AFTER returning true
        true
    } else false
}
```

`lastCaptureTime = now` is set at the **end** of the true branch. Since `shouldCapture()` is called from a single HandlerThread, there's no concurrent call. However:

If the ImageReader delivers two images extremely quickly (within the same Handler message processing cycle), `shouldCapture()` could be called twice before `lastCaptureTime` has the updated value persisted. In practice, the HandlerThread processes one callback at a time, so this is not an issue.

**Severity: NONE** — Single-threaded HandlerThread prevents double-call. ✓

---

# PART 3 — LOGIC AND DESIGN BUGS

---

## 🟡 LD-1 : `WorkflowEngine.execute()` — Previous Job Not Cancelled Before Starting New One

**File:** `android/app/src/main/kotlin/com/visionagent/core/workflow/engine/WorkflowEngine.kt`
**Lines:** 630–660

```kotlin
fun execute(workflowId: String, sessionId: String): Job? {
    val workflow = workflows[workflowId] ?: ...
    ...
    val job = engineScope.launch {
        val result = runWorkflow(workflow, sessionId)
        ...
    }
    runningJobs[workflowId] = job    // ← OVERWRITES previous job
    return job
}
```

If `execute()` is called for the same `workflowId` twice (e.g., trigger fires twice rapidly), the first `Job` is **overwritten** in `runningJobs` without being cancelled. The first job continues running indefinitely, consuming resources, and cannot be cancelled via `stopAll()`. Two instances of the same workflow run concurrently.

**Fix required:**
```kotlin
runningJobs[workflowId]?.cancel()   // cancel old job first
runningJobs[workflowId] = job
```

---

## 🟡 LD-2 : `RuleEvaluator.evaluate()` — `CONSECUTIVE_ERRORS` Condition is Identical to `RETRY_COUNT_LESS_THAN` Negated But Neither Checks the Right Value

**File:** `android/app/src/main/kotlin/com/visionagent/core/rule/RuleEngine.kt`

```kotlin
ConditionType.RETRY_COUNT_LESS_THAN ->
    context.retryCount < (condition.value as? Int ?: 3)

ConditionType.CONSECUTIVE_ERRORS ->
    context.retryCount >= (condition.value as? Int ?: 3)
```

Both `RETRY_COUNT_LESS_THAN` and `CONSECUTIVE_ERRORS` use `context.retryCount` — the same field. `CONSECUTIVE_ERRORS` should semantically check a **consecutive error count**, not the retry counter. The retry counter resets on success; consecutive errors should be tracked separately.

In `RecoveryEngine`, `consecutiveErrors` is tracked. But `RuleEvaluator.EvaluationContext` has no `consecutiveErrors` field — only `retryCount`. The two concepts are conflated.

**Also:** `condition.value as? Int` is an unsafe cast that silently returns `null` (→ fallback 3) if `value` is not literally an `Int` at runtime. Since `RuleCondition.value: Any` was annotated `@Contextual` but the actual value stored might be a `Long`, `Double`, or even a `String` depending on how it was constructed. A `Long` value of `3L` cast to `Int?` returns `null` (not 3), so the fallback kicks in silently.

**Severity: MEDIUM** — Silent wrong evaluation when `value` is `Long` instead of `Int`.

---

## 🟡 LD-3 : `compressBitmap()` — `ByteArrayOutputStream` Not Closed

**File:** `android/app/src/main/kotlin/com/visionagent/core/screen/ScreenCaptureEngine.kt`
**Line:** 425–428

```kotlin
private fun compressBitmap(bitmap: Bitmap): ByteArray {
    val stream = java.io.ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality, stream)
    return stream.toByteArray()
    // stream never closed
}
```

`ByteArrayOutputStream.close()` is a no-op (documented in the JDK). It doesn't release any resources. **This is not a resource leak.** ✓

**Severity: NONE** — `ByteArrayOutputStream.close()` does nothing. ✓

---

## 🟡 LD-4 : `VisionEngine.config` — Plain `var` Not `@Volatile`

**File:** `android/app/src/main/kotlin/com/visionagent/core/vision/VisionEngine.kt`
**Lines:** 63, 68

```kotlin
private var config = VisionConfig()           // ← not @Volatile
private var isInitialized = false             // ← not @Volatile

fun initialize(config: VisionConfig = VisionConfig()) {
    this.config = config       // ← written from calling thread (Main or Default)
    nativeBridge.initialize(config)
    isInitialized = true       // ← written
    subscribeToEvents()
    ...
}
```

`processFrame()` runs on `dispatcher` (a `newFixedThreadPoolContext(2)`) and reads:
```kotlin
withContext(dispatcher) {
    ...
    val result = nativeBridge.processFrame(..., config = config)
```

`config` is written in `initialize()` (called from `AgentOrchestrator.startAgent()` on Main). Without `@Volatile`, the write may not be visible on the dispatcher's threads.

`isInitialized = true` is also plain `var`. If `processFrame()` is called before `initialize()` completes (race between `launchIn(engineScope)` setup and the first FrameCapturedEvent), `isInitialized` might be `false` on the dispatcher thread.

**Fix required:** `@Volatile private var config = VisionConfig()` and `@Volatile private var isInitialized = false`.

---

## 🟡 LD-5 : `ScreenCaptureEngine.currentSessionId` — Plain `var String`, Written From Main, Read From HandlerThread

**File:** `android/app/src/main/kotlin/com/visionagent/core/screen/ScreenCaptureEngine.kt`
**Line:** 136

```kotlin
private var currentSessionId: String = ""

fun startCapture(resultCode: Int, ..., sessionId: String) {
    ...
    currentSessionId = sessionId    // written from Main thread
    ...
}

private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
    ...
    eventBus.publish(FrameDroppedEvent(frameId, ..., sessionId = currentSessionId))
    // ← read from HandlerThread
```

`currentSessionId` is written on the calling thread (Main) and read on the HandlerThread. Without `@Volatile`, the HandlerThread may read the old empty string.

**Fix required:** `@Volatile private var currentSessionId: String = ""`

---

## 🟡 LD-6 : `WorkflowEngine` — `BlockExecutor` Has No `CoroutineScope` for `engineScope.async` — Remains a Compile Error

This is the same as R3-1 — the `engineScope.async` reference in `BlockExecutor.executeParallel()` will fail to compile because `BlockExecutor` has no `engineScope` field.

---

# PART 4 — PYTHON BACKEND ISSUES

---

## 🔵 PY-1 : `vision_service.py` — `VisionService._initialized` Written Without GIL Protection in Thread Pool

**File:** `backend/src/services/vision_service.py`
**Lines:** 65, 68

```python
async def initialize(self, model_dir: str):
    loop = asyncio.get_event_loop()
    await loop.run_in_executor(None, self._load_models, model_dir)
    self._initialized = True      # ← written after executor completes
```

Since `run_in_executor` awaits completion before writing `_initialized`, and `_initialized = True` happens in the async context (single event loop thread), this is safe. ✓

**Severity: NONE** ✓

---

## 🔵 PY-2 : `main.py` — Global `_rate_limit_lock` Defined Before Event Loop — Python 3.12 Hard Error

This is R3-8, already listed above. The `asyncio.Lock()` at module level crashes on Python 3.12.

---

# SUMMARY TABLE — NEW FINDINGS

| ID | Severity | Location | Issue |
|----|----------|----------|-------|
| R3-1 | 🔴 CRITICAL | WorkflowEngine.kt | `engineScope.async` in `BlockExecutor` — **compile error: unresolved reference** |
| R3-2 | 🔴 CRITICAL | ActionEngine.kt | `dispatchGesture()` callback not delivered to correct thread after Dispatcher.Main→Default change |
| R3-3 | 🔴 CRITICAL | ScreenCaptureEngine.kt | `FPSController.fpsSamples` and `lastCaptureTime` — unsynchronised cross-thread access |
| R3-4 | 🟡 MEDIUM | MemoryEngine.kt | STM lock held during KeyStore decryption — 100ms contention |
| R3-5 | 🔴 CRITICAL | RecoveryEngine.kt | `totalRecoveryAttempts` and `consecutiveErrors` — non-atomic, no `@Volatile` |
| R3-6 | 🔴 CRITICAL | Logger.kt | `fileWriter` (BufferedWriter) accessed from two threads — data corruption |
| R3-7 | 🔴 CRITICAL | DAOs.kt | `@Transaction + @Query` with entity parameter — Room compile error |
| R3-8 | 🔴 CRITICAL | main.py | `asyncio.Lock()` at module level — crashes Python 3.12+ on startup |
| R3-9 | 🔵 LOW | redis_client.py | Circuit breaker is per-process, misleading comment |
| R3-10 | 🔵 LOW | WorkflowEngine.kt | `WorkflowContext` mutable fields fragile under parallel use |
| IC-1 | 🟠 HIGH | WorkflowEngine.kt | Workflow action steps always return `Success` — actual action result ignored |
| IC-2 | 🟠 HIGH | ActionEngine.kt | `performanceTracker.end()` skipped in exception path — stat miscounting |
| IC-3 | 🟠 HIGH | ActionEngine.kt | `accessibilityService` not `@Volatile` — null read possible from Default thread |
| LD-1 | 🟡 MEDIUM | WorkflowEngine.kt | Duplicate workflow trigger: previous Job not cancelled before new one starts |
| LD-2 | 🟡 MEDIUM | RuleEngine.kt | `condition.value as? Int` silently returns null for `Long` values |
| LD-4 | 🟡 MEDIUM | VisionEngine.kt | `config` and `isInitialized` not `@Volatile` |
| LD-5 | 🟡 MEDIUM | ScreenCaptureEngine.kt | `currentSessionId` not `@Volatile` |
| LD-6 | 🟡 MEDIUM | WorkflowEngine.kt | Duplicate of R3-1 — compile error remains |

---

## PRIORITY FIX ORDER

1. **R3-1 / LD-6** — Compile error: `engineScope` in `BlockExecutor` — project does not build
2. **R3-7** — Compile error: `@Transaction + @Query(INSERT)` with entity param — Room kapt fails
3. **R3-8** — Python 3.12 startup crash: `asyncio.Lock()` before event loop
4. **R3-2** — `dispatchGesture` callback threading: must pass explicit `Handler(Looper.getMainLooper())`
5. **R3-3** — `FPSController` thread safety: `@Synchronized` or `AtomicLong`
6. **R3-5** — `RecoveryEngine` counter races: `AtomicInteger`
7. **R3-6** — Logger `BufferedWriter` concurrent access: synchronize `writeToFile()`
8. **IC-3** — `accessibilityService` not `@Volatile`
9. **IC-1** — Workflow actions always succeed: wait for `ActionExecutedEvent`
10. **LD-4** — `VisionEngine.config` and `isInitialized` not `@Volatile`
11. **LD-5** — `currentSessionId` not `@Volatile`
12. **LD-1** — Cancel old workflow job before starting new
13. **IC-2** — `performanceTracker` stats undercounted on exception
14. **LD-2** — `condition.value as? Int` silent null for Long
