"""Targeted full-chapter revision preserving every earlier artifact."""

from __future__ import annotations

from typing import Any

from app.agents.chapter_utils import chapter_artifact, chapter_progress, invoke_text
from app.infrastructure.llm_factory import TextModel, get_creative_text_model
from app.prompts import load_prompt


class ChapterRevisionAgent:
    def __init__(self, model: TextModel | None = None) -> None:
        self.model = model or get_creative_text_model()
        self.prompt = load_prompt("chapter_revision")

    async def __call__(self, state: dict[str, Any]) -> dict[str, Any]:
        previous = int(state.get("revision_count", 0))
        revision_count = previous + 1
        content, call = await invoke_text(
            self.model,
            node="revise_chapter",
            prompt_name="chapter_revision",
            prompt=self.prompt,
            payload={
                "chapter_no": state["chapter_no"],
                "chapter_plan": state["chapter_plan"],
                "content": state["draft_content"],
                "review": state.get("chapter_review", {}),
                "mechanical_errors": state.get("mechanical_errors", []),
                "user_notes": state.get("user_notes", ""),
                "revision_count": previous,
                **state["context_packet"],
            },
            purpose="chapter_revision",
        )
        return {
            "draft_content": content,
            "final_content": content,
            "revision_count": revision_count,
            "user_notes": "",
            "approved": False,
            "current_node": "revise_chapter",
            "progress_events": [
                chapter_progress(
                    "revise_chapter",
                    f"已完成第{revision_count}次章节修改",
                    revision_no=revision_count + 1,
                )
            ],
            "artifacts": [
                chapter_artifact(
                    "CHAPTER_CONTENT",
                    version_no=revision_count + 1,
                    status="DRAFT",
                    content={"content": content, "sourceType": "AI_REVISION"},
                    prompt_name="chapter_revision",
                    model_name=str(call["model_name"]),
                )
            ],
            "model_calls": [call],
        }
