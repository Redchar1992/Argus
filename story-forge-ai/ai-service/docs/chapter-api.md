# Chapter worker Redis API

The chapter worker is an internal asynchronous adapter. Spring writes commands
to `story:chapter:commands`; Python writes ordered events to
`story:chapter:events`. All Redis field values are strings. Nested values use
camelCase JSON.

## Start the worker

```bash
python -m app.workers.chapter_worker
```

Runtime settings:

| Variable | Default |
| --- | --- |
| `REDIS_URL` | `redis://localhost:6379/0` |
| `CHAPTER_COMMAND_STREAM` | `story:chapter:commands` |
| `CHAPTER_EVENT_STREAM` | `story:chapter:events` |
| `CHAPTER_CONSUMER_GROUP` | `story-chapter-workers` |
| `CHAPTER_CONSUMER_NAME` | hostname plus PID |
| `CHAPTER_EVENT_MAXLEN` | `100000` |
| `CHAPTER_CHECKPOINT_DB` | `/data/chapter-checkpoints.sqlite` |

`/data` must be a persistent, writable volume. SQLite is the LangGraph
checkpointer, not the formal story database.

## Command fields

Every command has exactly these Redis fields:

```text
taskId storyId chapterId chapterNo action threadId idempotencyKey payload
```

`action` is one of `PLAN`, `GENERATE`, `REWRITE_SELECTION`, or `FINALIZE`.
`idempotencyKey` identifies one semantic operation. Redelivery with the same
key replays its stored terminal event without another model call. For `PLAN`,
`GENERATE`, and `REWRITE_SELECTION`, `threadId` may be empty; the worker derives
a stable UUID from the idempotency key. `FINALIZE` must use the `threadId`
returned by `GENERATE`.

### PLAN

```json
{
  "taskId": "chapter-plan-1001",
  "storyId": "5001",
  "chapterId": "7001",
  "chapterNo": "1",
  "action": "PLAN",
  "threadId": "",
  "idempotencyKey": "chapter:7001:plan:v1",
  "payload": {
    "storyTitle": "离婚当天，我继承百亿集团",
    "genre": "都市情感",
    "targetAudience": "女性",
    "styleProfile": {"tone": "克制、紧凑"},
    "characters": [{"name": "林晚", "role": "主角"}],
    "canonFacts": [],
    "relationshipStates": [],
    "recentSummaries": [],
    "unresolvedThreads": [],
    "foreshadowingLedger": [],
    "outlineNodes": [
      {"nodeNo": 1, "event": "本章事件一", "protagonistGoal": "本章目标一"},
      {"nodeNo": 2, "event": "本章事件二", "protagonistGoal": "本章目标二"}
    ],
    "currentOutlineNodes": [
      {"nodeNo": 1, "event": "本章事件一", "protagonistGoal": "本章目标一"},
      {"nodeNo": 2, "event": "本章事件二", "protagonistGoal": "本章目标二"}
    ],
    "targetLength": 1200,
    "maxRevisions": 2
  }
}
```

The real character cards and outline nodes keep all approved Week-2 fields. The
abbreviated objects above only illustrate the envelope. Spring maps chapter
`N` to approved outline indexes `2N-2` and `2N-1`; both keys contain exactly
that same pair. `outlineNodes` is retained for wire compatibility, while
`currentOutlineNodes` states the bounded contract explicitly. The worker also
accepts a legacy full `outlineNodes` array and performs the same mapping, but it
rejects a missing pair before any model call. The planner rejects unknown scene
characters, cites both current events and protagonist goals in `chapterGoal`
and the scenes, and produces exactly 3–6 contiguous scenes.

Terminal event: `CHAPTER_PLAN_READY` with `data.plan`.

### GENERATE

Use the same bounded context and add the human-approved plan:

```json
{
  "chapterPlan": {
    "chapterTitle": "第一章 失控的证据",
    "chapterGoal": "公开异常记录并改变人物关系",
    "openingHook": "异常记录突然占满会议屏幕",
    "endingHook": "收款人竟是主角最信任的人",
    "targetLength": 1200,
    "scenes": []
  }
}
```

The full `ChapterPlan` contains 3–6 `scenes`. The worker streams ordinary text,
validates length/paragraphs/duplicates, performs a six-dimension review, and
revises while score `< 82` or fatal/mechanical problems exist, with at most two
automatic revisions.

Terminal event: `HUMAN_REVIEW_REQUIRED` with:

```json
{
  "plan": {},
  "content": "完整当前正文",
  "review": {"totalScore": 86, "fatalProblems": []},
  "mechanicalErrors": [],
  "revisionCount": 1,
  "artifacts": [],
  "modelCalls": []
}
```

Every `CHAPTER_CONTENT` artifact is append-only and identifies `versionNo` and
`sourceType` (`AI_DRAFT` or `AI_REVISION`). Spring persists each artifact as an
immutable MySQL chapter version.

### FINALIZE

Approve the current user-edited content:

```json
{
  "approved": true,
  "notes": "",
  "currentContent": "浏览器中最终确认的完整正文",
  "baseVersionId": 3002
}
```

`currentContent` and `baseVersionId` are optional at the AI boundary; Spring
must still enforce optimistic version checks before submitting. Approval runs
summary and memory extraction. Terminal `FINAL_READY` data contains:

```json
{
  "plan": {},
  "content": "批准正文",
  "review": {},
  "summary": {},
  "memoryUpdate": {},
  "revisionCount": 1,
  "artifacts": [],
  "modelCalls": []
}
```

Reject for one targeted revision:

```json
{
  "approved": false,
  "notes": "保留场景1和3，加强场景2中的现实阻力"
}
```

The same thread revises once, reviews again, and emits another
`HUMAN_REVIEW_REQUIRED`. Human-requested revisions do not create an unbounded
automatic quality loop.

### REWRITE_SELECTION

```json
{
  "chapterVersionId": 3002,
  "startOffset": 520,
  "endOffset": 531,
  "selectedText": "她按住证据，没有后退。",
  "selectedTextHash": "sha256-lowercase-hex",
  "action": "ENHANCE_CONFLICT",
  "customInstruction": "增加对手的具体阻止行动",
  "context": {}
}
```

The worker validates that `endOffset - startOffset == len(selectedText)` and
that the SHA-256 hash matches before spending a model call. It never applies a
replacement. `REWRITE_PROPOSAL_READY` returns:

```json
{
  "chapterVersionId": 3002,
  "originalText": "原选中文本",
  "replacementText": "AI建议文本",
  "reason": "具体修改理由",
  "selectedTextHash": "原sha256",
  "modelCalls": []
}
```

Spring must reject proposal acceptance when either the current version ID or
selected-text hash has changed.

## Event fields and ordering

Every event has exactly these Redis fields:

```text
taskId storyId chapterId chapterNo threadId type sequence status
currentNode progress idempotencyKey data errorCode errorMessage
```

Redis assigns the stream entry ID; Spring exposes it as the SSE `id`. `sequence`
is generated with Redis `INCR` per `taskId` and is strictly increasing. The SSE
adapter may use both ID and sequence to deduplicate reconnects.

Event types:

```text
TASK_STARTED
CONTEXT_LOADED
CHAPTER_PLAN_READY
GENERATION_STARTED
TOKEN_DELTA
DRAFT_READY
REVIEW_READY
REVISION_STARTED
REVISION_READY
HUMAN_REVIEW_REQUIRED
SUMMARY_READY
MEMORY_UPDATE_READY
REWRITE_PROPOSAL_READY
FINAL_READY
TASK_FAILED
```

`TOKEN_DELTA` uses:

```json
{"text": "林晚盯着那张转账记录，", "phase": "chapter_write"}
```

`phase` is `chapter_write` or `chapter_revision`. Token deltas are transient UI
transport. `DRAFT_READY`, `REVISION_READY`, and terminal events always contain
the complete content so reconnecting clients can replace a partial buffer.
Spring/MySQL must save complete versions; Redis trimming is allowed and events
are never the canonical正文.

Statuses are `RUNNING`, `REVIEW_REQUIRED`, `SUCCESS`, or `FAILED`. Poison
commands emit `TASK_FAILED` with `INVALID_CHAPTER_COMMAND` and are ACKed.
Retryable execution or provider failures remain pending for `XAUTOCLAIM`.
`XACK` happens only after the terminal event and completed-result marker are
written.

## Long-memory safety

Only approval triggers `ChapterSummary` and `MemoryUpdate`. The latter contains
`newFacts`, relationship changes, opened/updated/resolved plot threads,
foreshadowing setup/payoff, character state changes, and continuity warnings.
Before emitting it, application code filters every `newFact` whose `factKey`
would overwrite a locked fact and appends a deterministic warning while
preserving model warnings. Spring validates and applies the remaining delta in
the same transaction that approves the formal chapter version.
