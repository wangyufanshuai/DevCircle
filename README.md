# DevCircle

[![CI](https://github.com/wangyufanshuai/DevCircle/actions/workflows/ci.yml/badge.svg)](https://github.com/wangyufanshuai/DevCircle/actions/workflows/ci.yml)

DevCircle 是一个面向程序员的开源社交内容平台。当前 v0.7 版本在可靠事件闭环基础上，补齐了文本、图片、视频三类内容，本地轻量 ML 推荐，MySQL + Redis 二跳社交图谱，以及 GitHub/Gitee OAuth2 演示登录。

项目定位是“生产预备级工程样板”：可以本地运行、自动测试、演示核心链路，但不承诺直接承载真实大规模生产流量。

## 技术栈

- Java 21, Spring Boot 4.0.x, Spring Cloud 2025.1.x, Spring Cloud Alibaba 2025.1.x
- MySQL 8.4, Redis, Kafka 4.1.x, Elasticsearch 9.x, MinIO
- Vue 3, Vite, TypeScript, Naive UI, Playwright
- Docker Compose, Helm, Prometheus, Grafana, GitHub Actions
- JUnit 5, Mockito, Testcontainers

## v0.7 能力矩阵

| 能力 | 当前状态 |
| --- | --- |
| 多媒体内容 | `TEXT`、`IMAGE`、`VIDEO` 帖子模型；图片瀑布流；视频 Feed；媒体元数据落库 |
| 文件上传 | `dc-file` 真实接入 MinIO/S3，校验 png/jpeg/webp/mp4，上传后进入 `PENDING_REVIEW` |
| 可靠事件 | 发帖同事务写入帖子、正文、标签、媒体关联和 Outbox；Kafka 成功后才标记 `PUBLISHED` |
| 搜索同步 | Elasticsearch 索引模型、重建入口和 Kafka 内容事件处理入口 |
| 轻量 ML 推荐 | Java 本地线性模型，融合标签、作者、媒体类型、热度、时效、关注关系、共同关注、曝光记录 |
| 社交图谱 | `dc-user` 使用 MySQL + Redis 管理关注、粉丝、共同关注和二跳用户推荐 |
| OAuth2/JWT | GitHub/Gitee OAuth2 mock 回调、JWT 签发、刷新令牌、Gateway JWT 校验和用户上下文透传 |
| 前端演示 | 推荐/图片/视频切换、上传媒体、发布内容、搜索、点赞、收藏、关注、OAuth demo 登录、审核和通知 |
| 容器测试 | MySQL、Redis、Kafka、Elasticsearch、MinIO Testcontainers 进入默认 Maven 测试 |

## 模块

- `dc-gateway`: 统一入口、路由、TraceId、JWT/demo token 鉴权和 `X-User-Id`、`X-Roles` 透传。
- `dc-auth`: 密码登录、JWT 刷新、GitHub/Gitee OAuth2 mock 登录。
- `dc-user`: 用户资料、关注关系、粉丝列表、共同关注和二跳推荐。
- `dc-content`: 帖子、标签、媒体关联、审核状态、JDBC 写入和 Outbox 事件。
- `dc-comment`: 评论与回复。
- `dc-interaction`: 点赞、收藏、浏览等高频互动，使用 MySQL 唯一索引和 Redis 热度信号。
- `dc-search`: Elasticsearch 索引、重建入口和 Kafka 内容事件同步。
- `dc-recommend`: 规则 fallback + 本地轻量模型推荐流、热度排序、曝光去重。
- `dc-notification`: 站内信与 WebSocket 推送。
- `dc-file`: MinIO/S3 兼容上传和 `media_asset` 元数据。
- `dc-admin`: 审核、封禁、标签和运营指标。
- `dc-common`: 统一响应、错误码、事件模型、分页模型和共享视图。
- `web`: Vue 用户端和管理端演示界面。

## 本地启动

后端需要 Java 21。Windows 如默认 Java 不是 21，可临时设置：

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

启动基础设施：

```powershell
docker compose up -d mysql redis kafka elasticsearch minio prometheus grafana
.\deploy\kafka\create-topics.ps1
```

运行后端测试：

```powershell
.\mvnw.cmd -B test
```

启动单个服务示例：

```powershell
.\mvnw.cmd -pl dc-content spring-boot:run
```

启动前端：

```powershell
cd web
npm install
npm run dev
```

Playwright 使用 `http://127.0.0.1:5500` 作为测试端口，避免本机常见 5173 占用。

## Demo 鉴权

普通接口：

```http
Authorization: Bearer demo-access-token-demo
```

管理接口：

```http
Authorization: Bearer demo-access-token-admin
```

真实 JWT 可通过 `POST /api/auth/login` 或 OAuth2 mock callback 获取。Gateway 会向下游透传：

- `X-User-Id`
- `X-Roles`
- `X-Trace-Id`

## 文档

- [架构设计](docs/architecture.md)
- [推荐引擎](docs/feed-engine.md)
- [媒体平台](docs/media-platform.md)
- [轻量 ML 推荐](docs/ml-recommendation.md)
- [OAuth2 安全](docs/oauth2-security.md)
- [社交图谱](docs/social-graph.md)
- [集成测试](docs/integration-testing.md)
- [API 示例](docs/api-examples.http)

## 验证命令

```powershell
.\mvnw.cmd -B test
cd web
npm run build
npx playwright test
helm lint deploy/helm/devcircle
docker compose config
```

CI 会执行后端测试、前端构建、Playwright、Helm lint、Compose config，并上传 Surefire 报告。

## 常见问题

- 如果后端提示不支持 `--release 21`，说明当前 `java` 不是 JDK 21。
- Testcontainers 需要本机 Docker 可用；v0.7 核心容器测试不会静默跳过。
- 如果前端 API 请求失败，界面会 fallback 到本地 mock 数据，方便只查看 UI。
- 项目默认使用本地事务 + Outbox + Kafka + 消费端幂等，不引入 XA、Seata 或图数据库。
