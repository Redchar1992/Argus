# StoryForge AI：可由 AI 执行的单机内测部署手册

> 版本：2026-08-04
> 适用分支：`develop`
> 目标：在一台 Ubuntu 云服务器上部署 Vue、Spring Boot、FastAPI、MySQL、Redis、两个 Worker 和 Caddy，完成 HTTPS 访问及端到端冒烟验证。

本文是给另一台电脑上的 GPT/CLI Agent 使用的执行手册。Agent 应严格按顺序执行，
每一步检查命令退出码和输出；遇到未满足的前置条件先停止并报告，不要猜测密钥、域名、
模型名称或数据库密码。

## 0. Agent 执行规则

1. 所有命令默认在目标服务器执行，工作目录为 `/opt/argus/story-forge-ai`。
2. 不要把 `deploy/.env.pilot`、模型 API Key、JWT、Basic Auth 明文密码或命令输出发到聊天记录。
3. 生产环境禁止执行 `docker compose down -v`、删除 Docker volume、`git reset --hard` 或强制回滚数据库，除非用户明确确认并且已有可验证备份。
4. 每个阶段失败时保留日志，先执行诊断命令，不要重复运行可能产生费用的 AI 冒烟。
5. 远程模型调用会产生费用。没有模型供应商 Key、预算和告警时，只完成配置校验，不启动真实 AI 冒烟。
6. 所有服务通过 Docker Compose 运行；不需要在服务器安装 Node.js、Java、Maven 或 Python（备份/冒烟脚本除外，Ubuntu 通常已提供 Python 3）。

## 1. 部署目标与架构

```text
浏览器
  │ HTTPS 80/443
  ▼
Caddy ──► frontend(Nginx/Vue)
  │
  └──────► backend(Spring Boot)
              ├── MySQL（正式业务数据/Flyway）
              ├── Redis Streams（任务队列/事件）
              └── ai-service(FastAPI) ◄── ai-worker / chapter-worker
                                             │
                                             └── 远程 OpenAI-compatible LLM
```

本手册部署的是邀请制单机 Pilot，不是高可用生产集群：

- 只运行一个 Backend 消费者和一个 Story Worker、一个 Chapter Worker。
- MySQL、Redis、Backend、Frontend 只绑定服务器回环地址；公网只暴露 Caddy 的 80/443。
- AI Key 只存在服务器端环境变量，不进入浏览器。
- Caddy 对浏览器入口和注册/登录接口启用 Basic Auth；应用 API 仍使用 JWT。
- 远程模型失败时 Pilot 不降级为 `local-template`，避免把模板结果当成真实模型结果。

## 2. 服务器和网络前置条件

### 2.1 最低配置

| 项目 | 最低 | 建议 |
| --- | --- | --- |
| CPU | 2 vCPU | 4 vCPU |
| 内存 | 4 GB RAM + 2 GB Swap | 8 GB RAM |
| 系统盘 | 60 GB SSD | 80～100 GB SSD |
| 系统 | Ubuntu 24.04 LTS 64 位 | Ubuntu 24.04 LTS 64 位 |
| 带宽 | 10 Mbps | 20 Mbps+ |

4 GB 只适合远程模型和低并发内测。不要在此服务器运行 Ollama 或 7B 本地模型。

### 2.2 必须由用户提供的值

Agent 开始部署前必须确认以下值；缺少任何一项就停止：

| 变量 | 说明 |
| --- | --- |
| `PUBLIC_HOST` | 已解析到服务器的域名，例如 `story.example.com` |
| `ACME_EMAIL` | Caddy 申请证书使用的邮箱 |
| `OPENAI_API_KEY` | 服务器端远程模型 Key，或其他 OpenAI-compatible 服务 Key |
| `OPENAI_BASE_URL` | 供应商的 `/v1` 根地址 |
| `OPENAI_MODEL` | 供应商账号实际可用的模型名 |
| `PILOT_BASIC_AUTH_USER` | 邀请制入口用户名 |
| Basic Auth 明文密码 | 只用于生成 bcrypt 哈希，不写入仓库 |

模型供应商必须已配置独立项目、硬预算、日/月用量上限和异常告警。

### 2.3 DNS 和防火墙

在启动 Caddy 前完成：

1. `PUBLIC_HOST` 的 A 记录指向服务器 IPv4。
2. 如果启用 IPv6，AAAA 记录也必须可达；否则不要配置错误的 AAAA。
3. 公网放行：22/TCP（建议限制办公 IP）、80/TCP、443/TCP。
4. 需要 HTTP/3 时额外放行 443/UDP；不是必需项。
5. 服务器能出站访问 443/TCP（Docker Hub、模型供应商、证书服务、告警 Webhook）。

在服务器上验证 DNS：

```bash
getent ahosts story.example.com
# 或：dig +short A story.example.com
# 或：dig +short AAAA story.example.com
```

如果 DNS 还未生效，不要启动 Caddy；证书申请会失败。

## 3. 安装服务器依赖

以下命令适用于 Ubuntu 24.04。若服务器已有 Docker Engine 和 Compose Plugin，先执行
版本检查，满足条件即可跳过安装。

```bash
uname -m
cat /etc/os-release
docker --version || true
docker compose version || true
git --version || true
```

需要 `amd64/x86_64`（ARM64 也可，但优先 amd64）、Docker Engine 24+、Docker Compose
Plugin 2.x、Git、curl、openssl、jq、ufw。

如缺少 Docker，使用官方仓库安装：

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl git openssl jq ufw
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"
```

加入 Docker 用户组后重新登录 SSH，或执行：

```bash
newgrp docker
docker run --rm hello-world
docker compose version
```

如果没有 Swap，建议增加 2 GB；不要用 Swap 替代内存：

```bash
free -h
swapon --show
```

防火墙只追加必要规则，不要执行 `ufw reset`：

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw --force enable
sudo ufw status verbose
```

## 4. 获取代码

仓库的应用位于 Git 仓库的 `story-forge-ai/` 子目录。推荐将仓库放在 `/opt/argus`：

```bash
sudo mkdir -p /opt/argus
sudo chown -R "$USER":"$USER" /opt/argus

if [ -d /opt/argus/.git ]; then
  cd /opt/argus
  git fetch origin
  git checkout develop
  git pull --ff-only origin develop
else
  git clone --branch develop https://github.com/Redchar1992/Argus.git /opt/argus
fi

cd /opt/argus/story-forge-ai
git branch --show-current
git log -1 --oneline
```

期望当前分支为 `develop`。如果仓库地址、分支或目录不同，先报告，不要自行改用其他项目。

## 5. 创建 Pilot 配置

### 5.1 复制模板并保护权限

```bash
cd /opt/argus/story-forge-ai
umask 077
cp deploy/pilot.env.example deploy/.env.pilot
chmod 600 deploy/.env.pilot
```

### 5.2 生成独立随机密钥

为每个变量生成不同值，不能复用：

```bash
openssl rand -hex 32  # MYSQL_PASSWORD
openssl rand -hex 32  # MYSQL_ROOT_PASSWORD
openssl rand -hex 32  # REDIS_PASSWORD
openssl rand -hex 32  # JWT_SECRET
openssl rand -hex 32  # AI_INTERNAL_API_KEY
openssl rand -hex 32  # PILOT_METRICS_KEY
```

将生成的值写入 `deploy/.env.pilot`。不要把值写入 Git、聊天、截图或 shell 脚本。
可以使用 `sudoedit`/`nano`，也可以由 Agent 使用安全的 secret prompt 写入；完成后检查：

```bash
grep -E '^(PUBLIC_HOST|ACME_EMAIL|OPENAI_BASE_URL|OPENAI_MODEL|MODEL_PROVIDER|AI_REQUIRE_REMOTE_MODEL|OPENAI_FALLBACK_ENABLED|CORS_ALLOWED_ORIGINS)=' deploy/.env.pilot
```

上面的检查不得输出任何密码或 API Key。

### 5.3 必须设置的关键配置

`deploy/.env.pilot` 至少应满足：

```dotenv
PUBLIC_HOST=你的域名
ACME_EMAIL=你的运维邮箱
PUBLIC_API_BASE_URL=https://你的域名
CORS_ALLOWED_ORIGINS=https://你的域名

MODEL_PROVIDER=openai-compatible
OPENAI_BASE_URL=https://供应商.example/v1
OPENAI_MODEL=供应商实际可用模型
OPENAI_CREATIVE_MODEL=供应商实际可用模型
OPENAI_REVIEW_MODEL=供应商实际可用模型
OPENAI_FALLBACK_ENABLED=false
AI_REQUIRE_REMOTE_MODEL=true
AI_REQUIRE_INTERNAL_API_KEY=true
```

`OPENAI_API_KEY`、`MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`REDIS_PASSWORD`、
`JWT_SECRET`、`AI_INTERNAL_API_KEY`、`PILOT_METRICS_KEY` 必须非空。

### 5.4 生成 Caddy Basic Auth 哈希

Basic Auth 明文密码只用于生成哈希。不要将明文密码写入 `.env.pilot`：

```bash
read -r -s -p 'Pilot Basic Auth password: ' PILOT_BASIC_AUTH_PASSWORD
printf '\n'
docker run --rm caddy:2.11.4-alpine \
  caddy hash-password --algorithm bcrypt --bcrypt-cost 10 \
  --plaintext "$PILOT_BASIC_AUTH_PASSWORD"
unset PILOT_BASIC_AUTH_PASSWORD
```

将命令输出的 bcrypt 值写入 `PILOT_BASIC_AUTH_HASH`，并保留外层单引号；
`PILOT_BASIC_AUTH_USER` 写入邀请入口用户名。

### 5.5 配置审核超时和过期扫描

当前默认值已经可用：

```dotenv
AI_WORKFLOW_REVIEW_TIMEOUT_HOURS=24
AI_QUOTA_EXPIRY_INTERVAL_MS=60000
```

含义：工作流预冻结额度默认 24 小时有效，后端每 60 秒扫描过期预冻结并释放钱包及日/月额度。

## 6. 配置校验（启动前必须通过）

先检查关键变量是否为空，但不要打印值：

```bash
cd /opt/argus/story-forge-ai
set -a
source deploy/.env.pilot
set +a

for name in PUBLIC_HOST ACME_EMAIL PUBLIC_API_BASE_URL CORS_ALLOWED_ORIGINS \
  MYSQL_PASSWORD MYSQL_ROOT_PASSWORD REDIS_PASSWORD JWT_SECRET \
  AI_INTERNAL_API_KEY PILOT_METRICS_KEY OPENAI_API_KEY \
  PILOT_BASIC_AUTH_USER PILOT_BASIC_AUTH_HASH OPENAI_BASE_URL OPENAI_MODEL; do
  if [ -z "${!name:-}" ]; then
    printf 'missing required variable: %s\n' "$name" >&2
    exit 1
  fi
done

if [ "$MODEL_PROVIDER" != "openai-compatible" ] \
  || [ "$OPENAI_FALLBACK_ENABLED" != "false" ] \
  || [ "$AI_REQUIRE_REMOTE_MODEL" != "true" ]; then
  echo 'pilot must use remote model without local fallback' >&2
  exit 1
fi

chmod 600 deploy/.env.pilot
docker compose --env-file deploy/.env.pilot \
  -f deploy/docker-compose.yml \
  -f deploy/docker-compose.pilot.yml config --quiet
```

如果 `config --quiet` 失败，先修复变量或 YAML 配置，不要继续 `up`。

可选：检查 Caddy 配置。该命令会拉取 Caddy 镜像（如果本地没有）：

```bash
docker compose --env-file deploy/.env.pilot \
  -f deploy/docker-compose.yml \
  -f deploy/docker-compose.pilot.yml \
  run --rm --no-deps caddy \
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
```

## 7. 启动服务

### 7.1 第一次启动

```bash
cd /opt/argus/story-forge-ai
docker compose --env-file deploy/.env.pilot \
  -f deploy/docker-compose.yml \
  -f deploy/docker-compose.pilot.yml \
  up -d --build --remove-orphans
```

第一次构建可能需要数分钟，并且会产生较高内存峰值。4 GB 服务器若构建 OOM，先确认
Swap，再考虑临时升级到 8 GB 或在其他机器构建镜像；不要删除 MySQL/Redis volume。

### 7.2 查看容器状态

```bash
COMPOSE='docker compose --env-file deploy/.env.pilot -f deploy/docker-compose.yml -f deploy/docker-compose.pilot.yml'
$COMPOSE ps
$COMPOSE ps --format json
```

应看到以下服务：

```text
mysql redis ai-service ai-worker chapter-worker backend frontend caddy
```

`mysql`、`redis`、`ai-service`、`backend`、`frontend`、`caddy` 应为 `running/healthy`；
两个 Worker 没有 HTTP 健康检查，但必须为 `running`。

### 7.3 查看启动日志

```bash
$COMPOSE logs --tail=120 mysql redis ai-service backend
$COMPOSE logs --tail=120 ai-worker chapter-worker
```

正常情况下不应出现：

- `Flyway validation failed`
- `Access denied for user`
- `Cannot connect to Redis`
- `AI_INTERNAL_API_KEY` 不匹配
- 远程模型 Key 无效或模型不存在
- 容器反复重启

## 8. 首次健康检查和数据库迁移确认

如果 Agent 开启了新的 shell，会话必须先重新加载配置和 Compose 命令：

```bash
cd /opt/argus/story-forge-ai
set -a
source deploy/.env.pilot
set +a
COMPOSE='docker compose --env-file deploy/.env.pilot -f deploy/docker-compose.yml -f deploy/docker-compose.pilot.yml'
```

### 8.1 公网健康检查

证书申请完成后执行：

```bash
curl --fail --silent --show-error --max-time 15 \
  "https://${PUBLIC_HOST}/api/health"
printf '\n'
```

期望返回：

```json
{"status":"ok"}
```

如果 Caddy 尚未拿到证书，可先从服务器回环地址验证 Backend：

```bash
curl --fail --silent --show-error http://127.0.0.1:8080/api/health
printf '\n'
```

### 8.2 确认 Flyway 版本

```bash
docker compose --env-file deploy/.env.pilot \
  -f deploy/docker-compose.yml -f deploy/docker-compose.pilot.yml \
  exec -T mysql sh -ceu '
    mysql -h127.0.0.1 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" \
      -Nse "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;"
  '
```

期望版本至少为 `9`，因为 V9 增加审核预冻结过期字段和索引。

### 8.3 确认公网端口没有绕过 Caddy

```bash
sudo ss -lntup | grep -E ':(80|443|3306|6379|8080|5173)\b' || true
```

3306、6379、8080、5173 应只监听 `127.0.0.1`，不应绑定 `0.0.0.0`。

## 9. 端到端验收

### 9.1 浏览器验收

1. 打开 `https://${PUBLIC_HOST}`。
2. 输入 Pilot Basic Auth 用户名和密码。
3. 注册一个应用账号并同意隐私说明。
4. 登录后创建故事，填写题材、受众、关键词。
5. 生成 10 个结构化选题并选择一个。
6. 启动工作流，等待 `REVIEW_REQUIRED`。
7. 查看人物卡、20 节点大纲和五维评分。
8. 提交一次“要求修改”，确认新任务继续同一个工作流线程。
9. 再次审核并批准，确认状态变为 `SUCCESS`。
10. 确认刷新页面或重新登录后仍能查看故事和工作流结果。

### 9.2 服务器回环冒烟

Caddy 会保护 `/api/auth/*`，仓库中的 Python 冒烟脚本没有 Basic Auth 参数，因此在
服务器上用 Backend 回环地址运行，避免把测试账号暴露到公网：

```bash
cd /opt/argus/story-forge-ai
python3 deploy/smoke_workflow.py \
  --backend http://127.0.0.1:8080 \
  --timeout 300
```

如果要验收章节闭环（会执行更多远程模型调用并产生测试数据）：

```bash
python3 deploy/smoke_chapter_workflow.py \
  --backend http://127.0.0.1:8080 \
  --timeout 480
```

冒烟成功应打印 `{"status":"ok", ...}`。失败时先查看对应任务 ID 和 Worker 日志，
不要立即重复执行，因为重复执行会继续产生模型费用。

### 9.3 审核超时验收

不要为了测试而等待 24 小时。使用测试环境专用配置：

```dotenv
AI_WORKFLOW_REVIEW_TIMEOUT_HOURS=1
AI_QUOTA_EXPIRY_INTERVAL_MS=10000
```

修改配置后必须重建/重启 Backend：

```bash
docker compose --env-file deploy/.env.pilot \
  -f deploy/docker-compose.yml -f deploy/docker-compose.pilot.yml \
  up -d --force-recreate backend
```

创建工作流并让它进入人工审核，等待过期后确认：

- 额度冻结值回到 0。
- 可用积分恢复。
- 日/月预留额度恢复。
- 再次提交审核返回 `402 / AI_WORKFLOW_REVIEW_EXPIRED`。
- 后端日志没有重复扣费或重复释放异常。

验收完成后将 Pilot 配置恢复为 24 小时，并重启 Backend。生产环境不要把 TTL 设置得过短。

## 10. 监控和日常运维

以下命令假定已执行第 8 节的配置加载块；如果是新 shell，先重新执行该块。

### 10.1 健康检查脚本

脚本会检查容器、Redis 内存、工作流/章节 Stream 积压、dead-letter 数量和公网健康接口：

```bash
cd /opt/argus/story-forge-ai
ENV_FILE=deploy/.env.pilot deploy/pilot-healthcheck.sh
```

成功输出类似：

```text
Story Forge pilot is healthy: https://story.example.com
```

加入 root cron，每 5 分钟执行一次：

```bash
sudo crontab -e
```

加入：

```cron
*/5 * * * * cd /opt/argus/story-forge-ai && ENV_FILE=/opt/argus/story-forge-ai/deploy/.env.pilot /opt/argus/story-forge-ai/deploy/pilot-healthcheck.sh >>/var/log/story-forge-health.log 2>&1
```

如果配置了 `ALERT_WEBHOOK_URL`，失败时脚本会发送最小化 JSON 告警；不要将密钥放到 URL 查询参数中。

### 10.2 日志和资源

```bash
$COMPOSE ps
$COMPOSE logs --tail=200 backend
$COMPOSE logs --tail=200 ai-worker chapter-worker
docker stats --no-stream
df -h
free -h
```

出现 OOM、Redis 内存告警、Stream 持续积压或 Worker 重启时，先暂停新增邀请用户和真实模型冒烟，
保存日志后再扩容或处理队列。

### 10.3 备份

备份会短暂停止应用服务，包含 MySQL、Redis 持久化状态、两个 SQLite Checkpoint 和导出文件。
备份目录必须位于独立磁盘或随后同步到对象存储：

```bash
sudo mkdir -p /srv/story-forge-backups
sudo chown "$USER":"$USER" /srv/story-forge-backups

cd /opt/argus/story-forge-ai
ENV_FILE=deploy/.env.pilot \
COMPOSE_OVERRIDE_FILE=deploy/docker-compose.pilot.yml \
BACKUP_DIR=/srv/story-forge-backups \
deploy/backup.sh
```

备份完成后检查目录中有 `manifest.txt`、`SHA256SUMS`、`mysql.sql.gz`、两个 SQLite 文件、
`exports.tar.gz` 和 `redis-data.tar.gz`。不要只保留同一台服务器上的副本。

### 10.4 恢复演练和正式恢复

正式恢复前先做只读校验：

```bash
cd /opt/argus/story-forge-ai
ENV_FILE=deploy/.env.pilot \
COMPOSE_OVERRIDE_FILE=deploy/docker-compose.pilot.yml \
DRY_RUN=1 deploy/restore.sh /srv/story-forge-backups/<timestamp>
```

校验通过后，正式恢复会替换应用数据并停止/启动相关服务，必须由负责人确认：

```bash
ENV_FILE=deploy/.env.pilot \
COMPOSE_OVERRIDE_FILE=deploy/docker-compose.pilot.yml \
deploy/restore.sh /srv/story-forge-backups/<timestamp>
```

非交互环境只有在负责人明确确认后才允许使用 `FORCE=1`。恢复失败时不要自动重新开放流量，
先保留容器和日志现场。

## 11. 更新和回滚

更新/回滚前在新 shell 中重新执行第 8 节的配置加载块，并确认已经完成备份。

### 11.1 正常更新

先备份，再更新代码和镜像：

```bash
cd /opt/argus
git fetch origin
git checkout develop
git pull --ff-only origin develop
cd /opt/argus/story-forge-ai

ENV_FILE=deploy/.env.pilot \
COMPOSE_OVERRIDE_FILE=deploy/docker-compose.pilot.yml \
BACKUP_DIR=/srv/story-forge-backups \
deploy/backup.sh

docker compose --env-file deploy/.env.pilot \
  -f deploy/docker-compose.yml -f deploy/docker-compose.pilot.yml \
  up -d --build --remove-orphans

ENV_FILE=deploy/.env.pilot deploy/pilot-healthcheck.sh
```

Flyway 迁移只向前执行。不要手工删除 `flyway_schema_history` 或回退表结构。

### 11.2 版本回滚

应用镜像回滚前先记录当前 commit 和容器日志：

```bash
cd /opt/argus
git log -5 --oneline -- story-forge-ai
git status --short
```

如需回滚应用代码，只切换到已验证的 commit，再重新构建：

```bash
git checkout <已验证的commit>
cd /opt/argus/story-forge-ai
docker compose --env-file deploy/.env.pilot \
  -f deploy/docker-compose.yml -f deploy/docker-compose.pilot.yml \
  up -d --build
```

如果新版本已经执行了不可逆数据库迁移，不能只回滚镜像；必须使用备份恢复到兼容版本，
并由负责人确认数据丢失范围。

## 12. 常见故障处理

### Caddy 无法申请证书

检查：

```bash
getent ahosts "$PUBLIC_HOST"
sudo ufw status verbose
$COMPOSE logs --tail=200 caddy
```

确认 A/AAAA、80/443、服务器时间和云厂商安全组。错误 AAAA 记录是常见原因。

### Backend 启动失败

```bash
$COMPOSE logs --tail=250 backend
$COMPOSE ps mysql redis ai-service
```

重点检查 MySQL 密码、Redis 密码、`AI_INTERNAL_API_KEY`、JWT_SECRET 和 Flyway 报错。

### AI 任务一直 WAITING

```bash
$COMPOSE logs --tail=250 ai-worker chapter-worker
$COMPOSE exec -T redis redis-cli --no-auth-warning -a "$REDIS_PASSWORD" XLEN story:workflow:requests
$COMPOSE exec -T redis redis-cli --no-auth-warning -a "$REDIS_PASSWORD" XLEN story:workflow:events
```

检查 Worker 是否 running、Redis 密码是否一致、模型供应商是否可访问。不要直接清空 Redis Stream。

### AI 返回 401/404/超时

检查 `OPENAI_BASE_URL` 是否为供应商的 OpenAI-compatible 根地址、模型名是否可用、Key 是否有额度。
Pilot 的 `OPENAI_FALLBACK_ENABLED=false` 是故意的，不能为了绕过真实故障打开本地模板回退。

### 前端 API 仍指向 localhost

`PUBLIC_API_BASE_URL` 在前端镜像构建时注入，不是运行时动态读取。修改后必须重新构建 Frontend：

```bash
$COMPOSE up -d --build frontend caddy
```

### 内存不足或容器反复重启

```bash
free -h
docker stats --no-stream
$COMPOSE ps
$COMPOSE events --since 30m
```

先停止真实 AI 冒烟，确认 Swap 和磁盘空间；优先升级到 4 vCPU/8 GB，不要简单删除数据库 volume。

## 13. 最终交付清单

部署 Agent 只有在以下项目全部满足时才可报告“部署完成”：

- [ ] 服务器配置达到最低要求，Docker 和 Compose 可用。
- [ ] DNS A/AAAA 和云安全组已验证。
- [ ] `deploy/.env.pilot` 已填写且权限为 `600`，没有空的必需变量。
- [ ] `docker compose ... config --quiet` 通过。
- [ ] Caddy 配置验证通过。
- [ ] 八个服务均已启动，Worker 处于 running。
- [ ] Flyway 迁移版本至少为 9。
- [ ] `https://<PUBLIC_HOST>/api/health` 返回 `{"status":"ok"}`。
- [ ] 3306/6379/8080/5173 未暴露到公网。
- [ ] 浏览器登录、创建故事、生成选题、工作流审核和批准均成功。
- [ ] `smoke_workflow.py` 成功；章节冒烟在确认预算后执行。
- [ ] 审核超时释放逻辑已在测试环境验证。
- [ ] 健康检查已加入 cron 或外部监控。
- [ ] 已完成一次备份和 `DRY_RUN=1` 恢复校验。
- [ ] 备份已复制到独立存储。
- [ ] 没有把任何 Secret、JWT 或模型 Key 提交到 Git。

## 14. 部署完成后下一步

不要立即开放大规模用户。建议按以下顺序推进：

1. 先邀请 3～5 名可信用户，观察 AI 成本、任务积压、审核完成率和错误率。
2. 每天查看 `pilot-healthcheck.sh`、模型供应商账单和 Redis Stream 积压。
3. 收集选题价值、人物/大纲质量、章节可编辑性和审核超时反馈。
4. 再实现“过期工作流重新开始”入口和前端审核倒计时；当前过期后会提示新建故事。
5. 用户超过 20 人或需要多副本前，先升级配置、迁移共享限流和托管数据库/Redis，
   不要直接横向复制当前单机 Compose。
