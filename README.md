# DevCircle

[![CI](https://github.com/wangyufanshuai/DevCircle/actions/workflows/ci.yml/badge.svg)](https://github.com/wangyufanshuai/DevCircle/actions/workflows/ci.yml)

DevCircle 是工程代号，v0.9 前台产品名为 **MysticVerse**：一个围绕“推理、科幻、宇宙”的主题内容平台。它不再是普通社交 Feed，而是把案件解谜、科幻创作、世界观设定、宇宙档案、主题搜索、推荐排序、详情阅读、相关内容召回、审核和通知组织成一条可运行的开源演示链路。

项目定位是“生产预备级工程样板”：可以本地运行、自动测试、演示核心链路，但不承诺直接承载真实大规模生产流量。

## 技术栈

- Java 21, Spring Boot 4.0.x, Spring Cloud 2025.1.x
- MySQL 8.4, Redis, Kafka 4.1.x, Elasticsearch 9.x, MinIO
- Vue 3, Vite, TypeScript, Naive UI, Playwright
- Docker Compose, Helm, Prometheus, Grafana, GitHub Actions
- JUnit 5, Mockito, Testcontainers

## v0.9 能力矩阵

| 能力 | 当前状态 |
| --- | --- |
| 主题内容库 | 共享种子库内置 120 条示例内容，推理馆、科幻馆、宇宙档案各 40 条，统一供前端 mock、推荐 fallback、搜索 fallback 和详情页使用 |
| 详情体验 | `GET /api/posts/{id}` 返回线索板、嫌疑人、时间线、世界观设定、宇宙观测事实和解析摘要 |
| 相关内容 | `GET /api/posts/{id}/related?size=6` 按主题、标签和系列召回相关内容 |
| 推理解谜 | `POST /api/deduction/cases/{id}/answers` 支持答案关键词判定、积分反馈和幂等提交 |
| 主题推荐 | `GET /api/recommend/feed?scene=deduction|sci_fi|cosmos|mixed`，候选池来自 120 条主题内容库，推荐理由贴合主题 |
| 主题搜索 | 搜索接口支持 `theme`、`contentKind`、`difficulty`、关键词和标签过滤；Elasticsearch 不可用时使用主题库 fallback |
| 前端体验 | 首页、推理馆、科幻馆、宇宙档案、详情页、排行榜、创作台、审核台和移动端布局均有 Playwright 覆盖 |
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

运行后端测试。本机 Docker Desktop 如 `desktop-linux` context 的 pipe 不可用，使用 default engine：

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

- [DevCircle / MysticVerse 技术说明文档](docs/technical-specification.md)
- [主题平台设计](docs/theme-platform.md)
- [架构设计](docs/architecture.md)
- [推荐引擎](docs/feed-engine.md)
- [媒体平台](docs/media-platform.md)
- [轻量 ML 推荐](docs/ml-recommendation.md)
- [OAuth2 安全](docs/oauth2-security.md)
- [社交图谱](docs/social-graph.md)
- [集成测试](docs/integration-testing.md)
- [API 示例](docs/api-examples.http)
