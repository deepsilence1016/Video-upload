"""OCR API Router"""

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from src.auth.api_key import verify_api_key

router = APIRouter()


class OCRResponse(BaseModel):
    session_id: str = ""
    text: str = ""
    confidence: float = 0.0
    language: str = "eng"
    processing_ms: float = 0.0


@router.post("/extract", response_model=OCRResponse)
async def extract_text(_: str = Depends(verify_api_key)):
    return OCRResponse()


@router.get("/status")
async def ocr_status():
    return {"initialized": True, "supported_languages": ["eng", "hin"], "engine": "tesseract"}
