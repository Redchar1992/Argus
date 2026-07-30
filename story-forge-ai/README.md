# StoryForge AI

StoryForge AI 是一个用两周验证“AI 生成的结构化故事方案是否有价值”的 MVP。

第一周闭环：

```text
注册 / 登录
  → 创建故事并填写创作方向
  → 后端调用 Topic Agent + Score Agent
  → 展示 10 个结构化选题
  → 保存选中的方案
  → 从「我的作品」再次查看
```

第二周在已选方案上增加一条可暂停、可恢复的工作流：

```text
人物卡 → 20 节点大纲 → 五维评分 → 最多两轮自动修订
       → 人工批准 / 提出意见 → 版本化保存
```

本目录是现有仓库中的独立应用，沿用仓库 Git 历史，并在 `develop` 分支开发。

## 目录

```text
story-forge-ai/
├── frontend/       # Vue 3 + TypeScript + Element Plus
├── backend/        # Spring Boot 3 + Spring Security + MyBatis Plus
├── ai-service/     # FastAPI + LangGraph Agents + Redis Worker
├── deploy/         # MySQL / Redis / 三服务 / Worker
└── docs/           # 架构、API、验收说明
```

## 快速启动

第一周选题链路默认使用：

- 后端：H2 内存数据库
- AI：无密钥时使用明确标记为 `local-template` 的确定性结构化生成器
- 前端：Vite 开发服务器

第二周异步工作流需要 Redis。最省事的完整启动方式是使用下方 Docker
Compose；分别启动服务时还需运行 `python -m app.workers.story_worker`。

需要 Node.js 20+、JDK 17+、Maven 3.9+、Python 3.11+。

### 1. AI 服务

```bash
cd story-forge-ai/ai-service
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt
uvicorn app.main:app --reload --port 8000
```

健康检查：`GET http://localhost:8000/health`

### 2. 后端

```bash
cd story-forge-ai/backend
mvn spring-boot:run
```

健康检查：`GET http://localhost:8080/api/health`

### 3. 前端

```bash
cd story-forge-ai/frontend
npm install
npm run dev
```

打开 `http://localhost:5173`，注册后即可体验选题闭环。若要运行第二周工作流，
请同时启动 Redis 和 Worker。

## 使用真实 LLM

AI 服务支持 OpenAI-compatible Chat Completions API：

```bash
cd story-forge-ai/ai-service
cp .env.example .env
# 填写 OPENAI_API_KEY，并按需修改 OPENAI_BASE_URL / OPENAI_MODEL
uvicorn app.main:app --reload --port 8000 --env-file .env
```

没有配置密钥时仍能完成联调，但页面和 API 会显示实际使用的是
`local-template`，不会把模板结果伪装成模型输出。

## Docker Compose

```bash
cd story-forge-ai
cp deploy/.env.example deploy/.env
# 把以下命令的输出填入 deploy/.env 的 JWT_SECRET
openssl rand -hex 32
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up --build
```

Compose 会同时启动 HTTP AI 服务和异步 AI Worker。服务地址：

| 服务 | 地址 |
|---|---|
| Web | http://localhost:5173 |
| Backend | http://localhost:8080 |
| AI Service | http://localhost:8000 |
| MySQL | localhost:3306 |
| Redis | localhost:6379 |

服务全部健康后，可执行一次真实的“生成 → 退回修改 → 再审核 → 批准”冒烟：

```bash
python3 deploy/smoke_workflow.py
```

第二周本地 MVP 按计划使用内存 checkpointer。请在一次“生成 → 审核 → 批准”
演示期间保持 AI Worker 运行；生产环境需要改用持久化 checkpointer 才能跨
Worker 重启恢复。

## 验证

```bash
# AI service
cd story-forge-ai/ai-service && pytest

# Backend
cd story-forge-ai/backend && mvn test

# Frontend
cd story-forge-ai/frontend && npm test -- --run && npm run build
```

详细信息：

- [架构与数据流](docs/architecture.md)
- [API 契约](docs/api.md)
- [第一周验收清单](docs/week-1-acceptance.md)
- [第二周验收清单](docs/week-2-acceptance.md)

## 当前明确不做

- 正文生成和长文本记忆
- 视频生成
- 社区
- 推荐系统
- 爆款知识库检索和多模型路由
- WebSocket、支付、额度扣减和自动投稿

当前产品只收集创作方向、结构化选题、人物、大纲、评分、版本和人工审核结果。
