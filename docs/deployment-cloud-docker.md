# 云服务器 Docker 部署

本文档面向 v1.0 生产预备版，目标是在一台云服务器上用 Docker Compose 跑通 MysticVerse 全栈演示。

## 前置条件

- 2 核 8 GB 内存以上，推荐 4 核 16 GB。
- Docker 29+ 和 Docker Compose v2。
- 已开放 80/443、8080、5501、19090、13000、16686 等需要访问的端口。
- 真实生产请关闭 `GATEWAY_DEMO_TOKEN_ENABLED`，并通过环境变量注入强随机 JWT secret、数据库密码和 MinIO 密钥。

## 启动步骤

```bash
git clone https://github.com/wangyufanshuai/DevCircle.git
cd DevCircle
./mvnw -B -DskipTests package
docker compose --profile demo up -d --build
```

访问入口：

- 前端：`http://<server-ip>:5501`
- Gateway：`http://<server-ip>:8080`
- Prometheus：`http://<server-ip>:19090`
- Grafana：`http://<server-ip>:13000`
- Jaeger：`http://<server-ip>:16686`

## 回滚

如果使用 GHCR 镜像部署，把 compose 中的镜像 tag 回退到上一个 commit sha，然后执行：

```bash
docker compose --profile demo pull
docker compose --profile demo up -d
```

## 日志排查

```bash
docker compose ps
docker compose logs -f dc-gateway
docker compose logs -f dc-content
docker compose logs -f dc-recommend
```

常见问题：

- MySQL 首次启动慢：等待 healthcheck 通过后重启依赖服务。
- Elasticsearch 内存不足：提高服务器内存或调低 `ES_JAVA_OPTS`。
- 前端提示演示降级：说明对应后端接口不可用，先看 Gateway 和目标服务日志。
