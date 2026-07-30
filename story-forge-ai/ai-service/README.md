# Story Forge AI Service

FastAPI service for the first-week Story Forge MVP. It turns a creative
direction into exactly ten structured short-drama topics and scores each topic
on conflict, reversal, emotional value, and short-drama fit.

## Run locally

Python 3.11+ is required.

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt
cp .env.example .env
uvicorn main:app --reload
```

Environment files are not loaded implicitly. Export values from `.env` with
your process manager (or `set -a; source .env; set +a`) when remote LLM access
is wanted. With no `OPENAI_API_KEY`, the service deliberately uses the
deterministic `local-template` provider, so the entire MVP remains demoable
without a key.

Interactive API documentation is available at <http://localhost:8000/docs>.

## API

### `GET /health`

```json
{"status": "ok"}
```

### `POST /ai/topic/generate`

Request (`keywords` can also be a comma-delimited string; `storyId` is
optional):

```json
{
  "genre": "都市情感",
  "audience": "女性",
  "keywords": ["复仇"],
  "storyId": 10001
}
```

Response (abbreviated; `topics` always contains exactly 10 items):

```json
{
  "topics": [
    {
      "id": 1,
      "title": "离婚当天，我继承了复仇帝国",
      "hook": "签字现场遭到羞辱，下一秒失踪多年的继承人身份公开。",
      "summary": "……",
      "score": 89,
      "scoreReasons": {
        "conflict": {"score": 92, "reason": "……"},
        "reversal": {"score": 91, "reason": "……"},
        "emotionalValue": {"score": 86, "reason": "……"},
        "shortDramaFit": {"score": 88, "reason": "……"}
      },
      "tags": ["都市情感", "复仇", "离婚", "继承", "逆袭"]
    }
  ],
  "model": "local-template",
  "generatedAt": "2026-07-30T06:00:00Z"
}
```

All scores are normalized to the inclusive range 0–100. Invalid request data
returns FastAPI's HTTP 422 validation response.

## Provider and fallback behavior

- No API key: use the deterministic local templates and return
  `model: "local-template"`.
- API key configured: call `${OPENAI_BASE_URL}/chat/completions` with the model
  selected by `OPENAI_MODEL`.
- The remote response is accepted only when it is plain JSON matching the exact
  ten-topic schema. Markdown-wrapped JSON, missing/extra fields, or a provider
  error is treated as a failure.
- With `OPENAI_FALLBACK_ENABLED=true` (default), failures fall back to local
  templates and still report `model: "local-template"`; the response never
  masquerades as an LLM result.
- With fallback disabled, failures return HTTP 503 with error code
  `TOPIC_GENERATION_UNAVAILABLE`.

See [`docs/prompts.md`](docs/prompts.md) for the prompt and agent responsibility
split.

## Agent architecture boundary

`TopicAgent` depends only on the small `TopicProvider` protocol and hands every
validated candidate to `ScoreAgent`. The first-week flow intentionally uses
plain Python orchestration so its behavior stays obvious and testable.
LangChain and LangGraph are installed as requested and are available for later
multi-step character or outline graphs; either can replace the orchestration
behind these interfaces without changing the HTTP contract or provider models.

## Test and lint

```bash
pytest
ruff check .
```

## Docker

```bash
docker build -t story-forge-ai-service .
docker run --rm -p 8000:8000 story-forge-ai-service
```
