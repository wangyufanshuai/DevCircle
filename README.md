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
- `dc-identity`: auth、user、interaction、notification 的兼容聚合部署门面。
- `dc-content-deployment`: content、comment、file、moderation 的兼容聚合部署门面。
- `dc-discovery`: search、recommend、experiment 的兼容聚合部署门面。
- `dc-auth`: 邮箱密码登录、Refresh Token 轮换、GitHub OAuth、审计日志。
- `dc-user`: 关注关系、共同关注、二跳推荐。
- `dc-content`: 主题内容、详情、相关内容、推理解谜、审核状态、Outbox。
- `dc-interaction`: 点赞、收藏、幂等写入、Redis 热度信号。
- `dc-search`: Elasticsearch 索引、Kafka 内容事件同步、重建入口。
- `dc-recommend`: 可解释固定权重规则、候选召回、曝光去重、推荐实验。
- `dc-notification`: 站内通知、Kafka 消费、WebSocket 推送。
- `dc-file`: MinIO 上传、媒体元数据、上传安全校验。
- `dc-admin`: 内容治理、风险运营、推荐实验、审计日志、工程运维接口。
- `web`: MysticVerse 路由化前台与独立运营入口。

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

生产部署使用固定提交 SHA 与强随机密钥，详见 [生产运行手册](docs/production-runbook.md)。

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
- [OAuth2 与安全](docs/oauth2-security.md)
- [推荐系统](docs/ml-recommendation.md)
