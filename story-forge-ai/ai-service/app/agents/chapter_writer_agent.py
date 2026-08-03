"""Ordinary-text chapter writer with invocation-scoped token streaming."""

from __future__ import annotations

from typing import Any

from app.agents.chapter_utils import chapter_artifact, chapter_progress, invoke_text
from app.infrastructure.llm_factory import TextModel, get_creative_text_model
from app.prompts import load_profile_prompt


class ChapterWriterAgent:
    def __init__(self, model: TextModel | None = None) -> None:
        self.model = model or get_creative_text_model()
        self.prompt = None

    async def __call__(self, state: dict[str, Any]) -> dict[str, Any]:
        prompt, prompt_name = load_profile_prompt("chapter_write", state.get("content_mode", "SHORT_STORY"))
        content, call = await invoke_text(
            self.model,
            node="write_chapter",
            prompt_name=prompt_name,
            prompt=prompt,
            payload={
                "chapter_no": state["chapter_no"],
                "chapter_plan": state["chapter_plan"],
                "contentMode": state.get("content_mode", "SHORT_STORY"),
                "viewpoint": state.get("viewpoint", "THIRD_LIMITED"),
                **state["context_packet"],
            },
            purpose="chapter_write",
        )
        return {
            "draft_content": content,
            "final_content": content,
            "current_node": "write_chapter",
            "progress_events": [
                chapter_progress("write_chapter", "章节初稿生成完成", revision_no=1)
            ],
            "artifacts": [
                chapter_artifact(
                    "CHAPTER_CONTENT",
                    version_no=1,
                    status="DRAFT",
                    content={"content": content, "sourceType": "AI_DRAFT"},
                    prompt_name=prompt_name,
                    model_name=str(call["model_name"]),
                )
            ],
            "model_calls": [call],
        }
