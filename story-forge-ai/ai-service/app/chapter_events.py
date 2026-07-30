"""Invocation-scoped event sink used by streaming chapter nodes."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Any

ChapterEventSink = Callable[[str, dict[str, Any]], Awaitable[None]]
_sink: ContextVar[ChapterEventSink | None] = ContextVar(
    "chapter_event_sink", default=None
)


@contextmanager
def chapter_event_sink(sink: ChapterEventSink | None):
    token = _sink.set(sink)
    try:
        yield
    finally:
        _sink.reset(token)


async def emit_chapter_event(event_type: str, data: dict[str, Any]) -> None:
    sink = _sink.get()
    if sink is not None:
        await sink(event_type, data)
