"""Three-to-six scene chapter planning agent."""

from __future__ import annotations

from typing import Any

from app.agents.chapter_utils import chapter_artifact, chapter_progress
from app.agents.workflow_utils import invoke_structured
from app.infrastructure.llm_factory import StructuredModel, get_creative_model
from app.prompts import load_prompt
from app.schemas.chapter import ChapterPlan


class ChapterPlanAgent:
    def __init__(self, model: StructuredModel | None = None) -> None:
        self.model = model or get_creative_model()
        self.prompt = load_prompt("chapter_plan")

    async def __call__(self, state: dict[str, Any]) -> dict[str, Any]:
        generation, call = await invoke_structured(
            self.model,
            ChapterPlan,
            node="plan_chapter",
            prompt_name="chapter_plan",
            prompt=self.prompt,
            payload={
                "story_title": state.get("story_title", ""),
                "genre": state.get("genre", ""),
                "target_audience": state.get("target_audience", ""),
                "chapter_no": state["chapter_no"],
                "target_length": state.get("target_length", 1200),
                **state["context_packet"],
            },
            purpose="chapter_plan",
        )
        plan = generation.value
        plan.validate_known_characters(
            {str(item["name"]) for item in state["characters"]}
        )
        plan.validate_outline_coverage(
            list(state["context_packet"]["currentOutlineNodes"])
        )
        plan_data = plan.model_dump(mode="json", by_alias=True)
        return {
            "chapter_plan": plan_data,
            "target_length": plan.target_length,
            "status": "PLAN_READY",
            "current_node": "plan_chapter",
            "progress_events": [chapter_progress("plan_chapter", "章节场景计划已生成")],
            "artifacts": [
                chapter_artifact(
                    "CHAPTER_PLAN",
                    version_no=1,
                    status="REVIEW_REQUIRED",
                    content=plan_data,
                    prompt_name="chapter_plan",
                    model_name=generation.model_name,
                )
            ],
            "model_calls": [call],
        }
