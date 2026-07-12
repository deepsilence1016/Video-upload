# Production Grade Vision Agent Framework — Master Architecture

## Overview

यह framework एक standalone, offline-first, modular Intelligent Vision Agent है
जो केवल उपयोगकर्ता के Android App के लिए डिज़ाइन किया गया है।

---

## Technology Stack — चुनाव और कारण

| Layer | Technology | कारण | विकल्प |
|---|---|---|---|
| Android App | Kotlin | Type-safe, Coroutines, JetBrains support | Java (verbose) |
| Native Performance | Rust via JNI | Memory-safe, zero-cost abstraction | C++ (unsafe) |
| Vision Core | C++ + OpenCV | Mature CV library, real-time performance | Python+CV (slow) |
| OCR | Tesseract + C++ | Open-source, offline, accurate | ML Kit (cloud) |
| AI Backend | Python + FastAPI | Async, ML ecosystem, OpenAI compat | Node (no ML) |
| Backend Core | Go (Gin) | Low latency, goroutines, compiled | Rust (harder ML) |
| Local DB | SQLite + Room | Jetpack support, offline | Realm (proprietary) |
| Server DB | PostgreSQL | ACID, JSON support, open-source | MySQL |
| Cache | Redis | In-memory, pub/sub, fast | Memcached |
| IPC | Android Binder + AIDL | Native Android IPC | gRPC (overkill local) |
| DI | Hilt | Jetpack, compile-time | Koin (runtime) |
| Async | Coroutines + Flow | Structured concurrency | RxJava (complex) |

---

## System Architecture — Layers

```
┌─────────────────────────────────────────────────────┐
│              PRESENTATION LAYER                      │
│         (Minimal UI — Foreground Service)            │
└───────────────────┬─────────────────────────────────┘
                    │
┌───────────────────▼─────────────────────────────────┐
│              DOMAIN LAYER                            │
│    UseCases | Domain Models | Repository Interfaces  │
└───────────────────┬─────────────────────────────────┘
                    │
┌───────────────────▼─────────────────────────────────┐
│              ORCHESTRATION LAYER                     │
│   AgentOrchestrator → EventBus → Module Coordinators│
└──┬──────┬──────┬──────┬──────┬──────┬──────┬───────┘
   │      │      │      │      │      │      │
   ▼      ▼      ▼      ▼      ▼      ▼      ▼
Screen  Vision  OCR   Rule  Planner Memory Action
Engine Engine Engine Engine Engine  Engine Engine
   │      │      │      │      │      │      │
   └──────┴──────┴──────┴──────┴──────┴──────┘
                    │
┌───────────────────▼─────────────────────────────────┐
│              DATA LAYER                              │
│   Room DB | DataStore | Cache | SecureStorage       │
└─────────────────────────────────────────────────────┘
                    │
┌───────────────────▼─────────────────────────────────┐
│              NATIVE LAYER (C++/Rust via JNI)         │
│   FrameProcessor | VisionCore | OCRCore | PerfUtils  │
└─────────────────────────────────────────────────────┘
```

---

## Event-Driven Architecture — Message Flow

```
FrameCaptured Event
      │
      ▼
EventBus.publish(FrameEvent)
      │
      ├─► VisionEngine.process(frame)
      │         │
      │         └─► UIElement detected → ElementEvent
      │
      ├─► OCREngine.extract(frame)
      │         │
      │         └─► TextResult → OCREvent
      │
      └─► RuleEngine.evaluate(state)
                │
                └─► Decision → ActionEvent
                          │
                          └─► ActionEngine.execute(action)
```

---

## Memory Architecture

```
┌─────────────────────────────────────┐
│         MEMORY ENGINE               │
│                                     │
│  ┌──────────────┐  ┌─────────────┐ │
│  │ Short Term   │  │ Session     │ │
│  │ Memory (RAM) │  │ Memory      │ │
│  │ LRU Cache    │  │ (DataStore) │ │
│  └──────────────┘  └─────────────┘ │
│                                     │
│  ┌──────────────┐  ┌─────────────┐ │
│  │ Long Term    │  │ Learning    │ │
│  │ Memory       │  │ Memory      │ │
│  │ (Room DB)    │  │ (Encrypted) │ │
│  └──────────────┘  └─────────────┘ │
│                                     │
│  ┌──────────────┐  ┌─────────────┐ │
│  │ Screen       │  │ Action      │ │
│  │ Memory       │  │ Memory      │ │
│  └──────────────┘  └─────────────┘ │
└─────────────────────────────────────┘
```

---

## Database Schema

### Tables

- `agent_sessions` — session tracking
- `screen_states` — captured screen states
- `ui_elements` — detected UI elements
- `ocr_results` — OCR text extractions
- `rule_executions` — rule engine decisions
- `action_history` — all executed actions
- `memory_store` — long term memory KV
- `performance_logs` — timing & resource metrics
- `error_logs` — error & recovery records
- `config_store` — agent configuration

---

## Development Roadmap

### Phase 1 — Foundation (Week 1–2)
- Project setup, Folder structure, DI, Database schema
- Screen Capture Engine (basic)
- Logger, Event Bus

### Phase 2 — Vision Core (Week 3–4)
- Vision Engine (C++ + OpenCV)
- OCR Engine (Tesseract)
- Frame Pipeline

### Phase 3 — Intelligence (Week 5–6)
- Rule Engine (State Machine)
- Planner Engine
- Memory Engine (all layers)

### Phase 4 — Action & Recovery (Week 7)
- Action Engine
- Recovery Engine
- Fault Tolerance

### Phase 5 — Backend (Week 8–9)
- FastAPI AI backend
- Go backend services
- PostgreSQL + Redis

### Phase 6 — Optimization (Week 10)
- Performance Engine
- Battery optimization
- Memory pool

### Phase 7 — Security & Hardening (Week 11)
- Encryption
- Secure storage
- Permission validation

### Phase 8 — Testing & CI/CD (Week 12)
- Unit tests, Integration tests
- GitHub Actions (APK build)
- Documentation
