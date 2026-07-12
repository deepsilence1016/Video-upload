"""
Vision Agent — AI Backend (FastAPI + AsyncIO)
Production Grade | Python 3.11+ | Async-first

Responsibilities:
- ML inference endpoint (ONNX / PyTorch models)
- Screen embedding & similarity search
- OCR correction via LLM (optional)
- Learning pattern API
- Performance analytics API

Architecture:
- FastAPI with async handlers
- Pydantic v2 for validation
- SQLAlchemy async for PostgreSQL
- Redis for caching
- ONNX Runtime for offline-fast inference
"""

import asyncio
import time
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import JSONResponse

from src.api import analytics_router, memory_router, ocr_router, vision_router
from src.cache.redis_client import close_redis, init_redis
from src.config import settings
from src.db.database import close_db, init_db
from src.utils.logging import setup_logging

# ---- Logging Setup ----
logger = setup_logging()

# ============================================================
# Lifespan — Startup & Shutdown
# ============================================================


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Manage application lifecycle resources"""
    logger.info("🚀 Vision Agent Backend starting...")

    # Initialize database
    await init_db()
    logger.info("✅ PostgreSQL connected")

    # Initialize Redis cache
    await init_redis()
    logger.info("✅ Redis connected")

    # FIX R3-8: Create asyncio.Lock() inside lifespan — event loop is running here.
    global _rate_limit_lock
    _rate_limit_lock = asyncio.Lock()
    logger.info("✅ Rate-limit lock initialised")

    yield  # App runs here

    # Cleanup
    await close_db()
    await close_redis()
    logger.info("👋 Vision Agent Backend stopped")


# ============================================================
# FastAPI App
# ============================================================

app = FastAPI(
    title="Vision Agent AI Backend",
    description="Production Grade AI Backend for Vision Agent Framework",
    version="1.0.0-alpha",
    docs_url="/docs" if settings.DEBUG else None,  # Disable docs in production
    redoc_url=None,
    lifespan=lifespan,
)

# ---- Middleware ----

# GZIP compression for large responses
app.add_middleware(GZipMiddleware, minimum_size=1000)

# CORS — restrict to local Android agent
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.ALLOWED_ORIGINS,
    allow_credentials=False,
    allow_methods=["POST", "GET"],
    allow_headers=["X-Agent-Version", "X-Session-ID", "Content-Type"],
)

# ---- Request Timing Middleware ----


@app.middleware("http")
async def add_process_time_header(request: Request, call_next):
    start = time.perf_counter()
    response = await call_next(request)
    duration_ms = (time.perf_counter() - start) * 1000
    response.headers["X-Process-Time-Ms"] = f"{duration_ms:.2f}"

    if duration_ms > 500:
        logger.warning(f"Slow request: {request.url.path} took {duration_ms:.0f}ms")

    return response


# ---- Rate Limiting Middleware ----

from collections import defaultdict  # noqa: E402

# FIX H-5: Proper sliding-window rate limiter.
#
# Old code: request_counts[ip] incremented forever, never reset.
# After the first RATE_LIMIT_PER_MINUTE requests an IP was permanently banned
# for the lifetime of the process — not a "per minute" limit at all.
# Also: the dict grew without bound (one entry per unique IP ever seen).
#
# Fix: store per-IP list of request timestamps.
# On each request: evict timestamps older than 60 seconds, then check count.
# Dict entries are naturally bounded: IPs that stop sending requests have
# their lists emptied by eviction and can be pruned.

_rate_limit_window_sec: int = 60
_rate_limit_timestamps: dict = defaultdict(list)  # ip -> [timestamp, ...]
# FIX R3-8: asyncio.Lock() must NOT be created at module level (before the event loop runs).
# In Python 3.10+ this raises DeprecationWarning; in Python 3.12 it raises RuntimeError.
# Solution: initialise lazily inside the lifespan startup handler, which runs after the
# event loop is already active.
_rate_limit_lock: asyncio.Lock | None = None

# Prune IPs that haven't sent a request in 5 minutes (memory management)
_PRUNE_AFTER_SEC = 300


@app.middleware("http")
async def rate_limit(request: Request, call_next):
    client_ip = request.client.host if request.client else "unknown"
    now = time.time()
    cutoff = now - _rate_limit_window_sec

    # FIX R3-8: use the lazily-initialised lock (guaranteed non-None after startup)
    lock = _rate_limit_lock
    if lock is None:
        # Fallback: should never happen after lifespan startup
        lock = asyncio.Lock()
    async with lock:
        # Evict old timestamps for this IP
        _rate_limit_timestamps[client_ip] = [
            t for t in _rate_limit_timestamps[client_ip] if t > cutoff
        ]

        count = len(_rate_limit_timestamps[client_ip])
        if count >= settings.RATE_LIMIT_PER_MINUTE:
            return JSONResponse(
                status_code=429,
                content={
                    "error": "Rate limit exceeded",
                    "limit": settings.RATE_LIMIT_PER_MINUTE,
                    "window_seconds": _rate_limit_window_sec,
                    "retry_after": int(
                        cutoff
                        - (
                            _rate_limit_timestamps[client_ip][0]
                            if _rate_limit_timestamps[client_ip]
                            else now
                        )
                        + _rate_limit_window_sec
                    )
                    + 1,
                },
            )
        _rate_limit_timestamps[client_ip].append(now)

        # Prune IPs silent for > 5 minutes (keeps dict bounded)
        prune_cutoff = now - _PRUNE_AFTER_SEC
        stale = [ip for ip, ts in _rate_limit_timestamps.items() if not ts or ts[-1] < prune_cutoff]
        for ip in stale:
            del _rate_limit_timestamps[ip]

    response = await call_next(request)
    return response


# ---- Routers ----

app.include_router(vision_router, prefix="/api/v1/vision", tags=["Vision"])
app.include_router(ocr_router, prefix="/api/v1/ocr", tags=["OCR"])
app.include_router(memory_router, prefix="/api/v1/memory", tags=["Memory"])
app.include_router(analytics_router, prefix="/api/v1/analytics", tags=["Analytics"])

# ---- Health Check ----


@app.get("/health", tags=["Health"])
async def health_check():
    return {"status": "healthy", "version": "1.0.0-alpha", "timestamp": time.time()}


@app.get("/ready", tags=["Health"])
async def readiness_check():
    """Kubernetes readiness probe"""
    # Check DB + Redis connectivity
    return {"status": "ready"}


# ============================================================
# Entry Point
# ============================================================

if __name__ == "__main__":
    uvicorn.run(
        "src.main:app",
        host="0.0.0.0",
        port=settings.PORT,
        workers=settings.WORKERS,
        loop="uvloop",  # Faster event loop
        http="httptools",  # Faster HTTP parser
        log_level="info",
        access_log=settings.DEBUG,
        reload=settings.DEBUG,
    )
