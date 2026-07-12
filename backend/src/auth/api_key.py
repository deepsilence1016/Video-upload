"""
API Key Authentication — Secure agent-to-backend communication
"""

import hashlib
import hmac
from functools import lru_cache

from fastapi import HTTPException, Security, status
from fastapi.security import APIKeyHeader

from src.config import settings
from src.utils.logging import get_logger

logger = get_logger(__name__)

API_KEY_HEADER = APIKeyHeader(name="X-Agent-API-Key", auto_error=False)


# Pre-computed key hash (constant-time comparison to prevent timing attacks)
@lru_cache(maxsize=1)
def _expected_hash() -> bytes:
    return hashlib.sha256(settings.AGENT_API_KEY.encode()).digest()


def verify_api_key(api_key: str = Security(API_KEY_HEADER)) -> str:
    """Dependency for protected endpoints. Returns session token on success."""
    if not api_key:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="API key required",
            headers={"WWW-Authenticate": "ApiKey"},
        )

    # Constant-time comparison (prevents timing attacks)
    provided_hash = hashlib.sha256(api_key.encode()).digest()
    if not hmac.compare_digest(provided_hash, _expected_hash()):
        logger.warning("Invalid API key attempt")
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Invalid API key")

    return api_key


def verify_ws_api_key(api_key: str) -> bool:
    """For WebSocket connections (non-dependency version)."""
    if not api_key:
        return False
    provided_hash = hashlib.sha256(api_key.encode()).digest()
    return hmac.compare_digest(provided_hash, _expected_hash())
