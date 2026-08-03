#!/usr/bin/env python3
"""Exercise the complete week-two browser-to-worker workflow with stdlib only."""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from typing import Any


def request_json(
    base_url: str,
    method: str,
    path: str,
    *,
    token: str | None = None,
    payload: dict[str, Any] | None = None,
) -> dict[str, Any]:
    headers = {"Accept": "application/json"}
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload, ensure_ascii=False).encode()
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(
        f"{base_url.rstrip('/')}{path}",
        data=data,
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            body = response.read().decode()
    except urllib.error.HTTPError as exc:
        body = exc.read().decode(errors="replace")
        raise RuntimeError(f"{method} {path} -> HTTP {exc.code}: {body}") from exc
    parsed = json.loads(body or "{}")
    if not isinstance(parsed, dict):
        raise RuntimeError(f"{method} {path} did not return a JSON object")
    return parsed


def wait_for_status(
    base_url: str,
    token: str,
    task_id: int | str,
    expected: set[str],
    *,
    deadline: float,
) -> dict[str, Any]:
    last: dict[str, Any] = {}
    while time.monotonic() < deadline:
        last = request_json(
            base_url,
            "GET",
            f"/api/ai-tasks/{task_id}",
            token=token,
        )
        status = str(last.get("status", ""))
        print(
            f"task={task_id} status={status} "
            f"node={last.get('currentNode')} progress={last.get('progress')}%",
            flush=True,
        )
        if status == "FAILED":
            raise RuntimeError(
                f"workflow failed: {last.get('errorCode')} {last.get('errorMessage')}"
            )
        if status in expected:
            return last
        time.sleep(2)
    raise TimeoutError(f"task {task_id} did not reach {sorted(expected)}; last={last}")


def validate_review(review: dict[str, Any]) -> None:
    characters = review.get("characters")
    outline = review.get("outline")
    score = review.get("score")
    if not isinstance(characters, list) or not 3 <= len(characters) <= 6:
        raise AssertionError("character pack must contain 3-6 entries")
    if isinstance(outline, dict):
        nodes = outline.get("nodes")
    else:
        nodes = outline
    if not isinstance(nodes, list) or len(nodes) != 20:
        raise AssertionError("outline must contain exactly 20 nodes")
    numbers = [node.get("node_no", node.get("nodeNo")) for node in nodes]
    if numbers != list(range(1, 21)):
        raise AssertionError("outline node numbers must be 1..20")
    if not isinstance(score, dict) or not isinstance(score.get("total"), int):
        raise AssertionError("review must contain an application-computed total score")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend", default="http://localhost:8080")
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    deadline = time.monotonic() + args.timeout
    suffix = int(time.time() * 1000)
    username = f"workflow-smoke-{suffix}"

    password = "StoryForge123!"
    auth = request_json(
        args.backend,
        "POST",
        "/api/auth/register",
        payload={
            "username": username,
            "password": password,
            "privacyAccepted": True,
        },
    )
    token = str(auth["token"])
    story = request_json(
        args.backend,
        "POST",
        "/api/story/create",
        token=token,
        payload={
            "title": "都市婚姻复仇",
            "genre": "都市情感",
            "audience": "女性",
            "keywords": "复仇,身份反转",
        },
    )
    story_id = story["id"]
    topics = request_json(
        args.backend,
        "POST",
        "/api/ai/topic/generate",
        token=token,
        payload={
            "storyId": story_id,
            "genre": "都市情感",
            "audience": "女性",
            "keywords": "复仇,身份反转",
        },
    )
    topic_list = topics.get("topics")
    if not isinstance(topic_list, list) or len(topic_list) != 10:
        raise AssertionError("topic workflow must return exactly 10 topics")

    started = request_json(
        args.backend,
        "POST",
        f"/api/stories/{story_id}/workflow",
        token=token,
        payload={"topicId": topic_list[0]["id"]},
    )
    first_task = started["taskId"]
    wait_for_status(
        args.backend,
        token,
        first_task,
        {"REVIEW_REQUIRED"},
        deadline=deadline,
    )
    first_review = request_json(
        args.backend,
        "GET",
        f"/api/ai-tasks/{first_task}/review",
        token=token,
    )
    validate_review(first_review)

    revised = request_json(
        args.backend,
        "POST",
        f"/api/ai-tasks/{first_task}/review",
        token=token,
        payload={
            "approved": False,
            "notes": "请在节点8提前铺垫姐姐与反派的利益关系，并强化反派动机。",
        },
    )
    revision_task = revised["taskId"]
    wait_for_status(
        args.backend,
        token,
        revision_task,
        {"REVIEW_REQUIRED"},
        deadline=deadline,
    )
    revised_review = request_json(
        args.backend,
        "GET",
        f"/api/ai-tasks/{revision_task}/review",
        token=token,
    )
    validate_review(revised_review)

    approved = request_json(
        args.backend,
        "POST",
        f"/api/ai-tasks/{revision_task}/review",
        token=token,
        payload={"approved": True, "notes": ""},
    )
    final_task = approved["taskId"]
    final_status = wait_for_status(
        args.backend,
        token,
        final_task,
        {"SUCCESS"},
        deadline=deadline,
    )
    final_review = request_json(
        args.backend,
        "GET",
        f"/api/ai-tasks/{final_task}/review",
        token=token,
    )
    validate_review(final_review)

    # Prove that the persisted result can be rediscovered without localStorage.
    relogin = request_json(
        args.backend,
        "POST",
        "/api/auth/login",
        payload={"username": username, "password": password},
    )
    reopened = request_json(
        args.backend,
        "GET",
        f"/api/stories/{story_id}/workflow/latest",
        token=str(relogin["token"]),
    )
    if str(reopened.get("taskId")) != str(final_task):
        raise AssertionError("latest workflow discovery did not return the final task")

    print(
        json.dumps(
            {
                "status": "ok",
                "storyId": story_id,
                "tasks": [first_task, revision_task, final_task],
                "threadId": final_status.get("threadId"),
                "finalScore": final_review["score"]["total"],
                "versions": len(final_review.get("versions", [])),
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, RuntimeError, TimeoutError) as exc:
        print(f"smoke failed: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
