# v1.2 工程化就绪说明

本文是 DevCircle / MysticVerse 的工程化 how-to 文档，目标是让开发者能快速检查环境、启动依赖、定位降级原因，并理解当前生产预备版的运行边界。

## 环境配置

1. 复制配置模板：

```powershell
Copy-Item .env.example .env
```

2. 按需修改 `.env`：

- `MYSQL_*`: MySQL 连接信息。
- `REDIS_*`: Redis 连接信息。
- `KAFKA_BOOTSTRAP_SERVERS`: Kafka 地址。
- `ELASTICSEARCH_URIS`: Elasticsearch 地址。
- `S3_*`: MinIO / S3 兼容存储配置。
- `DEVCIRCLE_JWT_SECRET`: JWT 签名密钥。
- `GATEWAY_DEMO_TOKEN_ENABLED`: 是否启用 demo token。
- `VITE_API_BASE_URL`: 前端请求 Gateway 的地址。
- `VITE_ENABLE_DEMO_FALLBACK`: 前端是否允许演示降级。

真实 secret 不应提交到仓库。

## 开发脚本

| 脚本 | 用途 |
| --- | --- |
| `scripts/dev/doctor.ps1` | 检查 Java、Docker、Git、Node、npm、Compose 配置 |
| `scripts/dev/compile-build.ps1` | 后端 `compile -DskipTests` + 前端 build |
| `scripts/dev/up-infra.ps1` | 启动 MySQL、Redis、Kafka、ES、MinIO、Prometheus、Grafana、Jaeger |
| `scripts/dev/up-demo.ps1` | 启动完整 demo，可加 `-Build` 构建镜像 |
| `scripts/dev/down.ps1` | 停止本地容器，可加 `-Volumes` 清理数据卷 |

## 服务健康

后端服务统一启用：

- Graceful shutdown。
- Actuator `health/info/prometheus`。
- Kubernetes probes 风格 health endpoint。
- Gateway 请求日志：traceId、userId、method、path、status、duration、route。

管理端提供工程状态接口：

- `GET /api/admin/ops/health`
- `GET /api/admin/ops/outbox?status=&topic=&page=&size=`
- `POST /api/admin/ops/outbox/replay?topic=&batchSize=`
- `GET /api/admin/ops/degradation`

## 显式降级

v1.2 保留演示降级，但不静默隐藏错误：

- 生产读写路径不返回 seed 或 mock 数据；依赖不可用时返回统一错误码和 `traceId`。
- 页面只展示用户可理解的空状态和重试入口。
- 管理端工程状态展示真实 Outbox backlog、推荐事件和依赖健康。

常见来源：

- `DB`: 真实数据库路径。
- `ES`: Elasticsearch 搜索路径。
- `DB` / `ES`: 真实持久化与索引路径。

## 常见故障

- Docker 不可用：运行 `scripts/dev/doctor.ps1`，确认 Docker Desktop 已启动。
- Kafka topic 缺失：运行 `deploy/kafka/create-topics.ps1`。
- 前端一直降级：检查 `VITE_API_BASE_URL` 是否指向 Gateway。
- 管理端 403：确认真实 JWT 中包含 `ADMIN`。
- Outbox 积压：查看 `/api/admin/ops/outbox`，必要时调用 replay 接口。
