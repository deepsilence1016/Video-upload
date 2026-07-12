# Vision Agent — Incident Response Runbook

## 🔴 P0: Agent Completely Stopped

### Symptoms
- No frames captured for > 30 seconds
- AgentState stuck in ERROR or TERMINATED
- HealthMonitor reports CRITICAL across all modules

### Diagnosis Steps
```bash
# 1. Check Android logs
adb logcat -s VisionAgent:V ScreenCaptureEngine:V

# 2. Check memory (OOM kill?)
adb shell dumpsys meminfo com.visionagent.app

# 3. Check thermal state
adb shell cat /sys/class/thermal/thermal_zone0/temp

# 4. Check if Accessibility Service is still running
adb shell settings get secure enabled_accessibility_services
```

### Recovery Steps
1. Force stop app: `adb shell am force-stop com.visionagent.app`
2. Clear app cache: `adb shell pm clear com.visionagent.app`
3. Restart agent from AgentOrchestrator
4. If OOM: reduce STM size in ConfigEngine

---

## 🟡 P1: High OCR Error Rate (> 30%)

### Symptoms
- OCREngine logs show confidence < 0.5 repeatedly
- TextCache hit rate very low
- RuleEngine making wrong decisions due to bad text

### Diagnosis
```kotlin
// In DebugActivity or via adb:
val stats = ocrEngine.getStats()
println("Cache hits: ${stats.cacheHitRate}")
println("Avg confidence: ${stats.avgConfidence}")
```

### Recovery
1. Increase preprocessing level: `OCRConfig(preprocessingLevel = PreprocessingLevel.HEAVY)`
2. Check tessdata integrity: verify `eng.traineddata` SHA256
3. Reset TextCache: `textCache.clear()`
4. Try different PSM: `OCRConfig(pageSegMode = 11)` for sparse text

---

## 🟡 P2: Vision Pipeline Latency > 300ms

### Symptoms
- PerformanceTracker.getAverageLatency("vision_pipeline") > 300
- Frame queue backing up (ScreenCaptureEngine.getQueueSize() > 8)
- AdaptiveFPS reducing to < 5fps

### Recovery
1. Reduce maxElementsPerFrame: `VisionConfig(maxElementsPerFrame = 20)`
2. Disable expensive detectors: `VisionConfig(enableIconDetection = false)`
3. Enable NNAPI if available: feature flag `EXP_NNAPI_SUPPORT = true`
4. Reduce capture resolution: `CaptureConfig(downscaleForLowEnd = true)`

---

## 🟡 P3: Memory > 180MB

### Recovery
1. `memoryEngine.shortTermMemory.clear()`
2. `vectorMemory.clear()` (clears HNSW index)
3. `adaptiveFPS.reportAgentState(AgentState.PAUSED, ScreenType.UNKNOWN)`
4. Trigger GC: `Runtime.getRuntime().gc()`
5. Long-term: reduce `MemoryEngineConfig(stmMaxSize = 200)`
