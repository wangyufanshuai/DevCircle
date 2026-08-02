# 可解释规则推荐

当前推荐系统不训练模型，使用 Java 本地固定权重规则，保证每条结果的分数、特征和推荐理由均可解释、可审计。

## 特征

- `themeInterest35`: 主题与标签兴趣，最高 35 分。
- `followedAuthor20`: 关注作者，最高 20 分。
- `qualityHotness20`: 内容质量与互动热度，最高 20 分。
- `freshness15`: 内容新鲜度，最高 15 分。
- `exploration10`: 确定性探索，最高 10 分。
- `exposurePenalty`: 已曝光内容降权 100 分。

## 打分

`RecommendationScorer` 输出总分、特征分解和一条面向用户的推荐理由。推荐响应包含：

- `algorithm`
- `features`
- `reason`

这样开源用户可以直接看到排序依据。

## 降级

生产环境不使用 Seed 或 Mock 伪造推荐成功。内容、Redis、关注图谱或推荐持久化在严格模式下不可用时返回明确依赖错误；只有显式开启开发读取降级时才允许使用种子候选或缺省特征。
