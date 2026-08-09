# Linux 验收门禁与解谜策略非容器证据（2026-08-10）

## 证据身份

- Commit: `5bbbb268a14c37b28c24eef5587643252a15ef49`
- Branch: `overhaul/mysticverse-production`
- Host: Windows 11，Java 21
- 范围：Linux 性能验收 fail-closed 门禁、解谜答案关键词策略拆分及非容器回归

## 实现结果

Linux 验收执行器不再把“查询命令成功”解释为指标通过：

- Gateway Prometheus 查询与 recording rule 统一使用 `job="devcircle", service="dc-gateway"`；
- `promtool query instant` 必须返回且只返回一个有限数值，空值、NaN、Inf 和多序列均失败；
- availability 必须不低于 0.99，读 P95 不高于 0.3 秒，写 P95 不高于 0.6 秒；
- Outbox complete 必须为 1，DEAD 与 blocked 必须为 0；
- `/api/admin/ops/health` 必须返回 `outboxScope=FLEET`、`outboxComplete=true`、无不可用来源，并且所有来源状态为 `UP`；
- 原始 Prometheus 输出和结构化 `prometheus-summary.json` 同时保留，阈值写入 `host.json` 和摘要。

解谜答案关键词由 `AnswerKeywordPolicy` 负责：

- 使用 Jackson 读取 JSON 数组，不再使用 `replace` 与 `split` 手工解析；
- 空值与空白关键词被过滤；
- 非法 JSON 返回明确的 `SYSTEM_ERROR`，并保留原始异常作为 cause；
- 归一化、同义词扩展与决定性关键词匹配从 `DeductionService` 中移出；
- 保留原有构造器兼容现有聚焦测试和嵌入调用。

## 执行结果

后端非容器回归：

```powershell
.\mvnw.cmd -B clean verify -Pcoverage `
  "-Dtest=!*IntegrationTest,!DockerPreflightTest,!KafkaContainerSmokeTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false"
```

- Reactor: 19/19 success（根项目 + 18 个子模块）
- Surefire: 110 suites，478 tests，0 failures，0 errors，0 skipped
- JaCoCo: 17 reports，line 81.31%，branch 74.18%
- 关键状态门禁：14 个类全部达到 branch 85% 以上

运维与脚本门禁：

```powershell
python -m unittest discover scripts/ops -p 'test*.py'
bash -n scripts/performance/run-linux-acceptance.sh
bash -n scripts/ops/finalize-linux-recovery-evidence.sh
```

- Ops Python: 49 tests，全部通过
- Linux 性能验收与恢复 finalizer Shell 语法通过
- `git diff --check` 通过，仅保留 Windows LF/CRLF 提示

## 未完成证据

当前 `docker info` 仍超时，因此本证据不证明：

- Testcontainers 或 `ContentRuntimeFlywayIntegrationTest` 已启动真实 MySQL 8.4；
- 生产 Compose、真实 Auth/Content/Review/Community/Search Recovery E2E；
- 目标 Linux 主机的 40 读 + 10 写 VU、三分钟 P95、资源曲线或 Kafka Lag；
- MySQL、MinIO、Elasticsearch、Kafka 的完整恢复与正式 RTO/RPO；
- Helm 集群部署或 Registry-backed Cosign、SBOM、provenance 验签；
- 30 天 SLO。

Linux 验收执行器现已具备 fail-closed 门禁，但只有在目标 Linux 主机对当前发布 SHA 实际运行成功后，才能产生性能与可靠性通过结论。
