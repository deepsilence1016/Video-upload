"""
Redis Client — Async connection pool with circuit breaker
"""

import time

import redis.asyncio as aioredis

from src.config import settings
from src.utils.logging import get_logger

logger = get_logger(__name__)

_redis: aioredis.Redis | None = None
_circuit_open = False
_failure_count = 0
_last_failure_time = 0.0
CIRCUIT_THRESHOLD = 5
CIRCUIT_RESET_SEC = 30


async def init_redis():
    global _redis
    try:
        _redis = aioredis.Redis.from_url(
            settings.REDIS_URL,
            max_connections=settings.REDIS_MAX_CONNECTIONS,
            socket_timeout=2.0,
            socket_connect_timeout=2.0,
            health_check_interval=30,
            decode_responses=True,
        )
        await _redis.ping()
        logger.info("Redis connected")
    except Exception as e:
        logger.error(f"Redis connection failed: {e} — caching disabled")
        _redis = None


async def close_redis():
    global _redis
    if _redis:
        await _redis.aclose()
        _redis = None
        logger.info("Redis disconnected")


async def get_redis() -> aioredis.Redis | None:
    """Get Redis client with circuit breaker protection."""
    global _circuit_open, _failure_count, _last_failure_time

    # Circuit breaker: if too many failures, stop trying
    if _circuit_open:
        if time.time() - _last_failure_time > CIRCUIT_RESET_SEC:
            _circuit_open = False  # Half-open: try again
            _failure_count = 0
        else:
            return None  # Circuit open — skip Redis

    if not _redis:
        return None

    return _redis


async def cache_get(key: str) -> str | None:
    r = await get_redis()
    if not r:
        return None
    try:
        return await r.get(key)
    except Exception as e:
        _record_failure(e)
        return None


async def cache_set(key: str, value: str, ttl_sec: int = 60) -> bool:
    r = await get_redis()
    if not r:
        return False
    try:
        await r.setex(key, ttl_sec, value)
        return True
    except Exception as e:
        _record_failure(e)
        return False


async def cache_delete(key: str) -> bool:
    r = await get_redis()
    if not r:
        return False
    try:
        await r.delete(key)
        return True
    except Exception as e:
        _record_failure(e)
        return False


async def cache_exists(key: str) -> bool:
    r = await get_redis()
    if not r:
        return False
    try:
        return bool(await r.exists(key))
    except Exception as e:
        _record_failure(e)
        return False


def _record_failure(e: Exception):
    global _circuit_open, _failure_count, _last_failure_time
    _failure_count += 1
    _last_failure_time = time.time()
    if _failure_count >= CIRCUIT_THRESHOLD:
        _circuit_open = True
        logger.error(f"Redis circuit breaker OPEN after {_failure_count} failures")
    else:
        logger.warning(f"Redis error ({_failure_count}/{CIRCUIT_THRESHOLD}): {e}")
