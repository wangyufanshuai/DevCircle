# 本地备份恢复演练记录

## 2026-08-15 · 当前 SHA 的 180 条内容真实恢复演练

本次在提交 `36afeb1c6dc096a2a0962adbc2ed710827506816` 上使用 Docker Desktop 29.6.2 Linux Engine（可用内存 6,218,960,896 bytes）执行。启动前确认没有正在运行的 Compose project；生产 Compose 的固定项目名为 `devcircle-prod`，本轮使用新建的 MySQL、MinIO 卷，只启动 MySQL 8.4.6、MinIO 和按需维护容器，不接触历史 `devcircle-*` 停止容器或其他项目数据。

在 `dc_content` 写入 180 条互不重复且全部公开的内容、正文、线索和解析，并在 MinIO 卷写入对象标记后，真实执行 `scripts/ops/backup.sh`：

- 备份命令耗时 4.35 秒；
- Manifest 记录 `postCount=180`、`publishedPostCount=180`；
- 内容哈希为 `ee55895f0aeeba0e1de651defe2fa4514d0f271035b745575cdb0df145b32ee9`；
- MySQL 归档摘要为 `ce0828523bf0f62dcb09c2aba44d8fa2d66386cb556233a4e8311ef8fa0e081e`；
- MinIO 归档摘要为 `cff43326fae619affb331ccc9d1cbe2e36571cb4d3bfdb3d9689684f59a4d8d1`。

随后将第 1 条标题改为 `tampered-after-backup`、删除第 180 条内容，并把 MinIO 标记改为 `after-backup-tamper`。破坏后数据库明确为 179/179。使用 `CONFIRM_RESTORE=RESTORE`、`RESTORE_MANAGE_APPLICATIONS=false` 真实执行 `scripts/ops/restore.sh`：

- `mysql.sql.gz`、`minio-data.tar.gz` 与 `backup-manifest.json` 的 `SHA256SUMS` 全部通过；
- 恢复命令耗时 4.39 秒；
- 恢复脚本返回前完成 Manifest 业务基线校验；
- 独立复核得到 180/180，第 1 条与第 180 条标题分别为 `drill-post-1`、`drill-post-180`；
- 独立重算内容哈希与 Manifest 完全一致；
- MinIO 对象恢复为备份值 `before-backup-36afeb1`；
- 演练容器、网络、卷、临时凭据、包装器和备份产物均已清理。

该结果证明当前 SHA 的 MySQL/MinIO 备份、破坏、恢复和业务哈希闭环可执行，但不是目标 Linux 主机的正式 RTO/RPO。演练没有启动全部应用，也没有执行 Elasticsearch 全量重建、Kafka 追平、媒体抽查或公网健康探测。

## 2026-08-08 · 180 条内容真实恢复演练

本次使用独立 Compose project `devcircle-recovery-drill`，只启动生产 Compose 中的 MySQL 8.4.6、MinIO 和按需维护容器；项目网络、`mysql-data` 与 `minio-data` 卷均为本次演练新建，结束后已销毁。演练使用提交 `92280f106b22153ce84244a4a33359807f911be4` 和一次性伪生产配置，不接触现有数据。

在 `dc_content` 中写入 180 条互不重复的已发布内容及正文、线索和解析后，真实执行 `backup.sh`。备份耗时约 8.2 秒，Manifest 记录 `postCount=180`、`publishedPostCount=180` 和内容哈希 `3011126d2b145a7e94ec8cb777d8d126d85573f38cb9abc8c1680912091ae600`。随后故意修改第 1 条标题并删除第 180 条内容，再以 `RESTORE_MANAGE_APPLICATIONS=false` 执行 `restore.sh`：

- `mysql.sql.gz`、`minio-data.tar.gz`、`backup-manifest.json` 三项 SHA-256 校验通过；
- 恢复命令耗时约 6.1 秒；
- 恢复后总数与公开数均为 180，第 1 条标题从篡改值恢复为 `drill-post-1`；
- 恢复脚本在返回成功前重新计算并匹配 Manifest 的数量与内容哈希；
- 演练容器、网络、卷、备份产物和一次性配置均已清理。

这是对 180 条主题内容规模下 MySQL 与 MinIO 数据链的真实恢复证据，但仍不是目标 Linux 主机的正式 RTO：本次没有启动全部应用，也没有包含 Elasticsearch 全量重建、Kafka 追平、媒体人工抽查和公网健康探测。

## 2026-07-30 · 隔离 Docker 演练

本次演练使用独立 Compose project `devcircle-ops-rehearsal` 和独立数据卷，只启动生产配置中的 MySQL 8.4.6、MinIO 与按需维护容器。现有开发环境、容器和数据卷未参与演练。

演练步骤：

1. 在 `dc_content.restore_probe` 写入数据库标记 `before-backup`。
2. 在 MinIO 数据卷写入对象标记 `before-backup`。
3. 运行 `scripts/ops/backup.sh`，生成压缩 SQL、对象数据归档、`backup-manifest.json` 和 `SHA256SUMS`。脚本会在备份前后读取内容基线；文章数量、公开数量或内容哈希发生变化时废弃本次备份，避免发布与数据快照不一致的 Manifest。
4. 将数据库和对象标记都改为 `after-backup`。
5. 使用 `CONFIRM_RESTORE=RESTORE` 和 `RESTORE_MANAGE_APPLICATIONS=false` 运行恢复脚本。
6. 重新读取数据库和对象标记，二者均恢复为 `before-backup`。
7. 销毁隔离容器、网络和数据卷，并确认无演练资源残留。

结果：

- MySQL 备份与恢复：通过。
- MinIO 数据卷备份与恢复：通过。
- MySQL、MinIO 与 Manifest 三个备份文件 SHA-256 校验：通过。
- `backup-manifest.json` 记录备份时间、Git 提交、镜像 SHA、文章数量、公开数量、内容哈希以及 MySQL/MinIO 文件摘要；恢复前会校验产物，恢复数据库后会在应用重启前自动核对业务基线。
- 最小数据集恢复命令耗时约 4.3 秒；该结果只证明脚本链路，不代表生产数据量下的 RTO。
- `test-backup-restore.sh` 覆盖 Manifest 正常恢复、产物被篡改、Manifest 内部摘要不一致、恢复后数量/内容哈希不一致、旧备份显式兼容、迁移计数以及 Elasticsearch 非公开文档阻断。

演练过程中实际发现并修复：

- `mysqldump` 与迁移校验命令中的字面量补丁标记。
- MinIO 官方镜像不包含 `tar`，改由只读根文件系统的按需维护容器访问数据卷。
- Compose 会再次插值 `.env` 中未转义的 `$变量`，生产环境校验现已拒绝包含 `$` 的密钥。
- Elasticsearch 校验原先只打印异常数量；现在非零数量或无法解析响应都会阻断切换。
- 迁移校验原先只检查 ES 中非公开文档数量，空索引可能被误判为通过；现在使用实际索引名 `devcircle-posts`，同时要求数据库 `PUBLISHED` 数量与 ES 总文档数一致，并保留非公开文档为零的约束。

尚需在目标 Linux 主机完成包含 Elasticsearch 重建、Kafka 追平和全部应用服务的正式恢复演练，并记录实际 RTO、磁盘吞吐和服务恢复时间。

## 目标 Linux 正式演练入口（尚未执行）

正式演练必须在与生产等价的 Linux 主机执行，不能用 Windows Docker、局部测试报告或旧的 `target` 目录替代。主机最低要求为 4 CPU、15 GiB 内存、180 GiB 可用文件系统；工作区必须干净，`IMAGE_TAG` 必须等于当前 Git SHA。

建议按以下顺序操作，并把每一步的原始输出写入同一个证据目录：

1. 使用 `scripts/performance/run-linux-acceptance.sh` 先采集宿主机、Compose、容器资源、k6 和 Prometheus 基线。
2. 使用 `scripts/ops/backup.sh` 生成带 `backup-manifest.json` 与 `SHA256SUMS` 的备份，并记录开始/结束时间。
3. 在维护窗口停止应用服务，按 `scripts/ops/restore.sh` 从指定备份恢复 MySQL 与 MinIO。
4. 执行 `scripts/ops/verify-migration.sh`，随后记录 Elasticsearch 全量重建、文档数量与非公开文档为零的校验结果。
5. 恢复 Kafka 消费，记录 Outbox backlog、消费追平时间；如果进入 DLT，使用管理员接口人工重放并保存请求/响应摘要。
6. 启动全部应用服务，保存每个聚合门面和公网 Web 的 readiness/health 响应，再执行匿名浏览与登录写操作探测。
7. 将证据文件放入统一目录后执行：

   ```bash
   RECOVERY_EVIDENCE_CONFIRM=CONFIRM \
   scripts/ops/finalize-linux-recovery-evidence.sh /var/lib/devcircle/evidence/<run-id>
   ```

该校验器只验证证据，不会启动、停止或恢复任何服务。它要求 `host.json`、前后 Compose 状态、备份 Manifest、迁移校验、ES 重建、Kafka 追平、DLT 重放、服务健康、公网探测和 `recovery-timeline.json` 全部为 `PASS`，并重新生成 `recovery-summary.json` 与 `SHA256SUMS`。在该命令成功前，不能宣称正式 RTO/RPO 已达标。
