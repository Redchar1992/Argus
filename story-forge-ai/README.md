# StoryForge AI

StoryForge AI 是一个用三周验证“AI 是否能辅助作者完成可控短故事创作”的 MVP。

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

第三周把已批准的大纲推进为一次一章、每章人工确认的写作闭环：

```text
3～6 个场景计划 → 人工确认 → 流式正文 → 六维审核
  → 最多两轮自动修订 → 人工编辑 / 局部 AI 改写
  → 批准前版本对比与恢复 → 批准 → 更新长期故事记忆
```

本目录是现有仓库中的独立应用，沿用仓库 Git 历史，并在 `develop` 分支开发。

## 目录

```text
story-forge-ai/
├── frontend/       # Vue 3 + TypeScript + Element Plus + Monaco
├── backend/        # Spring Boot 3 + Spring Security + MyBatis Plus
├── ai-service/     # FastAPI + LangGraph Agents + Redis Workers
├── deploy/         # MySQL / Redis / 三服务 / 两类 Worker
└── docs/           # 架构、API、验收说明
```

## 快速启动

第一周选题链路默认使用：

- 后端：H2 内存数据库
- AI：无密钥时使用明确标记为 `local-template` 的确定性结构化生成器
- 前端：Vite 开发服务器

第二、三周异步工作流需要 Redis。最省事的完整启动方式是使用下方 Docker
Compose；分别启动服务时还需运行两个 Worker：

```bash
python -m app.workers.story_worker
python -m app.workers.chapter_worker
```

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

打开 `http://localhost:5173`，注册后即可体验完整闭环。若要运行人物、大纲或章节
工作流，请同时启动 Redis 和相应 Worker。

## 选择模型

AI 服务通过统一 `ModelProvider` 支持 OpenAI-compatible API、Ollama 和用于开发
验收的确定性 `local-template`：

```bash
cd story-forge-ai/ai-service
cp .env.example .env
# MODEL_PROVIDER 可设为 auto / openai-compatible / ollama / local
# OpenAI-compatible：填写 OPENAI_API_KEY / OPENAI_BASE_URL / OPENAI_MODEL
# Ollama：填写 OLLAMA_BASE_URL / OLLAMA_MODEL，无需把凭据交给浏览器
uvicorn app.main:app --reload --port 8000 --env-file .env
```

没有配置密钥时仍能完成联调，但页面和 API 会显示实际使用的是
`local-template`，不会把模板结果伪装成模型输出。

## Docker Compose

```bash
cd story-forge-ai
cp deploy/.env.example deploy/.env
# 分别运行并把输出填入 deploy/.env 的 MYSQL_PASSWORD、
# MYSQL_ROOT_PASSWORD、REDIS_PASSWORD 与 JWT_SECRET（仅使用十六进制值）
openssl rand -hex 32
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up --build
```

Compose 会同时启动 HTTP AI 服务、故事工作流 Worker 和持久化章节 Worker。
章节短期状态保存在命名卷中的 SQLite Checkpointer，正式正文、版本与长期记忆保存
在 MySQL。服务地址：

| 服务 | 地址 |
|---|---|
| Web | http://localhost:5173 |
| Backend | http://localhost:8080 |
| AI Service | http://localhost:8000 |
| MySQL | 127.0.0.1:3306（随机密码） |
| Redis | 127.0.0.1:6379（随机密码） |

服务全部健康后，可分别执行第二周和第三周冒烟：

```bash
python3 deploy/smoke_workflow.py
python3 deploy/smoke_chapter_workflow.py
```

章节 Worker 本地使用持久化 SQLite Checkpointer；生产环境应按官方建议切换到
数据库型 Checkpointer（例如 PostgreSQL），并为副作用继续保留业务幂等键。

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
- [第三周验收清单](docs/week-3-acceptance.md)

## 当前明确不做

- 一键生成或自动发布整本故事
- 复杂向量知识库和用户文风微调
- 视频生成
- 社区
- 推荐系统
- 多人协作编辑
- ChatGPT OAuth、MCP 插件、支付、额度扣减和自动投稿

当前产品聚焦选题、人物、大纲、单章写作、人工编辑、版本管理和结构化故事记忆。
