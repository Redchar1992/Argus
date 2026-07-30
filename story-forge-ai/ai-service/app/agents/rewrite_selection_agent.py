"""Version-safe selected-text rewrite proposal agent."""

from __future__ import annotations

import hashlib
from typing import Any

from app.agents.workflow_utils import invoke_structured
from app.infrastructure.llm_factory import StructuredModel, get_creative_model
from app.prompts import load_prompt
from app.schemas.chapter import RewriteProposal


class RewriteSelectionAgent:
    def __init__(self, model: StructuredModel | None = None) -> None:
        self.model = model or get_creative_model()
        self.prompt = load_prompt("rewrite_selection")

    async def rewrite(
        self, payload: dict[str, Any]
    ) -> tuple[RewriteProposal, dict[str, Any]]:
        selected = str(payload["selectedText"])
        selected_hash = str(payload["selectedTextHash"])
        if hashlib.sha256(selected.encode()).hexdigest() != selected_hash:
            raise ValueError("selectedTextHash与选中文本不一致")
        generation, call = await invoke_structured(
            self.model,
            RewriteProposal,
            node="rewrite_selection",
            prompt_name="rewrite_selection",
            prompt=self.prompt,
            payload={
                "chapter_version_id": payload["chapterVersionId"],
                "selected_text": selected,
                "selected_text_hash": selected_hash,
                "action": payload["action"],
                "custom_instruction": payload.get("customInstruction", ""),
                "context": payload.get("context", {}),
            },
            purpose="rewrite_selection",
        )
        proposal = generation.value
        # Model output is never allowed to move the optimistic-lock anchors.
        if proposal.chapter_version_id != int(payload["chapterVersionId"]):
            raise ValueError("模型返回了错误的chapterVersionId")
        if proposal.original_text != selected:
            raise ValueError("模型不得修改originalText")
        if proposal.selected_text_hash != selected_hash:
            raise ValueError("模型不得修改selectedTextHash")
        return proposal, call
