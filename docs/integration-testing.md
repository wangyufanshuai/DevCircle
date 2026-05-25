# 集成测试说明

DevCircle v0.4 使用 Testcontainers 验证核心依赖闭环。测试会在本地 Docker 中启动临时 MySQL、Redis、Kafka 和 Elasticsearch 容器，执行结束后自动清理。

## 前置条件

- 已安装 Docker Desktop 或兼容 Docker Engine。
- Docker daemon 正在运行。
- 本机能拉取测试镜像：`mysql:8.4`、`redis:8.2`、`apache/kafka:4.1.1`、`docker.elastic.co/elasticsearch/elasticsearch:9.2.2`。

## 运行命令

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -B test
```

v0.5 起核心 Testcontainers 测试不再静默跳过。Docker 不可用时测试应失败，避免把“没有跑容器”误认为“容器链路通过”。

Windows Docker Desktop 29 在部分环境下会让旧的 Java named pipe 探测拿到空的 `/info` 响应。推荐先启动本地只绑定回环地址的 Docker socket 代理：

```powershell
.\scripts\dev\testcontainers-docker-proxy.ps1
$env:DOCKER_HOST='tcp://127.0.0.1:2375'
.\mvnw.cmd -B test
```

测试完成后可停止代理：

```powershell
.\scripts\dev\testcontainers-docker-proxy.ps1 -Stop
```

CI 环境使用默认 Docker Unix socket，不需要这个代理。

## 覆盖链路

- `dc-content`: 发帖写入帖子、正文、标签和 Outbox 事件。
- `dc-interaction`: 重复点赞/收藏不会重复写记录或重复增加计数，Redis 热度会随首次互动变化。
- `dc-search`: 内容事件可以写入 Elasticsearch 文档，Kafka/ES 容器可正常启动。
- `dc-recommend`: Redis 兴趣、热度和曝光记录会影响 Feed 排序和去重。

## 排查建议

- 拉取镜像慢：先手动执行 `docker pull` 拉取上述镜像。
- 端口冲突：Testcontainers 默认使用随机宿主机端口，通常不受本地 Compose 端口影响。
- Elasticsearch 启动慢：确认 Docker 可用内存不低于 4GB。
- Windows 路径问题：优先在仓库根目录执行 Maven 命令。
