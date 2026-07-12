"""
Configuration — Pydantic Settings v2
Reads from environment variables / .env file
"""

from functools import lru_cache

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # Server
    PORT: int = 8000
    WORKERS: int = 4
    DEBUG: bool = False

    # Database
    DATABASE_URL: str = "postgresql+asyncpg://user:pass@localhost:5432/visionagent"
    DB_POOL_SIZE: int = 10
    DB_MAX_OVERFLOW: int = 20

    # Redis
    REDIS_URL: str = "redis://localhost:6379/0"
    REDIS_MAX_CONNECTIONS: int = 20

    # Security
    AGENT_API_KEY: str = "change-me-in-production"
    ALLOWED_ORIGINS: list[str] = ["http://localhost", "http://127.0.0.1"]
    RATE_LIMIT_PER_MINUTE: int = 200

    # Models
    MODEL_DIR: str = "/opt/visionagent/models"

    # Logging
    LOG_LEVEL: str = "INFO"
    LOG_FILE: str = "/var/log/visionagent/backend.log"

    model_config = {"env_file": ".env", "case_sensitive": True}


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
