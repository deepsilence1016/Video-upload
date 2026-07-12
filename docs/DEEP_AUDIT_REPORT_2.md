# Deep Code Audit — Round 2
## Vision Agent Framework — Mental Execution Trace
**Method:** Every code path mentally executed as if running on device.  
**Date:** 2026-07-09  
**Severity:** 🔴 CRITICAL | 🟠 HIGH | 🟡 MEDIUM | 🔵 LOW

---

# PART 1 — NEW CRITICAL BUGS (पहले Audit में नहीं पकड़े गए)

---

## 🔴 NC-1: `insert()` calls `searchLayer()` while holding `synchronized(lock)` — Potential Deadlock + Re-entrant Lock Corruption — `HNSWIndex`

**File:** `android/app/src/main/kotlin/com/visionagent/core/memory/vector/VectorMemory.kt`  
**Lines:** 123, 148–173, 184

**Mental execution trace:**
```
Thread-A calls: index.insert(entry)
→ acquires synchronized(lock) on HNSWIndex
→ inside lock, calls: searchLayer(node.vector, curEp, efConstruction, lc)
→ searchLayer is NOT synchronized — it reads nodes[cIdx].layers directly
→ searchLayer reads: nodes[neighborIdx] (line ~246)

Thread-B simultaneously calls: index.search(query, k, ef)
→ tries to acquire synchronized(lock)
→ BLOCKED waiting for Thread-A to release
```

This part is safe. But the actual new bug is:

**Inside `searchLayer()` (called while lock is held from `insert()`):**
```kotlin
val node = nodes[cIdx]
val layerNeighbors = if (layer < node.layers.size) node.layers[layer] else emptySet()
for (neighborIdx in layerNeighbors) { ... }
```

`searchLayer` is a private function called from both `insert()` (under lock) and `search()` (also under lock). The problem is **`insert()` calls `searchLayer()` which calls `nodes[neighborIdx]`** — and also **mutates `node.layers[lc].add(idx)`** and **`neighborNode.layers[lc] = pruned`** (lines 153–164) — all while already holding `synchronized(lock)`.

**The real bug:** Kotlin's `synchronized(lock)` on an `Any()` is **NOT re-entrant** from the same thread in the same way as Java's `synchronized` on `this`. Wait — actually in Kotlin/JVM `synchronized` IS re-entrant. So deadlock between the same thread is not an issue.

**BUT** — the real remaining bug: `insert()` mutates `node.layers` (a `MutableSet<Int>`) and `nodes` (a `MutableList`) while holding the lock, then calls `searchLayer()` which iterates over `node.layers`. If during `selectNeighbors` → pruning loop (line 160), `neighborNode.layers[lc]` is replaced with a new `MutableSet` (`neighborNode.layers[lc] = pruned`), this is a **structural mutation of the layer set while the for-loop may be referencing the old set's iterator from a previous `searchLayer` call within the same `insert()` invocation**. Since both calls share the same lock (re-entrant), this is a potential `ConcurrentModificationException` if the inner `searchLayer` holds a reference to the old set and the pruning replaces it.

**Severity: HIGH** — `ConcurrentModificationException` in HNSW insert when neighbor pruning triggers during a recursive `searchLayer` traversal.

---

## 🔴 NC-2: `executeWithRetry()` swallows `CancellationException` — Coroutine Cannot Be Cancelled — `ActionEngine`

**File:** `android/app/src/main/kotlin/com/visionagent/core/action/ActionEngine.kt`  
**Lines:** 290–328

**Mental execution trace:**
```kotlin
private suspend fun executeWithRetry(command: ActionCommand, attempt: Int = 1) {
    // ...
    try {
        val success = executeCommand(command)    // ← suspends here (dispatchGesture)
        // ...
    } catch (e: Exception) {                    // ← catches ALL exceptions
        handleActionFailure(command, attempt, e.message ?: "Unknown error")
    }
}
```

When the `engineScope` is cancelled (e.g., `release()` is called), the coroutine receives `CancellationException`. But `catch (e: Exception)` catches `CancellationException` (it extends `IllegalStateException` → `RuntimeException` → `Exception` in Kotlin).

After catching it, `handleActionFailure()` is called which does `delay(delayMs)`. `delay()` on a cancelled coroutine **re-throws** `CancellationException`. So the coroutine eventually stops, but only after consuming retry delays (up to 500 + 1000 + 2000 = 3500ms) and logging spurious "Action failed" errors for every in-flight action when the engine is stopped.

**Correct fix:** `CancellationException` must be re-thrown immediately:
```kotlin
} catch (e: CancellationException) {
    throw e   // MUST re-throw
} catch (e: Exception) {
    handleActionFailure(command, attempt, e.message ?: "Unknown error")
}
```

**Severity: HIGH** — Engine takes up to 3.5 seconds to shut down instead of immediately, and logs false action failures on normal shutdown.

---

## 🔴 NC-3: `ActionEngine` on `Dispatchers.Main` — `executeWithRetry` Blocks Main Thread via `delay()` — ANR Risk

**File:** `android/app/src/main/kotlin/com/visionagent/core/action/ActionEngine.kt`  
**Lines:** 174–175, 200–215

```kotlin
private val engineScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Main + CoroutineName("ActionEngine")
)
```

The queue processor loop runs on `Dispatchers.Main`:
```kotlin
private fun startQueueProcessor() {
    engineScope.launch {          // ← on Main thread
        while (isActive) {
            val command = actionQueue.dequeueIfIdle()
            if (command != null) {
                try {
                    executeWithRetry(command)   // ← suspends on Main!
                }
                ...
                delay(16)                       // ← ok, yield
            }
        }
    }
}
```

`executeWithRetry` calls `executeTap` → `suspendCancellableCoroutine` → `dispatchGesture`. The gesture callback resumes the coroutine. Since this coroutine is on `Dispatchers.Main`, the `suspendCancellableCoroutine` DOES yield the main thread — that part is safe.

**BUT:** `handleActionFailure` calls `delay(RETRY_DELAY_BASE_MS * ...)` — up to 2000ms delay — ON THE MAIN THREAD COROUTINE. `delay()` in a coroutine does yield the thread, so it does not block the main thread. So no ANR per se.

**However:** `executeCommand` → `executeTextInput` calls `node.performAction()` which is **synchronous** and can block. And `executeNavigateBack` calls `performGlobalAction` which is **synchronous**. These blocking calls run on `Dispatchers.Main`. If `performAction` takes >100ms, the main thread is blocked and the UI becomes unresponsive.

**Also:** The entire `subscribeToDecisions()` listener also runs on `Dispatchers.Main`, calling `enqueueDecision()`. The `ActionRateLimiter.canExecute()` is called from `enqueueDecision()` which runs on Main — fine. But `enqueueDecision` calls various coordinate calculations. All harmless, but architecture is fragile.

**Root cause:** `ActionEngine` should use `Dispatchers.Default` for the queue processor and only post to `Dispatchers.Main` when calling `dispatchGesture()` which requires Main context. The blocking `performAction` calls must move to `Dispatchers.Default`.

**Severity: HIGH** — `performAction()` and `performGlobalAction()` block Main thread. Not an immediate crash but causes UI jank and potential ANR under slow system.

---

## 🔴 NC-4: `maxDurations` Race Condition — Non-Atomic Read-Modify-Write — `PerformanceTracker`

**File:** `android/app/src/main/kotlin/com/visionagent/core/performance/PerformanceTracker.kt`  
**Lines:** 96–100

```kotlin
maxDurations.getOrPut(operation) { AtomicLong(0) }.let { max ->
    if (durationMs > max.get()) max.set(durationMs)   // ← NOT atomic
}
```

This is a classic check-then-act race. Sequence:
1. Thread-A: `max.get()` returns 50, `durationMs` = 80 → condition true
2. Thread-B: `max.get()` returns 50, `durationMs` = 90 → condition true
3. Thread-A: `max.set(80)`
4. Thread-B: `max.set(90)` ← correct in this case

But another ordering:
1. Thread-A: `max.get()` = 50, `durationMs` = 90 → true
2. Thread-B: `max.get()` = 50, `durationMs` = 80 → true
3. Thread-B: `max.set(80)`
4. Thread-A: `max.set(90)` ← correct

And yet another:
1. Thread-A: `max.get()` = 50, `durationMs` = 80 → true
2. Thread-B: `max.get()` = 50, `durationMs` = 70 → false (70 < 80? No — 70 > 50, true)
3. Thread-B: `max.set(70)` ← maxDuration is now 70!
4. Thread-A: `max.set(80)` ← recovered, but 70 was wrong for a moment

More critically:
1. Thread-A: `max.get()` = 100, `durationMs` = 80 → false, skip
2. Thread-B: `max.get()` = 100, `durationMs` = 120 → true
3. Thread-A finishes
4. Thread-B: `max.set(120)` ← fine

But with interleaving:
1. Thread-A: reads 100, checks 80 < 100 → false
2. (Other thread reduced max somehow... wait, max only ever increases)
   
Since max only increases, the only real bug is: two threads can both pass the `>` check and both call `set()`, with the lower value overwriting the higher. This is a window of a few nanoseconds. **In practice this is very unlikely to corrupt meaningful data** since the JVM guarantees 64-bit `AtomicLong.set()` is atomic.

**But the correct fix is:**
```kotlin
maxDurations.getOrPut(operation) { AtomicLong(0) }.let { max ->
    var current = max.get()
    while (durationMs > current) {
        if (max.compareAndSet(current, durationMs)) break
        current = max.get()
    }
}
```

**Severity: LOW-MEDIUM** — Max latency stats may be occasionally incorrect (1-2ms window). Not a crash.

---

## 🔴 NC-5: `PerformanceTracker.start()` Key Collision — Shared `startTimes` ConcurrentHashMap With String Keys — Lost Timing Data

**File:** `android/app/src/main/kotlin/com/visionagent/core/performance/PerformanceTracker.kt`  
**Lines:** 83–87

```kotlin
private val startTimes = ConcurrentHashMap<String, Long>()  // op -> start time

fun start(operation: String): Long {
    val startTime = SystemClock.elapsedRealtimeNanos()
    startTimes[operation] = startTime    // ← OVERWRITES any previous entry!
    return startTime
}
```

Mental execution:
- `VisionEngine.processFrame()` calls `performanceTracker.start("vision_pipeline")` → stored
- Before `end()` is called, another frame arrives and calls `start("vision_pipeline")` again
- The first start time is **overwritten** → first operation's timing is lost
- When first frame calls `end("vision_pipeline", startTime, ...)`, it passes `startTime` as parameter (from the local variable), so `durationMs` is actually computed correctly from the local var

Wait — the `startTime` is passed back to the caller and used as parameter to `end()`. So the `startTimes` map is actually **not used** in `end()`:

```kotlin
fun end(operation: String, startTime: Long, sessionId: String): Long {
    val endTime = SystemClock.elapsedRealtimeNanos()
    val durationMs = (endTime - startTime) / 1_000_000L  // uses parameter, not map!
    // ...
    startTimes.remove(operation)    // ← removes wrong entry if concurrent
```

The `startTimes.remove(operation)` at line 101 removes the entry by key. If two concurrent operations of the same name are running, the second `start()` overwrites the first's value, and when the first `end()` calls `startTimes.remove(operation)`, it removes the second operation's entry. The second operation then calls `remove()` on a non-existent key (no-op). **The timing map is always out of sync**, but since timing is computed from local `startTime` parameter, the actual `durationMs` is correct.

**The actual bug:** `startTimes` map serves no purpose (it's written and removed but never read in `end()`). It's dead code that creates unnecessary contention on a `ConcurrentHashMap`. For 15 frames/sec × 4 operations = 60 puts/removes/sec with no reads — pure overhead.

**Severity: LOW** — Functionally harmless (timing still correct via parameter), but dead code creating unnecessary ConcurrentHashMap churn.

---

## 🔴 NC-6: `OTAUpdateManager.applyFromUrl()` Calls `URL().readText()` on Coroutine but NOT on `Dispatchers.IO` — Blocking the Calling Thread

**File:** `android/app/src/main/kotlin/com/visionagent/core/ota/OTAUpdateManager.kt`  
**Lines:** 123–133

```kotlin
suspend fun applyFromUrl(url: String): OTAResult {
    return try {
        withTimeout(15_000L) {
            val content = URL(url).readText()    // ← BLOCKING network call!
            val pkg     = json.decodeFromString<OTAPackage>(content)
            apply(pkg)
        }
    } catch (e: Exception) {
        OTAResult.Failed("URL fetch error: ${e.message}")
    }
}
```

`URL(url).readText()` is a **blocking** call from `java.net`. It is NOT a coroutine-aware HTTP client. `withTimeout()` does NOT make blocking code cancellable. If the server takes 14 seconds to respond, this blocks whatever thread the coroutine is dispatched on for 14 seconds.

The `otaScope` uses `Dispatchers.IO` so this will block an IO thread — not ideal but tolerable. However:

1. `checkForUpdates()` similarly calls `URL(...).readText()` on whatever dispatcher the caller uses.
2. `applyFromFile()` calls `file.readText()` synchronously — also blocking.
3. None of these are wrapped in `withContext(Dispatchers.IO)`.

If any caller dispatches on `Dispatchers.Default` or `Dispatchers.Main`, these block computational/UI threads.

**Fix:** Wrap all IO calls in `withContext(Dispatchers.IO) { ... }`.

**Severity: MEDIUM** — Blocks thread pools if called from wrong dispatcher. Causes UI jank if called from Main.

---

## 🔴 NC-7: `Logger.startAsyncWriter()` — Infinite `while(true)` Loop with Blocking `take()` — Coroutine Scope Never Cancels

**File:** `android/app/src/main/kotlin/com/visionagent/utils/Logger.kt`  
**Lines:** 86–92

```kotlin
private fun startAsyncWriter() {
    logScope.launch {
        while (true) {           // ← NOT `while(isActive)`!
            val entry = logBuffer.take()  // ← BLOCKING! Not coroutine-aware
            writeToFile(entry)
        }
    }
}
```

Two problems:

**Problem 1: `logBuffer.take()` is `ArrayBlockingQueue.take()` — it BLOCKS the thread permanently.** In a coroutine, this is equivalent to `Thread.sleep()` — it holds the thread without yielding. `logScope` uses `Dispatchers.IO` so it blocks an IO thread. At 1 logger instance = 1 blocked IO thread permanently. Tolerable but wrong.

**Problem 2: `while (true)` never checks `isActive`.** When the app shuts down, `logScope` is never cancelled (no `cancel()` call in `Logger`). Even if it were cancelled, `take()` does not respond to coroutine cancellation — it would block forever, preventing the scope from stopping.

**Result:** The logger thread leaks on app stop. On process death this is moot, but in unit tests or if Logger is re-initialized, the old writer coroutine continues running.

**Fix:**
```kotlin
logScope.launch {
    while (isActive) {
        // Poll with timeout to allow cancellation check
        val entry = logBuffer.poll(500L, TimeUnit.MILLISECONDS) ?: continue
        writeToFile(entry)
    }
}
```

**Severity: MEDIUM** — Thread leak in tests; uncancellable logger coroutine.

---

## 🔴 NC-8: `EventBus.subscribe<T>()` — Unsafe Cast `as SharedFlow<T>` — ClassCastException at Runtime

**File:** `android/app/src/main/kotlin/com/visionagent/core/event/EventBus.kt`  
**Line:** 258

```kotlin
inline fun <reified T : AgentEvent> subscribe(): SharedFlow<T> {
    return _events.filterIsInstance<T>() as SharedFlow<T>   // ← UNSAFE CAST
}
```

`_events.filterIsInstance<T>()` returns a `Flow<T>`, not a `SharedFlow<T>`. Casting `Flow<T>` to `SharedFlow<T>` with `as SharedFlow<T>` is an **unchecked cast** — it will compile but is **not actually a `SharedFlow`**. `filterIsInstance` wraps the flow in an internal `FilteringFlow` which does NOT implement `SharedFlow`.

**At runtime this currently "works"** because the callers use it as `SharedFlow<T>` only to call `onEach { }.launchIn(scope)` — which works on any `Flow<T>`. BUT if any caller calls `.replayCache`, `.subscriptionCount`, or any `SharedFlow`-specific property, it crashes with `ClassCastException`.

Additionally, the comment says "Uses reified type param for clean filtering" but the return type claims `SharedFlow<T>` which is a lie — subscribers don't get a shared hot flow, they get a cold `filterIsInstance` wrap. Multiple subscribers each get their own separate filter chain, which is actually correct behavior but the type contract is wrong.

**Fix:** Change return type to `Flow<T>`:
```kotlin
inline fun <reified T : AgentEvent> subscribe(): Flow<T> {
    return _events.filterIsInstance<T>()
}
```

**Severity: HIGH** — Wrong type contract. Works today but any code calling `SharedFlow`-specific APIs crashes at runtime.

---

## 🔴 NC-9: `frame_detect_change()` Hardcodes Width = 1080 — Wrong on All Non-1080p Devices

**File:** `android/app/src/main/cpp/frame_processor/FrameProcessor.cpp`  
**Line:** 380–384

```cpp
ROIChangeResult frame_detect_change(const uint8_t* frame,
                                     int n_bytes,
                                     float threshold) {
    ROIChangeResult result = {};
    if (!g_prev_frame || n_bytes != g_frame_size) return result;

    result = detect_roi_change(g_prev_frame, frame,
                                1080,                          // ← HARDCODED width!
                                g_frame_size / (1080 * 4),    // ← height derived from 1080
                                threshold);
```

The `detect_roi_change()` function divides the frame into a 4×4 grid using `width` and `height`. With hardcoded `width = 1080`, on a 1440p device (width = 1440), the grid blocks are computed incorrectly. On a device where `g_frame_size = 1440 * 3200 * 4`:
- Hardcoded: `height = g_frame_size / (1080 * 4) = 1440 * 3200 * 4 / (1080 * 4) = 4266`
- Block calculations: `block_h = 4266 / 4 = 1066` — but actual image height is 3200, not 4266

The ROI grid blocks now extend beyond the actual frame data → **buffer over-read** in `compute_frame_diff_neon()`. The NEON code reads `frame1 + row_start + col_start` where `row_start = y * width * 4`. With wrong width, `row_start` can exceed `n_bytes` → **undefined behaviour / segfault** on non-1080p devices.

**Fix:** Store width/height at `frame_processor_init()` time and use them in `frame_detect_change()`.

**Severity: CRITICAL** — Buffer over-read / segfault on any non-1080-width device.

---

## 🔴 NC-10: `OCRCore.cpp` — `g_ocr.pre_denoised` and `g_ocr.pre_binary` are Shared Mutable Buffers Written by `preprocess_for_ocr()` — Residual Data Race

**File:** `android/app/src/main/cpp/ocr/OCRCore.cpp`  
**Lines:** 131–149

```cpp
struct OCRState {
    // ...
    cv::Mat pre_gray;
    cv::Mat pre_binary;      // ← shared mutable buffer
    cv::Mat pre_denoised;    // ← shared mutable buffer
};
static OCRState g_ocr;
```

```cpp
cv::Mat preprocess_for_ocr(const cv::Mat& input_rgba, int level) {
    // ...
    g_ocr.clahe->apply(gray, clahe_result);
    cv::GaussianBlur(clahe_result, g_ocr.pre_denoised, ...);  // ← writes g_ocr.pre_denoised
    cv::threshold(g_ocr.pre_denoised, g_ocr.pre_binary, ...); // ← writes g_ocr.pre_binary
    cv::morphologyEx(g_ocr.pre_binary, g_ocr.pre_binary, ...);
    return g_ocr.pre_binary;   // ← returns reference to shared buffer!
}
```

The comment says "Tesseract is NOT thread-safe, use mutex from JNI side." The Kotlin `OCREngine` uses `newSingleThreadContext("OCRThread")` to serialize Tesseract calls. **So the Tesseract API itself is protected.** But `preprocess_for_ocr()` returns `g_ocr.pre_binary` — a reference to a shared `cv::Mat` buffer. This buffer is passed to Tesseract:

```cpp
Pix* pix = mat_to_pix(processed);  // processed = g_ocr.pre_binary
g_ocr.api->SetImage(pix);
```

Since OCR is serialized via `OCRThread`, there's no concurrency here in practice. **However:** the returned `cv::Mat` is a reference to the global buffer. If `ocr_release()` is called concurrently (from a different thread, e.g., app shutdown), it destroys `g_ocr` including `pre_binary`, while `ocr_extract_text()` is still using it. The OCR single thread is not guarded against concurrent `ocr_release()`.

**Also:** the previous C-1 fix made `VisionCore` use local buffers. `OCRCore` still uses `g_ocr` shared buffers. While serialized through Kotlin, the architecture is brittle and relies on external caller discipline.

**Severity: MEDIUM** — Crash on concurrent `ocr_release()` during active OCR. Requires `std::mutex` at C++ level.

---

## 🔴 NC-11: `RemoteDashboardServer.parseRequest()` — No Request Size Limit — OOM / DoS Attack

**File:** `android/app/src/main/kotlin/com/visionagent/core/remote/server/RemoteDashboardServer.kt`  
**Lines:** 724–742

```kotlin
val bodyLen = headers["content-length"]?.toIntOrNull() ?: 0
val body = if (bodyLen > 0) {
    val buf = CharArray(bodyLen)   // ← allocates whatever the client claims!
    reader.read(buf)
    String(buf)
} else ""
```

A malicious client (or a misconfigured one) on the local network can send:
```
POST /api/workflow/run HTTP/1.1
Content-Length: 2147483647
```

This allocates `CharArray(2147483647)` = 4GB → `OutOfMemoryError`. The server crashes. Since there's no authentication, any device on the WiFi can trigger this.

Even at reasonable sizes: a request with `Content-Length: 10485760` (10MB) allocates 10MB per request. With multiple concurrent connections, this exhausts heap.

**Fix:** Add a maximum body size check:
```kotlin
val bodyLen = (headers["content-length"]?.toIntOrNull() ?: 0)
    .coerceAtMost(1024 * 1024)  // Max 1MB body
```

**Severity: HIGH** — OOM DoS on local network. No authentication required.

---

## 🔴 NC-12: `ScreenCaptureEngine` — `config` and `fpsController` Written Without `@Volatile` — Visibility Gap Between Threads

**File:** `android/app/src/main/kotlin/com/visionagent/core/screen/ScreenCaptureEngine.kt`  
**Lines:** 122, 128, 400–402

```kotlin
private var fpsController: FPSController = FPSController(15)
private var config = CaptureConfig()

fun updateFps(newFps: Int) {
    config = config.copy(targetFps = newFps)    // ← written from calling thread
    fpsController = FPSController(newFps)        // ← written from calling thread
}
```

`updateFps()` can be called from any thread (e.g., `AdaptiveFPSController` from `Dispatchers.Default`). The `imageAvailableListener` runs on `captureHandler` (a `HandlerThread`). Without `@Volatile`, writes to `config` and `fpsController` on Thread-A may not be visible on Thread-B (the HandlerThread).

Specifically:
- `config.enableROI` is read in the callback — could see old value
- `config.jpegQuality` is read in `compressBitmap()` — could see old value
- `fpsController.shouldCapture()` uses the old controller — FPS doesn't actually update

On modern ARM processors with cache coherency protocols, this likely works in practice, but it is technically undefined behaviour on JVM without the memory barrier that `@Volatile` provides.

**Fix:** Mark both fields `@Volatile`.

**Severity: MEDIUM** — FPS updates may not take effect immediately or at all on some devices.

---

## 🔴 NC-13: `Workflow` HTTP Request Block — `connection.inputStream` Not Closed — Socket File Descriptor Leak

**File:** `android/app/src/main/kotlin/com/visionagent/core/workflow/engine/WorkflowEngine.kt`  
**Lines:** 493–510

```kotlin
private suspend fun executeHttpRequest(block: HttpRequestBlock, ctx: WorkflowContext): BlockResult {
    return try {
        val url = ctx.interpolate(block.url)
        withTimeout(block.timeoutMs) {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = block.method
            connection.connectTimeout = 5000
            connection.readTimeout    = block.timeoutMs.toInt()
            block.headers.forEach { (k, v) -> connection.setRequestProperty(k, ctx.interpolate(v)) }
            val response = connection.inputStream.bufferedReader().readText()  // ← stream not closed
            ctx.setVar(block.responseVariable, response.take(10_000))
            ctx.setVar("${block.responseVariable}_code", connection.responseCode.toString())
            BlockResult.Success                                                // ← returns without closing
        }
    } catch (e: Exception) { ... }
}
```

`connection.inputStream` is opened but never closed. `connection.disconnect()` is never called. Each HTTP workflow block execution leaks one socket file descriptor. Android allows ~1024 open file descriptors per process. A workflow running HTTP requests every few seconds will exhaust FDs within minutes, causing `java.io.IOException: Too many open files`.

**Fix:**
```kotlin
try {
    connection.connect()
    val response = connection.inputStream.use { it.bufferedReader().readText() }
    // ...
} finally {
    connection.disconnect()
}
```

**Severity: HIGH** — File descriptor leak → process crashes with "Too many open files" after minutes of HTTP workflow use.

---

## 🔴 NC-14: `RuleRegistry` Not Thread-Safe — `sortedMapOf` with `MutableList` Values — Concurrent Modification

**File:** `android/app/src/main/kotlin/com/visionagent/core/rule/RuleEngine.kt`  
**Lines:** 177–213

```kotlin
class RuleRegistry {
    private val rules = sortedMapOf<Int, MutableList<Rule>>(compareByDescending { it })

    fun register(rule: Rule) {
        rules.getOrPut(rule.priority) { mutableListOf() }.add(rule)  // ← not synchronized
    }

    fun getOrderedRules(): List<Rule> =
        rules.values.flatten().filter { it.isEnabled }               // ← reads while register() might add
```

`RuleRegistry` has no synchronisation whatsoever. `RuleEngine.loadDefaultRules()` calls `register()` during `initialize()`. `OTAUpdateManager.applyRules()` calls `ruleEngine.registerRule()` which calls `register()`. Both happen on different threads.

`rules.getOrPut()` on a `sortedMapOf` (which is a `TreeMap`) is not thread-safe. Concurrent `getOrPut` + `flatten()` can:
- Cause `ConcurrentModificationException` in `flatten()`
- Corrupt the TreeMap internal structure (red-black tree rebalancing is not thread-safe)

**Fix:** Add `@Synchronized` to all methods, or replace with `ConcurrentHashMap<Int, CopyOnWriteArrayList<Rule>>`.

**Severity: HIGH** — `ConcurrentModificationException` crash when OTA updates rules while agent is evaluating.

---

# PART 2 — PREVIOUSLY REPORTED, NOW VERIFIED AS STILL PRESENT

---

## 🟠 V-1: `ScreenCaptureEngine` — `previousFrameHash` Not `@Volatile` — Stale Hash Cross-Thread

**Lines:** 134, 296–302

`previousFrameHash` is written by the `HandlerThread` (ImageReader callback) and... only read by the same HandlerThread. So actually this is single-threaded access — **not a race**. But `updateFps()` is called from a different thread and reassigns `fpsController` (NC-12 above). This is the real issue.

Actually verified: `previousFrameHash` is only ever accessed from `imageAvailableListener` which runs on `captureHandler` (single HandlerThread). No race here. ✓

---

## 🟠 V-2: `WorkflowEngine.executeParallel()` — Leaked Coroutine Scope

**Line:** 404

```kotlin
CoroutineScope(Dispatchers.Default).async { ... }
```

`CoroutineScope(Dispatchers.Default)` creates an **unstructured scope** with no parent. If the workflow is cancelled (via `stopAll()`), these anonymous scopes continue running. They are never cancelled.

**Fix:** Use the existing `engineScope` for structured concurrency:
```kotlin
engineScope.async { executeBlocks(branch, branchCtx) }
```

**Severity: MEDIUM** — Workflow branches continue running after workflow is cancelled.

---

## 🟠 V-3: `Logger.writeToFile()` Uses `appendText()` — New FileWriter per Log Line — Severe Performance Issue

**File:** `android/app/src/main/kotlin/com/visionagent/utils/Logger.kt`  
**Lines:** 163–169

```kotlin
private fun writeToFile(entry: LogEntry) {
    // ...
    file.appendText(line)          // opens FileWriter, writes, closes — every time!
    entry.throwable?.let { e ->
        file.appendText("  Exception: ...")  // another open/write/close
        e.stackTrace.take(5).forEach { frame ->
            file.appendText("    at $frame\n")  // and again for each frame!
        }
    }
}
```

`File.appendText()` opens a `FileWriter`, writes the string, and closes it on every call. At 50 log lines/sec, this is 50 file open/write/close operations per second. Each involves a `FileOutputStream` allocation, a `flush()`, and a `close()`. On Android's ext4 filesystem, `fsync()` may be called on close, making this extremely slow.

A single exception with 5 stack frames = 7 file open/close cycles for one log entry.

**Fix:** Keep a persistent `BufferedWriter` open, flush every N lines or on a timer.

**Severity: MEDIUM** — Logger slows down entire system under high log volume. May cause >16ms delays on IO thread.

---

## 🟠 V-4: `SelfDiagnosticEngine.checkJNILibraries()` — Calling `System.loadLibrary()` Diagnostic May Throw `UnsatisfiedLinkError` on Already-Loaded Library — Incorrect Result

**File:** `android/app/src/main/kotlin/com/visionagent/core/diagnostic/SelfDiagnosticEngine.kt`  
**Lines:** 428–438

```kotlin
val loaded = try {
    System.loadLibrary(lib)    // ← if already loaded, this is a no-op
    true
} catch (e: UnsatisfiedLinkError) {
    jniLoadStatus[lib] ?: false
}
```

`System.loadLibrary()` called on an already-loaded library is a no-op and returns normally — **does not throw**. So this check always returns `true` if the library was ever successfully loaded (even if the native code subsequently failed). And if the library was never loaded (because the .so file is missing from the APK), `System.loadLibrary()` throws `UnsatisfiedLinkError`, and the fallback `jniLoadStatus[lib]` is only populated if the engine had already tried and stored the result.

The logic is backwards: if the initial load failed, `jniLoadStatus[lib]` is `false` or null. The diagnostic correctly returns `false`. If the library IS present, `System.loadLibrary()` succeeds (no-op) and returns `true`. So the logic is actually correct in the end... BUT:

**The real bug:** `System.loadLibrary()` in a diagnostic check that runs every 5 minutes could trigger a library load that shouldn't happen during normal operation. If the library has not been loaded yet (impossible since the companion object `init {}` blocks load them at class instantiation), this would cause an unexpected load.

**Not a critical bug** — the companion `init {}` blocks in `VisionNativeBridge`, `OCRNativeBridge`, `ROIChangeDetector` load libraries at class-init time. By the time the diagnostic runs, all libraries are already loaded or definitively failed. The diagnostic check is redundant but harmless.

**Severity: LOW** — Redundant check, not a bug.

---

## 🔵 V-5: `ScriptEngine.eval()` — Operator Search with `i > 0` Guard Still Wrong for Multi-Char Operators

**File:** `android/app/src/main/kotlin/com/visionagent/core/workflow/script/ScriptEngine.kt`  
**Lines:** 242–243

```kotlin
!inStr && depth == 0 && i > 0 &&
expr.substring(i).startsWith(op) -> return i
```

The operators are checked in this order: `+`, `-`, `*`, `/`, `%`, `==`, `!=`, `>`, `<`, `>=`, `<=`.

Problem: For `"5 >= 3"`, when checking `>` (before `>=`), at position 2 (the `>` char), `expr.substring(2).startsWith(">")` = true → returns 2. The `>=` operator is never checked. So `"5 >= 3"` is parsed as `"5"` `>` `"= 3"` → `eval("= 3")` returns `"= 3"` (unrecognized) → `"5" > "= 3"` is numeric comparison → both `toDoubleOrNull()` are null → result is `false`.

Expected: `"5 >= 3"` should return `true`.

**Fix:** Check longer operators first in the list: `listOf("==", "!=", ">=", "<=", ">", "<", "+", "-", "*", "/", "%")`.

**Severity: LOW** — `>=` and `<=` operators silently broken in ScriptEngine.

---

## 🔵 V-6: `HNSW.insert()` — Early Return Inside `synchronized(lock)` Without Unlocking

**File:** `android/app/src/main/kotlin/com/visionagent/core/memory/vector/VectorMemory.kt`  
**Lines:** 138–141

```kotlin
fun insert(entry: VectorEntry) = synchronized(lock) {
    // ...
    if (entryPoint == -1) {
        entryPoint = idx
        maxLayer   = level
        return       // ← bare `return` from inside `synchronized` lambda
    }
    // ...
}
```

In Kotlin, `synchronized(lock) { ... }` is an inline function. A bare `return` inside it returns from the **enclosing function** (`insert()`), NOT from the lambda. Since `synchronized` is an inline function, the lock IS properly released on return from the lambda (the `monitorExit` bytecode instruction is generated by the compiler after the lambda body, including on early return paths). 

**So this is actually safe in Kotlin/JVM.** The lock is released even on `return`. ✓

**Severity: NONE** — Not a bug. Kotlin inline synchronized handles early return correctly.

---

## 🔵 V-7: `AutoBackupManager.createBackup()` — `getExternalFilesDir(null)` Can Return `null` — NPE

**File:** `android/app/src/main/kotlin/com/visionagent/core/backup/AutoBackupManager.kt`  
**Lines:** 127–129

```kotlin
val macroDir  = File(context.getExternalFilesDir(null), "macros")
val otaDir    = File(context.getExternalFilesDir(null), "ota_packages")
val crashDir  = File(context.getExternalFilesDir(null), "crash_replay")
```

`Context.getExternalFilesDir()` returns `null` if external storage is not mounted (e.g., device has no SD card AND external emulated storage is not available, which can happen during direct boot mode or on some edge cases). `File(null, "macros")` throws `NullPointerException`.

Same issue exists in `backupDir` initializer at line 91 (though it uses `also { it.mkdirs() }` which would NPE there too).

**Fix:** Use `?: context.filesDir` as fallback:
```kotlin
val macroDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "macros")
```

**Severity: LOW-MEDIUM** — NPE on devices without external storage during backup creation.

---

## 🔵 V-8: `DiagnosticCheck` Data Class — Missing `weight` in Default `DiagnosticCheck(...)` Constructor Calls

**File:** `android/app/src/main/kotlin/com/visionagent/core/diagnostic/SelfDiagnosticEngine.kt`  
**Lines:** 103–113

```kotlin
private suspend fun runCheck(...): DiagnosticCheck = try {
    val result = withTimeout(3000L) { block() }
    result.copy(durationMs = ..., weight = weight)  // ← overwrites correctly
} catch (e: Exception) {
    DiagnosticCheck(name, DiagnosticStatus.WARNING, "error", "N/A",
        "Check failed: ${e.message}", 0L, weight)    // ← passes weight correctly
}
```

This is actually correct — `weight` is properly handled. ✓

---

# PART 3 — IMPLEMENTATION GAPS (Code that Exists but Does Nothing Useful)

---

## 🟡 IG-1: `AgentOrchestrator.savePerformanceLog()` and `saveErrorLog()` Write to DB but DAOs Exist — Verify Entity Match

**File:** `android/app/src/main/kotlin/com/visionagent/core/AgentOrchestrator.kt`

```kotlin
private suspend fun savePerformanceLog(event: PerformanceMetricEvent) {
    database.performanceLogDao().insert(
        com.visionagent.data.local.entity.PerformanceLogEntity(...)
    )
}
```

This exists and seems correct. ✓

---

## 🟡 IG-2: `TextInputCommand.nodeInfo` is Always `null` in All Call Sites — Text Input Never Works

**File:** `android/app/src/main/kotlin/com/visionagent/core/action/ActionEngine.kt`  
**Lines:** 247–256, 451–460

```kotlin
ActionType.TEXT_INPUT -> {
    val text = decision.parameters["text"] as? String ?: ""
    TextInputCommand(text = text, nodeInfo = null, sessionId = sessionId)  // ← nodeInfo = null!
}
```

```kotlin
private suspend fun executeTextInput(command: TextInputCommand): Boolean {
    val node = command.nodeInfo ?: return false    // ← always returns false!
    // ...
}
```

Every `TextInputCommand` is created with `nodeInfo = null`. `executeTextInput` immediately returns `false` when `nodeInfo` is null. **Text input never works.** This is a complete feature gap — not a crash, but silently broken functionality.

**Fix:** Before creating the command, use `AgentAccessibilityService.findNodeByText()` or `findNodeById()` to locate the target input node, then pass it.

**Severity: HIGH** — Core feature (text input) silently does nothing.

---

## 🟡 IG-3: `MacroRecorder.subscribeToEvents()` Records `ActionType` But Not Coordinates — Replay Uses Hardcoded Coordinates

**File:** `android/app/src/main/kotlin/com/visionagent/core/workflow/macro/MacroRecorder.kt`  
**Lines:** 89–118

```kotlin
recordedActions.add(RecordedAction(
    sequenceIdx      = sequenceIdx++,
    actionType       = event.actionType,
    screenTypeBefore = lastScreenType,
    durationMs       = event.durationMs
    // ← x, y coordinates NOT recorded (null defaults)
))
```

`ActionExecutedEvent` does not carry coordinates. So recorded macros have no coordinate data. During `macroToWorkflow()`:

```kotlin
ActionType.TAP -> {
    if (action.elementType != null) {
        // Uses VisionFindBlock to re-find element — OK
    } else {
        listOf(ActionBlock(
            label = "${action.actionType.name} (${action.x?.toInt()}, ${action.y?.toInt()})",
            // ← x and y are null → produces "TAP (null, null)"
            actionType = action.actionType
        ))
    }
}
```

Taps without element context are recorded without coordinates and replayed without coordinates. The replay cannot actually tap anything meaningful.

**Fix:** `ActionExecutedEvent` needs to carry coordinates, or the macro recorder needs to subscribe to `UIElementDetectedEvent` + `RuleEvaluatedEvent` to capture the intended target.

**Severity: MEDIUM** — Macro replay for coordinate-based taps is broken.

---

## 🟡 IG-4: `PlannerEngine.planCache` — Plain `mutableMapOf()` Shared Between Coroutines — Unsynchronized

**File:** `android/app/src/main/kotlin/com/visionagent/core/planner/PlannerEngine.kt`  
**Line:** 261

```kotlin
private val planCache = mutableMapOf<String, ExecutionPlan>()
```

`submitGoal()` is a `suspend` function called from `AgentOrchestrator` on `Dispatchers.Main`. `advancePlan()` is called from an event subscriber on `Dispatchers.Default`. Both access `planCache` without synchronisation. `mutableMapOf()` (a `LinkedHashMap`) is not thread-safe.

**Severity: MEDIUM** — `ConcurrentModificationException` or corrupted plan cache under concurrent goal submission.

---

# SUMMARY OF NEW FINDINGS

| ID | Severity | Location | Bug |
|----|----------|----------|-----|
| NC-1 | 🟠 HIGH | HNSWIndex | `ConcurrentModificationException` during neighbor pruning inside synchronized insert |
| NC-2 | 🟠 HIGH | ActionEngine | `CancellationException` swallowed in `catch (e: Exception)` → slow shutdown |
| NC-3 | 🟠 HIGH | ActionEngine | `performAction()` blocks Main thread synchronously → potential ANR |
| NC-4 | 🔵 LOW | PerformanceTracker | Non-atomic check-then-set on `maxDurations` |
| NC-5 | 🔵 LOW | PerformanceTracker | `startTimes` ConcurrentHashMap is dead code — never read in `end()` |
| NC-6 | 🟡 MEDIUM | OTAUpdateManager | Blocking `URL().readText()` without `withContext(Dispatchers.IO)` |
| NC-7 | 🟡 MEDIUM | Logger | `while(true)` + blocking `take()` → uncancellable logger coroutine |
| NC-8 | 🟠 HIGH | EventBus | `Flow<T> as SharedFlow<T>` unsafe cast — wrong type contract |
| NC-9 | 🔴 CRITICAL | FrameProcessor.cpp | Hardcoded width=1080 → buffer over-read / segfault on non-1080p devices |
| NC-10 | 🟡 MEDIUM | OCRCore.cpp | Shared `g_ocr` buffers returned by reference; crash if `ocr_release()` concurrent |
| NC-11 | 🟠 HIGH | RemoteDashboardServer | No body size limit → OOM DoS from any local-network client |
| NC-12 | 🟡 MEDIUM | ScreenCaptureEngine | `config` and `fpsController` not `@Volatile` → FPS updates invisible |
| NC-13 | 🟠 HIGH | WorkflowEngine | HTTP connection InputStream not closed → file descriptor leak |
| NC-14 | 🟠 HIGH | RuleRegistry | `sortedMapOf` + `MutableList` not synchronized → `ConcurrentModificationException` |
| V-2 | 🟡 MEDIUM | WorkflowEngine | Parallel branches use `CoroutineScope(Dispatchers.Default)` → leaked scope |
| V-3 | 🟡 MEDIUM | Logger | `appendText()` = file open/write/close per line → severe IO overhead |
| V-5 | 🔵 LOW | ScriptEngine | `>=` and `<=` operators broken — shorter `>` and `<` matched first |
| V-7 | 🔵 LOW | AutoBackupManager | `getExternalFilesDir(null)` can return null → NPE |
| IG-2 | 🟠 HIGH | ActionEngine | `TextInputCommand.nodeInfo` always null → text input silently does nothing |
| IG-3 | 🟡 MEDIUM | MacroRecorder | Tap coordinates not captured → macro replay broken for coordinate taps |
| IG-4 | 🟡 MEDIUM | PlannerEngine | `planCache` unsynchronized `mutableMapOf()` |

---

## TOP PRIORITY FIXES (Ordered by Impact)

1. **NC-9** — Hardcoded 1080 width in FrameProcessor → segfault on most devices
2. **NC-14** — RuleRegistry concurrent modification → crash during OTA rule update
3. **NC-8** — EventBus unsafe cast → type contract violation
4. **NC-13** — HTTP FD leak → process crash after minutes of workflow use
5. **NC-2** — CancellationException swallowed → slow shutdown + spurious errors
6. **NC-3** — ActionEngine on Dispatchers.Main + blocking calls → ANR risk
7. **IG-2** — Text input permanently broken
8. **NC-11** — No body size limit → OOM DoS
9. **NC-7** — Logger infinite loop blocks IO thread
10. **NC-6** — Blocking network call without IO dispatcher
11. **NC-12** — config/@Volatile missing → FPS updates lost
12. **V-2** — Leaked parallel workflow scopes
13. **V-3** — Logger IO performance
14. **IG-4** — PlannerEngine unsynchronized cache
15. **V-5** — `>=` `<=` broken in ScriptEngine
