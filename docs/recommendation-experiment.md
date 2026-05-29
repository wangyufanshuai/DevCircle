# v1.2 推荐实验平台

当前版本将推荐从“固定规则排序”升级为“可解释、多路召回、可实验”的内容平台推荐链路。目标不是引入复杂离线训练系统，而是在 Java 微服务内提供接近大厂业务形态的实验分桶、召回来源、特征日志和降级解释。

## 核心模型

- `recommend_experiment`: 推荐实验主表，记录实验 id、场景、状态、流量比例和权重配置。
- `recommend_experiment_bucket`: 实验分桶表，记录 bucket 名称、流量比例和参数覆盖。
- `recommend_event_log`: 推荐事件日志，记录曝光、点击、点赞、收藏、答题、详情页访问等行为。
- `recommendation_impression`: 曝光与特征快照，用于 7 天曝光去重和后续分析。

## 多路召回

推荐候选按场景组合以下召回源：

- `theme`: 当前主题频道召回，例如推理馆优先召回 `DEDUCTION`。
- `tag`: 用户兴趣标签和内容标签匹配。
- `hot`: Redis 热度和互动计数召回。
- `follow`: 关注作者召回。
- `series`: 同系列内容召回。
- `cold_start`: 新用户或候选不足时的冷启动内容。
- `seed_fallback`: 数据库、搜索或 Redis 不可用时的显式降级兜底。

响应中的 `recallSources` 会暴露本次命中的召回来源，便于演示和排查。

## 实验分桶

推荐服务根据 `userId + scene + experimentId` 做稳定 hash 分桶。同一用户在同一场景下会稳定进入同一个 bucket，避免每次刷新策略变化过大。

返回字段：

- `experimentId`: 当前命中的实验。
- `bucket`: 当前实验桶，例如 `control`、`weighted`。
- `algorithm`: 使用的算法名称，例如 `linear-v2`、`rule-fallback`。
- `features`: 本条内容的打分特征。
- `reason`: 面向用户和开源阅读者的解释文案。
- `degraded`: 候选不足或依赖失败时为 `true`。
- `source`: `DB`、`ES`、`SEED_FALLBACK` 等候选来源。

## 事件日志

前端在这些行为发生时调用 `POST /api/recommend/events`：

- `detail_view`: 打开详情。
- `click`: 点击推荐卡片。
- `like`: 点赞。
- `favorite`: 收藏。
- `answer`: 提交推理答案。

事件会写入 `recommend_event_log`，同时写入 Outbox topic `recommend.event.logged`。当前版本不做实时训练，但这些日志已经具备后续 CTR、互动率、解谜完成率分析所需的基础字段。

## 降级策略

当数据库、搜索或 Redis 依赖不可用时：

1. 推荐服务优先返回可用候选。
2. 候选为空时使用主题 seed 内容库。
3. 响应设置 `degraded=true`，并在 `source` 中标明 `SEED_FALLBACK`。
4. 前端展示“演示降级模式”，不静默掩盖后端错误。

## 管理接口

- `GET /api/admin/recommend/experiments`: 查看实验列表。
- `POST /api/admin/recommend/experiments`: 创建实验配置。
- `POST /api/admin/recommend/experiments/{id}/activate`: 激活实验。
- `POST /api/admin/recommend/experiments/{id}/pause`: 暂停实验。

这些接口仍是生产预备实现，适合开源展示和继续迭代；后续可扩展为带审批流、灰度范围、离线评估和自动报表的完整实验平台。
