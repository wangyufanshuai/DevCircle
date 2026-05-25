# OAuth2 与安全

v0.7 提供 GitHub/Gitee OAuth2 登录接口形态、JWT 签发、刷新令牌和 Gateway 校验。默认 profile 使用 mock callback，避免本地演示依赖真实 provider secret。

## 接口

- `GET /api/auth/oauth2/authorize/{provider}`
- `GET /api/auth/oauth2/callback/{provider}`
- `POST /api/auth/refresh`
- `GET /api/auth/me`

`provider` 支持 `github` 和 `gitee`。

## JWT

`dc-auth` 签发访问令牌和刷新令牌。Gateway 对 demo token 和真实 JWT 都会校验，并向下游透传：

- `X-User-Id`
- `X-Roles`
- `X-Trace-Id`

## 真实 provider 配置

真实环境应通过环境变量注入 client id 和 secret，不提交到仓库：

```env
GITHUB_CLIENT_ID=replace-me
GITHUB_CLIENT_SECRET=replace-me
GITEE_CLIENT_ID=replace-me
GITEE_CLIENT_SECRET=replace-me
DEVCIRCLE_JWT_SECRET=replace-with-at-least-32-bytes
```

v0.7 不实现完整生产账号风控、设备管理和密钥轮换，这些留给后续版本。
