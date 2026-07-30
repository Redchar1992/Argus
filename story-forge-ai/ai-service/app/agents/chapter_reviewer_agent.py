"""Mechanical validation plus six-dimension chapter review."""

from __future__ import annotations

from typing import Any

from app.agents.chapter_utils import (
    chapter_artifact,
    chapter_progress,
    validate_chapter_content,
)
from app.agents.workflow_utils import invoke_structured
from app.infrastructure.llm_factory import StructuredModel, get_review_model
from app.prompts import load_prompt
from app.schemas.chapter import ChapterReview, build_chapter_review


def validate_chapter_node(state: dict[str, Any]) -> dict[str, Any]:
    errors = validate_chapter_content(
        state["draft_content"],
        state["chapter_plan"],
        {str(item["name"]) for item in state["characters"]},
    )
    return {
        "mechanical_errors": errors,
        "current_node": "validate_chapter",
        "progress_events": [
            chapter_progress(
                "validate_chapter",
                "机械校验通过" if not errors else f"机械校验发现{len(errors)}项问题",
            )
        ],
    }


class ChapterReviewerAgent:
    def __init__(self, model: StructuredModel | None = None) -> None:
        self.model = model or get_review_model()
        self.prompt = load_prompt("chapter_review")

    async def __call__(self, state: dict[str, Any]) -> dict[str, Any]:
        generation, call = await invoke_structured(
            self.model,
            ChapterReview,
            node="review_chapter",
            prompt_name="chapter_review",
            prompt=self.prompt,
            payload={
                "chapter_no": state["chapter_no"],
                "chapter_plan": state["chapter_plan"],
                "content": state["draft_content"],
                "mechanical_errors": state.get("mechanical_errors", []),
                "revision_count": state.get("revision_count", 0),
                **state["context_packet"],
            },
            purpose="chapter_review",
        )
        review = build_chapter_review(
            generation.value,
            list(state.get("mechanical_errors") or []),
        )
        review_data = review.model_dump(mode="json", by_alias=True)
        version_no = int(state.get("revision_count", 0)) + 1
        return {
            "chapter_review": review_data,
            "mechanical_errors": list(state.get("mechanical_errors") or []),
            "revision_count": int(state.get("revision_count", 0)),
            "current_node": "review_chapter",
            "progress_events": [
                chapter_progress(
                    "review_chapter",
                    f"章节质量审核完成：{review.total_score}分",
                    revision_no=version_no,
                )
            ],
            "artifacts": [
                chapter_artifact(
                    "CHAPTER_REVIEW",
                    version_no=version_no,
                    status="DRAFT",
                    content=review_data,
                    prompt_name="chapter_review",
                    model_name=generation.model_name,
                )
            ],
            "model_calls": [call],
        }
