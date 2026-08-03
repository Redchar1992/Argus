"""Persistent application service around the chapter LangGraph subgraph."""

from __future__ import annotations

from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
from langgraph.types import Command, StateSnapshot

from app.chapter_events import chapter_event_sink
from app.schemas.chapter import (
    ChapterCommand,
    ChapterDecision,
    ChapterPlan,
    ChapterRunResponse,
    ChapterRunStatus,
)
from app.workflow.chapter_graph import build_chapter_graph

ChapterUpdateCallback = Callable[[str, dict[str, Any]], Awaitable[None]]
ChapterStreamCallback = Callable[[str, dict[str, Any]], Awaitable[None]]


class ChapterWorkflowNotFound(LookupError):
    pass


class ChapterWorkflowConflict(RuntimeError):
    pass


class ChapterWorkflowService:
    def __init__(self, graph: Any) -> None:
        self.graph = graph

    @staticmethod
    def _config(thread_id: str) -> dict[str, dict[str, str]]:
        return {"configurable": {"thread_id": thread_id}}

    async def start(
        self,
        command: ChapterCommand,
        *,
        on_update: ChapterUpdateCallback | None = None,
        on_stream: ChapterStreamCallback | None = None,
    ) -> ChapterRunResponse:
        if command.action.value not in {"PLAN", "GENERATE"}:
            raise ChapterWorkflowConflict("仅PLAN或GENERATE可创建章节线程")
        existing = await self._snapshot_or_none(command.thread_id)
        if existing and existing.values:
            raise ChapterWorkflowConflict(f"章节线程已存在：{command.thread_id}")
        initial = self._initial_state(command)
        await self._invoke(
            initial,
            command.thread_id,
            on_update=on_update,
            on_stream=on_stream,
        )
        return await self.get(command.thread_id)

    async def finalize(
        self,
        command: ChapterCommand,
        *,
        on_update: ChapterUpdateCallback | None = None,
        on_stream: ChapterStreamCallback | None = None,
    ) -> ChapterRunResponse:
        snapshot = await self._snapshot(command.thread_id)
        if snapshot.values.get("status") == ChapterRunStatus.COMPLETED.value:
            raise ChapterWorkflowConflict("已批准章节不能再次完成")
        if not self._waiting_for_review(snapshot):
            raise ChapterWorkflowConflict("章节当前未等待人工审核")
        # Backend FINALIZE commands also carry chapterPlan and other dispatch
        # context. Keep ChapterDecision itself strict, but project the command
        # envelope down to the fields understood by the human-review resume.
        decision_payload: dict[str, Any] = {}
        for name, field in ChapterDecision.model_fields.items():
            alias = field.alias or name
            if alias in command.payload:
                decision_payload[alias] = command.payload[alias]
            elif name in command.payload:
                decision_payload[name] = command.payload[name]
        decision = ChapterDecision.model_validate(decision_payload)
        resume = decision.model_dump(mode="json", by_alias=True)
        resume["_operationKey"] = command.idempotency_key
        await self._invoke(
            Command(resume=resume),
            command.thread_id,
            on_update=on_update,
            on_stream=on_stream,
        )
        return await self.get(command.thread_id)

    async def continue_thread(
        self,
        thread_id: str,
        *,
        on_update: ChapterUpdateCallback | None = None,
        on_stream: ChapterStreamCallback | None = None,
    ) -> ChapterRunResponse:
        snapshot = await self._snapshot(thread_id)
        if self._waiting_for_review(snapshot) or not snapshot.next:
            return await self.get(thread_id)
        await self._invoke(
            None,
            thread_id,
            on_update=on_update,
            on_stream=on_stream,
        )
        return await self.get(thread_id)

    async def get(self, thread_id: str) -> ChapterRunResponse:
        snapshot = await self._snapshot(thread_id)
        values = dict(snapshot.values)
        waiting = self._waiting_for_review(snapshot)
        stored = str(values.get("status", ChapterRunStatus.RUNNING.value))
        if waiting:
            status = ChapterRunStatus.REVIEW_REQUIRED
        elif not snapshot.next and stored == ChapterRunStatus.PLAN_READY.value:
            status = ChapterRunStatus.PLAN_READY
        elif not snapshot.next and stored == ChapterRunStatus.COMPLETED.value:
            status = ChapterRunStatus.COMPLETED
        else:
            status = ChapterRunStatus.RUNNING
        return ChapterRunResponse.model_validate(
            {
                "taskId": values["task_id"],
                "storyId": values["story_id"],
                "chapterId": values["chapter_id"],
                "chapterNo": values["chapter_no"],
                "threadId": thread_id,
                "status": status,
                "currentNode": (
                    "human_review" if waiting else values.get("current_node", "unknown")
                ),
                "chapterPlan": values.get("chapter_plan") or None,
                "draftContent": values.get("draft_content", ""),
                "finalContent": values.get("final_content", ""),
                "chapterReview": values.get("chapter_review") or None,
                "chapterSummary": values.get("chapter_summary") or None,
                "memoryUpdate": values.get("memory_update") or None,
                "mechanicalErrors": values.get("mechanical_errors", []),
                "revisionCount": values.get("revision_count", 0),
                "maxRevisions": values.get("max_revisions", 2),
                "approved": values.get("approved", False),
                "artifacts": values.get("artifacts", []),
                "modelCalls": values.get("model_calls", []),
                "progressEvents": values.get("progress_events", []),
                "interrupt": self._interrupt_payload(snapshot),
                "operationCallStart": values.get("operation_call_start", 0),
                "processedOperationKeys": values.get("processed_operation_keys", []),
            }
        )

    async def operation_applied(self, thread_id: str, key: str) -> bool:
        snapshot = await self._snapshot(thread_id)
        return key in snapshot.values.get("processed_operation_keys", [])

    def _initial_state(self, command: ChapterCommand) -> dict[str, Any]:
        payload = command.payload

        def value(camel: str, snake: str, default: Any = None) -> Any:
            return payload.get(camel, payload.get(snake, default))

        characters = list(value("characters", "characters", []))
        plan_raw = value("chapterPlan", "chapter_plan", {}) or {}
        if plan_raw:
            plan = ChapterPlan.model_validate(plan_raw)
            plan.validate_known_characters({str(item["name"]) for item in characters})
            plan_raw = plan.model_dump(mode="json", by_alias=True)
        all_outline_nodes = list(value("outlineNodes", "outline_nodes", []) or [])
        explicit_current = value(
            "currentOutlineNodes", "current_outline_nodes", None
        )
        if explicit_current is not None:
            current_outline_nodes = list(explicit_current or [])
        elif len(all_outline_nodes) == 2:
            # Backwards compatibility for the original bounded payload.
            current_outline_nodes = all_outline_nodes
        else:
            start = (command.chapter_no - 1) * 2
            current_outline_nodes = all_outline_nodes[start : start + 2]
        if len(current_outline_nodes) != 2 or any(
            not isinstance(node, dict) for node in current_outline_nodes
        ):
            raise ValueError(
                f"第{command.chapter_no}章必须且只能包含两个当前大纲节点"
            )
        return {
            "task_id": command.task_id,
            "story_id": command.story_id,
            "chapter_id": command.chapter_id,
            "chapter_no": command.chapter_no,
            "thread_id": command.thread_id,
            "mode": command.action.value,
            "content_mode": str(value("contentMode", "content_mode", "SHORT_STORY")),
            "target_chapter_count": int(value("targetChapterCount", "target_chapter_count", 10) or 10),
            "target_total_words": int(value("targetTotalWords", "target_total_words", 30_000) or 30_000),
            "chapter_target_words": int(value("chapterTargetWords", "chapter_target_words", 1_800) or 1_800),
            "viewpoint": str(value("viewpoint", "viewpoint", "THIRD_LIMITED")),
            "context_snapshot_hash": str(value("contextSnapshotHash", "context_snapshot_hash", "")),
            "story_title": str(value("storyTitle", "story_title", "")),
            "genre": str(value("genre", "genre", "")),
            "target_audience": str(value("targetAudience", "target_audience", "")),
            "style_profile": dict(value("styleProfile", "style_profile", {}) or {}),
            "characters": characters,
            "canon_facts": list(value("canonFacts", "canon_facts", []) or []),
            "relationship_states": list(
                value("relationshipStates", "relationship_states", []) or []
            ),
            "recent_summaries": list(
                value("recentSummaries", "recent_summaries", []) or []
            ),
            "unresolved_threads": list(
                value("unresolvedThreads", "unresolved_threads", []) or []
            ),
            "foreshadowing_ledger": list(
                value("foreshadowingLedger", "foreshadowing_ledger", []) or []
            ),
            "current_outline_nodes": current_outline_nodes,
            "outline_nodes": current_outline_nodes,
            "chapter_plan": plan_raw,
            "target_length": int(value("targetLength", "target_length", 1200) or 1200),
            "context_packet": {},
            "draft_content": "",
            "final_content": "",
            "chapter_review": {},
            "chapter_summary": {},
            "memory_update": {},
            "mechanical_errors": [],
            "revision_count": 0,
            "max_revisions": min(
                2, max(0, int(value("maxRevisions", "max_revisions", 2)))
            ),
            "approved": False,
            "user_notes": "",
            "status": ChapterRunStatus.RUNNING.value,
            "current_node": "load_context",
            "operation_call_start": 0,
            "progress_events": [],
            "errors": [],
            "artifacts": [],
            "model_calls": [],
            "processed_operation_keys": [command.idempotency_key],
        }

    async def _invoke(
        self,
        graph_input: Any,
        thread_id: str,
        *,
        on_update: ChapterUpdateCallback | None,
        on_stream: ChapterStreamCallback | None,
    ) -> None:
        config = self._config(thread_id)
        with chapter_event_sink(on_stream):
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

    async def _snapshot(self, thread_id: str) -> StateSnapshot:
        snapshot = await self.graph.aget_state(self._config(thread_id))
        if not snapshot.values:
            raise ChapterWorkflowNotFound(f"章节线程不存在：{thread_id}")
        return snapshot

    async def _snapshot_or_none(self, thread_id: str) -> StateSnapshot | None:
        snapshot = await self.graph.aget_state(self._config(thread_id))
        return snapshot if snapshot.values else None

    @staticmethod
    def _waiting_for_review(snapshot: StateSnapshot) -> bool:
        return "human_review" in snapshot.next

    @staticmethod
    def _interrupt_payload(snapshot: StateSnapshot) -> dict[str, Any] | None:
        for task in snapshot.tasks:
            if task.interrupts:
                value = task.interrupts[0].value
                return value if isinstance(value, dict) else {"value": value}
        return None


@asynccontextmanager
async def persistent_chapter_service(
    database_path: str,
) -> AsyncIterator[ChapterWorkflowService]:
    """Open a durable local SQLite checkpointer for one process lifecycle."""

    path = Path(database_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    async with AsyncSqliteSaver.from_conn_string(str(path)) as saver:
        yield ChapterWorkflowService(build_chapter_graph(checkpointer=saver))
