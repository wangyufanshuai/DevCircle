# 性能验收记录

## 验收口径

- 总并发默认 50：40 个读场景 VU、10 个写场景 VU。
- Feed、搜索和详情每个读接口的 P95 均小于 300 ms；同时保留读场景聚合门槛。
- 点赞、取消点赞和答题每个写接口的 P95 均小于 600 ms；同时保留写场景聚合门槛。
- HTTP、读业务断言和写业务断言错误率均小于 1%。
- `POST_ID` 必须是已发布且可解谜的真实内容；详情返回 404 会直接计入失败。
- `TOKEN` 可传单个访问令牌，`TOKENS` 可传逗号分隔的多个真实用户令牌。脚本会自动补充 `Bearer` 前缀。
- 本地 50 VU profile 为每个 VU 使用独立的 TEST-NET-2 `X-Forwarded-For`，模拟 50 个真实客户端经过受信反向代理进入 Gateway；不应把单个源 IP 的生产限流容量误当成应用吞吐上限。
- `setup()` 会在发压前验证 `POST_ID` 的公开状态与可解谜属性；不合格时停止测试。
- 幂等键包含每次进程自动生成的运行标识；需要跨主机复现实验时显式传入全局唯一的 `RUN_ID`。

## 执行命令

```bash
BASE_URL=https://staging.example.com \
POST_ID=1001 \
TOKENS="token-user-1,token-user-2" \
RUN_ID="staging-20260802-01" \
READ_VUS=40 WRITE_VUS=10 DURATION=3m \
k6 run tests/k6/mysticverse-smoke.js
```

目标 Linux 主机应使用证据化执行器，而不是直接调用 k6：

```bash
ENV_FILE=.env.production \
BASE_URL=http://127.0.0.1:8080 \
POST_ID=1001 \
TOKENS="token-user-1,token-user-2" \
ADMIN_TOKEN="token-admin" \
bash scripts/performance/run-linux-acceptance.sh
```

`run-linux-acceptance.sh` 会强制要求 Linux、至少 4 CPU、15 GiB 可见内存和 180 GiB
文件系统容量，并要求 `.env.production` 的 `IMAGE_TAG` 与当前 Git SHA 完全一致、跟踪文件无未提交
修改。执行器默认运行 40 读 VU、10 写 VU、3 分钟，保留以下不可变验收证据：

- 主机、块设备、Docker 版本和镜像 SHA；
- 压测前后的 Compose 状态与容器资源快照；
- k6 JSON 摘要、完整输出和退出码；
- Gateway 压测前后 readiness；
- Admin Outbox fleet 完整性快照；
- Prometheus 可用性、读写 P95、Outbox 完整性和积压查询结果；
- 解析后的 `prometheus-summary.json`，包含实际值与本次生效阈值；
- 全部 JSON/TXT 证据的 `SHA256SUMS`。

执行器要求宿主机安装 `jq`。Prometheus 查询必须返回且只返回一个有限数值；空值、NaN、Inf、多序列、availability 低于 0.99、读/写 P95 超过 0.3/0.6 秒都会失败。Admin 健康响应还必须证明 `outboxScope=FLEET`、`outboxComplete=true`、DEAD/blocked 为零、无不可用来源且每个来源均为 `UP`。执行失败时证据仍会保留，但退出码非零，不得把存在证据目录解释为验收通过。

场景覆盖：

- 推荐 Feed、搜索和正式详情路由。
- 点赞与取消点赞，写入请求均使用唯一幂等键。
- 推理解谜提交；建议使用不会答对的 `ANSWER`，避免性能测试产生积分。

当前离线脚本契约测试为 3/3 通过，Node 模块语法检查通过；本机 50 并发 k6 稳态基线见下文。该结果是本地 Docker 一分钟基线，不替代目标 Linux 主机三分钟正式验收。

## 本地 Docker 基准

### SQL 读路径验证（2026-08-08）

- 通知、评论、收藏、频道和作者投稿新增与实际过滤/排序一致的组合索引。
- 一次性 MySQL 9.3 实例成功执行相关四个模块共 39 份迁移。
- 每条路径使用 10,000 行代表性数据执行 `ANALYZE TABLE` 和 `EXPLAIN`；目标索引均被实际选中，计划不含 `filesort`。
- 固定 MySQL 8.4 的四个自动化集成测试已在同一批次通过。Content、Comment、Interaction 和 Notification 完整执行 Flyway 迁移，并以 10,000/20,000 行代表数据确认目标索引被优化器实际选择，执行计划不含 `filesort`；0 failures、0 errors、0 skipped。
- 同批全仓 `clean verify -Pcoverage` 实际启动五类 Testcontainers 并通过 465/465 测试；该结果证明索引与基础设施切片，不等同于 50 并发端到端延迟。
- 该预检只证明查询计划符合预期，不代表接口 P95、连接池等待、网络开销或多服务并发目标已通过。

### 50 VU 稳态基线（2026-08-08）

- 环境为 Windows 11 + Docker Desktop 29.6.2，Docker 分配 4 CPU、约 5.79 GiB 内存；并非目标 Linux 生产主机。
- 场景使用 40 个读 VU、10 个写 VU，持续 1 分钟；读用户每轮执行 Feed、搜索、详情后等待 1 秒，写用户每轮执行点赞、取消点赞、答题后等待 3 秒，确保每个模拟客户端不违反 Gateway 的生产限流。
- 计时前完成服务健康检查、10 个真实账号注册、搜索重建、详情预检和 5 次 Feed 预热。未预热冷启动对照中 Feed P95 为 399.79 ms、最大 3.18 s，主要来自推荐服务首次初始化 Hikari；冷启动值不计入稳态通过结论，但作为启动性能风险保留。
- 稳态共处理 7,018 个 HTTP 请求，平均 111.81 RPS；k6 六个操作标签、读写聚合和错误率阈值全部通过，7,017/7,017 业务检查成功。
- 本轮先修复内容列表在 RowMapper 内嵌套查询媒体导致的连接池饥饿，再将推荐热度读取、曝光缓存和曝光持久化改为批处理。修复前真实 Feed 会把 Content 的 10 个连接全部占满，等待峰值 58；修复后未再出现连接池等待超时。
- 本次没有保留完整 Prometheus 时序数据，因此结论限定为“本地 k6 基线通过”；目标 Linux 三分钟验收、Kafka Lag 和资源曲线仍待执行。

| 指标 | 结果 |
| --- | --- |
| 执行日期与基线 | 2026-08-08；`c1e0099`、`4929214`、`b937c34`、`63f5f26` |
| 主机与 Docker 配额 | i9-13900HX / 15.8 GB；Docker 4 CPU / 5.79 GiB |
| 读/写 VU | 40 / 10 |
| 测试时长 | 1 分钟（稳态窗口） |
| 请求数 / 平均 RPS | 7,018 / 111.81 |
| 总体 / 读 / 写 P95 | 150.18 / 138.53 / 241.48 ms |
| Feed / 搜索 / 详情 P95 | 290.84 / 106.30 / 65.48 ms |
| 点赞 / 取消点赞 / 答题 P95 | 402.08 / 231.96 / 190.16 ms |
| HTTP 错误率 | 0.00%（0 / 7,018） |
| 业务断言错误率 | 0.00%（0 / 7,017） |
| 冷启动对照 | Feed P95 399.79 ms；最大 3.18 s |
| 主要瓶颈 | 媒体嵌套查询导致连接池饥饿；推荐逐条 Redis/SQL 往返；均已修复 |

## 目标 Linux 主机基准

| 指标 | 结果 |
| --- | --- |
| 执行日期与提交 SHA | 待实测 |
| CPU / RAM / SSD | 4 vCPU / 16 GB / 200 GB SSD |
| 读/写 VU | 40 / 10 |
| 测试时长 | 待实测 |
| 平均 RPS | 待实测 |
| 读 P95 | 待实测 |
| 写 P95 | 待实测 |
| HTTP 错误率 | 待实测 |
| 业务断言错误率 | 待实测 |
| Kafka Lag / Outbox backlog | 待实测 |
| 恢复后追平时间 | 待实测 |

## 结果纪律

- 只有 k6 阈值全部通过且 Prometheus 数据完整时，才可记录为通过。
- 404、401、429、5xx 和业务断言失败均不得从统计中排除。
- 不得只读取聚合 P95；六个 `operation` 标签的独立延迟与错误率门槛必须全部通过。
- 本地共享 Docker 配额不足或守护进程异常时记录为环境失败，不得替代目标主机结果。
- 每次调优后保留前后两份结果，记录数据库索引、连接池、JVM、Kafka 或缓存参数变化。
