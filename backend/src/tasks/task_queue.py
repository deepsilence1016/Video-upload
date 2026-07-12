"""
Task Queue — Async background task processing
Uses asyncio-native approach (no Celery dependency for embedded use).
For larger deployments: swap asyncio.Queue → Celery + Redis backend.
"""

import asyncio
import time
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from enum import Enum, StrEnum
from typing import Any

from src.utils.logging import get_logger

logger = get_logger(__name__)


class TaskStatus(StrEnum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class TaskPriority(int, Enum):
    LOW = 0
    NORMAL = 5
    HIGH = 10


@dataclass(order=True)
class Task:
    priority: int = field(compare=True)
    task_id: str = field(default_factory=lambda: str(uuid.uuid4()), compare=False)
    name: str = field(default="unnamed", compare=False)
    func: Any = field(default=None, compare=False)
    args: tuple = field(default_factory=tuple, compare=False)
    kwargs: dict = field(default_factory=dict, compare=False)
    status: TaskStatus = field(default=TaskStatus.PENDING, compare=False)
    created_at: float = field(default_factory=time.time, compare=False)
    result: Any = field(default=None, compare=False)
    error: str | None = field(default=None, compare=False)

    def __post_init__(self):
        # Negate priority so higher values = higher priority in min-heap
        self.priority = -self.priority


class TaskQueue:
    """
    Priority-based async task queue.
    HIGH priority tasks execute before NORMAL before LOW.
    """

    def __init__(self, max_workers: int = 4, max_queue_size: int = 1000):
        self.max_workers = max_workers
        self._queue: asyncio.PriorityQueue = asyncio.PriorityQueue(max_queue_size)
        self._tasks: dict[str, Task] = {}
        self._workers: list = []
        self._running: bool = False
        self._stats = {
            "submitted": 0,
            "completed": 0,
            "failed": 0,
            "in_queue": 0,
        }

    async def start(self):
        self._running = True
        self._workers = [asyncio.create_task(self._worker(i)) for i in range(self.max_workers)]
        logger.info(f"TaskQueue started with {self.max_workers} workers")

    async def stop(self, timeout: float = 10.0):
        self._running = False
        for w in self._workers:
            w.cancel()
        await asyncio.gather(*self._workers, return_exceptions=True)
        logger.info("TaskQueue stopped")

    async def submit(
        self,
        func: Callable,
        *args,
        name: str = "",
        priority: TaskPriority = TaskPriority.NORMAL,
        **kwargs,
    ) -> str:
        task = Task(
            priority=priority.value, name=name or func.__name__, func=func, args=args, kwargs=kwargs
        )
        self._tasks[task.task_id] = task
        await self._queue.put(task)
        self._stats["submitted"] += 1
        self._stats["in_queue"] += 1
        logger.debug(f"Task submitted: {task.name} [{task.task_id[:8]}] priority={priority.name}")
        return task.task_id

    async def get_result(self, task_id: str, timeout: float = 30.0) -> Any:
        deadline = time.time() + timeout
        while time.time() < deadline:
            task = self._tasks.get(task_id)
            if not task:
                return None
            if task.status == TaskStatus.COMPLETED:
                return task.result
            if task.status == TaskStatus.FAILED:
                raise RuntimeError(f"Task failed: {task.error}")
            await asyncio.sleep(0.1)
        raise TimeoutError(f"Task {task_id} timed out")

    def get_status(self, task_id: str) -> TaskStatus | None:
        task = self._tasks.get(task_id)
        return task.status if task else None

    def stats(self) -> dict:
        return {**self._stats, "queue_size": self._queue.qsize()}

    async def _worker(self, worker_id: int):
        logger.debug(f"Worker {worker_id} started")
        while self._running:
            try:
                task = await asyncio.wait_for(self._queue.get(), timeout=1.0)
            except TimeoutError:
                continue

            task.status = TaskStatus.RUNNING
            self._stats["in_queue"] -= 1
            start = time.perf_counter()

            try:
                if asyncio.iscoroutinefunction(task.func):
                    task.result = await task.func(*task.args, **task.kwargs)
                else:
                    # FIX R4-8: asyncio.get_event_loop() is deprecated in Python 3.10+
                    # and raises RuntimeError in Python 3.12+ in some contexts.
                    # Use asyncio.get_running_loop() — safe inside a running coroutine.
                    loop = asyncio.get_running_loop()
                    # Capture task args/kwargs at this point to avoid closure over loop var
                    _func, _args, _kwargs = task.func, task.args, task.kwargs
                    task.result = await loop.run_in_executor(
                        None, lambda f=_func, a=_args, k=_kwargs: f(*a, **k)
                    )

                task.status = TaskStatus.COMPLETED
                self._stats["completed"] += 1
                duration = time.perf_counter() - start
                logger.debug(f"Task completed: {task.name} in {duration:.3f}s")

            except asyncio.CancelledError:
                task.status = TaskStatus.CANCELLED
                raise
            except Exception as e:
                task.status = TaskStatus.FAILED
                task.error = str(e)
                self._stats["failed"] += 1
                logger.error(f"Task failed: {task.name} — {e}", exc_info=True)
            finally:
                self._queue.task_done()
                # FIX H-4: Schedule removal of completed/failed task from dict.
                # Without this, _tasks grows without bound — every task ever
                # submitted stays in memory forever (unbounded memory leak).
                # Use call_later to allow get_result() callers a 60s window
                # to read the result before it is evicted.
                RESULT_TTL_SECONDS = 60
                # FIX R4-8: get_running_loop() is the correct API inside a coroutine.
                asyncio.get_running_loop().call_later(
                    RESULT_TTL_SECONDS,
                    self._tasks.pop,
                    task.task_id,
                    None,  # default if key already gone
                )


# Global instance
task_queue = TaskQueue(max_workers=4)
