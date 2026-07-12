"""
Metrics — Prometheus-compatible metrics for monitoring
"""

from prometheus_client import Counter, Gauge, Histogram

# Vision metrics
VISION_REQUESTS_TOTAL = Counter(
    "vision_agent_requests_total", "Total vision analysis requests", ["endpoint"]
)

VISION_REQUEST_DURATION = Histogram(
    "vision_agent_request_duration_seconds",
    "Vision analysis request duration",
    ["endpoint"],
    buckets=[0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0],
)

VISION_ELEMENTS_DETECTED = Histogram(
    "vision_agent_elements_per_frame",
    "Number of UI elements detected per frame",
    buckets=[0, 1, 5, 10, 20, 50, 100],
)

# OCR metrics
OCR_PROCESSING_DURATION = Histogram(
    "vision_agent_ocr_duration_seconds",
    "OCR processing time per frame",
    buckets=[0.05, 0.1, 0.2, 0.5, 1.0, 2.0],
)

OCR_CACHE_HIT_RATE = Gauge("vision_agent_ocr_cache_hit_ratio", "OCR cache hit ratio")

# Session metrics
ACTIVE_SESSIONS = Gauge("vision_agent_active_sessions", "Number of active agent sessions")

SESSION_DURATION = Histogram(
    "vision_agent_session_duration_seconds",
    "Agent session duration",
    buckets=[60, 300, 600, 1800, 3600],
)

# WebSocket metrics
WS_CONNECTIONS = Gauge("vision_agent_ws_connections", "Active WebSocket connections")

WS_MESSAGES_TOTAL = Counter(
    "vision_agent_ws_messages_total",
    "Total WebSocket messages",
    ["direction"],  # "inbound" or "outbound"
)

# Error metrics
ERRORS_TOTAL = Counter(
    "vision_agent_errors_total", "Total errors by type", ["error_code", "is_fatal"]
)

# Action metrics
ACTIONS_TOTAL = Counter(
    "vision_agent_actions_total", "Total actions executed", ["action_type", "success"]
)

ACTION_DURATION = Histogram(
    "vision_agent_action_duration_ms",
    "Action execution duration",
    buckets=[10, 50, 100, 250, 500, 1000, 3000],
)

# System metrics
MEMORY_USAGE_MB = Gauge("vision_agent_backend_memory_mb", "Backend memory usage in MB")


def update_system_metrics():
    """Update system-level metrics — call periodically."""
    import psutil

    proc = psutil.Process()
    MEMORY_USAGE_MB.set(proc.memory_info().rss / 1024 / 1024)
