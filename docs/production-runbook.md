# MysticVerse 单机生产运行手册

## 前置条件

- Linux 4 vCPU / 16 GB RAM / 200 GB SSD，Docker Engine 与 Compose v2。
- 正式域名的 A/AAAA 记录已指向主机，80/443 可入站；Caddy 自动申请 TLS。
- 已准备隐私政策、用户协议及适用的备案/合规材料。
- 从 `.env.production.example` 创建权限为 `0600` 的 `.env.production`，所有占位值都必须替换；`IMAGE_TAG` 必须是 40 位提交 SHA。
- 从 `deploy/alertmanager/alertmanager.example.yml` 创建宿主机私有配置，替换 webhook，并将其绝对路径写入 `ALERTMANAGER_CONFIG_PATH`；真实通知凭据不得提交仓库。
- 密钥使用 Base64URL 等不含 `$` 的字符集；Compose 会把未转义的 `$变量` 当作插值并可能静默截断密码。

## 首次发布

```bash
chmod +x scripts/ops/*.sh
scripts/ops/validate-production-env.sh .env.production
docker compose --env-file .env.production -f docker-compose.prod.yml config --quiet
python scripts/ops/assert-production-resource-budgets.py --env-file .env.production
docker compose --env-file .env.production -f docker-compose.prod.yml pull
scripts/ops/verify-release-identity.sh .env.production
docker compose --env-file .env.production -f docker-compose.prod.yml up -d mysql redis kafka elasticsearch minio
docker compose --env-file .env.production -f docker-compose.prod.yml up -d
```

生产 Compose 将24个容器的内存上限控制在约14.63 GiB，只允许 `web` 与 `dc-gateway` 发布宿主机端口。14个 JVM 服务单实例限制为512 MiB、0.75 CPU和256个 PID，Java 最大堆按容器内存的60%计算；数据库连接池默认每服务最多8个连接。全部生产容器统一使用 `json-file` 日志驱动，每个日志文件最多20 MiB并保留5份；资源门禁会拒绝任何服务缺少该轮转契约。内部 Prometheus 保留35天/最多1GB指标，Alertmanager使用宿主机私有路由配置，Jaeger使用持久化Badger存储。该预算是防止资源失控的上限，不代表性能验收已经通过；发布前仍必须执行50并发 k6 和目标主机观测。

生产编排发布 Caddy Web 的 80/443，并仅把 Gateway 的 8080 绑定到宿主机 `127.0.0.1`，供本机诊断使用。公网 API 统一经过 Caddy 与 Web Nginx 转发；MySQL、Redis、Kafka、Elasticsearch、MinIO、Gateway Actuator 与领域服务均不能从公网直接访问。Caddy 管理 API 同样只监听容器内 localhost。

## 迁移顺序

1. 运行 `scripts/ops/backup.sh`，保留生成目录、`backup-manifest.json` 和 `SHA256SUMS`。备份期间内容发生变化时脚本会删除不完整产物并失败，应在写入低峰重试。
2. 对推荐消费者先执行优雅停机并确认无活跃消费，再发布兼容旧表结构的新镜像，让 Flyway 建立新表/字段；包含 `recommend_consumer_dedup.lease_token` 的迁移完成后，只启动支持 lease token 的新 `dc-recommend` 镜像，避免旧 worker 在过期租约后覆盖新 worker 状态。
3. 引入评论/点赞权威计数快照时，先停止旧 `dc-comment` 和 `dc-interaction` 写入实例，再执行 `dc-content` V21、`dc-comment` V6 与 `dc-interaction` V4 迁移，最后启动同时发布 `counterVersion` 的新实例。禁止在评论计数回填完成后重新启动不会维护 `post_comment_counter` 的旧评论镜像。
4. 迁移主题内容，运行 `EXPECTED_POST_COUNT=180 scripts/ops/verify-migration.sh` 校验数量和内容校验和。
5. 触发 Elasticsearch 全量重建，再次运行校验脚本确认无非 `PUBLISHED` 文档。
6. 将入口切为只读验证，抽查登录、详情、搜索、媒体和审核链路。
7. 解除只读并观察错误率、Outbox backlog、磁盘与容器重启次数至少 30 分钟。

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
BACKUP_MANIFEST=/srv/backups/devcircle/<timestamp>/backup-manifest.json \
  scripts/ops/verify-migration.sh
```

在隔离恢复主机上没有应用容器时，使用 `RESTORE_MANAGE_APPLICATIONS=false`；生产主机保持默认值，让脚本在恢复前停止应用并在成功后重新启动。

新备份必须包含 Manifest。恢复上一版本生成的旧格式备份时，需要先人工验证目录和 `SHA256SUMS`，然后显式设置 `ALLOW_LEGACY_BACKUP=true`；该开关不得写入长期生产环境变量。Manifest 与恢复后的文章数量、公开数量或内容哈希不一致时，恢复脚本会保持应用停止，不得绕过后直接启动。

本地隔离演练证据记录在 [`recovery-rehearsal.md`](recovery-rehearsal.md)。

记录恢复开始/结束时间、内容数量、校验和、媒体抽查和 RTO。

## 发布与回滚

发布时只修改 `.env.production` 的 `IMAGE_TAG` 为新提交 SHA，然后：

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml pull
scripts/ops/verify-release-identity.sh .env.production
docker compose --env-file .env.production -f docker-compose.prod.yml up -d
```

保留上一 SHA 和迁移前备份。需要回滚时：

```bash
PREVIOUS_IMAGE_TAG=<previous-sha> scripts/ops/rollback.sh /srv/backups/devcircle/<pre-migration-timestamp>
```

若新迁移仍向前兼容，可只回滚镜像；只有发生不兼容数据写入时才恢复数据库和媒体备份。

## Kafka DLT 人工重放

只有在消费者依赖已经恢复、失败原因已经确认且自动重试无法继续时，才允许人工重放。先从告警或 Kafka 工具确定 DLT 记录的位置，并保留原始 topic（请求中不填写 `.DLT` 后缀）、partition、offset、事件 ID、故障原因和工单引用。当前仅允许服务端白名单内的版本化领域事件；接口还会校验 Spring Kafka 原始 topic header、事件生产者与事件信封。

```bash
curl -fsS -X POST https://<domain>/api/admin/ops/dlt/replay \
  -H 'Authorization: Bearer <admin-access-token>' \
  -H 'Idempotency-Key: dlt-audit-completed-0-42' \
  -H 'Content-Type: application/json' \
  --data '{
    "sourceTopic": "audit.completed",
    "partition": 0,
    "offset": 42,
    "reason": "Elasticsearch 已恢复；变更单 OPS-2026-0812 批准重放。"
  }'
```

成功后必须同时核对：源 topic 只新增一条消息、目标消费者状态转为 `COMPLETED`、业务投影只生效一次、`kafka_dlt_replay` 有唯一位置记录、`audit_log` 存在 `REPLAY_KAFKA_DLT`。响应中的 `duplicateRequest=true` 表示同键安全重试，没有再次发布。Kafka 发布失败会回滚重放记录并写入 `REPLAY_KAFKA_DLT_FAILED`；依赖恢复后使用完全相同的参数和幂等键重试。禁止换键绕过失败或重复处理同一 DLT 位置。

## 日常检查

- `docker compose ... ps`：所有健康检查通过且无重启循环。
- `/actuator/health/readiness`：Gateway 与内部服务均为 UP。
- Outbox 无持续增长的 `FAILED/DEAD`，DLT 有告警和人工重放记录。
- `/api/admin/ops/health` 中必须为 `outboxScope=FLEET`、`outboxComplete=true`，且 `outboxDead` 与 `outboxBlockedByDead` 为 0。检查 `outboxSources` 确认 Auth、User、Content、Comment、Interaction 五域均为 `UP`；出现 `OUTBOX_SOURCE_UNAVAILABLE` 时先恢复对应 `unavailableOutboxSources`，不能把当前已知总量视为全局零积压。
- Outbox 每批数量、最大尝试次数和发送等待分别由 `OUTBOX_BATCH_SIZE`（默认 50）、`OUTBOX_MAX_ATTEMPTS`（默认 8）和 `OUTBOX_SEND_TIMEOUT_MS`（默认 15000ms）注入。调整重试上限前必须评估故障持续时间与告警响应时间。
- DLT 发布等待时间由 `KAFKA_DLT_PUBLISH_TIMEOUT_MS` 控制（默认 5000ms）；DLT broker 未确认时源消息不得提交 offset。
- 磁盘低于 80%，备份校验和每天成功，证书续期无错误。
- 抽查 `docker inspect` 的 `HostConfig.LogConfig`，所有生产容器均为 `json-file`、`max-size=20m`、`max-file=5`；日志策略漂移必须阻断发布。
- 写接口失败时必须带公开错误码和 `traceId`，日志中按 `traceId` 关联，禁止向页面暴露内部服务名或堆栈。
- `devcircle:gateway_availability:ratio30d` 不低于 0.995，读取/写入 P95 分别低于 0.3/0.6 秒；燃烧率告警按 [`observability-runbook.md`](observability-runbook.md) 处置。
- Alertmanager 必须有真实通知接收方并完成一次测试告警；生产不得使用仓库中的 `dev-null` 或 `.invalid` 示例接收器。
