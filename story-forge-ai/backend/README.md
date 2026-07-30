# Story Forge AI Backend

Spring Boot 3 / Java 17 backend for the first-week Story Forge MVP. It provides JWT
authentication, per-user story persistence, and synchronous orchestration of the
FastAPI topic-generation service.

## Scope

- Public health check
- Registration and login with BCrypt + JWT
- Create, list, and reopen stories
- Generate topics through the AI service and persist every task/result
- Select a generated topic by ID
- Unified JSON errors, CORS, and ownership checks
- H2 local profile with no MySQL or Redis process required
- MySQL profile for deployment

Redis is included as a project dependency for later work, but the first-week flow
does not connect to Redis.

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

Flyway creates `sys_user`, `story_project`, and `ai_task`.

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
