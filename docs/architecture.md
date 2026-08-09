# DevCircle 架构说明

## 服务拆分

DevCircle 使用按业务域拆分、兼容聚合的微服务结构。Gateway 负责统一入口、路由、TraceId、真实 JWT 校验和幂等键约束；业务服务分别承载认证、用户、内容、评论、互动、搜索、推荐、通知、文件和管理端能力。部署边界与 Maven 领域模块是两个维度：`dc-search` 领域代码第一阶段由 `dc-discovery` 进程承载，`dc-auth` 仍保留认证逻辑 producer、schema、迁移和 Maven 模块但由 `dc-identity` 进程承载；两个旧的独立可执行进程都只用于对应的 legacy rollback Compose。

## 数据流

用户发布帖子后，`dc-content` 写入帖子数据，并在同一个本地事务内写入 `outbox_event`。后台发布任务将事件发送到 Kafka，`dc-discovery` 进程内承载的 `dc-search` runtime 消费后同步 Elasticsearch，`dc-notification` 消费审核和互动事件后写入站内信并通过 WebSocket 推送。推荐由独立的 `dc-recommend` 进程消费特征事件；`/api/recommend/**` 由 Gateway 直接路由到它，不经过 Discovery 内嵌。

## Outbox 约定

写服务维护统一形状的 `outbox_event`：

- `event_id`: 全局事件 ID，用于消费端幂等。
- `topic`: Kafka topic，例如 `post.created`。
- `aggregate_type` 与 `aggregate_id`: 聚合根定位。
- `payload`: JSON 事件载荷。
- `status`: `NEW`、`PROCESSING`、`PUBLISHED`、`FAILED`、`DEAD`；失败事件按退避时间重试，超过最大次数进入死信状态。

消费端维护 `consumer_dedup(event_id, topic, consumed_at)`。处理事件前先插入幂等记录，插入失败表示重复消息，应直接确认消费。

同一 Outbox 表内，相同 `topic + aggregate_id` 的事件严格按自增 ID 发布。前序事件处于 `NEW`、`PROCESSING`、`FAILED` 或 `DEAD` 时，后续事件不会越过它；死信必须由运营重放或明确处置后才能解除该聚合的阻塞。评论创建、删除和隐藏统一发布到 `comment.changed`，以评论 ID 作为 Kafka Key；旧版 `comment.created` 仅作为一个版本的消费兼容入口。

Outbox 批量大小、最大尝试次数和发送等待分别受 `DEVCIRCLE_OUTBOX_BATCH_SIZE`（默认 50）、`DEVCIRCLE_OUTBOX_MAX_ATTEMPTS`（默认 8）和 `DEVCIRCLE_OUTBOX_SEND_TIMEOUT_MS`（默认 15 秒）控制。Kafka Future 超时会释放租约、记录失败并按指数退避重试；耗尽尝试后进入 `DEAD`，同一 `(topic, aggregateId)` 的后续事件保持等待，不能越过失败前序。

Admin 不再以自身连接的内容库代表全局 Outbox。Auth、User、Content、Comment、Interaction 通过 Docker 内网精确暴露 `GET /internal/ops/outbox/status`，使用共享内部运维密钥并做常量时间比较；Gateway 不路由 `/internal/**`。Admin 以虚拟线程并行、1 秒连接与 2 秒读取超时汇总五域，返回 `outboxScope=FLEET`。任何来源缺失都会显式产生 `outboxComplete=false` 和 `OUTBOX_SOURCE_UNAVAILABLE`；只有 Admin 自身数据库不可用时，数据库故障原因才优先于 fleet 原因。

生产 `aggregate` Gateway 将内容、评论、媒体和审核请求统一路由到 `dc-content-aggregate`；旧领域服务只作为聚合门面内部依赖，不允许被外部 Gateway 路由直接绕过。

## 关键索引

- `post(status, created_at)`: 支撑审核列表和最新内容流。
- `post(status, theme, content_kind, difficulty, created_at, id)`: 支撑完整频道筛选和不透明游标。
- `post(author_id, created_at)`: 支撑个人主页内容列表。
- `post(author_id, updated_at, id)`: 支撑个人中心投稿时间线。
- `comment(post_id, parent_id, id)` 与 `comment(post_id, root_id, id)`: 支撑根评论游标和二级回复装配。
- `like_record(user_id, target_type, target_id)`: 保证点赞幂等。
- `favorite_record(user_id, post_id)`: 保证收藏幂等。
- `favorite_record(user_id, created_at, post_id)`: 支撑收藏内容的最新优先时间线。
- `notification(receiver_id, id)` 与 `notification(receiver_id, type, id)`: 支撑收件箱及类型筛选的 ID 游标。
- `consumer_dedup(event_id)`: 保证 Kafka 至少一次投递下的消费幂等。
- `outbox_event(status, created_at)`: 支撑事件发布任务扫描。

## 搜索同步

搜索服务只依赖事件，不直接跨库读取内容库。`dc-search` 的代码模块在第一阶段由 `dc-discovery` 进程承载；`post.created` 和 `post.updated` 更新索引，`post.deleted` 删除索引。Elasticsearch 短暂不可用时，消费者保留失败状态并由补偿任务重放。独立 `dc-search` 可执行镜像仅在 `legacy-search rollback Compose` 中启用。

搜索投影以 `(occurredAtMillis, eventId)` 作为统一的单调版本。协调器和 Elasticsearch 脚本使用相同的字典序规则：较新的事件毫秒时间优先；同一毫秒内 eventId 较大的事件优先。这样同一时刻产生的审核、更新或删除事件不会被协调器误判为重复，也不会由旧事件覆盖较新投影。

评论服务和互动服务分别在本地事务中维护文章级权威计数与单调 `counterVersion`，并把计数快照写入 Outbox 事件。内容服务按生产者版本更新 `comment_count` 和 `like_count`，乱序旧版本只能写消费去重记录，不能回退计数；一旦接收带版本快照的事件，无版本旧事件也不能覆盖它。升级期间遗留事件仍可使用 `(occurredAtMillis, eventId)` 增量兼容路径。

互动服务在接受写入前通过内容详情确认公开状态、当前点赞基线和作者身份，并把作者作为 `receiverId` 写入同一条 `interaction.changed` 事件。通知消费者处理新版事件时不再同步调用内容服务；缺少 `receiverId` 的旧事件保留一个版本的回源兼容路径，避免升级窗口内丢失通知。

所有同步 HTTP 客户端统一使用有界连接：连接超时 1 秒、读取超时 2 秒。评论、互动、推荐、搜索、旧通知事件回源及 GitHub OAuth 在依赖半开或响应挂起时都会明确失败，由调用方的错误响应或 Kafka 重试/DLT 接管，不允许无限占用请求与消费线程。聚合门面的 WebClient 调用另有 2 秒 Reactor 超时。

## 推荐策略

推荐运行在独立的 `dc-recommend` 进程中。Gateway 将 `/api/recommend/**` 直接路由到 `dc-recommend`；Discovery 不嵌入推荐运行时，二者通过 Kafka 事件和共享契约协作。

第一版推荐不训练模型，采用可解释规则：

- 关注标签召回。
- 热度分召回。
- 关注作者召回。
- 新内容探索召回。
- 按互动质量、时效性、标签匹配度和审核状态排序。
