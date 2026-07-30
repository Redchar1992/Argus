"""Initial twenty-node outline agent."""

from __future__ import annotations

from typing import Any

from app.agents.workflow_utils import artifact, invoke_structured, progress
from app.infrastructure.llm_factory import StructuredModel, get_creative_model
from app.prompts import load_prompt
from app.schemas.outline import OutlineResult, validate_outline


class OutlineAgent:
    def __init__(self, model: StructuredModel | None = None) -> None:
        self.model = model or get_creative_model()
        self.prompt = load_prompt("outline")

    async def __call__(self, state: dict[str, Any]) -> dict[str, Any]:
        generation, call = await invoke_structured(
            self.model,
            OutlineResult,
            node="generate_outline",
            prompt_name="outline",
            prompt=self.prompt,
            payload={
                "topic": state["topic"],
                "characters": state["characters"],
            },
            purpose="outline",
        )
        result = generation.value
        validate_outline(result.nodes)
        nodes = [node.model_dump(mode="json") for node in result.nodes]
        metadata = {
            "title": result.title,
            "core_conflict": result.core_conflict,
            "ending_type": result.ending_type,
        }
        return {
            "outline": nodes,
            "outline_metadata": metadata,
            "current_node": "generate_outline",
            "progress_events": [
                progress("generate_outline", "20节点大纲已生成", revision_no=1)
            ],
            "artifacts": [
                artifact(
                    artifact_type="OUTLINE",
                    version_no=1,
                    status="DRAFT",
                    content={**metadata, "nodes": nodes},
                    prompt_name="outline",
                    model_name=generation.model_name,
                )
            ],
            "model_calls": [call],
        }


async def generate_outline(state: dict[str, Any]) -> dict[str, Any]:
    return await OutlineAgent()(state)
