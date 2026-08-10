# MysticVerse 可观测告警运行手册

所有处置先记录告警开始时间、提交 SHA、操作者和关联 `traceId`。没有完成根因定位时，不得通过静默告警、清空 Outbox 或更换幂等键恢复表面绿色。

## service-down

1. 检查 `docker compose ps`、容器退出码、健康探针和最近一次发布。
2. 查看目标服务日志中的 OOM、数据库连接、Kafka/ES/Redis 超时和 Flyway 错误。
3. 若为单服务发布问题，回滚到上一 SHA；若为共享依赖故障，先恢复依赖。
4. 确认 `up`、readiness 和真实用户请求恢复后再关闭告警。

## availability-fast-burn

1. 立即暂停发布和数据迁移。
2. 按 status、URI、服务和最近 SHA 分解 Gateway 5xx。
3. 使用 `traceId` 关联 Gateway、聚合门面和领域服务日志。
4. 回滚高相关变更或隔离失败依赖；禁止用 Mock/Seed 返回伪成功。
5. 持续观察 1h 与 5m 燃烧率均回落后记录影响时长和预算消耗。

## availability-slow-burn

检查周期性任务、连接池等待、依赖尾延迟、磁盘和 Outbox 重试。若错误集中在单一端点，建立端点级修复；若覆盖多个端点，优先排查共享数据库、Gateway 或资源压力。

## availability-budget-risk

统计当前 30 天可用性、剩余预算和近期故障。剩余预算不足时冻结非必要发布，直到修复和恢复演练完成。

## latency-slo-miss

1. 分别核对读取与写入 P95，不使用聚合平均数替代。
2. 检查 CPU、GC、Hikari active/pending、慢 SQL、Gateway 下游耗时和 Kafka 同步等待。
3. 对照冷启动与稳态，确认是否为连接池或缓存首次初始化。
4. 优化后使用相同 k6 场景和提交记录复测。

## jvm-memory-pressure

检查堆使用、GC 暂停、对象分配和容器 OOM 记录。只有在确认工作集合理后才提高内存；内存泄漏或无界缓存必须先修复。

## mysql-pool-exhausted

检查 Hikari pending、慢查询、事务时长和 MySQL 当前连接。优先修复 N+1、事务内远程调用或连接泄漏；不要简单扩大每个服务的连接池，使 MySQL 超过 120 连接上限。

## outbox-fleet-incomplete

1. 查询 `/api/admin/ops/health` 的 `unavailableOutboxSources`。
2. 恢复缺失服务或内部网络/密钥配置。
3. 在 `outboxComplete=true` 前，不得把已知 DEAD/backlog 的零值解释为全局健康。

## outbox-dead-or-blocked

1. 确认 Kafka 和目标消费者依赖已经恢复。
2. 查看 DEAD 事件的 topic、aggregateId、attempts 和最后错误。
3. 评估同一流被阻塞的后续事件。
4. 使用管理员身份、操作理由和原幂等键执行精确重放。
5. 确认 DEAD、blocked 均归零，下游投影只生效一次且审计日志存在。

## rate-limit-dependency-unavailable

1. Check Redis health, authentication, latency, and connection errors from the Gateway logs.
2. Confirm the Redis service is reachable from every Gateway replica and that the configured password is current.
3. Keep the fail-closed behavior while restoring Redis; do not re-enable an in-process counter as a production workaround.
4. After recovery, verify the `devcircle_gateway_rate_limit_total` dependency-unavailable counter stops increasing and exercise one authenticated request from two Gateway replicas.

## outbox-backlog-high

区分持续新增和恢复追平。检查发布速率、Kafka ACK、消费者 Lag 和失败重试；依赖恢复后记录 backlog 回零所需时间。积压继续增长时应停止非必要写流量并升级响应级别。
