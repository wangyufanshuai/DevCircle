# DevCircle / MysticVerse 技术说明文档

## 1. 项目定位

DevCircle 是项目工程代号，v0.9 前台产品名为 MysticVerse。项目从普通技术社区演进为“推理、科幻、宇宙”三主题内容平台，目标是展示一个 Java 科班方向的高质量开源微服务项目。

当前版本重点不是直接承载真实大规模商业流量，而是提供一套可运行、可测试、可演示、便于继续迭代的生产预备级工程样板。项目覆盖内容发布、主题内容库、推荐信息流、搜索、解谜积分、社交互动、媒体上传、通知、审核、网关鉴权、可靠事件、容器化部署和 CI。

## 2. 技术栈

| 层级 | 技术 |
| --- | --- |
| 后端语言 | Java 21 |
| 后端框架 | Spring Boot 4.0.x, Spring Cloud 2025.1.x, Spring Cloud Alibaba 2025.1.x |
| 网关 | Spring Cloud Gateway |
| 数据库 | MySQL 8.4 |
| 缓存与信号 | Redis 8.x |
| 消息队列 | Kafka 4.1.x |
| 搜索 | Elasticsearch 9.x |
| 对象存储 | MinIO, S3-compatible API |
| 前端 | Vue 3, Vite, TypeScript, Naive UI, lucide-vue-next |
| 测试 | JUnit 5, Mockito, Testcontainers, Playwright |
| 部署 | Docker Compose, Helm, Kubernetes manifests |
| 可观测性 | Actuator, Prometheus, Grafana |
| CI/CD | GitHub Actions |

## 3. 仓库结构

```text
devcircle/
  dc-common/          通用模型、返回体、分页、事件、主题种子库
  dc-gateway/         API Gateway、鉴权、路由、用户上下文透传
  dc-auth/            登录、JWT/OAuth2 demo 能力
  dc-user/            用户资料、关注关系、二跳推荐
  dc-content/         帖子、主题内容、推理解谜、Outbox
  dc-comment/         评论服务骨架
  dc-interaction/     点赞、收藏、浏览、幂等与热度信号
  dc-search/          Elasticsearch 索引、搜索、重建
  dc-recommend/       规则推荐、轻量 ML 排序、曝光去重
  dc-notification/    站内通知、WebSocket demo
  dc-file/            MinIO 上传、媒体元数据
  dc-admin/           审核、封禁、标签、运营概览
  web/                Vue 3 前端
  deploy/             Compose 初始化、Kafka topic、Prometheus、Helm
  docs/               技术文档
```

## 4. 总体架构

```mermaid
flowchart LR
  Browser["Vue 3 前端"] --> Gateway["dc-gateway"]
  Gateway --> Auth["dc-auth"]
  Gateway --> User["dc-user"]
  Gateway --> Content["dc-content"]
  Gateway --> Interaction["dc-interaction"]
  Gateway --> Search["dc-search"]
  Gateway --> Recommend["dc-recommend"]
  Gateway --> Notify["dc-notification"]
  Gateway --> File["dc-file"]
  Gateway --> Admin["dc-admin"]

  Content --> MySQLContent[("MySQL: dc_content")]
  Interaction --> MySQLInteraction[("MySQL: dc_interaction")]
  User --> MySQLUser[("MySQL: dc_user")]
  File --> MySQLFile[("MySQL: dc_file")]
  File --> MinIO[("MinIO")]

  Content --> Outbox[("outbox_event")]
  Outbox --> Kafka["Kafka topics"]
  Kafka --> Search
  Kafka --> Notify

  Search --> ES[("Elasticsearch")]
  Recommend --> Redis[("Redis")]
  Interaction --> Redis
  User --> Redis
```

设计原则：

- 服务按业务域拆分，避免单体 Controller 堆叠。
- 写服务使用本地事务保证自身数据一致性。
- 跨服务一致性不使用 XA，采用 Outbox + Kafka + 消费端幂等。
- Redis 承载热点计数、兴趣标签、曝光集合和社交图谱缓存。
- Elasticsearch 只负责搜索索引，不作为业务主存储。
- 前端优先走真实 API，失败时 fallback 到本地 mock，保证演示稳定。

## 5. 服务说明

### 5.1 dc-gateway

职责：

- 统一 API 入口。
- 路由 `/api/auth/**`、`/api/posts/**`、`/api/recommend/**`、`/api/search/**`、`/api/admin/**` 等路径。
- demo token 鉴权。
- 管理接口要求 admin token。
- 向下游透传 `X-User-Id`、`X-Roles`、`X-Trace-Id`。

当前 demo token：

```http
Authorization: Bearer demo-access-token-demo
Authorization: Bearer demo-access-token-admin
```

### 5.2 dc-auth

职责：

- 密码登录 demo。
- GitHub/Gitee OAuth2 mock 回调。
- 刷新令牌与用户信息接口占位。

生产级 OAuth2 的完整 provider secret、回调域名、授权码换 token 和密钥轮换保留为后续增强点；当前实现重点是展示认证链路和 Gateway 资源保护模式。

### 5.3 dc-user

职责：

- 用户资料。
- 关注与取关。
- 粉丝和关注列表。
- 共同关注与二跳用户推荐。
- Redis 缓存关注集合与图谱特征。

典型 Redis key：

```text
user:{id}:followees
user:{id}:graph-features
```

### 5.4 dc-content

职责：

- 帖子发布与查询。
- 主题字段：`theme`、`contentKind`、`difficulty`、`solvable`。
- 详情字段：线索、嫌疑人、时间线、世界观、宇宙事实、相关内容。
- 推理解谜答案提交、积分和排行榜。
- Outbox 事件写入。

内容发布时，服务将帖子主表、正文、标签、媒体关联和 `outbox_event` 放在同一个本地事务中写入，避免“帖子写入成功但事件丢失”。

### 5.5 dc-interaction

职责：

- 点赞。
- 收藏。
- 浏览记录。
- 幂等处理。
- Redis 热度信号。

接口读取 `Idempotency-Key` 请求头。重复点赞或收藏保持成功响应，但不会重复增加计数。

典型 Redis key：

```text
post:{id}:stats
feed:hot-posts
user:{id}:interest-tags
```

### 5.6 dc-search

职责：

- 消费内容事件并同步 Elasticsearch。
- 提供主题搜索。
- 提供索引重建入口。
- Elasticsearch 不可用时使用共享主题内容库 fallback。

搜索支持：

- 关键词。
- 主题：`DEDUCTION`、`SCI_FI`、`COSMOS`。
- 内容类型：`CASE_FILE`、`PUZZLE`、`FICTION`、`WORLDBUILDING`、`COSMIC_ARTICLE`、`OBSERVATION_LOG`。
- 难度：`EASY`、`MEDIUM`、`HARD`。

### 5.7 dc-recommend

职责：

- 推荐 Feed。
- 规则排序 fallback。
- 轻量 ML 线性打分。
- Redis 兴趣、热度、曝光和社交图谱特征。
- 曝光记录与去重。

推荐场景：

```text
mixed
deduction
sci_fi
cosmos
image
video
```

v0.9 做了一个演示友好的保护：如果某个用户的曝光集合把候选池全部过滤空，则回退到不使用曝光惩罚的候选池，避免本地演示多刷新几次后首页空白。

### 5.8 dc-file

职责：

- 图片和视频上传。
- 文件类型校验。
- MinIO/S3 存储。
- `media_asset` 元数据落库。

允许类型：

```text
image/jpeg
image/png
image/webp
video/mp4
```

当前不做 FFmpeg 转码、多清晰度切片和真实内容审核，只记录媒体元数据和审核状态。

### 5.9 dc-notification

职责：

- 站内通知列表。
- WebSocket demo 推送。
- 审核、互动、解谜结果通知入口。

WebSocket 路径：

```text
/ws/notifications
```

### 5.10 dc-admin

职责：

- 待审核帖子列表。
- 审核通过/驳回。
- 用户封禁。
- 标签创建。
- 运营概览指标。

管理接口通过 Gateway 要求 admin token。

## 6. 主题内容模型

MysticVerse 的核心差异来自主题化内容模型，而不是普通社区 Feed。

### 6.1 Theme

| 值 | 说明 |
| --- | --- |
| `DEDUCTION` | 推理馆：案件、谜题、线索、答题 |
| `SCI_FI` | 科幻馆：短篇、世界观、技术设定 |
| `COSMOS` | 宇宙档案：天体、观测、科普 |

### 6.2 ContentKind

| 值 | 说明 |
| --- | --- |
| `CASE_FILE` | 案件档案 |
| `PUZZLE` | 逻辑谜题 |
| `FICTION` | 科幻短篇 |
| `WORLDBUILDING` | 世界观设定 |
| `COSMIC_ARTICLE` | 宇宙科普档案 |
| `OBSERVATION_LOG` | 观测日志 |

### 6.3 共享主题内容库

`dc-common` 中的 `ThemeContentLibrary` 是 v0.9 的共享内容源：

- 120 条示例内容。
- 推理、科幻、宇宙各 40 条。
- 后端推荐 fallback 使用它。
- 搜索 fallback 使用它。
- 内容详情 fallback 使用它。
- 前端 mock 使用同构字段生成。

这样做的目的：

- 避免前后端 mock 字段漂移。
- 让演示环境不依赖必须预置数据库数据。
- 保证搜索、推荐、详情在基础设施不稳定时仍能展示内容。

## 7. 核心业务链路

### 7.1 发帖链路

```mermaid
sequenceDiagram
  participant Web as Web
  participant Gateway as Gateway
  participant Content as dc-content
  participant DB as MySQL
  participant Outbox as outbox_event
  participant Kafka as Kafka
  participant Search as dc-search
  participant ES as Elasticsearch

  Web->>Gateway: POST /api/posts
  Gateway->>Content: 转发用户上下文
  Content->>DB: 写 post / post_content / post_tag / post_media
  Content->>Outbox: 同事务写 post.created
  Content-->>Web: 返回 PENDING/PUBLISHED
  Content->>Kafka: Outbox publisher 发送事件
  Content->>Outbox: 标记 PUBLISHED
  Kafka->>Search: 消费 post.created
  Search->>ES: 写入索引
```

一致性策略：

- 发帖和事件写入在同一个本地事务里。
- Kafka 发送成功后才把 Outbox 标记为 `PUBLISHED`。
- Kafka 失败时事件保持 `NEW`，后续补偿任务重试。
- 消费端使用去重表或幂等逻辑避免重复消费副作用。

### 7.2 推荐链路

```mermaid
flowchart TD
  Request["GET /api/recommend/feed"] --> Scene["scene 归一化"]
  Scene --> Candidates["主题内容候选池"]
  Candidates --> Feature["提取特征"]
  Feature --> RedisSignals["Redis: 兴趣/热度/曝光/图谱"]
  RedisSignals --> Rank["线性模型排序"]
  Rank --> Fallback["模型异常时规则 fallback"]
  Rank --> Page["CursorPage<FeedItem>"]
  Page --> Exposure["POST /api/recommend/exposures"]
```

特征：

- 标签匹配。
- 热度。
- 新鲜度。
- 是否关注作者。
- 共同关注数量。
- 媒体类型与场景匹配。
- 是否已曝光。

响应字段：

- `algorithm`: `THEME_LINEAR_MODEL` 或 `RULE_FALLBACK`。
- `features`: 特征明细。
- `reason`: 可读推荐理由。
- `score`: 排序分。

### 7.3 推理解谜链路

```mermaid
sequenceDiagram
  participant Web as Web
  participant Content as dc-content
  participant DB as MySQL

  Web->>Content: POST /api/deduction/cases/{id}/answers
  Content->>Content: 读取案件 answerKeywords
  Content->>DB: 写 deduction_attempt
  Content->>DB: 正确时增加 deduction_score
  Content-->>Web: correct / duplicated / points / explanation
```

设计点：

- 同一用户同一案件重复提交不重复加分。
- 判题使用本地关键词规则，不引入 AI 自动判题。
- 答案解析来自主题内容库或数据库字段。

## 8. 数据设计

开发环境使用一个 MySQL 实例多个 schema：

```text
dc_auth
dc_user
dc_content
dc_comment
dc_interaction
dc_notification
dc_file
dc_search
```

核心表类别：

| 域 | 表 |
| --- | --- |
| 用户 | `user_account`, `user_profile`, `user_follow` |
| 内容 | `post`, `post_content`, `post_tag`, `tag`, `post_media`, `deduction_attempt`, `deduction_score` |
| 互动 | `like_record`, `favorite_record`, `view_record`, `post_counter`, `user_counter` |
| 文件 | `media_asset` |
| 事件 | `outbox_event`, `consumer_dedup` |

索引原则：

- 业务幂等用唯一索引保障，例如用户对同一对象的点赞/收藏。
- 高频列表使用组合索引，例如主题、状态、发布时间。
- Outbox 使用状态和创建时间索引，便于批量扫描 `NEW` 事件。
- 消费端使用 `event_id` 去重。

## 9. Kafka 事件

主要 topic：

```text
post.created
post.updated
post.deleted
comment.created
interaction.changed
notification.created
audit.completed
```

事件设计：

- `eventId`: 全局唯一事件 ID。
- `aggregateType`: 聚合类型，如 `POST`。
- `aggregateId`: 聚合 ID。
- `topic`: Kafka topic。
- `payload`: JSON 载荷。
- `status`: `NEW`、`PUBLISHED` 等。

语义：

- 至少一次投递。
- 发送成功才标记发布。
- 消费者必须幂等。
- 失败可通过 Outbox 或死信/补偿重放。

## 10. Redis 设计

| Key | 用途 |
| --- | --- |
| `feed:hot-posts` | 热门帖子 ZSet |
| `user:{id}:interest-tags` | 用户最近兴趣标签 |
| `user:{id}:exposed-posts` | 用户已曝光帖子集合 |
| `post:{id}:stats` | 帖子互动统计 |
| `user:{id}:followees` | 用户关注集合 |
| `user:{id}:graph-features` | 图谱特征缓存 |
| `recommend:model:weights` | 推荐模型权重预留 |

Redis 不可用时，推荐服务会使用默认兴趣和基础分数，保证接口可降级。

## 11. Elasticsearch 设计

搜索服务维护帖子索引，包含：

- 标题。
- 摘要。
- 作者。
- 标签。
- 主题。
- 内容类型。
- 难度。
- 发布时间。

搜索路径：

```text
GET /api/search/posts?keyword=&theme=&contentKind=&difficulty=&page=&size=
```

重建路径：

```text
POST /api/search/admin/reindex
```

当 Elasticsearch 不可用时，服务返回共享主题内容库的 fallback 结果，保证本地演示仍可用。

## 12. REST API 概览

### 12.1 内容

```http
GET  /api/posts?theme=&contentKind=&difficulty=&page=&size=
GET  /api/posts/{id}
GET  /api/posts/{id}/related?size=6
POST /api/posts
```

### 12.2 推理

```http
POST /api/deduction/cases/{id}/answers
GET  /api/deduction/cases/{id}/attempts/me
GET  /api/deduction/leaderboard
```

### 12.3 推荐

```http
GET  /api/recommend/feed?scene=mixed&cursor=&size=
POST /api/recommend/exposures
```

### 12.4 搜索

```http
GET  /api/search/posts?keyword=钟楼&theme=DEDUCTION
POST /api/search/admin/reindex
```

### 12.5 互动

```http
POST /api/posts/{id}/like
POST /api/posts/{id}/favorite
```

互动接口建议携带：

```http
Idempotency-Key: like-1001-user-2
```

### 12.6 用户和图谱

```http
POST   /api/users/{id}/follow
DELETE /api/users/{id}/follow
GET    /api/users/{id}/followees
GET    /api/users/{id}/followers
GET    /api/users/{id}/recommendations
```

### 12.7 文件

```http
POST /api/files
```

返回媒体 ID、对象 key、访问 URL、文件类型、大小、宽高、时长和审核状态。

### 12.8 管理

```http
GET  /api/admin/audits/posts
POST /api/admin/audits/posts/{id}/approve
POST /api/admin/audits/posts/{id}/reject
POST /api/admin/users/{id}/ban
POST /api/admin/tags
GET  /api/admin/metrics/overview
```

## 13. 前端设计

前端位于 `web/`，使用 Vue 3 + Vite + TypeScript + Naive UI。

主要页面区域：

- 左侧主题导航：星环首页、推理馆、科幻馆、宇宙档案、创作台、审核台。
- 顶部搜索。
- 首页主题 Hero。
- 推荐 Feed 卡片。
- 详情侧栏：线索板、嫌疑人、时间线、世界观、宇宙事实、相关内容。
- 右侧信息栏：主题概览、宇宙信号、排行榜、待审核、通知。

API 客户端策略：

- 优先请求真实后端 API。
- 请求失败时 fallback 到 `web/src/api/mock.ts`。
- Playwright 覆盖首页、频道切换、搜索、发布、详情、答题、移动端布局。

## 14. 本地运行

### 14.1 启动基础设施

```powershell
docker compose up -d mysql redis kafka elasticsearch minio prometheus grafana
.\deploy\kafka\create-topics.ps1
```

### 14.2 设置 Java 21

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

### 14.3 后端测试

```powershell
$env:DOCKER_HOST='npipe:////./pipe/docker_engine'
.\mvnw.cmd -B test
```

### 14.4 前端运行

```powershell
cd web
npm install
npm run dev
```

默认前端地址：

```text
http://127.0.0.1:5173/
```

默认 Gateway 地址：

```text
http://127.0.0.1:28080/
```

## 15. 测试策略

### 15.1 单元测试

覆盖内容：

- 通用返回体。
- 推荐排序。
- 推理答案关键词判定。
- 管理审核状态。
- Gateway 鉴权。
- 主题内容库数量和分布。

### 15.2 集成测试

Testcontainers 覆盖：

- MySQL。
- Redis。
- Kafka。
- Elasticsearch。
- MinIO。

核心链路：

- 发帖写 MySQL 和 Outbox。
- Outbox 发布 Kafka。
- Elasticsearch 索引和搜索。
- 点赞/收藏幂等。
- Redis 热度和曝光影响推荐。
- MinIO 上传和媒体元数据。

### 15.3 前端 E2E

Playwright 覆盖：

- 首页内容密度。
- 三主题频道。
- 搜索。
- 详情打开。
- 推理答题。
- 创作发布。
- 审核操作。
- 移动端无横向溢出。
- 中文无乱码。

### 15.4 CI

GitHub Actions job：

- `backend`: `./mvnw -B test`，上传 Surefire 报告。
- `frontend`: `npm ci`、`npm run build`、`npx playwright test`。
- `helm`: `helm lint deploy/helm/devcircle`、`docker compose config`。

## 16. 部署说明

### 16.1 Docker Compose

Compose 用于本地开发基础设施，不直接把所有微服务做成镜像编排。主要服务：

- MySQL: `23306 -> 3306`
- Redis: `26379 -> 6379`
- Kafka: `29092`
- Elasticsearch: `29200 -> 9200`
- MinIO: `29000 -> 9000`, `29001 -> 9001`
- Prometheus: `19090 -> 9090`
- Grafana: `13000 -> 3000`

### 16.2 Helm/Kubernetes

`deploy/helm/devcircle` 提供 Kubernetes 部署骨架，包括：

- Deployment。
- Service。
- ConfigMap。
- Secret。
- Ingress。
- HPA。
- ServiceMonitor。

当前 Helm 主要用于交付结构展示和 CI lint，生产级参数、密钥管理、镜像版本和多环境 values 仍可继续增强。

## 17. 可观测性

每个后端服务启用 Spring Boot Actuator：

```text
/actuator/health
/actuator/info
/actuator/prometheus
```

Prometheus 配置位于：

```text
deploy/prometheus/prometheus.yml
```

Grafana 通过 Compose 启动后可接入 Prometheus 数据源。

## 18. 安全设计

当前安全策略：

- Gateway 统一鉴权。
- `/api/auth/**` 公开。
- 其他接口检查 Bearer token。
- 管理接口要求 admin token。
- Gateway 透传用户 ID、角色和 TraceId。
- 上传接口校验文件类型。
- 互动接口使用幂等 key。

边界说明：

- 当前不是完整生产 OAuth2/JWT 安全体系。
- GitHub/Gitee OAuth2 以 demo profile 和 mock 回调为主。
- 真实生产需要补充 client secret 管理、JWT 密钥轮换、CSRF/CORS 策略、RBAC 持久化和审计日志。

## 19. 当前边界和后续方向

已经实现或具备骨架：

- 三主题内容平台。
- 120 条共享主题内容库。
- 内容详情和相关内容召回。
- 推理解谜和排行榜。
- 规则 + 轻量线性模型推荐。
- 搜索 fallback 和 Elasticsearch 集成。
- MySQL/Redis/Kafka/ES/MinIO Testcontainers。
- Vue 3 演示前端。
- Docker Compose、Helm、CI。

仍可继续增强：

- 使用真实数据库 seed 替代纯 fallback 内容库。
- 完整 OAuth2 provider 授权码链路。
- 更严格的 RBAC 权限模型。
- Kafka 消费失败死信队列和管理后台重放入口。
- 更完整的 OpenAPI 生成和前端类型同步。
- 生产镜像构建、版本发布和多环境 Helm values。
- 搜索高亮、聚合筛选和拼音/同义词分析。
- 内容审核工作流和操作审计。

## 20. 面试讲解建议

可以按以下顺序介绍项目：

1. 先说明它不是普通 CRUD 社区，而是三主题内容平台。
2. 介绍微服务拆分和 Gateway 统一入口。
3. 讲发帖链路：MySQL 本地事务 + Outbox + Kafka + ES。
4. 讲推荐链路：Redis 信号 + 轻量模型 + 曝光去重 + fallback。
5. 讲推理解谜：主题详情、答案关键词、积分幂等。
6. 讲测试：Testcontainers 把 MySQL/Redis/Kafka/ES/MinIO 都纳入默认测试。
7. 讲工程交付：Compose、Helm、GitHub Actions、Playwright。

这个项目的核心亮点是：业务主题有辨识度，工程链路覆盖完整，测试和交付不是空壳。
