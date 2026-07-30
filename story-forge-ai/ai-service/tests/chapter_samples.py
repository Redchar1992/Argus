"""Reusable valid chapter workflow payloads."""

from __future__ import annotations

from typing import Any

from tests.workflow_samples import character, outline_nodes


def chapter_context(*, target_length: int = 900) -> dict[str, Any]:
    return {
        "storyTitle": "离婚当天，我继承百亿集团",
        "genre": "都市情感",
        "targetAudience": "女性",
        "styleProfile": {"tone": "克制、紧凑", "viewpoint": "第三人称限知"},
        "characters": [
            character("林晚", "主角"),
            character("顾承泽", "反派"),
            character("苏晴", "盟友"),
        ],
        "canonFacts": [
            {
                "factKey": "identity_lin_wan",
                "factType": "IDENTITY",
                "subject": "林晚",
                "predicate": "真实身份",
                "value": "林氏集团继承人",
                "visibility": "READER_ONLY",
                "sourceChapter": 1,
                "locked": True,
            }
        ],
        "relationshipStates": [],
        "recentSummaries": [
            {"chapterNo": no, "summary": f"第{no}章摘要"} for no in range(1, 6)
        ],
        "unresolvedThreads": [
            {
                "threadKey": "father_death",
                "description": "父亲死亡原因",
                "status": "OPEN",
            }
        ],
        "foreshadowingLedger": [],
        "outlineNodes": outline_nodes()[:2],
        "targetLength": target_length,
        "maxRevisions": 2,
    }


def chapter_plan() -> dict[str, Any]:
    return {
        "chapterTitle": "第一章 失控的证据",
        "chapterGoal": "公开异常记录并让人物关系发生变化",
        "openingHook": "会议开始前，异常转账记录突然占满屏幕",
        "endingHook": "最后的收款人竟是主角最信任的人",
        "targetLength": 900,
        "scenes": [
            {
                "sceneNo": number,
                "location": f"地点{number}",
                "time": "当天",
                "characters": ["林晚", "顾承泽"] if number != 2 else ["林晚", "苏晴"],
                "protagonistGoal": f"推进第{number}步调查",
                "opposingForce": "顾承泽试图销毁证据",
                "visibleConflict": "双方争夺证据控制权",
                "informationRevealed": f"揭示第{number}条利益关联",
                "emotionalChange": "主角由迟疑转为主动",
                "setupOrPayoff": "设置或回收证据线索",
                "exitHook": "新的记录指向更深利益方",
                "sceneFunction": ["建立", "升级", "反转", "高潮"][number - 1],
            }
            for number in range(1, 5)
        ],
    }
