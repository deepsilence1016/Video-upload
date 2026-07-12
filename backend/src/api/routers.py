"""
API Routers — All FastAPI route definitions
"""

# Import individual routers
from .analytics_api import router as _analytics
from .memory_api import router as _memory
from .ocr_api import router as _ocr
from .vision_api import router as _vision
from .websocket_api import router as _ws

vision_router = _vision
ocr_router = _ocr
memory_router = _memory
analytics_router = _analytics
ws_router = _ws
