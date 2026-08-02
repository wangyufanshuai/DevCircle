# 本地备份恢复演练记录

## 2026-07-30 · 隔离 Docker 演练

本次演练使用独立 Compose project `devcircle-ops-rehearsal` 和独立数据卷，只启动生产配置中的 MySQL 8.4.6、MinIO 与按需维护容器。现有开发环境、容器和数据卷未参与演练。

演练步骤：

1. 在 `dc_content.restore_probe` 写入数据库标记 `before-backup`。
2. 在 MinIO 数据卷写入对象标记 `before-backup`。
3. 运行 `scripts/ops/backup.sh`，生成压缩 SQL、对象数据归档和 `SHA256SUMS`。
4. 将数据库和对象标记都改为 `after-backup`。
5. 使用 `CONFIRM_RESTORE=RESTORE` 和 `RESTORE_MANAGE_APPLICATIONS=false` 运行恢复脚本。
6. 重新读取数据库和对象标记，二者均恢复为 `before-backup`。
7. 销毁隔离容器、网络和数据卷，并确认无演练资源残留。

结果：

- MySQL 备份与恢复：通过。
- MinIO 数据卷备份与恢复：通过。
- 两个备份文件 SHA-256 校验：通过。
- 最小数据集恢复命令耗时约 4.3 秒；该结果只证明脚本链路，不代表生产数据量下的 RTO。
- `test-backup-restore.sh` 覆盖备份产物、校验和、恢复顺序、迁移计数以及 Elasticsearch 非公开文档阻断。

演练过程中实际发现并修复：

- `mysqldump` 与迁移校验命令中的字面量补丁标记。
- MinIO 官方镜像不包含 `tar`，改由只读根文件系统的按需维护容器访问数据卷。
- Compose 会再次插值 `.env` 中未转义的 `$变量`，生产环境校验现已拒绝包含 `$` 的密钥。
- Elasticsearch 校验原先只打印异常数量；现在非零数量或无法解析响应都会阻断切换。
- 迁移校验原先只检查 ES 中非公开文档数量，空索引可能被误判为通过；现在使用实际索引名 `devcircle-posts`，同时要求数据库 `PUBLISHED` 数量与 ES 总文档数一致，并保留非公开文档为零的约束。

尚需在目标 Linux 主机完成一次包含 180 条内容、Elasticsearch 重建和全部应用服务的正式恢复演练，并记录实际 RTO、磁盘吞吐和服务恢复时间。
