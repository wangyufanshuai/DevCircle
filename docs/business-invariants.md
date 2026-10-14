# 状态与权限反例验收

阶段三以数据库和实际 HTTP 结果验收，不以代码中存在锁或鉴权注解作为通过依据。

| 不变量 | 反例 | 自动验证 |
|---|---|---|
| 每用户每题最多一份解谜记录、一次积分 | 8 个并发请求复用同一键；8 个请求使用不同键 | `DeductionConcurrencyIntegrationTest`：真实 MySQL、真实事务代理；核心 API `verify` 再验 |
| 揭示错误答案不能产生积分 | 错误答题与揭示同时提交 | 同一 MySQL 并发测试 |
| 幂等键绑定用户及载荷 | 他人复用键、原用户换答案 | 核心 API `verify`，必须返回 409 |
| 草稿修改不会丢失新版本 | 旧 version 覆盖已经更新的草稿 | 核心 API `verify`，必须返回 409 |
| 评论/互动计数不被旧消息回退 | 新版本先到、旧版本后到、重复事件 | `ContentCommentProjectionIntegrationTest`、`ContentInteractionProjectionIntegrationTest` |
| 同一搜索事件只有一个租约拥有者 | 两线程同时 claim | `SearchLeaseIntegrationTest`：真实 MySQL 和迁移 |
| 已过期的 worker 不能污染新租约 | 租约回收后旧 worker complete/fail | 同一 MySQL 租约测试，验证拒绝且新 token 不变 |
| Outbox 保序及失效租约隔离 | 前序失败、过期 worker 写回 | `ReliableOutboxWorkerTest`；该层使用 H2 与发送器替身，不等同真实 Kafka 故障演练 |
| 用户只能读写自己的草稿 | 两个其他账号读写、伪造身份头 | 核心 API `verify`，必须拒绝 |
| 私有媒体不能被他人完成或提前读取 | 真实 MinIO 上传后，他人确认完成、匿名取未发布媒体地址 | 核心 API `verify`；同时校验上传地址同源 |
| 普通用户不能访问管理接口或提前获得答案 | 读者访问审核；匿名查看答案 | 核心 API `verify`；完成解谜后才可读取解析 |
| 重启后业务状态不丢失 | 停止全部核心容器，再用原数据卷启动 | `verify` 第二轮先检查上一轮记录和积分 |

## 本轮修复的并发缺陷

真实 MySQL 反例复现了两类 `EmptyResultDataAccessException`：事务先检查内容可用性，建立 REPEATABLE READ 快照；并发唯一键等待结束后，普通 SELECT 仍看不见胜者刚提交的幂等记录或解谜记录。数据库没有重复计分，但重复请求错误返回 500。

解谜写事务显式使用 READ_COMMITTED，让重复请求读取刚提交的胜者结果。唯一键、积分更新和幂等记录仍处于同一事务；不移除幂等校验，也不靠前端禁用按钮掩盖竞争。

## 搜索业务版本与乱序反例

搜索现使用内容业务版本；`SearchCausalOrderIntegrationTest` 同时连接真实 MySQL 与 Elasticsearch，覆盖同毫秒 UUID 逆序、时钟回拨、乱序删除/更新、重复投递、删除后旧消息重放、ES 已提交而数据库水位落后、旧 worker 延迟写入和旧重建快照。生产者并发版本分配与 Outbox 回滚由 `DraftPersistenceIntegrationTest` 验证；审核幂等重试的版本及快照由 `AdminServiceStateTest` 验证。

这不允许混跑旧搜索写入器。无版本旧事件需要迁移，索引升级和回滚约束见 [搜索版本迁移](search-version-migration.md)。

## 未覆盖的生产保证

本轮核心切片不覆盖所有媒体/通知资源权限，也不包含网络分区、进程 kill、真实 Kafka 重平衡或长期压测。完整测试入口和 CI 提供更广覆盖，但不能替代生产演练。不能据此声称整个系统已经达到企业生产验收。
