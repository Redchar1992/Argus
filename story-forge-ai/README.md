# StoryForge AI

StoryForge AI 是一个用四周验证“AI 是否能辅助作者完成可控短故事创作”的 MVP。

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

第四周把章节工作流收口为可交付的内测 MVP：

```text
全部章节批准 → 全书终审报告 → 正式版本快照 → TXT / Markdown / DOCX / JSON 导出
              → Prompt 版本追踪 → 模型 Token / 成本记录 → AI 积分流水 → 用户反馈
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
| AI Service | 仅 Compose 内网 `http://ai-service:8000` |
| MySQL | 127.0.0.1:3306（随机密码） |
| Redis | 127.0.0.1:6379（随机密码） |

服务全部健康后，可分别执行第二周和第三周冒烟：

```bash
python3 deploy/smoke_workflow.py
python3 deploy/smoke_chapter_workflow.py
```

## 单机内测部署

Pilot 配置使用 Caddy 自动申请 HTTPS 证书，只公开 80/443，并对浏览器入口与
注册/登录接口启用 Basic Auth 作为首批邀请门禁；登录后的 API 继续只接受 JWT。
登录和注册还启用有界的单机限流；扩容到多个后端副本前应替换为 Redis 共享限流。
MySQL、Redis、后端调试端口和前端容器端口默认只绑定 `127.0.0.1`。内测模式
强制调用 HTTPS 远程模型，模型调用失败时不会静默降级为本地模板。必须在模型
供应商后台设置独立项目、硬预算和用量告警，避免邀请账号异常调用造成失控费用。
当前积分尚未覆盖每条人物/大纲/章节调用，因此首批只允许可信的小规模邀请用户，
供应商硬额度必须作为上线门槛，而不是仅依赖应用内积分。

部署前把域名 A/AAAA 记录指向服务器，并确保防火墙放行 80/TCP 与 443/TCP；
如需 HTTP/3，再放行可选的 443/UDP：

```bash
cd story-forge-ai
cp deploy/pilot.env.example deploy/.env.pilot
# 修改域名、邮箱、模型配置，并为每个密码/密钥分别执行：openssl rand -hex 32
# 按 pilot.env.example 的命令生成 PILOT_BASIC_AUTH_HASH，并保留值外层单引号
docker compose \
  --env-file deploy/.env.pilot \
  -f deploy/docker-compose.yml \
  -f deploy/docker-compose.pilot.yml \
  up -d --build

ENV_FILE=deploy/.env.pilot deploy/pilot-healthcheck.sh
```

健康检查同时覆盖容器状态、Redis 内存、故事/章节 Stream 积压和章节死信。建议
每 5 分钟由 cron 或外部监控执行一次，并通过 `ALERT_WEBHOOK_URL` 接收失败通知。
运营人员可使用普通用户 JWT 与独立指标密钥
查看近 7 天漏斗和 AI 任务健康度：

```bash
curl -H "Authorization: Bearer <jwt>" \
  -H "X-Pilot-Metrics-Key: <PILOT_METRICS_KEY>" \
  "https://<PUBLIC_HOST>/api/internal/pilot/metrics?days=7"
```

## 备份与恢复

备份包含 MySQL、Redis Streams/幂等状态、故事/章节 Checkpoint 与导出文件，并
生成完整性清单。正式恢复
前必须先执行只读校验；实际恢复要求交互确认或显式设置 `FORCE=1`：

```bash
ENV_FILE=deploy/.env.pilot \
COMPOSE_OVERRIDE_FILE=deploy/docker-compose.pilot.yml \
BACKUP_DIR=/srv/story-forge-backups \
deploy/backup.sh

ENV_FILE=deploy/.env.pilot \
COMPOSE_OVERRIDE_FILE=deploy/docker-compose.pilot.yml \
DRY_RUN=1 deploy/restore.sh /srv/story-forge-backups/<timestamp>
```

备份应同步到独立磁盘或对象存储；上线前先在隔离卷完成一次真实恢复演练。

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
- [部署前置依赖](docs/deployment-prerequisites.md)
- [AI 可执行部署手册](docs/ai-deployment-runbook.md)
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
- ChatGPT OAuth、MCP 插件、支付、自动投稿

当前产品聚焦选题、人物、大纲、单章写作、人工编辑、版本管理、全书报告、正式版本和结构化故事记忆。
