from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import create_app


def test_week_one_topic_can_start_and_complete_week_two_workflow() -> None:
    with TestClient(create_app()) as client:
        topic_response = client.post(
            "/ai/topic/generate",
            json={
                "genre": "都市情感",
                "audience": "女性",
                "keywords": ["复仇"],
            },
        )
        assert topic_response.status_code == 200
        selected_topic = topic_response.json()["topics"][0]

        start = client.post(
            "/ai/workflow/start",
            json={
                "taskId": "task-http-1",
                "storyId": 5001,
                "topic": selected_topic,
            },
        )
        assert start.status_code == 200
        paused = start.json()
        assert paused["status"] == "REVIEW_REQUIRED"
        assert paused["currentNode"] == "human_review"
        assert paused["revisionCount"] == 1
        assert len(paused["characters"]) == 4
        assert len(paused["outline"]) == 20
        assert paused["score"]["total"] == 84

        fetched = client.get(f"/ai/workflow/{paused['threadId']}")
        assert fetched.status_code == 200
        assert fetched.json()["threadId"] == paused["threadId"]

        complete = client.post(
            f"/ai/workflow/{paused['threadId']}/resume",
            json={"approved": True, "notes": ""},
        )

    assert complete.status_code == 200
    assert complete.json()["status"] == "COMPLETED"
    assert complete.json()["approved"] is True
    assert complete.json()["artifacts"][-1]["artifactType"] == "WORKFLOW_FINAL"


def test_workflow_http_rejects_unknown_and_invalid_resume() -> None:
    with TestClient(create_app()) as client:
        unknown = client.get("/ai/workflow/not-found")
        invalid_notes = client.post(
            "/ai/workflow/not-found/resume",
            json={"approved": False, "notes": ""},
        )

    assert unknown.status_code == 404
    assert unknown.json()["error"]["code"] == "WORKFLOW_NOT_FOUND"
    assert invalid_notes.status_code == 422
