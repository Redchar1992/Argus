# API 契约

除健康检查、注册和登录外，后端接口都要求：

```http
Authorization: Bearer <token>
```

## 后端 API（浏览器调用）

### 健康检查

```http
GET /api/health
```

```json
{"status":"ok"}
```

### 注册

```http
POST /api/auth/register
Content-Type: application/json

{"username":"demo","password":"demo1234","privacyAccepted":true}
```

`privacyAccepted` 必须为 `true`。注册成功后会保存当前隐私说明版本与确认时间，
并直接返回与登录相同的令牌结构。

### 内测漏斗指标（运营接口）

```http
GET /api/internal/pilot/metrics?days=7
Authorization: Bearer <token>
X-Pilot-Metrics-Key: <PILOT_METRICS_KEY>
```

只有配置了 `PILOT_METRICS_KEY` 后接口才启用；`days` 允许 1～90。响应包含注册与
活跃用户数、服务端记录的关键漏斗事件、AI 任务成功率和模型 Token/成本汇总。

### 登录

```http
POST /api/auth/login
Content-Type: application/json

{"username":"demo","password":"demo1234","privacyAccepted":true}
```

已确认当前版本隐私说明的账号可省略 `privacyAccepted`；历史账号首次登录新版本时
必须传 `true`，否则返回 `PRIVACY_CONSENT_REQUIRED`。

单机内测默认对注册 IP 和登录的 `IP + 用户名` 组合限流；超限返回 HTTP `429`
与错误码 `AUTH_RATE_LIMITED`。

```json
{"token":"<jwt>","userId":10001}
```

### 创建故事

```http
POST /api/story/create
Content-Type: application/json

{
  "title":"都市复仇",
  "genre":"都市情感",
  "audience":"女性",
  "keywords":"复仇, 身份反转"
}
```

### 我的作品

```http
GET /api/story/list
```

只返回当前 JWT 用户的故事。

### 故事详情

```http
GET /api/story/{id}
```

返回故事信息、最新生成结果和已选方案。

### 生成选题

```http
POST /api/ai/topic/generate
Content-Type: application/json

{
  "storyId":1,
  "genre":"都市情感",
  "audience":"女性",
  "keywords":"复仇"
}
```

响应的稳定核心结构：

```json
{
  "topics":[
    {
      "id":1,
      "title":"离婚当天，我继承百亿集团",
      "hook":"净身出户现场，陌生董事会突然集体向她鞠躬",
      "summary":"女主在最低谷得知真实身份，并反击夺走她一切的人。",
      "score":92,
      "scoreReasons":{
        "conflict":{"score":94,"reason":"开场存在明确对抗或生存压力。"},
        "reversal":{"score":92,"reason":"身份变化能够推动剧情二次升级。"},
        "emotionalValue":{"score":91,"reason":"逆袭提供了清晰的观众回报。"},
        "shortDramaFit":{"score":90,"reason":"节点紧凑，可拆分为连续悬念。"}
      },
      "tags":["都市情感","复仇","身份反转"]
    }
  ],
  "model":"local-template",
  "generatedAt":"2026-07-30T12:00:00Z"
}
```

实际响应固定包含 10 个 `topics`。

### 保存选中方案

```http
PUT /api/story/{id}/selection
Content-Type: application/json

{"topicId":1}
```

后端从该故事已持久化的生成结果中选择，客户端不能写入任意伪造方案。
V1 工作流一旦创建，该选题即被锁定；之后再次调用本接口会返回
`409 WORKFLOW_TOPIC_LOCKED`，且不会改变故事选题或状态。

### 启动人物与大纲工作流

故事必须属于当前用户，且 `topicId` 必须对应服务端已保存的选题。

```http
POST /api/stories/{storyId}/workflow
Content-Type: application/json

{"topicId":1}
```

```json
{"taskId":90001,"status":"WAITING"}
```

重复提交同一个启动动作会命中数据库幂等键，不会创建第二套产物版本。若已存在
的 V1 工作流绑定了其他选题，则返回 `409 WORKFLOW_TOPIC_LOCKED`。

### 查询工作流任务

```http
GET /api/ai-tasks/{taskId}
```

核心响应：

```json
{
  "taskId":90001,
  "storyId":5001,
  "topicId":1,
  "status":"REVIEW_REQUIRED",
  "currentNode":"human_review",
  "progress":85,
  "threadId":"7fcb...",
  "score":84,
  "revisionCount":1,
  "maxRevisions":2,
  "progressEvents":[
    {"node":"character","status":"completed","message":"人物设定已生成"}
  ]
}
```

状态只使用 `WAITING`、`RUNNING`、`REVIEW_REQUIRED`、`SUCCESS`、`FAILED`。

故事详情页可在没有浏览器缓存时发现该故事最新的工作流任务：

```http
GET /api/stories/{storyId}/workflow/latest
```

响应与按任务 ID 查询相同；尚未启动过工作流时返回 `404` 和
`WORKFLOW_TASK_NOT_FOUND`。这保证退出登录后仍能重新打开审核中或已完成的方案。

### 获取审核内容

```http
GET /api/ai-tasks/{taskId}/review
```

返回当前人物卡、恰好 20 个大纲节点、五维评分，以及可重新查看的历史版本：

```json
{
  "characters":[],
  "outline":[],
  "score":{"total":84,"level":"A"},
  "versions":[]
}
```

### 提交人工审核

批准：

```http
POST /api/ai-tasks/{taskId}/review
Content-Type: application/json

{"approved":true,"notes":""}
```

要求修改时 `notes` 必填：

```json
{
  "approved":false,
  "notes":"节点12缺乏前置动机，请提前铺垫妹妹与反派的利益关系。"
}
```

审核会恢复原 LangGraph `threadId`。服务端为这次 `RESUME` 留下独立任务记录，
响应中的 `taskId` 可能是新的任务 ID；客户端必须改为轮询响应返回的 ID。

## 第三周章节 API

章节接口只接受已批准人物与大纲的故事。第 2 章及以后还要求上一章已经批准。

### 章节目录与详情

```http
GET /api/stories/{storyId}/chapters
GET /api/stories/{storyId}/chapters/{chapterNo}
GET /api/chapters/{chapterId}
```

章节详情包含计划、计划哈希、状态、当前不可变版本和已批准摘要：

```json
{
  "id":701,
  "storyId":5001,
  "chapterNo":1,
  "title":"第1章 失控的证据",
  "status":"REVIEW_REQUIRED",
  "planStatus":"APPROVED",
  "plan":{"scenes":[]},
  "planHash":"<sha256>",
  "wordCount":1280,
  "rowVersion":3,
  "currentVersionId":3003,
  "currentVersion":{"id":3003,"sourceType":"AI_REVISION","content":"..."}
}
```

### 生成并确认章节计划

```http
POST /api/stories/{storyId}/chapters/{chapterNo}/plan
Content-Type: application/json

{"targetLength":1600}
```

返回 `202 Accepted`：

```json
{"taskId":10001,"chapterId":701,"status":"WAITING"}
```

任务完成后通过章节详情取得 3～6 个场景，并使用服务端返回的哈希确认：

```http
POST /api/stories/{storyId}/chapters/{chapterNo}/plan/approve
Content-Type: application/json

{"planHash":"<sha256>"}
```

计划变化时旧哈希返回 `409 CHAPTER_PLAN_CONFLICT`。

### 流式生成正文

```http
POST /api/stories/{storyId}/chapters/{chapterNo}/generate
```

计划必须已经确认。响应仍为 `202` 的任务引用。通用任务查询接口现在也会为章节
任务返回 `taskType`、`chapterId` 和最后一个事件的 `result`：

```http
GET /api/ai-tasks/{taskId}
```

正文流使用带 JWT Header 的 `fetch`，不要把 Token 放在查询参数中：

```http
GET /api/ai-tasks/{taskId}/events
Accept: text/event-stream
Authorization: Bearer <token>
Last-Event-ID: 1712345678-1
```

每个 SSE frame 的 `id` 是可恢复游标，`event` 是下列类型之一：

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
REWRITE_PROPOSAL_READY
SUMMARY_READY
MEMORY_UPDATE_READY
FINAL_READY
TASK_RETRYING
TASK_FAILED
```

`data` 为统一事件对象：

```json
{
  "eventId":"1712345678-1",
  "taskId":10001,
  "storyId":5001,
  "chapterId":701,
  "chapterNo":1,
  "type":"TOKEN_DELTA",
  "sequence":27,
  "status":"RUNNING",
  "currentNode":"write_chapter",
  "progress":30,
  "data":{"text":"林晚盯着那张转账记录，","phase":"chapter_write"}
}
```

后端在发送前持久化事件。非流式客户端和测试可以读取同一历史：

```http
GET /api/ai-tasks/{taskId}/events/history?after={eventId}
```

如果游标已经被裁剪，第一条为 `STREAM_RESET`，客户端应以随后保存的完整草稿或
最终版本重建编辑器，不能继续盲目拼接 Token。

### 保存人工编辑

```http
PUT /api/chapters/{chapterId}/content
Content-Type: application/json

{
  "baseVersionId":3001,
  "baseContentHash":"<sha256>",
  "content":"用户编辑后的完整正文"
}
```

成功创建 `USER_EDIT` 版本。基础版本不是当前版本时返回
`409 CHAPTER_VERSION_CONFLICT`，不会覆盖新内容。

### 局部 AI 改写

```http
POST /api/chapters/{chapterId}/rewrite-selection
Content-Type: application/json

{
  "chapterVersionId":3002,
  "startOffset":520,
  "endOffset":920,
  "selectedText":"原始选中文本",
  "selectedTextHash":"<sha256>",
  "action":"ENHANCE_CONFLICT",
  "customInstruction":"增加反派的具体阻止行动"
}
```

响应是异步任务。`REWRITE_PROPOSAL_READY` 后可读取建议；建议不会直接覆盖正文：

```http
GET /api/chapters/{chapterId}/rewrite-proposals
GET /api/chapters/{chapterId}/rewrite-proposals/{proposalId}
```

```json
{
  "id":9001,
  "baseVersionId":3002,
  "startOffset":520,
  "endOffset":920,
  "originalText":"原始选中文本",
  "originalTextHash":"<sha256>",
  "replacementText":"AI 建议文本",
  "replacementTextHash":"<sha256>",
  "reason":"增加了对手阻止调查的可见行动",
  "status":"READY"
}
```

处理提案：

```http
POST /api/chapters/{chapterId}/rewrite-proposals/{proposalId}/accept
POST /api/chapters/{chapterId}/rewrite-proposals/{proposalId}/reject
POST /api/chapters/{chapterId}/rewrite-proposals/{proposalId}/regenerate
```

接受请求可重复提供 `baseVersionId` 和 `baseContentHash`。服务端会再次验证版本、
偏移和选区哈希；过期提案返回冲突。接受会创建 `AI_SELECTION_REWRITE` 版本，拒绝
只改变提案状态，再次生成返回新的异步任务。

### 版本历史、对比与恢复

```http
GET /api/chapters/{chapterId}/versions
GET /api/chapters/{chapterId}/versions/compare?fromVersionId=3001&toVersionId=3002
POST /api/chapters/{chapterId}/versions/{versionId}/restore
```

对比响应给出共同前后缀长度以及双方变化文本。恢复不会移动旧记录，而是创建新的
`RESTORE` 版本。恢复只在章节批准前开放；章节一旦进入 `APPROVED`，正文和已经写入
的长期记忆都保持不可变，恢复请求返回 `409 CHAPTER_LOCKED`。MVP 不提供长期记忆回滚。

### 批准或退回章节

```http
POST /api/chapters/{chapterId}/approve
Content-Type: application/json

{"approved":true,"notes":""}
```

返回新的 `FINALIZE` 任务。批准时恢复原章节线程，生成摘要和 MemoryUpdate，并在
一个数据库事务中创建 `APPROVED` 版本、更新章节状态、Canon Facts、人物关系、
剧情线和伏笔。若要求继续修改，使用：

```json
{"approved":false,"notes":"第二场需要补足女主保全证据的具体动作。"}
```

修改意见不能为空；工作流定向修订后再次进入 `HUMAN_REVIEW_REQUIRED`。

计划、正文生成、局部改写或定稿任务失败后，用户重复提交同一请求会创建新的任务
尝试。新任务使用独立 `taskId` 和重试幂等键，`parentTaskId` 指向上一失败尝试，
`attemptNo` 递增；运行中或已经成功的同一请求仍返回原任务。

## AI Service API（仅后端调用）

### 健康检查

```http
GET /health
```

### Topic Agent

```http
POST /ai/topic/generate
Content-Type: application/json

{
  "storyId":1,
  "genre":"都市情感",
  "audience":"女性",
  "keywords":"复仇"
}
```

AI 服务负责保证：

- 恰好 10 条不同选题；
- 所有字段通过 Pydantic 校验；
- `score` 为 `0..100`；
- 输出来源通过 `model` 明确披露；
- 无效输入返回 `422`，内部生成失败返回结构化错误。

### Story Workflow（开发与测试）

HTTP 适配器可在同一 AI 服务进程内验证暂停和恢复：

```http
POST /ai/workflow/start
Content-Type: application/json

{
  "taskId":"90001",
  "storyId":5001,
  "topic":{"id":1,"title":"离婚当天，我继承百亿集团","hook":"身份反转"}
}
```

首次调用会在人工审核节点暂停，并返回 `threadId`、结构化产物、进度记录和
`REVIEW_REQUIRED`。随后使用同一线程恢复：

```http
POST /ai/workflow/{threadId}/resume
Content-Type: application/json

{"approved":false,"notes":"提前铺垫反派的现实利益。"}
```

生产链路不让 Spring 同步等待此 HTTP 接口，而使用
`story:workflow:requests` 和 `story:workflow:events` 两个 Redis Stream。

浏览器与 Spring Boot 会在调用 AI 前执行同一组边界校验：题材 `2..50`
字符、受众最多 50 字符、关键词最多 10 个且每个最多 30 字符。用户输入
错误因此返回 `400`，不会被误报成 AI 服务 `502`。

## 错误结构

后端错误使用统一 JSON：

```json
{
  "code":"STORY_NOT_FOUND",
  "message":"故事不存在或无权访问",
  "timestamp":"2026-07-30T12:00:00Z"
}
```

常见状态码：`400` 参数错误、`401` 未登录、`403` 无权访问、`404` 不存在、
`409` 冲突（包括用户名或工作流选题锁定）、`502` AI 服务不可用、`503` 工作流
队列暂时不可用。
