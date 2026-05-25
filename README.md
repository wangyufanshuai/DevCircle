# DevCircle

DevCircle 是一个面向程序员的开源技术社区微服务项目。它不是简单 CRUD，而是围绕内容社区常见链路设计：发帖、评论、点赞收藏、关注、搜索、推荐、通知、文件上传和管理审核。

当前版本定位为 v0.2 工程骨架：能编译、能测试、能演示，保留继续接入真实 Repository、Kafka 消费者、Elasticsearch 索引和 MinIO 上传的清晰扩展点。

## 技术栈

- Java 21, Spring Boot 4.0.x, Spring Cloud 2025.1.x, Spring Cloud Alibaba 2025.1.x
- MySQL 8.4, Redis, Kafka 4.1.x, Elasticsearch 9.x, MinIO
- Vue 3, Vite, TypeScript, Naive UI, Playwright
- Docker Compose, Helm, Prometheus, Grafana, GitHub Actions

## 项目结构

- `dc-gateway`: 统一入口、路由、TraceId、demo 鉴权。
- `dc-auth`: 登录、刷新令牌、OAuth2 provider 入口。
- `dc-user`: 用户资料与关注。
- `dc-content`: 帖子、标签、审核状态。
- `dc-comment`: 评论与回复。
- `dc-interaction`: 点赞、收藏、幂等互动。
- `dc-search`: 搜索接口与索引同步扩展点。
- `dc-recommend`: 可解释推荐流。
- `dc-notification`: 站内信与 WebSocket 推送。
- `dc-file`: MinIO/S3 兼容上传入口。
- `dc-admin`: 审核、封禁、标签和运营指标。
- `dc-common`: 统一响应、错误码、事件模型、共享视图模型。
- `web`: Vue 用户端 + 管理端演示界面。

## 本地开发

后端需要 Java 21。Windows 上如果默认 Java 不是 21，可以临时设置：

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

启动基础设施：

```powershell
docker compose up -d mysql redis kafka elasticsearch minio prometheus grafana
```

创建 Kafka topics：

```powershell
.\deploy\kafka\create-topics.ps1
```

运行后端测试：

```powershell
.\mvnw.cmd test
```

运行某个服务：

```powershell
.\mvnw.cmd -pl dc-content spring-boot:run
```

启动前端：

```powershell
cd web
npm install
npm run dev
```

访问 `http://localhost:5173`。

## Demo 鉴权

Gateway 允许 `/api/auth/**` 匿名访问，其它接口需要请求头：

```http
Authorization: Bearer demo-access-token-demo
```

管理端接口需要：

```http
Authorization: Bearer demo-access-token-admin
```

## API 文档

各 Web 服务接入 `springdoc-openapi`。服务启动后可访问：

- Swagger UI: `http://localhost:<service-port>/swagger-ui.html`
- OpenAPI JSON: `http://localhost:<service-port>/v3/api-docs`

REST Client 示例见 [docs/api-examples.http](docs/api-examples.http)。

## 架构约定

跨服务一致性默认使用本地事务 + `outbox_event` + Kafka + `consumer_dedup`，不使用 XA 和 Seata。详细说明见 [docs/architecture.md](docs/architecture.md)。

核心事件 topic：

- `post.created`
- `post.updated`
- `post.deleted`
- `comment.created`
- `interaction.changed`
- `notification.created`
- `audit.completed`

## 验证

```powershell
.\mvnw.cmd test
cd web
npm run build
npx playwright test
```

CI 会执行后端测试、前端构建、Playwright 冒烟测试和 Helm lint。

## v0.3 Feed Engine

v0.3 增加真实推荐流主线：

- `dc-content` 使用 JDBC 写入帖子、标签和 Outbox 事件。
- `dc-interaction` 使用 MySQL 唯一索引保证互动幂等，并写入 Redis 热度。
- `dc-recommend` 提供游标 Feed、可解释排序和曝光去重。
- `dc-search` 增加 Elasticsearch 文档、手动重建索引和 Kafka 消费者。
- 前端首页支持刷新推荐、加载更多、发布文章、点赞、收藏和搜索。

详细设计见 [docs/feed-engine.md](docs/feed-engine.md)。

## 常见问题

- 如果后端提示不支持 `--release 21`，说明当前 `java` 不是 JDK 21。
- 如果前端 API 请求失败，界面会自动 fallback 到本地 mock 数据，方便只看 UI。
- 本地未安装 Helm 不影响开发；Helm lint 可在 GitHub Actions 中验证。
