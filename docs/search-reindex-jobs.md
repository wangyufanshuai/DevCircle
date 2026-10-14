# 可恢复搜索重建任务

维护窗口内使用此入口取代长时间同步请求。部署搜索迁移 V5 后可用；新旧写入器不能混跑。

## 前置条件

暂停所有内容写入和审核，停止搜索 Kafka 消费者，保留内容服务的读取 API。调用者必须通过网关 ADMIN 鉴权。`maintenanceConfirmed=true` 是操作员声明，不会自动关闭其他服务；不确认真实停写状态就不能把结果视为一致快照。任务的二次扫描可发现许多来源变化，但不替代停写隔离。

## 创建、执行与恢复

先生成并保存一个 UUID。访问令牌仅放在当前进程的 `DEVCIRCLE_ADMIN_TOKEN` 环境变量，不写进命令行或结果文件。

```powershell
python -X utf8 scripts/ops/run_search_reindex.py --job-id <UUID> --create-confirm-maintenance
# 客户端或服务进程中断后，使用原 UUID 继续；不要创建新任务来绕过失败。
python -X utf8 scripts/ops/run_search_reindex.py --job-id <UUID>
```

脚本默认访问 `http://127.0.0.1:8080`，通过 `--base-url` 指定其他入口；非回环地址必须使用 HTTPS，拒绝重定向。结果保存到 E 盘仓库的 `artifacts/local/search-reindex/<UUID>/status.json`，可用 `--output-root` 指定 D/E 位置，C 盘被拒绝。`--max-steps 1` 可在一批确认后退出，用于恢复演练。

退出码：0 仅表示 `COMPLETED`；1 表示参数/请求失败或结果未知；2 表示任务 `FAILED/CANCELLED`；3 表示按批数退出且任务仍可继续。结果未知时先查询原任务，不能直接认定未执行。访问令牌到期需重新取得管理员令牌后继续同一任务。

| 方法与路径（前缀 `/api/search/admin/reindex/jobs`） | 行为 |
|---|---|
| `PUT /{uuid}?maintenanceConfirmed=true` | 幂等创建；相同 UUID 返回已有状态 |
| `GET /{uuid}` | 查询持久检查点 |
| `POST /{uuid}/step?revision=N` | 最多处理一批；旧 revision 返回当前状态，不重复推进；未来 revision 拒绝 |
| `POST /{uuid}/cancel` | 取消并释放任务占用；已写的投影不会被删除 |

同一时间只允许一个未完成任务。每批事务通过数据库 `FOR UPDATE NOWAIT` 锁互斥，忙时返回 409；事务超时 60 秒。进程/连接丢失后数据库释放锁、回滚未提交检查点，已落 ES 的文档由业务版本保护，下一次从最后确认批次重试。旧同步重建入口也受同一互斥锁约束，不能绕过活动任务。

## 完成门禁

1. `COPYING`：每批读取最多 20 条来源，执行版本条件写入，提交游标、计数和内容指纹链。
2. `VERIFYING`：从头再次按游标读取来源，逐条比较 ES 的 ID、标题、摘要、作者、作者 ID、标签、状态、主题、内容类型、难度、创建时间和业务版本。
3. 两遍的来源计数与指纹链必须一致；ES 公开文档总数必须一致，额外文档只允许具有非负业务版本的删除墓碑。
4. 只有全部通过才持久化 `COMPLETED`。字段不一致记 `PROJECTION_MISMATCH`（首个差异 ID、字段名及双方指纹）；来源变化记 `SOURCE_CHANGED_DURING_REINDEX`；多余/无效文档记 `INDEX_EXTRA_OR_INVALID_DOCUMENTS`。

`copied` 是提交条件更新的数量，不能代替验证结果。较新删除墓碑拒绝旧发布快照时，任务应因差异而失败，不能用相同计数伪装恢复成功。失败任务不会自动清除墓碑、强行覆盖同版本内容或自动切换索引。修复实际数据/来源问题后，用新 UUID 重新建立任务。

该门禁不自动切换索引、不开放写入，也不证明在线强一致迁移或生产 RTO/RPO。首次升级仍需先备份并遵循[搜索升级步骤](search-version-migration.md)；旧版本回滚仍只读。
