"""Memory API Router"""

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from src.auth.api_key import verify_api_key

router = APIRouter()


class MemoryEntry(BaseModel):
    key: str = ""
    value: str = ""
    memory_type: str = ""
    weight: float = 1.0


class MemorySearchResponse(BaseModel):
    results: list[MemoryEntry] = []
    total_results: int = 0


@router.post("/search", response_model=MemorySearchResponse)
async def search_memory(_: str = Depends(verify_api_key)):
    return MemorySearchResponse()


@router.get("/stats")
async def memory_stats(_: str = Depends(verify_api_key)):
    return {"entries": 0, "stm_size": 0, "ltm_size": 0}
