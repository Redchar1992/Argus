"""Interruptible human review node."""

from __future__ import annotations

from typing import Any

from langgraph.types import interrupt

from app.agents.workflow_utils import progress
from app.schemas.workflow import ReviewDecision


def human_review(state: dict[str, Any]) -> dict[str, Any]:
    decision_payload = interrupt(
        {
            "type": "outline_review",
            "storyId": state["story_id"],
            "threadId": state["thread_id"],
            "score": state["score"],
            "characters": state["characters"],
            "outline": state["outline"],
            "artifacts": state.get("artifacts", []),
            "message": "请批准大纲，或填写具体修改意见。",
        }
    )
    if not isinstance(decision_payload, dict):
        decision_payload = {}
    operation_key = str(
        decision_payload.get("_operationKey")
        or decision_payload.get("_operation_key")
        or ""
    ).strip()
    decision = ReviewDecision.model_validate(
        {
            "approved": decision_payload.get("approved"),
            "notes": decision_payload.get("notes", ""),
        }
    )
    update = {
        "approved": decision.approved,
        "review_notes": decision.notes.strip(),
        "operation_call_start": len(state.get("model_calls", [])),
        "current_node": "human_review",
        "progress_events": [
            progress(
                "human_review",
                "用户已批准大纲" if decision.approved else "用户要求继续修改",
                status="completed",
                revision_no=int(state.get("revision_count", 0)) + 1,
            )
        ],
    }
    if operation_key:
        update["processed_operation_keys"] = [operation_key]
    return update
