"""Application service for starting, inspecting, and resuming graph threads."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any
from uuid import uuid4

from langgraph.types import Command, StateSnapshot

from app.schemas.workflow import (
    ReviewDecision,
    WorkflowRunResponse,
    WorkflowStartRequest,
    WorkflowStatus,
)
from app.workflow.story_graph import build_story_graph


class StoryWorkflowNotFound(LookupError):
    """Raised when a thread does not exist in this service process."""


class StoryWorkflowConflict(RuntimeError):
    """Raised when an operation is invalid for the current graph state."""


ProgressCallback = Callable[[str, dict[str, Any]], Awaitable[None]]


class StoryWorkflowService:
    """Own the checkpointer-backed graph and its local thread registry."""

    def __init__(self, graph: Any | None = None) -> None:
        self.graph = graph or build_story_graph()
        self._known_threads: set[str] = set()

    @staticmethod
    def _config(thread_id: str) -> dict[str, dict[str, str]]:
        return {"configurable": {"thread_id": thread_id}}

    async def start(
        self,
        request: WorkflowStartRequest,
        *,
        on_update: ProgressCallback | None = None,
        operation_key: str = "",
    ) -> WorkflowRunResponse:
        thread_id = request.thread_id or str(uuid4())
        if thread_id in self._known_threads:
            raise StoryWorkflowConflict(f"工作流线程已存在：{thread_id}")

        initial_state = {
            "task_id": request.task_id,
            "story_id": request.story_id,
            "thread_id": thread_id,
            "topic": request.topic.model_dump(mode="json", by_alias=True),
            "characters": [],
            "outline": [],
            "outline_metadata": {},
            "score": {},
            "revision_count": 0,
            "max_revisions": request.max_revisions,
            "review_notes": "",
            "approved": False,
            "current_node": "generate_characters",
            "status": WorkflowStatus.RUNNING.value,
            "operation_call_start": 0,
            "progress_events": [],
            "errors": [],
            "artifacts": [],
            "model_calls": [],
            "processed_operation_keys": [operation_key] if operation_key else [],
        }
        # Register before invocation so a failed execution remains diagnosable.
        self._known_threads.add(thread_id)
        try:
            await self._invoke(
                initial_state,
                thread_id,
                on_update=on_update,
            )
        except Exception:
            # The checkpointer may contain useful partial state. Keep it known.
            raise
        return await self.get(thread_id)

    async def resume(
        self,
        thread_id: str,
        decision: ReviewDecision,
        *,
        on_update: ProgressCallback | None = None,
        operation_key: str = "",
    ) -> WorkflowRunResponse:
        snapshot = await self._snapshot(thread_id)
        if snapshot.values.get("status") == WorkflowStatus.COMPLETED.value:
            raise StoryWorkflowConflict("已完成的工作流不能再次恢复")
        if not self._is_waiting_for_review(snapshot):
            raise StoryWorkflowConflict("工作流当前未等待人工审核")

        resume_payload = decision.model_dump(mode="json")
        if operation_key:
            resume_payload["_operationKey"] = operation_key
        await self._invoke(
            Command(resume=resume_payload),
            thread_id,
            on_update=on_update,
        )
        return await self.get(thread_id)

    async def continue_thread(
        self,
        thread_id: str,
        *,
        on_update: ProgressCallback | None = None,
    ) -> WorkflowRunResponse:
        """Continue a partially executed thread after a worker-side failure."""

        snapshot = await self._snapshot(thread_id)
        if self._is_waiting_for_review(snapshot):
            return await self.get(thread_id)
        if snapshot.values.get("status") == WorkflowStatus.COMPLETED.value:
            return await self.get(thread_id)
        if not snapshot.next:
            raise StoryWorkflowConflict("工作流没有可继续执行的节点")
        await self._invoke(None, thread_id, on_update=on_update)
        return await self.get(thread_id)

    async def get(self, thread_id: str) -> WorkflowRunResponse:
        snapshot = await self._snapshot(thread_id)
        values = dict(snapshot.values)
        waiting = self._is_waiting_for_review(snapshot)
        stored_status = WorkflowStatus(
            values.get("status", WorkflowStatus.RUNNING)
        )
        if waiting:
            status = WorkflowStatus.REVIEW_REQUIRED
        elif not snapshot.next and stored_status in {
            WorkflowStatus.COMPLETED,
            WorkflowStatus.FAILED,
        }:
            status = stored_status
        else:
            # A resume starts from a REVIEW_REQUIRED checkpoint, but once the
            # interrupt has been consumed the thread is running until it pauses
            # again. Do not expose stale REVIEW_REQUIRED during that interval.
            status = WorkflowStatus.RUNNING
        interrupt_payload = self._interrupt_payload(snapshot)
        return WorkflowRunResponse.model_validate(
            {
                "thread_id": thread_id,
                "task_id": values["task_id"],
                "story_id": values["story_id"],
                "status": status,
                "current_node": (
                    "human_review"
                    if waiting
                    else values.get("current_node", "unknown")
                ),
                "revision_count": values.get("revision_count", 0),
                "max_revisions": values.get("max_revisions", 2),
                "characters": values.get("characters", []),
                "outline": values.get("outline", []),
                "score": values.get("score") or None,
                "approved": values.get("approved", False),
                "progress_events": values.get("progress_events", []),
                "artifacts": values.get("artifacts", []),
                "model_calls": values.get("model_calls", []),
                "interrupt": interrupt_payload,
                "operation_call_start": values.get("operation_call_start", 0),
                "processed_operation_keys": values.get(
                    "processed_operation_keys", []
                ),
            }
        )

    async def operation_applied(self, thread_id: str, key: str) -> bool:
        snapshot = await self._snapshot(thread_id)
        return key in snapshot.values.get("processed_operation_keys", [])

    async def _snapshot(self, thread_id: str) -> StateSnapshot:
        if thread_id not in self._known_threads:
            raise StoryWorkflowNotFound(f"工作流线程不存在：{thread_id}")
        snapshot = await self.graph.aget_state(self._config(thread_id))
        if not snapshot.values:
            raise StoryWorkflowNotFound(f"工作流线程不存在：{thread_id}")
        return snapshot

    async def _invoke(
        self,
        graph_input: Any,
        thread_id: str,
        *,
        on_update: ProgressCallback | None,
    ) -> None:
        config = self._config(thread_id)
        if on_update is None:
            await self.graph.ainvoke(graph_input, config=config)
            return

        async for chunk in self.graph.astream(
            graph_input,
            config=config,
            stream_mode="updates",
        ):
            if not isinstance(chunk, dict):
                continue
            for node, update in chunk.items():
                if node == "__interrupt__" or not isinstance(update, dict):
                    continue
                await on_update(node, update)

    @staticmethod
    def _is_waiting_for_review(snapshot: StateSnapshot) -> bool:
        return "human_review" in snapshot.next

    @staticmethod
    def _interrupt_payload(snapshot: StateSnapshot) -> dict[str, Any] | None:
        for task in snapshot.tasks:
            if task.interrupts:
                value = task.interrupts[0].value
                return value if isinstance(value, dict) else {"value": value}
        return None
