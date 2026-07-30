"""Character-generation workflow agent."""

from __future__ import annotations

from typing import Any

from app.agents.workflow_utils import artifact, invoke_structured, progress
from app.infrastructure.llm_factory import StructuredModel, get_creative_model
from app.prompts import load_prompt
from app.schemas.character import CharacterPack


class CharacterAgent:
    def __init__(self, model: StructuredModel | None = None) -> None:
        self.model = model or get_creative_model()
        self.prompt = load_prompt("character")

    async def __call__(self, state: dict[str, Any]) -> dict[str, Any]:
        generation, call = await invoke_structured(
            self.model,
            CharacterPack,
            node="generate_characters",
            prompt_name="character",
            prompt=self.prompt,
            payload={"topic": state["topic"]},
            purpose="character",
        )
        pack = generation.value
        characters = [
            character.model_dump(mode="json") for character in pack.characters
        ]
        return {
            "characters": characters,
            "current_node": "generate_characters",
            "progress_events": [
                progress("generate_characters", "人物设定已生成", revision_no=1)
            ],
            "artifacts": [
                artifact(
                    artifact_type="CHARACTER",
                    version_no=1,
                    status="DRAFT",
                    content={"characters": characters},
                    prompt_name="character",
                    model_name=generation.model_name,
                )
            ],
            "model_calls": [call],
        }


async def generate_characters(state: dict[str, Any]) -> dict[str, Any]:
    """Spec-friendly default entrypoint."""

    return await CharacterAgent()(state)
