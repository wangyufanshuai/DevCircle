# DevCircle

[![CI](https://github.com/wangyufanshuai/DevCircle/actions/workflows/ci.yml/badge.svg)](https://github.com/wangyufanshuai/DevCircle/actions/workflows/ci.yml)

DevCircle 是工程代号，前台产品名为 **MysticVerse**：一个围绕“推理馆、科幻馆、宇宙档案”的主题内容平台。v1.1 在 v1.0 生产预备工程基础上继续对标大厂内容平台复杂度，重点补齐内容治理、风控运营和推荐实验能力。

## 技术栈

- Java 21, Spring Boot 4, Spring Cloud Gateway
- MySQL 8.4, Redis, Kafka 4.1, Elasticsearch 9, MinIO
- Vue 3, Vite, TypeScript, Naive UI, Playwright
- Docker Compose, Helm, GHCR, Prometheus, Grafana, Jaeger
- JUnit 5, Testcontainers, k6

## v1.1 能力矩阵

| 能力 | 状态 |
| --- | --- |
| 主题内容库 | 120 条推理、科幻、宇宙样例作为 seed，启动导入 MySQL，seed 只作为降级兜底 |
| 内容闭环 | 发帖写 MySQL，关联标签/媒体，Outbox 事件，审核前 `PENDING` |
| 搜索 | Elasticsearch 查询优先，手动重建优先从内容服务读取 MySQL 内容 |
| 推荐 | 内容服务候选优先，Redis 热度/兴趣/曝光去重，返回 `degraded` 和 `source` |
| 推理解谜 | 线索、嫌疑人、时间线、答案关键词、积分与排行榜 |
| 认证授权 | JWT access/refresh/logout/me，refresh token 持久化，Gateway JWT 解析和角色拦截 |
| 安全审计 | 登录、刷新、退出、审核、封禁等审计表；上传 MIME、扩展名、魔数和大小校验 |
| 通知 | 通知落库，审核/互动 Kafka 消费生成站内通知，WebSocket 演示推送 |
| 部署 | Compose `infra/app/demo` profiles，Helm dev/prod values，GHCR 镜像构建 |
| 可观测 | Prometheus alerts，Grafana dashboard，Jaeger OTLP endpoint |
| 压测 | k6 脚本与报告模板，统一测试阶段补录真实数据 |
| 内容治理 | 发帖生成审核任务，包含机审结果、风险等级、SLA、复审状态和人工决策 |
| 风控运营 | 内容风险信号、用户风险画像、禁发/封禁占位拦截和管理端治理接口 |
| 推荐实验 | 多路召回来源、实验 id、分桶、推荐事件日志、实验启停管理 |
| 运营看板 | 审核积压、通过率、推荐曝光、风险用户、主题指标等管理端视图 |

## 模块

- `dc-gateway`: 统一入口、TraceId、JWT/demo token 鉴权、角色拦截、用户上下文透传。
- `dc-auth`: 登录、刷新、退出、OAuth2 mock/真实 provider 配置入口、审计日志。
- `dc-user`: 关注关系、共同关注、二跳推荐。
- `dc-content`: 主题内容、详情、相关内容、推理解谜、审核状态、Outbox。
- `dc-interaction`: 点赞、收藏、幂等写入、Redis 热度信号。
- `dc-search`: Elasticsearch 索引、Kafka 内容事件同步、重建入口。
- `dc-recommend`: 规则 fallback + 轻量线性模型、候选召回、曝光去重。
- `dc-notification`: 站内通知、Kafka 消费、WebSocket 推送。
- `dc-file`: MinIO 上传、媒体元数据、上传安全校验。
- `dc-admin`: 内容审核、审计日志、运营概览。
- `web`: MysticVerse 前端演示和管理入口。

## 本地启动

Java 需要 21：

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

只启动基础设施：

```powershell
docker compose --profile infra up -d
.\deploy\kafka\create-topics.ps1
```

构建并启动完整 demo：

```powershell
.\mvnw.cmd -B -DskipTests package
docker compose --profile demo up -d --build
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

生产或准生产部署应设置：

```bash
GATEWAY_DEMO_TOKEN_ENABLED=false
DEVCIRCLE_JWT_SECRET=<strong-random-secret>
```

## 验证命令

用户当前要求先完成短板实现，最终统一测试阶段再执行完整验证。预期验证命令如下：

```powershell
.\mvnw.cmd -B test
cd web
npm run build
npx playwright test
helm lint deploy/helm/devcircle -f deploy/helm/devcircle/values-dev.yaml
helm lint deploy/helm/devcircle -f deploy/helm/devcircle/values-prod.yaml
docker compose --profile infra config
docker compose --profile demo config
k6 run tests/k6/mysticverse-smoke.js
```

## 文档

- [技术说明](docs/technical-specification.md)
- [主题平台](docs/theme-platform.md)
- [云服务器 Docker 部署](docs/deployment-cloud-docker.md)
- [压测报告](docs/performance-report.md)
- [内容治理平台](docs/governance-platform.md)
- [推荐实验平台](docs/recommendation-experiment.md)
- [OAuth2 与安全](docs/oauth2-security.md)
- [推荐系统](docs/ml-recommendation.md)
