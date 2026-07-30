"""At-least-once Redis Streams worker for the story workflow."""

from __future__ import annotations

import asyncio
import json
import os
import socket
from collections.abc import Mapping
from typing import Any
from uuid import NAMESPACE_URL, uuid5

from pydantic import ValidationError

from app.agents.workflow_utils import WorkflowInvocationError
from app.config import Settings
from app.infrastructure.redis_stream import (
    IdempotencyStore,
    RedisStreamBroker,
    StreamEntry,
    decode_request,
)
from app.schemas.workflow import (
    ArtifactRecord,
    ModelCallRecord,
    ProgressEvent,
    RedisWorkflowMessage,
    ReviewDecision,
    WorkflowRunResponse,
    WorkflowStartRequest,
    WorkflowStatus,
)
from app.workflow.service import (
    StoryWorkflowConflict,
    StoryWorkflowNotFound,
    StoryWorkflowService,
)

NODE_PROGRESS = {
    "generate_characters": 20,
    "generate_outline": 45,
    "score_outline": 65,
    "revise_outline": 75,
    "human_review": 85,
    "finish": 100,
}


class WorkflowEventPublishError(RuntimeError):
    """Retryable event-transport failure, not a story-generation failure."""


def _artifact_payload(response: WorkflowRunResponse) -> list[dict[str, Any]]:
    """Return only the exact cross-service artifact contract."""

    return [
        {
            "artifactType": item.artifact_type,
            "versionNo": item.version_no,
            "status": item.status,
            "content": item.content,
            "promptVersion": item.prompt_version,
            "modelName": item.model_name,
        }
        for item in response.artifacts
    ]


def _progress_payload(response: WorkflowRunResponse) -> list[dict[str, Any]]:
    return [
        event.model_dump(mode="json", by_alias=True)
        for event in response.progress_events
    ]


def _new_artifact_payload(items: object) -> list[dict[str, Any]]:
    if not isinstance(items, list):
        return []
    return [
        {
            "artifactType": item.artifact_type,
            "versionNo": item.version_no,
            "status": item.status,
            "content": item.content,
            "promptVersion": item.prompt_version,
            "modelName": item.model_name,
        }
        for raw in items
        for item in [ArtifactRecord.model_validate(raw)]
    ]


def _new_progress_payload(items: object) -> list[dict[str, Any]]:
    if not isinstance(items, list):
        return []
    return [
        ProgressEvent.model_validate(raw).model_dump(mode="json", by_alias=True)
        for raw in items
    ]


def _model_call_payload(response: WorkflowRunResponse) -> list[dict[str, Any]]:
    return [
        call.model_dump(mode="json", by_alias=True) for call in response.model_calls
    ]


def _new_model_call_payload(items: object) -> list[dict[str, Any]]:
    if not isinstance(items, list):
        return []
    return [
        ModelCallRecord.model_validate(raw).model_dump(mode="json", by_alias=True)
        for raw in items
    ]


def _model_call_fields(calls: list[dict[str, Any]]) -> dict[str, str]:
    if not calls:
        return {
            "inputTokens": "0",
            "outputTokens": "0",
            "modelName": "",
            "promptVersion": "",
            "durationMs": "0",
            "modelCalls": "[]",
        }
    last = calls[-1]
    return {
        "inputTokens": str(sum(int(call.get("inputTokens", 0)) for call in calls)),
        "outputTokens": str(sum(int(call.get("outputTokens", 0)) for call in calls)),
        "modelName": str(last.get("modelName", "")),
        "promptVersion": str(last.get("promptVersion", "")),
        "durationMs": str(sum(int(call.get("durationMs", 0)) for call in calls)),
        "modelCalls": json.dumps(
            calls,
            ensure_ascii=False,
            separators=(",", ":"),
        ),
    }


class StoryWorkflowWorker:
    """Consume requests, execute the graph, publish state, then acknowledge."""

    def __init__(
        self,
        *,
        broker: RedisStreamBroker,
        idempotency: IdempotencyStore,
        workflow: StoryWorkflowService | None = None,
    ) -> None:
        self.broker = broker
        self.idempotency = idempotency
        self.workflow = workflow or StoryWorkflowService()
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
            await self.process_entry(entry, attempt_no=entry.attempt_no)
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
            await self.process_entry(entry, attempt_no=entry.attempt_no)
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

    async def process_entry(
        self,
        entry: StreamEntry,
        *,
        attempt_no: int,
    ) -> None:
        try:
            message = decode_request(entry.fields)
        except (ValidationError, ValueError, TypeError) as exc:
            await self._publish_invalid(entry.fields, attempt_no, exc)
            # A malformed message cannot become valid through retry.
            await self.broker.acknowledge(entry.message_id)
            return

        key = message.resolved_idempotency_key()
        completed = await self.idempotency.completed_result(key)
        if completed is not None:
            # The previous attempt may have persisted the final event and marker
            # but lost XACK. Replay the stored terminal event before ACK so a
            # newer transient event can never remain the backend's last state.
            try:
                replay = {**completed, "attemptNo": str(entry.attempt_no)}
                await self.broker.publish_event(replay)
                await self.broker.acknowledge(entry.message_id)
            except Exception:
                # Keep the entry pending; recovery can retry without rerunning
                # the graph because the completed marker is already durable.
                pass
            return
        owner_token = await self.idempotency.acquire(key)
        if owner_token is None:
            # Another consumer owns the operation; keep this delivery pending.
            return
        heartbeat = self.idempotency.heartbeat(key, owner_token)
        await heartbeat.start()
        try:
            await self._process_owned_entry(
                entry,
                message,
                key=key,
                owner_token=owner_token,
                attempt_no=attempt_no,
            )
        except asyncio.CancelledError:
            if not heartbeat.lost:
                await self.idempotency.release(key, owner_token)
                raise
        finally:
            await heartbeat.stop()

    async def _process_owned_entry(
        self,
        entry: StreamEntry,
        message: RedisWorkflowMessage,
        *,
        key: str,
        owner_token: str,
        attempt_no: int,
    ) -> None:

        thread_id = self._thread_id(message, key)
        current_revision = 0
        current_max_revisions = message.max_revisions
        if message.action == "RESUME":
            try:
                current = await self.workflow.get(thread_id)
                current_revision = current.revision_count
                current_max_revisions = current.max_revisions
            except StoryWorkflowNotFound:
                pass
        try:
            await self.broker.publish_event(
                self._event(
                    message,
                    thread_id=thread_id,
                    status="RUNNING",
                    current_node=(
                        "generate_characters"
                        if message.action == "START"
                        else "human_review"
                    ),
                    progress=0,
                    attempt_no=attempt_no,
                    revision_count=current_revision,
                    max_revisions=current_max_revisions,
                    idempotency_key=key,
                )
            )
        except Exception:
            await self.idempotency.release(key, owner_token)
            return

        try:
            last_progress = 0

            async def publish_update(
                node: str,
                update: dict[str, Any],
            ) -> None:
                nonlocal current_revision, last_progress
                if node in {"prepare_human_review", "finish"}:
                    return
                current_revision = int(update.get("revision_count", current_revision))
                current_node = str(update.get("current_node", node))
                progress_value = self._update_progress(
                    current_node,
                    update,
                    last_progress,
                )
                last_progress = max(last_progress, progress_value)
                try:
                    await self.broker.publish_event(
                        self._event(
                            message,
                            thread_id=thread_id,
                            status="RUNNING",
                            current_node=current_node,
                            progress=last_progress,
                            attempt_no=attempt_no,
                            revision_count=current_revision,
                            max_revisions=current_max_revisions,
                            idempotency_key=key,
                            artifacts=_new_artifact_payload(
                                update.get("artifacts", [])
                            ),
                            progress_events=_new_progress_payload(
                                update.get("progress_events", [])
                            ),
                            model_calls=_new_model_call_payload(
                                update.get("model_calls", [])
                            ),
                        )
                    )
                except Exception as exc:
                    raise WorkflowEventPublishError("节点进度事件发布失败") from exc

            response = await self._execute(
                message,
                thread_id,
                idempotency_key=key,
                on_update=publish_update,
            )
            status = (
                "SUCCESS"
                if response.status is WorkflowStatus.COMPLETED
                else response.status.value
            )
            progress_value = (
                100
                if status == "SUCCESS"
                else NODE_PROGRESS.get(response.current_node, 0)
            )
            event = self._event(
                message,
                thread_id=response.thread_id,
                status=status,
                current_node=response.current_node,
                progress=progress_value,
                attempt_no=attempt_no,
                revision_count=response.revision_count,
                max_revisions=response.max_revisions,
                idempotency_key=key,
                artifacts=_artifact_payload(response),
                progress_events=_progress_payload(response),
                model_calls=_model_call_payload(response)[
                    response.operation_call_start :
                ],
            )
        except Exception as exc:
            if isinstance(exc, WorkflowEventPublishError):
                await self.idempotency.release(key, owner_token)
                return
            failed_calls = (
                _new_model_call_payload([exc.call])
                if isinstance(exc, WorkflowInvocationError)
                else []
            )
            try:
                await self.broker.publish_event(
                    self._event(
                        message,
                        thread_id=thread_id,
                        status="FAILED",
                        current_node="worker",
                        progress=0,
                        attempt_no=attempt_no,
                        revision_count=current_revision,
                        max_revisions=current_max_revisions,
                        idempotency_key=key,
                        error_code=self._error_code(exc),
                        error_message=str(exc)[:2000],
                        model_calls=failed_calls,
                    )
                )
            finally:
                await self.idempotency.release(key, owner_token)
            # Intentionally omit XACK: XAUTOCLAIM can recover the delivery.
            return

        # Transport/idempotency failures after successful graph execution are
        # retryable infrastructure failures, not semantic workflow failures.
        # In particular, never emit a higher-ID FAILED after a final event.
        try:
            await self.broker.publish_event(event)
            if not await self.idempotency.mark_completed(
                key,
                event,
                owner_token,
            ):
                return
        except Exception:
            await self.idempotency.release(key, owner_token)
            return

        # XACK is deliberately outside the execution failure handler. If it
        # fails, the durable marker lets redelivery replay the terminal result.
        try:
            await self.broker.acknowledge(entry.message_id)
        except Exception:
            pass

    async def _execute(
        self,
        message: RedisWorkflowMessage,
        thread_id: str,
        *,
        idempotency_key: str,
        on_update: Any,
    ) -> WorkflowRunResponse:
        if message.action == "RESUME":
            try:
                already_applied = await self.workflow.operation_applied(
                    thread_id,
                    idempotency_key,
                )
            except StoryWorkflowNotFound:
                already_applied = False
            if already_applied:
                existing = await self.workflow.get(thread_id)
                if existing.status is WorkflowStatus.RUNNING:
                    return await self.workflow.continue_thread(
                        thread_id,
                        on_update=on_update,
                    )
                return existing
            return await self.workflow.resume(
                thread_id,
                ReviewDecision(
                    approved=bool(message.approved),
                    notes=message.notes,
                ),
                on_update=on_update,
                operation_key=idempotency_key,
            )

        try:
            # Covers the crash window between graph completion and recording the
            # idempotency result when this worker process is still alive.
            existing = await self.workflow.get(thread_id)
            if existing.status is WorkflowStatus.RUNNING:
                return await self.workflow.continue_thread(
                    thread_id,
                    on_update=on_update,
                )
            return existing
        except StoryWorkflowNotFound:
            pass

        try:
            return await self.workflow.start(
                WorkflowStartRequest(
                    task_id=message.task_id,
                    story_id=message.story_id,
                    thread_id=thread_id,
                    topic=message.topic,
                    max_revisions=message.max_revisions,
                ),
                on_update=on_update,
                operation_key=idempotency_key,
            )
        except StoryWorkflowConflict:
            return await self.workflow.get(thread_id)

    @staticmethod
    def _thread_id(message: RedisWorkflowMessage, key: str) -> str:
        if message.thread_id:
            return message.thread_id
        # Stable across redelivery, unlike a random per-attempt UUID.
        return str(uuid5(NAMESPACE_URL, f"story-forge:{key}"))

    @staticmethod
    def _event(
        message: RedisWorkflowMessage,
        *,
        thread_id: str,
        status: str,
        current_node: str,
        progress: int,
        attempt_no: int,
        revision_count: int,
        max_revisions: int,
        idempotency_key: str,
        artifacts: list[dict[str, Any]] | None = None,
        progress_events: list[dict[str, Any]] | None = None,
        model_calls: list[dict[str, Any]] | None = None,
        error_code: str = "",
        error_message: str = "",
    ) -> dict[str, str]:
        event = {
            "taskId": message.task_id,
            "storyId": str(message.story_id),
            "threadId": thread_id,
            "status": status,
            "currentNode": current_node,
            "progress": str(progress),
            "attemptNo": str(attempt_no),
            "revisionCount": str(revision_count),
            "maxRevisions": str(max_revisions),
            "idempotencyKey": idempotency_key,
            "artifacts": json.dumps(
                artifacts or [],
                ensure_ascii=False,
                separators=(",", ":"),
            ),
            "progressEvents": json.dumps(
                progress_events or [],
                ensure_ascii=False,
                separators=(",", ":"),
            ),
            "errorCode": error_code,
            "errorMessage": error_message,
        }
        event.update(_model_call_fields(model_calls or []))
        return event

    async def _publish_invalid(
        self,
        fields: Mapping[str, str],
        attempt_no: int,
        exc: Exception,
    ) -> None:
        await self.broker.publish_event(
            {
                "taskId": fields.get("taskId", ""),
                "storyId": fields.get("storyId", ""),
                "threadId": fields.get("threadId", ""),
                "status": "FAILED",
                "currentNode": "worker",
                "progress": "0",
                "attemptNo": str(attempt_no),
                "revisionCount": "0",
                "maxRevisions": "2",
                "idempotencyKey": fields.get("idempotencyKey", ""),
                "artifacts": "[]",
                "progressEvents": "[]",
                "inputTokens": "0",
                "outputTokens": "0",
                "modelName": "",
                "promptVersion": "",
                "durationMs": "0",
                "modelCalls": "[]",
                "errorCode": "INVALID_WORKFLOW_MESSAGE",
                "errorMessage": str(exc)[:2000],
            }
        )

    @staticmethod
    def _error_code(exc: Exception) -> str:
        if isinstance(exc, StoryWorkflowNotFound):
            return "WORKFLOW_NOT_FOUND"
        if isinstance(exc, StoryWorkflowConflict):
            return "WORKFLOW_STATE_CONFLICT"
        if isinstance(exc, ValidationError):
            return "WORKFLOW_OUTPUT_INVALID"
        if isinstance(exc, WorkflowInvocationError):
            return "WORKFLOW_MODEL_CALL_FAILED"
        return "WORKFLOW_EXECUTION_FAILED"

    @staticmethod
    def _update_progress(
        node: str,
        update: dict[str, Any],
        previous: int,
    ) -> int:
        progress_value = NODE_PROGRESS.get(node, previous)
        artifacts = update.get("artifacts")
        if isinstance(artifacts, list) and artifacts:
            record = ArtifactRecord.model_validate(artifacts[-1])
            if node == "score_outline":
                progress_value = min(84, 57 + 8 * record.version_no)
            elif node == "revise_outline":
                progress_value = min(82, 60 + 8 * record.version_no)
        return max(previous, progress_value)


async def _main() -> None:
    from redis.asyncio import Redis

    settings = Settings.from_env()
    redis = Redis.from_url(settings.redis_url, decode_responses=True)
    broker = RedisStreamBroker(
        redis,
        request_stream=settings.redis_request_stream,
        event_stream=settings.redis_event_stream,
        consumer_group=settings.redis_consumer_group,
        consumer_name=(
            os.getenv("REDIS_CONSUMER_NAME", "").strip()
            or f"{socket.gethostname()}-{os.getpid()}"
        ),
    )
    worker = StoryWorkflowWorker(
        broker=broker,
        idempotency=IdempotencyStore(redis),
    )
    try:
        await worker.run_forever()
    finally:
        await redis.aclose()


if __name__ == "__main__":
    asyncio.run(_main())
