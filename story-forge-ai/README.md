# StoryForge AI

StoryForge AI 是一个只验证“AI 生成的故事方向是否有价值”的 7 天 MVP。

第一周闭环：

```text
注册 / 登录
  → 创建故事并填写创作方向
  → 后端调用 Topic Agent + Score Agent
  → 展示 10 个结构化选题
  → 保存选中的方案
  → 从「我的作品」再次查看
```

本目录是现有仓库中的独立应用，沿用仓库 Git 历史，并在 `develop` 分支开发。

## 目录

```text
story-forge-ai/
├── frontend/       # Vue 3 + TypeScript + Element Plus
├── backend/        # Spring Boot 3 + Spring Security + MyBatis Plus
├── ai-service/     # FastAPI + Topic Agent + Score Agent
├── deploy/         # Docker Compose 本地部署
└── docs/           # 架构、API、验收说明
```

## 快速启动（零外部基础设施）

本地开发默认使用：

- 后端：H2 内存数据库
- AI：无密钥时使用明确标记为 `local-template` 的本地结构化生成器
- 前端：Vite 开发服务器

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

打开 `http://localhost:5173`，注册后即可体验完整闭环。

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

服务地址：

| 服务 | 地址 |
|---|---|
| Web | http://localhost:5173 |
| Backend | http://localhost:8080 |
| AI Service | http://localhost:8000 |
| MySQL | localhost:3306 |
| Redis | localhost:6379 |

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

## 明确不在第一周范围内

- 正文生成
- 富文本编辑器
- 视频生成
- 社区
- 支付
- 推荐系统
- 第二周的人物卡和 20 节点大纲

第一周只收集创作方向、结构化选题、评分和用户最终选择。
