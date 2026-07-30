from __future__ import annotations

from typing import Any

import pytest

from app.agents.character_agent import CharacterAgent
from app.agents.outline_agent import OutlineAgent
from app.agents.revise_agent import ReviseAgent
from app.agents.score_agent import CommercialScoreAgent
from app.infrastructure.llm_factory import (
    LocalStructuredModel,
    StructuredGeneration,
)
from app.schemas.score import SCORE_DIMENSIONS, StoryScore
from app.schemas.workflow import (
    ReviewDecision,
    WorkflowStartRequest,
    WorkflowStatus,
)
from app.workflow.service import (
    StoryWorkflowConflict,
    StoryWorkflowNotFound,
    StoryWorkflowService,
)
from app.workflow.story_graph import build_story_graph
from tests.workflow_samples import week_one_topic

FIXED_TOPIC_TITLES = [
    "都市婚姻复仇",
    "真假千金家庭冲突",
    "职场女性逆袭",
    "民俗悬疑老宅",
    "时间循环救援",
    "校园秘密调查",
    "亲情误解与真相",
    "遗产争夺",
    "医疗悬疑",
    "轻科幻身份替换",
]


def start_request(
    *,
    task_id: str = "task-10001",
    max_revisions: int = 2,
) -> WorkflowStartRequest:
    return WorkflowStartRequest(
        task_id=task_id,
        story_id=5001,
        topic=week_one_topic(),
        max_revisions=max_revisions,
    )


@pytest.mark.asyncio
async def test_default_graph_revises_low_score_and_pauses_for_review() -> None:
    service = StoryWorkflowService()

    response = await service.start(start_request())

    assert response.status is WorkflowStatus.REVIEW_REQUIRED
    assert response.current_node == "human_review"
    assert response.interrupt and response.interrupt["type"] == "outline_review"
    assert len(response.characters) == 4
    assert len(response.outline) == 20
    assert response.score and response.score.total == 84
    assert response.revision_count == 1

    outline_versions = [
        artifact.version_no
        for artifact in response.artifacts
        if artifact.artifact_type == "OUTLINE"
    ]
    score_versions = [
        artifact.version_no
        for artifact in response.artifacts
        if artifact.artifact_type == "SCORE"
    ]
    assert outline_versions == [1, 2]
    assert score_versions == [1, 2]
    assert len(response.model_calls) == 5


@pytest.mark.asyncio
@pytest.mark.parametrize("title", FIXED_TOPIC_TITLES)
async def test_fixed_topic_regression_set_is_structurally_valid(
    title: str,
) -> None:
    service = StoryWorkflowService()
    response = await service.start(
        WorkflowStartRequest(
            task_id=f"fixed-{FIXED_TOPIC_TITLES.index(title)}",
            story_id=5001,
            topic={
                "title": title,
                "hook": "主角开场遭遇强冲突，随后发现足以改变关系的身份真相。",
            },
            # This regression set targets the three-call base path.
            max_revisions=0,
        )
    )

    assert response.status is WorkflowStatus.REVIEW_REQUIRED
    assert 3 <= len(response.characters) <= 6
    assert len(response.outline) == 20
    assert [node.node_no for node in response.outline] == list(range(1, 21))
    assert sum(node.is_twist for node in response.outline) >= 4
    assert response.model_calls[-1].node == "score_outline"


@pytest.mark.asyncio
async def test_approve_resumes_same_thread_and_creates_final_artifact() -> None:
    service = StoryWorkflowService()
    paused = await service.start(start_request())

    completed = await service.resume(
        paused.thread_id,
        ReviewDecision(approved=True, notes=""),
    )

    assert completed.thread_id == paused.thread_id
    assert completed.status is WorkflowStatus.COMPLETED
    assert completed.current_node == "finish"
    assert completed.approved is True
    assert completed.artifacts[-1].artifact_type == "WORKFLOW_FINAL"
    assert completed.artifacts[-1].status == "APPROVED"

    with pytest.raises(StoryWorkflowConflict, match="不能再次恢复"):
        await service.resume(
            paused.thread_id,
            ReviewDecision(approved=True),
        )


@pytest.mark.asyncio
async def test_rejection_revises_full_outline_then_pauses_again() -> None:
    service = StoryWorkflowService()
    first_review = await service.start(start_request())

    second_review = await service.resume(
        first_review.thread_id,
        ReviewDecision(
            approved=False,
            notes="节点12缺少前置动机，请提前铺垫妹妹与反派的利益关系。",
        ),
    )

    assert second_review.status is WorkflowStatus.REVIEW_REQUIRED
    assert second_review.thread_id == first_review.thread_id
    assert second_review.revision_count == 2
    assert second_review.score and second_review.score.total == 90
    assert [
        artifact.version_no
        for artifact in second_review.artifacts
        if artifact.artifact_type == "OUTLINE"
    ] == [1, 2, 3]
    assert "修订2" in next(
        artifact.content["title"]
        for artifact in reversed(second_review.artifacts)
        if artifact.artifact_type == "OUTLINE"
    )

    completed = await service.resume(
        second_review.thread_id,
        ReviewDecision(approved=True),
    )
    assert completed.status is WorkflowStatus.COMPLETED


class AlwaysLowScoreModel(LocalStructuredModel):
    async def generate(
        self,
        schema: type[Any],
        *,
        system_prompt: str,
        payload: dict[str, Any],
        purpose: str,
    ) -> StructuredGeneration[Any]:
        generation = await super().generate(
            schema,
            system_prompt=system_prompt,
            payload=payload,
            purpose=purpose,
        )
        if schema is not StoryScore:
            return generation

        raw = generation.value.model_dump()
        for dimension in SCORE_DIMENSIONS:
            raw[dimension]["score"] = 10
        return StructuredGeneration(
            value=StoryScore.model_validate(raw),
            model_name=generation.model_name,
            input_tokens=generation.input_tokens,
            output_tokens=generation.output_tokens,
        )


@pytest.mark.asyncio
async def test_low_score_automatically_revises_at_most_twice() -> None:
    creative = LocalStructuredModel()
    graph = build_story_graph(
        character_agent=CharacterAgent(creative),
        outline_agent=OutlineAgent(creative),
        revise_agent=ReviseAgent(creative),
        score_agent=CommercialScoreAgent(AlwaysLowScoreModel()),
    )
    service = StoryWorkflowService(graph)

    response = await service.start(start_request(task_id="low", max_revisions=2))

    assert response.status is WorkflowStatus.REVIEW_REQUIRED
    assert response.revision_count == 2
    assert response.score and response.score.total == 50
    assert [
        artifact.version_no
        for artifact in response.artifacts
        if artifact.artifact_type == "OUTLINE"
    ] == [1, 2, 3]
    # character + outline + 3 scores + 2 revisions
    assert len(response.model_calls) == 7


@pytest.mark.asyncio
async def test_unknown_thread_is_not_silently_created() -> None:
    service = StoryWorkflowService()

    with pytest.raises(StoryWorkflowNotFound):
        await service.get("missing-thread")
    with pytest.raises(StoryWorkflowNotFound):
        await service.resume(
            "missing-thread",
            ReviewDecision(approved=True),
        )


@pytest.mark.asyncio
async def test_applied_resume_can_continue_without_replaying_human_decision() -> None:
    service = StoryWorkflowService()
    paused = await service.start(
        start_request(),
        operation_key="5001:v1:START:0",
    )

    async def fail_after_revision(
        node: str,
        _update: dict[str, Any],
    ) -> None:
        if node == "revise_outline":
            raise RuntimeError("event transport unavailable")

    operation_key = "5001:v1:RESUME:1"
    with pytest.raises(RuntimeError, match="event transport unavailable"):
        await service.resume(
            paused.thread_id,
            ReviewDecision(
                approved=False,
                notes="提前铺垫妹妹与反派的利益关系。",
            ),
            on_update=fail_after_revision,
            operation_key=operation_key,
        )

    partial = await service.get(paused.thread_id)
    assert partial.status is WorkflowStatus.RUNNING
    assert await service.operation_applied(paused.thread_id, operation_key)

    recovered = await service.continue_thread(paused.thread_id)
    assert recovered.status is WorkflowStatus.REVIEW_REQUIRED
    assert recovered.revision_count == 2
    assert [
        artifact.version_no
        for artifact in recovered.artifacts
        if artifact.artifact_type == "OUTLINE"
    ] == [1, 2, 3]
