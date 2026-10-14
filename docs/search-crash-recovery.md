# 搜索消费者执行中崩溃验收

入口：

```powershell
python -X utf8 scripts/ops/rehearse_search_crash.py --maven-repo E:\xuexi\.cache\devcircle-maven
```

默认构建生产 Discovery 程序；`--runtime standalone` 验证回滚用独立 Search 程序。脚本创建随机命名的隔离项目，只启动 MySQL、Kafka、Elasticsearch 和目标应用四个服务。工作盘、Docker 数据盘和 Maven 缓存遵循迁移演练的 D/E 盘与空间预检，主机可用内存不足 1 GiB 时不会启动服务。所有输出留在 `artifacts/local/search-crash/`，结束后停止本项目并保留卷和证据。

首次运行需先取得依赖镜像：`docker compose -f docker-compose.e2e.yml --profile search-recovery pull mysql kafka elasticsearch`。CI 显式执行此步骤，演练启动本身不替换已有镜像。

构建完成后、启动镜像前，执行器逐个核对可执行 JAR 内的项目依赖与本次 reactor 产物的 SHA-256，避免当前源码与实际运行代码不一致。父 POM 强制重新生成普通 JAR，再交给 Spring Boot 打包；这同时修复了只更新依赖、应用自身类文件不变时重复使用旧可执行 JAR 的问题。参见 Maven 的 [forceCreation 说明](https://maven.apache.org/plugins/maven-jar-plugin/jar-mojo.html#forceCreation)。报告记录实际 JAR、项目依赖和镜像摘要；提供旧镜像进行反例验证时不冒充当前源码构建。

## 真实注入点

先完成版本 1 的合成事件并确认 Kafka 位点为 1。暂停 Elasticsearch，发布版本 2，观察到数据库已提交 `PROCESSING` 及匹配的有效租约后，SIGKILL 目标应用，确认退出码 137、非 OOM，数据库未完成且位点仍为 1。

恢复 Elasticsearch 时，此前进入网络缓冲区的请求仍可能写入索引。因此允许索引保留版本 1，或已变为版本 2；不把任何一个结果当作数据库消费完成的证明。报告保存实际索引结果。重启应用后先读取 Kafka 位点，再读取数据库状态，避免把跨系统读取时间差误判为提前确认。

## 必须通过的断言

1. `PROCESSING` 或 `FAILED` 事件不能对应已确认的位点 2；出现即失败。
2. 五分钟事件租约自然到期后，同一事件重新取得执行权；不直接更改租约时间，不重置 Kafka 位点。
3. 恢复后状态为 `COMPLETED`、attempts=2、业务水位=2、租约释放，Kafka 位点=2；索引只有一份正确的版本 2 文档。
4. 租约争用不得进入死信队列；重复事件、删除后旧更新重放不能复活文档。
5. 缺少业务版本的坏消息仍经过有限重试进入 DLT，不能因为新增租约策略而无限重试所有错误。
6. 最终四个服务运行且无 OOM；保存镜像标识、源码提交、执行器摘要、注入前后租约、文档、位点及阶段资源采样。

## 处理策略与边界

搜索协调器只把 `COMPLETED` 认作已处理重复。尚未完成的同一事件、正在占用聚合租约的其他事件都抛出租约争用异常；共享 Kafka 处理器只对该明确异常持续退避，其他异常保留原有限重试/DLT 策略。实现使用 Spring Kafka 的[按异常选择退避策略](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)。事件租约仍为五分钟，聚合租约仍为六十秒，本修复不靠缩短租约让验收通过。

争用期间该分区会等待，持续积压需要观察并诊断；不能手动确认消息来掩盖未完成状态。演练使用合成 Kafka 事件，证明消费者进程崩溃后的状态闭环，不证明整站备份恢复、所有资源权限或生产可用性。

升级前若存在历史 `PROCESSING` 记录，必须核对它对应的 Kafka 位点及投影。旧程序已经错误确认的消息不会因新程序上线或租约到期自动重投，需要依据仍保留的原始事件进行受控重放并逐条对账；不能把状态直接改成 `COMPLETED`，也不能把一次全量索引重建当作消费审计已经修复。本演练中的历史失败夹具独立保留，不触碰用户业务数据。

复现旧镜像时可额外提供 `--image-id sha256:<完整64位摘要>` 和对应 `--runtime`；仅允许本机不可变镜像，不拉取浮动标签。此时报告明确区分执行器所在提交与运行镜像，不能把当前 checkout 的 SHA 当作旧镜像来源。
