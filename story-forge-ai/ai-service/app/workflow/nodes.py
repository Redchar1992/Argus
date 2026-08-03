"""Non-LLM workflow nodes and conditional routing."""

from __future__ import annotations

from typing import Any, Literal

from app.agents.workflow_utils import artifact, progress


def route_after_score(
    state: dict[str, Any],
) -> Literal["revise_outline", "prepare_human_review"]:
    total = int(state["score"]["total"])
    revision_count = int(state.get("revision_count", 0))
    max_revisions = int(state.get("max_revisions", 2))

    if total < 80 and revision_count < max_revisions:
        return "revise_outline"
    return "prepare_human_review"


def prepare_human_review(state: dict[str, Any]) -> dict[str, Any]:
    version_no = int(state.get("revision_count", 0)) + 1
    return {
        "status": "REVIEW_REQUIRED",
        "current_node": "human_review",
        "progress_events": [
            progress(
                "human_review",
                "故事方案等待用户审核",
                status="waiting",
                revision_no=version_no,
            )
        ],
    }


def route_after_human_review(
    state: dict[str, Any],
) -> Literal["finish", "revise_outline"]:
    return "finish" if state.get("approved") else "revise_outline"


def finish_workflow(state: dict[str, Any]) -> dict[str, Any]:
    version_no = int(state.get("revision_count", 0)) + 1
    final_content = {
        "topic": state["topic"],
        "contentMode": state.get("content_mode", "SHORT_STORY"),
        "storyProfile": {
            "targetChapterCount": state.get("target_chapter_count"),
            "targetTotalWords": state.get("target_total_words"),
            "chapterTargetWords": state.get("chapter_target_words"),
            "viewpoint": state.get("viewpoint", "THIRD_LIMITED"),
            "styleProfile": state.get("style_profile", {}),
        },
        "characters": state["characters"],
        "outlineMetadata": state.get("outline_metadata", {}),
        "outline": state["outline"],
        "score": state["score"],
    }
    return {
        "status": "COMPLETED",
        "current_node": "finish",
        "approved": True,
        "progress_events": [
            progress(
                "finish",
                "故事方案已确认",
                revision_no=version_no,
            )
        ],
        "artifacts": [
            artifact(
                artifact_type="WORKFLOW_FINAL",
                version_no=version_no,
                status="APPROVED",
                content=final_content,
                prompt_name="workflow",
                model_name="application",
            )
        ],
    }
