# StoryForge AI 部署前置依赖清单

> 适用场景：全新的 Ubuntu 24.04 云服务器，已预装 Docker 29.6.1。
> 用途：交给另一台电脑上的 GPT/CLI Agent，在执行正式部署前完成主机检查和依赖准备。
> 后续手册：[`ai-deployment-runbook.md`](./ai-deployment-runbook.md)

## 1. 结论

Docker Engine 29.6.1 可以直接使用，不需要降级。必须额外确认 Docker Compose Plugin
可用，并安装少量宿主机工具。

使用 Docker Compose 部署时，宿主机不需要安装：

- Node.js / npm
- JDK / Maven
- Python 虚拟环境
- MySQL / Redis / Nginx / Caddy

宿主机只需要 Python 3 用于运行部署冒烟脚本；应用运行时依赖都在 Docker 镜像内。

## 2. Agent 安全规则

1. 任何安装或配置命令失败都要停止并保留错误输出。
2. 不要执行 `ufw reset`、`docker compose down -v`、删除 Docker volume 或格式化磁盘。
3. 修改防火墙前必须确认当前 SSH 端口，避免锁死远程连接。
4. 不要在聊天记录、日志或截图中输出密码、JWT、Basic Auth 明文密码或模型 API Key。
5. 缺少域名、模型 Key 或邮箱时，向用户索要具体值；不要自行生成业务凭据或猜测模型名。

## 3. 推荐服务器规格

| 项目 | 最低可运行 | 建议内测 |
| --- | --- | --- |
| 操作系统 | Ubuntu 24.04 LTS 64 位 | Ubuntu 24.04 LTS 64 位 |
| 架构 | `amd64/x86_64` | `amd64/x86_64` |
| CPU | 2 vCPU | 4 vCPU |
| 内存 | 4 GB RAM + 2 GB Swap | 8 GB RAM |
| 系统盘 | 60 GB SSD | 80～100 GB SSD |
| 公网带宽 | 10 Mbps | 20 Mbps+ |

最低配置只能使用远程模型和低并发邀请制 Pilot，不要在服务器运行 Ollama 或本地 7B 模型。

## 4. 主机预检查

以下命令不修改系统，仅用于确认环境：

```bash
set -e

uname -m
cat /etc/os-release
free -h
df -h /
swapon --show
```
期望结果：

- `/etc/os-release` 为 Ubuntu 24.04 LTS。
- `uname -m` 为 `x86_64`（ARM64 需要确认所有镜像架构可用）。
- 根分区至少有 60 GB 总容量，并保留足够可用空间。
- 4 GB 主机建议存在至少 1～2 GB Swap。

确认系统时间同步：

```bash
timedatectl status
sudo timedatectl set-ntp true
```

HTTPS、JWT 过期时间、MySQL 时间和 Caddy 证书都依赖正确的系统时间。

## 5. 安装宿主机工具

```bash
sudo apt-get update
sudo apt-get install -y \
  ca-certificates \
  curl \
  git \
  openssl \
  jq \
  ufw \
  python3 \
  dnsutils
```

验证：

```bash
git --version
curl --version | head -1
openssl version
jq --version
python3 --version
dig -v
```

`dnsutils` 只用于 `dig` DNS 检查；如果云镜像已经提供 `getent`，也可以使用：

```bash
getent ahosts story.example.com
```

## 6. 验证 Docker 29.6.1

```bash
docker --version
docker compose version
docker buildx version
sudo systemctl enable --now docker
sudo systemctl is-active docker
```

最低要求：

- Docker Engine 29.6.1 或兼容的 Docker 24+。
- `docker compose version` 能正常输出 Compose Plugin 版本。
- `docker buildx version` 能正常输出版本。

执行一次无副作用运行时检查：

```bash
docker run --rm hello-world
```

### Compose Plugin 不存在时

先尝试系统包：

```bash
if ! docker compose version >/dev/null 2>&1; then
  sudo apt-get install -y docker-compose-plugin
fi
docker compose version
```

如果系统包不存在，使用 Docker 官方仓库安装。不要安装旧的 Python `docker-compose` 1.x：

```bash
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt-get update
sudo apt-get install -y docker-compose-plugin docker-buildx-plugin
docker compose version
```

## 7. Docker 用户权限

如果 Agent 使用普通 SSH 用户，加入 Docker 用户组并重新登录：

```bash
sudo usermod -aG docker "$USER"
newgrp docker
docker ps
```

也可以退出 SSH 后重新连接。若 Agent 全程以 root 身份执行，则不需要加入用户组，但不建议让应用容器以 root 运行；仓库 Dockerfile 已为应用创建非 root 用户。

## 8. Swap 和磁盘准备

先检查已有 Swap：

```bash
swapon --show
free -h
```

如果没有 Swap，且服务器有足够磁盘，可创建 2 GB：

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
grep -q '^/swapfile ' /etc/fstab || \
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
swapon --show
```

Swap 只能缓解 Docker 构建或备份时的瞬时峰值，不能替代物理内存。不要把 Swap 放满后继续启动更多 Worker。

## 9. 防火墙和云安全组

### 9.1 UFW

如果 SSH 是默认 22 端口：

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw --force enable
sudo ufw status verbose
```

如果 SSH 使用其他端口，先放行实际端口，例如：

```bash
sudo ufw allow 2222/tcp
```

再启用 UFW。不要在未确认 SSH 端口前启用或重置防火墙。

### 9.2 云厂商安全组

安全组至少允许：

| 方向 | 协议/端口 | 用途 |
| --- | --- | --- |
| 入站 | SSH 实际端口/TCP | 服务器管理，建议限制固定办公 IP |
| 入站 | 80/TCP | Caddy ACME 验证和 HTTP 跳转 |
| 入站 | 443/TCP | HTTPS Web、API、SSE |
| 入站 | 443/UDP | 可选，HTTP/3 |
| 出站 | 443/TCP | Docker Hub、模型供应商、证书和告警服务 |

不要开放 MySQL 3306、Redis 6379、Backend 8080、Frontend 5173。Compose 会把它们绑定到服务器回环地址。

## 10. DNS、出网和域名准备

部署前由用户完成 DNS：

1. `PUBLIC_HOST` 的 A 记录指向服务器 IPv4。
2. 只有在 IPv6 路由确认可用时才配置 AAAA 记录。
3. DNS 生效后再启动 Caddy。

验证：

```bash
getent ahosts story.example.com
dig +short A story.example.com
dig +short AAAA story.example.com
```

确认出站网络：

```bash
curl -sSIL --max-time 10 https://github.com | head
curl -sSIL --max-time 10 https://download.docker.com | head
```

模型供应商的 OpenAI-compatible 根地址也必须能从服务器访问。不要把模型 Key 放进 URL 或前端环境变量。

## 11. 部署所需人工输入

在复制 `deploy/pilot.env.example` 前，Agent 必须拿到以下值：

| 值 | 用途 |
| --- | --- |
| `PUBLIC_HOST` | 公网域名 |
| `ACME_EMAIL` | Caddy TLS 证书邮箱 |
| `OPENAI_API_KEY` | 远程模型 Key |
| `OPENAI_BASE_URL` | OpenAI-compatible `/v1` 根地址 |
| `OPENAI_MODEL` | 账号可用模型名 |
| `PILOT_BASIC_AUTH_USER` | 邀请制入口用户名 |
| Basic Auth 明文密码 | 只用于生成 bcrypt 哈希 |

以下密钥由 Agent 在服务器本地使用 `openssl rand -hex 32` 分别生成，每个值必须不同：

- `MYSQL_PASSWORD`
- `MYSQL_ROOT_PASSWORD`
- `REDIS_PASSWORD`
- `JWT_SECRET`
- `AI_INTERNAL_API_KEY`
- `PILOT_METRICS_KEY`

## 12. 前置依赖最终验收

执行以下检查；全部通过后，才进入 `ai-deployment-runbook.md` 第 4 节：

```bash
set -e
test "$(. /etc/os-release && echo "$VERSION_ID")" = "24.04"
test "$(command -v docker)" != ""
docker info >/dev/null
docker compose version >/dev/null
docker buildx version >/dev/null
test "$(command -v git)" != ""
test "$(command -v curl)" != ""
test "$(command -v openssl)" != ""
test "$(command -v python3)" != ""
test "$(command -v jq)" != ""
test "$(command -v dig)" != ""
df -Pk / | awk 'NR == 2 { if ($4 < 10485760) exit 1 }'
docker run --rm hello-world >/dev/null
echo 'deployment prerequisites: OK'
```

磁盘检查中的 `10485760` 约等于 10 GB 可用空间；实际部署建议留出更多空间用于镜像、日志、数据库和备份。

## 13. 完成前置依赖后的下一步

```bash
cd /opt/argus
git clone --branch develop https://github.com/Redchar1992/Argus.git
cd /opt/argus/story-forge-ai
```

如果仓库已经存在，不要重复 clone，改为：

```bash
cd /opt/argus
git fetch origin
git checkout develop
git pull --ff-only origin develop
cd /opt/argus/story-forge-ai
```

然后严格执行：

```text
docs/ai-deployment-runbook.md
```
