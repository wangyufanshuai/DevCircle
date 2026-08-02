# MysticVerse 单机生产运行手册

## 前置条件

- Linux 4 vCPU / 16 GB RAM / 200 GB SSD，Docker Engine 与 Compose v2。
- 正式域名的 A/AAAA 记录已指向主机，80/443 可入站；Caddy 自动申请 TLS。
- 已准备隐私政策、用户协议及适用的备案/合规材料。
- 从 `.env.production.example` 创建权限为 `0600` 的 `.env.production`，所有占位值都必须替换；`IMAGE_TAG` 必须是 40 位提交 SHA。
- 密钥使用 Base64URL 等不含 `$` 的字符集；Compose 会把未转义的 `$变量` 当作插值并可能静默截断密码。

## 首次发布

```bash
chmod +x scripts/ops/*.sh
scripts/ops/validate-production-env.sh .env.production
docker compose --env-file .env.production -f docker-compose.prod.yml config --quiet
python scripts/ops/assert-production-resource-budgets.py --env-file .env.production
docker compose --env-file .env.production -f docker-compose.prod.yml pull
docker compose --env-file .env.production -f docker-compose.prod.yml up -d mysql redis kafka elasticsearch minio
docker compose --env-file .env.production -f docker-compose.prod.yml up -d
```

生产 Compose 将21个容器的内存上限控制在合计14 GiB，只允许 `web` 与 `dc-gateway` 发布宿主机端口。14个 JVM 服务单实例限制为512 MiB、0.75 CPU和256个 PID，Java 最大堆按容器内存的60%计算；数据库连接池默认每服务最多8个连接。MySQL、Kafka和Elasticsearch分别设置显式缓存或堆上限，为16GB目标主机保留约2GB给操作系统与 Docker。该预算是防止资源失控的上限，不代表性能验收已经通过；发布前仍必须执行50并发 k6 和目标主机观测。

生产编排只发布 Caddy Web 的 80/443 和 Gateway 的 8080。MySQL、Redis、Kafka、Elasticsearch、MinIO 与领域服务只在内部网络可达。若不需要直接访问 Gateway，应由主机防火墙把 8080 限制为运维网段。

## 迁移顺序

1. 运行 `scripts/ops/backup.sh`，保留生成目录和 `SHA256SUMS`。
2. 对推荐消费者先执行优雅停机并确认无活跃消费，再发布兼容旧表结构的新镜像，让 Flyway 建立新表/字段；包含 `recommend_consumer_dedup.lease_token` 的迁移完成后，只启动支持 lease token 的新 `dc-recommend` 镜像，避免旧 worker 在过期租约后覆盖新 worker 状态。
3. 迁移主题内容，运行 `EXPECTED_POST_COUNT=180 scripts/ops/verify-migration.sh` 校验数量和内容校验和。
4. 触发 Elasticsearch 全量重建，再次运行校验脚本确认无非 `PUBLISHED` 文档。
5. 将入口切为只读验证，抽查登录、详情、搜索、媒体和审核链路。
6. 解除只读并观察错误率、Outbox backlog、磁盘与容器重启次数至少 30 分钟。

数据库迁移必须保持前一版本镜像可读取；删除列、改名和收紧约束要拆成至少两个版本。

## 备份、恢复与演练

建议 root crontab：

```cron
17 3 * * * ENV_FILE=/srv/devcircle/.env.production BACKUP_ROOT=/srv/backups/devcircle /srv/devcircle/scripts/ops/backup.sh >>/var/log/devcircle-backup.log 2>&1
*/5 * * * * DISK_THRESHOLD_PERCENT=80 /srv/devcircle/scripts/ops/check-disk.sh / >>/var/log/devcircle-disk.log 2>&1
```

每月至少在隔离主机执行一次：

```bash
CONFIRM_RESTORE=RESTORE scripts/ops/restore.sh /srv/backups/devcircle/<timestamp>
EXPECTED_POST_COUNT=180 scripts/ops/verify-migration.sh
```

在隔离恢复主机上没有应用容器时，使用 `RESTORE_MANAGE_APPLICATIONS=false`；生产主机保持默认值，让脚本在恢复前停止应用并在成功后重新启动。

本地隔离演练证据记录在 [`recovery-rehearsal.md`](recovery-rehearsal.md)。

记录恢复开始/结束时间、内容数量、校验和、媒体抽查和 RTO。

## 发布与回滚

发布时只修改 `.env.production` 的 `IMAGE_TAG` 为新提交 SHA，然后：

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml pull
docker compose --env-file .env.production -f docker-compose.prod.yml up -d
```

保留上一 SHA 和迁移前备份。需要回滚时：

```bash
PREVIOUS_IMAGE_TAG=<previous-sha> scripts/ops/rollback.sh /srv/backups/devcircle/<pre-migration-timestamp>
```

若新迁移仍向前兼容，可只回滚镜像；只有发生不兼容数据写入时才恢复数据库和媒体备份。

## 日常检查

- `docker compose ... ps`：所有健康检查通过且无重启循环。
- `/actuator/health/readiness`：Gateway 与内部服务均为 UP。
- Outbox 无持续增长的 `FAILED/DEAD`，DLT 有告警和人工重放记录。
- DLT 发布等待时间由 `KAFKA_DLT_PUBLISH_TIMEOUT_MS` 控制（默认 5000ms）；DLT broker 未确认时源消息不得提交 offset。
- 磁盘低于 80%，备份校验和每天成功，证书续期无错误。
- 写接口失败时必须带公开错误码和 `traceId`，日志中按 `traceId` 关联，禁止向页面暴露内部服务名或堆栈。
