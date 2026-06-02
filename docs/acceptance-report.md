# v1.3 本地验收报告

本文记录 DevCircle / MysticVerse v1.3 的本地全量验收结果。本轮聚焦推理馆内容库扩容、推理解题体验和端到端可验证性。

## 验收范围

- 后端 Maven 全量测试，包含 Testcontainers 依赖链路。
- 前端生产构建与 Playwright 冒烟测试。
- Helm dev/prod values lint。
- Docker Compose infra/demo 配置校验。
- 本地浏览器人工验收首页、推理馆、案件详情、答题反馈和移动端布局。

## 推理馆验收点

- 推理内容库包含 100 条 `DEDUCTION` 内容，科幻馆和宇宙档案各保留 40 条。
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
