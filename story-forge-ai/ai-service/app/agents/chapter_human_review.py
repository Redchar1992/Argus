"""Interrupt node for chapter approval or targeted human-requested revision."""

from __future__ import annotations

from typing import Any

from langgraph.types import interrupt

from app.agents.chapter_utils import chapter_progress
from app.schemas.chapter import ChapterDecision


def chapter_human_review(state: dict[str, Any]) -> dict[str, Any]:
    payload = interrupt(
        {
            "type": "chapter_review",
            "storyId": state["story_id"],
            "chapterId": state["chapter_id"],
            "chapterNo": state["chapter_no"],
            "threadId": state["thread_id"],
            "content": state["draft_content"],
            "review": state["chapter_review"],
            "mechanicalErrors": state.get("mechanical_errors", []),
            "revisionCount": state.get("revision_count", 0),
            "message": "请批准章节，或填写具体修改意见。",
        }
    )
    if not isinstance(payload, dict):
        payload = {}
    operation_key = str(
        payload.get("_operationKey") or payload.get("_operation_key") or ""
    ).strip()
    decision = ChapterDecision.model_validate(
        {
            key: value
            for key, value in payload.items()
            if key not in {"_operationKey", "_operation_key"}
        }
    )
    content = (decision.current_content or state["draft_content"]).strip()
    update: dict[str, Any] = {
        "approved": decision.approved,
        "user_notes": decision.notes,
        "draft_content": content,
        "final_content": content,
        "operation_call_start": len(state.get("model_calls", [])),
        "current_node": "human_review",
        "progress_events": [
            chapter_progress(
                "human_review",
                "用户已批准章节" if decision.approved else "用户要求继续修改章节",
                revision_no=int(state.get("revision_count", 0)) + 1,
            )
        ],
    }
    if operation_key:
        update["processed_operation_keys"] = [operation_key]
    return update
