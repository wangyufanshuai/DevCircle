# DevCircle

[![CI](https://github.com/wangyufanshuai/DevCircle/actions/workflows/ci.yml/badge.svg)](https://github.com/wangyufanshuai/DevCircle/actions/workflows/ci.yml)

DevCircle 是工程代号，v0.8 前台产品名为 **MysticVerse**：一个围绕“推理、科幻、宇宙”的主题内容平台。它不再是普通社交 Feed，而是把案件解谜、科幻创作、世界观设定、宇宙档案、主题搜索、推荐排序、审核和通知组织成一条可运行的开源演示链路。

项目定位是“生产预备级工程样板”：可以本地运行、自动测试、演示核心链路，但不承诺直接承载真实大规模生产流量。

## 技术栈

- Java 21, Spring Boot 4.0.x, Spring Cloud 2025.1.x
- MySQL 8.4, Redis, Kafka 4.1.x, Elasticsearch 9.x, MinIO
- Vue 3, Vite, TypeScript, Naive UI, Playwright
- Docker Compose, Helm, Prometheus, Grafana, GitHub Actions
- JUnit 5, Mockito, Testcontainers

## v0.8 能力矩阵

| 能力 | 当前状态 |
| --- | --- |
| 三主题内容 | `DEDUCTION`、`SCI_FI`、`COSMOS` 三类主题，覆盖案件档案、逻辑谜题、科幻短篇、世界观设定、宇宙档案、观测日志 |
| 推理解谜 | `POST /api/deduction/cases/{id}/answers` 支持提交答案、正确性反馈、积分和排行榜，重复提交不重复加分 |
| 主题推荐 | `GET /api/recommend/feed?scene=deduction|sci_fi|cosmos|mixed`，融合主题偏好、热度、新鲜度、曝光和解谜状态 |
| 主题搜索 | 搜索接口支持 `theme`、`contentKind`、`difficulty` 筛选，fallback 数据也按主题返回 |
| 内容发布 | `POST /api/posts` 支持主题、内容类型、难度、线索、隐藏答案和媒体关联，写入 Outbox |
| 前端体验 | MysticVerse 首页、推理馆、科幻馆、宇宙档案、创作台、解谜提交、排行榜、审核台、通知 |
| 可靠链路 | MySQL、Redis、Kafka、Elasticsearch、MinIO Testcontainers 进入默认 Maven 测试 |

## 模块

- `dc-gateway`: 统一入口、TraceId、JWT/demo token 鉴权和用户上下文透传。
- `dc-auth`: 密码登录、JWT 刷新、GitHub/Gitee OAuth2 mock 登录。
- `dc-user`: 关注关系、粉丝列表、共同关注和二跳推荐。
- `dc-content`: 主题帖子、标签、媒体关联、推理解谜、审核状态、JDBC 写入和 Outbox 事件。
- `dc-interaction`: 点赞、收藏、浏览等高频互动，使用 MySQL 唯一索引和 Redis 热度信号。
- `dc-search`: Elasticsearch 索引、重建入口和 Kafka 内容事件同步。
- `dc-recommend`: 规则 fallback + 主题线性模型推荐流、热度排序、曝光去重。
- `dc-file`: MinIO/S3 兼容上传和 `media_asset` 元数据。
- `web`: Vue 用户端和管理端演示界面。

## 本地启动

后端需要 Java 21。Windows 如默认 Java 不是 21，先设置：

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

启动基础设施：

```powershell
docker compose up -d mysql redis kafka elasticsearch minio prometheus grafana
.\deploy\kafka\create-topics.ps1
```

运行后端测试。本机 Docker Desktop 如 `desktop-linux` context 的 pipe 不可用，使用 `default` engine：

```powershell
$env:DOCKER_HOST='npipe:////./pipe/docker_engine'
.\mvnw.cmd -B test
```

启动前端：

```powershell
cd web
npm install
npm run dev
```

## Demo 鉴权

普通接口：

```http
Authorization: Bearer demo-access-token-demo
```

管理接口：

```http
Authorization: Bearer demo-access-token-admin
```

## 验证命令

```powershell
$env:DOCKER_HOST='npipe:////./pipe/docker_engine'
.\mvnw.cmd -B test
cd web
npm run build
npx playwright test
helm lint deploy/helm/devcircle
docker compose config
```

## 文档

- [主题平台设计](docs/theme-platform.md)
- [架构设计](docs/architecture.md)
- [推荐引擎](docs/feed-engine.md)
- [媒体平台](docs/media-platform.md)
- [轻量 ML 推荐](docs/ml-recommendation.md)
- [OAuth2 安全](docs/oauth2-security.md)
- [社交图谱](docs/social-graph.md)
- [集成测试](docs/integration-testing.md)
- [API 示例](docs/api-examples.http)
