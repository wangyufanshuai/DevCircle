# 云服务器 Docker 部署

本文档面向生产预备版。开发演示和公网生产使用不同 Compose 文件，生产路径不启用 seed/mock 降级。

## 前置条件

- 2 核 8 GB 内存以上，推荐 4 核 16 GB。
- Docker 29+ 和 Docker Compose v2。
- 生产公网只开放 80/443；Gateway 的 8080 固定绑定宿主机 `127.0.0.1`，不接受远程连接。
- 所有环境都使用真实认证；生产必须通过环境变量注入强随机 JWT secret、数据库密码和 MinIO 密钥。

## 启动步骤

```bash
git clone https://github.com/wangyufanshuai/DevCircle.git
cd DevCircle
./mvnw -B -DskipTests package
docker compose --env-file .env.production -f docker-compose.prod.yml pull
docker compose --env-file .env.production -f docker-compose.prod.yml up -d
```

访问入口：

- 网站：`https://<domain>`
- Gateway 本机诊断：`http://127.0.0.1:8080`

本地演示仍使用 `docker-compose.yml` 的 `demo` profile；它会暴露开发端口，不得直接作为公网生产配置。

## 回滚

如果使用 GHCR 镜像部署，把 `.env.production` 中的 `IMAGE_TAG` 回退到上一个 commit SHA，然后执行：

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml pull
docker compose --env-file .env.production -f docker-compose.prod.yml up -d
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
- 前端提示依赖暂时不可用：先看 Gateway 和目标服务日志；不要在生产开启读取降级。
