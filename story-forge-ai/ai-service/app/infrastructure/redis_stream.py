"""Redis Streams transport with consumer-group and idempotency helpers."""

from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from redis.exceptions import ResponseError

from app.schemas.workflow import RedisWorkflowMessage

REQUEST_STREAM = "story:workflow:requests"
EVENT_STREAM = "story:workflow:events"

REQUEST_FIELDS = (
    "taskId",
    "storyId",
    "threadId",
    "action",
    "payloadVersion",
    "idempotencyKey",
    "topic",
    "approved",
    "notes",
)
EVENT_FIELDS = (
    "taskId",
    "storyId",
    "threadId",
    "status",
    "currentNode",
    "progress",
    "attemptNo",
    "revisionCount",
    "maxRevisions",
    "idempotencyKey",
    "artifacts",
    "progressEvents",
    "inputTokens",
    "outputTokens",
    "modelName",
    "promptVersion",
    "durationMs",
    "modelCalls",
    "errorCode",
    "errorMessage",
)


@dataclass(frozen=True, slots=True)
class StreamEntry:
    message_id: str
    fields: dict[str, str]
    recovered: bool = False
    attempt_no: int = 1


def _text(value: object) -> str:
    if isinstance(value, bytes):
        return value.decode()
    return str(value)


def _decoded_fields(fields: Mapping[object, object]) -> dict[str, str]:
    return {_text(key): _text(value) for key, value in fields.items()}


def encode_request(message: RedisWorkflowMessage) -> dict[str, str]:
    """Encode the exact request contract expected by the Spring producer."""

    topic = (
        json.dumps(
            message.topic.model_dump(mode="json", by_alias=True),
            ensure_ascii=False,
            separators=(",", ":"),
        )
        if message.topic is not None
        else ""
    )
    approved = (
        ""
        if message.approved is None
        else ("true" if message.approved else "false")
    )
    values = {
        "taskId": message.task_id,
        "storyId": str(message.story_id),
        "threadId": message.thread_id,
        "action": message.action,
        "payloadVersion": message.payload_version,
        "idempotencyKey": message.resolved_idempotency_key(),
        "topic": topic,
        "approved": approved,
        "notes": message.notes,
    }
    return {field: values[field] for field in REQUEST_FIELDS}


def decode_request(fields: Mapping[object, object]) -> RedisWorkflowMessage:
    """Validate a Redis string-field message as a workflow command."""

    values = _decoded_fields(fields)
    topic_raw = values.get("topic", "").strip()
    approved_raw = values.get("approved", "").strip().lower()
    try:
        topic = json.loads(topic_raw) if topic_raw else None
    except json.JSONDecodeError as exc:
        raise ValueError("topic必须是合法JSON对象") from exc

    approved: bool | None
    if not approved_raw:
        approved = None
    elif approved_raw in {"true", "1", "yes"}:
        approved = True
    elif approved_raw in {"false", "0", "no"}:
        approved = False
    else:
        raise ValueError("approved必须为true或false")

    return RedisWorkflowMessage.model_validate(
        {
            "taskId": values.get("taskId", ""),
            "storyId": values.get("storyId", ""),
            "threadId": values.get("threadId", ""),
            "action": values.get("action", ""),
            "payloadVersion": values.get("payloadVersion", "1"),
            "idempotencyKey": values.get("idempotencyKey") or None,
            "topic": topic,
            "approved": approved,
            "notes": values.get("notes", ""),
        }
    )


def encode_event(fields: Mapping[str, object]) -> dict[str, str]:
    """Normalize every event field to a Redis string."""

    defaults: dict[str, object] = {
        "taskId": "",
        "storyId": "",
        "threadId": "",
        "status": "",
        "currentNode": "",
        "progress": "0",
        "attemptNo": "1",
        "revisionCount": "0",
        "maxRevisions": "2",
        "idempotencyKey": "",
        "artifacts": "[]",
        "progressEvents": "[]",
        "inputTokens": "0",
        "outputTokens": "0",
        "modelName": "",
        "promptVersion": "",
        "durationMs": "0",
        "modelCalls": "[]",
        "errorCode": "",
        "errorMessage": "",
    }
    defaults.update(fields)
    return {field: _text(defaults[field]) for field in EVENT_FIELDS}


class RedisStreamBroker:
    """Small async adapter around the Redis consumer-group primitives."""

    def __init__(
        self,
        redis: Any,
        *,
        request_stream: str = REQUEST_STREAM,
        event_stream: str = EVENT_STREAM,
        consumer_group: str = "story-workflow-workers",
        consumer_name: str = "worker-1",
    ) -> None:
        self.redis = redis
        self.request_stream = request_stream
        self.event_stream = event_stream
        self.consumer_group = consumer_group
        self.consumer_name = consumer_name
        self._autoclaim_cursor = "0-0"

    async def ensure_group(self) -> None:
        try:
            await self.redis.xgroup_create(
                self.request_stream,
                self.consumer_group,
                id="0-0",
                mkstream=True,
            )
        except ResponseError as exc:
            if "BUSYGROUP" not in str(exc):
                raise

    async def enqueue(self, message: RedisWorkflowMessage) -> str:
        message_id = await self.redis.xadd(
            self.request_stream,
            encode_request(message),
        )
        return _text(message_id)

    async def publish_event(self, fields: Mapping[str, object]) -> str:
        message_id = await self.redis.xadd(
            self.event_stream,
            encode_event(fields),
        )
        return _text(message_id)

    async def read_new(
        self,
        *,
        count: int = 1,
        block_ms: int | None = 1000,
    ) -> list[StreamEntry]:
        result = await self.redis.xreadgroup(
            self.consumer_group,
            self.consumer_name,
            {self.request_stream: ">"},
            count=count,
            block=block_ms,
        )
        return self._entries(result, recovered=False)

    async def recover_pending(
        self,
        *,
        min_idle_ms: int = 30_000,
        count: int = 10,
    ) -> list[StreamEntry]:
        result = await self.redis.xautoclaim(
            self.request_stream,
            self.consumer_group,
            self.consumer_name,
            min_idle_ms,
            self._autoclaim_cursor,
            count=count,
        )
        # redis-py returns (next_start_id, messages, deleted_ids).
        if result:
            self._autoclaim_cursor = _text(result[0])
        messages = result[1] if result and len(result) > 1 else []
        wrapped = [(self.request_stream, messages)] if messages else []
        entries = self._entries(wrapped, recovered=True)
        enriched: list[StreamEntry] = []
        for entry in entries:
            pending = await self.redis.xpending_range(
                self.request_stream,
                self.consumer_group,
                entry.message_id,
                entry.message_id,
                1,
            )
            delivery_count = (
                pending[0].get("times_delivered")
                or pending[0].get(b"times_delivered")
                if pending
                else 2
            )
            attempt_no = (
                int(delivery_count)
                if delivery_count is not None
                else 2
            )
            enriched.append(
                StreamEntry(
                    message_id=entry.message_id,
                    fields=entry.fields,
                    recovered=True,
                    attempt_no=max(2, attempt_no),
                )
            )
        return enriched

    async def acknowledge(self, message_id: str) -> int:
        return int(
            await self.redis.xack(
                self.request_stream,
                self.consumer_group,
                message_id,
            )
        )

    @staticmethod
    def _entries(result: object, *, recovered: bool) -> list[StreamEntry]:
        entries: list[StreamEntry] = []
        for _stream_name, messages in result or []:  # type: ignore[union-attr]
            for message_id, fields in messages:
                entries.append(
                    StreamEntry(
                        message_id=_text(message_id),
                        fields=_decoded_fields(fields),
                        recovered=recovered,
                    )
                )
        return entries


class IdempotencyStore:
    """Redis-backed completed-result marker plus short-lived processing lock."""

    def __init__(
        self,
        redis: Any,
        *,
        prefix: str = "story:workflow:idempotency",
        lock_ttl_seconds: int = 300,
        result_ttl_seconds: int = 7 * 24 * 60 * 60,
    ) -> None:
        self.redis = redis
        self.prefix = prefix
        self.lock_ttl_seconds = lock_ttl_seconds
        self.result_ttl_seconds = result_ttl_seconds

    def _result_key(self, key: str) -> str:
        return f"{self.prefix}:result:{key}"

    def _lock_key(self, key: str) -> str:
        return f"{self.prefix}:lock:{key}"

    async def completed_result(self, key: str) -> dict[str, Any] | None:
        raw = await self.redis.get(self._result_key(key))
        if raw is None:
            return None
        return json.loads(_text(raw))

    async def acquire(self, key: str) -> bool:
        acquired = await self.redis.set(
            self._lock_key(key),
            "1",
            nx=True,
            ex=self.lock_ttl_seconds,
        )
        return bool(acquired)

    async def mark_completed(
        self,
        key: str,
        result: Mapping[str, Any],
    ) -> None:
        payload = json.dumps(
            dict(result),
            ensure_ascii=False,
            separators=(",", ":"),
        )
        pipeline = self.redis.pipeline(transaction=True)
        pipeline.set(
            self._result_key(key),
            payload,
            ex=self.result_ttl_seconds,
        )
        pipeline.delete(self._lock_key(key))
        await pipeline.execute()

    async def release(self, key: str) -> None:
        await self.redis.delete(self._lock_key(key))
