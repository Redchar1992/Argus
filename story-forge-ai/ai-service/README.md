# Story Forge AI Service

FastAPI + LangGraph service for the Story Forge MVP.

- **Week one remains stable:** generate exactly ten structured topics with
  `POST /ai/topic/generate`.
- **Week two adds one stateful workflow:** selected topic → 3–6 characters →
  exactly 20 outline nodes → five-dimension score → at most two automatic
  revisions → interruptible human review → approved final artifact.
- Every character, outline, score, revision, prompt/model identity, token count,
  duration, and progress event is structured and retained.

No API key is required for local development. The disclosed
`local-workflow-template` produces deterministic, schema-valid demo data.

## Run locally

Python 3.11+ is required.

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt
cp .env.example .env
set -a; source .env; set +a
uvicorn main:app --reload
```

Start Redis and the asynchronous worker in a second process:

```bash
python -m app.workers.story_worker
```

Interactive API documentation is available at <http://localhost:8000/docs>.

## HTTP API

### Health and week-one topic generation

```http
GET /health
POST /ai/topic/generate
```

The week-one topic response still contains ten items with `scoreReasons`; that
complete item can be passed directly to the week-two start endpoint. Unknown
future scoring fields are ignored by `SelectedTopic` rather than breaking an
otherwise valid workflow.

### Start a workflow

```http
POST /ai/workflow/start
Content-Type: application/json

{
  "taskId": "task-10001",
  "storyId": 5001,
  "topic": {
    "id": 1,
    "title": "离婚当天，我继承百亿集团",
    "hook": "签字现场遭到羞辱，隐藏继承人身份随即公开。",
    "summary": "女主保全证据并夺回人生选择权。",
    "score": 89,
    "scoreReasons": {},
    "tags": ["都市情感", "复仇"]
  },
  "maxRevisions": 2
}
```

The call runs until `interrupt()` and returns `REVIEW_REQUIRED`. Important
response fields include:

```json
{
  "threadId": "3af4...",
  "status": "REVIEW_REQUIRED",
  "currentNode": "human_review",
  "revisionCount": 1,
  "maxRevisions": 2,
  "characters": [],
  "outline": [],
  "score": {"total": 84, "level": "A"},
  "artifacts": [],
  "modelCalls": [],
  "progressEvents": []
}
```

### Inspect and resume the same thread

```http
GET /ai/workflow/{threadId}

POST /ai/workflow/{threadId}/resume
{"approved": true, "notes": ""}
```

To request a revision:

```json
{
  "approved": false,
  "notes": "节点12缺少前置动机，请提前铺垫妹妹与反派的利益关系。"
}
```

Rejection resumes the same `threadId`, creates a complete new outline version,
scores it, and pauses again. Approval appends a `WORKFLOW_FINAL` artifact and
returns `COMPLETED`. Unknown threads return HTTP 404; attempting to resume a
completed or non-paused thread returns HTTP 409.

## Enforced content contracts

Pydantic plus application-owned validation rejects:

- casts outside 3–6 characters, duplicate names, missing/excess protagonists,
  or a missing antagonist;
- outlines that do not contain exactly nodes 1–20 in order, contain fewer than
  four twists, lack conflict in the first three nodes, or lack an emotional
  ending;
- any score dimension outside 0–20.

The model never supplies the total. Application code adds the five dimensions
and assigns `S/A/B/C`. A score below 80 triggers revision only while
`revisionCount < maxRevisions`, preventing an infinite loop. Old `OUTLINE` and
`SCORE` artifacts are append-only (`versionNo` 1, 2, 3, …).

## Models and fallback

- No `OPENAI_API_KEY`: both creative and review agents use
  `local-workflow-template`.
- With a key: Chat Completions is called at
  `${OPENAI_BASE_URL}/chat/completions` using strict Pydantic JSON Schema.
- Creative calls use `OPENAI_CREATIVE_MODEL` at temperature `0.7`.
- Scoring uses `OPENAI_REVIEW_MODEL` at temperature `0.1`.
- Invalid JSON, schema violations, timeouts, and HTTP failures fall back to the
  local model when `OPENAI_FALLBACK_ENABLED=true`; fallback model identity is
  recorded rather than masquerading as a remote result.

Prompt files are versioned under `app/prompts/*_v1.txt`. Each model call records
the node, model, prompt version, input/output token counts, duration, success,
and error.

## Redis Streams contract

The recommended backend integration is asynchronous:

```text
story:workflow:requests  -> Python consumer group
story:workflow:events    <- progress/result events
```

Request fields are Redis strings:

```text
taskId storyId threadId action payloadVersion idempotencyKey
topic approved notes
```

`topic` is a JSON object. `approved` is `true`, `false`, or empty. Event fields
are:

```text
taskId storyId threadId status currentNode progress attemptNo
revisionCount maxRevisions idempotencyKey artifacts progressEvents
inputTokens outputTokens modelName promptVersion durationMs modelCalls
errorCode errorMessage
```

`artifacts`, `progressEvents`, and `modelCalls` are JSON arrays. Artifact objects
use exactly:

```text
artifactType versionNo status content promptVersion modelName
```

The worker publishes `RUNNING` after each character/outline/score/revision
node, then `REVIEW_REQUIRED`, `SUCCESS`, or `FAILED`. It uses:

1. a short Redis processing lock;
2. a completed-result idempotency key;
3. `XREADGROUP` for new work;
4. `XAUTOCLAIM` for abandoned pending entries;
5. `XACK` only after the result event and idempotency result are saved.

Malformed poison messages are failed with `INVALID_WORKFLOW_MESSAGE` and ACKed;
retryable execution failures remain pending. A deterministic thread ID closes
the normal redelivery window for START messages. Applied RESUME operation keys
are also checkpointed, so a lost completed marker cannot apply the same review
decision twice. If terminal publication and marker persistence succeed but
`XACK` fails, redelivery replays the stored terminal event before ACK and never
publishes a later false `FAILED` state.

## Persistence boundary

The graph intentionally uses `InMemorySaver` for this local, single-process MVP.
It supports pause/resume while the service stays alive, but process restarts
lose graph checkpoints. Production must inject a database-backed LangGraph
checkpointer before relying on cross-restart resume; Redis Streams alone does
not replace checkpoint persistence.

## Test, lint, and Docker

```bash
pytest
ruff check .
docker build -t story-forge-ai-service .
docker run --rm -p 8000:8000 story-forge-ai-service
```

Tests cover week-one compatibility, schemas, automatic revision limits,
interrupt/resume/approval/rejection, HTTP behavior, remote structured output,
Redis exact fields, duplicate delivery, ACK ordering, and `XAUTOCLAIM`
recovery.
