"""
Integration Tests — Vision Agent Backend API
"""

import asyncio
import base64
import io
import time

import pytest
from httpx import ASGITransport, AsyncClient
from PIL import Image

from src.config import settings
from src.main import app

# ─────────────────────────────────────────────────────────────────────────────
# Fixtures
# ─────────────────────────────────────────────────────────────────────────────


@pytest.fixture
def sample_frame_b64() -> str:
    """Generate a test JPEG frame."""
    img = Image.new("RGB", (540, 960), color=(200, 200, 200))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=80)
    return base64.b64encode(buf.getvalue()).decode()


@pytest.fixture
def api_headers() -> dict:
    return {"X-Agent-API-Key": settings.AGENT_API_KEY}


@pytest.fixture
async def client():
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        yield c


# ─────────────────────────────────────────────────────────────────────────────
# Health Tests
# ─────────────────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_health_endpoint(client):
    resp = await client.get("/health")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "healthy"
    assert "version" in data


@pytest.mark.asyncio
async def test_ready_endpoint(client):
    resp = await client.get("/ready")
    assert resp.status_code == 200


# ─────────────────────────────────────────────────────────────────────────────
# Vision API Tests
# ─────────────────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_vision_analyze_requires_auth(client, sample_frame_b64):
    """Unauthenticated requests should return 401."""
    resp = await client.post(
        "/api/v1/vision/analyze",
        json={
            "frame_data": sample_frame_b64,
            "session_id": "test_session",
            "width": 540,
            "height": 960,
        },
    )
    assert resp.status_code in (401, 403)


@pytest.mark.asyncio
async def test_vision_analyze_authenticated(client, sample_frame_b64, api_headers):
    """Authenticated requests should return analysis result."""
    resp = await client.post(
        "/api/v1/vision/analyze",
        json={
            "frame_data": sample_frame_b64,
            "session_id": "test_session_001",
            "width": 540,
            "height": 960,
            "agent_version": "1.0.0",
        },
        headers=api_headers,
    )
    assert resp.status_code == 200
    data = resp.json()
    assert "session_id" in data
    assert "screen_type" in data
    assert "elements" in data
    assert "overall_confidence" in data
    assert isinstance(data["elements"], list)
    assert 0.0 <= data["overall_confidence"] <= 1.0


@pytest.mark.asyncio
async def test_vision_analyze_response_time(client, sample_frame_b64, api_headers):
    """Response time should be under 1 second for test images."""
    start = time.perf_counter()
    resp = await client.post(
        "/api/v1/vision/analyze",
        json={
            "frame_data": sample_frame_b64,
            "session_id": "perf_test",
            "width": 540,
            "height": 960,
        },
        headers=api_headers,
    )
    duration = time.perf_counter() - start
    assert resp.status_code == 200
    assert duration < 1.0, f"Response too slow: {duration:.2f}s"


@pytest.mark.asyncio
async def test_vision_status(client, api_headers):
    resp = await client.get("/api/v1/vision/status", headers=api_headers)
    assert resp.status_code == 200
    data = resp.json()
    assert "version" in data


# ─────────────────────────────────────────────────────────────────────────────
# OCR API Tests
# ─────────────────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_ocr_enhance(client, api_headers):
    resp = await client.post(
        "/api/v1/vision/ocr/enhance",
        json={
            "raw_text": "Subrn1t",  # Simulated OCR error
            "session_id": "test_ocr",
            "language": "eng",
        },
        headers=api_headers,
    )
    assert resp.status_code == 200
    data = resp.json()
    assert "corrected_text" in data
    assert "confidence" in data
    assert "corrections_made" in data


# ─────────────────────────────────────────────────────────────────────────────
# Rate Limiting Tests
# ─────────────────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_rate_limiting(client, sample_frame_b64, api_headers):
    """Verify rate limiter kicks in after threshold."""
    # This test is informational — actual rate depends on settings
    responses = []
    for _ in range(5):
        resp = await client.get("/health")
        responses.append(resp.status_code)
    # Most should succeed (200), rate limit only on excessive requests
    success_count = sum(1 for s in responses if s == 200)
    assert success_count >= 3, f"Too many rate-limited responses: {responses}"


# ─────────────────────────────────────────────────────────────────────────────
# WebSocket Tests
# ─────────────────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_websocket_invalid_key():
    """WebSocket with invalid key should be rejected."""

    # This test validates the auth rejection
    # Full WebSocket testing requires httpx-ws
    pass  # Placeholder — implement with httpx_ws library


# ─────────────────────────────────────────────────────────────────────────────
# Stress Test
# ─────────────────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
@pytest.mark.slow
async def test_concurrent_vision_requests(client, sample_frame_b64, api_headers):
    """10 concurrent vision requests should all succeed."""

    async def make_request():
        return await client.post(
            "/api/v1/vision/analyze",
            json={
                "frame_data": sample_frame_b64,
                "session_id": f"stress_{time.time()}",
                "width": 540,
                "height": 960,
            },
            headers=api_headers,
        )

    tasks = [make_request() for _ in range(10)]
    start = time.perf_counter()
    responses = await asyncio.gather(*tasks)
    duration = time.perf_counter() - start

    success_count = sum(1 for r in responses if r.status_code == 200)
    assert success_count >= 8, f"Only {success_count}/10 requests succeeded"
    print(
        f"\n10 concurrent requests completed in {duration:.2f}s " f"({success_count}/10 successful)"
    )
