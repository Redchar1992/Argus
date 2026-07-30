"""Reusable valid second-week payloads for tests."""

from __future__ import annotations

from typing import Any


def week_one_topic() -> dict[str, Any]:
    return {
        "id": 1,
        "title": "离婚当天，我继承百亿集团",
        "hook": "签字现场遭到羞辱，隐藏继承人身份随即公开。",
        "summary": "女主保全证据并夺回人生选择权。",
        "score": 89,
        "scoreReasons": {
            "conflict": {"score": 92, "reason": "开场对抗明确"},
            "reversal": {"score": 91, "reason": "身份变化推动剧情"},
            "emotionalValue": {"score": 86, "reason": "逆袭回报清晰"},
            "shortDramaFit": {"score": 88, "reason": "钩子具体"},
        },
        "tags": ["都市情感", "复仇", "身份反转"],
        # Future week-one explainability fields must remain forwards compatible.
        "conflict": 92,
        "twist": 91,
        "emotionalValue": 86,
    }


def character(
    name: str,
    role: str,
) -> dict[str, Any]:
    return {
        "name": name,
        "role": role,
        "public_identity": "公开身份说明",
        "hidden_secret": "不愿公开的核心秘密",
        "core_desire": "希望夺回人生选择权",
        "greatest_fear": "担心保护的人再次受伤",
        "personality": ["克制", "敏锐"],
        "relationship_to_protagonist": "围绕核心冲突形成的关系",
        "character_arc": "从逃避冲突到主动承担公开真相的后果",
    }


def outline_nodes() -> list[dict[str, Any]]:
    twists = {4, 8, 12, 16}
    return [
        {
            "node_no": number,
            "stage": (
                "开篇"
                if number <= 3
                else "发展"
                if number <= 8
                else "升级"
                if number <= 14
                else "高潮"
                if number <= 18
                else "结局"
            ),
            "event": f"主角执行第{number}步并引发可见后果",
            "conflict": f"对手阻止第{number}步行动",
            "protagonist_goal": "保全证据并查清真相",
            "emotional_target": (
                "尊严与新生得到释放"
                if number == 20
                else "制造紧张与逆袭期待"
            ),
            "new_information": f"获得第{number}条可验证信息",
            "cliffhanger": "无" if number == 20 else "出现更深利益方",
            "is_twist": number in twists,
            "setup_or_payoff": f"埋下或回收线索{number}",
        }
        for number in range(1, 21)
    ]


def raw_score(value: int = 16) -> dict[str, Any]:
    dimension = {
        "score": value,
        "reason": "存在明确的事件证据",
        "major_problem": "局部铺垫仍可提前",
        "suggestion": "提前展示行动与代价",
    }
    return {
        "hook": dimension,
        "emotion": dimension,
        "conflict": dimension,
        "twist": dimension,
        "adaptation": dimension,
        "fatal_problem": "反派利益动机的前置铺垫不足",
        "revision_priority": ["提前展示反派利益"],
    }
