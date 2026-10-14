# 2026-10-14 搜索流式游标重建

起始提交 `751ab1991ec975bed809009398fd8c014b8b1602`，本记录与实现一起提交。不是生产规模、在线迁移或持久任务验收。

## 改动

搜索重建改为消费内容服务已有的 `created_at DESC, id DESC` 游标 API，每批 20 条并逐批执行带业务版本保护的 ES 更新。移除 100 页截断和全量结果驻留内存；整批校验版本、状态、排序和游标后才写入。分页元数据缺失、排序回退、重复游标、依赖失败均返回错误。

## 本机结果

- 定向组 43 项通过，0 失败、错误、跳过。
- 真实依赖组 39 项通过，0 失败、错误、跳过。两组有重叠，不相加。
- `SearchReindexPagingTest`：22 项；0/1/1999/2000/2001/10000 条遍历，上一批必须先写完再请求下一批；错误元数据、错误游标、重复/重叠页、无效版本、来源与目标故障、从头重试。该测试的 HTTP 来源及 ES 操作使用夹具/替身。
- `ContentReadPathIndexIntegrationTest`：真实 MySQL 8.4、完整 Content/File 迁移；实际生产查询遍历 10000 条，ID 与数据库排序逐条一致，包含同时间戳跨页，业务版本与摘要保留。另一个查询计划检查通过。
- `SearchCausalOrderIntegrationTest`：真实 MySQL/Elasticsearch 9.2.2；新增 2001 条重建测试使用 HTTP 来源夹具、真实 ES 条件写入及读取，逐条核对 ID、标题、摘要、状态与版本，较新墓碑不被旧快照复活。已有因果反例一并通过。
- `SearchLeaseIntegrationTest` 的 4 项真实 MySQL 租约回归通过。
- 扩展 Content 查询测试时发现测试只初始化 Content 迁移、缺少 File 的 `upload_status` 列；改为加载部署时相同的独立 File 迁移历史后通过，没有修改或伪造历史迁移。

日志位置：`E:\xuexi\devcircle\artifacts\local\goal-search-paging\focused-tests.log` 与 `integration-tests.log`。缓存和临时数据使用 E 盘，Docker 数据留在 D 盘。容器在测试结束后由 Testcontainers 回收；本轮没有执行长期资源验收。

复现真实依赖组（需先确保 Docker 可用，否则 Testcontainers 会跳过，不能验收）：

```powershell
$env:TEMP='E:\xuexi\devcircle\artifacts\local\goal-search-paging\tmp'
$env:TMP=$env:TEMP
$env:MAVEN_OPTS='-Xmx768m -Djava.io.tmpdir=E:/xuexi/devcircle/artifacts/local/goal-search-paging/tmp'
.\mvnw.cmd -B '-Dmaven.repo.local=E:/xuexi/.cache/devcircle-maven' '-Djava.io.tmpdir=E:/xuexi/devcircle/artifacts/local/goal-search-paging/tmp' -pl dc-content,dc-search -am '-Dtest=ContentReadPathIndexIntegrationTest,SearchCausalOrderIntegrationTest,SearchLeaseIntegrationTest,SearchReindexPagingTest,SearchServiceDependencyFailureTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

## 尚未完成

保持停写维护窗口。当前请求同步执行，客户端超时不能证明服务器已经停止；`indexed` 是来源提交数，不能取代最终对账。中途失败可从头安全重试，尚无持久检查点、互斥任务、断点续跑或源/索引全量差异报告。这些属于下一交付包 1b。
