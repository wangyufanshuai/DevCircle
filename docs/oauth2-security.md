# OAuth2 与安全

当前版本提供 GitHub OAuth2 登录、PKCE、一次性 state、JWT 签发、刷新令牌和 Gateway 校验。未配置 GitHub 凭证时接口明确返回未配置错误，不提供 mock callback 或固定 Token。

## 接口

- `GET /api/auth/oauth2/github/start`
- `GET /api/auth/oauth2/github/callback`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`
- `GET /api/auth/me`

OAuth state 和 PKCE verifier 只保存哈希/短期状态；回调成功后立即消费 state。

## JWT

`dc-auth` 签发访问令牌和刷新令牌。Gateway 只接受真实 JWT，并向下游透传：

- `X-User-Id`
- `X-Roles`
- `X-Trace-Id`

## 真实 provider 配置

真实环境应通过环境变量注入 client id 和 secret，不提交到仓库：

```env
GITHUB_CLIENT_ID=replace-with-real-client-id
GITHUB_CLIENT_SECRET=replace-with-real-client-secret
GITHUB_CALLBACK_URI=https://your-domain.example/api/auth/oauth2/github/callback
DEVCIRCLE_JWT_SECRET=replace-with-at-least-64-random-characters
```

GitHub 返回的邮箱必须是已验证邮箱；如果该邮箱已有密码账号，系统拒绝静默合并并返回 `OAUTH_ACCOUNT_CONFLICT`。注册、登录、刷新和退出均要求 `Idempotency-Key`，刷新响应使用加密的短期幂等记录恢复原会话响应。
