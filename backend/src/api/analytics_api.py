"""Analytics API Router"""

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from src.auth.api_key import verify_api_key

router = APIRouter()


class AnalyticsResponse(BaseModel):
    total_sessions: int = 0
    total_frames: int = 0
    total_actions: int = 0
    avg_latency_ms: float = 0.0


@router.get("/summary", response_model=AnalyticsResponse)
async def get_analytics_summary(_: str = Depends(verify_api_key)):
    return AnalyticsResponse()


@router.get("/health")
async def analytics_health():
    return {"status": "ok"}
