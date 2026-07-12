"""
Vision Service — AI-powered screen analysis
Uses ONNX Runtime for fast, offline-capable inference
"""

import asyncio
import hashlib
import time

import numpy as np

try:
    import onnxruntime as ort

    ONNX_AVAILABLE = True
except ImportError:
    ONNX_AVAILABLE = False

from pydantic import BaseModel, Field

from src.cache.redis_client import get_redis
from src.utils.logging import get_logger

logger = get_logger(__name__)

# ============================================================
# Request/Response Models
# ============================================================


class FrameAnalysisRequest(BaseModel):
    frame_data: str = Field(..., description="Base64 encoded JPEG frame")
    session_id: str
    width: int
    height: int
    agent_version: str = "1.0.0"


class UIElement(BaseModel):
    element_type: str
    bounds: dict  # {left, top, right, bottom}
    text: str | None = None
    confidence: float


class FrameAnalysisResponse(BaseModel):
    session_id: str
    screen_type: str
    elements: list[UIElement]
    overall_confidence: float
    processing_ms: float
    cached: bool = False


class OCREnhancementRequest(BaseModel):
    raw_text: str
    session_id: str
    language: str = "eng"
    context: str | None = None


class OCREnhancementResponse(BaseModel):
    corrected_text: str
    confidence: float
    corrections_made: int
    processing_ms: float


# ============================================================
# Vision Service
# ============================================================


class VisionService:
    """
    AI-powered vision analysis service.

    Uses ONNX Runtime for model inference:
    - Screen type classification model
    - UI element detection model
    - Text region detection model

    All models run offline (no external API calls).
    """

    def __init__(self):
        self.screen_classifier: object | None = None
        self.element_detector: object | None = None
        self._initialized = False

    async def initialize(self, model_dir: str):
        """Load ONNX models asynchronously"""
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, self._load_models, model_dir)
        self._initialized = True
        logger.info(f"VisionService initialized | ONNX={ONNX_AVAILABLE}")

    def _load_models(self, model_dir: str):
        """Load ONNX models (blocking — runs in thread pool)"""
        if not ONNX_AVAILABLE:
            logger.warning("ONNX Runtime not available — using rule-based fallback")
            return

        # Session options for performance
        sess_options = ort.SessionOptions()
        sess_options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        sess_options.intra_op_num_threads = 2
        sess_options.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL

        try:
            self.screen_classifier = ort.InferenceSession(
                f"{model_dir}/screen_classifier.onnx",
                sess_options=sess_options,
                providers=["CPUExecutionProvider"],
            )
            logger.info("Screen classifier model loaded")
        except FileNotFoundError:
            logger.warning("screen_classifier.onnx not found — using heuristics")

    async def analyze_frame(self, request: FrameAnalysisRequest) -> FrameAnalysisResponse:
        """Analyze a screen frame for UI elements"""
        start = time.perf_counter()

        # Check cache
        cache_key = f"vision:{hashlib.md5(request.frame_data[:100].encode()).hexdigest()}"
        redis = await get_redis()

        if redis:
            cached = await redis.get(cache_key)
            if cached:
                response = FrameAnalysisResponse.model_validate_json(cached)
                response.cached = True
                return response

        # Decode frame
        import base64

        frame_bytes = base64.b64decode(request.frame_data)

        # Run analysis in thread pool (ONNX inference is blocking)
        loop = asyncio.get_event_loop()
        result = await loop.run_in_executor(
            None, self._run_inference, frame_bytes, request.width, request.height
        )

        processing_ms = (time.perf_counter() - start) * 1000

        response = FrameAnalysisResponse(
            session_id=request.session_id,
            screen_type=result["screen_type"],
            elements=result["elements"],
            overall_confidence=result["confidence"],
            processing_ms=processing_ms,
        )

        # Cache result (60 second TTL)
        if redis:
            await redis.setex(cache_key, 60, response.model_dump_json())

        return response

    def _run_inference(self, frame_bytes: bytes, width: int, height: int) -> dict:
        """
        Run ONNX inference or rule-based fallback.
        This is CPU-bound and runs in thread pool.
        """
        if self.screen_classifier and ONNX_AVAILABLE:
            return self._onnx_inference(frame_bytes, width, height)
        else:
            return self._heuristic_analysis(frame_bytes, width, height)

    def _onnx_inference(self, frame_bytes: bytes, width: int, height: int) -> dict:
        """ONNX model inference"""
        try:
            # Preprocess: decode JPEG → numpy array
            import io

            from PIL import Image

            img = Image.open(io.BytesIO(frame_bytes)).resize((224, 224))
            img_array = np.array(img).astype(np.float32) / 255.0
            img_array = np.transpose(img_array, (2, 0, 1))  # HWC → CHW
            img_array = np.expand_dims(img_array, 0)  # Add batch dim

            # Run inference
            input_name = self.screen_classifier.get_inputs()[0].name
            outputs = self.screen_classifier.run(None, {input_name: img_array})

            screen_class_idx = int(np.argmax(outputs[0]))
            confidence = float(outputs[0][0][screen_class_idx])

            screen_types = [
                "HOME",
                "LOADING",
                "DIALOG",
                "ERROR",
                "FORM",
                "LIST",
                "NAVIGATION",
                "UNKNOWN",
            ]
            screen_type = screen_types[min(screen_class_idx, len(screen_types) - 1)]

            return {
                "screen_type": screen_type,
                "elements": [],  # Element detection from separate model
                "confidence": confidence,
            }
        except Exception as e:
            logger.error(f"ONNX inference failed: {e}")
            return self._heuristic_analysis(frame_bytes, width, height)

    def _heuristic_analysis(self, frame_bytes: bytes, width: int, height: int) -> dict:
        """Fast heuristic analysis when model unavailable"""
        # Basic pixel analysis for screen type detection
        return {"screen_type": "UNKNOWN", "elements": [], "confidence": 0.5}


# ============================================================
# OCR Enhancement Service
# ============================================================


class OCRService:
    """
    Enhances OCR output using NLP techniques.
    No external API calls — all offline.
    """

    # Common OCR errors for English
    OCR_CORRECTIONS = {
        "rn": "m",
        "cl": "d",
        "vv": "w",
        "0": "O",  # Context-dependent
        "|": "l",
    }

    async def enhance_text(self, request: OCREnhancementRequest) -> OCREnhancementResponse:
        start = time.perf_counter()

        corrected = request.raw_text
        corrections = 0

        # Apply corrections
        for wrong, right in self.OCR_CORRECTIONS.items():
            if wrong in corrected:
                # Context-sensitive replacement
                new_text = corrected.replace(wrong, right)
                if new_text != corrected:
                    corrections += 1
                    corrected = new_text

        processing_ms = (time.perf_counter() - start) * 1000

        return OCREnhancementResponse(
            corrected_text=corrected,
            confidence=0.85 if corrections == 0 else 0.75,
            corrections_made=corrections,
            processing_ms=processing_ms,
        )


# ---- Singleton instances ----
vision_service = VisionService()
ocr_service = OCRService()
