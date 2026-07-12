"""
Vision API Router — Frame analysis endpoints
"""

import time

from fastapi import APIRouter, BackgroundTasks, Depends, HTTPException

from src.auth.api_key import verify_api_key
from src.monitoring.metrics import VISION_REQUEST_DURATION, VISION_REQUESTS_TOTAL
from src.services.vision_service import (
    FrameAnalysisRequest,
    FrameAnalysisResponse,
    OCREnhancementRequest,
    OCREnhancementResponse,
    ocr_service,
    vision_service,
)
from src.utils.logging import get_logger

router = APIRouter()
logger = get_logger(__name__)


@router.post(
    "/analyze",
    response_model=FrameAnalysisResponse,
    summary="Analyze a screen frame for UI elements",
    description="""
    Accepts a Base64-encoded JPEG frame and returns:
    - Detected UI elements with bounding boxes
    - Screen type classification
    - Overall confidence score
    """,
)
async def analyze_frame(
    request: FrameAnalysisRequest,
    background_tasks: BackgroundTasks,
    _: str = Depends(verify_api_key),
):
    start = time.perf_counter()
    VISION_REQUESTS_TOTAL.labels(endpoint="analyze").inc()

    try:
        result = await vision_service.analyze_frame(request)
        duration = time.perf_counter() - start
        VISION_REQUEST_DURATION.labels(endpoint="analyze").observe(duration)

        # Log slow requests in background
        if duration > 0.5:
            background_tasks.add_task(
                logger.warning,
                f"Slow vision analysis: {duration:.2f}s | session={request.session_id}",
            )

        return result

    except Exception as e:
        logger.error(f"Vision analysis error: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Vision processing failed: {str(e)}") from e


@router.post(
    "/ocr/enhance", response_model=OCREnhancementResponse, summary="Enhance raw OCR text output"
)
async def enhance_ocr(request: OCREnhancementRequest, _: str = Depends(verify_api_key)):
    try:
        result = await ocr_service.enhance_text(request)
        return result
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e)) from e


@router.get("/status", summary="Vision service health status")
async def vision_status():
    return {
        "vision_initialized": vision_service._initialized,
        "onnx_available": True,
        "supported_models": ["screen_classifier", "ui_detector"],
        "version": "1.0.0",
    }
