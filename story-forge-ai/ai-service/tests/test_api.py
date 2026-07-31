from __future__ import annotations

from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


def test_health() -> None:
    with TestClient(create_app()) as client:
        response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_internal_routes_require_configured_service_key() -> None:
    settings = Settings(internal_api_key="test-service-key")
    with TestClient(create_app(settings=settings)) as client:
        missing = client.post(
            "/ai/topic/generate",
            json={"genre": "都市情感", "audience": "女性"},
        )
        valid = client.post(
            "/ai/topic/generate",
            headers={"X-Internal-API-Key": "test-service-key"},
            json={"genre": "都市情感", "audience": "女性"},
        )

    assert missing.status_code == 401
    assert valid.status_code == 200


def test_local_generation_returns_ten_structured_topics(
    valid_payload: dict[str, object],
) -> None:
    with TestClient(create_app()) as client:
        response = client.post("/ai/topic/generate", json=valid_payload)

    assert response.status_code == 200
    body = response.json()
    assert body["model"] == "local-template"
    assert body["generatedAt"].endswith("Z")
    assert len(body["topics"]) == 10
    assert [topic["id"] for topic in body["topics"]] == list(range(1, 11))
    for topic in body["topics"]:
        assert set(topic) == {
            "id",
            "title",
            "hook",
            "summary",
            "score",
            "scoreReasons",
            "tags",
        }
        assert 0 <= topic["score"] <= 100
        assert set(topic["scoreReasons"]) == {
            "conflict",
            "reversal",
            "emotionalValue",
            "shortDramaFit",
        }
        for reason in topic["scoreReasons"].values():
            assert 0 <= reason["score"] <= 100
            assert reason["reason"]


def test_keywords_may_be_a_delimited_string() -> None:
    payload = {
        "genre": "都市情感",
        "audience": "女性",
        "keywords": "复仇，成长",
    }
    with TestClient(create_app()) as client:
        response = client.post("/ai/topic/generate", json=payload)

    assert response.status_code == 200
    first_tags = response.json()["topics"][0]["tags"]
    assert "复仇" in first_tags
    assert "成长" in first_tags


def test_invalid_input_is_rejected() -> None:
    with TestClient(create_app()) as client:
        missing_audience = client.post(
            "/ai/topic/generate", json={"genre": "都市情感"}
        )
        blank_genre = client.post(
            "/ai/topic/generate", json={"genre": " ", "audience": "女性"}
        )
        extra_field = client.post(
            "/ai/topic/generate",
            json={"genre": "都市情感", "audience": "女性", "unknown": True},
        )

    assert missing_audience.status_code == 422
    assert blank_genre.status_code == 422
    assert extra_field.status_code == 422
