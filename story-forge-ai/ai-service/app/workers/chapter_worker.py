"""At-least-once chapter worker with ordered token and state events."""

from __future__ import annotations

import asyncio
import json
import os
import socket
from collections.abc import Mapping
from typing import Any
from uuid import NAMESPACE_URL, uuid5

from pydantic import ValidationError

from app.agents.rewrite_selection_agent import RewriteSelectionAgent
from app.agents.workflow_utils import WorkflowInvocationError
from app.config import Settings
from app.infrastructure.chapter_redis import (
    ChapterRedisBroker,
    ChapterStreamEntry,
    decode_chapter_command,
)
from app.infrastructure.redis_stream import IdempotencyStore
from app.schemas.chapter import (
    ChapterCommand,
    ChapterCommandAction,
    ChapterRunResponse,
    ChapterRunStatus,
)
from app.workflow.chapter_service import (
    ChapterWorkflowConflict,
    ChapterWorkflowNotFound,
    ChapterWorkflowService,
    persistent_chapter_service,
)

NODE_PROGRESS = {
    "load_context": 10,
    "plan_chapter": 100,
    "write_chapter": 35,
    "validate_chapter": 45,
    "review_chapter": 60,
    "revise_chapter": 72,
    "human_review": 85,
    "summarize_chapter": 90,
    "update_memory": 96,
    "persist_chapter": 100,
    "rewrite_selection": 100,
}
NODE_EVENT = {
    "load_context": "CONTEXT_LOADED",
    "write_chapter": "DRAFT_READY",
    "review_chapter": "REVIEW_READY",
    "revise_chapter": "REVISION_READY",
    "summarize_chapter": "SUMMARY_READY",
    "update_memory": "MEMORY_UPDATE_READY",
}


class ChapterEventPublishError(RuntimeError):
    pass


class ChapterWorkflowWorker:
    def __init__(
        self,
        *,
        broker: ChapterRedisBroker,
        idempotency: IdempotencyStore,
        workflow: ChapterWorkflowService,
        rewrite_agent: RewriteSelectionAgent | None = None,
        max_attempts: int = 3,
    ) -> None:
        if max_attempts < 1:
            raise ValueError("max_attempts must be at least 1")
        self.broker = broker
        self.idempotency = idempotency
        self.workflow = workflow
        self.rewrite_agent = rewrite_agent or RewriteSelectionAgent()
        self.max_attempts = max_attempts
        self._group_ready = False

    async def ensure_ready(self) -> None:
        if not self._group_ready:
            await self.broker.ensure_group()
            self._group_ready = True

    async def run_once(
        self,
        *,
        count: int = 1,
        block_ms: int | None = 1000,
    ) -> int:
        await self.ensure_ready()
        entries = await self.broker.read_new(count=count, block_ms=block_ms)
        for entry in entries:
            await self.process_entry(entry)
        return len(entries)

    async def recover_once(
        self,
        *,
        min_idle_ms: int = 30_000,
        count: int = 10,
    ) -> int:
        await self.ensure_ready()
        entries = await self.broker.recover_pending(
            min_idle_ms=min_idle_ms,
            count=count,
        )
        for entry in entries:
            await self.process_entry(entry)
        return len(entries)

    async def run_forever(
        self,
        *,
        poll_block_ms: int = 2000,
        recovery_interval_seconds: float = 30.0,
        pending_idle_ms: int = 30_000,
    ) -> None:
        await self.ensure_ready()
        next_recovery = asyncio.get_running_loop().time()
        while True:
            now = asyncio.get_running_loop().time()
            if now >= next_recovery:
                await self.recover_once(min_idle_ms=pending_idle_ms)
                next_recovery = now + recovery_interval_seconds
            await self.run_once(block_ms=poll_block_ms)

    async def process_entry(self, entry: ChapterStreamEntry) -> None:
        try:
            command = decode_chapter_command(entry.fields)
        except (ValidationError, ValueError, TypeError) as exc:
            await self._publish_invalid(entry.fields, exc)
            await self.broker.acknowledge(entry.message_id)
            return

        key = command.idempotency_key
        completed = await self.idempotency.completed_result(key)
        if completed is not None:
            try:
                await self.broker.publish_event(completed)
                await self.broker.acknowledge(entry.message_id)
            except Exception:
                pass
            return

        failed_delivery = await self._terminal_delivery(entry.message_id)
        if failed_delivery is not None:
            # The failure event was already published, but its XACK was lost.
            # Replay and ACK this delivery without consuming another workflow
            # attempt. A new command entry with the same operation key remains
            # eligible for an explicit backend/user retry.
            try:
                await self.broker.publish_event(failed_delivery)
                await self.broker.acknowledge(entry.message_id)
                await self._forget_terminal_delivery(entry.message_id)
            except Exception:
                pass
            return
        owner_token = await self.idempotency.acquire(key)
        if owner_token is None:
            return
        heartbeat = self.idempotency.heartbeat(key, owner_token)
        await heartbeat.start()
        try:
            await self._process_owned_entry(
                entry,
                command,
                key=key,
                owner_token=owner_token,
            )
        except asyncio.CancelledError:
            if not heartbeat.lost:
                await self.idempotency.release(key, owner_token)
                raise
        finally:
            await heartbeat.stop()

    async def _process_owned_entry(
        self,
        entry: ChapterStreamEntry,
        command: ChapterCommand,
        *,
        key: str,
        owner_token: str,
    ) -> None:

        thread_id = self._thread_id(command)
        command = command.model_copy(update={"thread_id": thread_id})
        try:
            await self._publish(
                command,
                event_type="TASK_STARTED",
                status="RUNNING",
                current_node="worker",
                progress=0,
                data={"attemptNo": entry.attempt_no},
            )
            if command.action is ChapterCommandAction.GENERATE:
                await self._publish(
                    command,
                    event_type="GENERATION_STARTED",
                    status="RUNNING",
                    current_node="write_chapter",
                    progress=15,
                    data={"revisionNo": 1},
                )

            if command.action is ChapterCommandAction.REWRITE_SELECTION:
                terminal = await self._rewrite(command)
            else:
                terminal = await self._run_workflow(command)
        except Exception as exc:
            is_terminal = (
                not self._is_retryable(exc) or entry.attempt_no >= self.max_attempts
            )
            failure = self._event(
                command,
                event_type="TASK_FAILED" if is_terminal else "TASK_RETRYING",
                status="FAILED" if is_terminal else "RUNNING",
                current_node="worker",
                progress=0,
                data={
                    "attemptNo": entry.attempt_no,
                    "maxAttempts": self.max_attempts,
                    **({} if is_terminal else {"nextAttemptNo": entry.attempt_no + 1}),
                },
                error_code=self._error_code(exc),
                error_message=str(exc)[:2000],
            )
            try:
                await self.broker.publish_event(failure)
                if is_terminal:
                    await self._remember_terminal_delivery(
                        entry.message_id,
                        failure,
                    )
            except Exception:
                await self.idempotency.release(key, owner_token)
                return

            if not is_terminal:
                await self.idempotency.release(key, owner_token)
                # Keep pending for XAUTOCLAIM retry.
                return

            # A semantic failure or exhausted retry budget is a durable terminal
            # result for this delivery, but it is deliberately not a completed
            # operation result: an explicit retry may reuse the idempotency key.
            await self.idempotency.release(key, owner_token)
            try:
                await self.broker.acknowledge(entry.message_id)
            except Exception:
                return
            try:
                await self._forget_terminal_delivery(entry.message_id)
            except Exception:
                pass
            return

        try:
            await self.broker.publish_event(terminal)
            if not await self.idempotency.mark_completed(
                key,
                terminal,
                owner_token,
            ):
                return
        except Exception:
            await self.idempotency.release(key, owner_token)
            return
        try:
            await self.broker.acknowledge(entry.message_id)
        except Exception:
            pass

    async def _run_workflow(self, command: ChapterCommand) -> dict[str, str]:
        async def on_stream(event_type: str, data: dict[str, Any]) -> None:
            await self._publish(
                command,
                event_type=event_type,
                status="RUNNING",
                current_node=(
                    "revise_chapter"
                    if data.get("phase") == "chapter_revision"
                    else "write_chapter"
                ),
                progress=30,
                data=data,
            )

        async def on_update(node: str, update: dict[str, Any]) -> None:
            event_type = NODE_EVENT.get(node)
            if event_type is None:
                return
            data = self._update_data(node, update)
            await self._publish(
                command,
                event_type=event_type,
                status="RUNNING",
                current_node=str(update.get("current_node", node)),
                progress=NODE_PROGRESS.get(node, 0),
                data=data,
            )
            if node == "review_chapter":
                review = data.get("review") or {}
                total = int(review.get("totalScore", 0))
                fatal = review.get("fatalProblems") or []
                errors = data.get("mechanicalErrors") or []
                revision = int(data.get("revisionCount", 0))
                if (total < 82 or fatal or errors) and revision < 2:
                    await self._publish(
                        command,
                        event_type="REVISION_STARTED",
                        status="RUNNING",
                        current_node="revise_chapter",
                        progress=65,
                        data={"revisionNo": revision + 1},
                    )

        try:
            if command.action is ChapterCommandAction.FINALIZE:
                existing = await self.workflow.get(command.thread_id)
                if existing.status is ChapterRunStatus.COMPLETED:
                    # Backend retries create a fresh task/idempotency key but
                    # intentionally reuse the durable chapter thread. Its final
                    # state is already authoritative, so replay it for the new
                    # task instead of trying to resume a completed LangGraph.
                    response = existing
                elif (
                    existing.status is ChapterRunStatus.RUNNING
                    and len(existing.processed_operation_keys) > 1
                ):
                    # Approval already left the interrupt, but summary/memory
                    # generation failed. A backend retry has a fresh operation
                    # key and must continue the checkpoint rather than attempt
                    # to resume human_review a second time.
                    response = await self.workflow.continue_thread(
                        command.thread_id,
                        on_update=on_update,
                        on_stream=on_stream,
                    )
                elif await self.workflow.operation_applied(
                    command.thread_id, command.idempotency_key
                ):
                    response = await self.workflow.continue_thread(
                        command.thread_id,
                        on_update=on_update,
                        on_stream=on_stream,
                    )
                else:
                    response = await self.workflow.finalize(
                        command,
                        on_update=on_update,
                        on_stream=on_stream,
                    )
            else:
                try:
                    existing = await self.workflow.get(command.thread_id)
                except ChapterWorkflowNotFound:
                    existing = None
                if existing is not None:
                    response = await self.workflow.continue_thread(
                        command.thread_id,
                        on_update=on_update,
                        on_stream=on_stream,
                    )
                else:
                    response = await self.workflow.start(
                        command,
                        on_update=on_update,
                        on_stream=on_stream,
                    )
        except ChapterEventPublishError:
            raise

        return self._terminal_event(command, response)

    async def _rewrite(self, command: ChapterCommand) -> dict[str, str]:
        proposal, call = await self.rewrite_agent.rewrite(command.payload)
        data = {
            **proposal.model_dump(mode="json", by_alias=True),
            "modelCalls": [self._camel_call(call)],
        }
        return self._event(
            command,
            event_type="REWRITE_PROPOSAL_READY",
            status="SUCCESS",
            current_node="rewrite_selection",
            progress=100,
            data=data,
        )

    def _terminal_event(
        self,
        command: ChapterCommand,
        response: ChapterRunResponse,
    ) -> dict[str, str]:
        calls = response.model_calls
        if command.action is ChapterCommandAction.FINALIZE:
            calls = calls[response.operation_call_start :]
        common = {
            "artifacts": response.artifacts,
            "modelCalls": [self._camel_call(call) for call in calls],
        }
        if response.status is ChapterRunStatus.PLAN_READY:
            event_type = "CHAPTER_PLAN_READY"
            status = "SUCCESS"
            data = {"plan": self._dump(response.chapter_plan), **common}
            progress = 100
        elif response.status is ChapterRunStatus.REVIEW_REQUIRED:
            event_type = "HUMAN_REVIEW_REQUIRED"
            status = "REVIEW_REQUIRED"
            data = {
                "plan": self._dump(response.chapter_plan),
                "content": response.draft_content,
                "review": self._dump(response.chapter_review),
                "mechanicalErrors": response.mechanical_errors,
                "revisionCount": response.revision_count,
                **common,
            }
            progress = 85
        elif response.status is ChapterRunStatus.COMPLETED:
            event_type = "FINAL_READY"
            status = "SUCCESS"
            data = {
                "plan": self._dump(response.chapter_plan),
                "content": response.final_content,
                "review": self._dump(response.chapter_review),
                "summary": self._dump(response.chapter_summary),
                "memoryUpdate": self._dump(response.memory_update),
                "revisionCount": response.revision_count,
                **common,
            }
            progress = 100
        else:
            raise ChapterWorkflowConflict(
                f"章节工作流意外终态：{response.status.value}"
            )
        return self._event(
            command,
            event_type=event_type,
            status=status,
            current_node=response.current_node,
            progress=progress,
            data=data,
        )

    async def _publish(
        self,
        command: ChapterCommand,
        *,
        event_type: str,
        status: str,
        current_node: str,
        progress: int,
        data: dict[str, Any],
        error_code: str = "",
        error_message: str = "",
    ) -> None:
        try:
            await self.broker.publish_event(
                self._event(
                    command,
                    event_type=event_type,
                    status=status,
                    current_node=current_node,
                    progress=progress,
                    data=data,
                    error_code=error_code,
                    error_message=error_message,
                )
            )
        except Exception as exc:
            raise ChapterEventPublishError("章节事件发布失败") from exc

    @staticmethod
    def _event(
        command: ChapterCommand,
        *,
        event_type: str,
        status: str,
        current_node: str,
        progress: int,
        data: dict[str, Any],
        error_code: str = "",
        error_message: str = "",
    ) -> dict[str, str]:
        return {
            "taskId": command.task_id,
            "storyId": str(command.story_id),
            "chapterId": str(command.chapter_id),
            "chapterNo": str(command.chapter_no),
            "threadId": command.thread_id,
            "type": event_type,
            # ChapterRedisBroker owns the authoritative monotonic sequence.
            "sequence": "0",
            "status": status,
            "currentNode": current_node,
            "progress": str(progress),
            "idempotencyKey": command.idempotency_key,
            "data": json.dumps(data, ensure_ascii=False, separators=(",", ":")),
            "errorCode": error_code,
            "errorMessage": error_message,
        }

    @staticmethod
    def _update_data(node: str, update: dict[str, Any]) -> dict[str, Any]:
        if node == "plan_chapter":
            return {"plan": update.get("chapter_plan")}
        if node in {"write_chapter", "revise_chapter"}:
            return {
                "content": update.get("draft_content", ""),
                "revisionCount": update.get("revision_count", 0),
                "artifacts": update.get("artifacts", []),
            }
        if node == "review_chapter":
            return {
                "review": update.get("chapter_review"),
                "mechanicalErrors": update.get("mechanical_errors", []),
                "revisionCount": update.get("revision_count", 0),
            }
        if node == "summarize_chapter":
            return {"summary": update.get("chapter_summary")}
        if node == "update_memory":
            return {"memoryUpdate": update.get("memory_update")}
        return {}

    @staticmethod
    def _thread_id(command: ChapterCommand) -> str:
        if command.thread_id:
            return command.thread_id
        return str(
            uuid5(
                NAMESPACE_URL,
                f"story-forge:chapter:{command.idempotency_key}",
            )
        )

    @staticmethod
    def _dump(value: Any) -> Any:
        return (
            value.model_dump(mode="json", by_alias=True)
            if hasattr(value, "model_dump")
            else value
        )

    @staticmethod
    def _camel_call(call: Mapping[str, Any]) -> dict[str, Any]:
        return {
            "node": call.get("node", ""),
            "modelName": call.get("model_name", call.get("modelName", "")),
            "promptVersion": call.get("prompt_version", call.get("promptVersion", "")),
            "inputTokens": call.get("input_tokens", call.get("inputTokens", 0)),
            "outputTokens": call.get("output_tokens", call.get("outputTokens", 0)),
            "durationMs": call.get("duration_ms", call.get("durationMs", 0)),
            "success": call.get("success", False),
            "error": call.get("error"),
        }

    async def _publish_invalid(
        self,
        fields: Mapping[str, str],
        exc: Exception,
    ) -> None:
        await self.broker.publish_event(
            {
                "taskId": fields.get("taskId", ""),
                "storyId": fields.get("storyId", ""),
                "chapterId": fields.get("chapterId", ""),
                "chapterNo": fields.get("chapterNo", ""),
                "threadId": fields.get("threadId", ""),
                "type": "TASK_FAILED",
                "status": "FAILED",
                "currentNode": "worker",
                "progress": "0",
                "idempotencyKey": fields.get("idempotencyKey", ""),
                "data": "{}",
                "errorCode": "INVALID_CHAPTER_COMMAND",
                "errorMessage": str(exc)[:2000],
            }
        )

    @staticmethod
    def _error_code(exc: Exception) -> str:
        if isinstance(exc, ChapterWorkflowNotFound):
            return "CHAPTER_WORKFLOW_NOT_FOUND"
        if isinstance(exc, ChapterWorkflowConflict):
            return "CHAPTER_WORKFLOW_STATE_CONFLICT"
        if isinstance(exc, ValidationError):
            return "CHAPTER_OUTPUT_INVALID"
        if isinstance(exc, WorkflowInvocationError):
            return "CHAPTER_MODEL_CALL_FAILED"
        if isinstance(exc, ChapterEventPublishError):
            return "CHAPTER_EVENT_PUBLISH_FAILED"
        return "CHAPTER_EXECUTION_FAILED"

    @staticmethod
    def _is_retryable(exc: Exception) -> bool:
        # Command decoding/validation is handled before execution and ACKed.
        # Once decoded, missing checkpoints and state conflicts cannot heal by
        # rerunning the same idempotent command; provider/output/infrastructure
        # failures can, so they consume the bounded retry budget.
        return not isinstance(
            exc,
            (ChapterWorkflowNotFound, ChapterWorkflowConflict),
        )

    def _terminal_delivery_key(self, message_id: str) -> str:
        return f"{self.idempotency.prefix}:terminal-delivery:{message_id}"

    async def _terminal_delivery(
        self,
        message_id: str,
    ) -> dict[str, Any] | None:
        raw = await self.idempotency.redis.get(self._terminal_delivery_key(message_id))
        if raw is None:
            return None
        if isinstance(raw, bytes):
            raw = raw.decode()
        return json.loads(str(raw))

    async def _remember_terminal_delivery(
        self,
        message_id: str,
        event: Mapping[str, Any],
    ) -> None:
        await self.idempotency.redis.set(
            self._terminal_delivery_key(message_id),
            json.dumps(
                dict(event),
                ensure_ascii=False,
                separators=(",", ":"),
            ),
            ex=self.idempotency.result_ttl_seconds,
        )

    async def _forget_terminal_delivery(self, message_id: str) -> None:
        await self.idempotency.redis.delete(self._terminal_delivery_key(message_id))


async def _main() -> None:
    from redis.asyncio import Redis

    settings = Settings.from_env()
    redis = Redis.from_url(settings.redis_url, decode_responses=True)
    broker = ChapterRedisBroker(
        redis,
        command_stream=settings.chapter_command_stream,
        event_stream=settings.chapter_event_stream,
        consumer_group=settings.chapter_consumer_group,
        consumer_name=(
            os.getenv("CHAPTER_CONSUMER_NAME", "").strip()
            or f"{socket.gethostname()}-{os.getpid()}"
        ),
        event_maxlen=settings.chapter_event_maxlen,
    )
    idempotency = IdempotencyStore(
        redis,
        prefix="story:chapter:idempotency",
    )
    try:
        async with persistent_chapter_service(
            settings.chapter_checkpoint_db
        ) as workflow:
            worker = ChapterWorkflowWorker(
                broker=broker,
                idempotency=idempotency,
                workflow=workflow,
                max_attempts=settings.chapter_max_attempts,
            )
            await worker.run_forever()
    finally:
        await redis.aclose()


if __name__ == "__main__":
    asyncio.run(_main())
