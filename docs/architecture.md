# DevCircle 架构说明

## 服务拆分

DevCircle 使用按业务域拆分、兼容聚合的微服务结构。Gateway 负责统一入口、路由、TraceId、真实 JWT 校验和幂等键约束；业务服务分别承载认证、用户、内容、评论、互动、搜索、推荐、通知、文件和管理端能力。

## 数据流

用户发布帖子后，`dc-content` 写入帖子数据，并在同一个本地事务内写入 `outbox_event`。后台发布任务将事件发送到 Kafka，`dc-search` 消费后同步 Elasticsearch，`dc-notification` 消费审核和互动事件后写入站内信并通过 WebSocket 推送。

## Outbox 约定

写服务维护统一形状的 `outbox_event`：

- `event_id`: 全局事件 ID，用于消费端幂等。
- `topic`: Kafka topic，例如 `post.created`。
- `aggregate_type` 与 `aggregate_id`: 聚合根定位。
- `payload`: JSON 事件载荷。
- `status`: `NEW`、`PROCESSING`、`PUBLISHED`、`FAILED`、`DEAD`；失败事件按退避时间重试，超过最大次数进入死信状态。

消费端维护 `consumer_dedup(event_id, topic, consumed_at)`。处理事件前先插入幂等记录，插入失败表示重复消息，应直接确认消费。

## 关键索引

- `post(status, created_at)`: 支撑审核列表和最新内容流。
- `post(author_id, created_at)`: 支撑个人主页内容列表。
- `like_record(user_id, target_type, target_id)`: 保证点赞幂等。
- `favorite_record(user_id, post_id)`: 保证收藏幂等。
- `consumer_dedup(event_id)`: 保证 Kafka 至少一次投递下的消费幂等。
- `outbox_event(status, created_at)`: 支撑事件发布任务扫描。

## 搜索同步

搜索服务只依赖事件，不直接跨库读取内容库。`post.created` 和 `post.updated` 更新索引，`post.deleted` 删除索引。Elasticsearch 短暂不可用时，消费者保留失败状态并由补偿任务重放。

## 推荐策略

第一版推荐不训练模型，采用可解释规则：

- 关注标签召回。
- 热度分召回。
- 关注作者召回。
- 新内容探索召回。
- 按互动质量、时效性、标签匹配度和审核状态排序。
