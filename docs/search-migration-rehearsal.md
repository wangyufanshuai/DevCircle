# 本机搜索索引迁移与回滚演练

入口：`python scripts/ops/rehearse_search_migration.py --maven-repo <本机 Maven 缓存目录>`。
它创建随机命名的独立 Compose 项目，使用真实 MySQL、Kafka、Elasticsearch 和旧／新搜索二进制；旧基线是两篇专用测试内容，新阶段再增加 19 篇，用于跨批次恢复验证。它不连接已有生产环境，也不迁移用户现有数据。

已完成的 Windows 本机验证见 [2026-10-14 演练证据](evidence/2026-10-14-search-index-migration.md)。需要 Git 克隆中包含下文固定旧提交；浅克隆须先补齐该提交。

## Windows 存储与启动

先确认 Docker Desktop 的磁盘镜像位置在 D/E 盘，Docker 可用内存至少 4 GiB，工作盘和 Docker 数据盘分别至少空闲 6 GiB。脚本检查这些条件，并拒绝将输出、Maven 缓存或 Docker 数据目录放到 C 盘。临时目录位于本次输出目录中。

```powershell
$env:TEMP = 'E:\xuexi\.codex-temp-devcircle'
$env:TMP = $env:TEMP
$env:DEVCIRCLE_TEST_MINIO_IMAGE = 'devcircle-minio-test:07c3a429bfed433e49018cb0f78a52145d4bedeb'
python -X utf8 scripts/ops/rehearse_search_migration.py `
  --maven-repo E:\xuexi\.cache\devcircle-maven `
  --output-root E:\xuexi\devcircle\artifacts\local\search-migration
```

需要已运行的 Docker Desktop、Java 21、Python、Git，以及已构建的固定版本 MinIO 测试镜像。可以先把现有 Maven 缓存复制到指定 E 盘目录；脚本使用显式 `maven.repo.local`，后续依赖写入该目录。网关和对象存储沿用本机验收端口 28080、29000，运行前确保没有其他任务占用。

## 演练顺序和成功条件

1. 从固定提交 `00a4b99ebb938120b0a3c22f806bb52bdfc50abd` 导出源码并构建旧搜索程序；记录旧／新镜像内容标识。
2. 在搜索 Flyway V3 和旧索引上建立公开内容基线，并让旧消费者真实处理一条无业务版本的 Kafka 消息。
3. 停止应用写入器和消费者，导出内容库及搜索消费状态的 SQL、Kafka 消费位点和 Elasticsearch 原生快照。备份文件保存 SHA-256 校验和。
4. 启动新搜索程序，升级至 V5，在消费者停止的状态下通过管理员网关创建持久重建任务。实际调用 `run_search_reindex.py`，第一批写入 20 条并以退出码 3 保存回执后，强制结束本次独立项目的搜索进程，重启并核对检查点及旧请求重试不重复推进；再次调用相同命令行入口续跑，完成剩余复制和 21 条完整字段验证，退出码必须为 0。额外核对 ID、标题、摘要和业务版本，确认没有从旧时间水位伪造业务版本。
5. 开启新消费者，确认无版本消息进入 DLT 且不改写索引；验证删除、重复删除、旧更新重放和重建均不能复活内容。保留此时的新版本备份。
6. 恢复旧备份：从导出的快照压缩包建立独立、只读的快照仓库，实际恢复被删除的旧索引，同时恢复 SQL 和 Kafka 位点，逐条核对恢复结果。
7. 用旧二进制恢复搜索读取，保持消费者关闭，确认消费位点没有移动。
8. 再恢复切换后的备份，用新二进制确认删除状态和剩余内容都保留下来。

成功记录为输出目录的 `report.json`，包含完成检查、镜像标识、代码提交和演练脚本摘要。`baseline/`、`current/` 保留 SQL、快照、逐条文档和位点；`resource-samples.jsonl` 记录各检查阶段的资源占用。记录的阶段采样值不等于连续监控的绝对峰值。演练结束检查 Docker OOM 事件及最终容器状态。

失败会记录错误并停止本次独立项目；备份和数据卷保留供排查。成功后同样停止容器、保留备份和数据卷。删除这些数据前核对 `report.json` 中的随机项目名，不要对其他 Compose 项目执行清理。

## 回滚边界

这是**恢复备份时点后的只读回滚**。Kafka 中切换后产生的消息仍然存在；只恢复数据库和旧索引后就启动旧消费者，会让这些消息再次修改旧状态。演练因此强制旧消费者保持关闭，并另外保存切换后备份用于向前恢复。

要恢复旧版本写服务，仍需制定切换后业务写入与消息的隔离／补偿方案，并验证所有生产者的协议兼容性。本演练不声称零数据损失的在线降级，也不能代替真实生产备份、容量和权限演练。通用 `backup.sh` 仍不包含搜索快照和 Kafka 位点，不能独自作为本次搜索协议升级的回滚凭据。
