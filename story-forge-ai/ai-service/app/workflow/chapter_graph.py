"""Reusable LangGraph subgraph for one chapter at a time."""

from __future__ import annotations

from typing import Any, Literal

from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import RetryPolicy

from app.agents.chapter_context import ChapterContextAssembler
from app.agents.chapter_human_review import chapter_human_review
from app.agents.chapter_memory_agents import ChapterSummaryAgent, MemoryUpdateAgent
from app.agents.chapter_plan_agent import ChapterPlanAgent
from app.agents.chapter_reviewer_agent import (
    ChapterReviewerAgent,
    validate_chapter_node,
)
from app.agents.chapter_revision_agent import ChapterRevisionAgent
from app.agents.chapter_utils import chapter_artifact, chapter_progress
from app.agents.chapter_writer_agent import ChapterWriterAgent
from app.workflow.chapter_state import ChapterState


def route_after_context(
    state: ChapterState,
) -> Literal["plan_chapter", "write_chapter"]:
    return "plan_chapter" if state.get("mode") == "PLAN" else "write_chapter"


def route_after_review(
    state: ChapterState,
) -> Literal["revise_chapter", "prepare_human_review"]:
    review = state["chapter_review"]
    score = int(review.get("totalScore", review.get("total_score", 0)))
    fatal = list(review.get("fatalProblems", review.get("fatal_problems", [])))
    errors = list(state.get("mechanical_errors", []))
    revision_count = int(state.get("revision_count", 0))
    max_revisions = int(state.get("max_revisions", 2))
    if (score < 82 or fatal or errors) and revision_count < max_revisions:
        return "revise_chapter"
    return "prepare_human_review"


def prepare_human_review(state: ChapterState) -> dict[str, Any]:
    return {
        "status": "REVIEW_REQUIRED",
        "current_node": "human_review",
        "progress_events": [
            chapter_progress(
                "human_review",
                "章节等待用户审核",
                status="waiting",
                revision_no=int(state.get("revision_count", 0)) + 1,
            )
        ],
    }


def route_after_human_review(
    state: ChapterState,
) -> Literal["summarize_chapter", "revise_chapter"]:
    return "summarize_chapter" if state.get("approved") else "revise_chapter"


def persist_chapter_result(state: ChapterState) -> dict[str, Any]:
    """Prepare the persistence payload; Spring/MySQL remains the source of truth."""

    version_no = int(state.get("revision_count", 0)) + 1
    final = {
        "content": state["final_content"],
        "review": state["chapter_review"],
        "summary": state["chapter_summary"],
        "memoryUpdate": state["memory_update"],
        "sourceType": "APPROVED",
    }
    return {
        "status": "COMPLETED",
        "approved": True,
        "current_node": "persist_chapter",
        "progress_events": [
            chapter_progress(
                "persist_chapter",
                "章节与记忆结果等待业务服务事务持久化",
                revision_no=version_no,
            )
        ],
        "artifacts": [
            chapter_artifact(
                "CHAPTER_FINAL",
                version_no=version_no,
                status="APPROVED",
                content=final,
                prompt_name="chapter_workflow",
                model_name="application",
            )
        ],
    }


def build_chapter_graph(
    *,
    context_assembler: ChapterContextAssembler | None = None,
    plan_agent: ChapterPlanAgent | None = None,
    writer_agent: ChapterWriterAgent | None = None,
    reviewer_agent: ChapterReviewerAgent | None = None,
    revision_agent: ChapterRevisionAgent | None = None,
    summary_agent: ChapterSummaryAgent | None = None,
    memory_agent: MemoryUpdateAgent | None = None,
    checkpointer: Any | None = None,
):
    """Compile a dependency-injectable PLAN/GENERATE/FINALIZE subgraph."""

    retry = RetryPolicy(max_attempts=2, initial_interval=0.2, jitter=False)
    builder = StateGraph(ChapterState)
    builder.add_node("load_context", context_assembler or ChapterContextAssembler())
    builder.add_node(
        "plan_chapter",
        plan_agent or ChapterPlanAgent(),
        retry_policy=retry,
    )
    builder.add_node(
        "write_chapter",
        writer_agent or ChapterWriterAgent(),
        retry_policy=retry,
    )
    builder.add_node("validate_chapter", validate_chapter_node)
    builder.add_node(
        "review_chapter",
        reviewer_agent or ChapterReviewerAgent(),
        retry_policy=retry,
    )
    builder.add_node(
        "revise_chapter",
        revision_agent or ChapterRevisionAgent(),
        retry_policy=retry,
    )
    builder.add_node("prepare_human_review", prepare_human_review)
    builder.add_node("human_review", chapter_human_review)
    builder.add_node(
        "summarize_chapter",
        summary_agent or ChapterSummaryAgent(),
        retry_policy=retry,
    )
    builder.add_node(
        "update_memory",
        memory_agent or MemoryUpdateAgent(),
        retry_policy=retry,
    )
    builder.add_node("persist_chapter", persist_chapter_result)

    builder.add_edge(START, "load_context")
    builder.add_conditional_edges(
        "load_context",
        route_after_context,
        {
            "plan_chapter": "plan_chapter",
            "write_chapter": "write_chapter",
        },
    )
    builder.add_edge("plan_chapter", END)
    builder.add_edge("write_chapter", "validate_chapter")
    builder.add_edge("validate_chapter", "review_chapter")
    builder.add_conditional_edges(
        "review_chapter",
        route_after_review,
        {
            "revise_chapter": "revise_chapter",
            "prepare_human_review": "prepare_human_review",
        },
    )
    builder.add_edge("revise_chapter", "validate_chapter")
    builder.add_edge("prepare_human_review", "human_review")
    builder.add_conditional_edges(
        "human_review",
        route_after_human_review,
        {
            "summarize_chapter": "summarize_chapter",
            "revise_chapter": "revise_chapter",
        },
    )
    builder.add_edge("summarize_chapter", "update_memory")
    builder.add_edge("update_memory", "persist_chapter")
    builder.add_edge("persist_chapter", END)
    return builder.compile(checkpointer=checkpointer or InMemorySaver())
