# DevCircle

DevCircle 是一个面向程序员的开源技术社区微服务项目。它围绕内容社区的核心链路设计：发帖、评论、点赞、收藏、搜索、推荐、通知、文件上传和管理审核。

当前版本定位为 v0.4：在 v0.3 推荐/信息流骨架的基础上，补齐可靠闭环测试和中文开源观感。项目重点不是堆砌 CRUD，而是展示 Java 科班项目常见的工程能力：服务拆分、数据库建模、Outbox 可靠事件、缓存信号、搜索索引、可解释推荐、前后端联调、容器化和 CI。

## 技术栈

- Java 21, Spring Boot 4.0.x, Spring Cloud 2025.1.x, Spring Cloud Alibaba 2025.1.x
- MySQL 8.4, Redis, Kafka 4.1.x, Elasticsearch 9.x, MinIO
- Vue 3, Vite, TypeScript, Naive UI, Playwright
- Docker Compose, Helm, Prometheus, Grafana, GitHub Actions
- JUnit 5, Mockito, Testcontainers

## 模块

- `dc-gateway`: 统一入口、路由、TraceId、demo 鉴权和用户上下文透传。
- `dc-auth`: 登录、刷新令牌、OAuth2 provider 入口。
- `dc-user`: 用户资料和关注关系。
- `dc-content`: 帖子、标签、审核状态、JDBC 写入和 Outbox 事件。
- `dc-comment`: 评论与回复。
- `dc-interaction`: 点赞、收藏、浏览等高频互动，使用 MySQL 唯一索引和 Redis 信号。
- `dc-search`: Elasticsearch 索引、重建入口和 Kafka 内容事件同步。
- `dc-recommend`: 可解释推荐流、热度排序和曝光去重。
- `dc-notification`: 站内信与 WebSocket 推送。
- `dc-file`: MinIO/S3 兼容上传入口。
- `dc-admin`: 审核、封禁、标签和运营指标。
- `dc-common`: 统一响应、错误码、事件模型、分页模型和共享视图。
- `web`: Vue 用户端和管理端演示界面。

## 本地启动

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

Windows Docker Desktop 29 如遇 Testcontainers named pipe 探测失败，先启动本地 Docker socket 代理：

```powershell
.\scripts\dev\testcontainers-docker-proxy.ps1
$env:DOCKER_HOST='tcp://127.0.0.1:2375'
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

Gateway 会向下游透传：

- `X-User-Id`
- `X-Roles`
- `X-Trace-Id`

## v0.4 可靠闭环

v0.4 的核心演示链路：

1. 用户发布文章，`dc-content` 写入 `post/post_content/tag/post_tag`。
2. 同一个本地事务写入 `outbox_event`，避免帖子成功但事件丢失。
3. Outbox publisher 将 `post.created` 发送到 Kafka，并标记为 `PUBLISHED`。
4. `dc-search` 消费内容事件并维护 Elasticsearch 文档。
5. `dc-interaction` 用 MySQL 唯一索引保证点赞/收藏幂等，并将热度写入 Redis。
6. `dc-recommend` 根据兴趣标签、热度、新鲜度、关注作者和曝光记录生成 Feed。
7. 前端展示推荐原因、热度、状态、互动状态，并在发布后提示“已进入审核和事件队列”。

详细设计见 [docs/feed-engine.md](docs/feed-engine.md)，集成测试说明见 [docs/integration-testing.md](docs/integration-testing.md)。

## API 文档

Web 服务接入 `springdoc-openapi`。服务启动后可访问：

- Swagger UI: `http://localhost:<service-port>/swagger-ui.html`
- OpenAPI JSON: `http://localhost:<service-port>/v3/api-docs`

REST Client 示例见 [docs/api-examples.http](docs/api-examples.http)。

## 验证命令

```powershell
.\mvnw.cmd -B test
cd web
npm run build
npx playwright test
```

可选交付检查：

```powershell
helm lint deploy/helm/devcircle
docker compose config
```

CI 会执行后端测试、前端构建、Playwright 冒烟测试、Helm lint 和 Compose 配置校验。

## 常见问题

- 如果后端提示不支持 `--release 21`，说明当前 `java` 不是 JDK 21。
- Testcontainers 需要本机 Docker 可用；v0.5 起核心容器测试不会静默跳过。
- 如果前端 API 请求失败，界面会 fallback 到本地 mock 数据，方便只查看 UI。
- 本项目默认使用本地事务 + Outbox + Kafka + 消费端幂等，不引入 XA 或 Seata。
