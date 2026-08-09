# DevCircle

[![CI](https://github.com/wangyufanshuai/DevCircle/actions/workflows/ci.yml/badge.svg)](https://github.com/wangyufanshuai/DevCircle/actions/workflows/ci.yml)

DevCircle 是工程代号，前台产品名为 **MysticVerse**：一个围绕“推理馆、科幻馆、宇宙档案”的读者与解谜玩家内容社区。

## 技术栈

- Java 21, Spring Boot 4, Spring Cloud Gateway
- MySQL 8.4, Redis, Kafka 4.1, Elasticsearch 9, MinIO
- Vue 3, Vite, TypeScript, Naive UI, Playwright
- Docker Compose, Helm, GHCR, Prometheus, Grafana, Jaeger
- JUnit 5, Testcontainers, k6

## v1.3 能力矩阵

| 能力 | 状态 |
| --- | --- |
| 主题内容库 | 100 条推理、40 条科幻、40 条宇宙内容作为首次初始化数据；生产读请求不使用 seed 降级 |
| 推理馆 | 覆盖密室、时间线、证词矛盾、物证误导、密码谜题、航海、列车、剧院、档案室、冷案复盘 |
| 内容闭环 | 发帖写 MySQL、标签、媒体、Outbox，审核前状态为 `PENDING` |
| 内容治理 | 发帖生成审核任务，包含机审结果、风险等级、SLA、复审状态和人工决策 |
| 风控运营 | 内容风险信号、用户风险画像、禁发/封禁占位拦截和管理端治理接口 |
| 推荐实验 | 多路召回、实验 id、分桶、推荐事件日志、实验启停管理 |
| 工程启动 | `.env.example`、Windows dev scripts、Compose `infra/app/demo` profiles |
| 运行韧性 | Graceful shutdown、Actuator probes、Gateway 请求日志、显式降级来源 |
| 运维接口 | 系统健康、Outbox backlog、Outbox replay、推荐降级概览 |
| 前端工程化 | Vue Router、内存 Access Token、HttpOnly Refresh Cookie、统一响应和幂等写请求 |
| 可观测 | Prometheus alerts、Grafana dashboard、Jaeger OTLP endpoint |

## 模块

- `dc-gateway`: 统一入口、TraceId、JWT 鉴权、角色拦截、限流、用户上下文透传、请求日志。
- `dc-identity`: 第一阶段的 Identity 部署单元；生产进程内嵌 `dc-auth` 认证运行时和个人仪表盘。`dc-user`、`dc-interaction`、`dc-notification` 仍以兼容服务保留，尚未完成完整四聚合。
- `dc-content-deployment`: Content 生产部署单元；当前进程内承载 `dc-content` 与 `dc-file`，评论和治理继续使用兼容服务。
- `dc-discovery`: Discovery 生产边界；第一阶段在该进程内承载 `dc-search` Search 代码模块，实验能力继续兼容聚合。推荐不是 Discovery 的内嵌能力，而是独立的 `dc-recommend` 进程，由 Gateway 直接路由。
- `dc-auth`: 邮箱密码登录、Refresh Token 轮换、GitHub OAuth、审计日志；逻辑 producer、schema 和 Maven 模块保留，由 `dc-identity` 进程承载，独立可执行镜像仅用于 legacy auth rollback。
- `dc-user`: 关注关系、共同关注、二跳推荐。
- `dc-content`: 主题内容、详情、相关内容、推理解谜、审核状态、Outbox。
- `dc-interaction`: 点赞、收藏、幂等写入、Redis 热度信号。
- `dc-search`: 保留的 Search 领域模块；正常生产由 `dc-discovery` 进程承载，独立进程仅用于 [`legacy-search rollback Compose`](deploy/compose/docker-compose.legacy-search-rollback.yml)。
- `dc-recommend`: 可解释固定权重规则、候选召回、曝光去重、推荐实验。
- `dc-notification`: 站内通知、Kafka 消费、WebSocket 推送。
- `dc-file`: MinIO 上传、媒体元数据、上传安全校验。
- `dc-admin`: 内容治理、风险运营、推荐实验、审计日志、工程运维接口。
- `web`: MysticVerse 路由化前台与独立运营入口。

## 当前部署收口状态

生产 Compose 的认证路由为 `Gateway /api/auth/** → dc-identity:8201`，`/api/me/**` 也由 `dc-identity` 处理；用户、互动和通知请求仍分别路由至其兼容服务。认证逻辑、事件 producer 名称 `dc-auth`、认证 schema 与 Flyway 迁移、以及 `dc-auth` Maven 模块继续保留，变化的是生产执行载体而不是认证领域契约。

独立的 `dc-auth` 可执行镜像不属于正常生产拓扑；仅在 [legacy auth rollback Compose](deploy/compose/docker-compose.legacy-auth-rollback.yml) 中用于短期回滚。该覆盖文件会将 Gateway 与管理端的认证上游切回 `dc-auth:8101`，回滚结束后应恢复 `dc-identity` 路由。

生产推荐路由为 `Gateway /api/recommend/** → dc-recommend:8107`；`dc-recommend` 是独立进程，推荐运行时未嵌入 `dc-discovery`。Search 路由为 `Gateway /api/search/** → dc-discovery:8203`，其中由 Discovery 进程承载 `dc-search` 代码模块。

生产内容路由为 `Gateway → dc-content-aggregate:8202`。该 JVM 直接承载 Content 与 File 代码、各自独立的 Flyway 历史和逻辑 Outbox producer `dc-content`；独立 `dc-content` 与 `dc-file` 镜像仅用于 [legacy content rollback Compose](deploy/compose/docker-compose.legacy-content-rollback.yml)。公开内容响应不返回存储桶对象 URL，浏览器必须通过 `/api/files/access` 取得短期读取地址。

生产静态资源预算当前为 20 个 Compose 服务、12.62 GiB 容器内存上限，仅 Web 和 Gateway 向宿主机发布端口。该数字是配置门禁结果，不是 Linux 实际运行、容量或可用性证明。当前聚合证据仅覆盖 Windows 非容器回归和 Compose 静态解析；Docker、Testcontainers、Helm 实际部署、目标 Linux 性能/恢复和 Cosign Registry 验签仍未验证。

## 本地启动

准备环境：

```powershell
Copy-Item .env.example .env
.\scripts\dev\doctor.ps1
```

只启动基础设施：

```powershell
.\scripts\dev\up-infra.ps1
```

编译后启动完整 demo：

```powershell
.\scripts\dev\compile-build.ps1
.\scripts\dev\up-demo.ps1 -Build
```

停止：

```powershell
.\scripts\dev\down.ps1
```

访问：

- 前端：`http://localhost:5501`
- Gateway：`http://localhost:8080`
- Prometheus：`http://localhost:19090`
- Grafana：`http://localhost:13000`
- Jaeger：`http://localhost:16686`

## 认证与前端配置

Demo Token、前端本地 Token 和 Mock fallback 已删除。使用邮箱密码注册/登录或 GitHub OAuth；Access Token 只驻留内存，Refresh Token 使用 Secure、HttpOnly Cookie。前端只需要配置 API 地址：

```bash
VITE_API_BASE_URL=http://localhost:8080
```

生产部署使用固定提交 SHA 与强随机密钥，详见 [生产运行手册](docs/production-runbook.md)。Helm 生产 values 对 Identity 强制 `AUTH_COOKIE_SECURE=true`、从 `publicOrigin` 推导前端与 GitHub 回调地址，并要求 GitHub 凭证和内部运维密钥来自非可选 Kubernetes Secret 引用。values 映射不完整或由 Chart 创建的 Secret 缺少必需键时 render 失败；引用外部 `existingSecret` 时，Helm render 无法验证 Secret 或键是否真实存在，缺失会因 `optional: false` 在 Pod 启动阶段失败。

## 本轮验证边界

当前版本恢复本地全量验收：

```powershell
.\mvnw.cmd -B test
cd web
npm run build
npx playwright test
helm lint deploy/helm/devcircle -f deploy/helm/devcircle/values-dev.yaml
helm lint deploy/helm/devcircle -f deploy/helm/devcircle/values-prod.yaml
docker compose --profile infra config
docker compose --profile demo config
```

验收结论记录在 [本地验收报告](docs/acceptance-report.md)。

## 文档

- [工程化就绪说明](docs/engineering-readiness.md)
- [本地验收报告](docs/acceptance-report.md)
- [技术说明](docs/technical-specification.md)
- [主题平台](docs/theme-platform.md)
- [内容治理平台](docs/governance-platform.md)
- [推荐实验平台](docs/recommendation-experiment.md)
- [云服务器 Docker 部署](docs/deployment-cloud-docker.md)
- [压测报告](docs/performance-report.md)
- [服务等级目标](docs/service-level-objectives.md)
- [可观测告警运行手册](docs/observability-runbook.md)
- [OAuth2 与安全](docs/oauth2-security.md)
- [推荐系统](docs/ml-recommendation.md)
