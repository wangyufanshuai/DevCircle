# 集成测试说明

DevCircle v1.2 保留 Testcontainers 验证核心依赖闭环。默认 Maven 测试会启动临时 MySQL、Redis、Kafka、Elasticsearch 和 MinIO 容器，执行结束后自动清理。

## 前置条件

- 已安装 Docker Desktop 或兼容 Docker Engine。
- Docker daemon 正在运行。
- 本机能拉取测试镜像：`mysql:8.4`、`redis:8.2`、`apache/kafka:4.1.1`、`docker.elastic.co/elasticsearch/elasticsearch:9.2.2`、`minio/minio`。

## 运行命令

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.7.6-hotspot'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -B test
```

前端常规 Playwright 套件使用网络拦截验证页面状态与四档响应式布局；真实认证链路使用独立 Compose 数据卷，不使用 API Mock：

```bash
cd web && npm ci && npx playwright install chromium && cd ..
bash scripts/e2e/run-real-auth.sh
```

真实链路会打包可执行的 Auth/Gateway Spring Boot JAR，启动 MySQL、Redis、Auth 和 Gateway，随后验证注册、Refresh Token 轮换、退出撤销、重复注册持久化冲突与再次登录。脚本退出时自动删除隔离容器和数据卷；失败时先输出服务日志。

关注、通知和推荐事件链路使用独立的 community profile：

```bash
bash scripts/e2e/run-real-community.sh
```

该脚本启动 Kafka、Auth、User、Content、Notification、Recommend 和 Gateway，验证两名新用户的资料同步、关注幂等、通知去重、取消关注静默以及推荐投影回退为未关注状态。

Community E2E 已限制 JVM、MySQL 和 Kafka 的测试内存。独占 Docker 时建议至少分配 2GB；如果同一 Docker Engine 还运行其他项目，建议分配 4GB 以上或先由操作者自行停止无关容器。Docker API 返回 500/502、容器退出码 137 或 `context deadline exceeded` 都属于环境失败，不能记为业务链路通过。

脚本会在构建镜像前用 10 秒超时探测 Docker daemon 和内存：daemon 无响应或低于 2 GiB 时直接退出；Docker 中已有其他运行容器且内存低于 4 GiB 时也会退出。确需在共享 Docker 上承担资源风险时，必须显式设置 `REAL_E2E_ALLOW_SHARED_DOCKER=true`，该覆盖只允许执行测试，不会把资源失败改记为通过。

镜像已在本机缓存且外网不可用时，可明确禁止拉取：

```bash
REAL_E2E_PULL_POLICY=never bash scripts/e2e/run-real-community.sh
```

Windows 请优先使用 Git Bash；`C:\Windows\System32\bash.exe` 可能实际进入 WSL，并受到 Docker Desktop 内部发行版状态影响。

本地 `mvnw test` 在 Docker 不可用时会把 Testcontainers 项目标记为 skipped，输出只能作为“单元测试通过、容器链路未验证”。CI 在 Maven 后额外运行 `scripts/ops/assert-no-skipped-tests.sh`，任何 skipped 都会阻断构建，避免把“没有跑容器”误认为“容器链路通过”。

Windows Docker Desktop 29 如遇 named pipe 探测失败，可启用本地只绑定回环地址的 Docker socket 代理：

```powershell
.\scripts\dev\testcontainers-docker-proxy.ps1
$env:DOCKER_HOST='tcp://127.0.0.1:2375'
.\mvnw.cmd -B test
```

测试完成后停止代理：

```powershell
.\scripts\dev\testcontainers-docker-proxy.ps1 -Stop
```

## 覆盖链路

CI 使用完整 `verify -Pcoverage`，当前回归下限为行覆盖率 68%、分支覆盖率 64%，并在下一步拒绝任何 skipped 测试。覆盖率检查还要求所有有业务代码的生产模块都生成 JaCoCo 报告，缺少报告会直接失败，避免部分模块未执行时聚合覆盖率反而虚高。Docker 不可用时，可以只测量纯单元基线，但该结果不得替代容器验收：

```powershell
.\mvnw.cmd -B -Pcoverage clean verify "-Dtest=!*IntegrationTest,!DockerPreflightTest,!KafkaContainerSmokeTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

纯单元命令会排除 `dc-file` 当前唯一的容器集成测试，因此只允许在本地测量时显式声明该报告缺失：

```bash
ALLOW_MISSING_COVERAGE_MODULES=dc-file bash scripts/ops/assert-coverage.sh
```

完整 CI 不设置该例外；`dc-file`、其余生产模块或 Testcontainers 未执行都会阻断构建。不要使用 `C:\Windows\System32\bash.exe`，Windows 上请使用 Git Bash。

2026-08-02 在 `e256608` 后的纯单元/H2 基线为 308 个测试、0 failures/errors/skipped；排除只含容器测试的 `dc-file` 后，13 份报告的行覆盖率为 71.13%、分支覆盖率为 66.87%。其中搜索模块为 79.34%/57.95%，新增契约覆盖稀疏事件回源、非公开内容移除和双重故障保留；管理模块为 52.96%/57.76%，新增事务覆盖用户限制、解限、幂等重放、校验失败和审计存储故障回滚。该数值是本地快速反馈基线，不替代包含 `dc-file` 和所有 Testcontainers 的完整 CI 覆盖结果。

2026-08-01 在 `6f1467d` 后的纯单元/H2 状态基线为 217 个测试、0 failures/errors/skipped，行覆盖率 60.34%、分支覆盖率 56.89%。其中 `DeductionService` 为 100% 行覆盖率、95.29% 分支覆盖率；`CommentService` 为 100%/95.83%，`CommentRepository` 为 96.77%/89.66%；`InteractionService` 为 92%/100%，`InteractionRepository` 为 98.11%/90.91%；`UserGraphService` 与 `UserGraphRepository` 均为 100%/100%；通知模块为 83.70%/87.93%，其消费器、仓储和控制器分支覆盖率均超过 88%；`PostRepository` 为 36.66%/28.04%，内容模块整体为 48.89%/49.58%；管理模块从 2.45%/0.91% 提升至 45.13%/48.33%，审核、申诉、审计和 Outbox 的核心事务路径已纳入 H2 回归。认证事务测试确认 Refresh/Logout 的令牌族撤销不会被幂等事务回滚，禁用账号也不能通过登录或刷新重放取得新会话。完整 CI 仍必须运行下列 Testcontainers 链路：

- Docker preflight：启动最小容器，确认 Testcontainers 能连接 Docker。
- `dc-content`: 发帖写入帖子、正文、标签、媒体关联和 Outbox；真实 Kafka 发送成功后事件才变为 `PUBLISHED`。
- `dc-interaction`: 重复点赞/收藏不重复写记录或重复增加计数，Redis 热度随首次互动变化。
- `dc-search`: 内容事件可以写入 Elasticsearch 文档。
- `dc-recommend`: Redis 兴趣、热度、曝光和图谱信号影响 Feed 排序。
- `dc-user`: 关注幂等、取消关注、粉丝/关注列表、共同关注和二跳推荐。
- `dc-file`: 图片上传到 MinIO，媒体元数据写入 MySQL。
- `dc-auth`/`dc-gateway`: OAuth2 mock 回调、JWT 签发、刷新令牌、Gateway 鉴权和透传。

## 排查建议

- 拉取镜像慢：先手动执行 `docker pull`。
- 端口冲突：Testcontainers 默认使用随机宿主机端口，通常不受本地 Compose 端口影响。
- Elasticsearch 启动慢：确认 Docker 可用内存不低于 4GB。
- MinIO 上传失败：确认 endpoint、access key、secret key 和 bucket 配置一致。
- Windows 路径问题：优先在仓库根目录执行 Maven 命令。
