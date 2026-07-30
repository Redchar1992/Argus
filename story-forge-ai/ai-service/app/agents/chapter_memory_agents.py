"""Post-approval chapter summary and long-term memory extraction agents."""

from __future__ import annotations

from typing import Any

from app.agents.chapter_utils import chapter_artifact, chapter_progress
from app.agents.workflow_utils import invoke_structured
from app.infrastructure.llm_factory import StructuredModel, get_review_model
from app.prompts import load_prompt
from app.schemas.chapter import ChapterSummary, MemoryUpdate


class ChapterSummaryAgent:
    def __init__(self, model: StructuredModel | None = None) -> None:
        self.model = model or get_review_model()
        self.prompt = load_prompt("chapter_summary")

    async def __call__(self, state: dict[str, Any]) -> dict[str, Any]:
        generation, call = await invoke_structured(
            self.model,
            ChapterSummary,
            node="summarize_chapter",
            prompt_name="chapter_summary",
            prompt=self.prompt,
            payload={
                "chapter_no": state["chapter_no"],
                "chapter_plan": state["chapter_plan"],
                "approved_content": state["final_content"],
                **state["context_packet"],
            },
            purpose="chapter_summary",
        )
        summary = generation.value.model_dump(mode="json", by_alias=True)
        return {
            "chapter_summary": summary,
            "current_node": "summarize_chapter",
            "progress_events": [
                chapter_progress("summarize_chapter", "批准章节摘要已生成")
            ],
            "artifacts": [
                chapter_artifact(
                    "CHAPTER_SUMMARY",
                    version_no=int(state.get("revision_count", 0)) + 1,
                    status="APPROVED",
                    content=summary,
                    prompt_name="chapter_summary",
                    model_name=generation.model_name,
                )
            ],
            "model_calls": [call],
        }


class MemoryUpdateAgent:
    def __init__(self, model: StructuredModel | None = None) -> None:
        self.model = model or get_review_model()
        self.prompt = load_prompt("chapter_memory")

    async def __call__(self, state: dict[str, Any]) -> dict[str, Any]:
        generation, call = await invoke_structured(
            self.model,
            MemoryUpdate,
            node="update_memory",
            prompt_name="chapter_memory",
            prompt=self.prompt,
            payload={
                "chapter_no": state["chapter_no"],
                "approved_content": state["final_content"],
                "chapter_summary": state["chapter_summary"],
                **state["context_packet"],
            },
            purpose="chapter_memory",
        )
        update = generation.value.model_dump(mode="json", by_alias=True)
        locked_keys = {
            str(item.get("factKey") or item.get("fact_key"))
            for item in state.get("canon_facts", [])
            if item.get("locked")
        }
        proposed_facts = update["newFacts"]
        conflicting_keys = sorted(
            {
                str(fact.get("factKey") or fact.get("fact_key"))
                for fact in proposed_facts
                if str(fact.get("factKey") or fact.get("fact_key")) in locked_keys
            }
        )
        update["newFacts"] = [
            fact
            for fact in proposed_facts
            if str(fact.get("factKey") or fact.get("fact_key")) not in locked_keys
        ]
        update["continuityWarnings"] = list(
            dict.fromkeys(
                [
                    *update["continuityWarnings"],
                    *[
                        f"Memory Update尝试覆盖锁定事实，已拒绝：{key}"
                        for key in conflicting_keys
                    ],
                ]
            )
        )
        return {
            "memory_update": update,
            "current_node": "update_memory",
            "progress_events": [
                chapter_progress("update_memory", "故事长期记忆增量已提取")
            ],
            "artifacts": [
                chapter_artifact(
                    "MEMORY_UPDATE",
                    version_no=int(state.get("revision_count", 0)) + 1,
                    status="APPROVED",
                    content=update,
                    prompt_name="chapter_memory",
                    model_name=generation.model_name,
                )
            ],
            "model_calls": [call],
        }
