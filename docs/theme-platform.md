# MysticVerse 三主题平台设计

DevCircle v0.8 将前台产品重塑为 MysticVerse，核心主题为推理、科幻和宇宙。三类内容共用帖子、互动、搜索、推荐和审核基础设施，但在内容类型、推荐场景和前端展示上明确区分。

## 主题模型

- `DEDUCTION`: 推理馆，包含案件档案和逻辑谜题。
- `SCI_FI`: 科幻馆，包含科幻短篇和世界观设定。
- `COSMOS`: 宇宙档案，包含宇宙科普和观测日志。

内容类型使用 `contentKind` 表示：`CASE_FILE`、`PUZZLE`、`FICTION`、`WORLDBUILDING`、`COSMIC_ARTICLE`、`OBSERVATION_LOG`。帖子保留 `TEXT`、`IMAGE`、`VIDEO` 作为媒体形态字段。

## 推理解谜

推理内容可以设置 `solvable=true`、`clues` 和 `solution`。用户通过 `POST /api/deduction/cases/{id}/answers` 提交答案。服务端会写入 `deduction_attempt`，同一用户同一案件唯一，正确答案会增加 `deduction_score` 和 `post.solved_count`。

当前判题是本地演示规则，不做 AI 自动判题。后续可以替换为更严格的关键词、人工审核或模型辅助判定。

## 推荐与搜索

推荐接口支持 `scene=deduction|sci_fi|cosmos|mixed`。排序信号包括主题匹配、标签兴趣、热度、新鲜度、关注作者、共同关注、曝光惩罚和解谜完成状态。

搜索接口支持 `theme`、`contentKind`、`difficulty` 筛选。Elasticsearch 不可用时，服务会返回主题化 fallback 数据，保证本地演示页面仍可查看。

## 前端信息架构

首页直接展示 MysticVerse 品牌和三主题价值，不再使用通用社交平台表达。导航包括星环首页、推理馆、科幻馆、宇宙档案、创作台和审核台。右侧栏提供主题概览、宇宙信号、解谜排行榜、审核队列和通知。

创作台支持主题、内容类型、难度、媒体形态、线索和隐藏答案字段，提交后进入审核与事件队列。
