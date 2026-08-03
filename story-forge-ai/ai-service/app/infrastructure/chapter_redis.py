"""Stable Redis Streams transport contract for chapter commands and events."""

from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from redis.exceptions import ResponseError, WatchError

from app.schemas.chapter import ChapterCommand

CHAPTER_COMMAND_STREAM = "story:chapter:commands"
CHAPTER_EVENT_STREAM = "story:chapter:events"
CHAPTER_COMMAND_FIELDS = (
    "taskId",
    "storyId",
    "chapterId",
    "chapterNo",
    "action",
    "threadId",
    "idempotencyKey",
    "payload",
)
CHAPTER_EVENT_FIELDS = (
    "taskId",
    "storyId",
    "chapterId",
    "chapterNo",
    "threadId",
    "type",
    "sequence",
    "status",
    "currentNode",
    "progress",
    "idempotencyKey",
    "data",
    "errorCode",
    "errorMessage",
)


@dataclass(frozen=True, slots=True)
class ChapterStreamEntry:
    message_id: str
    fields: dict[str, str]
    attempt_no: int = 1
    recovered: bool = False


def _text(value: object) -> str:
    return value.decode() if isinstance(value, bytes) else str(value)


def _decoded(fields: Mapping[object, object]) -> dict[str, str]:
    return {_text(key): _text(value) for key, value in fields.items()}


def encode_chapter_command(command: ChapterCommand) -> dict[str, str]:
    values = {
        "taskId": command.task_id,
        "storyId": str(command.story_id),
        "chapterId": str(command.chapter_id),
        "chapterNo": str(command.chapter_no),
        "action": command.action.value,
        "threadId": command.thread_id,
        "idempotencyKey": command.idempotency_key,
        "payload": json.dumps(
            command.payload,
            ensure_ascii=False,
            separators=(",", ":"),
        ),
    }
    return {field: values[field] for field in CHAPTER_COMMAND_FIELDS}


def decode_chapter_command(fields: Mapping[object, object]) -> ChapterCommand:
    values = _decoded(fields)
    try:
        payload = json.loads(values.get("payload") or "{}")
    except json.JSONDecodeError as exc:
        raise ValueError("payload必须是合法JSON对象") from exc
    if not isinstance(payload, dict):
        raise ValueError("payload必须是JSON对象")
    return ChapterCommand.model_validate(
        {
            "taskId": values.get("taskId", ""),
            "storyId": values.get("storyId", ""),
            "chapterId": values.get("chapterId", ""),
            "chapterNo": values.get("chapterNo", ""),
            "action": values.get("action", ""),
            "threadId": values.get("threadId", ""),
            "idempotencyKey": values.get("idempotencyKey", ""),
            "payload": payload,
        }
    )


def encode_chapter_event(fields: Mapping[str, object]) -> dict[str, str]:
    defaults: dict[str, object] = {
        "taskId": "",
        "storyId": "",
        "chapterId": "",
        "chapterNo": "",
        "threadId": "",
        "type": "",
        "sequence": "0",
        "status": "",
        "currentNode": "",
        "progress": "0",
        "idempotencyKey": "",
        "data": "{}",
        "errorCode": "",
        "errorMessage": "",
    }
    defaults.update(fields)
    return {field: _text(defaults[field]) for field in CHAPTER_EVENT_FIELDS}


class ChapterRedisBroker:
    def __init__(
        self,
        redis: Any,
        *,
        command_stream: str = CHAPTER_COMMAND_STREAM,
        event_stream: str = CHAPTER_EVENT_STREAM,
        consumer_group: str = "story-chapter-workers",
        consumer_name: str = "chapter-worker-1",
        event_maxlen: int = 100_000,
    ) -> None:
        self.redis = redis
        self.command_stream = command_stream
        self.event_stream = event_stream
        self.consumer_group = consumer_group
        self.consumer_name = consumer_name
        self.event_maxlen = event_maxlen
        self._autoclaim_cursor = "0-0"

    async def ensure_group(self) -> None:
        try:
            await self.redis.xgroup_create(
                self.command_stream,
                self.consumer_group,
                id="0-0",
                mkstream=True,
            )
        except ResponseError as exc:
            if "BUSYGROUP" not in str(exc):
                raise

    async def enqueue(self, command: ChapterCommand) -> str:
        return _text(
            await self.redis.xadd(
                self.command_stream,
                encode_chapter_command(command),
            )
        )

    async def publish_event(self, fields: Mapping[str, object]) -> str:
        task_id = _text(fields.get("taskId", ""))
        sequence_key = f"story:chapter:sequence:{task_id}"
        # WATCH + MULTI keeps sequence allocation and stream publication in one
        # atomic Redis transaction, including when several workers publish for
        # the same task concurrently.
        for _attempt in range(20):
            async with self.redis.pipeline(transaction=True) as pipeline:
                try:
                    await pipeline.watch(sequence_key)
                    current = await pipeline.get(sequence_key)
                    sequence = int(_text(current)) + 1 if current else 1
                    values = {**fields, "sequence": str(sequence)}
                    pipeline.multi()
                    pipeline.set(sequence_key, str(sequence))
                    # Do not trim producer-side: MAXLEN can evict an event that
                    # the backend consumer group has not persisted yet. The
                    # backend trims only after acknowledging deliveries.
                    pipeline.xadd(
                        self.event_stream,
                        encode_chapter_event(values),
                    )
                    result = await pipeline.execute()
                    return _text(result[-1])
                except WatchError:
                    continue
        raise RuntimeError("章节事件序号分配冲突，请重试")

    async def read_new(
        self,
        *,
        count: int = 1,
        block_ms: int | None = 1000,
    ) -> list[ChapterStreamEntry]:
        result = await self.redis.xreadgroup(
            self.consumer_group,
            self.consumer_name,
            {self.command_stream: ">"},
            count=count,
            block=block_ms,
        )
        return self._entries(result, recovered=False)

    async def recover_pending(
        self,
        *,
        min_idle_ms: int = 30_000,
        count: int = 10,
    ) -> list[ChapterStreamEntry]:
        result = await self.redis.xautoclaim(
            self.command_stream,
            self.consumer_group,
            self.consumer_name,
            min_idle_ms,
            self._autoclaim_cursor,
            count=count,
        )
        if result:
            self._autoclaim_cursor = _text(result[0])
        messages = result[1] if result and len(result) > 1 else []
        entries = self._entries(
            [(self.command_stream, messages)] if messages else [],
            recovered=True,
        )
        recovered: list[ChapterStreamEntry] = []
        for entry in entries:
            rows = await self.redis.xpending_range(
                self.command_stream,
                self.consumer_group,
                entry.message_id,
                entry.message_id,
                1,
            )
            count_value = (
                rows[0].get("times_delivered") or rows[0].get(b"times_delivered")
                if rows
                else 2
            )
            recovered.append(
                ChapterStreamEntry(
                    message_id=entry.message_id,
                    fields=entry.fields,
                    attempt_no=max(2, int(count_value or 2)),
                    recovered=True,
                )
            )
        return recovered

    async def acknowledge(self, message_id: str) -> int:
        acknowledged = int(
            await self.redis.xack(
                self.command_stream,
                self.consumer_group,
                message_id,
            )
        )
        if acknowledged:
            try:
                # The workflow result and emitted events are durable now. Keep
                # pending commands intact and remove only this ACKed payload.
                await self.redis.xdel(self.command_stream, message_id)
            except Exception:
                # Cleanup is best-effort after XACK; never replay completed work
                # merely because deletion briefly failed.
                pass
        return acknowledged

    @staticmethod
    def _entries(
        result: object,
        *,
        recovered: bool,
    ) -> list[ChapterStreamEntry]:
        entries: list[ChapterStreamEntry] = []
        for _stream_name, messages in result or []:  # type: ignore[union-attr]
            for message_id, fields in messages:
                entries.append(
                    ChapterStreamEntry(
                        message_id=_text(message_id),
                        fields=_decoded(fields),
                        recovered=recovered,
                    )
                )
        return entries
