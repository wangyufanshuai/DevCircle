# Content/File-in-Content-Aggregate 非容器证据（2026-08-10）

## 证据身份

- Commit: `86fd1fa3db17665876d9c2d4b18ada3a938a42f1`
- Branch: `overhaul/mysticverse-production`
- Host: Windows 11，Java 21
- 范围：Content 与 File 运行时收口、双 Flyway 配置、详情组合、私有媒体访问、生产/回滚静态拓扑

## 执行结果

后端非容器回归：

```powershell
.\mvnw.cmd -B clean verify -Pcoverage `
  "-Dtest=!*IntegrationTest,!DockerPreflightTest,!KafkaContainerSmokeTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

- Reactor: 19/19 success（根项目 + 18 个子模块）
- Surefire: 109 个 suites，474 tests，0 failures，0 errors，0 skipped
- JaCoCo: 17 reports，line 81.23%，branch 74.13%
- 关键状态门禁：14 个类全部达到 branch 85% 以上

Content Aggregate 定向非容器测试：

```powershell
.\mvnw.cmd -B -pl dc-content-deployment -am test `
  "-Dtest=ContentRuntimeContextTest,ContentRuntimeFlywayContractTest,ContentPostDetailComposerTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

- 6 tests，0 failures/errors/skipped
- Spring context 中只有一个 `PostDetailComposer` 和一个 `ReliableOutboxWorker`
- Content/File 路由由同一 Servlet runtime 提供
- 作者、关注和互动依赖失败返回 `DEPENDENCY_UNAVAILABLE`，不构造占位成功数据

公开媒体回归：

```powershell
.\mvnw.cmd -B -pl dc-content -am test `
  "-Dtest=PostRepositoryConnectionPoolTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

- 列表与详情只返回 `APPROVED + COMPLETED` 媒体元数据
- 存储在数据库中的对象 URL 不进入公开 DTO
- 浏览器必须通过 `/api/files/access` 获得短期读取 URL

前端：

```powershell
cd web
npm run build
npm run test:e2e
```

- Vite/Vue TypeScript 生产构建成功
- Playwright 120/120 通过，覆盖 Chromium、laptop、tablet、mobile
- 私有 MinIO URL 被短期访问 URL 替换；签名失败时只显示可理解错误和占位内容

静态交付门禁：

```powershell
python scripts/ops/assert-production-resource-budgets.py
docker compose --env-file .env.production.example -f docker-compose.prod.yml config --quiet
docker compose --env-file .env.production.example -f docker-compose.prod.yml `
  -f deploy/compose/docker-compose.legacy-content-rollback.yml config --quiet
```

- Production Compose: 20 services，12.62 GiB memory ceiling，2 host-published services
- 正常生产不运行独立 `dc-content` 或 `dc-file`
- legacy Content rollback overlay 静态解析通过，无依赖环
- 生产 S3 presign endpoint 与公开 origin 绑定，内部 MinIO endpoint 不作为浏览器 URL

## 架构决定

- `dc-content-deployment` 从反向代理门面改为可执行 Servlet 部署，进程内承载普通 `dc-content`、`dc-file` 和 `dc-messaging-support` 依赖制品。
- Content 与 File 共用 `dc_content` datasource，但分别保留 `flyway_schema_history` 与 `flyway_schema_history_file`。
- 聚合进程的 Spring application name 为 `dc-content-aggregate`，逻辑 Outbox source 保持 `dc-content`，避免监控和恢复工具错误识别生产者。
- 独立 Content/File `-exec.jar` 只用于开发兼容和 legacy rollback；正常生产镜像为 `dc-content-aggregate`。

## 未完成证据

`ContentRuntimeFlywayIntegrationTest` 已实现并通过 `testCompile`，覆盖：

1. fresh MySQL database 同时迁移 Content 与 File；
2. 已存在 Content V22 和 File V2 history/data 后升级；
3. 重复 migrate 与 aggregate restart 幂等。

Docker Engine 在本机持续无响应，因此该 Testcontainers 测试没有实际启动 MySQL 8.4，不能记为通过。本证据也不证明：

- 当前 SHA 的 Testcontainers 或真实 Docker Compose 启动；
- 真实 Auth/Content/Review/Community E2E；
- 目标 Linux 50 并发 P95、资源曲线或30天 SLO；
- MySQL/MinIO/ES/Kafka 完整恢复和正式 RTO/RPO；
- Helm 集群部署；
- Registry-backed Cosign、SBOM 或 provenance 验签。

上述项目必须在目标 Linux/Docker 环境对同一提交或其后无行为变化的发布提交重新执行。
