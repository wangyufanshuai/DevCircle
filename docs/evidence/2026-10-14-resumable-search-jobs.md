# 2026-10-14 可恢复搜索任务与进程中断演练

本轮从 `a19d818ab918d8650bf6f3b0eacd0eac2e28f056` 继续，该提交的 [CI 已通过](https://github.com/wangyufanshuai/DevCircle/actions/runs/38512687608)。本记录与新增持久任务实现一起提交。

## 自动测试

- 57 项 Java 定向/真实依赖测试通过，0 失败、错误、跳过。包含 11 项任务状态测试、6 项真实 MySQL 事件租约/任务锁测试，以及真实 ES 条件写入、业务版本、墓碑与索引回归。
- 104 项运维脚本回归通过，包含 6 项可恢复任务驱动测试；验证未知请求结果保留检查点、失败退出、不前进响应停止、凭据不进入回执及 C 盘输出拒绝。
- `SearchReindexJobs` 分支覆盖率 96%（48/50），行覆盖率约 95.24%（100/105）。已加入关键状态类的 85% 分支 CI 门禁。这不是全仓覆盖率结果。
- 真实 MySQL 8.4 复现 NOWAIT 竞争使用 SQLState HY000 / 错误 3572，Spring 未将其分类为锁异常。修正映射后，竞争调用返回任务冲突；不能进入受锁保护的批次或取消正在运行的批次。
- 外部写入后异常的测试证明未确认检查点回滚，重新构造服务仍可读取并从原检查点重试。该故障注入测试与下面的真实进程终止演练分别记录。
- 对账反例包括同版本错误字段、缺失文档、来源在两遍之间改变或减少、多余/无效索引文档。差异返回 FAILED，不自动覆盖相同版本或清空墓碑。

日志：`E:\xuexi\devcircle\artifacts\local\goal-search-jobs\integration-coverage.log`。

## 真实进程演练

- 记录：`E:\xuexi\devcircle\artifacts\local\goal-search-jobs\rehearsal\20261014T130026-9fa1b2c2\report.json`。
- 独立项目：`devcircle-search-migration-55a4bab17fe7`。
- 演练脚本 SHA-256：`f9aaced229981bd1730f411bca2cf99fcc9d5cb43dc6e85f7af23448781d191f`。
- 旧镜像：`sha256:26d9e0ebc5ded09c8c6940e87848d42657d1893dceafb25c59a3c8c91b39b905`。
- 新镜像：`sha256:8a519528812adac7852eddcb64a1a6d74c7d2b825626efce1358c3c145b3dabb`。

真实 MySQL、Kafka、Elasticsearch、网关及旧／新 Java 二进制参与。旧基线两篇内容；切换后添加 19 篇，共 21 篇隔离夹具。

1. 管理员通过真实网关创建任务；匿名访问返回 401，普通用户查询、创建、推进及取消返回 403，数据库任务状态不变。
2. 第一批 20 条提交后持久状态为 COPYING，游标非空。对本次独立项目的搜索容器执行 SIGKILL，其他数据服务保持运行。
3. 重启后 GET 返回完全相同的检查点；重试 revision=0 也返回原检查点，不重复推进。
4. 同一任务继续复制及验证，最终 copied=21、verified=21、revision=4、phase=COMPLETED，复制和验证指纹相同。
5. 无版本消息进入 DLT；重复删除、旧更新重放和重建不复活已删除内容。
6. 导出并真实恢复旧备份，旧二进制只读查询正常且 Kafka 位点不移动；再次恢复新备份后保留切换后的内容与删除状态。

报告 `status=passed`，12 项检查通过，`oomEvents=0`、`oomKilledAtFinalInspect=false`、`containersStopped=true`。Docker 数据在 D 盘，缓存、备份、临时文件与证据在 E 盘。备份和数据卷保留。

## 边界

本次进程终止发生在已提交批次边界；批次中途的异常回滚由独立故障注入测试验证，未把它称为真实进程内随机崩溃测试。任务仍要求内容写入、审核及搜索消费者停止；操作员确认参数不是全局停写开关。完成门禁不自动切换索引或恢复业务写入。本次仍不是生产零损失回滚、整站灾备、长期容量或全部资源权限验收。

操作入口见[可恢复任务](../search-reindex-jobs.md)，完整重演入口见[迁移与回滚演练](../search-migration-rehearsal.md)。

## 命令行真实入口复验与 CI 诊断补强

首轮演练直接使用测试 HTTP 客户端，未调用命令行驱动。后续审查发现驱动遗漏网关写请求必需的 `Idempotency-Key`，会被拒绝。已修正为按任务操作和 revision 生成稳定的键，并补充经过真实 HTTP 连接的驱动回归；没有把替身测试描述为真实网关验证。

第二次完整演练实际以子进程调用 `run_search_reindex.py`：第一批退出码 3，保存 20/21 条检查点；SIGKILL 搜索进程后用同一 UUID 续跑，退出码 0，最终复制和验证各 21 条、指纹一致。报告路径：`E:\xuexi\devcircle\artifacts\local\goal-search-jobs\driver-rehearsal\20261014T131546-83c051eb\report.json`。12 项检查通过，0 OOM，容器已停止。此次脚本 SHA-256 为 `ead33378f2ceb3cc414ddfddca5732d93f907ee4cbda0ce6d551ee1192d3c60a`，驱动 SHA-256 为 `23d370f19b1e38ce71f3557e4ced43b2e069feafd0d814aab22ae23de775366b`。Java 代码未改变；镜像标识见该报告。

修正后的运维回归为 107 项通过，包含 7 项驱动测试与失败诊断测试。日志：`artifacts/local/goal-search-jobs/ops-tests-driver-fix.log`。

提交 `a4f415f` 的 [首次 CI](https://github.com/wangyufanshuai/DevCircle/actions/runs/38515439493) 后端及覆盖率、前端、真实浏览器/业务 E2E 通过，但 `local-core` 第二轮出现 HTTP 500，使整体 CI 失败。旧验收器缺少请求定位与服务日志，无法确认根因。现在 5xx 明确记录方法和路径并立即失败，不记录令牌、请求体及查询参数；退出前保存本项目的有界容器日志，采集失败也不能覆盖原错误。未增加重试来隐藏该故障。

补强后 Windows 本机两轮 `local.py verify` 通过，验证期间源码不变，各轮保留数据与业务反例通过且无 OOM/异常重启；报告：`E:\xuexi\devcircle\artifacts\local\20261014T052350848364-verify\report.json`。该结果没有复现远端 500，不能宣称已经修复其运行时根因。后续提交仍需重新核对 CI，并在再次出现时依据新增诊断定位。
