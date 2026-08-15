# 2026-08-15 聚合部署 Docker 与恢复证据

## 结论

本轮在 Windows 11 + Docker Desktop Linux Engine 上恢复了当前聚合拓扑的真实容器验证。Docker Client/Server 均为 29.6.2，Engine 可用内存为 6,218,960,896 bytes。验证绑定分支 `overhaul/mysticverse-production`，核心修复提交为：

- `b28e003`：Standalone Content 显式执行 Content/File 双 Flyway history；
- `827b09c`：修复 Recommend 候选加载器的 Spring 构造器选择；
- `01dace0`：恢复聚合 E2E 的安全配置、Kafka profile 和跨平台 Node runtime；
- `9178a62`：支持显式选择 Windows Docker CLI，同时保持 Linux/CI 默认路径；
- `3390021`：Review profile 恢复真实 Kafka/Outbox 作者投影，并在内容详情组合时把 Bearer 委托给 Identity 自行验签。

## 已执行验证

### Testcontainers 与模块集成

- 当前 `3390021` 上执行 `./mvnw.cmd -B clean verify -Pcoverage`，19/19 个 Reactor projects 全部成功，145 份 Surefire XML 共 597 tests，0 failures、0 errors、0 skipped，总耗时 10 分 48 秒。该轮实际启动 MySQL 8.4、Redis、Kafka、Elasticsearch 9.2.2 与 MinIO Testcontainers，并在结束后自动清理。
- 17 份 JaCoCo 报告的聚合行覆盖率为 85.38%、分支覆盖率为 74.38%，通过 CI 的 75%/70% 门禁；认证、Outbox、内容/解谜、通知、推荐、搜索与用户投影共 14 个关键状态类全部通过 85% 分支门禁。
- `dc-content`：104 tests，0 failures/errors/skips；真实 MySQL 8.4、Kafka 和 MinIO 均启动。
- `dc-file`：12 tests，0 failures/errors/skips。
- `dc-comment`、`dc-content-deployment`：`./mvnw.cmd -pl dc-comment,dc-content-deployment -am clean verify` Reactor 全部成功；聚合部署 17 tests 通过。
- `dc-recommend`：52 tests，0 failures/errors/skips；其中 Spring 全上下文使用真实 Redis Testcontainer 启动，捕获并验证了构造器装配修复。
- Content schema 实际迁移到 V23，File 独立 history 实际迁移到 V3，Comment 实际迁移到 V7。

### 真实 Compose E2E

- Auth：1/1 Playwright 通过。验证邮箱注册、Refresh Token 轮换、退出撤销和再次登录；使用真实 Gateway、Identity 与 MySQL 链路。
- Review：1/1 Playwright 通过。验证注册事件经 Outbox/Kafka 创建真实作者投影、草稿投稿、审核理由必填、管理员通过、审核幂等重放、公开正文、未解锁答案保护、认证读者互动状态、正确答题获得 20 分、重复答题不重复计分，以及解锁答案与解析。
- Content：1/1 Playwright 通过。验证注册、Secure Refresh Cookie、草稿五秒自动保存、重载恢复、乐观锁冲突不覆盖服务端版本，以及显式加载服务端版本。
- Community 正常链路：1/1 Playwright 通过。验证资料同步、关注幂等、通知恰好一次、取消关注与推荐投影收敛。
- Community Kafka retry：Kafka 停机时 `follow.changed` Outbox 进入 `FAILED`，通知和推荐投影保持 0；原容器恢复后事件自动转为 `PUBLISHED`，通知=1、推荐投影=1，同键重放不重复。
- Community Outbox DEAD：首事件达到 `DEAD:2` 后，同 `(topic, aggregateId)` 后续事件保持 `NEW:0`；管理员显式重放后恢复为 `PUBLISHED/PUBLISHED`，五域健康从 complete 变 partial 后再次 complete，重放审计与幂等断言通过。
- Search retry：Elasticsearch 停机时公开搜索明确失败，Outbox 保持 `PUBLISHED`、消费记录为 `FAILED`；ES 恢复后第 13 次尝试转为 `COMPLETED`，旧事件未回退索引水位线。
- Search DLT：最大重试 1 时事件进入 DLT offset 0；管理员按原 topic/partition/offset 精确重放后第 3 次尝试转为 `COMPLETED`，重复重放不追加消息，旧事件仍被忽略。

### 前端构建与浏览器回归

- `npm run build` 通过，主 JavaScript 产物 gzip 约 90 KB。
- 使用独立端口 `15510/15511` 执行完整 Playwright：主流程 124/124、Notification WebSocket 12/12，总计 136/136；覆盖 1440、1024、768 与 390 px。
- `npm audit --audit-level=high --registry=https://registry.npmjs.org` 返回 `found 0 vulnerabilities`。默认 npmmirror 不支持 npm audit API，因此审计证据明确使用 npm 官方 Registry。

### 配置与运维门禁

- `scripts/ops`：83 Python tests 通过。
- `scripts/e2e/test-docker-preflight.sh` 通过。
- `scripts/ops/test-backup-restore.sh` 通过，覆盖 Manifest、产物校验和、篡改阻断、恢复后内容基线和旧格式显式兼容。
- `infra`、`demo`、默认 E2E、Community E2E 和生产 Compose 均通过 `config --quiet`。
- 所有 E2E/生产运维 Shell 通过 `bash -n`，工作区通过 `git diff --check`。

### 真实备份恢复演练

- 在 `36afeb1` 上使用全新 MySQL 8.4.6 与 MinIO 卷写入 180 条公开内容和对象标记，真实执行 `backup.sh`，随后篡改标题、删除第 180 条内容并改写对象，再执行 `restore.sh`。
- 备份耗时 4.35 秒，恢复耗时 4.39 秒；三项 `SHA256SUMS`、180/180 数量、内容哈希 `ee55895f0aeeba0e1de651defe2fa4514d0f271035b745575cdb0df145b32ee9`、首尾标题与 MinIO 对象均恢复一致。
- 演练结束后容器、网络、卷、临时凭据与备份产物全部清理。详细步骤和证据边界见 [本地备份恢复演练记录](../recovery-rehearsal.md)。

## 实际发现并修复

1. Standalone Content 查询 File 所有的 `upload_status`，但未执行 File history，真实 MySQL 报 `Unknown column 'm.upload_status'`。现由两个有序 Flyway initializer 分别维护 `flyway_schema_history` 与 `flyway_schema_history_file`。
2. E2E 生产模式仍配置不安全 Cookie，Identity 的生产守卫正确拒绝启动。E2E 现使用 Secure Cookie 和一致的 HTTPS 回调契约；真实浏览器回环链路通过。
3. 无 Kafka 的 Content profile 误启动 Identity Kafka 消费者。消费者、Topic Admin 与 Outbox/Kafka profile 现在同步启停。
4. `RecommendationCandidateLoader` 有两个构造器但生产构造器未显式标记，真实容器启动失败。现已使用明确构造器注入，并由 Spring 全上下文测试覆盖。
5. WSL 的 Linux Docker CLI 与 Docker Desktop Windows CLI 使用不同 daemon 视图，且 Windows CLI 需要路径和环境转换。E2E 现在允许显式 `REAL_E2E_DOCKER_COMMAND=docker.exe`，默认 Linux/CI 行为不变。
6. Review profile 原先关闭 Outbox 且不启动 Kafka，注册事件无法建立真实作者投影，发布后公开详情返回依赖失败。Review 现启用 Kafka 与 Outbox，并在测试中等待 `user.registered` 投影完成；预检脚本会拒绝再次关闭该链路。
7. 已认证详情原先只向 Identity 转发 `X-User-Id`，而 Identity 正确拒绝可伪造身份头，导致关注与互动状态内部查询返回 401。Content 现把原始 Bearer 委托给 Identity 自行验签并派生身份；没有引入万能内部密钥，也没有放宽权限。相关日志只记录依赖路径、traceId、异常类型与消息，不记录 Token。

## 证据边界

本轮已重新执行完整全仓 `clean verify -Pcoverage`、Auth/Review Compose E2E、前端构建、四档 Playwright、npm 官方 Registry 审计，以及当前 SHA 的 180 条内容 MySQL/MinIO 真实备份恢复。尚未执行目标 Linux 4 vCPU/16 GB 下的 50 并发 k6、真实 GitHub OAuth Provider E2E、Helm 集群部署、Cosign Registry 验签，也没有完成包含全部应用、Elasticsearch 重建、Kafka 追平、公网探测和正式 RTO/RPO 的目标主机恢复演练。

Flyway 仍提示其 MySQL 支持矩阵只验证到 8.1，部分已应用迁移还使用 MySQL 已弃用的 `VALUES(column)` 语法。不得修改已应用迁移以消除警告；应以后续向前迁移和依赖升级处理。
