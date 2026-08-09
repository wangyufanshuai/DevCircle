# OAuth2 与安全

当前版本提供 GitHub OAuth2 登录、PKCE、一次性 state、JWT 签发、刷新令牌和 Gateway 校验。未配置 GitHub 凭证时接口明确返回未配置错误，不提供 mock callback 或固定 Token。生产认证执行载体是 `dc-identity`：它内嵌 `dc-auth` 运行时；`dc-auth` 的认证领域模块、逻辑事件 producer `dc-auth`、schema 与 Flyway 迁移仍保留。

## 接口

- `GET /api/auth/oauth2/github/start`
- `GET /api/auth/oauth2/github/callback`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`
- `GET /api/auth/me`

OAuth state 和 PKCE verifier 只保存哈希/短期状态；回调成功后立即消费 state。

## JWT

`dc-identity` 进程内的 `dc-auth` 认证运行时签发访问令牌和刷新令牌。正常 aggregate 路由中，Gateway 将 `/api/auth/**` 指向 `dc-identity:8201`；Gateway 只接受真实 JWT，并向下游透传：

- `X-User-Id`
- `X-Roles`
- `X-Trace-Id`

第一阶段并未完成完整 Identity 聚合：`/api/me/**` 由 `dc-identity` 的仪表盘处理，而用户、互动、通知仍保留在兼容服务。独立 `dc-auth` 可执行镜像只用于 `deploy/compose/docker-compose.legacy-auth-rollback.yml` 的短期回滚；该文件将 Gateway/Admin 的认证上游临时切回 `dc-auth:8101`，不是常规生产部署方式。

## Helm 生产约束

生产 Helm 模板采用 fail-closed 校验：Identity 必须显式设置 `AUTH_COOKIE_SECURE=true`；`AUTH_FRONTEND_CALLBACK_URI` 必须等于 `${publicOrigin}/auth/callback`，`GITHUB_CALLBACK_URI` 必须等于 `${publicOrigin}/api/auth/oauth2/github/callback`，且 `publicOrigin` 必须是公开 HTTPS origin。GitHub Client ID、Client Secret 与内部运维密钥必须从 `optional: false` 的 Kubernetes Secret 引用投影。values 映射不完整或由 Chart 创建的 Secret 缺少必需键会使 render 失败；外部 `existingSecret` 及其键无法在 Helm render 阶段验证，若集群中不存在，Pod 会在启动阶段失败。

## 真实 provider 配置

真实环境应通过环境变量注入 client id 和 secret，不提交到仓库：

```env
GITHUB_CLIENT_ID=replace-with-real-client-id
GITHUB_CLIENT_SECRET=replace-with-real-client-secret
GITHUB_CALLBACK_URI=https://your-domain.example/api/auth/oauth2/github/callback
DEVCIRCLE_JWT_SECRET=replace-with-at-least-64-random-characters
```

GitHub 返回的邮箱必须是已验证邮箱；如果该邮箱已有密码账号，系统拒绝静默合并并返回 `OAUTH_ACCOUNT_CONFLICT`。注册、登录、刷新和退出均要求 `Idempotency-Key`，刷新响应使用加密的短期幂等记录恢复原会话响应。

Provider 返回的无效或过期授权码按认证失败处理；GitHub 5xx、网络中断和无法解析的依赖响应按依赖不可用处理。对外错误不会包含 GitHub 响应体、内部节点名或底层异常文本。账号绑定前还会执行以下规范化和边界校验：

- GitHub 用户 ID 必须为正数。
- 只接受 `/user/emails` 返回的非空、已验证邮箱，并统一去除首尾空格和按 `Locale.ROOT` 转为小写。
- 展示名优先使用规范化后的 `name`，缺失时回退到规范化后的 `login`；两者都不可用时拒绝创建账号。
- null 邮箱数组、null 项、空白邮箱和未验证邮箱不会进入账号绑定。

仓库中的 Provider 测试使用 `MockRestServiceServer` 验证请求和失败分类，只属于本地契约测试，不代表真实 GitHub OAuth E2E。`dc-identity` 的 H2 回归启用 8 个认证 Flyway 迁移，并验证注册真实写入认证数据与 `user.registered` Outbox；这也不替代真实 Cookie、Gateway、GitHub Provider 或 MySQL 链路。真实 Provider 验收仍需要在预发布域名、正式回调 URI 和独立测试账号下完成。
