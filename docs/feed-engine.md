# Feed Engine

DevCircle v1.2 的 Feed 引擎由规则召回、本地轻量模型排序、推荐实验分桶和曝光去重组成。目标是用低部署成本展示真实社交内容平台的推荐闭环。

## 数据来源

- 内容：`dc-content` 写入 MySQL 的 `post/post_content/tag/post_tag/post_media`。
- 媒体：`dc-file` 写入 `media_asset`，帖子通过 `post_media` 关联。
- 事件：发布时同事务写入 `outbox_event`，再由 Outbox publisher 推送到 Kafka。
- 搜索：`dc-search` 监听发布、审核通过、驳回和删除事件，使用持久去重、文章级租约和事件水位维护 Elasticsearch 文档。
- 互动：`dc-interaction` 用 MySQL 唯一索引保证点赞/收藏幂等，写入 `interaction.changed` Outbox；`dc-recommend` 消费事件后原子更新 Redis 信号。
- 图谱：`dc-user` 用 MySQL + Redis 提供关注作者和共同关注特征。
- 曝光：`dc-recommend` 记录 `user:{id}:exposed-posts`，避免短时间重复刷出同一内容。

## Redis Key

- `feed:hot-posts`: ZSET，帖子热度分。
- `user:{id}:interest-tags`: SET，用户近期兴趣标签。
- `user:{id}:exposed-posts`: SET，用户近期曝光过的帖子。
- `user:{id}:followees`: SET，用户关注集合。
- `user:{id}:graph-features`: HASH，推荐侧图谱特征。
- `recommend:model:weights`: 后续可配置模型权重。

## 排序

当前版本使用 `LightweightMlRanker` 做本地线性打分：

```text
score = 标签匹配 + 热度 + 新鲜度 + 关注作者 + 共同关注 + 场景匹配 - 曝光惩罚
```

响应会返回 `algorithm`、`features` 和 `reason`，便于调试和开源展示。

## 降级

- Redis 不可用：生产推荐请求明确失败；只有显式开发读取降级时才返回标记为 `SEED_FALLBACK` 的演示结果。
- 模型异常：回退到规则公式，并显式返回降级来源。
- Elasticsearch 不可用：生产搜索请求明确失败，Kafka 事件保持可重试；开发降级必须显式开启。
- Kafka 不可用：Outbox 事件保持 `NEW`，不会误标记为 `PUBLISHED`。

## 场景

- `home`: 文本、图片、视频混排。
- `image`: 图片瀑布流。
- `video`: 纵向视频 Feed。
