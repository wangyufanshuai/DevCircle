# v1.3 本地验收报告

## 当前证据状态（2026-08-02）

本文件下方的 v1.3 表格保留历史验收记录，不能替代当前状态。最新前端 Playwright 回归为 112/112 通过，覆盖 Chromium、桌面、平板和移动配置，但使用显式 API 拦截；真实认证、内容、审核和社区 E2E 仍需独立 Compose 环境。当前 Docker 配额约 1.93 GiB，低于真实 E2E 的 2 GiB 最小门槛，因此不能将旧的“本地全量验收通过”解释为当前生产或真实多服务验收通过。最新后端与基础设施证据以 [`integration-testing.md`](integration-testing.md) 为准。

本文记录 DevCircle / MysticVerse v1.3 的本地全量验收结果。本轮聚焦推理馆内容库扩容、推理解题体验和端到端可验证性。

## 验收范围

- 后端 Maven 全量测试，包含 Testcontainers 依赖链路。
- 前端生产构建与 Playwright 冒烟测试。
- Helm dev/prod values lint。
- Docker Compose infra/demo 配置校验。
- 本地浏览器人工验收首页、推理馆、案件详情、答题反馈和移动端布局。

## 推理馆验收点

- 主题内容库包含 180 条内容：100 条 `DEDUCTION`、40 条 `SCI_FI`、40 条 `COSMOS`，仅作为首次初始化数据。
- 每条推理内容具备标题、摘要、标签、难度、作者、热度、推荐理由、线索、嫌疑人、时间线、问题、答案关键词和解析。
- 答题判定支持中文标点/空格归一化、多关键词命中和常见同义词。
- 同一用户同一案件重复提交不重复加分。
- 推理馆 Feed、搜索、详情、相关内容和答题反馈均能正常展示。

## 执行结果

| 命令 | 结果 | 说明 |
| --- | --- | --- |
| `.\mvnw.cmd -B test` | 通过 | 覆盖 MySQL、Redis、Kafka、Elasticsearch、MinIO Testcontainers。 |
| `cd web; npm run build` | 通过 | Vue 类型检查和 Vite 生产构建通过。 |
| `cd web; npx playwright test` | 通过 | 8 项通过，覆盖首页、推理详情、答题、搜索、管理面板和移动端溢出。 |
| `helm lint deploy/helm/devcircle -f deploy/helm/devcircle/values-dev.yaml` | 通过 | 仅提示 Chart icon 推荐项。 |
| `helm lint deploy/helm/devcircle -f deploy/helm/devcircle/values-prod.yaml` | 通过 | 仅提示 Chart icon 推荐项。 |
| `docker compose --profile infra config` | 通过 | 基础设施 profile 配置可解析。 |
| `docker compose --profile demo config` | 通过 | 全栈 demo profile 配置可解析。 |
| `git diff --check` | 通过 | 无空白错误；Windows 下仅有 LF/CRLF 提示。 |

## 修复记录

- 修复推理答题服务中的旧乱码文案，补齐同义词和归一化判题逻辑。
- 修复内容集成测试只加载 V1 schema 导致的详情字段缺失问题，测试改为加载完整迁移链。
- 修复推荐服务 Spring 构造器选择问题，保证 Redis 集成测试上下文可启动。
- 修复上传集成测试使用伪造图片字节的问题，改为真实 PNG 魔数样例。
- 修复前端详情页缺少案件内答题框的问题，并补强 Playwright 精确断言。

## 当前结论

v1.3 本地全量验收通过。按计划仅本地提交，不 push。
