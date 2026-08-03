# StoryForge AI 云服务器最低配置

> 适用范围：单机、邀请制、远程 LLM、约 10～20 名可信内测用户。
> 本文的“最低配置”是基于当前 Docker Compose 资源上限和小规模内测场景的工程估算，
> 不是生产高可用配置。

## 1. 结论

### 最低可运行配置

| 项目 | 最低配置 |
| --- | --- |
| CPU | 2 vCPU |
| 内存 | 4 GB RAM + 2 GB Swap |
| 系统盘 | 60 GB SSD |
| 操作系统 | Ubuntu 24.04 LTS，64 位 |
| CPU 架构 | x86_64/amd64（ARM64 也可，但优先使用 x86_64） |
| 公网带宽 | 10 Mbps |
| 公网网络 | 公网 IP、域名、出站 HTTPS |

4 GB 仅适合远程模型和低并发邀请制内测。构建镜像、数据库恢复、并发生成时可能
出现内存压力；Swap 只能缓解瞬时峰值，不能替代物理内存。

### 建议配置

| 项目 | 建议配置 |
| --- | --- |
| CPU | 4 vCPU |
| 内存 | 8 GB RAM |
| 系统盘 | 80～100 GB SSD |
| 公网带宽 | 20 Mbps 或更高 |

建议配置适合持续内测、同时运行 Docker 构建和偶发的备份/恢复任务。

## 2. 当前 Compose 的资源依据

Pilot Compose 当前为各容器设置了以下内存上限：

| 容器 | 内存上限 |
| --- | ---: |
| MySQL | 768 MiB |
| Redis | 192 MiB |
| Spring Boot Backend | 640 MiB |
| AI Service | 384 MiB |
| AI Worker | 384 MiB |
| Chapter Worker | 512 MiB |
| Frontend | 96 MiB |
| Caddy | 96 MiB |
| **合计** | **3072 MiB** |

因此 4 GB 主机只剩约 1 GB 给操作系统、Docker、文件缓存和临时峰值；这就是它只能
作为“最低可运行配置”的原因。不要在该配置上运行本地 Ollama 或 7B 模型。

MySQL 8.4 文档说明，InnoDB Buffer Pool 默认值为 128 MB，并且 InnoDB 还会为控制
结构和其他缓冲区使用额外内存；整机容量不能只按 Buffer Pool 估算。

## 3. 必须满足的网络条件

### 必需端口

| 方向 | 端口 | 用途 |
| --- | --- | --- |
| 入站 | 80/TCP | Caddy ACME HTTP 校验和 HTTP→HTTPS 跳转 |
| 入站 | 443/TCP | HTTPS Web、API、SSE |
| 入站 | 22/TCP | SSH 管理，建议仅允许固定办公 IP |
| 出站 | 443/TCP | 访问远程模型供应商、镜像仓库和告警服务 |

443/UDP 仅在需要 HTTP/3 时开放；不开放时，HTTPS/TCP 仍可正常工作。

### DNS

1. 将域名的 A 记录指向服务器 IPv4 地址。
2. 使用 IPv6 时，再配置 AAAA 记录，并确认服务器 IPv6 可达。
3. DNS 生效后再启动 Caddy，否则证书申请会失败。

Caddy 会在配置了公开域名且 80/443 对公网可达时自动申请和续期 HTTPS 证书。

## 4. 部署前置条件

- 使用远程 `openai-compatible` 模型。
- `AI_REQUIRE_REMOTE_MODEL=true`。
- `OPENAI_FALLBACK_ENABLED=false`。
- 服务器端保存 `OPENAI_API_KEY`，绝不写入前端变量。
- 为 MySQL、Redis、JWT、AI 内部接口和指标接口分别生成独立随机密钥。
- 为 Caddy 配置 Basic Auth bcrypt 哈希，首批用户通过邀请进入。
- 为模型供应商创建独立项目，并设置硬预算、用量告警和异常调用告警。
- 准备独立备份位置；不要只把备份保存在同一台服务器的同一块磁盘上。

当前人物、大纲、章节工作流尚未完全接入 AI 积分扣费，因此第一批用户必须是可信的
邀请用户，供应商硬预算是上线门槛。

## 5. 磁盘容量建议

60 GB SSD 的最低分配建议如下：

| 用途 | 初始预留 |
| --- | ---: |
| Docker 镜像、构建缓存和日志 | 15 GB |
| MySQL、Redis、SQLite Checkpoint | 10 GB |
| 导出文件和临时文件 | 10 GB |
| 本机短期备份 | 15 GB |
| 系统和安全更新余量 | 10 GB |

正式环境应把备份同步到独立磁盘或对象存储，并保留至少 7 个日备份。故事正文、
章节版本和导出文件增长后，应优先扩容磁盘，而不是继续压缩 Redis 内存。

## 6. 上线前检查清单

- [ ] Ubuntu、Docker Engine、Docker Compose Plugin 安装完成。
- [ ] A/AAAA 记录已生效。
- [ ] 80/TCP、443/TCP 可从公网访问。
- [ ] `deploy/.env.pilot` 已填写，所有生产密钥均为随机值。
- [ ] `MODEL_PROVIDER=openai-compatible`，远程模型强制模式已开启。
- [ ] 模型供应商硬预算和用量告警已启用。
- [ ] `docker compose ... config --quiet` 通过。
- [ ] Caddy 配置校验通过。
- [ ] `deploy/pilot-healthcheck.sh` 已加入每 5 分钟 cron 或外部监控。
- [ ] 已执行一次 `DRY_RUN=1 deploy/restore.sh ...`。
- [ ] 已在隔离 Docker 卷完成一次真实恢复演练，覆盖 MySQL、Redis、SQLite 和导出文件。
- [ ] 已确认恢复失败时不会自动重新开放流量。

## 7. 何时升级配置

出现以下任一情况时，不应继续使用最低配置：

- 同时生成任务经常超过 2 个。
- Redis 内存告警或章节 Stream 积压持续出现。
- Docker 构建导致服务 OOM 或频繁重启。
- 内测用户超过 20 人，或开始接收非邀请用户。
- 需要多个 Backend 副本或跨可用区部署。
- 需要本地模型、向量库或更长的正文生成。

升级顺序建议：先升到 4 vCPU/8 GB，再将 MySQL、Redis、对象存储和备份迁移到独立
托管服务；扩容 Backend 前，先把当前单机 Auth 限流替换为 Redis 共享限流。

## 8. 官方参考

- [Docker Engine on Ubuntu](https://docs.docker.com/engine/install/ubuntu/)
- [Caddy HTTPS quick-start](https://caddyserver.com/docs/quick-starts/https)
- [Caddy running and HTTP/3 ports](https://caddyserver.com/docs/running)
- [MySQL 8.4 InnoDB system variables](https://dev.mysql.com/doc/refman/8.4/en/innodb-parameters.html)
