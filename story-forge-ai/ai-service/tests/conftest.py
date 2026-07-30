from __future__ import annotations

import pytest


@pytest.fixture
def raw_topics() -> list[dict[str, object]]:
    return [
        {
            "title": f"测试选题 {index}",
            "hook": "主角开场被当众赶走，却公开隐藏继承人身份完成反转。",
            "summary": (
                "主角在最低谷寻找证据，揭开幕后背叛者，"
                "并通过事业逆袭夺回人生选择权。"
            ),
            "tags": ["都市情感", "复仇", "身份反转"],
        }
        for index in range(1, 11)
    ]


@pytest.fixture
def valid_payload() -> dict[str, object]:
    return {
        "genre": "都市情感",
        "audience": "女性",
        "keywords": ["复仇"],
        "storyId": 10001,
    }
