from __future__ import annotations

import json
from typing import Any

import fakeredis.aioredis
import pytest

from app.agents.character_agent import CharacterAgent
from app.infrastructure.llm_factory import WorkflowModelError
from app.infrastructure.redis_stream import (
    EVENT_FIELDS,
    EVENT_STREAM,
    REQUEST_FIELDS,
    IdempotencyStore,
    RedisStreamBroker,
    RedisWorkflowMessage,
    encode_request,
)
from app.workers.story_worker import StoryWorkflowWorker
from app.workflow.service import StoryWorkflowNotFound, StoryWorkflowService
from app.workflow.story_graph import build_story_graph
from tests.workflow_samples import week_one_topic


def start_message(
    *,
    idempotency_key: str = "5001:v1:START:0",
) -> RedisWorkflowMessage:
    return RedisWorkflowMessage(
        task_id="task-redis-start",
        story_id=5001,
        action="START",
        payload_version="1",
        idempotency_key=idempotency_key,
        topic=week_one_topic(),
    )


def test_request_encoding_uses_exact_string_contract() -> None:
    fields = encode_request(start_message())

    assert tuple(fields) == REQUEST_FIELDS
    assert all(isinstance(value, str) for value in fields.values())
    assert json.loads(fields["topic"])["scoreReasons"]["conflict"]["score"] == 92
    assert "conflict" not in json.loads(fields["topic"])


@pytest.mark.asyncio
async def test_worker_acknowledges_success_and_deduplicates_redelivery() -> None:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    broker = RedisStreamBroker(redis, consumer_name="worker-a")
    idempotency = IdempotencyStore(redis)
    worker = StoryWorkflowWorker(
        broker=broker,
        idempotency=idempotency,
    )

    message = start_message()
    await broker.ensure_group()
    await broker.enqueue(message)
    assert await worker.run_once(block_ms=None) == 1

    pending = await redis.xpending(broker.request_stream, broker.consumer_group)
    assert pending["pending"] == 0
    assert await redis.xlen(broker.request_stream) == 0
    events = [fields for _id, fields in await redis.xrange(EVENT_STREAM)]
    assert events[0]["status"] == "RUNNING"
    assert events[0]["revisionCount"] == "0"
    assert events[0]["maxRevisions"] == "2"
    assert events[-1]["status"] == "REVIEW_REQUIRED"
    assert events[-1]["revisionCount"] == "1"
    assert events[-1]["maxRevisions"] == "2"
    assert {
        "generate_characters",
        "generate_outline",
        "score_outline",
        "revise_outline",
    }.issubset({event["currentNode"] for event in events})
    assert any(
        event["currentNode"] == "revise_outline"
        and event["revisionCount"] == "1"
        for event in events
    )
    assert set(events[-1]) == set(EVENT_FIELDS)
    artifacts = json.loads(events[-1]["artifacts"])
    assert [
        item["versionNo"]
        for item in artifacts
        if item["artifactType"] == "OUTLINE"
    ] == [1, 2]
    assert set(artifacts[0]) == {
        "artifactType",
        "versionNo",
        "status",
        "content",
        "promptVersion",
        "modelName",
    }
    calls = json.loads(events[-1]["modelCalls"])
    assert len(calls) == 5
    assert int(events[-1]["inputTokens"]) == sum(
        call["inputTokens"] for call in calls
    )
    assert int(events[-1]["outputTokens"]) == sum(
        call["outputTokens"] for call in calls
    )
    assert int(events[-1]["durationMs"]) == sum(
        call["durationMs"] for call in calls
    )
    assert all(
        {
            "node",
            "modelName",
            "promptVersion",
            "inputTokens",
            "outputTokens",
            "durationMs",
            "success",
            "error",
        }
        == set(call)
        for call in calls
    )
    thread_id = events[-1]["threadId"]

    # A separately delivered duplicate is ACKed without running agents or
    # publishing a second result.
    await broker.enqueue(message)
    assert await worker.run_once(block_ms=None) == 1
    assert await redis.xlen(broker.request_stream) == 0
    event_count_before_duplicate = len(events)
    after_duplicate = await redis.xrange(EVENT_STREAM)
    assert len(after_duplicate) == event_count_before_duplicate + 1
    assert after_duplicate[-1][1]["status"] == "REVIEW_REQUIRED"
    event_count_after_start = len(after_duplicate)

    await broker.enqueue(
        RedisWorkflowMessage(
            task_id="task-redis-resume",
            story_id=5001,
            thread_id=thread_id,
            action="RESUME",
            payload_version="1",
            idempotency_key="5001:v1:RESUME:1",
            approved=True,
            notes="",
        )
    )
    assert await worker.run_once(block_ms=None) == 1

    events = [fields for _id, fields in await redis.xrange(EVENT_STREAM)]
    resume_events = events[event_count_after_start:]
    assert resume_events[0]["status"] == "RUNNING"
    assert resume_events[-1]["status"] == "SUCCESS"
    assert any(
        event["currentNode"] == "human_review" for event in resume_events
    )
    assert events[-1]["progress"] == "100"
    assert events[-1]["revisionCount"] == "1"
    # Approval itself does not spend another LLM call.
    assert json.loads(events[-1]["modelCalls"]) == []
    assert any(
        artifact["artifactType"] == "WORKFLOW_FINAL"
        for artifact in json.loads(events[-1]["artifacts"])
    )
    assert (
        await idempotency.completed_result("5001:v1:RESUME:1")
    )["status"] == "SUCCESS"
    await redis.aclose()


@pytest.mark.asyncio
async def test_resume_redelivery_after_marker_loss_is_not_applied_twice() -> None:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    broker = RedisStreamBroker(redis, consumer_name="worker-resume-dedup")
    idempotency = IdempotencyStore(redis)
    worker = StoryWorkflowWorker(broker=broker, idempotency=idempotency)
    await broker.ensure_group()
    await broker.enqueue(start_message())
    await worker.run_once(block_ms=None)
    first_review = (await redis.xrange(EVENT_STREAM))[-1][1]
    thread_id = first_review["threadId"]

    resume_key = "5001:v1:RESUME:1"
    resume = RedisWorkflowMessage(
        task_id="task-reject",
        story_id=5001,
        thread_id=thread_id,
        action="RESUME",
        idempotency_key=resume_key,
        approved=False,
        notes="请提前铺垫妹妹与反派的利益关系。",
    )
    await broker.enqueue(resume)
    await worker.run_once(block_ms=None)
    review_after_revision = (await redis.xrange(EVENT_STREAM))[-1][1]
    assert review_after_revision["revisionCount"] == "2"
    assert len(json.loads(review_after_revision["modelCalls"])) == 2

    # Simulate a crash after graph/result publication but before a durable
    # completed-result marker survived. The graph checkpoint remembers that
    # this operation key was applied and must not apply the review twice.
    await redis.delete(
        f"story:workflow:idempotency:result:{resume_key}"
    )
    await broker.enqueue(resume)
    await worker.run_once(block_ms=None)

    redelivered = (await redis.xrange(EVENT_STREAM))[-1][1]
    assert redelivered["status"] == "REVIEW_REQUIRED"
    assert redelivered["revisionCount"] == "2"
    assert [
        artifact["versionNo"]
        for artifact in json.loads(redelivered["artifacts"])
        if artifact["artifactType"] == "OUTLINE"
    ] == [1, 2, 3]
    assert await worker.workflow.operation_applied(thread_id, resume_key)
    assert (await redis.xpending(broker.request_stream, broker.consumer_group))[
        "pending"
    ] == 0
    await redis.aclose()


class FailingWorkflow:
    async def get(self, _thread_id: str) -> Any:
        raise StoryWorkflowNotFound("not started")

    async def start(
        self,
        _request: Any,
        *,
        on_update: Any = None,
        operation_key: str = "",
    ) -> Any:
        del on_update, operation_key
        raise RuntimeError("temporary model outage")


class AckFailsOnceBroker(RedisStreamBroker):
    def __init__(self, redis: Any, **kwargs: Any) -> None:
        super().__init__(redis, **kwargs)
        self.should_fail_ack = True

    async def acknowledge(self, message_id: str) -> int:
        if self.should_fail_ack:
            self.should_fail_ack = False
            raise ConnectionError("temporary XACK failure")
        return await super().acknowledge(message_id)


class FailingStructuredModel:
    model_name = "unavailable-model"

    async def generate(self, *_args: Any, **_kwargs: Any) -> Any:
        raise WorkflowModelError("provider timeout")


@pytest.mark.asyncio
async def test_pending_failure_can_be_recovered_with_xautoclaim() -> None:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    first_broker = RedisStreamBroker(redis, consumer_name="failed-worker")
    await first_broker.ensure_group()
    await first_broker.enqueue(start_message(idempotency_key="recover-me"))

    failed_worker = StoryWorkflowWorker(
        broker=first_broker,
        idempotency=IdempotencyStore(redis),
        workflow=FailingWorkflow(),  # type: ignore[arg-type]
    )
    assert await failed_worker.run_once(block_ms=None) == 1
    pending = await redis.xpending(
        first_broker.request_stream,
        first_broker.consumer_group,
    )
    assert pending["pending"] == 1

    events = [fields for _id, fields in await redis.xrange(EVENT_STREAM)]
    assert events[-1]["status"] == "FAILED"
    assert events[-1]["errorCode"] == "WORKFLOW_EXECUTION_FAILED"
    assert "temporary model outage" in events[-1]["errorMessage"]

    recovered_broker = RedisStreamBroker(redis, consumer_name="healthy-worker")
    recovered_worker = StoryWorkflowWorker(
        broker=recovered_broker,
        idempotency=IdempotencyStore(redis),
    )
    assert await recovered_worker.recover_once(min_idle_ms=0) == 1

    pending = await redis.xpending(
        recovered_broker.request_stream,
        recovered_broker.consumer_group,
    )
    assert pending["pending"] == 0
    events = [fields for _id, fields in await redis.xrange(EVENT_STREAM)]
    assert events[-1]["status"] == "REVIEW_REQUIRED"
    assert events[-1]["attemptNo"] == "2"
    await redis.aclose()


@pytest.mark.asyncio
async def test_ack_failure_replays_terminal_event_without_false_failure() -> None:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    broker = AckFailsOnceBroker(redis, consumer_name="worker-ack")
    idempotency = IdempotencyStore(redis)
    worker = StoryWorkflowWorker(broker=broker, idempotency=idempotency)
    await broker.ensure_group()
    message = start_message(idempotency_key="ack-window")
    await broker.enqueue(message)

    assert await worker.run_once(block_ms=None) == 1
    pending = await redis.xpending(broker.request_stream, broker.consumer_group)
    assert pending["pending"] == 1
    assert await idempotency.completed_result("ack-window") is not None
    first_events = [
        fields for _event_id, fields in await redis.xrange(EVENT_STREAM)
    ]
    assert first_events[-1]["status"] == "REVIEW_REQUIRED"
    assert all(event["status"] != "FAILED" for event in first_events)

    assert await worker.recover_once(min_idle_ms=0) == 1
    pending = await redis.xpending(broker.request_stream, broker.consumer_group)
    assert pending["pending"] == 0
    recovered_events = [
        fields for _event_id, fields in await redis.xrange(EVENT_STREAM)
    ]
    assert recovered_events[-1]["status"] == "REVIEW_REQUIRED"
    assert all(event["status"] != "FAILED" for event in recovered_events)
    # The final event is replayed, but the graph/artifact versions are not.
    assert sum(
        event["status"] == "REVIEW_REQUIRED" for event in recovered_events
    ) == 2
    await redis.aclose()


@pytest.mark.asyncio
async def test_failed_model_call_is_returned_in_failure_event() -> None:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    broker = RedisStreamBroker(redis, consumer_name="worker-model-failure")
    graph = build_story_graph(
        character_agent=CharacterAgent(FailingStructuredModel()),  # type: ignore[arg-type]
    )
    worker = StoryWorkflowWorker(
        broker=broker,
        idempotency=IdempotencyStore(redis),
        workflow=StoryWorkflowService(graph),
    )
    await broker.ensure_group()
    await broker.enqueue(start_message(idempotency_key="model-failure"))

    assert await worker.run_once(block_ms=None) == 1
    event = (await redis.xrange(EVENT_STREAM))[-1][1]
    assert event["status"] == "FAILED"
    assert event["errorCode"] == "WORKFLOW_MODEL_CALL_FAILED"
    calls = json.loads(event["modelCalls"])
    assert calls == [
        {
            "node": "generate_characters",
            "modelName": "unavailable-model",
            "promptVersion": "character_v1",
            "inputTokens": 0,
            "outputTokens": 0,
            "durationMs": calls[0]["durationMs"],
            "success": False,
            "error": "provider timeout",
        }
    ]
    assert (await redis.xpending(broker.request_stream, broker.consumer_group))[
        "pending"
    ] == 1
    await redis.aclose()


@pytest.mark.asyncio
async def test_poison_message_is_failed_and_acknowledged() -> None:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    broker = RedisStreamBroker(redis, consumer_name="worker-poison")
    worker = StoryWorkflowWorker(
        broker=broker,
        idempotency=IdempotencyStore(redis),
    )
    await broker.ensure_group()
    await redis.xadd(
        broker.request_stream,
        {
            "taskId": "bad-task",
            "storyId": "not-an-integer",
            "action": "START",
            "topic": "{broken",
        },
    )

    assert await worker.run_once(block_ms=None) == 1
    pending = await redis.xpending(broker.request_stream, broker.consumer_group)
    assert pending["pending"] == 0
    event = (await redis.xrange(EVENT_STREAM))[-1][1]
    assert event["status"] == "FAILED"
    assert event["errorCode"] == "INVALID_WORKFLOW_MESSAGE"
    await redis.aclose()
