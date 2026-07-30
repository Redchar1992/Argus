"""Typed state shared by the reusable LangGraph chapter subgraph."""

from __future__ import annotations

from operator import add
from typing import Annotated, Any, TypedDict


class ChapterState(TypedDict, total=False):
    # Business identity
    task_id: str
    story_id: int
    chapter_id: int
    chapter_no: int
    thread_id: str
    mode: str

    # Story context
    story_title: str
    genre: str
    target_audience: str
    style_profile: dict[str, Any]
    characters: list[dict[str, Any]]
    canon_facts: list[dict[str, Any]]
    relationship_states: list[dict[str, Any]]
    recent_summaries: list[dict[str, Any]]
    unresolved_threads: list[dict[str, Any]]
    foreshadowing_ledger: list[dict[str, Any]]
    # Exactly the two outline beats assigned to this chapter. outline_nodes is
    # retained as the legacy internal alias, never the complete story outline.
    current_outline_nodes: list[dict[str, Any]]
    outline_nodes: list[dict[str, Any]]

    # Current chapter
    chapter_plan: dict[str, Any]
    target_length: int
    context_packet: dict[str, Any]

    # Generated products
    draft_content: str
    final_content: str
    chapter_review: dict[str, Any]
    chapter_summary: dict[str, Any]
    memory_update: dict[str, Any]
    mechanical_errors: list[str]

    # Review controls
    revision_count: int
    max_revisions: int
    approved: bool
    user_notes: str
    status: str
    current_node: str
    operation_call_start: int

    # Append-only execution history
    progress_events: Annotated[list[dict[str, Any]], add]
    errors: Annotated[list[str], add]
    artifacts: Annotated[list[dict[str, Any]], add]
    model_calls: Annotated[list[dict[str, Any]], add]
    processed_operation_keys: Annotated[list[str], add]
