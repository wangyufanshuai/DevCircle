# 搜索消费者执行中崩溃与增量打包修复

本轮基于 `ae14d0ad8580f556a7b17cea81b0faf009c2092e` 的工作树修改；该基线 [CI 已通过](https://github.com/wangyufanshuai/DevCircle/actions/runs/38520331388)。下面的本机镜像来自未提交工作树，报告中的基线 SHA 不能单独代表运行代码；以镜像、JAR 和执行器摘要辨识实际产物。本修复提交的远端 CI 需另外核验。

## 真实失败与修复

旧独立 Search 镜像 `sha256:d4fb9ed51d9b2a745fbda3eecd1f48dc4636d5a5b7f8851c5ed8a255391a171c` 在进程取得租约、尚未完成数据库状态更新及位点确认时被 SIGKILL。重启后数据库仍是 `PROCESSING / attempts=1 / watermark=1`，Kafka 位点却由 1 前进到 2，演练正确失败。记录：`artifacts/local/search-crash/20261014T141851-f95f6d8b/report.json`。

原因是协调器将尚未完成且未过期的相同事件当作重复成功返回。修复后只有已完成记录允许重复成功；正在占用事件或聚合租约时抛出明确的租约争用异常。共享消费错误处理器对此持续退避；其他错误仍有限重试、进入 DLT。五分钟事件租约与六十秒聚合租约均未缩短。

独立 Search 修复镜像 `sha256:063fce4fa871848092e1c772f5521a0eb7050b9e1bfa4291500336b1d3c9c84b` 已通过全部 6 项检查。数据库原租约记录时间为 `06:22:13.441713 UTC`，恢复完成时间为 `06:27:14.403971 UTC`，约 301 秒；最终 `COMPLETED / attempts=2 / watermark=2`，租约释放、位点 2。随后重复投递、删除后旧更新和坏消息 DLT 均通过。记录：`artifacts/local/search-crash/20261014T142105-cec36548/report.json`，执行器摘要 `2f62a28205c96c92f54df2d0643dbf9caf0fbf984a0b1b43ca59ccadf43cd291`。

## 实际部署形态发现的第二个缺陷

首次验证 Discovery 镜像 `sha256:b0d979c7a8cca44b219dcaa370aea48294d340e7f1c20b8ea9662f9e03210c51` 仍提前确认。记录：`artifacts/local/search-crash/20261014T142819-41a9029c/report.json`。核对可执行 JAR 后发现，它仍内嵌旧 Search 和 messaging JAR：

| 依赖 | 错误包内 SHA-256 | 当时 reactor 产物 SHA-256 |
|---|---|---|
| dc-search | `31be50597b159ad13326f8a07df2b8b19d7c0240c75473262d127c4341f464c2` | `145e664ba7b5f53f66ccd9146ed35e43971b792d4593ac7f6517808a3844bb1f` |
| dc-messaging-support | `09b9673b6eb4a6889b886c44eaf3a873315e87a44a45894a7ef8a9b0c83ca1a2` | `69ce57e96c301f2c25bd0f71debd5c07b0fe818b1a5e0728435250013fdb4bad` |

应用自身类未变化，Maven JAR 插件跳过生成普通包，Spring Boot 重打包沿用了之前的可执行包。父 POM 现在设置 `forceCreation=true`，保证重打包输入新鲜；新增检查逐一比对包内项目依赖和 reactor 产物。检查器在修复前真实旧包上失败，在增量重建后对全部 5 个项目依赖通过。没有修改 Discovery 的源码来人为触发重编译。

## Discovery 最终演练

报告：`artifacts/local/search-crash/20261014T222703-a012bbbf/report.json`，全部 6 项检查通过，`oomEvents=0`、`containersStopped=true`。镜像为 `sha256:d79a7fb54836ec79b54ba476fcdf4efa6c85bc4f5266a2920a8f254ded543679`；可执行 JAR 摘要为 `f3e5e9021678d4174ee666169b1d7098cd725a342690804434c1d9a86dfa9a18`；执行器摘要为 `0a1875e89a68f5ba9b2cd42e66f2a54788f06fea7d7178e8da8468960e529928`。

原租约数据库时间 `14:28:27.364406 UTC`，恢复完成 `14:33:28.518828 UTC`，约 301 秒。观察期间未完成事件的位点保持 1；恢复后状态 `COMPLETED`、attempts=2、业务水位=2、租约为空、位点=2。租约争用未进入 DLT；随后重复事件、版本 3 删除、版本 1 旧更新保持版本 3 墓碑；缺少版本的坏消息经有限重试进入 DLT。最终更新 topic 位点为 5、删除 topic 位点为 1。

## 验证与适用边界

- 113 项运维脚本测试通过，包括位点/数据库观察顺序、未完成消息拒绝确认、失败清理，以及旧依赖打包拒绝等回归。日志：`artifacts/local/goal-search-jobs/crash-ops-full.log`。
- 96 项 Java 定向回归通过，0 失败/错误/跳过：搜索模块全部 90 项、共享 Kafka 处理器 4 项、Discovery 装配 2 项。覆盖真实 MySQL 并发租约、ES 因果顺序/墓碑、持久重建任务与部署装配；本机测试 JVM 限制为 384 MiB。日志：`artifacts/local/goal-search-jobs/crash-java-coverage.log`。这不是全仓测试结果。
- 本次新生成的覆盖率报告：`SearchEventCoordinator` 分支 92.5%（37/40）、行 98.15%（106/108）；`SearchReindexJobs` 分支 96%（48/50）。两者均超过现有关键状态类 85% 分支门槛。
- 后端 CI 已加入 Discovery 真实故障演练，并上传逐提交证据；独立 Search 与 Discovery 的证据分别保留。
- 演练只操作随机独立 Compose 项目的合成内容，正常和失败路径均停止自有容器并保留数据卷，不证明全域权限、整站一致恢复或生产 SLA。
- 已被历史版本错误确认的消息不会自动重投。升级时需要逐条核对历史未完成记录、位点和投影，利用保留的原事件受控重放；不能直接改成完成状态。
- 浏览器测试缓存已从 `D:\86137\DevCircleBrowsers` 移到 `E:\xuexi\.cache\devcircle-browsers`，逐文件校验 SHA-256 后在原路径建立目录链接。Docker 数据和历史演练卷未移动或删除。

执行入口、注入点与具体判定条件见[搜索崩溃演练](../search-crash-recovery.md)。
