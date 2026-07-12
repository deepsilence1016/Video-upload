"""
WebSocket API — Real-time bidirectional communication with Android agent
"""

import asyncio
import json
import time

from fastapi import APIRouter, Query, WebSocket, WebSocketDisconnect

from src.auth.api_key import verify_ws_api_key
from src.utils.logging import get_logger

router = APIRouter()
logger = get_logger(__name__)


# ─────────────────────────────────────────────────────────────────────────────
# Connection Manager — tracks all active WebSocket sessions
# ─────────────────────────────────────────────────────────────────────────────


class ConnectionManager:
    def __init__(self):
        # session_id → WebSocket
        self.active: dict[str, WebSocket] = {}
        # Broadcast groups
        self.groups: dict[str, set[str]] = {}

    async def connect(self, session_id: str, ws: WebSocket):
        await ws.accept()
        self.active[session_id] = ws
        logger.info(f"WebSocket connected: {session_id} | total={len(self.active)}")

    def disconnect(self, session_id: str):
        self.active.pop(session_id, None)
        for members in self.groups.values():
            members.discard(session_id)
        logger.info(f"WebSocket disconnected: {session_id}")

    async def send_to(self, session_id: str, message: dict):
        ws = self.active.get(session_id)
        if ws:
            try:
                await ws.send_json(message)
            except Exception as e:
                logger.error(f"Send error to {session_id}: {e}")
                self.disconnect(session_id)

    async def broadcast(self, message: dict):
        disconnected = []
        for sid, ws in list(self.active.items()):
            try:
                await ws.send_json(message)
            except Exception:
                disconnected.append(sid)
        for sid in disconnected:
            self.disconnect(sid)

    def is_connected(self, session_id: str) -> bool:
        return session_id in self.active

    def connected_count(self) -> int:
        return len(self.active)


manager = ConnectionManager()


# ─────────────────────────────────────────────────────────────────────────────
# Message Protocol
# ─────────────────────────────────────────────────────────────────────────────

MSG_TYPES = {
    # Client → Server
    "FRAME_EVENT": "frame_event",  # Frame analysis request
    "STATE_UPDATE": "state_update",  # Agent state change
    "PERFORMANCE_METRIC": "performance_metric",  # Perf data
    "ERROR_REPORT": "error_report",  # Error occurred
    "HEARTBEAT": "heartbeat",  # Keep-alive ping
    # Server → Client
    "ANALYSIS_RESULT": "analysis_result",  # Vision/OCR result
    "CONFIG_UPDATE": "config_update",  # Remote config push
    "FLAG_UPDATE": "flag_update",  # Feature flag update
    "COMMAND": "command",  # Server-sent command
    "HEARTBEAT_ACK": "heartbeat_ack",  # Pong
    "ERROR": "error",  # Server error
}


async def handle_message(session_id: str, message: dict) -> dict | None:
    """Route incoming WebSocket messages to appropriate handlers."""
    msg_type = message.get("type")

    if msg_type == MSG_TYPES["HEARTBEAT"]:
        return {
            "type": MSG_TYPES["HEARTBEAT_ACK"],
            "server_time": time.time(),
            "session_id": session_id,
        }

    elif msg_type == MSG_TYPES["FRAME_EVENT"]:
        # Async vision analysis — respond with analysis result
        from src.services.vision_service import FrameAnalysisRequest, vision_service

        try:
            req = FrameAnalysisRequest(
                frame_data=message.get("frame_data", ""),
                session_id=session_id,
                width=message.get("width", 1080),
                height=message.get("height", 1920),
            )
            result = await vision_service.analyze_frame(req)
            return {
                "type": MSG_TYPES["ANALYSIS_RESULT"],
                "session_id": session_id,
                "data": result.model_dump(),
            }
        except Exception as e:
            return {"type": MSG_TYPES["ERROR"], "message": str(e)}

    elif msg_type == MSG_TYPES["STATE_UPDATE"]:
        # Log state transition
        logger.info(f"[{session_id}] State: {message.get('previous')} → {message.get('current')}")
        return None  # No response needed

    elif msg_type == MSG_TYPES["PERFORMANCE_METRIC"]:
        # Store metrics (fire and forget)
        asyncio.create_task(store_metric(session_id, message))
        return None

    elif msg_type == MSG_TYPES["ERROR_REPORT"]:
        logger.error(
            f"[{session_id}] Agent error: {message.get('error_code')} — {message.get('message')}"
        )
        return None

    else:
        logger.warning(f"Unknown message type: {msg_type} from {session_id}")
        return {"type": MSG_TYPES["ERROR"], "message": f"Unknown message type: {msg_type}"}


async def store_metric(session_id: str, data: dict):
    """Persist performance metric to DB (background task)."""
    try:
        # DB write — placeholder for actual implementation
        pass
    except Exception as e:
        logger.error(f"Metric storage error: {e}")


# ─────────────────────────────────────────────────────────────────────────────
# WebSocket Endpoint
# ─────────────────────────────────────────────────────────────────────────────


@router.websocket("/ws/{session_id}")
async def websocket_endpoint(
    websocket: WebSocket,
    session_id: str,
    api_key: str = Query(...),
):
    # Verify API key before accepting
    if not verify_ws_api_key(api_key):
        await websocket.close(code=4001, reason="Invalid API key")
        return

    await manager.connect(session_id, websocket)

    # Send welcome message
    await manager.send_to(
        session_id,
        {
            "type": "connected",
            "session_id": session_id,
            "server_time": time.time(),
            "capabilities": ["frame_analysis", "config_push", "flag_update"],
        },
    )

    try:
        while True:
            # Receive message with timeout (detect dead connections)
            try:
                raw = await asyncio.wait_for(websocket.receive_text(), timeout=60.0)
            except TimeoutError:
                # Send ping to check if client is alive
                await manager.send_to(session_id, {"type": "ping"})
                continue

            try:
                message = json.loads(raw)
            except json.JSONDecodeError:
                await manager.send_to(
                    session_id, {"type": MSG_TYPES["ERROR"], "message": "Invalid JSON"}
                )
                continue

            response = await handle_message(session_id, message)
            if response:
                await manager.send_to(session_id, response)

    except WebSocketDisconnect:
        logger.info(f"WebSocket gracefully disconnected: {session_id}")
    except Exception as e:
        logger.error(f"WebSocket error [{session_id}]: {e}", exc_info=True)
    finally:
        manager.disconnect(session_id)


@router.get("/ws/connections")
async def list_connections():
    return {
        "active_connections": manager.connected_count(),
        "session_ids": list(manager.active.keys()),
    }
