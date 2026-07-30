from __future__ import annotations

import hashlib

import pytest
from pydantic import ValidationError

from app.schemas.chapter import (
    ChapterCommand,
    ChapterDecision,
    ChapterPlan,
    ChapterReview,
    MemoryUpdate,
    build_chapter_review,
)
from tests.chapter_samples import chapter_plan


def test_plan_enforces_scene_numbers_and_known_characters() -> None:
    plan = ChapterPlan.model_validate(chapter_plan())
    plan.validate_known_characters({"林晚", "顾承泽", "苏晴"})

    invalid = chapter_plan()
    invalid["scenes"][1]["sceneNo"] = 4
    with pytest.raises(ValidationError, match="场景编号"):
        ChapterPlan.model_validate(invalid)

    with pytest.raises(ValueError, match="未登记角色"):
        plan.validate_known_characters({"林晚", "顾承泽"})


def test_review_total_is_owned_by_application() -> None:
    def dimension(score: int, maximum: int) -> dict[str, object]:
        return {
            "score": score,
            "maxScore": maximum,
            "evidence": ["正文证据"],
            "problems": [],
            "suggestions": [],
        }

    review = ChapterReview.model_validate(
        {
            "outlineCompletion": dimension(18, 20),
            "continuity": dimension(18, 20),
            "conflictProgression": dimension(17, 20),
            "emotionAndVisuals": dimension(13, 15),
            "hooks": dimension(12, 15),
            "languageQuality": dimension(8, 10),
            "fatalProblems": [],
            "rewriteInstructions": [],
            # A model cannot force acceptance with this boolean.
            "shouldRewrite": False,
        }
    )
    result = build_chapter_review(review, ["章节长度明显不足"])
    assert result.total_score == 86
    assert result.should_rewrite is True


def test_rewrite_command_validates_hash_and_offsets() -> None:
    selected = "她按住证据，没有后退。"
    payload = {
        "chapterVersionId": 3,
        "startOffset": 10,
        "endOffset": 10 + len(selected),
        "selectedText": selected,
        "selectedTextHash": hashlib.sha256(selected.encode()).hexdigest(),
        "action": "ENHANCE_CONFLICT",
    }
    command = ChapterCommand(
        taskId="rewrite-1",
        storyId=1,
        chapterId=2,
        chapterNo=1,
        action="REWRITE_SELECTION",
        idempotencyKey="rewrite-1",
        payload=payload,
    )
    assert command.payload["chapterVersionId"] == 3

    payload["selectedTextHash"] = "0" * 64
    with pytest.raises(ValidationError, match="selectedTextHash"):
        ChapterCommand(
            taskId="rewrite-2",
            storyId=1,
            chapterId=2,
            chapterNo=1,
            action="REWRITE_SELECTION",
            idempotencyKey="rewrite-2",
            payload=payload,
        )


def test_chapter_decision_remains_strict_about_envelope_fields() -> None:
    with pytest.raises(ValidationError, match="chapterPlan"):
        ChapterDecision.model_validate(
            {
                "approved": True,
                "notes": "",
                "chapterPlan": chapter_plan(),
            }
        )


def test_memory_update_preserves_camel_case_contract() -> None:
    update = MemoryUpdate.model_validate(
        {
            "newFacts": [
                {
                    "factKey": "identity_lin_wan",
                    "factType": "IDENTITY",
                    "subject": "林晚",
                    "predicate": "身份",
                    "value": "林氏集团继承人",
                    "visibility": "READER_ONLY",
                    "sourceChapter": 1,
                    "locked": True,
                    "status": "ACTIVE",
                }
            ],
            "changedRelationships": [
                {
                    "characterA": "林晚",
                    "characterB": "顾承泽",
                    "relation": "公开对抗",
                    "trust": 0,
                    "conflict": 90,
                    "updatedAtChapter": 1,
                }
            ],
            "openedThreads": [
                {
                    "threadKey": "account_owner",
                    "description": "秘密账户收款人的真实身份",
                    "status": "OPEN",
                    "knownClues": ["异常账单"],
                }
            ],
            "newForeshadowing": [
                {
                    "foreshadowKey": "red_folder",
                    "setup": "红色文件夹被藏起",
                    "status": "SETUP",
                }
            ],
            "characterStateChanges": [
                {
                    "character": "林晚",
                    "field": "evidenceCopies",
                    "newValue": "掌握异常转账记录的多个副本",
                    "updatedAtChapter": 1,
                    "visibility": "CHARACTER_PRIVATE",
                }
            ],
        }
    )

    dumped = update.model_dump(mode="json", by_alias=True)
    assert dumped["newFacts"][0]["factKey"] == "identity_lin_wan"
    assert dumped["changedRelationships"][0]["characterA"] == "林晚"
    assert dumped["openedThreads"][0]["threadKey"] == "account_owner"
    assert dumped["newForeshadowing"][0]["foreshadowKey"] == "red_folder"
    assert dumped["characterStateChanges"][0]["updatedAtChapter"] == 1
    assert dumped["characterStateChanges"][0]["newValue"] == (
        "掌握异常转账记录的多个副本"
    )
    assert dumped["characterStateChanges"][0]["visibility"] == ("CHARACTER_PRIVATE")


@pytest.mark.parametrize(
    ("payload", "error_field"),
    [
        (
            {"newFacts": [{"factKey": "x" * 129, "value": "too long"}]},
            "factKey",
        ),
        (
            {
                "changedRelationships": [
                    {
                        "characterA": "x" * 101,
                        "characterB": "顾承泽",
                    }
                ]
            },
            "characterA",
        ),
        (
            {
                "characterStateChanges": [
                    {
                        "character": "林晚",
                        "field": "x" * 101,
                        "newValue": "受伤",
                    }
                ]
            },
            "field",
        ),
        (
            {
                "newFacts": [
                    {
                        "factKey": "oversized_value",
                        "value": "x" * 20_001,
                    }
                ]
            },
            "value",
        ),
        (
            {
                "characterStateChanges": [
                    {
                        "character": "林晚",
                        "field": "internalMonologue",
                        "newValue": "x" * 20_001,
                    }
                ]
            },
            "newValue",
        ),
        (
            {
                "characterStateChanges": [
                    {
                        "character": "林晚",
                        "field": "injury",
                        "newValue": None,
                    }
                ]
            },
            "newValue",
        ),
        (
            {
                "newFacts": [
                    {
                        "factKey": "identity_lin_wan",
                        "value": None,
                    }
                ]
            },
            "value",
        ),
    ],
)
def test_memory_update_rejects_values_over_backend_column_limits(
    payload: dict[str, object],
    error_field: str,
) -> None:
    with pytest.raises(ValidationError, match=error_field):
        MemoryUpdate.model_validate(payload)


@pytest.mark.parametrize(
    ("payload", "invalid_value"),
    [
        (
            {
                "newFacts": [
                    {
                        "factKey": "identity_lin_wan",
                        "value": "林氏集团继承人",
                        "visibility": "TEAM_ONLY",
                    }
                ]
            },
            "TEAM_ONLY",
        ),
        (
            {
                "updatedThreads": [
                    {
                        "threadKey": "account_owner",
                        "description": "秘密账户收款人的真实身份",
                        "status": "PENDING",
                    }
                ]
            },
            "PENDING",
        ),
        (
            {
                "newForeshadowing": [
                    {
                        "foreshadowKey": "red_folder",
                        "setup": "红色文件夹被藏起",
                        "status": "OPEN",
                    }
                ]
            },
            "OPEN",
        ),
    ],
)
def test_memory_update_rejects_unknown_visibility_and_status_enums(
    payload: dict[str, object],
    invalid_value: str,
) -> None:
    with pytest.raises(ValidationError, match=invalid_value):
        MemoryUpdate.model_validate(payload)
