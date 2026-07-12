"""
Database — Async SQLAlchemy PostgreSQL with connection pool
"""

from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from sqlalchemy import BigInteger, Boolean, Column, Float, String, Text  # noqa: E402
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.orm import DeclarativeBase

from src.config import settings
from src.utils.logging import get_logger

logger = get_logger(__name__)

# ─────────────────────────────────────────────────────────────────────────────
# Engine & Session Factory
# ─────────────────────────────────────────────────────────────────────────────

_engine: AsyncEngine | None = None
_session_factory: async_sessionmaker | None = None


async def init_db():
    global _engine, _session_factory

    _engine = create_async_engine(
        settings.DATABASE_URL,
        pool_size=settings.DB_POOL_SIZE,
        max_overflow=settings.DB_MAX_OVERFLOW,
        pool_pre_ping=True,  # Validate connections on checkout
        pool_recycle=3600,  # Recycle stale connections after 1hr
        echo=settings.DEBUG,
        connect_args={
            "command_timeout": 30,
            "server_settings": {"application_name": "vision_agent_backend"},
        },
    )

    _session_factory = async_sessionmaker(
        _engine,
        class_=AsyncSession,
        expire_on_commit=False,  # Don't expire objects after commit
        autocommit=False,
        autoflush=False,
    )

    # Create tables if needed
    async with _engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    logger.info(f"Database initialized: pool_size={settings.DB_POOL_SIZE}")


async def close_db():
    global _engine
    if _engine:
        await _engine.dispose()
        logger.info("Database connections closed")


@asynccontextmanager
async def get_db() -> AsyncGenerator[AsyncSession, None]:
    """Async context manager for database sessions."""
    if not _session_factory:
        raise RuntimeError("Database not initialized — call init_db() first")

    async with _session_factory() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
        finally:
            await session.close()


# ─────────────────────────────────────────────────────────────────────────────
# Base Model
# ─────────────────────────────────────────────────────────────────────────────


class Base(DeclarativeBase):
    pass


# ─────────────────────────────────────────────────────────────────────────────
# Database Models (PostgreSQL)
# ─────────────────────────────────────────────────────────────────────────────

from sqlalchemy import JSON, Index, Integer  # noqa: E402


class SessionModel(Base):
    __tablename__ = "sessions"
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    session_id = Column(String(64), nullable=False, unique=True, index=True)
    started_at = Column(BigInteger, nullable=False)
    ended_at = Column(BigInteger, nullable=True)
    total_frames = Column(BigInteger, default=0)
    total_actions = Column(BigInteger, default=0)
    is_successful = Column(Boolean, default=True)
    agent_version = Column(String(32), nullable=True)
    device_info = Column(JSON, nullable=True)


class ScreenStateModel(Base):
    __tablename__ = "screen_states"
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    session_id = Column(String(64), nullable=False, index=True)
    screen_type = Column(String(32), nullable=False, index=True)
    element_count = Column(Integer, default=0)
    ocr_preview = Column(Text, nullable=True)
    confidence = Column(Float, default=0.0)
    timestamp = Column(BigInteger, nullable=False, index=True)
    __table_args__ = (Index("ix_screen_state_session_ts", "session_id", "timestamp"),)


class ActionHistoryModel(Base):
    __tablename__ = "action_history"
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    action_id = Column(String(64), nullable=False, unique=True)
    session_id = Column(String(64), nullable=False, index=True)
    action_type = Column(String(32), nullable=False, index=True)
    success = Column(Boolean, nullable=False, index=True)
    duration_ms = Column(BigInteger, nullable=False)
    target_type = Column(String(32), nullable=True)
    error_message = Column(Text, nullable=True)
    timestamp = Column(BigInteger, nullable=False, index=True)
    __table_args__ = (
        Index("ix_action_session_ts", "session_id", "timestamp"),
        Index("ix_action_type_success", "action_type", "success"),
    )


class PerformanceLogModel(Base):
    __tablename__ = "performance_logs"
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    session_id = Column(String(64), nullable=False, index=True)
    module = Column(String(32), nullable=False, index=True)
    operation = Column(String(64), nullable=False)
    duration_ms = Column(BigInteger, nullable=False)
    memory_bytes = Column(BigInteger, nullable=False)
    cpu_percent = Column(Float, nullable=False)
    timestamp = Column(BigInteger, nullable=False, index=True)
    __table_args__ = (Index("ix_perf_module_ts", "module", "timestamp"),)


class ErrorLogModel(Base):
    __tablename__ = "error_logs"
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    session_id = Column(String(64), nullable=False, index=True)
    error_code = Column(String(64), nullable=False, index=True)
    message = Column(Text, nullable=False)
    stack_trace = Column(Text, nullable=True)
    is_fatal = Column(Boolean, default=False, index=True)
    timestamp = Column(BigInteger, nullable=False, index=True)


class MemoryStoreModel(Base):
    __tablename__ = "memory_store"
    id = Column(BigInteger, primary_key=True, autoincrement=True)
    key = Column(String(256), nullable=False, unique=True, index=True)
    value = Column(Text, nullable=False)
    memory_type = Column(String(32), nullable=False, index=True)
    session_id = Column(String(64), nullable=False)
    encrypted = Column(Boolean, default=False)
    weight = Column(Float, default=1.0)
    timestamp = Column(BigInteger, nullable=False)
    ttl_ms = Column(BigInteger, default=-1)
