# Feed Engine

DevCircle v0.3 的推荐流采用可解释工程规则，目标是先打通内容平台最关键的闭环：发布、事件、搜索、互动、推荐和曝光去重。

## 数据来源

- 内容：`dc-content` 写入 MySQL 的 `post/post_content/tag/post_tag`。
- 事件：发帖时同事务写入 `outbox_event`，再由 Outbox publisher 推送 Kafka。
- 互动：`dc-interaction` 使用 MySQL 唯一索引保证点赞/收藏幂等，并把热度写入 Redis。
- 曝光：`dc-recommend` 记录 `user:{id}:exposed-posts`，避免重复刷出。

## Redis Key

- `feed:hot-posts`: ZSET，帖子热度分。
- `user:{id}:interest-tags`: SET，用户近期兴趣标签。
- `user:{id}:exposed-posts`: SET，用户近期曝光过的帖子。
- `post:{id}:stats`: HASH，帖子互动计数快照。

## 排序公式

第一版分数为：

```text
score = 标签匹配分 + 热度分 + 新鲜度分 + 关注作者分 - 已曝光惩罚
```

这个公式的重点是可解释和易调试，不追求模型效果。

## 降级策略

- Redis 不可用：推荐服务使用默认兴趣标签和基础热度。
- Elasticsearch 不可用：搜索服务返回本地 fallback 响应，Kafka 事件可重放。
- MySQL 不可用：内容服务保留演示数据路径，方便只看前端和接口结构。

## 后续演进

- 增加真实用户行为埋点。
- 引入召回池：关注作者、兴趣标签、热门内容、新内容探索。
- 增加离线特征任务和在线排序实验。
- 增加 k6 压测和 Grafana Feed 面板。

