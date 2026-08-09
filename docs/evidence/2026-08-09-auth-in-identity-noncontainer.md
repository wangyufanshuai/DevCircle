# Auth-in-Identity 非容器覆盖率证据（2026-08-09）

## 证据身份

- 提交：`5821178e1456451d48dd28a3c8bab481fdf98142`
- 执行窗口：`2026-08-09T10:54:25.988Z` 至 `2026-08-09T10:56:35.711Z`
- 环境：Windows 11 家庭版（amd64）、Eclipse Temurin JDK 21.0.7、Maven Wrapper 3.9.11
- 测试运行器：Maven Surefire 3.2.5；命令未覆盖 `forkCount`，使用项目/Surefire 默认分叉配置

## 可复跑命令

```powershell
.\mvnw.cmd -B clean verify -Pcoverage `
  '-Dtest=!*IntegrationTest,!DockerPreflightTest,!KafkaContainerSmokeTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false'

$env:MIN_LINE_COVERAGE='75'
$env:MIN_BRANCH_COVERAGE='70'
& 'D:\86137\Git\bin\bash.exe' scripts/ops/assert-coverage.sh
python scripts/ops/assert-critical-state-coverage.py
```

## 本轮结果

| 检查 | 结果 |
| --- | --- |
| Maven Reactor | 19/19 projects 成功（根项目 + 18 个子模块） |
| Surefire | 107 份本轮 `TEST-*.xml`，471 tests，0 failures，0 errors，0 skipped |
| JaCoCo 聚合 | 16 份报告；line 81.19%，branch 73.89%；75%/70% 门禁通过 |
| 关键状态类 | 14/14 通过 85% 分支门禁；最低为 `SearchEventCoordinator` 87.50%，`ReliableOutboxWorker` 为 95.45% |
| Maven 退出状态 | `BUILD SUCCESS`，exit code 0 |

`clean` 在执行测试前删除各模块旧 `target`，因此以上 Surefire XML 和 JaCoCo XML 均由本轮命令重新生成。统计直接读取本轮 XML；没有把历史报告或终端缓存混入结果。

## 证明边界

这是一轮 Windows 非容器回归。命令明确排除了 `*IntegrationTest`、`DockerPreflightTest` 和 `KafkaContainerSmokeTest`，因此不证明 Testcontainers、真实 Docker Compose、真实 GitHub OAuth、目标 Linux 性能、备份恢复、Helm 集群部署或 Cosign Registry 验签通过。`dc-identity` 的测试覆盖内嵌认证运行时和 H2/Flyway 写入路径，但这只是 Auth 迁入 Identity 的第一阶段证据，不代表 `dc-user`、`dc-interaction`、`dc-notification` 已完成进程内聚合。
