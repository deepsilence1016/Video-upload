"""
OpenTelemetry — Distributed Tracing + Metrics + Logs
Production observability for the Vision Agent backend.

Provides:
- Distributed traces (request flow across services)
- Metrics export (Prometheus-compatible)
- Structured logs correlation (trace_id + span_id in logs)
- Auto-instrumentation for FastAPI, SQLAlchemy, Redis
"""

import time
from collections.abc import Callable
from functools import wraps
from typing import Any

from opentelemetry import metrics, trace
from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
from opentelemetry.exporter.prometheus import PrometheusMetricReader
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.instrumentation.redis import RedisInstrumentor
from opentelemetry.instrumentation.sqlalchemy import SQLAlchemyInstrumentor
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.resources import SERVICE_NAME, SERVICE_VERSION, Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor

from src.config import settings
from src.utils.logging import get_logger

logger = get_logger(__name__)

# ─────────────────────────────────────────────────────────────────────────────
# Resource — identifies this service in distributed traces
# ─────────────────────────────────────────────────────────────────────────────

RESOURCE = Resource.create(
    {
        SERVICE_NAME: "vision-agent-backend",
        SERVICE_VERSION: "1.0.0",
        "deployment.environment": "production" if not settings.DEBUG else "development",
        "service.namespace": "vision-agent",
    }
)

# ─────────────────────────────────────────────────────────────────────────────
# Tracer & Meter — module-level singletons
# ─────────────────────────────────────────────────────────────────────────────

_tracer: trace.Tracer | None = None
_meter: metrics.Meter | None = None

# ─────────────────────────────────────────────────────────────────────────────
# Custom Metrics
# ─────────────────────────────────────────────────────────────────────────────

# Will be initialized in setup_telemetry()
frame_analysis_histogram: Any = None
ocr_confidence_histogram: Any = None
active_sessions_gauge: Any = None
ws_message_counter: Any = None


def setup_telemetry(app=None, engine=None):
    """
    Initialize OpenTelemetry for the backend.
    Call once during application startup.
    """
    global _tracer, _meter
    global frame_analysis_histogram, ocr_confidence_histogram
    global active_sessions_gauge, ws_message_counter

    # ── Tracing Setup ────────────────────────────────────────────────────
    tracer_provider = TracerProvider(resource=RESOURCE)

    # OTLP exporter (sends to Jaeger, Tempo, etc.)
    otlp_exporter = OTLPSpanExporter(
        endpoint=getattr(settings, "OTLP_ENDPOINT", "http://localhost:4317"), insecure=True
    )
    tracer_provider.add_span_processor(BatchSpanProcessor(otlp_exporter))
    trace.set_tracer_provider(tracer_provider)
    _tracer = trace.get_tracer("vision_agent_backend", schema_url="1.0.0")

    # ── Metrics Setup ────────────────────────────────────────────────────
    prometheus_reader = PrometheusMetricReader()
    meter_provider = MeterProvider(resource=RESOURCE, metric_readers=[prometheus_reader])
    metrics.set_meter_provider(meter_provider)
    _meter = metrics.get_meter("vision_agent_backend")

    # Define custom metrics
    frame_analysis_histogram = _meter.create_histogram(
        name="vision_frame_analysis_duration_ms",
        description="Frame analysis end-to-end latency",
        unit="ms",
    )
    ocr_confidence_histogram = _meter.create_histogram(
        name="ocr_confidence_score",
        description="Distribution of OCR confidence scores",
        unit="ratio",
    )
    active_sessions_gauge = _meter.create_up_down_counter(
        name="active_agent_sessions", description="Currently active agent sessions"
    )
    ws_message_counter = _meter.create_counter(
        name="websocket_messages_total", description="Total WebSocket messages by direction"
    )

    # ── Auto-instrumentation ─────────────────────────────────────────────
    if app:
        FastAPIInstrumentor.instrument_app(app, excluded_urls="/health,/ready,/metrics")

    if engine:
        SQLAlchemyInstrumentor().instrument(engine=engine)

    RedisInstrumentor().instrument()

    logger.info("OpenTelemetry initialized", tracer="vision_agent_backend")
    return _tracer, _meter


def get_tracer() -> trace.Tracer:
    return _tracer or trace.get_tracer("vision_agent_backend")


def get_meter() -> metrics.Meter:
    return _meter or metrics.get_meter("vision_agent_backend")


# ─────────────────────────────────────────────────────────────────────────────
# Decorators for instrumentation
# ─────────────────────────────────────────────────────────────────────────────


def traced(operation_name: str | None = None):
    """Decorator that wraps a function in an OpenTelemetry span."""

    def decorator(func: Callable):
        span_name = operation_name or f"{func.__module__}.{func.__name__}"

        if hasattr(func, "__code__") and func.__code__.co_flags & 0x100:
            # Async function
            @wraps(func)
            async def async_wrapper(*args, **kwargs):
                with get_tracer().start_as_current_span(span_name) as span:
                    try:
                        span.set_attribute("function", func.__name__)
                        result = await func(*args, **kwargs)
                        span.set_attribute("status", "success")
                        return result
                    except Exception as e:
                        span.record_exception(e)
                        span.set_attribute("status", "error")
                        span.set_attribute("error.type", type(e).__name__)
                        raise

            return async_wrapper
        else:

            @wraps(func)
            def sync_wrapper(*args, **kwargs):
                with get_tracer().start_as_current_span(span_name) as span:
                    try:
                        start = time.perf_counter()
                        result = func(*args, **kwargs)
                        duration_ms = (time.perf_counter() - start) * 1000
                        span.set_attribute("duration_ms", duration_ms)
                        return result
                    except Exception as e:
                        span.record_exception(e)
                        raise

            return sync_wrapper

    return decorator


def measure_frame_analysis(duration_ms: float, session_id: str, cached: bool):
    """Record frame analysis metrics."""
    if frame_analysis_histogram:
        frame_analysis_histogram.record(
            duration_ms, attributes={"session_id": session_id[:8], "cached": str(cached)}
        )


def measure_ocr_confidence(confidence: float, language: str):
    """Record OCR confidence distribution."""
    if ocr_confidence_histogram:
        ocr_confidence_histogram.record(confidence, attributes={"language": language})


def session_started(session_id: str):
    if active_sessions_gauge:
        active_sessions_gauge.add(1, {"session_id": session_id[:8]})


def session_ended(session_id: str):
    if active_sessions_gauge:
        active_sessions_gauge.add(-1, {"session_id": session_id[:8]})


def ws_message(direction: str):
    """direction: 'inbound' or 'outbound'"""
    if ws_message_counter:
        ws_message_counter.add(1, {"direction": direction})
