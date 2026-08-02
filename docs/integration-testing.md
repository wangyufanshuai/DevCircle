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

四条真实 E2E 均限制 Docker 资源并在打包 JAR 前执行统一预检。独占 Docker 时至少分配 2 GiB；如果同一 Docker Engine 还运行其他项目，应分配 4 GiB 以上或先由操作者自行停止无关容器。Docker API 返回 500/502、容器退出码 137 或 `context deadline exceeded` 都属于环境失败，不能记为业务链路通过。

共享预检会用 10 秒超时探测 Docker daemon、内存和现有容器：daemon 无响应或低于 2 GiB 时直接退出；Docker 中已有其他运行容器且内存低于 4 GiB 时也会退出。确需在共享 Docker 上承担资源风险时，必须显式设置 `REAL_E2E_ALLOW_SHARED_DOCKER=true`，该覆盖只允许执行测试，不会把资源失败改记为通过。预检自身的离线回归命令为：

```bash
bash scripts/e2e/test-docker-preflight.sh
```

2026-08-02 在 `e3f6bed` 后，预检离线回归通过；真实 content 脚本在约 1 秒内识别到本机 Docker 配额为 2068439040 字节，低于 2 GiB 门槛，并在 Maven 打包前以状态码 2 退出。该结果只证明资源失败能被快速、明确地报告，真实 E2E 仍未执行。

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

`dc-file` 现在具有独立 H2 状态测试，纯单元命令也会生成该模块的 JaCoCo 报告，不再允许通过 `ALLOW_MISSING_COVERAGE_MODULES=dc-file` 跳过覆盖检查。完整 CI 仍必须执行容器集成测试；纯单元报告只能证明仓储事务和幂等状态，不证明真实 MySQL、MinIO 或预签名上传链路。

完整 CI 不设置该例外；`dc-file`、其余生产模块或 Testcontainers 未执行都会阻断构建。不要使用 `C:\Windows\System32\bash.exe`，Windows 上请使用 Git Bash。

2026-08-02 在 `b7ad448` 后的完整纯单元/H2 回归为 360 个测试、0 failures/errors/skipped；14 份报告的聚合行覆盖率为 76.29%、分支覆盖率为 68.60%，覆盖报告完整性与禁止 skipped 门禁均通过。该批次包含搜索删除 refresh 失败重试契约、`search_after` 游标改造和 Outbox 租约所有权测试；结果仍排除了完整 Gateway/Compose E2E、性能或恢复演练。

2026-08-02 在 `43e6f39` 后的完整纯单元/H2 回归为 357 个测试、0 failures/errors/skipped；14 份报告的聚合行覆盖率为 76.42%、分支覆盖率为 68.89%，覆盖报告完整性与禁止 skipped 门禁均通过。`ContentCommentProjectionRepository` 为 100%/88.46%，`ContentInteractionProjectionRepository` 为 100%/85.00%。内容库 V19 为评论与互动计数投影去重表增加事件指纹，并验证迁移会回填旧记录且旧应用省略新列的写入仍可兼容一个版本。两个消费者现在把完整 `ValidatedEvent` 传入投影事务：同 ID 同载荷保持幂等，同 ID 更换 topic、文章、动作、发生时间或 payload 会明确冲突；旧全零记录在首次重放时升级绑定但不会重复修改计数。状态测试通过真实 Spring 事务代理确认计数写入失败会回滚消费者声明，依赖恢复后原事件可用同一 ID 重试。当前结果排除了 Testcontainers，因此 V19 的真实 MySQL 迁移、Kafka 重投以及投影恢复尚未验证。

2026-08-02 在 `43e6f39` 后补充真实 MySQL 投影回归：`ContentCommentProjectionIntegrationTest`、`ContentInteractionProjectionIntegrationTest` 和 `ContentProjectionMigrationIntegrationTest` 共 5 项测试在 MySQL 8.4 Testcontainer 中通过。前两类验证 V19 全量迁移后的评论/互动重复投影、缺失文章回滚和计数一致性；迁移测试先在真实 MySQL 执行到 V18，写入旧去重记录，再执行 V19，确认旧记录回填全零指纹且旧应用省略 `event_fingerprint` 的写入仍兼容。该批次未启动 Kafka、Redis、Elasticsearch 或 MinIO，因此不能替代完整多服务集成验收。

同日 `OutboxFailureIntegrationTest` 以 MySQL 8.4 真实容器通过 1 项测试，验证 Kafka 不可用时内容写入仍保留 `FAILED` Outbox 事件并可继续补偿；该批次仍未执行完整 Gateway/多服务链路。

2026-08-02 补充单基础设施可靠性回归，共 9 项真实测试通过：`FeedServiceRedisIntegrationTest` 使用 Redis 8.2 验证兴趣、热度、关注信号和曝光降权；`InteractionPipelineIntegrationTest` 使用 MySQL 8.4 与 Redis 8.2 验证点赞/收藏幂等、持久化计数、Outbox 写入和 Redis 热度只变化一次；`KafkaContainerSmokeTest` 使用 Kafka 4.1.1 验证真实 topic、producer 和 consumer group；`NotificationKafkaReliabilityIntegrationTest` 使用 MySQL 8.4 与 Kafka 4.1.1 验证 poison event 重试、DLT 投递、有效事件消费和重复通知去重。该批次覆盖了真实 MySQL、Redis、Kafka 的关键切片，但仍未覆盖 Elasticsearch、MinIO 或完整 Gateway/多服务 E2E。

同日 `FileUploadIntegrationTest` 以 MySQL 8.4 + MinIO 真实容器通过 1 项媒体测试：预签名 PUT 上传真实 PNG 魔数，完成校验后媒体保持 `PENDING_REVIEW`、公开 URL 为空，并确认申请/完成幂等记录写入载荷指纹。至此本轮单基础设施真实回归累计 10 项；Elasticsearch、完整 Gateway/多服务 E2E 和目标主机性能仍未验收。

随后 `SearchIndexIntegrationTest` 以 Elasticsearch 9.2.2 真实容器（256MB 堆、单节点、禁用 ML）通过 4 项测试：发布事件写入真实索引后，文档详情和关键词搜索均可读；乱序旧事件不能覆盖新版本，内容驳回后会 refresh 索引并从关键词搜索中立即消失；最新排序使用 `search_after` 游标，第一页之后插入更晚内容不会造成重复，游标复用到不同筛选条件会被拒绝；相关性排序保留 `_score`、时间和 ID 的稳定 tie-breaker。连同 V19 投影迁移测试，本轮真实基础设施回归累计 20 项，五类基础设施均已有切片证据；完整 Gateway/Compose E2E、跨服务事件追平、性能和恢复演练仍未验收。

最后 `ContentOutboxIntegrationTest` 以 MySQL 8.4 + Kafka 4.1.1 真实容器通过 3 项测试：内容、标签、Outbox 在同一事务写入后可正常发布到 Kafka，重复评论/互动投影保持一次性计数。至此本轮单基础设施真实回归累计 23 项；完整 Gateway/Compose E2E、跨服务事件追平、性能和恢复演练仍未验收。

2026-08-02 在 `cd75565` 后的完整纯单元/H2 回归为 351 个测试、0 failures/errors/skipped；14 份报告的聚合行覆盖率为 76.05%、分支覆盖率为 68.26%，覆盖报告完整性与禁止 skipped 门禁均通过。`dc-user` 为 82.23%/91.84%，本批核心 `UserGraphRepository` 为 99.20%/97.62%。用户 V6 为 `consumer_dedup` 增加事件指纹，并验证迁移会回填旧记录且旧应用省略新列的写入仍可兼容一个版本。注册消费者现在把完整 `ValidatedEvent` 传入资料投影事务：同 ID 同载荷保持幂等，同 ID 更换 topic、发生时间、生产者、聚合标识或 payload 会明确冲突；旧全零记录在首次重放时升级绑定但不会再次修改资料。状态测试通过真实 Spring 事务代理确认资料写入失败会回滚消费者声明，依赖恢复后原事件可用同一 ID 重试。当前本机 Docker 资源不足以执行完整多服务链路，因此 V6 的真实 MySQL 迁移和 Kafka 注册事件重投尚未验证。

2026-08-02 在 `0912120` 后的完整纯单元/H2 回归为 347 个测试、0 failures/errors/skipped；14 份报告的聚合行覆盖率为 75.96%、分支覆盖率为 68.20%，覆盖报告完整性与禁止 skipped 门禁均通过。`dc-recommend` 为 84.01%/72.79%，本批核心 `JdbcInteractionConsumerDedup` 为 97.96%/87.50%。内容库 V18 为推荐消费者去重表增加事件指纹，并验证迁移会回填旧记录且旧应用省略新列的写入仍可兼容一个版本。交互与关注消费者改为向去重仓储传入完整 `ValidatedEvent`：已完成事件同载荷保持幂等，失败或过期处理中事件只有同载荷可以重领，同 ID 更换 topic、发生时间、生产者、聚合标识或 payload 会明确冲突，旧全零记录在首次有效重试时升级绑定。未过期 `PROCESSING` 事件不再被当作已完成而静默确认，而是抛出可重试异常进入现有 Kafka 重试/DLT 流程，避免进程在 claim 后崩溃造成投影丢失。当前本机 Docker 资源不足以执行完整多服务链路，因此 V18 的真实 MySQL 迁移、Kafka 重投/DLT、Redis 信号和关注投影尚未验证。

2026-08-02 在 `45aa3e9` 后的完整纯单元/H2 回归为 341 个测试、0 failures/errors/skipped；14 份报告的聚合行覆盖率为 75.88%、分支覆盖率为 68.07%，覆盖报告完整性与禁止 skipped 门禁均通过。`dc-search` 为 80.37%/58.89%，本批核心 `SearchEventCoordinator` 为 100%/92.31%。搜索 V2 为 `search_consumer_dedup` 增加事件指纹；消费者现在用共享 `EventFingerprint` 绑定类型、版本、发生时间、生产者、聚合标识和规范化 payload。已完成事件同载荷保持幂等，失败或过期处理中事件只有同载荷可以重领，同 ID 更换 topic、文章、发生时间、生产者、聚合标识或 payload 会明确冲突。旧全零记录兼容一个版本，并在首次有效重试时升级绑定。搜索聚合繁忙异常还通过真实 Spring 事务代理验证为保留第二事件的 `FAILED` 状态，同时不释放当前事件持有的水位线租约。当前本机 Docker 资源不足以执行完整多服务链路，因此 V2 的真实 MySQL 迁移、Kafka 重投和 Elasticsearch 投影恢复尚未验证。

2026-08-02 在 `6d86949` 后的完整纯单元/H2 回归为 338 个测试、0 failures/errors/skipped；14 份报告的聚合行覆盖率为 75.80%、分支覆盖率为 68.02%，覆盖报告完整性与禁止 skipped 门禁均通过。`dc-notification` 为 84.58%/88.20%，`NotificationRepository` 为 97.87%/90.62%，共享 `EventFingerprint` 为 94.87%/100%。通知消费者现在以事件类型、版本、发生时间、生产者、聚合标识和规范化 payload 计算 SHA-256 指纹：同事件 ID 同载荷保持幂等，同 ID 换 topic 或 payload 会抛出并进入现有重试/DLT策略，旧全零去重记录兼容一个版本。通知“全部已读”同键重放也会返回首次更新数量，且不会处理首次请求后新到达的通知。Docker Engine 仍不可用，因此 V4 的真实 MySQL 迁移、Kafka 重投和 DLT 链路尚未验证。

2026-08-02 在 `8437315` 后的完整纯单元/H2 回归为 333 个测试、0 failures/errors/skipped；14 份报告的聚合行覆盖率为 75.60%、分支覆盖率为 67.84%，覆盖报告完整性与禁止 skipped 门禁均通过。`dc-comment` 为 80.08%/77.92%，`CommentRepository` 为 96.00%/87.84%；创建评论的幂等键现在绑定文章、父评论和规范化正文，隐藏评论绑定评论与规范化审核理由，删除绑定评论标识。状态测试确认同键异载荷冲突、旧全零指纹兼容、无效父评论不消耗请求键，并验证 Outbox 故障会同时回滚评论、隐藏状态、审核记录和幂等键，依赖恢复后可用原键重试。Docker Engine 仍不可用，因此 V5 的真实 MySQL 迁移与 Kafka 事件链路尚未验证。

2026-08-02 在 `f0d3529` 后的完整纯单元/H2 回归为 331 个测试、0 failures/errors/skipped；14 份报告的聚合行覆盖率为 75.52%、分支覆盖率为 67.75%，覆盖报告完整性与禁止 skipped 门禁均通过。`dc-file` 为 39.91%/21.74%，本批核心 `MediaRepository` 为 74.56%/54.35%；状态测试覆盖上传申请元数据指纹、生成存储坐标重试、上传完成实际大小指纹、跨媒体重放冲突、旧全零指纹兼容、数据库失败回滚以及修复后同键重试。V3 为既有文件幂等记录回填一个版本兼容的全零指纹，新的申请和完成请求均写入 SHA-256 载荷指纹。Docker Engine 仍不可用，因此 V3 的真实 MySQL 迁移、MinIO 上传与完整 Testcontainers 链路尚未验证。

2026-08-02 在 `f99122b` 后的完整纯单元/H2 回归为 322 个测试、0 failures/errors/skipped；排除只含容器测试的 `dc-file` 后，13 份报告的行覆盖率为 77.00%、分支覆盖率为 69.75%。内容模块为 73.64%/69.95%，`PostRepository` 为 71.41%/55.16%，新增状态测试覆盖帖子创建和申诉的原子写入、Outbox 故障整体回滚、修复后同键重试及同键异载荷冲突。管理模块为 64.84%/64.84%；推荐实验创建/启停、Outbox 人工重放和真实标签创建现在要求管理员身份与 `Idempotency-Key`，绑定请求指纹并与审计日志同事务提交，遗留的固定“`MysticVerse精选`”标签写入和虚增使用次数逻辑已删除。随后 `2355532` 的 Gateway 定向覆盖回归为 30 个测试、0 failures/errors/skipped，普通用户无法再访问 `/api/search/admin/**` 或 `/api/content/admin/**`。本轮曾尝试启动和重启 Docker Desktop，但 Linux Engine API 持续超时且服务最终停止，因此当前结果仍不替代真实 MySQL、Kafka 及完整 Community E2E。

2026-08-02 在 `aa0cade` 后的纯单元/H2 基线为 313 个测试、0 failures/errors/skipped；排除只含容器测试的 `dc-file` 后，13 份报告的行覆盖率为 73.64%、分支覆盖率为 68.76%。其中内容模块为 65.15%/67.00%，`PostRepository` 为 57.30%/49.35%；管理模块为 54.38%/58.87%。本轮将内容创建、草稿保存与投稿、申诉、管理员审核和用户风险操作的 `Idempotency-Key` 绑定到 SHA-256 请求指纹；相同键用于不同用户、操作或载荷时返回冲突，旧全零指纹仅对同用户和同操作兼容一个版本。当前 Docker Desktop 服务未运行，因此 V16 的真实 MySQL 迁移探针、`dc-file` 及其余 Testcontainers 链路均未执行，不能据此记为完整集成验收通过。

2026-08-02 在 `f46c51a` 后的纯单元/H2 基线为 311 个测试、0 failures/errors/skipped；排除只含容器测试的 `dc-file` 后，13 份报告的行覆盖率为 71.78%、分支覆盖率为 67.05%。其中搜索模块为 79.34%/57.95%，新增契约覆盖稀疏事件回源、非公开内容移除和双重故障保留；管理模块为 52.96%/57.76%，新增事务覆盖用户限制、解限、幂等重放、校验失败和审计存储故障回滚；内容模块为 58.25%/60.57%，`PostRepository` 为 45.48%/36.09%，结构化草稿和种子内容序列化失败会整体回滚，数据库故障也不再伪装为导入零条成功。该数值是本地快速反馈基线，不替代包含 `dc-file` 和所有 Testcontainers 的完整 CI 覆盖结果。

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
