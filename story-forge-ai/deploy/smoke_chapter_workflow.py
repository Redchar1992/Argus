#!/usr/bin/env python3
"""Exercise the complete week-three chapter workflow with stdlib only."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request
from typing import Any

JsonValue = dict[str, Any] | list[Any]


def request_json(
    base_url: str,
    method: str,
    path: str,
    *,
    token: str | None = None,
    payload: dict[str, Any] | None = None,
) -> JsonValue:
    headers = {"Accept": "application/json"}
    data = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload, ensure_ascii=False).encode()
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}{path}",
        data=data,
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            body = response.read().decode()
    except urllib.error.HTTPError as exc:
        body = exc.read().decode(errors="replace")
        raise RuntimeError(f"{method} {path} -> HTTP {exc.code}: {body}") from exc
    value = json.loads(body or "{}")
    if not isinstance(value, (dict, list)):
        raise RuntimeError(f"{method} {path} did not return a JSON object or array")
    return value


def request_object(*args: Any, **kwargs: Any) -> dict[str, Any]:
    value = request_json(*args, **kwargs)
    if not isinstance(value, dict):
        raise RuntimeError("expected a JSON object")
    return value


def request_list(*args: Any, **kwargs: Any) -> list[Any]:
    value = request_json(*args, **kwargs)
    if not isinstance(value, list):
        raise RuntimeError("expected a JSON array")
    return value


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
        last = request_object(
            base_url,
            "GET",
            f"/api/ai-tasks/{task_id}",
            token=token,
        )
        status = str(last.get("status", ""))
        print(
            f"task={task_id} type={last.get('taskType')} status={status} "
            f"node={last.get('currentNode')} progress={last.get('progress')}%",
            flush=True,
        )
        if status == "FAILED":
            raise RuntimeError(
                f"task failed: {last.get('errorCode')} {last.get('errorMessage')}"
            )
        if status in expected:
            return last
        time.sleep(1)
    raise TimeoutError(f"task {task_id} did not reach {sorted(expected)}; last={last}")


def approve_story_workflow(
    base_url: str,
    token: str,
    story_id: int,
    topic_id: Any,
    deadline: float,
) -> int:
    started = request_object(
        base_url,
        "POST",
        f"/api/stories/{story_id}/workflow",
        token=token,
        payload={"topicId": topic_id},
    )
    task_id = int(started["taskId"])
    wait_for_status(base_url, token, task_id, {"REVIEW_REQUIRED"}, deadline=deadline)
    approved = request_object(
        base_url,
        "POST",
        f"/api/ai-tasks/{task_id}/review",
        token=token,
        payload={"approved": True, "notes": ""},
    )
    final_task_id = int(approved["taskId"])
    wait_for_status(base_url, token, final_task_id, {"SUCCESS"}, deadline=deadline)
    return final_task_id


def validate_plan(chapter: dict[str, Any]) -> None:
    plan = chapter.get("plan")
    if not isinstance(plan, dict):
        raise AssertionError("chapter plan was not persisted")
    scenes = plan.get("scenes")
    if not isinstance(scenes, list) or not 3 <= len(scenes) <= 6:
        raise AssertionError("chapter plan must contain 3-6 scenes")
    if [scene.get("sceneNo") for scene in scenes] != list(range(1, len(scenes) + 1)):
        raise AssertionError("scene numbers must be contiguous")
    for scene in scenes:
        if not scene.get("protagonistGoal") or not scene.get("opposingForce"):
            raise AssertionError("every scene must contain a goal and opposing force")
    if not plan.get("openingHook") or not plan.get("endingHook"):
        raise AssertionError("chapter plan must contain opening and ending hooks")


def sha256(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend", default="http://localhost:8080")
    parser.add_argument("--timeout", type=int, default=240)
    args = parser.parse_args()
    deadline = time.monotonic() + args.timeout
    suffix = int(time.time() * 1000)
    username = f"chapter-smoke-{suffix}"
    password = "StoryForge123!"

    auth = request_object(
        args.backend,
        "POST",
        "/api/auth/register",
        payload={"username": username, "password": password},
    )
    token = str(auth["token"])
    story = request_object(
        args.backend,
        "POST",
        "/api/story/create",
        token=token,
        payload={
            "title": "失控的证据",
            "genre": "都市情感",
            "audience": "女性",
            "keywords": "复仇,身份反转",
        },
    )
    story_id = int(story["id"])
    topics = request_object(
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
    story_workflow_task = approve_story_workflow(
        args.backend,
        token,
        story_id,
        topic_list[0]["id"],
        deadline,
    )

    planned = request_object(
        args.backend,
        "POST",
        f"/api/stories/{story_id}/chapters/1/plan",
        token=token,
        payload={"targetLength": 1000},
    )
    plan_task = int(planned["taskId"])
    wait_for_status(args.backend, token, plan_task, {"SUCCESS"}, deadline=deadline)
    chapter = request_object(
        args.backend,
        "GET",
        f"/api/stories/{story_id}/chapters/1",
        token=token,
    )
    validate_plan(chapter)
    chapter_id = int(chapter["id"])

    chapter = request_object(
        args.backend,
        "POST",
        f"/api/stories/{story_id}/chapters/1/plan/approve",
        token=token,
        payload={"planHash": chapter["planHash"]},
    )
    if chapter.get("planStatus") != "APPROVED":
        raise AssertionError("chapter plan was not approved")

    generated = request_object(
        args.backend,
        "POST",
        f"/api/stories/{story_id}/chapters/1/generate",
        token=token,
        payload={},
    )
    generation_task = int(generated["taskId"])
    generation = wait_for_status(
        args.backend,
        token,
        generation_task,
        {"REVIEW_REQUIRED"},
        deadline=deadline,
    )
    result = generation.get("result")
    review = result.get("review") if isinstance(result, dict) else None
    if not isinstance(review, dict) or int(review.get("totalScore", 0)) < 82:
        raise AssertionError("local workflow must auto-revise the initial low-score draft")
    if int(result.get("revisionCount", 0)) < 1:
        raise AssertionError("low-score chapter did not create an automatic revision")

    events = request_list(
        args.backend,
        "GET",
        f"/api/ai-tasks/{generation_task}/events/history",
        token=token,
    )
    sequences = [int(item["sequence"]) for item in events if item.get("type") != "STREAM_RESET"]
    if sequences != list(range(1, len(sequences) + 1)):
        raise AssertionError("chapter event sequence is not contiguous")
    if not any(item.get("type") == "TOKEN_DELTA" for item in events):
        raise AssertionError("chapter generation did not persist token events")
    if events[-1].get("type") != "HUMAN_REVIEW_REQUIRED":
        raise AssertionError("generation stream did not end at human review")

    chapter = request_object(
        args.backend,
        "GET",
        f"/api/chapters/{chapter_id}",
        token=token,
    )
    base = chapter.get("currentVersion")
    if not isinstance(base, dict) or not base.get("content"):
        raise AssertionError("generated chapter version was not persisted")
    edited_content = str(base["content"]) + "\n\n她把最后一份证据交给律师，决定亲自承担公开真相的后果。"
    edited = request_object(
        args.backend,
        "PUT",
        f"/api/chapters/{chapter_id}/content",
        token=token,
        payload={
            "baseVersionId": base["id"],
            "baseContentHash": base["contentHash"],
            "content": edited_content,
        },
    )
    if edited.get("sourceType") != "USER_EDIT":
        raise AssertionError("manual edit did not create USER_EDIT version")

    selected_text = edited_content[: min(80, len(edited_content))]
    rewrite = request_object(
        args.backend,
        "POST",
        f"/api/chapters/{chapter_id}/rewrite-selection",
        token=token,
        payload={
            "chapterVersionId": edited["id"],
            "startOffset": 0,
            "endOffset": len(selected_text),
            "selectedText": selected_text,
            "selectedTextHash": sha256(selected_text),
            "action": "ENHANCE_CONFLICT",
            "customInstruction": "增加一个可见阻碍，不改变既有事实。",
        },
    )
    rewrite_task = int(rewrite["taskId"])
    wait_for_status(args.backend, token, rewrite_task, {"SUCCESS"}, deadline=deadline)
    proposals = request_list(
        args.backend,
        "GET",
        f"/api/chapters/{chapter_id}/rewrite-proposals",
        token=token,
    )
    proposal = next((item for item in proposals if item.get("aiTaskId") == rewrite_task), None)
    if not isinstance(proposal, dict) or proposal.get("status") != "READY":
        raise AssertionError("rewrite proposal was not persisted for preview")
    if proposal.get("originalText") != selected_text or not proposal.get("replacementText"):
        raise AssertionError("rewrite proposal lost its optimistic-lock anchors")

    accepted = request_object(
        args.backend,
        "POST",
        f"/api/chapters/{chapter_id}/rewrite-proposals/{proposal['proposalId']}/accept",
        token=token,
        payload={
            "baseVersionId": edited["id"],
            "baseContentHash": edited["contentHash"],
        },
    )
    if accepted.get("sourceType") != "AI_SELECTION_REWRITE":
        raise AssertionError("accepting a proposal did not create an immutable version")

    comparison = request_object(
        args.backend,
        "GET",
        f"/api/chapters/{chapter_id}/versions/compare"
        f"?fromVersionId={edited['id']}&toVersionId={accepted['id']}",
        token=token,
    )
    if comparison.get("fromChangedText") == comparison.get("toChangedText"):
        raise AssertionError("version comparison did not expose the selected-text change")

    restored = request_object(
        args.backend,
        "POST",
        f"/api/chapters/{chapter_id}/versions/{accepted['id']}/restore",
        token=token,
        payload={},
    )
    if restored.get("sourceType") != "RESTORE":
        raise AssertionError("restore did not create a new immutable version")

    finalized = request_object(
        args.backend,
        "POST",
        f"/api/chapters/{chapter_id}/approve",
        token=token,
        payload={"approved": True, "notes": ""},
    )
    finalize_task = int(finalized["taskId"])
    final_status = wait_for_status(
        args.backend,
        token,
        finalize_task,
        {"SUCCESS"},
        deadline=deadline,
    )
    final_chapter = request_object(
        args.backend,
        "GET",
        f"/api/chapters/{chapter_id}",
        token=token,
    )
    if final_chapter.get("status") != "APPROVED" or not final_chapter.get("summary"):
        raise AssertionError("approved chapter or chapter summary was not persisted")

    second_plan = request_object(
        args.backend,
        "POST",
        f"/api/stories/{story_id}/chapters/2/plan",
        token=token,
        payload={"targetLength": 1000},
    )
    second_plan_task = int(second_plan["taskId"])
    wait_for_status(args.backend, token, second_plan_task, {"SUCCESS"}, deadline=deadline)
    chapter_two = request_object(
        args.backend,
        "GET",
        f"/api/stories/{story_id}/chapters/2",
        token=token,
    )
    validate_plan(chapter_two)

    versions = request_list(
        args.backend,
        "GET",
        f"/api/chapters/{chapter_id}/versions",
        token=token,
    )
    source_types = {item.get("sourceType") for item in versions}
    required_sources = {"AI_DRAFT", "AI_REVISION", "USER_EDIT", "AI_SELECTION_REWRITE", "RESTORE", "APPROVED"}
    if not required_sources.issubset(source_types):
        raise AssertionError(f"missing version sources: {sorted(required_sources - source_types)}")

    relogin = request_object(
        args.backend,
        "POST",
        "/api/auth/login",
        payload={"username": username, "password": password},
    )
    reopened = request_object(
        args.backend,
        "GET",
        f"/api/chapters/{chapter_id}",
        token=str(relogin["token"]),
    )
    if reopened.get("currentVersionId") != final_chapter.get("currentVersionId"):
        raise AssertionError("approved chapter could not be reopened after login")

    print(
        json.dumps(
            {
                "status": "ok",
                "username": username,
                "storyId": story_id,
                "chapterId": chapter_id,
                "tasks": {
                    "storyWorkflow": story_workflow_task,
                    "plan": plan_task,
                    "generate": generation_task,
                    "rewrite": rewrite_task,
                    "finalize": finalize_task,
                    "nextPlan": second_plan_task,
                },
                "threadId": final_status.get("threadId"),
                "reviewScore": review["totalScore"],
                "versions": len(versions),
                "tokenEvents": sum(item.get("type") == "TOKEN_DELTA" for item in events),
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, RuntimeError, TimeoutError) as exc:
        print(f"chapter smoke failed: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc
