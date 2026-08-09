# DevCircle / MysticVerse 技术说明

## 定位

DevCircle 是工程代号，MysticVerse 是前台产品名。v1.2 将项目定位为“生产预备级主题内容平台 + 内容治理/推荐实验 + 工程化运行平台”：围绕推理馆、科幻馆、宇宙档案提供内容发布、详情阅读、解谜积分、推荐、搜索、互动、通知、审核、媒体上传、认证授权、容器交付和可观测能力，并继续补齐审核队列、风险画像、申诉、推荐实验分桶、事件日志、显式降级和运维接口。

项目不承诺直接支撑真实大规模商业流量，但工程结构刻意接近真实后端项目：数据优先落 MySQL，跨服务一致性使用 Outbox + Kafka，搜索同步 Elasticsearch，推荐记录特征与曝光，前端明确展示降级状态。

## 模块边界

| 模块 | 职责 |
| --- | --- |
| `dc-gateway` | 统一入口、TraceId、JWT 校验、角色拦截、用户上下文透传 |
| `dc-identity` | 第一阶段生产 Identity 执行单元：进程内承载 `dc-auth` 认证运行时与个人仪表盘；用户、互动、通知仍通过兼容服务提供 |
| `dc-content-deployment` | Content 聚合门面：聚合详情视图，并将内容、评论、媒体和治理请求路由到兼容领域服务 |
| `dc-discovery` | Discovery 生产边界：第一阶段进程内承载 `dc-search` Search 代码模块，实验能力保留兼容门面；推荐独立运行在 `dc-recommend` |
| `dc-auth` | 保留认证领域 Maven 模块、逻辑事件 producer、认证 schema/Flyway 迁移和可回滚可执行镜像；正常生产不单独运行该进程 |
| `dc-content` | 主题帖子、详情、相关内容、推理解谜、审核状态、Outbox |
| `dc-search` | Search 领域模块；正常生产由 `dc-discovery` 承载，独立进程仅用于 `legacy-search rollback` |
| `dc-recommend` | 直接承载推荐排序、Redis 热度和曝光、推荐特征日志、实验分桶与多路召回 |
| `dc-interaction` | 点赞、收藏、幂等写入、热度信号 |
| `dc-notification` | 通知落库、Kafka 消费、WebSocket 推送 |
| `dc-file` | MinIO 上传、媒体元数据、MIME/扩展名/魔数校验 |
| `dc-admin` | 审核、审计日志、运营概览、治理队列、风险用户、推荐实验管理 |
| `web` | Vue 3 前台和管理演示 |

## 核心链路

```mermaid
flowchart LR
  Web["Vue MysticVerse"] --> Gateway["dc-gateway"]
  Gateway --> Identity["dc-identity<br/>阶段 1: embedded Auth + dashboard"]
  Gateway --> ContentAggregate["dc-content-aggregate"]
  Gateway --> Discovery["dc-discovery"]
  Gateway --> Recommend["dc-recommend"]

  Identity --> AuthRuntime["dc-auth runtime<br/>(进程内)"]
  Identity --> IdentityLegacy["dc-user / dc-interaction / dc-notification<br/>兼容领域服务"]
  ContentAggregate --> ContentLegacy["dc-content / dc-comment / dc-file / dc-admin<br/>兼容领域服务"]
  ContentAggregate --> IdentityLegacy
  Discovery --> SearchEmbedded["Search embedded in Discovery<br/>first phase"]
  Gateway -. "现存兼容直通路由" .-> IdentityLegacy

  ContentLegacy --> MySQLContent[("MySQL dc_content")]
  ContentLegacy --> Outbox[("outbox_event")]
  Outbox --> Kafka["Kafka"]
  Kafka --> SearchEmbedded
  Kafka --> Recommend
  Kafka --> IdentityLegacy
  SearchEmbedded --> ES[("Elasticsearch")]
  Recommend --> Redis[("Redis")]
  IdentityLegacy --> MySQLIdentity[("MySQL identity schemas")]
  ContentLegacy --> MinIO[("MinIO")]
```

该图描述当前生产收口阶段，而不是最终四进程形态：Gateway 的主路由已指向 Identity、Content Aggregate、Discovery 和独立的 `dc-recommend`；Identity 目前只完成 Auth 运行时内嵌和仪表盘聚合，用户、互动、通知仍是独立兼容服务，且 Gateway 仍保留这些接口的兼容直通路由。Content Aggregate 仍会调用旧领域进程；Discovery 的 Search 代码模块 `dc-search` 已第一阶段进程内承载。推荐请求由 Gateway 直接路由到 `dc-recommend`，不经过或嵌入 Discovery。独立 Search 只保留 `deploy/compose/docker-compose.legacy-search-rollback.yml` 的短期回滚用途。

## 数据策略

- 开发环境使用一个 MySQL 实例、多个 schema。
- 每个写服务使用 Flyway 管理 schema。
- `ThemeContentLibrary` 只作为首次初始化数据和开发环境显式读取降级来源；生产正常链路只读 MySQL/ES，依赖故障不得伪造成功。
- 写内容时同事务写 `post`、`post_content`、`post_tag`、`post_media`、`outbox_event`。
- 审核通过真实更新 `post.status`，写 `post_audit_record`、`audit_log` 和 `audit.completed` Outbox。
- 发帖后会生成 `moderation_task`，本地规则写入 `content_risk_signal` 和 `user_risk_profile`，高风险内容进入人工队列。
- 用户申诉写入 `appeal_record` 和 `appeal.created` Outbox，便于后续复审补偿。
- 推荐曝光写 Redis，同时写 `recommendation_impression`，便于后续做实验分析。
- 推荐点击、点赞、收藏、答题、详情访问写 `recommend_event_log`，并发布 `recommend.event.logged` 事件。

## 认证与安全

- 正常生产由 `dc-identity` 进程内的 `dc-auth` 认证运行时签发 JWT access token 和 refresh token；`dc-auth` 的逻辑 producer 名称、schema 和 Flyway 迁移保持不变，独立 `dc-auth` 可执行镜像仅用于 legacy auth rollback。
- refresh token 哈希后写库，退出时撤销。
- Gateway 解析 JWT 并透传 `X-User-Id`、`X-Roles`、`X-Trace-Id`。
- 管理接口要求 `ADMIN` 角色。
- 本地与生产统一使用邮箱密码或 GitHub OAuth，不提供固定 Token 登录通道。
- 上传校验文件大小、MIME、扩展名和魔数，拒绝脚本类伪装上传。

## 推荐系统

推荐系统保持 Java 本地轻量实现：

- 候选来源：内容服务 MySQL 列表优先；生产依赖失败直接返回错误，开发环境只有显式开启读取降级时才允许 seed fallback。
- 召回来源：主题召回、标签召回、热榜召回、关注作者召回、同系列召回、冷启动召回。
- 实验能力：`recommend_experiment` 与 `recommend_experiment_bucket` 控制实验、分桶、流量比例和权重配置。
- 排序特征：主题匹配、标签兴趣、热度、时效、关注作者、共同关注、曝光惩罚。
- 返回字段：`algorithm`、`features`、`reason`、`degraded`、`source`、`experimentId`、`bucket`、`recallSources`。
- Redis key：`feed:hot-posts`、`user:{id}:interest-tags`、`user:{id}:exposed-posts`、`user:{id}:followees`。

## 内容治理与风控

治理链路保留本地规则，不依赖外部内容安全 API：

- 机审规则识别敏感标签、重复内容、短内容、视频/上传异常和短时间高频发布。
- `moderation_task` 记录队列、机审结果、风险等级、SLA、复审状态和处理人。
- 人工动作包括通过、驳回、要求修改、升级复审。
- `appeal_record` 记录用户申诉，后续可接入复审流程。
- `user_risk_profile` 支持正常、观察、限流、禁发、封禁等状态。
- Gateway 预留用户状态拦截：封禁用户阻断写接口，禁发用户阻断发帖。

## 部署与可观测

- Compose profiles：`infra` 只启动基础设施，`app` 启动应用服务，`demo` 启动全栈。生产 Compose 的 aggregate profile 将 `/api/auth/**` 与 `/api/me/**` 路由到 `dc-identity`；`/api/recommend/**` 由 Gateway 直接路由到独立 `dc-recommend`，不嵌入 Discovery；`dc-user`、`dc-interaction`、`dc-notification` 仍是兼容服务。独立 `dc-auth` 仅由 `deploy/compose/docker-compose.legacy-auth-rollback.yml` 临时启用，以回滚 Gateway/Admin 的认证上游。
- Helm values：`values-dev.yaml` 和 `values-prod.yaml` 分离镜像、资源、Ingress 和生产密钥策略；生产不启用 demo token 或 mock fallback。生产模板 fail closed：Identity 必须启用安全 Cookie，前端和 GitHub callback 必须从公开 HTTPS origin 推导，GitHub OAuth 凭证及内部运维密钥必须由非可选 Secret 引用投影。Chart 创建 Secret 时缺少必需映射会在 render 阶段失败；外部 `existingSecret` 无法由 Helm render 验证，缺失会阻止 Pod 启动。
- CI：后端测试、前端构建、Playwright、Helm lint、Compose config、GHCR 镜像构建、Trivy 扫描。
- 可观测：Actuator + Prometheus + Grafana + Jaeger；告警覆盖服务不可用、5xx、P95、JVM、连接池压力。

## 生产差距

v1.2 是生产预备版，还不是大规模生产系统。后续应继续补：

- 完整多环境 Secret 管理和灰度发布。
- Kafka lag、Outbox backlog、ES 健康等更细指标。
- 推荐实验离线评估、自动报表和审批流。
- OAuth2 设备风控、已认证账号的显式绑定流程和密钥轮换自动化。
- 当前 SHA 的 Testcontainers、真实 Compose、真实认证 E2E、Linux 性能/恢复、Helm 实际 render 与 Cosign Registry 验签；以及云服务器或 Kubernetes 的真实压测数据和长期运行报告。

Search-in-Discovery 当前证据仅覆盖 Windows 非容器回归与 Compose 静态配置；因此 Docker 实际启动、Testcontainers、真实 Compose、Helm 集群部署、目标 Linux 性能/恢复及 Cosign Registry 验签均仍未验证。当前静态资源合同为 22 个服务、13.62 GiB 容器内存和 2 个宿主机发布服务；这是配置结果，不是运行时容量结论。
