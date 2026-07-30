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

{"username":"demo","password":"demo1234"}
```

注册成功后直接返回与登录相同的令牌结构。

### 登录

```http
POST /api/auth/login
Content-Type: application/json

{"username":"demo","password":"demo1234"}
```

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
