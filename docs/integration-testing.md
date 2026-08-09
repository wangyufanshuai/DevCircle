# 集成测试说明

DevCircle v1.2 保留 Testcontainers 验证核心依赖闭环。默认 Maven 测试会启动临时 MySQL、Redis、Kafka、Elasticsearch 和 MinIO 容器，执行结束后自动清理。

## 当前证据边界

截至 2026-08-09，当前架构基线为 `5dbba82`，媒体读取拆分基线为 `6ff55ff`，推荐拆分基线为 `acee747`，供应链验证基线为 `baf074e`。`FeedService` 已将写侧 Telemetry、场景策略和候选召回分离，`AdminService` 已将推荐实验迁入独立领域服务，并删除与 `AdminMutationSupport` 重复的幂等、审计、Outbox 和校验实现。内容读侧新增 `PostMediaReadRepository`、`PostDetailReadRepository` 和 `PostViewerStateReadRepository`，将媒体、详情以及解谜/阅读状态从列表仓储分离，`PostQueryRepository` 从约 356 行降至约 231 行，并保留双参数构造器兼容现有测试。

Windows 本机使用 E 盘临时目录并禁用 Surefire 分叉执行当前 `5dbba82` 的全仓纯单元/H2 回归，18/18 个 Maven 模块构建成功，其中 15 个模块在本轮时间窗口内产生 104 份测试报告，共 457 个测试，0 failures、0 errors、0 skipped；内容模块及其依赖回归 145 项，单独的内容仓储、连接池和依赖故障定向测试 19 项均通过。该全仓命令显式排除 `*IntegrationTest`、`DockerPreflightTest` 和 `KafkaContainerSmokeTest`，且 `forkCount=0` 不能产生可信 JaCoCo 结果，因此只能证明当前非容器功能回归，不能解释为最新全仓覆盖率、Testcontainers 或 Compose 通过。

同一提交的前端 `npm run build` 成功，四个 Playwright project 共 120/120 通过；常规 Playwright 套件使用显式 API 拦截，不替代真实多服务 E2E。31 项生产资源、迁移、覆盖率门禁、Linux 性能/恢复证据和发布身份 Python 契约通过，实际生产 Compose 静态资源门禁确认 24 个服务、14.62 GiB 内存上限和 2 个发布服务。当前 `docker info` 在约 19 秒内超时，Bash/WSL 入口也返回 `HCS_E_CONNECTION_TIMEOUT`，因此未执行最新 Testcontainers、真实 Compose、Shell 行为门禁、k6 或恢复演练；`npm audit --audit-level=high` 仍受本机 Registry 镜像不实现 security audit endpoint 的限制。本文下方带日期的容器、覆盖率、Compose、性能和恢复结果均为绑定旧提交的历史证据，除非重新在当前 SHA 执行，否则不得当作 `5dbba82` 的生产验收结论。

提交 `101359e` 后重新执行离线交付门禁：Linux 性能、Linux 恢复、k6 和生产资源预算契约 19 项通过，发布身份、迁移版本和关键状态覆盖契约 8 项通过，生产环境校验与覆盖率 Shell 门禁通过，备份、恢复和迁移验证夹具在补齐当前 Alertmanager 生产路径契约后通过。实际生产 Compose 渲染为 24 个服务、14.62 GiB 容器内存上限，只发布 Web 与绑定宿主机回环地址的 Gateway。该批次只证明脚本和配置拒绝错误证据的能力；没有启动真实容器、没有执行真实 Cosign Registry 验证，也没有产生目标 Linux 的性能或 RTO/RPO 证据。

供应链基线 `baf074e` 将单镜像 Cosign 签名与 SLSA provenance 验证抽为 CI/生产共用入口；后端镜像矩阵和 Web 镜像会在签名与证明写入 Registry 后立即按 digest 验证。验证器只接受项目 GHCR 的不可变 SHA-256 digest，Registry 短暂传播允许默认 5 次、每次 3 秒的有界重试，耗尽后失败关闭并输出最终 Cosign 诊断。Shell 行为测试、5 项 Python 契约、Bash 语法与 GitHub Actions YAML 解析均通过；由于本机没有执行真实 GHCR/Cosign 网络验证，该证据仍只证明失败关闭和工作流接线，不证明 Registry 中当前镜像已经验签成功。

## 验证证据时间线

### 2026-08-08 可执行基线（历史提交记录）

Docker Desktop 的 WSL 配额已从 2GB 调整为 6GB，Testcontainers 实测可用内存约 5930MB。当前工作树执行 `./mvnw.cmd -B verify -Pcoverage` 后，16/16 个 Maven 模块成功，117 个测试套件共 482 个测试，0 failures、0 errors、0 skipped；MySQL 8.4、Redis、Kafka、Elasticsearch 和 MinIO 容器均实际启动。聚合 JaCoCo 行覆盖率为 85.39%、分支覆盖率为 75.10%，14 个关键状态类全部通过 85% 分支门禁，Flyway 迁移版本门禁通过。

五条真实 Compose E2E 均使用本地固定镜像并通过：

- Auth：注册、刷新轮换、退出撤销、重复注册冲突和再次登录。
- Content：真实内容写入和公开读取；同时验证关闭 Outbox 的精简 profile 可以正常启动。
- Review：投稿、管理员审核、公开详情、答案保护、正确答题及幂等重放。Review profile 不启动评论服务时，正文与解题仍可用，评论区显示局部错误并可重试。
- Community：注册事件创建公开资料、同键关注只生效一次、Kafka 通知去重、取消关注以及推荐投影追平；正常链路后的四项数据库断言全部通过。随后脚本会停止 Kafka，在 broker 离线时执行真实关注写入，验证业务状态与 Outbox 同事务持久化、Outbox 进入 `FAILED` 且消费者投影保持为空；Kafka 重启后要求同一事件自动变为 `PUBLISHED`，通知和推荐投影各恰好生效一次。
- Search recovery：`retry` 模式验证 Elasticsearch 恢复后消费者自动重试、文档唯一和乱序水位线；`dlt` 模式强制耗尽重试，验证管理员按 partition/offset 精确重放、发布与审计幂等，并确认旧事件不能回退水位线。

Community 首轮执行实际发现并修复了两项生产缺陷：Gateway 现仅对匿名读者开放精确的 `/api/users/{id}` 与 `/api/users/batch` GET 路径，关注状态等私有路径继续受保护；`dc-user` 的注册事件消费者不再因过早的 `@ConditionalOnBean` 判定而被静默排除。前端 `npm run build` 通过，四个 Playwright project 共 120/120 通过；官方 npm registry 审计为 0 vulnerabilities，DOMPurify 与 Nano ID 已升级到修复版本。

2026-08-08 最新 Community 故障注入在完整 Compose 网络中通过：Kafka 容器被确认停止后，关注接口仍返回真实成功且可立即读到关注状态；对应 `follow.changed` Outbox 事件在 2 秒有界发送超时后进入 `FAILED`，通知表和推荐关注投影均保持 0。Kafka 原容器重启后，应用进程无需重启，Outbox 自动重试并转为 `PUBLISHED`；通知消费者去重记录、推荐消费者 `COMPLETED` 记录、单条通知和单条关注投影全部到位。同一 `Idempotency-Key` 再次请求返回重复标记，事件与通知数量仍为 1。该证据覆盖 Kafka 短时停机与恢复追平，不等同于长时间积压、跨主机 Kafka 数据恢复或 DLT 人工重放演练。

2026-08-08 Community profile 的 `dead` 模式补齐 Outbox 耗尽重试后的恢复证据，并证明部署配置会真实传入 Worker：最大尝试次数设为 2，Kafka 停机后首次 `post.submitted` 进入 `DEAD:2`，同一 `(topic, aggregateId)` 的第二次投稿保持 `NEW:0`，独立的 `content.rejected` 也进入 `DEAD:2`。管理健康接口现在聚合 Auth、User、Content、Comment、Interaction 五域，明确返回 `outboxScope=FLEET`、`outboxComplete=true`、`OUTBOX_DEAD`、DEAD 数和被前序阻塞数；停掉 `dc-user` 后返回 `outboxComplete=false`、`OUTBOX_SOURCE_UNAVAILABLE`，重启后自动恢复完整。五个内部端点均验证正确密钥与服务身份匹配，且无密钥请求返回 403。Kafka 恢复后 DEAD 不会被静默自动跳过；管理员使用真实 ADMIN JWT 与幂等键重放后，两个 `post.submitted` 和一个 `content.rejected` 按 Topic 精确追加，三条事件全部转为 `PUBLISHED`，重复请求不追加记录，健康状态恢复且审计日志只有一条。演练还修复了 Community 扩容后默认 Hikari 池耗尽 MySQL 60 个连接的问题，E2E 现显式限制每服务最多 4 个连接。该证据覆盖单机 Kafka 暂停、DEAD 可观测、跨域健康完整性、同流保序和人工恢复，不等同于跨主机 Kafka 数据目录灾难恢复。

2026-08-08 Search recovery 故障注入在完整 Auth/Content/Admin/Search/Gateway 与 MySQL/Redis/Kafka/Elasticsearch 网络中通过：待审核内容搜索结果为 0，审核后正文立即公开；Elasticsearch 停止期间搜索明确返回 `DEPENDENCY_UNAVAILABLE`，`audit.completed` Outbox 保持 `PUBLISHED`，搜索消费记录进入 `FAILED` 且 DLT 为 0。原 Elasticsearch 容器启动后应用无需重启，消费者第 11 次尝试转为 `COMPLETED`，关键词搜索恰好返回一条内容。随后投递一条 2020 年的旧拒绝事件，消费记录正常完成但水位线和已发布索引均未回退；同一审核幂等键重放也未产生第二个事件或文档。演练同时发现并修复了空索引缺少数值型 `id` 映射导致全部搜索返回 503 的生产缺陷；索引初始化现在会在服务就绪前为新旧索引补齐稳定游标映射。该证据覆盖短时 Elasticsearch 停机与 Kafka 原记录重试，不等同于超过重试上限后的 DLT 人工重放或 Elasticsearch 数据目录灾难恢复。

2026-08-08 同一 Search recovery profile 的 `dlt` 模式完成超过重试上限后的人工重放演练：Search 最大重试设为 1，`audit.completed` 在 Elasticsearch 停机期间进入 `audit.completed.DLT` offset 0，Outbox 保持 `PUBLISHED` 且搜索消费记录为 `FAILED`。恢复原 Elasticsearch 容器后，管理员使用真实 ADMIN JWT、`Idempotency-Key`、原始 topic、partition、offset 和理由调用 `/api/admin/ops/dlt/replay`；源 topic 只追加一条记录，搜索投影转为 `COMPLETED`，`kafka_dlt_replay` 与成功审计各写入一条。同键重放返回 `duplicateRequest=true` 且不再次发布，同一 DLT 位置也不能由另一幂等键重复处理；旧拒绝事件仍未回退水位线。该证据覆盖精确、幂等、可审计的人工 DLT 重放，不等同于 Kafka 数据目录丢失或跨集群灾难恢复。

提交 `ccfa310`（包含 `f2254fd`）后的完整 `./mvnw.cmd -B verify -Pcoverage` 已通过：16 个 Maven 模块、442 个测试，0 failures、0 errors、0 skipped；聚合 JaCoCo 行覆盖率 84.03%、分支覆盖率 75.40%，关键状态机分支门禁全部通过 85%。本轮还验证了 Flyway 迁移版本、生产 Compose 配置和生产环境变量门禁，并确认 aggregate Gateway 不再将评论请求旁路到旧评论服务。Outbox Kafka 发送现在受 `DEVCIRCLE_OUTBOX_SEND_TIMEOUT_MS`（默认 15 秒）约束，Future 超时会进入 FAILED 并按退避重试。

2026-08-03 搜索事件协调器与 Elasticsearch 投影统一采用 `(occurredAtMillis, eventId)` 单调顺序，修复同一毫秒内 eventId 较大的合法事件在进入 Elasticsearch 前被误判为旧事件的问题。全仓非容器覆盖回归为 402 个测试、0 failures/errors/skipped，聚合行覆盖率 79.68%、分支覆盖率 74.00%，`SearchEventCoordinator` 分支覆盖率 87.50%，全部关键状态机继续通过 85% 门禁。当前 Docker 守护进程未运行，因此本条证据明确不包含 Testcontainers、真实 Elasticsearch 或 Compose E2E。

同日评论与互动生产者增加事务内权威计数和单调 `counterVersion`，内容计数投影优先按生产者版本收敛，旧增量事件保留一个版本兼容。全仓非容器回归为 408 个测试、0 failures/errors/skipped，聚合行覆盖率 80.03%、分支覆盖率 74.30%；新增计数协调器分支覆盖率 92.11%，评论/互动投影仓储分别为 90.91%/92.86%，关键状态机门禁通过。真实 Testcontainers 回归包含内容 MySQL 投影/迁移 5 项、评论 MySQL/Kafka 4 项、互动 MySQL/Redis 4 项，均为 0 failures/errors/skipped。该证据验证单基础设施切片，不替代四套真实 Compose E2E。

2026-08-04 对同一批次执行完整 `./mvnw.cmd -B verify -Pcoverage`：16/16 个 Maven 模块成功，104 个测试套件共 449 个测试，0 failures、0 errors、0 skipped；MySQL、Redis、Kafka、Elasticsearch 和 MinIO Testcontainers 均实际启动。14 份 JaCoCo 报告聚合行覆盖率 84.31%、分支覆盖率 75.73%，包括 `ContentCounterProjectionCoordinator` 在内的全部关键状态机均通过 85% 分支门禁。迁移版本唯一性门禁通过。该结果证明单进程测试与容器切片完整通过，仍不替代四套 Gateway/Compose 真实 E2E、50 并发性能或目标主机恢复演练。

同日互动写入契约增加内容作者 `receiverId`：互动服务在公开状态校验时同时取得作者身份，并在本地事务内把接收者、计数快照和单调版本写入 `interaction.changed`。通知消费者对新版事件不再同步调用内容服务，旧无接收者事件保留一个版本的回源兼容。完整 `./mvnw.cmd -B verify -Pcoverage` 再次通过：16/16 个模块、104 个套件、452 个测试，0 failures/errors/skipped，五类 Testcontainers 均实际运行；聚合行/分支覆盖率为 84.35%/75.77%，`NotificationEventConsumer` 分支覆盖率为 91.51%，全部关键状态机继续通过 85% 门禁。真实 Compose E2E 仍因本机 `.wslconfig` 的 2GB 上限被预检阻断，未记为通过。

随后同步 HTTP 调用统一收口到有界客户端工厂，连接和读取超时分别固定为 1 秒与 2 秒；评论、互动、推荐、搜索、旧通知事件回源和 GitHub OAuth 不再使用无限等待的默认客户端，聚合门面另保留 2 秒 Reactor 超时。工厂测试用真实本地 HTTP Server 验证健康响应、尾部斜杠规范化、读取超时和非法配置失败。清理陈旧测试报告后的完整 `./mvnw.cmd -B clean verify -Pcoverage` 通过：16/16 个模块、105 个套件、455 个测试，0 failures/errors/skipped；五类 Testcontainers 实际运行，聚合行/分支覆盖率为 84.37%/75.84%，全部关键状态机通过门禁。该证据不改变真实 Compose E2E 的资源阻断状态。

生产暴露面门禁随后扩展为 9 项：Gateway 的宿主机 8080 必须绑定 `127.0.0.1`，所有应用必须保持生产模式、关闭读取降级与主题 Seed，并禁用 SpringDoc API 与 Swagger UI；Caddy 管理 API 必须只监听容器内 localhost。生产 Compose 实际渲染确认 `host_ip=127.0.0.1`，`caddy:2.10.2-alpine` 对当前 Caddyfile 的真实 `caddy validate` 通过。该结果验证配置契约，不替代目标主机防火墙和公网探测。

同日高频读路径审计补充了通知收件箱、评论线程、收藏时间线、频道游标和作者投稿列表的组合索引，并增加四个 MySQL 8.4 `EXPLAIN` 集成测试。测试先迁移完整模块 schema，再为每条路径写入 10,000 行代表性数据，要求优化器实际选择目标索引且执行计划不含 `filesort`；仅检查“索引存在”不能通过。当前 Docker Engine 停止，因此这些 Testcontainers 用例只完成了编译，定向执行被明确标记为 skipped，不能记为 MySQL 8.4 通过。作为独立兼容性证据，项目目录下的一次性 MySQL 9.3 实例实际执行了四个模块共 39 份迁移，并以同样的 10,000 行数据确认目标索引被选中且无 `filesort`；实例、数据目录和日志随后已清理。全仓非容器回归为 414 个测试、0 failures/errors/skipped，聚合行覆盖率 80.20%、分支覆盖率 74.45%，14 个关键状态类继续通过 85% 门禁。该结果仍不替代固定 MySQL 8.4 的自动化回归、四套真实 Compose E2E 或 k6 延迟验收。

真实 E2E profile 的 Outbox 依赖现在显式声明：auth/content/review 脚本关闭 Outbox，避免在无 Kafka 的隔离套件中产生无关 broker 重试；community 与 search-recovery 脚本开启 Outbox，并由 `test-docker-preflight.sh` 契约校验该差异。

历史前端基线为 `npm run build` 成功、四个 Playwright project 共 116/116 通过。最新结果见上方 2026-08-08 可执行基线；显式 API 拦截套件与真实多服务 E2E 仍按不同证据记录。

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

# 耗尽 Outbox 重试，验证 DEAD 可观测、保序阻塞和人工恢复
REAL_E2E_COMMUNITY_RECOVERY_MODE=dead \
  REAL_E2E_PULL_POLICY=never \
  bash scripts/e2e/run-real-community.sh
```

该脚本启动 Kafka、Auth、User、Content、Comment、Interaction、Admin、Notification、Recommend 和 Gateway，先验证正常资料同步、关注幂等、通知去重、取消关注以及推荐投影最终收敛。默认 `retry` 模式停止并重启 Kafka，验证 Outbox `FAILED → PUBLISHED`、消费者自动重连和投影追平；`dead` 模式把最大尝试次数设为 2，继续验证 `DEAD` 告警、同 Topic 聚合保序、五域健康完整性、管理员审计重放、精确 offset 增量和单域下线后的恢复。

搜索索引故障恢复使用独立 profile：

```bash
bash scripts/e2e/run-real-search-recovery.sh

# 强制耗尽消费者重试并验证管理员人工重放
REAL_E2E_SEARCH_RECOVERY_MODE=dlt \
  REAL_E2E_PULL_POLICY=never \
  bash scripts/e2e/run-real-search-recovery.sh
```

该脚本启动 Kafka、Elasticsearch、Auth、Content、Admin、Search 和 Gateway。默认 `retry` 模式验证短时依赖故障后的原记录自动重试；`dlt` 模式把 Search 最大重试设为 1，随后验证 DLT offset、管理员精确重放、唯一发布、重放审计和幂等返回。两种模式都会验证空索引、明确故障响应与乱序事件水位线。该 profile 明确要求至少 4 GiB Docker 内存。

五条真实 E2E 均限制 Docker 资源并在打包 JAR 前执行统一预检。除 search-recovery 外，独占 Docker 时至少分配 2 GiB；如果同一 Docker Engine 还运行其他项目，应分配 4 GiB 以上或先由操作者自行停止无关容器。Docker API 返回 500/502、容器退出码 137 或 `context deadline exceeded` 都属于环境失败，不能记为业务链路通过。

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

2026-08-02 最新事件可靠性回归：`dc-common`、`dc-content`、`dc-recommend` 共 170 项纯单元/H2 测试通过，覆盖率聚合为行 `78.86%`、分支 `72.29%`；推荐消费者新增 `lease_token` 迁移和过期 worker 不能覆盖新 worker 的状态测试。该结果仍不替代真实 Kafka、Redis 和 Compose 故障恢复演练。

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

CI 使用完整 `verify -Pcoverage`，当前全局回归下限为行覆盖率 75%、分支覆盖率 70%，并拒绝任何 skipped 测试。覆盖率检查还要求所有有业务代码的生产模块都生成 JaCoCo 报告，缺少报告会直接失败，避免部分模块未执行时聚合覆盖率反而虚高。Docker 不可用时，可以只测量纯单元基线，但该结果不得替代容器验收：

```powershell
.\mvnw.cmd -B -Pcoverage clean verify "-Dtest=!*IntegrationTest,!DockerPreflightTest,!KafkaContainerSmokeTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

`dc-file` 现在具有独立 H2 状态测试，纯单元命令也会生成该模块的 JaCoCo 报告，不再允许通过 `ALLOW_MISSING_COVERAGE_MODULES=dc-file` 跳过覆盖检查。完整 CI 仍必须执行容器集成测试；纯单元报告只能证明仓储事务和幂等状态，不证明真实 MySQL、MinIO 或预签名上传链路。

完整 CI 不设置该例外；`dc-file`、其余生产模块或 Testcontainers 未执行都会阻断构建。不要使用 `C:\Windows\System32\bash.exe`，Windows 上请使用 Git Bash。

除全局覆盖率外，CI 还执行 `scripts/ops/assert-critical-state-coverage.py`，对认证服务、Outbox、内容状态机、通知、推荐租约、搜索水位线和注册投影等 12 个关键类强制分支覆盖率不低于 85%。认证控制器、GitHub OAuth 和后台治理等尚未达到该门槛的类不会被列入当前关键门禁，仍保留在后续补测清单。

2026-08-02 前端回归基线：`cd web && npm run test:e2e` 在 Chromium、桌面、平板和移动四个 Playwright project 中共运行 112 项，112 项通过（34 秒）。该套件使用显式 API 拦截，验证路由、状态、错误处理、幂等键、响应式布局和键盘交互，不等同于真实服务 E2E。真实认证、内容、审核和社区脚本使用独立 Compose；当前 Docker 配额为 2068439040 bytes（约 1.93 GiB），低于 2147483648 bytes 的最小门槛，`run-real-auth.sh` 在打包前明确阻断，未记为业务通过。

2026-08-02 在 `b7ad448` 后的完整纯单元/H2 回归为 360 个测试、0 failures/errors/skipped；14 份报告的聚合行覆盖率为 76.29%、分支覆盖率为 68.60%，覆盖报告完整性与禁止 skipped 门禁均通过。该批次包含搜索删除 refresh 失败重试契约、`search_after` 游标改造和 Outbox 租约所有权测试；结果仍排除了完整 Gateway/Compose E2E、性能或恢复演练。

2026-08-02 在 `43e6f39` 后的完整纯单元/H2 回归为 357 个测试、0 failures/errors/skipped；14 份报告的聚合行覆盖率为 76.42%、分支覆盖率为 68.89%，覆盖报告完整性与禁止 skipped 门禁均通过。`ContentCommentProjectionRepository` 为 100%/88.46%，`ContentInteractionProjectionRepository` 为 100%/85.00%。内容库 V19 为评论与互动计数投影去重表增加事件指纹，并验证迁移会回填旧记录且旧应用省略新列的写入仍可兼容一个版本。两个消费者现在把完整 `ValidatedEvent` 传入投影事务：同 ID 同载荷保持幂等，同 ID 更换 topic、文章、动作、发生时间或 payload 会明确冲突；旧全零记录在首次重放时升级绑定但不会重复修改计数。状态测试通过真实 Spring 事务代理确认计数写入失败会回滚消费者声明，依赖恢复后原事件可用同一 ID 重试。当前结果排除了 Testcontainers，因此 V19 的真实 MySQL 迁移、Kafka 重投以及投影恢复尚未验证。

2026-08-02 在 `43e6f39` 后补充真实 MySQL 投影回归：`ContentCommentProjectionIntegrationTest`、`ContentInteractionProjectionIntegrationTest` 和 `ContentProjectionMigrationIntegrationTest` 共 5 项测试在 MySQL 8.4 Testcontainer 中通过。前两类验证 V19 全量迁移后的评论/互动重复投影、缺失文章回滚和计数一致性；迁移测试先在真实 MySQL 执行到 V18，写入旧去重记录，再执行 V19，确认旧记录回填全零指纹且旧应用省略 `event_fingerprint` 的写入仍兼容。该批次未启动 Kafka、Redis、Elasticsearch 或 MinIO，因此不能替代完整多服务集成验收。

同日 `OutboxFailureIntegrationTest` 以 MySQL 8.4 真实容器通过 1 项测试，验证 Kafka 不可用时内容写入仍保留 `FAILED` Outbox 事件并可继续补偿；该批次仍未执行完整 Gateway/多服务链路。

2026-08-02 补充单基础设施可靠性回归，共 9 项真实测试通过：`FeedServiceRedisIntegrationTest` 使用 Redis 8.2 验证兴趣、热度、关注信号和曝光降权；`InteractionPipelineIntegrationTest` 使用 MySQL 8.4 与 Redis 8.2 验证点赞/收藏幂等、持久化计数、Outbox 写入和 Redis 热度只变化一次；`KafkaContainerSmokeTest` 使用 Kafka 4.1.1 验证真实 topic、producer 和 consumer group；`NotificationKafkaReliabilityIntegrationTest` 使用 MySQL 8.4 与 Kafka 4.1.1 验证 poison event 重试、DLT 投递、有效事件消费和重复通知去重。该批次覆盖了真实 MySQL、Redis、Kafka 的关键切片，但仍未覆盖 Elasticsearch、MinIO 或完整 Gateway/多服务 E2E。

同日 `FileUploadIntegrationTest` 以 MySQL 8.4 + MinIO 真实容器通过 1 项媒体测试：预签名 PUT 上传真实 PNG 魔数，完成校验后媒体保持 `PENDING_REVIEW`、公开 URL 为空，并确认申请/完成幂等记录写入载荷指纹。至此本轮单基础设施真实回归累计 10 项；Elasticsearch、完整 Gateway/多服务 E2E 和目标主机性能仍未验收。

随后 `SearchIndexIntegrationTest` 以 Elasticsearch 9.2.2 真实容器（256MB 堆、单节点、禁用 ML）通过 4 项测试：发布事件写入真实索引后，文档详情和关键词搜索均可读；乱序旧事件不能覆盖新版本，内容驳回后会 refresh 索引并从关键词搜索中立即消失；最新排序使用 `search_after` 游标，第一页之后插入更晚内容不会造成重复，游标复用到不同筛选条件会被拒绝；相关性排序保留 `_score`、时间和 ID 的稳定 tie-breaker。连同 V19 投影迁移测试，本轮真实基础设施回归累计 20 项，五类基础设施均已有切片证据；完整 Gateway/Compose E2E、跨服务事件追平、性能和恢复演练仍未验收。

最后 `ContentOutboxIntegrationTest` 以 MySQL 8.4 + Kafka 4.1.1 真实容器通过 3 项测试：内容、标签、Outbox 在同一事务写入后可正常发布到 Kafka，重复评论/互动投影保持一次性计数。至此本轮单基础设施真实回归累计 23 项；完整 Gateway/Compose E2E、跨服务事件追平、性能和恢复演练仍未验收。

随后推荐消费者租约回归覆盖 `dc-common`、`dc-content`、`dc-recommend` 共 170 项纯单元/H2 测试，行/分支覆盖率为 `78.86%/72.29%`；`recommend_consumer_dedup.lease_token` 迁移和过期 worker 状态隔离已验证。该结果仍不替代真实 Kafka、Redis 和 Compose 故障恢复演练。

认证边界回归新增 5 项测试，覆盖输入规范化、禁用账号、随机 Refresh 轮换、双提交 Cookie、OAuth 登录和 Bearer 解析；`AuthService` 行/分支覆盖率提升到 `96.06%/95.71%` 并加入关键状态机门禁。当前 38 项 `dc-auth` 测试通过，但真实 Cookie、Gateway 和数据库链路仍以 Compose E2E 为准。

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

当前工作批次补强了 `web/tests-real/review.spec.ts`：审核通过后的真实详情现在继续验证正确答题会解锁解析并获得 20 分，同时使用浏览器实际生成的 `Idempotency-Key` 重放答题请求，确认返回 `duplicated=true` 且积分不重复。该用例已通过 `npx playwright test -c playwright.real.config.ts tests-real/review.spec.ts --list` 编译/发现检查，但尚未在 Compose 中执行；本机执行 `REAL_E2E_PULL_POLICY=never bash scripts/e2e/run-real-review.sh` 仍因 Docker 配额 `2068439040` bytes 低于 `2147483648` bytes 门槛而在打包前阻断。同期 `npm run build` 和 112 项拦截式 Playwright 回归通过；这些结果不替代真实审核 E2E。

同一工作批次的 `ReliableOutboxWorkerTest` 定向回归为 11/11 通过，新增覆盖无 `outbox_event` 表时定时 Worker 安全空转、人工重放明确失败、并发定时/人工执行互斥、超长失败原因截断，以及 Kafka Future 抛出 `InterruptedException` 时保留 `FAILED` 可重试状态并恢复线程中断标志。随后完整纯单元/H2 回归为 365 项、0 failures/errors/skipped，聚合行覆盖率 76.47%、分支覆盖率 68.83%；`ReliableOutboxWorker` 行覆盖率 100%、分支覆盖率 95.45%。该结果排除了 Testcontainers、真实 Compose E2E、性能和恢复演练。

同一工作批次的离线交付门禁均通过：`test-docker-preflight.sh`、`test-assert-coverage.sh`、`test-validate-production-env.sh`、`test-backup-restore.sh`、`test_assert_production_resource_budgets.py`，以及生产/基础设施 Compose `config --quiet`。当前 Windows 环境未安装 Helm，故本轮没有把 Helm lint 记为通过；CI 仍需在带 Helm 的 Runner 上执行该门禁。

随后新增 `SearchServiceCursorContractTest`，用模拟 Elasticsearch 命中验证稳定 `search_after` 游标、第二页无重复、旧索引主题/内容类型推断，以及第一页成功后 Elasticsearch 故障时不得切换到 Seed 数据；事件契约还补充审核通过完整载荷索引并刷新、普通更新变为非公开时删除索引、重复上行事件不触碰 Elasticsearch，以及索引/状态库双重失败时保留原始异常并附加状态异常。重建契约通过两页 20+1 条内容验证全量读取、批量保存和最终 refresh。搜索模块非容器定向回归为 35/35 通过；`SearchService` 行覆盖率 92.37%、分支覆盖率 76.47%，`SearchEventCoordinator` 为 100%/92.31%。该结果不包含真实 Elasticsearch 容器；完整纯单元/H2 聚合基线仍以 365 项、76.47%/68.83% 为准。

2026-08-02 在申诉一致性补强后，`dc-admin` 定向回归为 22 个测试、0 failures/errors/skipped；其中 `AdminServiceStateTest` 20 个测试通过。新增用例验证申诉处理的同一 `Idempotency-Key` 不能更换审核理由、审核结论或审核员，且重放不会重复写入投稿状态、申诉状态、审核记录、审计日志或 Outbox；审计序列化故障还会整体回滚申诉、投稿、复审任务、审核记录、审计日志、Outbox 和幂等记录，依赖恢复后可使用原键重试。带 JaCoCo 的管理模块定向报告为 64.84% 行覆盖率、64.84% 分支覆盖率；该结果只证明管理模块状态测试通过，不替代完整 CI 和真实数据库/多服务验收。

随后完整纯单元/H2 回归为 374 个测试、0 failures/errors/skipped；14 份 JaCoCo 报告的聚合行覆盖率为 77.26%、分支覆盖率为 70.58%，覆盖报告完整性与禁止 skipped 门禁均通过。该命令明确排除了 `*IntegrationTest`、`DockerPreflightTest` 和 `KafkaContainerSmokeTest`，因此仍不能替代 Testcontainers、真实 Compose E2E、性能或正式恢复演练。

管理写入口收口后，`AdminService` 不再暴露无 `Idempotency-Key` 的审核方法，也删除了使用固定审核员 ID 和默认理由的遗留通过/驳回入口；公开的通过、驳回、升级复审和要求修改方法全部要求审核员身份、理由与幂等键。状态测试确认升级复审和要求修改的空理由会整体回滚幂等声明且不改变任务状态。完整纯单元/H2 回归仍为 374 个测试、0 failures/errors/skipped，聚合覆盖率提升为 77.32%/70.69%；`AdminService` 本身为 66.67% 行覆盖率、66.29% 分支覆盖率。该批次未执行容器测试。

管理事件生产契约随后增加了直接回归：审核通过写入的 Outbox 行必须使用 `POST_AUDIT` 聚合类型，数据库列中的 `event_id/topic/aggregate_id` 必须与统一 `EventEnvelope` 一致；事件必须能被下游同款严格解析器按 `audit.completed + dc-admin` 契约解析，并携带版本 1、文章/接收者、审核动作和 `PUBLISHED` 状态。全仓纯单元/H2 基线上升为 375 个测试、0 failures/errors/skipped，聚合覆盖率保持 77.32%/70.69%，覆盖完整性和禁止 skipped 门禁通过。该测试验证序列化后的真实 Outbox 载荷，但仍不替代 Kafka 容器投递与消费者 E2E。

公共 `EventEnvelope` 现在在生产端构造时拒绝空事件 ID、空/带首尾空格的 type、producer、aggregateId 和非正版本，并复制 payload 防止调用方后续修改已入队事件；`null` payload 明确归一为空对象。新增 2 项 common 事件不变量测试，随后完整纯单元/H2 回归为 377 个测试、0 failures/errors/skipped，聚合行/分支覆盖率为 77.40%/70.82%，覆盖报告与禁止 skipped 门禁均通过。该校验只提前阻断本地无效事件，仍需真实 Kafka 投递验证跨服务行为。

媒体公开闭环补充了 5 项 `FileControllerTest`：不支持的扩展名在触碰对象存储前被拒绝，过期上传凭证不发起 HEAD，MIME/文件头不匹配会删除私有对象，合法 PNG 才能完成，已完成上传的重放直接返回持久化结果且不再依赖 MinIO 可用性。全仓纯单元/H2 回归为 382 个测试、0 failures/errors/skipped，聚合行/分支覆盖率提升至 78.19%/71.86%；`dc-file` 从原先 39.91%/21.74% 提升到 60.00%/45.30%。这些测试仍不替代真实 MinIO 上传和跨服务审核公开 E2E。

媒体权限状态矩阵随后补充单条和批量公开查询：只有 `APPROVED + COMPLETED + PUBLISHED` 关联才可返回，草稿、未审核、上传中和无效/重复 ID 均被过滤或拒绝。完整纯单元/H2 回归为 384 个测试、0 failures/errors/skipped，聚合行/分支覆盖率为 78.59%/72.24%；`dc-file` 保持 69.78%/53.85%。覆盖率和 skipped 门禁通过；真实 MinIO、预签名上传和审核后公开仍待容器 E2E。

2026-08-02 前端回归重新执行：`npm run build` 成功，四个 Playwright project（Chromium、laptop、tablet、mobile）共 112/112 通过，耗时约 30.5 秒。`scripts/e2e/test-docker-preflight.sh` 的离线 fixture 门禁通过；当前实际 Docker 内存为 2,068,439,040 bytes，`run-real-auth.sh` 按设计以退出码 2 阻断并提示至少需要 2,147,483,648 bytes，未将资源不足误记为认证业务通过。该结果仍不替代真实 Compose E2E。

同批部署/恢复门禁复验通过：生产环境变量校验、备份/恢复/迁移校验、生产资源预算 6/6、k6 脚本契约 3/3 均成功；注入专用测试环境变量（不提交、不用于生产）后 `docker compose -f docker-compose.prod.yml config --quiet` 通过。k6 契约和 Compose 语法不等于目标主机性能、Kafka/ES/MinIO 真实恢复或 RTO 证据，后续仍需在 Linux 4 vCPU/16GB 主机和满足内存的 Docker 上执行。

2026-08-02 GitHub OAuth 边界回归扩展到 11 项：缺失回调参数不会消费一次性 state；无效授权码返回安全的认证失败；5xx/网络故障返回安全的依赖失败；null/空白/未验证邮箱会被过滤；邮箱、展示名和 login 会规范化；空 token、空 profile、非正 GitHub ID 和缺失身份名称均不会创建账号。`dc-auth` 共 44 项测试通过，`GitHubOAuthService` 行/分支覆盖率为 96.26%/90.00%，并纳入关键状态机 85% 分支门禁。该证据使用本地模拟 Provider，不替代真实 GitHub OAuth E2E。

同日真实 MySQL 8.4/Flyway 回归发现并修复 `dc-content` 重复 V19 迁移：内容投影指纹保留为 V19，推荐消费者 lease token 顺延为 V20。清理陈旧构建产物后，20 个迁移均通过校验，V1→V18 与 V19→V20 两阶段升级成功。CI 新增不依赖 Docker 的迁移版本唯一性门禁，防止重复或 Flyway 等价版本再次进入运行时。

随后全仓非容器覆盖回归为 399 个测试、0 failures/errors/skipped，14 份 JaCoCo 报告的聚合行/分支覆盖率为 79.43%/74.14%；13 个关键认证、事件、内容、通知、推荐、搜索和用户状态类全部通过 85% 分支门禁。该命令明确排除了 `*IntegrationTest`、`DockerPreflightTest` 和 `KafkaContainerSmokeTest`；上面的单条真实 MySQL 迁移测试是独立证据，二者都不替代四套真实 Compose E2E。

## 排查建议

- 拉取镜像慢：先手动执行 `docker pull`。
- 端口冲突：Testcontainers 默认使用随机宿主机端口，通常不受本地 Compose 端口影响。
- Elasticsearch 启动慢：确认 Docker 可用内存不低于 4GB。
- MinIO 上传失败：确认 endpoint、access key、secret key 和 bucket 配置一致。
- Windows 路径问题：优先在仓库根目录执行 Maven 命令。
