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
`409` 用户名冲突、`502` AI 服务不可用。
