"""LangGraph assembly for the second-week story workflow."""

from __future__ import annotations

from typing import Any

from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, StateGraph

from app.agents.character_agent import CharacterAgent
from app.agents.human_review_agent import human_review
from app.agents.outline_agent import OutlineAgent
from app.agents.revise_agent import ReviseAgent
from app.agents.score_agent import CommercialScoreAgent
from app.workflow.nodes import (
    finish_workflow,
    prepare_human_review,
    route_after_human_review,
    route_after_score,
)
from app.workflow.state import StoryState


def build_story_graph(
    *,
    character_agent: CharacterAgent | None = None,
    outline_agent: OutlineAgent | None = None,
    score_agent: CommercialScoreAgent | None = None,
    revise_agent: ReviseAgent | None = None,
    checkpointer: Any | None = None,
):
    """Build a dependency-injectable graph suitable for production adapters."""

    builder = StateGraph(StoryState)
    builder.add_node(
        "generate_characters", character_agent or CharacterAgent()
    )
    builder.add_node("generate_outline", outline_agent or OutlineAgent())
    builder.add_node("score_outline", score_agent or CommercialScoreAgent())
    builder.add_node("revise_outline", revise_agent or ReviseAgent())
    builder.add_node("prepare_human_review", prepare_human_review)
    builder.add_node("human_review", human_review)
    builder.add_node("finish", finish_workflow)

    builder.add_edge(START, "generate_characters")
    builder.add_edge("generate_characters", "generate_outline")
    builder.add_edge("generate_outline", "score_outline")
    builder.add_conditional_edges(
        "score_outline",
        route_after_score,
        {
            "revise_outline": "revise_outline",
            "prepare_human_review": "prepare_human_review",
        },
    )
    builder.add_edge("revise_outline", "score_outline")
    builder.add_edge("prepare_human_review", "human_review")
    builder.add_conditional_edges(
        "human_review",
        route_after_human_review,
        {
            "finish": "finish",
            "revise_outline": "revise_outline",
        },
    )
    builder.add_edge("finish", END)

    return builder.compile(checkpointer=checkpointer or InMemorySaver())
