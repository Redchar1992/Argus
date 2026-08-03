from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from app.agents.chapter_context import ChapterContextAssembler
from app.agents.chapter_memory_agents import MemoryUpdateAgent
from app.agents.chapter_reviewer_agent import ChapterReviewerAgent
from app.infrastructure.llm_factory import StructuredGeneration
from app.schemas.chapter import (
    ChapterCommand,
    ChapterReview,
    ChapterRunStatus,
    MemoryUpdate,
)
from app.workflow.chapter_graph import build_chapter_graph
from app.workflow.chapter_service import (
    ChapterWorkflowService,
    persistent_chapter_service,
)
from tests.chapter_samples import chapter_context
from tests.workflow_samples import outline_nodes


class ConflictingMemoryModel:
    model_name = "conflicting-test-model"

    async def generate(
        self,
        _schema: Any,
        **_kwargs: Any,
    ) -> StructuredGeneration[MemoryUpdate]:
        return StructuredGeneration(
            value=MemoryUpdate(
                newFacts=[
                    {
                        "factKey": "identity_lin_wan",
                        "value": "错误覆盖值",
                    },
                    {"factKey": "new_safe_fact", "value": "可新增事实"},
                ],
                continuityWarnings=["模型原有警告"],
            ),
            model_name=self.model_name,
        )


class AlwaysLowReviewModel:
    model_name = "always-low-review-model"

    async def generate(
        self,
        _schema: Any,
        **_kwargs: Any,
    ) -> StructuredGeneration[ChapterReview]:
        def dimension(maximum: int) -> dict[str, Any]:
            return {
                "score": 1,
                "maxScore": maximum,
                "evidence": ["测试低分证据"],
                "problems": ["需要重写"],
                "suggestions": ["补足具体行动"],
            }

        return StructuredGeneration(
            value=ChapterReview.model_validate(
                {
                    "outlineCompletion": dimension(20),
                    "continuity": dimension(20),
                    "conflictProgression": dimension(20),
                    "emotionAndVisuals": dimension(15),
                    "hooks": dimension(15),
                    "languageQuality": dimension(10),
                    "fatalProblems": [],
                    "rewriteInstructions": ["继续修改"],
                    "shouldRewrite": True,
                }
            ),
            model_name=self.model_name,
        )


def command(
    action: str,
    *,
    task: str,
    thread: str,
    payload: dict[str, object],
) -> ChapterCommand:
    return ChapterCommand(
        taskId=task,
        storyId=5001,
        chapterId=7001,
        chapterNo=1,
        action=action,
        threadId=thread,
        idempotencyKey=task,
        payload=payload,
    )


@pytest.mark.asyncio
async def test_plan_generate_review_revision_and_memory_closed_loop() -> None:
    service = ChapterWorkflowService(build_chapter_graph())
    context = chapter_context()
    planned = await service.start(
        command("PLAN", task="plan-1", thread="plan-thread", payload=context)
    )
    assert planned.status is ChapterRunStatus.PLAN_READY
    assert planned.chapter_plan is not None
    assert 3 <= len(planned.chapter_plan.scenes) <= 6
    assert {
        name for scene in planned.chapter_plan.scenes for name in scene.characters
    } <= {"林晚", "顾承泽", "苏晴"}

    deltas: list[str] = []

    async def stream(event_type: str, data: dict[str, object]) -> None:
        if event_type == "TOKEN_DELTA":
            deltas.append(str(data["text"]))

    generated = await service.start(
        command(
            "GENERATE",
            task="generate-1",
            thread="generate-thread",
            payload={
                **context,
                "chapterPlan": planned.chapter_plan.model_dump(
                    mode="json", by_alias=True
                ),
            },
        ),
        on_stream=stream,
    )
    assert generated.status is ChapterRunStatus.REVIEW_REQUIRED
    assert generated.revision_count == 1
    assert generated.chapter_review is not None
    assert generated.chapter_review.total_score >= 82
    assert generated.mechanical_errors == []
    assert "".join(deltas).endswith(generated.draft_content)
    content_versions = [
        artifact
        for artifact in generated.artifacts
        if artifact["artifactType"] == "CHAPTER_CONTENT"
    ]
    assert [item["versionNo"] for item in content_versions] == [1, 2]

    revised = await service.finalize(
        command(
            "FINALIZE",
            task="human-revision-1",
            thread="generate-thread",
            payload={"approved": False, "notes": "加强结尾的行动悬念"},
        )
    )
    assert revised.status is ChapterRunStatus.REVIEW_REQUIRED
    assert revised.revision_count == 2

    completed = await service.finalize(
        command(
            "FINALIZE",
            task="approve-1",
            thread="generate-thread",
            payload={
                "approved": True,
                "notes": "",
                "currentContent": revised.draft_content + "\n\n用户确认了这一版。",
                "baseVersionId": 42,
                # The backend includes the approved plan in the FINALIZE
                # command envelope; it is context, not a ChapterDecision field.
                "chapterPlan": planned.chapter_plan.model_dump(
                    mode="json", by_alias=True
                ),
            },
        )
    )
    assert completed.status is ChapterRunStatus.COMPLETED
    assert completed.approved is True
    assert completed.final_content.endswith("用户确认了这一版。")
    assert completed.chapter_summary is not None
    assert completed.memory_update is not None
    assert completed.memory_update.new_facts
    assert any(
        "锁定事实保持不变" in warning
        for warning in completed.memory_update.continuity_warnings
    )
    assert completed.artifacts[-1]["artifactType"] == "CHAPTER_FINAL"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("content_mode", "target_chapter_count", "message"),
    [
        ("SHORT_STORY", 2, "短故事目标章节数"),
        ("SHORT_STORY", 11, "短故事目标章节数"),
        ("NOVEL", 19, "小说目标章节数"),
    ],
)
async def test_chapter_service_enforces_content_profile_limits(
    content_mode: str,
    target_chapter_count: int,
    message: str,
) -> None:
    context = chapter_context()
    context.update(
        {
            "contentMode": content_mode,
            "targetChapterCount": target_chapter_count,
        }
    )
    with pytest.raises(ValueError, match=message):
        await ChapterWorkflowService(build_chapter_graph()).start(
            command(
                "PLAN",
                task=f"profile-limit-{content_mode}",
                thread=f"profile-limit-{content_mode}",
                payload=context,
            )
        )


def test_context_assembler_keeps_only_latest_three_summaries() -> None:
    context = chapter_context()
    state = {
        "characters": context["characters"],
        "style_profile": context["styleProfile"],
        "canon_facts": context["canonFacts"],
        "relationship_states": context["relationshipStates"],
        "recent_summaries": context["recentSummaries"],
        "outline_nodes": context["outlineNodes"],
        "unresolved_threads": context["unresolvedThreads"],
        "foreshadowing_ledger": context["foreshadowingLedger"],
    }
    assembled = ChapterContextAssembler()(state)
    assert [
        summary["chapterNo"]
        for summary in assembled["context_packet"]["recentSummaries"]
    ] == [3, 4, 5]
    assert assembled["context_packet"]["currentOutlineNodes"] == context["outlineNodes"]
    assert assembled["context_packet"]["outlineNodes"] == context["outlineNodes"]


def test_context_assembler_bounds_large_novel_memory_and_adds_snapshot_hash() -> None:
    context = chapter_context()
    context["contentMode"] = "NOVEL"
    context["canonFacts"] = [
        {"factKey": f"fact-{index}", "value": "事实" * 500}
        for index in range(100)
    ]
    context["unresolvedThreads"] = [
        {"threadKey": f"thread-{index}", "description": "伏笔" * 500}
        for index in range(100)
    ]

    state = {
        "content_mode": "NOVEL",
        "characters": context["characters"],
        "style_profile": context["styleProfile"],
        "canon_facts": context["canonFacts"],
        "relationship_states": context["relationshipStates"],
        "recent_summaries": context["recentSummaries"],
        "outline_nodes": context["outlineNodes"],
        "unresolved_threads": context["unresolvedThreads"],
        "foreshadowing_ledger": context["foreshadowingLedger"],
    }

    packet = ChapterContextAssembler()(state)["context_packet"]
    assert len(json.dumps(packet, ensure_ascii=False, separators=(",", ":"))) <= 40_000
    assert packet["contentMode"] == "NOVEL"
    assert packet["contextSnapshotHash"]
    assert packet["contextOmitted"]


def test_context_preserves_injury_secret_death_and_item_ownership_facts() -> None:
    context = chapter_context()
    context["canonFacts"].extend(
        [
            {
                "factKey": "arm_injury",
                "factType": "CHARACTER_STATE",
                "subject": "林晚",
                "predicate": "手臂",
                "value": "受伤",
                "visibility": "PUBLIC",
                "locked": False,
            },
            {
                "factKey": "father_dead",
                "factType": "CHARACTER_STATE",
                "subject": "林父",
                "predicate": "生存状态",
                "value": "已死亡",
                "visibility": "PUBLIC",
                "locked": True,
            },
            {
                "factKey": "evidence_owner",
                "factType": "ITEM_STATE",
                "subject": "证据文件",
                "predicate": "当前持有人",
                "value": "顾承泽",
                "visibility": "PUBLIC",
                "locked": False,
            },
        ]
    )
    state = {
        "characters": context["characters"],
        "style_profile": context["styleProfile"],
        "canon_facts": context["canonFacts"],
        "relationship_states": [],
        "recent_summaries": [],
        "outline_nodes": context["outlineNodes"],
        "unresolved_threads": [],
        "foreshadowing_ledger": [],
    }

    packet = ChapterContextAssembler()(state)["context_packet"]
    facts = {fact["factKey"]: fact for fact in packet["canonFacts"]}
    assert facts["arm_injury"]["value"] == "受伤"
    assert facts["identity_lin_wan"]["visibility"] == "READER_ONLY"
    assert facts["father_dead"]["locked"] is True
    assert facts["evidence_owner"]["value"] == "顾承泽"


@pytest.mark.asyncio
async def test_chapter_plans_map_two_distinct_outline_nodes_per_chapter() -> None:
    context = chapter_context()
    nodes = outline_nodes()[:4]
    context["outlineNodes"] = nodes
    service = ChapterWorkflowService(build_chapter_graph())

    async def plan(chapter_no: int) -> Any:
        return await service.start(
            ChapterCommand(
                taskId=f"mapped-plan-{chapter_no}",
                storyId=5001,
                chapterId=7000 + chapter_no,
                chapterNo=chapter_no,
                action="PLAN",
                threadId=f"mapped-plan-thread-{chapter_no}",
                idempotencyKey=f"mapped-plan-{chapter_no}",
                payload=context,
            )
        )

    first = await plan(1)
    second = await plan(2)
    assert first.chapter_plan is not None
    assert second.chapter_plan is not None
    assert first.chapter_plan.chapter_goal != second.chapter_plan.chapter_goal

    for result, expected in ((first, nodes[:2]), (second, nodes[2:4])):
        assert result.chapter_plan is not None
        scene_contract = "".join(
            scene.protagonist_goal + scene.visible_conflict
            for scene in result.chapter_plan.scenes
        )
        for node in expected:
            event = str(node["event"])
            goal = str(node["protagonist_goal"])
            assert event in result.chapter_plan.chapter_goal
            assert goal in result.chapter_plan.chapter_goal
            assert event in scene_contract
            assert goal in scene_contract

    assert nodes[2]["event"] not in first.chapter_plan.chapter_goal
    assert nodes[0]["event"] not in second.chapter_plan.chapter_goal


@pytest.mark.asyncio
async def test_missing_chapter_outline_pair_fails_before_model_call() -> None:
    context = chapter_context()
    context["outlineNodes"] = outline_nodes()[:4]
    service = ChapterWorkflowService(build_chapter_graph())
    with pytest.raises(ValueError, match="第3章必须且只能包含两个当前大纲节点"):
        await service.start(
            ChapterCommand(
                taskId="missing-plan-3",
                storyId=5001,
                chapterId=7003,
                chapterNo=3,
                action="PLAN",
                threadId="missing-plan-thread-3",
                idempotencyKey="missing-plan-3",
                payload=context,
            )
        )


@pytest.mark.asyncio
async def test_automatic_revision_stops_after_two_attempts() -> None:
    service = ChapterWorkflowService(
        build_chapter_graph(reviewer_agent=ChapterReviewerAgent(AlwaysLowReviewModel()))
    )
    context = chapter_context()
    planned = await ChapterWorkflowService(build_chapter_graph()).start(
        command(
            "PLAN",
            task="low-plan",
            thread="low-plan-thread",
            payload=context,
        )
    )
    paused = await service.start(
        command(
            "GENERATE",
            task="always-low",
            thread="always-low-thread",
            payload={
                **context,
                "chapterPlan": planned.chapter_plan.model_dump(
                    mode="json", by_alias=True
                ),
            },
        )
    )
    assert paused.status is ChapterRunStatus.REVIEW_REQUIRED
    assert paused.revision_count == 2
    assert paused.chapter_review.total_score == 6


@pytest.mark.asyncio
async def test_sqlite_checkpointer_resumes_human_review_after_restart(
    tmp_path: Path,
) -> None:
    db = tmp_path / "chapter.sqlite"
    context = chapter_context()
    thread = "persistent-generate-thread"

    async with persistent_chapter_service(str(db)) as first:
        plan = await first.start(
            command("PLAN", task="persistent-plan", thread="p-plan", payload=context)
        )
        paused = await first.start(
            command(
                "GENERATE",
                task="persistent-generate",
                thread=thread,
                payload={
                    **context,
                    "chapterPlan": plan.chapter_plan.model_dump(
                        mode="json", by_alias=True
                    ),
                },
            )
        )
        assert paused.status is ChapterRunStatus.REVIEW_REQUIRED

    async with persistent_chapter_service(str(db)) as restarted:
        restored = await restarted.get(thread)
        assert restored.status is ChapterRunStatus.REVIEW_REQUIRED
        completed = await restarted.finalize(
            command(
                "FINALIZE",
                task="persistent-approve",
                thread=thread,
                payload={"approved": True, "notes": ""},
            )
        )
        assert completed.status is ChapterRunStatus.COMPLETED


@pytest.mark.asyncio
async def test_memory_agent_rejects_locked_fact_and_preserves_warnings() -> None:
    agent = MemoryUpdateAgent(ConflictingMemoryModel())
    update = await agent(
        {
            "chapter_no": 1,
            "final_content": "批准正文",
            "chapter_summary": {"summary": "摘要"},
            "context_packet": {},
            "canon_facts": [{"factKey": "identity_lin_wan", "locked": True}],
            "revision_count": 0,
        }
    )
    memory = update["memory_update"]
    assert [fact["factKey"] for fact in memory["newFacts"]] == ["new_safe_fact"]
    assert memory["continuityWarnings"][0] == "模型原有警告"
    assert any(
        "identity_lin_wan" in warning for warning in memory["continuityWarnings"]
    )
