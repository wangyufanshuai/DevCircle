# DevCircle

[![CI](https://github.com/wangyufanshuai/DevCircle/actions/workflows/ci.yml/badge.svg)](https://github.com/wangyufanshuai/DevCircle/actions/workflows/ci.yml)

DevCircle 是工程代号，前台产品名为 **MysticVerse**：一个围绕“推理馆、科幻馆、宇宙档案”的主题内容平台。v1.2 在 v1.1 内容治理和推荐实验基础上继续做工程化硬化，重点补齐配置规范、启动脚本、服务健康、显式降级、Outbox 运维和前端 API 工程化。

## 技术栈

- Java 21, Spring Boot 4, Spring Cloud Gateway
- MySQL 8.4, Redis, Kafka 4.1, Elasticsearch 9, MinIO
- Vue 3, Vite, TypeScript, Naive UI, Playwright
- Docker Compose, Helm, GHCR, Prometheus, Grafana, Jaeger
- JUnit 5, Testcontainers, k6

## v1.2 能力矩阵

| 能力 | 状态 |
| --- | --- |
| 主题内容库 | 120 条推理、科幻、宇宙样例作为 seed，启动导入 MySQL，seed 只作为显式降级兜底 |
| 内容闭环 | 发帖写 MySQL、标签、媒体、Outbox，审核前状态为 `PENDING` |
| 内容治理 | 发帖生成审核任务，包含机审结果、风险等级、SLA、复审状态和人工决策 |
| 风控运营 | 内容风险信号、用户风险画像、禁发/封禁占位拦截和管理端治理接口 |
| 推荐实验 | 多路召回、实验 id、分桶、推荐事件日志、实验启停管理 |
| 工程启动 | `.env.example`、Windows dev scripts、Compose `infra/app/demo` profiles |
| 运行韧性 | Graceful shutdown、Actuator probes、Gateway 请求日志、显式降级来源 |
| 运维接口 | 系统健康、Outbox backlog、Outbox replay、推荐降级概览 |
| 前端工程化 | API base URL、token localStorage、统一 request wrapper、全局降级状态条 |
| 可观测 | Prometheus alerts、Grafana dashboard、Jaeger OTLP endpoint |

## 模块

- `dc-gateway`: 统一入口、TraceId、JWT/demo token 鉴权、角色拦截、用户上下文透传、请求日志。
- `dc-auth`: 登录、刷新、退出、OAuth2 mock/真实 provider 配置入口、审计日志。
- `dc-user`: 关注关系、共同关注、二跳推荐。
- `dc-content`: 主题内容、详情、相关内容、推理解谜、审核状态、Outbox。
- `dc-interaction`: 点赞、收藏、幂等写入、Redis 热度信号。
- `dc-search`: Elasticsearch 索引、Kafka 内容事件同步、重建入口。
- `dc-recommend`: 规则 fallback + 轻量线性模型、候选召回、曝光去重、推荐实验。
- `dc-notification`: 站内通知、Kafka 消费、WebSocket 推送。
- `dc-file`: MinIO 上传、媒体元数据、上传安全校验。
- `dc-admin`: 内容治理、风险运营、推荐实验、审计日志、工程运维接口。
- `web`: MysticVerse 前台演示和管理入口。

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

## Demo 鉴权

开发演示默认允许 demo token：

```http
Authorization: Bearer demo-access-token-demo
Authorization: Bearer demo-access-token-admin
```

准生产或生产部署应关闭 demo token：

```bash
GATEWAY_DEMO_TOKEN_ENABLED=false
DEVCIRCLE_JWT_SECRET=<strong-random-secret>
```

前端通过这些变量配置 API 和演示模式：

```bash
VITE_API_BASE_URL=http://localhost:8080
VITE_DEMO_TOKEN=Bearer demo-access-token-demo
VITE_DEMO_ADMIN_TOKEN=Bearer demo-access-token-admin
VITE_ENABLE_DEMO_FALLBACK=true
```

## 本轮验证边界

当前开发阶段只允许编译级验证：

```powershell
.\mvnw.cmd -B -DskipTests compile
cd web
npm run build
```

完整测试、Testcontainers、Playwright、k6、Helm lint 和 CI 验证留到统一测试阶段。

## 文档

- [工程化就绪说明](docs/engineering-readiness.md)
- [技术说明](docs/technical-specification.md)
- [主题平台](docs/theme-platform.md)
- [内容治理平台](docs/governance-platform.md)
- [推荐实验平台](docs/recommendation-experiment.md)
- [云服务器 Docker 部署](docs/deployment-cloud-docker.md)
- [压测报告](docs/performance-report.md)
- [OAuth2 与安全](docs/oauth2-security.md)
- [推荐系统](docs/ml-recommendation.md)
