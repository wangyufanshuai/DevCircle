# 当前分支 CI 验证

## 触发与发布边界

`.github/workflows/ci.yml` 验证 `main` 和 `overhaul/mysticverse-production` 的 push，以及 pull request；同时声明 `workflow_dispatch`。

GitHub 的手动入口要求工作流在默认分支存在并包含相应事件。这个修改尚未合入默认分支时，使用当前分支 push 触发即可；不能因页面没有 Run workflow 按钮而宣称验证不可用。

只有 **push 到 main** 才允许执行 `images` 和 `web-image`；开发分支 push、PR、手动运行只验证。普通任务仅有 `contents: read`，写 Packages、OIDC、构建证明权限限制在发布任务。当前分支的旧运行可被新提交取消，main 的发布运行不会因新提交被自动取消。

## 通过条件

必需汇总检查为 **Required verification**，要求下面九项全部成功；缺失、失败、跳过或取消均失败：

1. backend：Java 21、完整 `verify -Pcoverage`、迁移版本、行覆盖率 75% / 分支覆盖率 70%、关键状态类 85% 分支、零跳过。
2. frontend：依赖审计、构建、默认及 Notification WebSocket Playwright、Lighthouse。
3. real-auth-e2e：真实认证链路。
4. real-content-e2e：真实内容链路。
5. real-review-e2e：真实投稿审核链路。
6. real-community-e2e：真实社区链路。
7. delivery：脚本契约、Compose、Helm、监控及资源门禁。
8. source-sbom：Java 与 Web 软件物料清单。
9. test-storage：从固定的 MinIO 官方源码提交构建真实测试镜像，供 backend 和三条对象存储 E2E 复用。

汇总检查写入提交 SHA、触发事件、ref 和各任务结果。它便于配置分支保护，但本次工作流修改不等于已经在 GitHub Settings 启用了必需状态检查。发布任务还需通过各自签名、证明、扫描等步骤。

## 查询当前提交

```bash
git rev-parse HEAD
gh run list --repo wangyufanshuai/DevCircle --branch overhaul/mysticverse-production --workflow ci.yml --limit 5
gh run view <run-id> --repo wangyufanshuai/DevCircle
gh run view <run-id> --repo wangyufanshuai/DevCircle --log-failed
```

核对 run 的 SHA 与待验证提交一致。PR 通常验证合并预览提交，应同时记录 head SHA 和 merge SHA，不能混为一谈。

Surefire、JaCoCo、失败 E2E 和 SBOM 产物名称含提交 SHA，保留 7 天；需要长期保留的候选版本应在到期前归档非敏感报告。成功的 run、对应日志和报告共同构成证据；仅存在 YAML、徽章或推送记录都不代表通过。

## 本地检查与证据边界

```powershell
python -m unittest discover -s scripts/ops -p 'test_*.py'
python scripts/ops/assert-actions-pinned.py
python scripts/ops/assert-observability-runbooks.py
python scripts/ops/assert_migration_versions.py
```

装有 Bash、Docker Compose 和 Helm 时还可运行生产资源检查。Windows 请使用 Git Bash，避免误用未配置 Docker Compose 的 WSL Bash。

本机没有 Docker Engine 时，普通 Maven `test` 可能输出 BUILD SUCCESS，同时跳过真实依赖测试；该结果只算有限验证。CI 保留零跳过门禁，不以 H2 或模拟依赖替代完整容器验收。

历史完整容器记录见 [2026-08-15 证据](evidence/2026-08-15-aggregate-docker-recovery.md)。Linux CI 通过仍不等于目标主机容量、全业务恢复、OAuth Provider、Helm 集群运行或公网生产验收通过。

## MinIO 测试依赖

2026-10-12 的首次远端运行证实旧 `minio/minio` 镜像无法拉取。CI 通过 `deploy/ci/Dockerfile.minio` 从官方 `RELEASE.2025-09-07T16-13-09Z` 对应提交 `07c3a429bfed433e49018cb0f78a52145d4bedeb` 构建真实服务，镜像只在本次 run 的任务间传递，产物保留 1 天，不发布到 GHCR，不替换生产镜像。

本机需要同样验证时，先构建该镜像并设置 `DEVCIRCLE_TEST_MINIO_IMAGE=devcircle-minio-test:07c3a429bfed433e49018cb0f78a52145d4bedeb`，再运行 Maven 或真实 E2E。未设置时保留旧镜像默认值，仅适用于已有本地缓存的环境。生产与完整 Demo 的旧 MinIO 镜像分发问题仍需独立处理；测试镜像可用不构成生产依赖维护承诺。
