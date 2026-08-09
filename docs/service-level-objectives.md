# MysticVerse 服务等级目标

## 用户侧 SLO

首版生产 SLO 以 Gateway 为用户侧边界，使用 30 天滚动窗口：

| SLI | 目标 | 计算方式 |
| --- | --- | --- |
| 可用性 | ≥ 99.5% | Gateway 非 5xx 请求数 / Gateway 总请求数 |
| 读取延迟 | P95 < 300ms | `GET/HEAD/OPTIONS` 的 Gateway HTTP 直方图 |
| 写入延迟 | P95 < 600ms | `POST/PUT/PATCH/DELETE` 的 Gateway HTTP 直方图 |

4xx 表示请求或权限问题，不消耗服务端可用性预算；依赖故障经 Gateway 返回的 5xx/503 会消耗预算。30 天 99.5% 可用性对应 0.5% 错误预算，即约 3 小时 36 分钟的等价完全不可用时间。

这是一项运行目标，不是当前代码提交自动获得的认证。只有目标 Linux 主机保留完整 30 天 Prometheus 数据后，才能声明月度 SLO 达成。

## 错误预算燃烧

Prometheus 在 `deploy/prometheus/slo.yml` 中记录 5m、30m、1h、6h 和 3d 燃烧率：

- 快速燃烧：1h 与 5m 同时超过 14.4 倍，持续 2 分钟，严重级别为 `critical`。
- 持续燃烧：6h 与 30m 同时超过 6 倍，持续 15 分钟，级别为 `warning`。
- 月预算风险：3d 与 6h 同时超过可持续的 1 倍，持续 1 小时。

快速燃烧应立即停止发布并进入故障响应；持续燃烧应在当前值班周期内处理；预算风险应触发容量、依赖和近期变更复盘。

## 数据保留和证据

生产 Prometheus 保留 35 天且限制 TSDB 为 1GB，满足一个完整 30 天窗口并给发布前后对照留下余量。正式验收必须保留：

- 测试提交 SHA、执行时间和主机规格；
- Gateway 读取/写入 P95；
- 5xx 比例及燃烧率；
- JVM、Hikari、Outbox 和容器重启曲线；
- Kafka/Elasticsearch 故障开始、恢复和追平时间；
- 触发的 Alertmanager 通知与处置记录。

## Outbox 可靠性指标

`dc-admin` 每 30 秒聚合五个事务 Outbox 来源并导出：

- `devcircle_outbox_fleet_backlog`
- `devcircle_outbox_fleet_retryable`
- `devcircle_outbox_fleet_dead`
- `devcircle_outbox_fleet_blocked`
- `devcircle_outbox_fleet_complete`
- `devcircle_outbox_fleet_unavailable_sources`

`complete=0` 表示至少一个来源缺失，此时 backlog 等数值只是已知部分，禁止解释为全局总量。
