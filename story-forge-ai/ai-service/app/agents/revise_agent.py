"""Full-outline revision agent that preserves all prior artifact versions."""

from __future__ import annotations

from typing import Any

from app.agents.workflow_utils import artifact, invoke_structured, progress
from app.infrastructure.llm_factory import StructuredModel, get_creative_model
from app.prompts import load_profile_prompt
from app.schemas.outline import OutlineResult, validate_outline


class ReviseAgent:
    def __init__(self, model: StructuredModel | None = None) -> None:
        self.model = model or get_creative_model()
        self.prompt = None

    async def __call__(self, state: dict[str, Any]) -> dict[str, Any]:
        previous_revision_count = int(state.get("revision_count", 0))
        revision_count = previous_revision_count + 1
        version_no = revision_count + 1
        content_mode = state.get("content_mode", "SHORT_STORY")
        prompt, prompt_name = load_profile_prompt("revise", content_mode)
        generation, call = await invoke_structured(
            self.model,
            OutlineResult,
            node="revise_outline",
            prompt_name=prompt_name,
            prompt=prompt,
            payload={
                "topic": state["topic"],
                "characters": state["characters"],
                "outline": state["outline"],
                "outline_metadata": state.get("outline_metadata", {}),
                "score": state["score"],
                "review_notes": state.get("review_notes", ""),
                "revision_count": previous_revision_count,
                "contentMode": content_mode,
                "targetChapterCount": state.get("target_chapter_count"),
            },
            purpose="revise",
        )
        result = generation.value
        validate_outline(
            result.nodes,
            content_mode,
            int(state.get("target_chapter_count", 0) or 0) or None,
        )
        nodes = [node.model_dump(mode="json") for node in result.nodes]
        metadata = {
            "title": result.title,
            "core_conflict": result.core_conflict,
            "ending_type": result.ending_type,
        }
        return {
            "outline": nodes,
            "outline_metadata": metadata,
            "revision_count": revision_count,
            "review_notes": "",
            "approved": False,
            "current_node": "revise_outline",
            "progress_events": [
                progress(
                    "revise_outline",
                    f"已完成第{revision_count}次大纲修改",
                    revision_no=version_no,
                )
            ],
            "artifacts": [
                artifact(
                    artifact_type="OUTLINE",
                    version_no=version_no,
                    status="DRAFT",
                    content={**metadata, "nodes": nodes},
                    prompt_name=prompt_name,
                    model_name=generation.model_name,
                )
            ],
            "model_calls": [call],
        }


async def revise_outline(state: dict[str, Any]) -> dict[str, Any]:
    return await ReviseAgent()(state)
