from __future__ import annotations

from copy import deepcopy

import pytest
from pydantic import ValidationError

from app.schemas.character import CharacterPack
from app.schemas.outline import OutlineResult
from app.schemas.score import StoryScore, build_score_result
from app.schemas.workflow import SelectedTopic, WorkflowStartRequest
from tests.workflow_samples import (
    character,
    outline_nodes,
    raw_score,
    week_one_topic,
)


def test_selected_topic_accepts_complete_week_one_result() -> None:
    selected = SelectedTopic.model_validate(week_one_topic())

    assert selected.title == "离婚当天，我继承百亿集团"
    assert selected.score_reasons
    # Unknown future score fields are ignored rather than rejecting START.
    assert "twist" not in selected.model_dump()


def test_character_pack_enforces_size_roles_and_unique_names() -> None:
    valid = [
        character("林晚", "主角"),
        character("顾承泽", "反派"),
        character("苏晴", "盟友"),
    ]
    assert len(CharacterPack(characters=valid).characters) == 3

    with pytest.raises(ValidationError):
        CharacterPack(characters=valid[:2])

    duplicated = [*valid, character("苏晴", "关键配角")]
    with pytest.raises(ValidationError, match="姓名不得重复"):
        CharacterPack(characters=duplicated)

    without_villain = [
        character("林晚", "主角"),
        character("苏晴", "盟友"),
        character("林岚", "关键配角"),
    ]
    with pytest.raises(ValidationError, match="至少一名反派"):
        CharacterPack(characters=without_villain)


@pytest.mark.parametrize("mutation", ["count", "numbering", "twists", "conflict"])
def test_outline_rejects_mechanically_invalid_structures(mutation: str) -> None:
    nodes = outline_nodes()
    if mutation == "count":
        nodes.pop()
    elif mutation == "numbering":
        nodes[9]["node_no"] = 9
    elif mutation == "twists":
        for node in nodes:
            node["is_twist"] = node["node_no"] in {4, 8, 12}
    else:
        for node in nodes[:3]:
            node["conflict"] = "无"

    with pytest.raises((ValidationError, ValueError)):
        OutlineResult(
            title="测试故事",
            core_conflict="主角与反派争夺关键证据",
            ending_type="真相公开",
            nodes=nodes,
        )


def test_outline_accepts_exactly_twenty_nodes_and_emotional_ending() -> None:
    result = OutlineResult(
        title="测试故事",
        core_conflict="主角与反派争夺关键证据",
        ending_type="真相公开",
        nodes=outline_nodes(),
    )

    assert [node.node_no for node in result.nodes] == list(range(1, 21))
    assert sum(node.is_twist for node in result.nodes) == 4
    assert result.nodes[-1].stage == "结局"


def test_score_dimension_is_bounded_and_total_is_application_owned() -> None:
    raw = StoryScore.model_validate(raw_score(16))
    result = build_score_result(raw)

    assert result.total == 80
    assert result.level == "A"
    assert "total" not in raw.model_dump()

    invalid = deepcopy(raw_score())
    invalid["hook"]["score"] = 21
    with pytest.raises(ValidationError):
        StoryScore.model_validate(invalid)


@pytest.mark.parametrize(
    ("content_mode", "target_chapter_count", "message"),
    [
        ("SHORT_STORY", 2, "短故事目标章节数"),
        ("SHORT_STORY", 11, "短故事目标章节数"),
        ("NOVEL", 19, "小说目标章节数"),
    ],
)
def test_workflow_enforces_content_profile_chapter_limits(
    content_mode: str,
    target_chapter_count: int,
    message: str,
) -> None:
    with pytest.raises(ValidationError, match=message):
        WorkflowStartRequest(
            task_id="profile-limit-test",
            story_id=1,
            content_mode=content_mode,
            target_chapter_count=target_chapter_count,
            topic={
                "title": "内容模式边界测试",
                "hook": "开场冲突足以触发边界校验",
            },
        )
