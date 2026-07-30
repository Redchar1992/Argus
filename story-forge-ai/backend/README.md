# Story Forge AI Backend

Spring Boot 3 / Java 17 backend for the two-week Story Forge MVP. It provides JWT
authentication, per-user persistence, synchronous topic generation, and an
asynchronous Redis Streams workflow for characters, outlines, scoring, and
human review.

## Scope

- Public health check
- Registration and login with BCrypt + JWT
- Create, list, and reopen stories
- Generate topics through the AI service and persist every task/result
- Select a generated topic by ID
- Start, poll, review, and resume the week-two story workflow
- Version characters, outlines, scores, and final workflow artifacts
- Redis Streams request publishing and transactional event persistence
- Unified JSON errors, CORS, and ownership checks
- H2 local profile with no MySQL or Redis process required
- MySQL profile for deployment

Redis is included as a project dependency for later work, but the first-week flow
does not connect to Redis. The week-two async workflow enables Redis explicitly
with `WORKFLOW_REDIS_ENABLED=true`; the default remains disabled so the local H2
profile can still start with no external services.

## Run locally

Requirements: JDK 17 and Maven 3.9+.

```bash
cd backend
export JAVA_HOME=$(/usr/libexec/java_home -v 17) # macOS when multiple JDKs exist
mvn spring-boot:run
```

The default `local` profile uses an in-memory H2 database and applies the Flyway
migration automatically. Data is reset when the process stops.

```bash
curl http://localhost:8080/api/health
# {"status":"ok"}
```

The AI service defaults to `http://localhost:8000`. Override it when needed:

```bash
AI_SERVICE_URL=http://localhost:8000 mvn spring-boot:run
```

## Run with MySQL

Create an empty `story_forge` database, then run:

```bash
SPRING_PROFILES_ACTIVE=mysql \
MYSQL_URL='jdbc:mysql://localhost:3306/story_forge?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' \
MYSQL_USERNAME=root \
MYSQL_PASSWORD=change-me \
JWT_SECRET="$(openssl rand -hex 32)" \
mvn spring-boot:run
```

Flyway creates `sys_user`, `story_project`, `ai_task`, and versioned
`story_artifact` records.

## API

All request and response bodies use JSON. Protected endpoints require:

```text
Authorization: Bearer <token>
```

### Health

```http
GET /api/health
```

### Authentication

```http
POST /api/auth/register
Content-Type: application/json

{"username":"demo-user","password":"password123"}
```

```http
POST /api/auth/login
Content-Type: application/json

{"username":"demo-user","password":"password123"}
```

Both successful responses contain:

```json
{"token":"<jwt>","userId":1}
```

### Stories

```http
POST /api/story/create

{
  "title": "离婚之后",
  "genre": "都市情感",
  "audience": "女性",
  "keywords": "复仇"
}
```

```http
GET /api/story/list
GET /api/story/{id}
```

The story response includes the structured `generatedTopics` and `selectedTopic`
values, so a previously generated result can be reopened.

### Topic generation

```http
POST /api/ai/topic/generate

{
  "storyId": 1,
  "genre": "都市情感",
  "audience": "女性",
  "keywords": "复仇"
}
```

The backend verifies ownership, creates an `ai_task`, calls
`POST {AI_SERVICE_URL}/ai/topic/generate`, and returns the AI JSON with backend
`taskId` and `storyId` added at the top level. The upstream `topics`, `model`, and
`generatedAt` fields are preserved. The topics array is also saved on the story.

If the AI service is unavailable or returns an invalid shape, the API responds
with HTTP `502` and persists both a failed task and the story's
`GENERATION_FAILED` status; it never returns fabricated topics.

### Select a topic

Numeric and string IDs are treated equivalently:

```http
PUT /api/story/{id}/selection

{"topicId":"1"}
```

The exact matching generated topic is saved in `selectedTopic`.
Once a workflow task exists, this endpoint returns HTTP `409` with
`WORKFLOW_TOPIC_LOCKED`; it cannot change either the selected topic or story
status.

## Week-two workflow API

The async workflow uses these task states only:

```text
WAITING -> RUNNING -> REVIEW_REQUIRED -> SUCCESS
              \----> FAILED ------> RUNNING / REVIEW_REQUIRED / SUCCESS
```

`REVIEW_REQUIRED` and `SUCCESS` never regress when delayed events arrive. A
`FAILED` delivery remains retryable because Redis pending-message recovery can
resume the same operation.

Start from one of the story's generated topics:

```http
POST /api/stories/{storyId}/workflow

{"topicId":1}
```

The accepted response is `{"taskId":90001,"status":"WAITING"}`. Poll it with:

```http
GET /api/ai-tasks/{taskId}
```

The response contains the immutable original `topicId`, `storyId`, `status`,
`currentNode`, `progress`, `threadId`, `score`, `revisionCount`, `maxRevisions`,
structured `progressEvents`, and explicit error fields. Starting an existing
workflow with another topic returns HTTP `409` and `WORKFLOW_TOPIC_LOCKED`;
numeric and string representations of the same topic ID are equivalent.

After logout or on another browser, rediscover the canonical latest task with:

```http
GET /api/stories/{storyId}/workflow/latest
```

Once status is `REVIEW_REQUIRED`, retrieve the current material and all outline
versions:

```http
GET /api/ai-tasks/{taskId}/review
```

The response preserves the complete outline object (`title`, `coreConflict`,
`endingType`, and `nodes`). It displays only the highest version that has both
an outline and score, returns only same-version pairs in `versions`, and prefers
the approved `WORKFLOW_FINAL` content after success.

Approve or request a revision:

```http
POST /api/ai-tasks/{taskId}/review

{"approved":false,"notes":"请提前铺垫反派的利益动机。"}
```

Every review command creates a new `ai_task` with the same LangGraph `threadId`.
Its deterministic key progresses from `storyId:v1:START:0` to
`storyId:v1:RESUME:1`, `RESUME:2`, and so on, independently of queue-delivery
attempt counts.

## Redis Stream contract

Enable the integration with `WORKFLOW_REDIS_ENABLED=true`. The backend writes to
`story:workflow:requests` using string fields:

```text
taskId, storyId, threadId, action, payloadVersion, idempotencyKey,
topic (JSON), approved, notes
```

It consumes `story:workflow:events` using:

```text
taskId, storyId, threadId, status, currentNode, progress, attemptNo,
revisionCount, maxRevisions, idempotencyKey, artifacts (JSON array),
progressEvents (JSON array), inputTokens, outputTokens, modelName,
promptVersion, durationMs, modelCalls (JSON array), errorCode, errorMessage
```

Each artifact is shaped as:

```json
{
  "artifactType": "OUTLINE",
  "versionNo": 2,
  "status": "REVIEW",
  "content": {},
  "promptVersion": "outline-v1",
  "modelName": "model-name"
}
```

Event persistence and task/story updates run in one database transaction. `XACK`
is issued only after that transaction returns successfully. Stream event IDs and
the unique `(story_id, artifact_type, version_no)` key make redelivery idempotent.
An `XPENDING` + `XCLAIM` recovery loop reclaims events that remain unacknowledged
beyond `WORKFLOW_RECLAIM_IDLE`. Configure its scheduler with the numeric
millisecond value `WORKFLOW_RECLAIM_INTERVAL_MS` (default `10000`).

## Error format

```json
{
  "timestamp": "2026-07-30T06:00:00Z",
  "status": 403,
  "error": "Forbidden",
  "code": "STORY_FORBIDDEN",
  "message": "无权访问该故事",
  "path": "/api/story/1"
}
```

## Tests and package

The integration test uses MockMvc, H2, and a real mock HTTP server for the FastAPI
boundary. It covers authentication, missing JWTs, cross-user access denial, the
full generate/save/reopen/select flow, and AI-service failures.

```bash
mvn test
mvn package
```

## Docker

```bash
docker build -t story-forge-backend .
docker run --rm -p 8080:8080 \
  -e AI_SERVICE_URL=http://host.docker.internal:8000 \
  -e JWT_SECRET="$(openssl rand -hex 32)" \
  story-forge-backend
```
