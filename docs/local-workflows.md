# 本机开发与验收

无需租服务器。先在自己的电脑完成可重复的核心业务验证，再使用仓库 CI 做独立复验。本机测试不是生产部署或容量认证。

## 三个入口

在仓库根目录执行 `./scripts/dev/local.ps1 <模式>`；Linux 可执行 `python3 scripts/dev/local.py <模式>`。

| 模式 | 工作内容 | 通过条件 |
|---|---|---|
| `quick` | 运维脚本单测、解谜/搜索租约/Outbox/文件权限的精选 Java 测试、前端类型检查与构建 | 全部命令成功；不启动容器，不代表集成验收 |
| `up` | 构建 4 个 Java 应用和静态 Web、启动核心切片、执行真实 API 业务链路 | Compose 健康检查和业务反例通过 |
| `verify` | 构建一次，默认两轮启动；轮间停止但保留卷，检查上一轮积分数据；并发答题、权限反例、资源采样 | 9 个服务完整健康，采样无 OOM/异常退出/重启，Docker OOM 事件为空，业务断言通过，验证期间源码不变 |
| `accept` | 运维单测、全量 Java 覆盖率与零跳过检查、关键状态覆盖率、前端审计/构建/浏览器测试、四组真实 E2E | 任一命令失败即停止，所有步骤成功才报告通过 |
| `status` / `down` | 查询状态 / 停止本项目容器并保留卷 | 操作成功；不是业务验收结论 |

`verify -Cycles 3 -HoldSeconds 60` 可增加轮数和观察时间（2–5 轮，每轮 10–300 秒）。`up` / `verify` 构建前会停止已有核心切片并保留卷，避免 Java 服务与构建同时占用内存。`verify` 成功后服务保持运行；不用时执行 `down`。失败后查看日志并执行 `down` 释放资源。不要把快测、Maven 跳过容器的结果标为完整验收。

`accept` 是完整的软件测试入口；镜像签名/发布、生产环境校验、恢复演练及长期压力测试仍由 CI 和相应运维入口负责。执行前停止核心切片；测试使用独立临时容器及 E2E 项目。现有 E2E 会清理自己的临时数据卷，核心切片数据卷不受影响。首次执行需安装 Playwright Chromium（在 `web` 中运行 `npx playwright install chromium`）。Windows 的完整 E2E 需要 Git Bash。

## 资源与访问边界

- 复用 `docker-compose.e2e.yml` 的 review profile，叠加 `deploy/compose/docker-compose.local.yml`，项目名固定为 `devcircle-local-core`。
- 9 个容器：MySQL、Redis、Kafka、MinIO、Identity、Content aggregate、Admin、Gateway、静态 Web。内存上限总和 2112 MiB；不含构建工具、Windows、WSL、Docker VM。不要同时运行完整 Demo 和验收。
- 页面 `127.0.0.1:5512`，API `127.0.0.1:28080`，MinIO `127.0.0.1:29000`；仅绑定本机回环。保留固定的本地测试凭据，不能对外部署。
- Docker 可用内存须大于容器预算加 512 MiB，主机可用内存须至少 1 GiB。检查只是启动下限，不保证长时间运行无 OOM。内存不足时先停止其他开发负载，勿强开全栈。
- 启动和快测要求工作区所在盘至少剩余 4 GiB，Windows 系统盘至少剩余 2 GiB；首次拉取全套依赖还需额外空间。Docker 虚拟磁盘可能位于其他盘，入口的主机磁盘检查不代替虚拟磁盘容量管理。2026-10-12 本机曾因系统盘耗尽而中断 Elasticsearch 镜像获取，该次完整验收明确失败。
- 核心切片不含 Elasticsearch、推荐、独立评论服务和监控栈；Identity 在此测试拓扑嵌入 Notification，与默认生产部署不同。
- MinIO 首次从固定官方源码构建测试镜像，网络及 Go 构建耗时不计入稳态资源。已有同名镜像时复用。完整 Demo/生产旧镜像迁移仍待完成。

## 结果如何复核

每次执行生成 `artifacts/local/<UTC时间>-<模式>/report.json`，命令日志同目录。`verify` 另存每轮 `resources-N.json`，包含时间、容器内存/CPU、健康状态、镜像 ID、OOM 标记、重启次数和主机可用内存。

报告记录提交 SHA、工作区是否修改、源码文件摘要及结束摘要。业务验证使用合成账号，只保存账号 ID、内容 ID 和断言名称，不保存访问令牌。重启后核对数据库中解谜记录、积分和解出人数，避免只检查容器“正在运行”。

`peak_sampled_total_mib` 是采样时容器内存之和的峰值，不是主机总内存，也不是采样间隙的绝对峰值。“无 OOM”仅覆盖本次观察窗口，不能推导长期吞吐或生产可靠性。
