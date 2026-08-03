"""Whole-book continuity and commercial review agent."""

from __future__ import annotations

from typing import Any

from app.agents.workflow_utils import invoke_structured
from app.infrastructure.llm_factory import StructuredModel, get_review_model
from app.prompts import load_profile_prompt
from app.schemas.final_review import FinalReviewRequest, FinalStoryReport


class FinalReviewAgent:
    def __init__(self, model: StructuredModel | None = None) -> None:
        self.model = model or get_review_model()
        self.prompt = None

    async def review(self, request: FinalReviewRequest) -> FinalStoryReport:
        prompt, prompt_name = load_profile_prompt("final_review", request.content_mode)
        generation, _call = await invoke_structured(
            self.model,
            FinalStoryReport,
            node="final_review",
            prompt_name=prompt_name,
            prompt=request.prompt_system or prompt,
            payload=request.model_dump(mode="json", by_alias=True),
            purpose="final_review",
        )
        return generation.value

    async def __call__(self, payload: dict[str, Any]) -> FinalStoryReport:
        return await self.review(FinalReviewRequest.model_validate(payload))
