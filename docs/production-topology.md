# 默认生产拓扑

本页描述 `docker-compose.prod.yml` 的默认服务，不包含 `ops` profile、Notification cutover 或 legacy rollback overlay。依据是 Compose 渲染结果以及 `dc-gateway/src/main/resources/application-aggregate.yml`；更新部署配置时须同步核对本页。

默认 **18 个服务、8 个 JVM**，声明内存上限合计 **11.625 GiB**。内存上限不是实际常驻占用，也不包含宿主机、Docker、IDE 等开销。`minio-maintenance` 仅在 `ops` profile 按需启用，不计入默认数量。

## 默认服务清单

“依赖”列是 Compose 的 `depends_on` 启动约束，不是完整业务调用图。端口以容器内端口为准，除最后一列外均不发布到宿主机。

| 服务 | 职责 / 内部端口 | 内存上限 MiB | Compose 启动依赖 | 宿主机入口 |
|---|---|---:|---|---|
| mysql | 权威数据库 / 3306 | 2048 | 无 | 无 |
| redis | 缓存、限流等 / 6379 | 384 | 无 | 无 |
| kafka | 事件总线 / 9092 | 1536 | 无 | 无 |
| elasticsearch | 搜索索引 / 9200 | 2048 | 无 | 无 |
| minio | 对象存储 / 9000 | 768 | 无 | 无 |
| prometheus | 指标 / 9090 | 256 | alertmanager | 无 |
| alertmanager | 告警路由 / 9093 | 128 | 无 | 无 |
| jaeger | 链路 / 16686、4318 | 256 | 无 | 无 |
| dc-identity | Auth、User、Interaction、个人面板 / 8201 | 512 | mysql、redis、kafka | 无 |
| dc-content-aggregate | Content、File / 8202 | 512 | mysql、kafka、minio、dc-identity | 无 |
| dc-discovery | Search / 8203 | 512 | mysql、elasticsearch、kafka、dc-content-aggregate | 无 |
| dc-comment | Comment / 8104 | 512 | mysql、kafka | 无 |
| dc-recommend | Recommend / 8107 | 512 | redis、kafka、dc-content-aggregate | 无 |
| dc-notification | Notification / 8108 | 512 | mysql、kafka | 无 |
| dc-admin | 审核与运营 / 8110 | 512 | mysql、kafka、dc-identity、dc-content-aggregate | 无 |
| dc-gateway | 鉴权及路由 / 8080 | 512 | 上述七个业务进程 | 127.0.0.1:8080 |
| web-static | Nginx 静态前端及反向代理 / 80 | 128 | dc-gateway、minio | 无 |
| web | Caddy HTTPS 入口 / 80、443 | 256 | web-static | 80/tcp、443/tcp、443/udp |

## 默认运行职责

- Identity 拥有 Auth、User、Interaction 的业务 runtime、相应迁移与事件处理；各领域 schema、历史和 producer 保持独立。
- Content Aggregate 拥有 Content 与 File，保留独立 Flyway history。`COMMENT_RUNTIME_ENABLED=false`，评论仍由独立 Comment 负责。
- Discovery 承载 Search；Recommend 保持独立。
- 独立 Notification 拥有 API、Flyway、Kafka consumer、topic admin、WebSocket。Identity 对应五项开关默认全部关闭。前端默认使用 socket-disabled 的 `web-notification-standalone` 镜像。
- Comment、Recommend、Notification、Admin 的迁移与消费者归各自进程；Gateway 和两个 Web 服务不执行领域数据库迁移。

## Gateway 默认路由

| 路径 | 上游 |
|---|---|
| `/api/auth/**`、`/api/me/**`、`/api/users/**` | dc-identity:8201 |
| `/api/posts/*/like`、`favorite`、`interaction-state` | dc-identity:8201 |
| `/api/notifications/**`、`/ws/notifications` | dc-notification:8108 |
| `/api/comments/**`、`/api/posts/*/comments/**` | dc-comment:8104 |
| `/api/admin/**` | dc-admin:8110 |
| 其余 `/api/posts/**`、`/api/deduction/**`、`/api/content/**`、`/api/files/**` | dc-content-aggregate:8202 |
| `/api/search/**` | dc-discovery:8203 |
| `/api/recommend/**` | dc-recommend:8107 |

更具体的互动和评论路由排在通用内容路由前。公开 API 经过 Caddy、Nginx、Gateway；领域服务不直接发布公网端口。

## 可选切换与核验

Notification 迁入 Identity 必须通过切流证据门禁并显式应用 `deploy/compose/docker-compose.notification-identity-cutover.yml`；该模式为 17 个服务、11.125 GiB，并切换五项所有权及前端镜像。legacy rollback 恢复独立 Notification。默认部署不能被描述为已经完成切流。

使用示例配置静态核验（不要求 Docker Engine 启动）：

```bash
bash scripts/ops/production-compose.sh .env.production.example -f docker-compose.prod.yml config --quiet
python scripts/ops/assert-production-resource-budgets.py
```

上述检查同时约束资源、端口、默认/切流/回退模式；它们不证明服务成功启动。历史运行证据见 [2026-08-15 记录](evidence/2026-08-15-aggregate-docker-recovery.md)，当前提交验证见 [CI 说明](ci-verification.md)。
