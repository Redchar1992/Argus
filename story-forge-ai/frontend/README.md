# Story Forge AI Frontend

Story Forge AI 两周 MVP 前端。第一周完成从登录、创建故事方向、生成结构化选题到
保存结果的闭环；第二周把已选选题推进为可暂停、可恢复、可人工审核的 AI 编剧工作流。

## 技术栈

- Vue 3 + TypeScript + Vite
- Vue Router（鉴权路由守卫）
- Pinia（账号与故事状态）
- Element Plus
- Axios（JWT 请求拦截与统一错误处理）
- Vitest

## 本地启动

要求 Node.js 20.19+。

```bash
npm install
cp .env.example .env.local
npm run dev
```

默认访问 `http://localhost:5173`，默认后端地址为
`http://localhost:8080`。如需修改，在 `.env.local` 中设置：

```dotenv
VITE_API_BASE_URL=http://localhost:8080
```

## 验证命令

```bash
npm run build
npm test
```

## 容器构建

前端使用 Node 构建、Nginx 运行的多阶段镜像；Nginx 已配置 Vue Router
history fallback。

```bash
docker build \
  --build-arg VITE_API_BASE_URL=http://localhost:8080 \
  -t story-forge-frontend .
docker run --rm -p 5173:80 story-forge-frontend
```

## MVP 页面

| 路由 | 页面 | 功能 |
| --- | --- | --- |
| `/login` | 登录 / 注册 | 注册、登录、JWT 本地会话 |
| `/privacy` | 内测隐私说明 | 说明采集范围、用途、保留与联系渠道 |
| `/` | 我的作品 | 作品列表、空状态、生成状态概览 |
| `/stories/new` | AI 故事策划 | 创建故事、生成 10 个选题、比较四维评分、保存主方案 |
| `/stories/:id` | 故事详情 | 从服务端重新加载生成结果、查看或更换主方案 |
| `/stories/:storyId/workflow/:taskId` | 工作流进度 | 每 2 秒轮询人物、大纲、评分与自动修改进度 |
| `/stories/:storyId/workflow/:taskId/review` | 大纲审核 | 三栏查看人物、20 节点大纲、五维评分，批准或要求修改 |

## 后端 API 契约

前端兼容裸 JSON 和 `{ code, message, data }` 两类响应。

| Method | Path | Body |
| --- | --- | --- |
| `POST` | `/api/auth/register` | `{ username, password, privacyAccepted: true }` |
| `POST` | `/api/auth/login` | `{ username, password, privacyAccepted?: true }` |
| `GET` | `/api/story/list` | — |
| `POST` | `/api/story/create` | `{ title, genre, audience, keywords }` |
| `POST` | `/api/ai/topic/generate` | `{ storyId, genre, audience, keywords }` |
| `PUT` | `/api/story/{id}/selection` | `{ topicId }` |
| `GET` | `/api/story/{id}` | — |
| `POST` | `/api/stories/{storyId}/workflow` | `{ topicId }` |
| `GET` | `/api/stories/{storyId}/workflow/latest` | — |
| `GET` | `/api/ai-tasks/{taskId}` | — |
| `GET` | `/api/ai-tasks/{taskId}/review` | — |
| `POST` | `/api/ai-tasks/{taskId}/review` | `{ approved, notes }` |

`generatedTopics`、`selectedTopic` 可以是已解析 JSON，也可以是 JSON
字符串。评分支持总分以及 `conflict`、`reversal`、`emotionalValue`、
`shortDramaFit` 四个 `{ score, reason }` 维度。

## 数据与安全约定

- JWT 通过 `Authorization: Bearer <token>` 自动附加。
- 收到 `401` 后清理会话并返回登录页。
- 本地 AI 结果缓存按 `userId` 隔离，退出登录时全部清理；它只用于网络故障或
  `5xx` 时的离线查看，不会在 `401`、`403`、`404` 时回退，避免跨用户展示。
- 主方案选择必须先成功写入服务端，再通过故事详情接口刷新本地状态。
- 工作流任务 ID、故事 ID、选题 ID 与最近进度按用户隔离保存；断线或刷新后会从
  路由和本地恢复信息继续轮询同一任务。重新登录时以服务端 latest-task 为准，
  localStorage 只作为网络故障时的降级视图。
- 人工要求修改时使用审核接口返回的全新任务 ID，继续轮询同一工作流线程，而不是
  创建新故事。
- 审核页只有在人物为 3–6 名、大纲恰好为连续 20 节点、评分包含五个维度时才允许批准。

## 目录

```text
src/
├── api/          # API 契约与响应标准化
├── components/   # 应用壳、空状态、选题卡片
├── router/       # 路由与鉴权守卫
├── stores/       # Pinia 状态
├── styles/       # 全局视觉变量
├── types/        # 领域类型
├── utils/        # Axios、缓存、错误与格式化
└── views/        # 登录、作品、创建、详情页面
```
