"""Typed shared state for the LangGraph story workflow."""

from __future__ import annotations

from operator import add
from typing import Annotated, Any, TypedDict


class StoryState(TypedDict, total=False):
    # Business identity
    task_id: str
    story_id: int
    thread_id: str
    topic: dict[str, Any]
    content_mode: str
    target_chapter_count: int
    target_total_words: int
    chapter_target_words: int
    viewpoint: str
    style_profile: dict[str, Any]

    # Current material. These fields intentionally use overwrite semantics.
    characters: list[dict[str, Any]]
    outline: list[dict[str, Any]]
    outline_metadata: dict[str, Any]
    score: dict[str, Any]

    # Revision and review controls
    revision_count: int
    max_revisions: int
    review_notes: str
    approved: bool
    current_node: str
    status: str
    operation_call_start: int

    # Append-only history. Reducers preserve every version and model call.
    progress_events: Annotated[list[dict[str, Any]], add]
    errors: Annotated[list[str], add]
    artifacts: Annotated[list[dict[str, Any]], add]
    model_calls: Annotated[list[dict[str, Any]], add]
    processed_operation_keys: Annotated[list[str], add]
