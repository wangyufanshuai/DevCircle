# 轻量 ML 推荐

当前推荐系统不是训练平台，而是 Java 本地可解释模型，用于展示真实社交平台推荐链路中的特征工程、打分、实验分桶和降级策略。

## 特征

- `tagMatch`: 用户兴趣标签和帖子标签匹配度。
- `hot`: Redis 热度和帖子互动量。
- `freshness`: 内容新鲜度。
- `followedAuthor`: 是否关注作者。
- `mutualFollowees`: 共同关注数量。
- `mediaSceneMatch`: 是否匹配 home/image/video 场景。
- `exposed`: 是否已曝光。

## 打分

`LightweightMlRanker` 使用固定线性权重输出分数和特征明细。推荐响应包含：

- `algorithm`
- `features`
- `reason`

这样开源用户可以直接看到排序依据。

## 降级

当模型不可用或特征缺失时，`dc-recommend` 回退到规则公式：标签匹配、热度、新鲜度、关注作者和曝光惩罚。
