import { expect, test } from '@playwright/test'

const post = {
  id: 1001, type: 'TEXT', theme: 'DEDUCTION', contentKind: 'CASE_FILE', difficulty: 'HARD', solvable: true,
  solvedCount: 58, title: '雾港钟楼失踪案', excerpt: '钟声停在 00:07，鞋印只到楼梯口。', markdown: '# 雾港钟楼\n\n钟声停在 00:07，鞋印只到楼梯口。', author: { id: 7, username: '林墨', bio: '记录异常信号', followerCount: 18, followedByViewer: false },
  tags: ['推理', '时间线'], media: [], likes: 520, comments: 4, views: 12800, status: 'PUBLISHED', clues: ['怀表与公开记录的时间不一致。', '现场只留下向内的擦痕。'],
  question: '谁制造了不在场证明？', worldbuilding: [], cosmicFacts: [], relatedPostIds: [], viewerState: { liked: false, favorited: false, readingProgress: 0 }, deductionState: { attempted: false, solved: false, revealed: false, points: 0, solutionUnlocked: false }, solution: null
}
const feed = Array.from({ length: 8 }, (_, index) => ({ post: { ...post, id: post.id + index, title: `${post.title} ${index + 1}` }, reason: '与你近期感兴趣的主题相关', score: 100 - index, liked: false, favorited: false, algorithm: 'EXPLAINABLE_RULE_V1', features: {} }))
const dashboard = { profile: { id: 2, username: '观测者', bio: '追踪星图中的异常', followers: 3, following: 5, status: 'ACTIVE' }, readingProgress: [], submissions: [], favorites: [], unreadNotifications: 0 }

async function authenticate(page: import('@playwright/test').Page, roles: string[] = ['USER']) {
  await page.context().addCookies([{ name: 'dc_csrf', value: 'test-csrf', domain: '127.0.0.1', path: '/' }])
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { accessToken: 'access-token', expiresAt: '2099-01-01T00:00:00Z', user: { userId: 2, displayName: '观测者', roles } } } }))
}

test.beforeEach(async ({ page }) => {
  await page.route('**/api/recommend/feed**', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { records: feed, nextCursor: '8', hasMore: false }, traceId: 'test' } }))
  await page.route('**/api/posts?*', route => route.fulfill({ json: { code: 'OK', message: 'success', data: feed.map(item => item.post), meta: { nextCursor: '', hasMore: false } } }))
  await page.route('**/api/posts/1001', route => route.fulfill({ json: { code: 'OK', message: 'success', data: post } }))
  await page.route('**/api/users/7', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { id: 7, username: '林墨', bio: '记录异常信号', followers: 18, following: 4, status: 'ACTIVE' } } }))
  await page.route('**/api/posts/*/related**', route => route.fulfill({ json: { code: 'OK', message: 'success', data: feed.slice(1, 3).map(item => item.post) } }))
  await page.route('**/api/posts/*/comments**', route => route.fulfill({ json: { code: 'OK', message: 'success', data: [], meta: { nextCursor: '', hasMore: false } } }))
  await page.route('**/api/recommend/exposures', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { recorded: 8 } } }))
  await page.route('**/api/posts/*/like', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { targetId: 1001, type: 'LIKE', active: true, duplicated: false, idempotencyKey: 'test' } } }))
  await page.route('**/api/posts/*/favorite', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { targetId: 1001, type: 'FAVORITE', active: true, duplicated: false, idempotencyKey: 'test' } } }))
  await page.route('**/api/deduction/cases/*/answers', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { postId: 1001, userId: 2, correct: true, duplicated: false, revealed: false, solutionUnlocked: true, points: 20, explanation: '推理成立', solution: '关键是怀表与时间线之间的矛盾。' } } }))
  await page.route('**/api/notifications', route => route.fulfill({ json: { code: 'OK', message: 'success', data: [] } }))
  await page.route('**/api/posts/drafts?*', route => route.fulfill({ json: { code: 'OK', message: 'success', data: [] } }))
  await page.route('**/api/me/dashboard', route => route.fulfill({ json: { code: 'OK', message: 'success', data: dashboard } }))
})

test('desktop home has observatory hierarchy and theme links', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: /把线索放回星图/ })).toBeVisible()
  await expect(page.getByRole('region', { name: '主题频道' }).getByRole('link', { name: /推理馆/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /进入推理馆/ })).toBeVisible()
})

test('formal post route opens case and submits deduction', async ({ page }) => {
  await page.goto('/posts/1001')
  await expect(page.getByRole('heading', { name: '雾港钟楼失踪案', exact: true })).toBeVisible()
  await page.getByLabel('推理结论').fill('夹层藏身，怀表')
  await page.getByRole('button', { name: '提交推理' }).click()
  await expect(page.getByRole('status')).toContainText('推理成立')
})

test('write route exposes draft editor and safe preview', async ({ page }) => {
  await authenticate(page)
  await page.goto('/write')
  await expect(page.getByRole('heading', { name: '把你的观测记录放上来。' })).toBeVisible()
  await expect(page.getByLabel('正文 Markdown')).toBeVisible()
  await expect(page.getByText('实时预览')).toBeVisible()
})

test('admin route keeps review reason visible', async ({ page }) => {
  await authenticate(page, ['USER', 'ADMIN'])
  await page.route('**/api/admin/moderation/tasks**', route => route.fulfill({ json: { code: 'OK', message: 'success', data: [{ taskId: 1, postId: 1001, title: '雾港钟楼失踪案', queue: 'manual-review', riskLevel: 'MEDIUM', riskScore: 42, reason: '需要人工确认', status: 'PENDING' }] } }))
  await page.goto('/admin')
  await expect(page.getByRole('heading', { name: '内容审核台' })).toBeVisible()
  await expect(page.getByLabel('审核理由')).toBeVisible()
})

test('protected route signs in, returns to destination and updates the shell', async ({ page }) => {
  await page.route('**/api/auth/login', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { accessToken: 'access-token', expiresAt: '2099-01-01T00:00:00Z', user: { userId: 2, displayName: '观测者', roles: ['USER'] } } } }))
  await page.goto('/write')
  await expect(page).toHaveURL(/\/auth\?redirect=\/write$/)
  await page.getByLabel('邮箱').fill('reader@example.com')
  await page.getByLabel('密码').fill('correct-horse-battery-staple')
  await page.locator('.auth-card button[type="submit"]').click()
  await expect(page).toHaveURL(/\/write$/)
  await expect(page.getByRole('heading', { name: '把你的观测记录放上来。' })).toBeVisible()
})

test('non-admin session cannot open the operations console', async ({ page }) => {
  await authenticate(page)
  await page.goto('/admin')
  await expect(page).toHaveURL(/\/me\?access=denied$/)
  await expect(page.getByRole('status')).toContainText('没有访问运营后台的权限')
})

test('logout clears the reactive session only after server confirmation', async ({ page }) => {
  await authenticate(page)
  await page.route('**/api/auth/logout', route => route.fulfill({ json: { code: 'OK', message: 'success', data: null } }))
  await page.goto('/me')
  await expect(page.getByRole('heading', { name: '观测者' })).toBeVisible()
  await page.locator('.profile-page').getByRole('button', { name: '退出登录' }).click()
  await expect(page).toHaveURL(/\/$/)
  await page.locator('a[href="/write"]:visible').first().click()
  await expect(page).toHaveURL(/\/auth\?redirect=\/write$/)
})

test('failed logout keeps the session visible and explains the failure', async ({ page }) => {
  await authenticate(page)
  await page.route('**/api/auth/logout', route => route.fulfill({ status: 503, json: { code: 'SERVICE_UNAVAILABLE', message: '退出服务暂时不可用', data: null, traceId: 'logout-test' } }))
  await page.goto('/me')
  await page.locator('.profile-page').getByRole('button', { name: '退出登录' }).click()
  await expect(page).toHaveURL(/\/me$/)
  await expect(page.getByRole('alert')).toContainText('退出服务暂时不可用')
  await expect(page.getByRole('heading', { name: '观测者' })).toBeVisible()
})

test('responsive shell has no horizontal overflow and uses the correct navigation', async ({ page }, testInfo) => {
  await page.goto('/')
  if (testInfo.project.name === 'mobile') await expect(page.locator('.mobile-nav')).toBeVisible()
  else await expect(page.locator('.mobile-nav')).toBeHidden()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
})
