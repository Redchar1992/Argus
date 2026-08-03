from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import create_app


def test_final_review_returns_three_explainable_scores() -> None:
    with TestClient(create_app()) as client:
        response = client.post(
            "/ai/final-review",
            json={
                "storyTitle": "测试故事",
                "genre": "都市情感",
                "targetAudience": "女性",
                "chapters": [
                    {"chapterNo": 1, "title": "开端", "content": "冲突在雨夜发生"},
                    {"chapterNo": 2, "title": "升级", "content": "证据被重新解释"},
                ],
            },
        )

    assert response.status_code == 200
    body = response.json()
    assert set((
        "contentQuality",
        "hitPotential",
        "shortDramaAdaptation",
        "total",
        "level",
    )) <= set(body)
    assert 0 <= body["contentQuality"]["score"] <= 100
    assert 0 <= body["hitPotential"]["score"] <= 100
    assert 0 <= body["shortDramaAdaptation"]["score"] <= 100
    assert body["disclaimer"]


def test_final_review_rejects_empty_chapters() -> None:
    with TestClient(create_app()) as client:
        response = client.post(
            "/ai/final-review",
            json={"storyTitle": "测试故事", "genre": "都市情感", "chapters": []},
        )

    assert response.status_code == 422


def test_novel_final_review_returns_novel_adaptation_score() -> None:
    with TestClient(create_app()) as client:
        response = client.post(
            "/ai/final-review",
            json={
                "storyTitle": "长篇测试",
                "genre": "都市情感",
                "contentMode": "NOVEL",
                "chapters": [
                    {"chapterNo": 1, "title": "开端", "content": "冲突发生"},
                    {"chapterNo": 2, "title": "升级", "content": "线索延伸"},
                ],
            },
        )

    assert response.status_code == 200
    body = response.json()
    assert body["novelAdaptation"]["score"] >= 0
    assert "shortDramaAdaptation" not in body
