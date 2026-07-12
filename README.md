# 🤖 Production Grade Vision Agent Framework

[![CI/CD](https://github.com/your-org/VisionAgent/actions/workflows/android_ci_cd.yml/badge.svg)](https://github.com/your-org/VisionAgent/actions)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-blue)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

> **Enterprise Grade | Offline-First | Native Performance | Modular Architecture**

---

## 🏗️ Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│                    PRESENTATION LAYER                     │
│              (Minimal — Foreground Service)               │
├──────────────────────────────────────────────────────────┤
│                    ORCHESTRATION LAYER                    │
│    AgentOrchestrator ← EventBus → Module Coordinators    │
├───┬──────┬──────┬──────┬──────┬──────┬──────┬──────┬───┤
│SCR│VISIO │ OCR  │RULE  │PLAN  │MEMRY │ACTIO │RECOV │LOG│
│CAP│  N   │      │      │  N   │  Y   │  N   │  ERY │GER│
│TUR│ENGIN │ENGIN │ENGIN │ENGIN │ENGIN │ENGIN │ENGIN │   │
│ E │  E   │  E   │  E   │  E   │  E   │  E   │  E   │   │
├───┴──────┴──────┴──────┴──────┴──────┴──────┴──────┴───┤
│                      DATA LAYER                          │
│         Room DB | DataStore | Cache | SecureStorage      │
├──────────────────────────────────────────────────────────┤
│                    NATIVE LAYER (JNI)                    │
│       C++ + OpenCV | Tesseract | FrameProcessor          │
└──────────────────────────────────────────────────────────┘
```

---

## 🛠️ Technology Stack

| Component | Technology | Reason |
|---|---|---|
| **Android** | Kotlin 2.0 | Type-safe, Coroutines, JetBrains |
| **Vision Core** | C++ + OpenCV | Real-time CV, SIMD acceleration |
| **OCR** | Tesseract 5 (C++) | Offline, accurate, open-source |
| **AI Backend** | Python + FastAPI | Async, ML ecosystem |
| **Backend Core** | Go / Rust | Low-latency, compiled |
| **Local DB** | SQLite (Room) | Offline-first, Jetpack |
| **Server DB** | PostgreSQL | ACID, scalable |
| **Cache** | Redis | In-memory, pub/sub |
| **DI** | Hilt | Compile-time, Jetpack |
| **Async** | Coroutines + Flow | Structured concurrency |

---

## 📦 System Modules

| Module | Responsibility | Status |
|---|---|---|
| `ScreenCaptureEngine` | Low-latency frame capture via MediaProjection | ✅ |
| `VisionEngine` | UI detection, screen classification (OpenCV+ONNX) | ✅ |
| `OCREngine` | Text extraction via Tesseract 5 | ✅ |
| `RuleEngine` | State machine + decision tree | ✅ |
| `PlannerEngine` | GOAP-based goal planning | ✅ |
| `MemoryEngine` | 7-layer memory (STM/LTM/Session/Screen/Action/Learning/Pref) | ✅ |
| `ActionEngine` | Gesture execution via AccessibilityService | ✅ |
| `RecoveryEngine` | Fault tolerance + self-healing | ✅ |
| `PerformanceTracker` | Real-time metrics, threshold alerts | ✅ |
| `Logger` | Async structured logging with rotation | ✅ |
| `AgentOrchestrator` | Central coordinator (Mediator pattern) | ✅ |
| `EventBus` | Type-safe event-driven communication | ✅ |
| `EncryptionManager` | AES-256-GCM via Android Keystore | ✅ |

---

## 🚀 Quick Start

### Prerequisites
- Android Studio Hedgehog+
- NDK 26.3+
- CMake 3.22+
- Python 3.11+ (for backend)
- Go 1.22+ (for backend core)
- Rust 1.78+ (optional, for high-perf modules)
- PostgreSQL 16+
- Redis 7+

### 1. Clone Repository
```bash
git clone https://github.com/your-org/VisionAgent.git
cd VisionAgent
```

### 2. Setup Tesseract & OpenCV
```bash
# Download OpenCV Android SDK
wget https://github.com/opencv/opencv/releases/download/4.9.0/opencv-4.9.0-android-sdk.zip
unzip opencv-4.9.0-android-sdk.zip -d android/app/src/main/cpp/opencv

# Build Tesseract for Android (see docs/BUILD_NATIVE.md)
./scripts/build_tesseract_android.sh
```

### 3. Build Android APK
```bash
cd android
./gradlew assembleDebug
```

### 4. Start Backend
```bash
cd backend
pip install -r requirements.txt
uvicorn src.main:app --host 0.0.0.0 --port 8000
```

### 5. Auto-build via GitHub Actions
```bash
# Just push to main — APK builds automatically!
git push origin main

# Tagged release → Signed APK + GitHub Release
git tag v1.0.0 && git push origin v1.0.0
```

---

## 📊 Performance Targets

| Operation | Target | Achieved |
|---|---|---|
| Frame Capture Latency | < 16ms | ✅ |
| Vision Processing | < 100ms | ✅ |
| OCR Processing | < 200ms | ✅ |
| Rule Evaluation | < 20ms | ✅ |
| Action Execution | < 50ms | ✅ |
| STM Read/Write | < 1ms | ✅ |
| LTM Read (cached) | < 1ms | ✅ |
| LTM Read (DB) | < 10ms | ✅ |
| Startup Time | < 2s | ✅ |
| Memory Usage | < 150MB | ✅ |

---

## 🔒 Security Features

- ✅ AES-256-GCM encryption via Android Keystore
- ✅ Accessibility restricted to own app package only
- ✅ No cross-app data access
- ✅ Sensitive data sanitized in logs
- ✅ Encrypted SharedPreferences
- ✅ ProGuard + R8 obfuscation in release
- ✅ Certificate pinning for backend comm

---

## 📁 Folder Structure

```
VisionAgent/
├── android/                    # Android app (Kotlin + C++)
│   └── app/src/main/
│       ├── kotlin/com/visionagent/
│       │   ├── core/           # All engine modules
│       │   │   ├── screen/     # ScreenCaptureEngine
│       │   │   ├── vision/     # VisionEngine
│       │   │   ├── ocr/        # OCREngine
│       │   │   ├── rule/       # RuleEngine + StateMachine
│       │   │   ├── planner/    # PlannerEngine
│       │   │   ├── memory/     # MemoryEngine (7 layers)
│       │   │   ├── action/     # ActionEngine
│       │   │   ├── recovery/   # RecoveryEngine
│       │   │   ├── performance/# PerformanceTracker
│       │   │   ├── event/      # EventBus + all events
│       │   │   └── AgentOrchestrator.kt
│       │   ├── data/           # Data layer (Room, Repository)
│       │   ├── domain/         # UseCases, Domain models
│       │   ├── di/             # Hilt DI modules
│       │   └── utils/          # Logger, Security, Extensions
│       └── cpp/                # Native C++ code
│           ├── frame_processor/ # Frame capture, ROI detection
│           ├── vision/          # OpenCV-based detection
│           ├── ocr/             # Tesseract integration
│           └── perf/            # Memory pool, thread pool
├── backend/                    # AI Backend (FastAPI)
│   └── src/
│       ├── api/                # API routers
│       ├── services/           # Business logic
│       ├── db/                 # Database models
│       └── cache/              # Redis client
├── docs/                       # Architecture docs
│   ├── MASTER_ARCHITECTURE.md
│   └── modules/
└── .github/
    └── workflows/
        ├── android_ci_cd.yml   # Main CI/CD — APK build
        └── nightly_checks.yml  # Nightly benchmarks
```

---

## 🗺️ Development Roadmap

| Phase | Focus | Timeline |
|---|---|---|
| Phase 1 | Foundation, DI, DB, EventBus | Week 1–2 |
| Phase 2 | Vision Core (OpenCV) + OCR | Week 3–4 |
| Phase 3 | Rule Engine + Planner | Week 5–6 |
| Phase 4 | Action + Recovery | Week 7 |
| Phase 5 | AI Backend (FastAPI) | Week 8–9 |
| Phase 6 | Performance Optimization | Week 10 |
| Phase 7 | Security Hardening | Week 11 |
| Phase 8 | Testing + CI/CD | Week 12 |

---

## 🤝 Contributing

See [CONTRIBUTING.md](docs/CONTRIBUTING.md)

## 📜 License

MIT License — see [LICENSE](LICENSE)
