from __future__ import annotations

import asyncio
import hashlib
import json

import fakeredis.aioredis
import pytest

from app.config import Settings
from app.infrastructure.chapter_redis import (
    CHAPTER_COMMAND_FIELDS,
    CHAPTER_EVENT_FIELDS,
    ChapterRedisBroker,
    encode_chapter_command,
)
from app.infrastructure.redis_stream import IdempotencyStore
from app.schemas.chapter import ChapterCommand, ChapterRunStatus
from app.workers.chapter_worker import ChapterWorkflowWorker
from app.workflow.chapter_graph import build_chapter_graph
from app.workflow.chapter_service import (
    ChapterWorkflowConflict,
    ChapterWorkflowNotFound,
    ChapterWorkflowService,
)
from tests.chapter_samples import chapter_context, chapter_plan


def make_command(
    action: str,
    *,
    task_id: str,
    idempotency_key: str,
    payload: dict[str, object],
    thread_id: str = "",
) -> ChapterCommand:
    return ChapterCommand(
        taskId=task_id,
        storyId=5001,
        chapterId=7001,
        chapterNo=1,
        action=action,
        threadId=thread_id,
        idempotencyKey=idempotency_key,
        payload=payload,
    )


def test_chapter_command_uses_exact_cross_service_contract() -> None:
    encoded = encode_chapter_command(
        make_command(
            "PLAN",
            task_id="task-plan",
            idempotency_key="plan-key",
            payload=chapter_context(),
        )
    )
    assert tuple(encoded) == CHAPTER_COMMAND_FIELDS
    assert all(isinstance(value, str) for value in encoded.values())
    assert json.loads(encoded["payload"])["storyTitle"]


@pytest.mark.asyncio
async def test_plan_emits_only_one_terminal_ready_event() -> None:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    broker = ChapterRedisBroker(redis, consumer_name="plan-terminal-test")
    worker = ChapterWorkflowWorker(
        broker=broker,
        idempotency=IdempotencyStore(
            redis,
            prefix="story:chapter:idempotency",
        ),
        workflow=ChapterWorkflowService(build_chapter_graph()),
    )
    await broker.enqueue(
        make_command(
            "PLAN",
            task_id="single-plan-terminal",
            idempotency_key="single-plan-terminal-key",
            payload=chapter_context(),
        )
    )

    assert await worker.run_once(block_ms=None) == 1
    assert await redis.xlen(broker.command_stream) == 0
    ready = [
        fields
        for _event_id, fields in await redis.xrange(broker.event_stream)
        if fields["type"] == "CHAPTER_PLAN_READY"
    ]
    assert len(ready) == 1
    assert ready[0]["status"] == "SUCCESS"
    await redis.aclose()


@pytest.mark.asyncio
async def test_concurrent_event_publication_keeps_task_sequence_contiguous() -> None:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    broker = ChapterRedisBroker(redis)

    async def publish(index: int) -> None:
        await broker.publish_event(
            {
                "taskId": "concurrent-task",
                "type": "TOKEN_DELTA",
                "data": json.dumps({"index": index}),
            }
        )

    await asyncio.gather(*(publish(index) for index in range(20)))
    events = [fields for _event_id, fields in await redis.xrange(broker.event_stream)]
    assert sorted(int(event["sequence"]) for event in events) == list(range(1, 21))
    assert await redis.get("story:chapter:sequence:concurrent-task") == "20"
    await redis.aclose()


@pytest.mark.asyncio
async def test_idempotency_lease_requires_owner_for_renew_release_and_completion() -> (
    None
):
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    store = IdempotencyStore(redis, prefix="lease-test")
    old_owner = await store.acquire("operation")
    assert old_owner is not None
    new_owner = "replacement-owner"
    await redis.set(store._lock_key("operation"), new_owner, ex=300)

    assert not await store.renew("operation", old_owner)
    assert not await store.release("operation", old_owner)
    assert not await store.mark_completed(
        "operation",
        {"status": "SUCCESS"},
        old_owner,
    )
    assert await redis.get(store._lock_key("operation")) == new_owner
    assert await store.completed_result("operation") is None
    assert await store.release("operation", new_owner)
    await redis.aclose()


@pytest.mark.asyncio
async def test_idempotency_heartbeat_renews_long_running_owner() -> None:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    store = IdempotencyStore(
        redis,
        prefix="heartbeat-test",
        lock_ttl_seconds=1,
    )
    owner = await store.acquire("long-operation")
    assert owner is not None
    heartbeat = store.heartbeat(
        "long-operation",
        owner,
        interval_seconds=0.1,
    )
    await heartbeat.start()
    await asyncio.sleep(1.2)
    assert await redis.get(store._lock_key("long-operation")) == owner
    assert not heartbeat.lost
    await heartbeat.stop()
    assert await store.release("long-operation", owner)
    await redis.aclose()


class TemporarilyFailingWorkflow:
    async def get(self, _thread_id: str) -> object:
        raise ChapterWorkflowNotFound("not started")

    async def start(self, *_args: object, **_kwargs: object) -> object:
        raise RuntimeError("temporary chapter provider outage")


class AlwaysFailingWorkflow(TemporarilyFailingWorkflow):
    def __init__(self) -> None:
        self.start_calls = 0

    async def start(self, *_args: object, **_kwargs: object) -> object:
        self.start_calls += 1
        return await super().start(*_args, **_kwargs)


class ConflictingWorkflow:
    async def get(self, _thread_id: str) -> object:
        raise ChapterWorkflowConflict("chapter is already approved")


class FailsFirstApprovalSummary:
    def __init__(self) -> None:
        self.calls = 0

    async def __call__(self, state: dict[str, object]) -> dict[str, object]:
        self.calls += 1
        if self.calls == 1:
            raise RuntimeError("temporary summary provider outage")
        return {
            "chapter_summary": {
                "chapterNo": state["chapter_no"],
                "summary": "林晚公开关键证据，并决定继续追查幕后账户。",
                "mainEvents": ["公开证据"],
                "characterChanges": ["林晚决定反击"],
                "openedThreads": [],
                "resolvedThreads": [],
                "endingHook": "陌生来电响起",
            },
            "current_node": "summarize_chapter",
            "progress_events": [],
            "artifacts": [],
            "model_calls": [],
        }


class AckFailsOnceChapterBroker(ChapterRedisBroker):
    def __init__(self, redis: object, **kwargs: object) -> None:
        super().__init__(redis, **kwargs)
        self.fail_ack = True

    async def acknowledge(self, message_id: str) -> int:
        if self.fail_ack:
            self.fail_ack = False
            raise ConnectionError("temporary XACK outage")
        return await super().acknowledge(message_id)


def test_chapter_retry_limit_defaults_and_reads_environment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    assert Settings().chapter_max_attempts == 3
    monkeypatch.setenv("CHAPTER_MAX_ATTEMPTS", "5")
    assert Settings.from_env().chapter_max_attempts == 5


@pytest.mark.asyncio
async def test_worker_streams_ordered_deltas_and_deduplicates_terminal_result() -> None:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    broker = ChapterRedisBroker(redis, consumer_name="chapter-test")
    idempotency = IdempotencyStore(redis, prefix="story:chapter:idempotency")
    worker = ChapterWorkflowWorker(
        broker=broker,
        idempotency=idempotency,
        workflow=ChapterWorkflowService(build_chapter_graph()),
    )
    generate = make_command(
        "GENERATE",
        task_id="chapter-generate",
        idempotency_key="chapter-generate-key",
        payload={**chapter_context(), "chapterPlan": chapter_plan()},
    )
    await broker.enqueue(generate)
    assert await worker.run_once(block_ms=None) == 1

    rows = await redis.xrange(broker.event_stream)
    events = [fields for _event_id, fields in rows]
    assert all(set(event) == set(CHAPTER_EVENT_FIELDS) for event in events)
    sequences = [int(event["sequence"]) for event in events]
    assert sequences == list(range(1, len(sequences) + 1))
    types = [event["type"] for event in events]
    assert types[0] == "TASK_STARTED"
    assert "GENERATION_STARTED" in types
    assert "TOKEN_DELTA" in types
    assert "DRAFT_READY" in types
    assert "REVIEW_READY" in types
    assert "REVISION_STARTED" in types
    assert "REVISION_READY" in types
    assert types[-1] == "HUMAN_REVIEW_REQUIRED"
    terminal_data = json.loads(events[-1]["data"])
    token_text = "".join(
        json.loads(event["data"])["text"]
        for event in events
        if event["type"] == "TOKEN_DELTA"
    )
    assert terminal_data["content"]
    assert token_text.endswith(terminal_data["content"])
    assert terminal_data["review"]["totalScore"] >= 82
    assert [
        item["versionNo"]
        for item in terminal_data["artifacts"]
        if item["artifactType"] == "CHAPTER_CONTENT"
    ] == [1, 2]
    assert (await redis.xpending(broker.command_stream, broker.consumer_group))[
        "pending"
    ] == 0

    thread_id = events[-1]["threadId"]
    await broker.enqueue(generate)
    await worker.run_once(block_ms=None)
    replay = (await redis.xrange(broker.event_stream))[-1][1]
    assert replay["type"] == "HUMAN_REVIEW_REQUIRED"
    assert replay["threadId"] == thread_id
    assert int(replay["sequence"]) > sequences[-1]

    finalize = make_command(
        "FINALIZE",
        task_id="chapter-finalize",
        idempotency_key="chapter-finalize-key",
        thread_id=thread_id,
        payload={"approved": True, "notes": ""},
    )
    await broker.enqueue(finalize)
    await worker.run_once(block_ms=None)
    final_events = [
        fields
        for _event_id, fields in await redis.xrange(broker.event_stream)
        if fields["taskId"] == "chapter-finalize"
    ]
    assert final_events[-1]["type"] == "FINAL_READY"
    final_data = json.loads(final_events[-1]["data"])
    assert final_data["summary"]
    assert final_data["memoryUpdate"]
    await redis.aclose()


@pytest.mark.asyncio
async def test_finalize_with_fresh_retry_key_replays_completed_thread() -> None:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    broker = ChapterRedisBroker(redis, consumer_name="completed-finalize-retry")
    idempotency = IdempotencyStore(redis, prefix="story:chapter:idempotency")
    workflow = ChapterWorkflowService(build_chapter_graph())
    thread_id = "completed-finalize-thread"
    generated = await workflow.start(
        make_command(
            "GENERATE",
            task_id="original-generate",
            idempotency_key="original-generate-key",
            thread_id=thread_id,
            payload={**chapter_context(), "chapterPlan": chapter_plan()},
        )
    )
    assert generated.status.value == "REVIEW_REQUIRED"
    completed = await workflow.finalize(
        make_command(
            "FINALIZE",
            task_id="original-finalize",
            idempotency_key="original-finalize-key",
            thread_id=thread_id,
            payload={"approved": True, "notes": ""},
        )
    )
    assert completed.status.value == "COMPLETED"
    model_call_count = len(completed.model_calls)

    retry = make_command(
        "FINALIZE",
        task_id="retried-finalize",
        idempotency_key="fresh-finalize-retry-key",
        thread_id=thread_id,
        payload={"approved": True, "notes": ""},
    )
    await broker.enqueue(retry)
    worker = ChapterWorkflowWorker(
        broker=broker,
        idempotency=idempotency,
        workflow=workflow,
    )
    assert await worker.run_once(block_ms=None) == 1

    events = [
        fields
        for _event_id, fields in await redis.xrange(broker.event_stream)
        if fields["taskId"] == "retried-finalize"
    ]
    assert [event["type"] for event in events] == ["TASK_STARTED", "FINAL_READY"]
    final_data = json.loads(events[-1]["data"])
    assert final_data["content"] == completed.final_content
    assert final_data["summary"] == completed.chapter_summary.model_dump(
        mode="json", by_alias=True
    )
    assert len((await workflow.get(thread_id)).model_calls) == model_call_count
    assert not await workflow.operation_applied(thread_id, retry.idempotency_key)
    assert await idempotency.completed_result(retry.idempotency_key) is not None
    assert (await redis.xpending(broker.command_stream, broker.consumer_group))[
        "pending"
    ] == 0
    await redis.aclose()


@pytest.mark.asyncio
async def test_finalize_with_fresh_key_resumes_failed_post_approval_checkpoint() -> (
    None
):
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    broker = ChapterRedisBroker(redis, consumer_name="failed-finalize-retry")
    summary = FailsFirstApprovalSummary()
    workflow = ChapterWorkflowService(build_chapter_graph(summary_agent=summary))
    worker = ChapterWorkflowWorker(
        broker=broker,
        idempotency=IdempotencyStore(
            redis,
            prefix="story:chapter:idempotency",
        ),
        workflow=workflow,
        max_attempts=1,
    )
    generate = make_command(
        "GENERATE",
        task_id="retryable-finalize-generate",
        idempotency_key="retryable-finalize-generate-key",
        payload={**chapter_context(), "chapterPlan": chapter_plan()},
    )
    await broker.enqueue(generate)
    assert await worker.run_once(block_ms=None) == 1
    generated = [
        fields
        for _event_id, fields in await redis.xrange(broker.event_stream)
        if fields["taskId"] == generate.task_id
    ][-1]
    thread_id = generated["threadId"]

    first_finalize = make_command(
        "FINALIZE",
        task_id="retryable-finalize-first",
        idempotency_key="retryable-finalize-first-key",
        thread_id=thread_id,
        payload={"approved": True, "notes": ""},
    )
    await broker.enqueue(first_finalize)
    assert await worker.run_once(block_ms=None) == 1
    first_terminal = [
        fields
        for _event_id, fields in await redis.xrange(broker.event_stream)
        if fields["taskId"] == first_finalize.task_id
    ][-1]
    assert first_terminal["type"] == "TASK_FAILED"
    interrupted = await workflow.get(thread_id)
    assert interrupted.status is ChapterRunStatus.RUNNING
    assert first_finalize.idempotency_key in interrupted.processed_operation_keys

    retry = make_command(
        "FINALIZE",
        task_id="retryable-finalize-second",
        idempotency_key="retryable-finalize-second-key",
        thread_id=thread_id,
        payload={"approved": True, "notes": ""},
    )
    await broker.enqueue(retry)
    assert await worker.run_once(block_ms=None) == 1
    retried_terminal = [
        fields
        for _event_id, fields in await redis.xrange(broker.event_stream)
        if fields["taskId"] == retry.task_id
    ][-1]
    assert retried_terminal["type"] == "FINAL_READY", retried_terminal["errorMessage"]
    assert (await workflow.get(thread_id)).status is ChapterRunStatus.COMPLETED
    await redis.aclose()


@pytest.mark.asyncio
async def test_rewrite_selection_returns_version_safe_proposal() -> None:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    broker = ChapterRedisBroker(redis, consumer_name="rewrite-test")
    worker = ChapterWorkflowWorker(
        broker=broker,
        idempotency=IdempotencyStore(redis, prefix="story:chapter:idempotency"),
        workflow=ChapterWorkflowService(build_chapter_graph()),
    )
    selected = "她按住证据，没有后退。"
    command = make_command(
        "REWRITE_SELECTION",
        task_id="rewrite-task",
        idempotency_key="rewrite-key",
        payload={
            "chapterVersionId": 3,
            "startOffset": 20,
            "endOffset": 20 + len(selected),
            "selectedText": selected,
            "selectedTextHash": hashlib.sha256(selected.encode()).hexdigest(),
            "action": "ENHANCE_CONFLICT",
            "customInstruction": "增加具体阻力",
        },
    )
    await broker.enqueue(command)
    await worker.run_once(block_ms=None)
    final = (await redis.xrange(broker.event_stream))[-1][1]
    assert final["type"] == "REWRITE_PROPOSAL_READY"
    proposal = json.loads(final["data"])
    assert proposal["chapterVersionId"] == 3
    assert proposal["originalText"] == selected
    assert proposal["selectedTextHash"] == hashlib.sha256(selected.encode()).hexdigest()
    assert proposal["replacementText"] != selected
    await redis.aclose()


@pytest.mark.asyncio
async def test_failed_command_stays_pending_and_xautoclaim_recovers_it() -> None:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    failed_broker = ChapterRedisBroker(redis, consumer_name="failed-chapter-worker")
    command = make_command(
        "GENERATE",
        task_id="recover-chapter",
        idempotency_key="recover-chapter-key",
        payload={**chapter_context(), "chapterPlan": chapter_plan()},
    )
    await failed_broker.enqueue(command)
    failed = ChapterWorkflowWorker(
        broker=failed_broker,
        idempotency=IdempotencyStore(redis, prefix="story:chapter:idempotency"),
        workflow=TemporarilyFailingWorkflow(),  # type: ignore[arg-type]
    )
    await failed.run_once(block_ms=None)
    assert (
        await redis.xpending(
            failed_broker.command_stream,
            failed_broker.consumer_group,
        )
    )["pending"] == 1
    retrying = (await redis.xrange(failed_broker.event_stream))[-1][1]
    assert retrying["type"] == "TASK_RETRYING"
    assert retrying["status"] == "RUNNING"
    assert retrying["errorCode"] == "CHAPTER_EXECUTION_FAILED"
    assert json.loads(retrying["data"]) == {
        "attemptNo": 1,
        "maxAttempts": 3,
        "nextAttemptNo": 2,
    }
    assert not await redis.exists("story:chapter:idempotency:lock:recover-chapter-key")

    recovered_broker = ChapterRedisBroker(redis, consumer_name="healthy-chapter-worker")
    recovered = ChapterWorkflowWorker(
        broker=recovered_broker,
        idempotency=IdempotencyStore(redis, prefix="story:chapter:idempotency"),
        workflow=ChapterWorkflowService(build_chapter_graph()),
    )
    assert await recovered.recover_once(min_idle_ms=0) == 1
    assert (
        await redis.xpending(
            recovered_broker.command_stream,
            recovered_broker.consumer_group,
        )
    )["pending"] == 0
    final = (await redis.xrange(recovered_broker.event_stream))[-1][1]
    assert final["type"] == "HUMAN_REVIEW_REQUIRED"
    assert (
        json.loads(
            [
                event
                for _id, event in await redis.xrange(recovered_broker.event_stream)
                if event["type"] == "TASK_STARTED"
            ][-1]["data"]
        )["attemptNo"]
        >= 2
    )
    await redis.aclose()


@pytest.mark.asyncio
async def test_retry_budget_emits_terminal_failure_and_acknowledges() -> None:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    broker = ChapterRedisBroker(redis, consumer_name="terminal-failure-worker")
    idempotency = IdempotencyStore(redis, prefix="story:chapter:idempotency")
    workflow = AlwaysFailingWorkflow()
    worker = ChapterWorkflowWorker(
        broker=broker,
        idempotency=idempotency,
        workflow=workflow,  # type: ignore[arg-type]
    )
    command = make_command(
        "GENERATE",
        task_id="terminal-failure",
        idempotency_key="terminal-failure-key",
        payload={**chapter_context(), "chapterPlan": chapter_plan()},
    )
    await broker.enqueue(command)

    assert await worker.run_once(block_ms=None) == 1
    assert await worker.recover_once(min_idle_ms=0) == 1
    assert await worker.recover_once(min_idle_ms=0) == 1

    pending = await redis.xpending(broker.command_stream, broker.consumer_group)
    assert pending["pending"] == 0
    events = [fields for _id, fields in await redis.xrange(broker.event_stream)]
    retry_events = [event for event in events if event["type"] == "TASK_RETRYING"]
    assert [json.loads(event["data"])["attemptNo"] for event in retry_events] == [
        1,
        2,
    ]
    terminal = events[-1]
    assert terminal["type"] == "TASK_FAILED"
    assert terminal["status"] == "FAILED"
    assert terminal["errorCode"] == "CHAPTER_EXECUTION_FAILED"
    assert json.loads(terminal["data"]) == {
        "attemptNo": 3,
        "maxAttempts": 3,
    }
    assert await idempotency.completed_result("terminal-failure-key") is None
    assert workflow.start_calls == 3

    # The exhausted stream entry was ACKed, while a deliberate new delivery can
    # reuse the operation key and recover after the provider becomes healthy.
    await broker.enqueue(command)
    recovered = ChapterWorkflowWorker(
        broker=broker,
        idempotency=idempotency,
        workflow=ChapterWorkflowService(build_chapter_graph()),
    )
    assert await recovered.run_once(block_ms=None) == 1
    assert workflow.start_calls == 3
    assert (await redis.xrange(broker.event_stream))[-1][1]["type"] == (
        "HUMAN_REVIEW_REQUIRED"
    )
    assert await idempotency.completed_result("terminal-failure-key") is not None
    assert (await redis.xpending(broker.command_stream, broker.consumer_group))[
        "pending"
    ] == 0
    await redis.aclose()


@pytest.mark.asyncio
async def test_permanent_workflow_conflict_fails_without_retry() -> None:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    broker = ChapterRedisBroker(redis, consumer_name="conflict-worker")
    worker = ChapterWorkflowWorker(
        broker=broker,
        idempotency=IdempotencyStore(
            redis,
            prefix="story:chapter:idempotency",
        ),
        workflow=ConflictingWorkflow(),  # type: ignore[arg-type]
        max_attempts=5,
    )
    await broker.enqueue(
        make_command(
            "GENERATE",
            task_id="conflict-task",
            idempotency_key="conflict-key",
            payload={**chapter_context(), "chapterPlan": chapter_plan()},
        )
    )

    assert await worker.run_once(block_ms=None) == 1
    assert (await redis.xpending(broker.command_stream, broker.consumer_group))[
        "pending"
    ] == 0
    terminal = (await redis.xrange(broker.event_stream))[-1][1]
    assert terminal["type"] == "TASK_FAILED"
    assert terminal["errorCode"] == "CHAPTER_WORKFLOW_STATE_CONFLICT"
    assert json.loads(terminal["data"]) == {
        "attemptNo": 1,
        "maxAttempts": 5,
    }
    await redis.aclose()


@pytest.mark.asyncio
async def test_terminal_ack_failure_replays_without_extra_execution() -> None:
    redis = fakeredis.aioredis.FakeRedis(decode_responses=True)
    broker = AckFailsOnceChapterBroker(
        redis,
        consumer_name="terminal-ack-worker",
    )
    workflow = AlwaysFailingWorkflow()
    worker = ChapterWorkflowWorker(
        broker=broker,
        idempotency=IdempotencyStore(
            redis,
            prefix="story:chapter:idempotency",
        ),
        workflow=workflow,  # type: ignore[arg-type]
        max_attempts=1,
    )
    await broker.enqueue(
        make_command(
            "GENERATE",
            task_id="terminal-ack-task",
            idempotency_key="terminal-ack-key",
            payload={**chapter_context(), "chapterPlan": chapter_plan()},
        )
    )

    assert await worker.run_once(block_ms=None) == 1
    assert (await redis.xpending(broker.command_stream, broker.consumer_group))[
        "pending"
    ] == 1
    assert workflow.start_calls == 1

    assert await worker.recover_once(min_idle_ms=0) == 1
    assert (await redis.xpending(broker.command_stream, broker.consumer_group))[
        "pending"
    ] == 0
    assert await redis.xlen(broker.command_stream) == 0
    assert workflow.start_calls == 1
    failures = [
        event
        for _id, event in await redis.xrange(broker.event_stream)
        if event["type"] == "TASK_FAILED"
    ]
    assert len(failures) == 2
    await redis.aclose()
