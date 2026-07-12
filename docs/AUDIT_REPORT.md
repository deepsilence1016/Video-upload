# Security, Performance & Architecture Audit
## Vision Agent Framework — Senior Engineer Review
**Auditor role:** Google/OpenAI-level Staff Engineer  
**Scope:** All 119 files — Kotlin · C++ · Rust · Python  
**Date:** 2026-07-09  
**Verdict:** Not production-ready as-is. 11 critical bugs found.

---

## SEVERITY LEGEND
| Level | Meaning |
|---|---|
| 🔴 CRITICAL | Data loss, crash, security breach, or silent corruption in production |
| 🟠 HIGH | Likely crash or major performance regression under real load |
| 🟡 MEDIUM | Incorrect behaviour, potential memory leak, or bad design that will cause pain |
| 🔵 LOW | Code smell, style, or minor correctness issue |

---

# PART 1 — CRITICAL BUGS (Fix before any release)

---

## 🔴 C-1: Data Race on `g_state` — VisionCore.cpp

**File:** `android/app/src/main/cpp/vision/VisionCore.cpp`  
**Lines:** 79, 187–232, 611–630

**The bug:**
```cpp
static VisionState g_state;   // One global instance

// OpenMP spawns 3 PARALLEL threads, ALL writing to g_state:
#pragma omp parallel sections num_threads(3)
{
    #pragma omp section
    { buttons = detect_buttons(frame_color, g_state.blur_buffer, ...); }
    // ↑ reads g_state.blur_buffer

    #pragma omp section
    { text_regions = detect_text_regions(g_state.blur_buffer, ...); }
    // ↑ reads SAME g_state.blur_buffer simultaneously

    #pragma omp section
    { popups = detect_popups(frame_color, g_state.blur_buffer, ...); }
    // ↑ reads SAME g_state.blur_buffer simultaneously
}
```

`detect_buttons()` writes to `g_state.thresh_buffer`, `g_state.morph_buffer`, `g_state.hsv_buffer` (lines 187–203).  
`detect_popups()` also writes to `g_state.hsv_buffer` (same buffer).  
These are **concurrent writes to the same cv::Mat** with no synchronisation.

**Result:** Undefined behaviour. On ARM64, partial writes to cv::Mat data pointer cause:
- Corrupted detection results (silent, hardest to debug)
- Segfault when one thread resizes the buffer while another reads it
- ThreadSanitizer will flag this immediately

**Fix required:** Either give each parallel section its own working buffers, or remove OpenMP and process sequentially (safer, simpler, still fast enough at 15fps).

---

## 🔴 C-2: Race Condition on `retryCount`, `latestScreenType`, `latestElements`, `latestOCRText` — RuleEngine.kt

**File:** `android/app/src/main/kotlin/com/visionagent/core/rule/RuleEngine.kt`  
**Lines:** 279–283, 295–316

**The bug:**
```kotlin
// These are plain `var` — no @Volatile, no synchronisation
private var latestScreenType: ScreenType = ScreenType.UNKNOWN
private var latestElements: List<DetectedUIElement> = emptyList()
private var latestOCRText: String = ""
private var retryCount: Int = 0
private var lastActionTimestamp: Long = 0L
```

These are written in three **separate** coroutine flows (Vision events, OCR events, Action events) and read in `evaluateRules()`. All run on `Dispatchers.Default` which uses a thread pool. On a multi-core ARM64 device, a write from thread A is **not guaranteed to be visible** to thread B without a memory barrier.

**Specific failure scenario:**  
- OCRCompletedEvent writes `latestOCRText = "Submit"` on Thread-1  
- UIElementDetectedEvent calls `evaluateRules()` on Thread-2  
- Thread-2 reads stale `latestOCRText = ""` → rule misses  
- Rule evaluates wrong, wrong action fires

**Also:** `retryCount++` and `retryCount = 0` are not atomic. On JVM this is a read-modify-write that can lose increments under concurrent access.

**Fix required:** Mark all five fields `@Volatile`, or use `AtomicInteger` for `retryCount`, or consolidate state updates into a single `MutableStateFlow<EvaluationContext>` that is atomically replaced.

---

## 🔴 C-3: TOCTOU Race in `ActionQueue` — ActionEngine.kt

**File:** `android/app/src/main/kotlin/com/visionagent/core/action/ActionEngine.kt`  
**Lines:** 127–137, 186–193

**The bug:**
```kotlin
// Check and act are TWO SEPARATE synchronized calls — not atomic together
if (!actionQueue.isEmpty() && !actionQueue.isCurrentlyExecuting()) {
    actionQueue.dequeue()?.let { command ->    // ← Another thread can dequeue here
        actionQueue.markExecuting(true)
```

Between `isEmpty()` and `dequeue()`, another coroutine (resumed on a different thread from `Dispatchers.Main` thread pool) could also pass the check and both call `dequeue()`. Because `isEmpty()` and `dequeue()` are separately `@Synchronized`, the compound check is not atomic.

**Result:** Two coroutines could execute the same action concurrently, or `dequeue()` on an empty queue producing null that silently drops work.

**Fix required:** Combine the check and dequeue into a single synchronized method:
```kotlin
@Synchronized fun dequeueIfIdle(): ActionCommand? {
    if (isExecuting || queue.isEmpty()) return null
    isExecuting = true
    return queue.removeFirstOrNull()
}
```

---

## 🔴 C-4: `@Volatile` on Mutable Map is Not Thread-Safe — ModelManager.kt

**File:** `android/app/src/main/kotlin/com/visionagent/core/ai/model_manager/ModelManager.kt`  
**Line:** 137

**The bug:**
```kotlin
@Volatile private var handles = mutableMapOf<ModelType, ModelHandle>()
```

`@Volatile` makes the **reference** `handles` visible across threads, but does **not** protect the **contents** of the `mutableMapOf`. Concurrent reads and writes to the map's internal state (e.g., `handles[type] = newHandle` and `handles.containsKey(type)` on two threads) are a data race.

**Specifically in `hotSwap()`:**
```kotlin
val oldHandle = handles[type]    // Thread A reads
handles[type] = newHandle        // Thread A writes
// Thread B calling infer() simultaneously:
val handle = handles[type]       // Thread B sees partially-updated map → potential NPE or wrong handle
```

**Fix required:** Replace with `ConcurrentHashMap<ModelType, ModelHandle>()`. Remove `@Volatile`.

---

## 🔴 C-5: Lock-Free FramePool Has ABA / Out-of-Bounds Bug — FrameProcessor.cpp

**File:** `android/app/src/main/cpp/frame_processor/FrameProcessor.cpp`  
**Lines:** 63–88

**The bug in `release()`:**
```cpp
void release(uint8_t* ptr) {
    // ...
    int idx = free_count_.fetch_add(1, std::memory_order_acq_rel);
    if (idx < buffer_count_) {
        free_list_[idx].store(ptr, std::memory_order_release);
    }
}
```

`fetch_add` increments `free_count_` **before** storing the pointer. If two threads call `release()` simultaneously:
- Thread A: `idx = fetch_add(1)` → gets index 4, increments to 5
- Thread B: `idx = fetch_add(1)` → gets index 5, increments to 6
- Thread A stores at `free_list_[4]`
- Thread B stores at `free_list_[5]`

So far fine. But then in `acquire()`:
```cpp
if (free_count_.compare_exchange_weak(count, count - 1, ...)) {
    return free_list_[count - 1].load(std::memory_order_acquire);
}
```

`acquire()` can return `free_list_[count-1]` **before** `release()` has stored the pointer there (the index incremented but the store hasn't happened yet). This returns **a null or garbage pointer**.

**Result:** Vision Engine receives a garbage buffer → segfault or silent memory corruption.

**Fix required:** Use a proper lock-based pool for this (Spinlock is sufficient), or use a well-tested lock-free stack (Hazard Pointers). The custom lock-free implementation is incorrect.

---

## 🔴 C-6: `saveCrashSnapshotSync()` Calls `buildSnapshot()` Which Allocates — CrashReplaySystem.kt

**File:** `android/app/src/main/kotlin/com/visionagent/core/crash/CrashReplaySystem.kt`  
**Lines:** 175–223

**The bug:**
```kotlin
Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
    saveCrashSnapshotSync(...)    // ← Called from crash handler
}

private fun saveCrashSnapshotSync(...) {
    val snapshot = buildSnapshot(...)    // Allocates new objects
    file.writeText(json.encodeToString(snapshot))  // JSON serialisation = massive allocation
}
```

The JVM crash handler is invoked **after** an uncaught exception — which may have been caused by `OutOfMemoryError`. Calling `buildSnapshot()` allocates a `CrashSnapshot` object with:
- `List<EventRecord>` (100 items × string copies)
- `Map<String, String>` (50 STM entries)
- JSON serialisation (string builders, arrays)

If the crash was `OutOfMemoryError`, these allocations will **throw another OOM** inside the crash handler, killing the handler before writing the file. The crash report is never saved.

**Fix required:** Pre-allocate the crash snapshot buffer at startup. In the crash handler, only copy into that pre-allocated buffer. Use `RandomAccessFile` with a fixed-size byte array rather than `writeText`.

---

## 🔴 C-7: `SYSTEM_ALERT_WINDOW` Permission Missing — DeveloperDashboard.kt

**File:** `android/app/src/main/kotlin/com/visionagent/core/dashboard/DeveloperDashboard.kt`  
**Line:** 157  
**File:** `android/app/src/main/AndroidManifest.xml`

**The bug:**
```kotlin
val params = WindowManager.LayoutParams(
    ...
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,   // ← Requires SYSTEM_ALERT_WINDOW
    ...
)
windowManager.addView(overlayView, params)
```

`TYPE_APPLICATION_OVERLAY` requires the `SYSTEM_ALERT_WINDOW` permission **and** the user must grant it via `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`. The manifest does not declare this permission. There is also no runtime check with `Settings.canDrawOverlays(context)` before calling `addView()`.

**Result:** `WindowManager$BadTokenException` thrown at runtime → app crash.

**Fix required:** Add to manifest: `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>`. Add a guard:
```kotlin
if (!Settings.canDrawOverlays(context)) {
    // Request permission via Intent
    return
}
```

---

## 🔴 C-8: HTTP (Non-TLS) OTA URL + Hardcoded HMAC Key — OTAUpdateManager.kt

**File:** `android/app/src/main/kotlin/com/visionagent/core/ota/OTAUpdateManager.kt`  
**Lines:** 86, 284

**Bug 1:**
```kotlin
private const val OTA_CHECK_URL = "http://your-server.com/api/ota/latest"
//                                  ^^^^ plaintext HTTP
```
Android 9+ blocks cleartext HTTP by default (`usesCleartextTraffic = false`). This will throw `java.io.IOException: Cleartext HTTP traffic not permitted`. Even if enabled, plaintext OTA is a trivial MITM vector — an attacker on the same network can inject malicious rules/workflows.

**Bug 2:**
```kotlin
val key = javax.crypto.spec.SecretKeySpec(
    "ota_signing_key_replace_me".toByteArray(), "HmacSHA256"
)
```
This hardcoded string is in plaintext in the APK. Anyone who decompiles the APK (trivial with `apktool`) has the signing key. The HMAC verification is therefore security theatre.

**Fix required:**  
1. Change to `https://`. Enforce certificate pinning on this endpoint.  
2. Load the HMAC key from Android Keystore (generated at first launch or provisioned securely), never embed it as a literal string.

---

## 🔴 C-9: `openOrCreateDatabase()` Bypasses Room WAL Mode — RemoteDashboardServer.kt

**File:** `android/app/src/main/kotlin/com/visionagent/core/remote/server/RemoteDashboardServer.kt`  
**Lines:** 247–256

**The bug:**
```kotlin
val db = context.openOrCreateDatabase("vision_agent.db", Context.MODE_PRIVATE, null)
val cur = db.rawQuery(query, null)
```

Room is configured with WAL (Write-Ahead Logging) journal mode. Room also manages its own connection pool. Opening the **same database file** via a separate raw `SQLiteDatabase` connection breaks WAL's guarantees — the raw connection does not participate in Room's WAL reader/writer coordination. This can cause:
- **Database corruption** if Room is writing while the raw connection reads
- **Stale data** (reading from the wrong WAL checkpoint)
- Room's schema version check may fail

**Fix required:** Expose a `@Query` DAO method for the dashboard queries. Route all DB access through Room. If raw SQL is truly needed, use Room's `SupportSQLiteOpenHelper` interface.

---

## 🔴 C-10: `null!!` — Guaranteed NPE at Runtime — RuleEditorEngine.kt

**File:** `android/app/src/main/kotlin/com/visionagent/core/editor/rule/RuleEditorEngine.kt`  
**Line:** 281

**The bug:**
```kotlin
private fun memoryEngineProxy() = object {
    fun hasKey(key: String) = false
}.let {
    object : com.visionagent.core.memory.MemoryEngine(
        android.app.Application(),  // Won't be used
        null!!, null!!, null!!, null!!   // ← Four guaranteed NPEs
    ) {
        override fun hasKey(key: String) = false
    }
}
```

`null!!` is the Kotlin not-null assertion on `null`. This throws `NullPointerException` **immediately** when evaluated, before the anonymous object is even constructed. This is never reached because the code is unreachable in practice, but it will not compile correctly and reveals the `MemoryEngine` constructor dependency problem being hacked around. This is a design flaw masquerading as working code.

**Fix required:** Extract `hasKey()` into an interface (`MemoryKeyStore`) and inject that interface instead of the full `MemoryEngine`. Remove this proxy entirely.

---

## 🔴 C-11: `StateManager.recent_history()` Corrupts Queue Under Concurrency — Rust

**File:** `rust_engine/src/state_manager.rs`  
**Lines:** 182–198

**The bug:**
```rust
pub fn recent_history(&self, n: usize) -> Vec<StateTransition> {
    let total = self.history.len();
    let mut all = Vec::with_capacity(total);
    let mut temp = Vec::with_capacity(total);
    
    while let Some(t) = self.history.pop() {  // ← Drains the queue
        temp.push(t);
    }
    for t in temp.iter().rev().take(n) {
        all.push(t.clone());
    }
    for t in temp {
        self.history.push(t);   // ← Re-pushes
    }
    all
}
```

`SegQueue` is a concurrent queue. Between the `pop()` loop and the `push()` loop, another thread calling `transition()` pushes a new entry. That entry gets buried in the middle of the drain and is re-pushed in **wrong chronological order**. The history is now permanently reordered.

More critically: `recent_history()` was advertised as non-blocking but it effectively **drains and rebuilds the entire queue** while holding no lock. Two concurrent calls to `recent_history()` will interleave their pops and pushes, producing garbled history and potentially losing entries.

**Fix required:** Replace `SegQueue` with a `Mutex<VecDeque<StateTransition>>` with a fixed capacity. `recent_history()` acquires the lock, clones the last N entries, releases. No drain required.

---

# PART 2 — HIGH SEVERITY

---

## 🟠 H-1: `Activity.RESULT_OK` Hardcoded — ScreenCaptureEngine.kt

**File:** `android/app/src/main/kotlin/com/visionagent/core/screen/ScreenCaptureEngine.kt`  
**Line:** 187

```kotlin
mediaProjection = projManager.getMediaProjection(Activity.RESULT_OK, mediaProjectionIntent)
```

The `resultCode` from `onActivityResult` is hardcoded as `RESULT_OK`. If the user denied the MediaProjection permission (result is `RESULT_CANCELED`), `getMediaProjection()` returns `null` and the subsequent `mediaProjection?.registerCallback(...)` silently does nothing. `setupVirtualDisplay()` then calls `mediaProjection?.createVirtualDisplay(...)` which also silently returns `null`. The capture never starts but `isRunning` is set to `true`. No error is reported.

**Fix required:** Accept `resultCode: Int` as a parameter in `startCapture()`. Guard:
```kotlin
if (resultCode != Activity.RESULT_OK) {
    isRunning.set(false)
    eventBus.publish(AgentErrorEvent(...))
    return
}
```

---

## 🟠 H-2: Bitmap Leak When `compressBitmap` Throws — ScreenCaptureEngine.kt

**File:** `android/app/src/main/kotlin/com/visionagent/core/screen/ScreenCaptureEngine.kt`  
**Lines:** 270–275

```kotlin
val frameData = compressBitmap(bitmap)
bitmap.recycle()   // ← Not in finally block
```

If `compressBitmap()` throws (e.g., `OutOfMemoryError` during JPEG compression at high resolution), `bitmap.recycle()` is never called. The bitmap leaks on the native heap. At 1080p ARGB_8888 this is 8MB per leak. Three leaked frames = 24MB native OOM.

**Fix required:**
```kotlin
try {
    val frameData = compressBitmap(bitmap)
    // ... use frameData
} finally {
    bitmap.recycle()
}
```

---

## 🟠 H-3: `recentLogs` in RemoteDashboard Has No Size Limit Enforcement — RemoteDashboardServer.kt

**File:** `android/app/src/main/kotlin/com/visionagent/core/remote/server/RemoteDashboardServer.kt`  
**Lines:** 94, 356

```kotlin
private val recentLogs = CopyOnWriteArrayList<String>()

// In subscribeToEvents():
recentLogs.add(line)
if (recentLogs.size > MAX_LOG) recentLogs.removeAt(0)
```

`CopyOnWriteArrayList.removeAt(0)` on a 200-element list copies **all 199 remaining elements** to a new array on every removal. This is called on **every EventBus event** (potentially 15/sec × multiple event types = ~50/sec). That is 50 full array copies per second of a 200-element list.

Also: the check `if (recentLogs.size > MAX_LOG)` + `removeAt(0)` is not atomic. Two concurrent event publications can both pass the size check and both add, then both remove index 0 — leaving the list one element short and removing the wrong element.

**Fix required:** Use `ArrayDeque<String>` protected by a `ReentrantReadWriteLock`, or switch to a `ConcurrentLinkedDeque` with atomic size management.

---

## 🟠 H-4: `TaskQueue._tasks` Dictionary Grows Without Bound — backend/tasks/task_queue.py

**File:** `backend/src/tasks/task_queue.py`  
**Lines:** 59, 99

```python
self._tasks: Dict[str, Task] = {}

async def submit(self, func, *args, **kwargs) -> str:
    task = Task(...)
    self._tasks[task.task_id] = task   # ← Added
    # Never removed
```

Completed tasks are never deleted from `_tasks`. After the backend handles thousands of vision requests, this dict holds every task ever submitted with its full result payload (including the base64 frame data if stored). Under sustained load this is an unbounded memory leak.

**Fix required:** After marking `TaskStatus.COMPLETED` or `TaskStatus.FAILED`, schedule deletion after a TTL (e.g., 60 seconds):
```python
asyncio.get_event_loop().call_later(60, self._tasks.pop, task.task_id, None)
```

---

## 🟠 H-5: `request_counts` Rate Limiter Never Resets — backend/main.py

**File:** `backend/src/main.py`  
**Lines:** 112–119

```python
request_counts: dict = defaultdict(int)

async def rate_limit(request: Request, call_next):
    client_ip = request.client.host
    request_counts[client_ip] += 1
    if request_counts[client_ip] > settings.RATE_LIMIT_PER_MINUTE:
        return JSONResponse(status_code=429, ...)
```

The counter increments forever and is never reset. After the first 200 requests from an IP, **all subsequent requests from that IP are blocked permanently**, even legitimate ones. The comment says "per minute" but there is no time window. This is a permanent ban not a rate limit.

Also: `request_counts` grows unboundedly with every unique IP, making it a memory leak and a DoS vector (attacker sends one request each from millions of IPs to fill the dict).

**Fix required:** Use a sliding window with TTL eviction:
```python
from collections import defaultdict
from time import time
request_timestamps: dict[str, list[float]] = defaultdict(list)

def is_rate_limited(ip: str, limit: int, window_sec: int = 60) -> bool:
    now = time()
    ts = request_timestamps[ip]
    # Evict old timestamps
    request_timestamps[ip] = [t for t in ts if now - t < window_sec]
    if len(request_timestamps[ip]) >= limit:
        return True
    request_timestamps[ip].append(now)
    return False
```

---

## 🟠 H-6: HNSW `searchLayer` Uses `sortedSetOf` — Broken Equality Semantics — VectorMemory.kt

**File:** `android/app/src/main/kotlin/com/visionagent/core/memory/vector/VectorMemory.kt`  
**Lines:** 208–232

```kotlin
val candidates = sortedSetOf(compareByDescending<Pair<Int,Float>> { it.second })
val results    = sortedSetOf(compareByDescending<Pair<Int,Float>> { it.second })
// ...
candidates.add(entryIdx to startSim)
results.add(entryIdx to startSim)
```

`sortedSetOf` uses the comparator for **both ordering and equality**. Two pairs `(0, 0.85f)` and `(1, 0.85f)` have the same float score. The comparator returns 0 for equal scores, so the `TreeSet` treats them as **the same element** and silently drops the second. This means elements with equal confidence scores are silently discarded from HNSW candidates, producing incorrect nearest-neighbour results without any error.

**Fix required:** The comparator must break ties using the index:
```kotlin
compareBy<Pair<Int,Float>>({ -it.second }, { it.first })
```
Or use a `MutableList` + sort instead of a `TreeSet`.

---

## 🟠 H-7: Unreachable Code and Broken Dependency in RuleEditorEngine — Compile Risk

**File:** `android/app/src/main/kotlin/com/visionagent/core/editor/rule/RuleEditorEngine.kt`  
**Line:** 278–285

```kotlin
private fun memoryEngineProxy() = object {
    fun hasKey(key: String) = false
}.let {
    object : com.visionagent.core.memory.MemoryEngine(
        android.app.Application(),
        null!!, null!!, null!!   // database, eventBus, encryptionManager = null!!
    ) {
        override fun hasKey(key: String) = false
    }
}
```

This **will not compile** once Hilt's generated code runs constructor injection validation. `MemoryEngine`'s constructor requires `@ApplicationContext Context`, `AgentDatabase`, `AgentEventBus`, `EncryptionManager`, `Logger` — all non-nullable. Passing `null!!` throws NPE at the call site before the constructor body even runs.

This entire approach is a sign that `RuleEvaluator` has the wrong dependency. It depends on `MemoryEngine` directly instead of a smaller interface.

---

# PART 3 — MEDIUM SEVERITY

---

## 🟡 M-1: `FPSController` Not Thread-Safe — ScreenCaptureEngine.kt

`FPSController.shouldCapture()` reads and writes `lastCaptureTime` and `fpsSamples`. It is called from the `HandlerThread` (ImageReader callback). `updateFps()` creates a new `FPSController` and assigns it to `fpsController` from a different thread. `@Volatile` is not applied to `fpsController`. The new instance may not be visible, and the old one may be used for an indeterminate period.

---

## 🟡 M-2: `AgentStateMachine` Has No Thread Safety — RuleEngine.kt

`AgentStateMachine.currentState` and `stateHistory` are plain, unsynchronised fields. `transition()` and `rollback()` are called from coroutines on `Dispatchers.Default` (a thread pool). Two concurrent `transition()` calls can interleave their read-modify-write on `stateHistory` (an `ArrayDeque`), producing undefined history state.

---

## 🟡 M-3: `MemoryEngine.persistActionToDb` is a No-op — MemoryEngine.kt

**Line:** `logger.d(TAG, "Action persisted...")` — this function logs but does not write to the database. The action history in Room DB will always be empty. Code that queries `action_history` table for analytics or replay will return zero rows. This is a silent data loss bug.

---

## 🟡 M-4: `HNSWIndex` Not Thread-Safe — VectorMemory.kt

`HNSWIndex.insert()` and `search()` operate on the same `nodes` list and `idToIndex` map with no synchronisation. `VectorMemory.store()` and `VectorMemory.searchSimilar()` are `suspend` functions running on `Dispatchers.Default`. Concurrent inserts and searches will corrupt the HNSW graph structure.

---

## 🟡 M-5: `CrashReplaySystem.checkForPreviousCrash()` Leaves Snapshot on Disk — CrashReplaySystem.kt

After detecting and notifying about a previous crash, the snapshot file is never deleted or archived. Every subsequent launch publishes the same "previous crash detected" error event indefinitely, until the user manually clears app data. The app will appear to be in a crash loop even after a clean session.

---

## 🟡 M-6: `ScreenCaptureEngine.engineScope` Never Cancelled on `stopCapture()` — ScreenCaptureEngine.kt

`stopCapture()` sets `isRunning = false` and clears the queue, but does not cancel `engineScope`. The `processFrameQueue()` coroutine continues looping, polling an empty queue, calling `delay(8)` indefinitely. This is a coroutine leak that persists until the process dies.

---

## 🟡 M-7: OCR Comment Says "use mutex from JNI side" But JNI Side Has None — OCRCore.cpp / OCREngine.kt

**OCRCore.cpp line 46:** `// OCR State — Tesseract is NOT thread-safe, use mutex from JNI side`

**JNIBridge.cpp:** No mutex exists around `ocr_extract_text()` calls.  
**OCREngine.kt:** `ocrDispatcher = newSingleThreadContext("OCRThread")` is correct, but `OCRNativeBridge` is loaded as a `@Singleton` and could be called from multiple contexts. The comment documents the intention but the protection is only partially in place — it relies on the Kotlin caller always using the single-thread dispatcher, which is not enforced by any mechanism.

---

## 🟡 M-8: `WorkflowContext.copy()` Shares Mutable `errorLog` Reference — WorkflowEngine.kt

**Line:** `val branchCtx = ctx.copy(variables = ConcurrentHashMap(ctx.variables))`

`data class WorkflowContext` has `var errorLog: MutableList<String>`. `ctx.copy()` creates a shallow copy — both the original and the copy share the **same** `MutableList` reference. Parallel branches writing to `errorLog` concurrently will corrupt each other's error state.

---

## 🟡 M-9: `OTAUpdateManager.applyMixed()` Deserialises `Map<String, String>` but Payload Contains Lists — OTAUpdateManager.kt

**Line:** `val map = json.decodeFromString<Map<String, String>>(pkg.payload)`

The MIXED format comment says: `{"rules": [...], "workflows": [...], "flags": {...}}`. But deserialising a JSON object whose values are arrays (`[...]`) as `Map<String, String>` will fail with a `SerializationException` because arrays are not strings. This crashes the OTA apply silently swallowed by the outer `try-catch`, leaving the agent on the old version with no indication of failure.

---

## 🟡 M-10: `recentLogs.removeAt(0)` on `CopyOnWriteArrayList` is O(N²) — RemoteDashboardServer.kt

See H-3 for context. Separately stated here as a performance issue: at 50 events/sec, `removeAt(0)` copies 200 elements per removal = **10,000 element copies per second** on the server's background thread. This will peg one CPU core under moderate load.

---

## 🟡 M-11: SSE Handler Calls `Thread.sleep(Long.MAX_VALUE)` — RemoteDashboardServer.kt

**Line:** `try { Thread.sleep(Long.MAX_VALUE) } catch (_: Exception) {}`

This blocks a coroutine-dispatched thread permanently. In Kotlin coroutines, blocking a thread prevents other coroutines from running on it. On `Dispatchers.IO` (which has a bounded thread pool), this will exhaust all IO threads after 64 SSE connections, hanging the entire server. The `Exception` catch also silently swallows `InterruptedException`, preventing the thread from ever being reclaimed.

**Fix required:** Use `delay(Long.MAX_VALUE)` inside a coroutine, or use a `Channel`/`StateFlow` to keep the SSE connection alive without blocking a thread.

---

## 🟡 M-12: `CrashReplaySystem.replayScope` Not Cancelled on `clearSnapshots()` — CrashReplaySystem.kt

The `replayScope` coroutine scope has no `stop()` or `cancel()` method. If `CrashReplaySystem` is re-initialised (e.g., during testing or multi-session use), the old scope leaks all its coroutines.

---

# PART 4 — LOW SEVERITY / ARCHITECTURE

---

## 🔵 L-1: Fake `getAll()` on `ShortTermMemory` — MemoryEngine.kt

`getAll(): Map<String, MemoryItem>` acquires the lock, copies the entire map, and returns it. It is called from `buildSnapshot()` (crash handler) and `getDashboardState()` (every second). For a 500-entry STM, this is 500 object copies per call. It also defeats the purpose of LRU ordering because the copy iteration order is undefined on `LinkedHashMap` under concurrent access.

---

## 🔵 L-2: `RuleRegistry` Uses `sortedMapOf` with `compareByDescending` — Logic Error in Key Semantics

`sortedMapOf<Int, MutableList<Rule>>(compareByDescending { it })` stores rules in descending priority order. This is correct for `getOrderedRules()`. However, `disable()` calls `unregister()` then `register()`. The re-registered rule (with `isEnabled = false`) is added back as a new entry, but there's no deduplication check — `register()` appends to the list, creating **duplicate rule IDs** in the registry. `getOrderedRules()` will return the same rule twice (once enabled, once disabled), and the enabled copy may match even after disabling.

---

## 🔵 L-3: `ScriptEngine.findOperator()` Has Off-By-One — ScriptEngine.kt

**Line:** `i > 0` in the operator detection loop. This prevents detecting operators at index 1 (e.g., the expression `"-5 + 3"` would not parse the `+`). The `i > 0` guard was meant to prevent matching the unary `-` at index 0, but the correct fix is to check `i >= 1` only for `-` and skip the guard for all other operators.

---

## 🔵 L-4: `RuleCondition.value: Any` is Not Serialisable — RuleEngine.kt

`data class RuleCondition(val value: Any)`. When rules are serialised to JSON (for OTA updates, backup, or the rule editor export), `kotlinx.serialization` cannot serialise `Any` without explicit type registration. The OTA `createRulesPackage()` will fail at runtime with `SerializationException: Class 'ScreenType' is not registered for polymorphic serialization`.

---

## 🔵 L-5: `CertificatePinner` Uses Placeholder Pins That Will Accept No Real Connection

```kotlin
"sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
"sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
```

These are not valid SHA-256/Base64 strings (wrong length, wrong alphabet). OkHttp will throw `CertificatePinnerException` on every request, making all backend communication fail in production. The developer must replace these before any real deployment — but there is no runtime assertion or startup check that detects placeholder pins. The failure is silent until the first network request.

---

## 🔵 L-6: `FeatureFlagManager` Init Block Writes Before Coroutine Restores — FeatureFlagManager.kt

The `init` block runs synchronously and sets defaults. `initialize()` is a `suspend` function that must be called separately. Between construction (by Hilt) and `initialize()` being called, any code reading flags gets the default values — but any code that was written to the DataStore in a previous session is **not yet loaded**. There is a silent window where the "wrong" defaults are in effect.

---

## 🔵 L-7: `AutoBugReport` Declares `lastScreenshot: ByteArray?` as a Public `var`

```kotlin
var lastScreenshot: ByteArray? = null
```

This is set externally. There's no synchronisation. The capture thread writes to it while the report generation reads it. This is a benign race in practice (worst case: slightly stale screenshot) but is architecturally incorrect for a class that claims production-grade design. It should be a `@Volatile` or use `AtomicReference<ByteArray?>`.

---

## 🔵 L-8: `WorkflowEngine.execute()` Returns `Job?` but Callers Cannot Observe Completion

`execute()` returns a `Job?` but stores it in `runningJobs[workflowId]`. If the same `workflowId` is triggered twice (e.g., by two trigger events), the second `execute()` overwrites the first job in `runningJobs` — the first job continues running but is unreachable and cannot be cancelled. Two instances of the same workflow run simultaneously.

---

## 🔵 L-9: `EventRingBuffer` in `CrashReplaySystem` Uses `@Synchronized` ArrayDeque Which Doesn't Exist in Kotlin

`EventRingBuffer` uses a plain `ArrayDeque` inside `@Synchronized` methods. This is correct. But the `record()` method is called from the EventBus subscription which runs on `Dispatchers.Default` (thread pool). `@Synchronized` in Kotlin synchronises on `this` — correct. However, `suspend fun` subscribers cannot hold a `@Synchronized` lock safely (coroutine may be resumed on a different thread while holding the lock). The subscriber calls a non-suspend `record()`, so this is currently safe, but the design is fragile.

---

## 🔵 L-10: `RemoteDashboardServer` Binds to `0.0.0.0` With No Authentication

```kotlin
serverSocket = ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0"))
```

This accepts connections from **any** network interface — including cellular (if the device has a public IP), VPN interfaces, hotspot clients, etc. The optional PIN mentioned in the comment is not implemented. Anyone on the same WiFi (or any reachable network) can read all logs, memory contents, run workflows, and query the database. For a debug tool this is acceptable if explicitly stated; for anything approaching production it is a security issue.

---

# SUMMARY TABLE

| ID | Severity | File | Issue |
|----|----------|------|-------|
| C-1 | 🔴 CRITICAL | VisionCore.cpp | Data race: shared `g_state` buffers written by 3 OpenMP threads simultaneously |
| C-2 | 🔴 CRITICAL | RuleEngine.kt | Race condition on 5 plain `var` fields written by concurrent coroutines |
| C-3 | 🔴 CRITICAL | ActionEngine.kt | TOCTOU race: `isEmpty()` + `dequeue()` are not atomic |
| C-4 | 🔴 CRITICAL | ModelManager.kt | `@Volatile` on mutable map does not protect map contents — concurrent corruption |
| C-5 | 🔴 CRITICAL | FrameProcessor.cpp | Lock-free pool ABA bug: pointer stored after index claimed — returns garbage pointer |
| C-6 | 🔴 CRITICAL | CrashReplaySystem.kt | Crash handler allocates in OOM context — crash report never written |
| C-7 | 🔴 CRITICAL | DeveloperDashboard.kt | `TYPE_APPLICATION_OVERLAY` used without `SYSTEM_ALERT_WINDOW` → crash |
| C-8 | 🔴 CRITICAL | OTAUpdateManager.kt | HTTP OTA + hardcoded HMAC key → MITM and trivially bypassable signing |
| C-9 | 🔴 CRITICAL | RemoteDashboardServer.kt | Raw `openOrCreateDatabase()` bypasses Room WAL → database corruption |
| C-10 | 🔴 CRITICAL | RuleEditorEngine.kt | `null!!` → guaranteed `NullPointerException` at construction |
| C-11 | 🔴 CRITICAL | state_manager.rs | `recent_history()` drains and re-pushes SegQueue without lock → concurrent corruption |
| H-1 | 🟠 HIGH | ScreenCaptureEngine.kt | Hardcoded `RESULT_OK` — denied permission produces silent no-op |
| H-2 | 🟠 HIGH | ScreenCaptureEngine.kt | Bitmap leak if `compressBitmap()` throws — not in `finally` block |
| H-3 | 🟠 HIGH | RemoteDashboardServer.kt | `CopyOnWriteArrayList.removeAt(0)` = O(N) per event; non-atomic size check |
| H-4 | 🟠 HIGH | task_queue.py | `_tasks` dict grows without bound — unbounded memory leak |
| H-5 | 🟠 HIGH | main.py | Rate limiter never resets — permanent ban after limit; IP dict unbounded |
| H-6 | 🟠 HIGH | VectorMemory.kt | HNSW `sortedSetOf` drops equal-score elements — incorrect nearest-neighbour results |
| H-7 | 🟠 HIGH | RuleEditorEngine.kt | Won't compile with Hilt validation — `null!!` constructor args |
| M-1 | 🟡 MEDIUM | ScreenCaptureEngine.kt | `FPSController` replaced without `@Volatile` — stale reference on update |
| M-2 | 🟡 MEDIUM | RuleEngine.kt | `AgentStateMachine` has no thread safety |
| M-3 | 🟡 MEDIUM | MemoryEngine.kt | `persistActionToDb` is a no-op — action history DB always empty |
| M-4 | 🟡 MEDIUM | VectorMemory.kt | `HNSWIndex` not thread-safe — concurrent insert/search corrupts graph |
| M-5 | 🟡 MEDIUM | CrashReplaySystem.kt | Crash snapshot never deleted — permanent "crash detected" loop |
| M-6 | 🟡 MEDIUM | ScreenCaptureEngine.kt | `engineScope` never cancelled on `stopCapture()` — coroutine leak |
| M-7 | 🟡 MEDIUM | OCRCore.cpp + JNIBridge | "Use mutex from JNI side" documented but mutex not implemented |
| M-8 | 🟡 MEDIUM | WorkflowEngine.kt | `ctx.copy()` shares mutable `errorLog` — parallel branches corrupt each other |
| M-9 | 🟡 MEDIUM | OTAUpdateManager.kt | MIXED OTA deserialise type mismatch → silent crash on apply |
| M-10 | 🟡 MEDIUM | RemoteDashboardServer.kt | O(N²) log trimming under load |
| M-11 | 🟡 MEDIUM | RemoteDashboardServer.kt | `Thread.sleep(MAX_VALUE)` in coroutine exhausts IO thread pool at 64 connections |
| M-12 | 🟡 MEDIUM | CrashReplaySystem.kt | `replayScope` has no stop/cancel method — leaks on re-init |
| L-1 | 🔵 LOW | MemoryEngine.kt | `getAll()` copies 500 objects every call — called at 1Hz from dashboard |
| L-2 | 🔵 LOW | RuleEngine.kt | `disable()` creates duplicate rule IDs in registry |
| L-3 | 🔵 LOW | ScriptEngine.kt | Off-by-one in operator detection for expressions starting with `-` |
| L-4 | 🔵 LOW | RuleEngine.kt | `RuleCondition.value: Any` not serialisable by kotlinx.serialization |
| L-5 | 🔵 LOW | CertificatePinner.kt | Placeholder SHA-256 pins make all backend connections fail in production |
| L-6 | 🔵 LOW | FeatureFlagManager.kt | Silent window between Hilt construction and `initialize()` uses wrong defaults |
| L-7 | 🔵 LOW | AutoBugReport.kt | `lastScreenshot` is a public unsynchronised `var` — benign race |
| L-8 | 🔵 LOW | WorkflowEngine.kt | Same workflow ID triggered twice runs two concurrent instances silently |
| L-9 | 🔵 LOW | CrashReplaySystem.kt | `@Synchronized` on `suspend`-adjacent code — fragile design |
| L-10 | 🔵 LOW | RemoteDashboardServer.kt | Binds to `0.0.0.0` with no auth — accessible from all network interfaces |

---

## PRIORITY FIX ORDER

1. **C-1** — Fix OpenMP race before any testing. Use per-section local buffers.
2. **C-5** — Replace custom lock-free pool with spinlock-based pool.
3. **C-2, C-3** — Fix Kotlin races. `@Volatile` fields + atomic action queue.
4. **C-7** — Add `SYSTEM_ALERT_WINDOW` check. App is currently unlaunchable with dashboard.
5. **C-9** — Remove raw DB access. Route through Room.
6. **C-8** — Switch OTA to HTTPS. Move signing key to Keystore.
7. **C-10, H-7** — Introduce `MemoryKeyStore` interface. Remove `null!!`.
8. **C-4** — Replace `@Volatile mutableMapOf` with `ConcurrentHashMap`.
9. **C-6** — Pre-allocate crash snapshot buffer at init time.
10. **C-11** — Replace `SegQueue` history with `Mutex<VecDeque>` in Rust.
11. **H-1, H-2** — Fix MediaProjection result code + bitmap finally block.
12. **H-4, H-5** — Fix Python memory leaks and rate limiter.
13. **H-6** — Fix HNSW comparator tie-breaking.
14. **M-3** — Implement `persistActionToDb` properly.
15. All remaining Medium and Low items.
