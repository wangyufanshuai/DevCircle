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
  await page.route('**/api/posts/*/interaction-state', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { liked: false, favorited: false } } }))
  await page.route('**/api/posts/*/reading-progress', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { postId: 1001, progressPercent: 25, completed: false } } }))
  await page.route('**/api/users/*/follow-state', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { following: false } } }))
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
  await expect(page.getByRole('link', { name: '登录参与讨论' })).toBeVisible()
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

test('notification inbox paginates, uses server unread count and handles missing targets', async ({ page }) => {
  await authenticate(page)
  let serverUnread = 3
  await page.route('**/api/notifications/unread-count', route => route.fulfill({ json: { code: 'OK', message: 'success', data: serverUnread } }))
  await page.route('**/api/notifications/*/read', route => { serverUnread = Math.max(0, serverUnread - 1); return route.fulfill({ json: { code: 'OK', message: 'success', data: { read: true } } }) })
  await page.route('**/api/notifications/read-all', route => { serverUnread = 0; return route.fulfill({ json: { code: 'OK', message: 'success', data: { updated: 2 } } }) })
  await page.route('**/api/notifications?*', route => {
    const url = new URL(route.request().url())
    const cursor = url.searchParams.get('cursor')
    const filter = url.searchParams.get('type')
    const records = filter === 'SYSTEM'
      ? [{ id: 1, type: 'SYSTEM', title: '轨道维护', content: '维护完成', read: true, targetRoute: '/posts/1001', createdAt: '2026-07-30T00:00:00Z' }]
      : cursor
      ? [{ id: 1, type: 'SYSTEM', title: '轨道维护', content: '维护完成', read: false, targetRoute: '/posts/1001', createdAt: '2026-07-30T00:00:00Z' }]
      : [
          { id: 3, type: 'AUDIT', title: '审核通过', content: '作品已经公开', read: false, targetRoute: '/posts/1001', createdAt: '2026-07-30T02:00:00Z' },
          { id: 2, type: 'INTERACTION', title: '失效目标', content: '原内容已经撤回', read: false, targetRoute: null, createdAt: '2026-07-30T01:00:00Z' }
        ]
    return route.fulfill({ json: { code: 'OK', message: 'success', data: records, meta: { nextCursor: !filter && !cursor ? '2' : '', hasMore: !filter && !cursor } } })
  })
  await page.goto('/notifications')
  await expect(page.getByText('3 条未读')).toBeVisible()
  await page.getByRole('button', { name: '加载更多通知' }).click()
  await expect(page.locator('.notification-row')).toHaveCount(3)
  await page.getByRole('button', { name: /失效目标/ }).click()
  await expect(page.getByRole('status')).toContainText('内容已不可用')
  await expect(page.getByText('2 条未读')).toBeVisible()
  await page.getByRole('button', { name: '全部已读' }).click()
  await expect(page.getByText('0 条未读')).toBeVisible()
  await page.getByLabel('通知类型').selectOption('SYSTEM')
  await expect(page).toHaveURL(/\/notifications\?type=SYSTEM$/)
  await expect(page.locator('.notification-row')).toHaveCount(1)
  await expect(page.getByRole('button', { name: /轨道维护/ })).toBeVisible()
})

test('keyword discovery keeps every filter in the URL and paginates search results', async ({ page }) => {
  const requests: URL[] = []
  await page.route('**/api/search/posts?*', route => {
    const url = new URL(route.request().url()); requests.push(url)
    const cursor = url.searchParams.get('cursor')
    const records = cursor ? [{ ...post, id: 1010, title: '钟楼余波' }] : [{ ...post, id: 1008, title: '钟楼证词' }, { ...post, id: 1009, title: '钟楼暗门' }]
    return route.fulfill({ json: { code: 'OK', message: 'success', data: records, meta: { nextCursor: cursor ? '' : 'djE6MQ', hasMore: !cursor } } })
  })
  await page.goto('/discover')
  await page.getByLabel('搜索内容').fill('钟楼')
  await page.getByLabel('按主题筛选').selectOption('DEDUCTION')
  await page.getByLabel('按内容类型筛选').selectOption('CASE_FILE')
  await page.getByLabel('按难度筛选').selectOption('HARD')
  await page.locator('#main-content').getByRole('button', { name: '搜索', exact: true }).click()
  await expect(page.locator('.post-card')).toHaveCount(2)
  const current = new URL(page.url())
  expect(Object.fromEntries(current.searchParams)).toMatchObject({ q: '钟楼', theme: 'DEDUCTION', contentKind: 'CASE_FILE', difficulty: 'HARD' })
  await page.getByRole('button', { name: '加载更多' }).click()
  await expect(page.locator('.post-card')).toHaveCount(3)
  expect(requests.at(-1)?.searchParams.get('cursor')).toBe('djE6MQ')
  expect(requests.at(-1)?.searchParams.get('contentKind')).toBe('CASE_FILE')
})

test('reader comments paginate, reply once and preserve deleted thread structure', async ({ page }) => {
  await authenticate(page)
  const root = { id: 30, postId: 1001, authorId: 7, author: '用户 7', content: '第一条线索', status: 'VISIBLE', likes: 0, createdAt: '2026-07-30T01:00:00Z' }
  const reply = { id: 31, postId: 1001, authorId: 7, author: '用户 7', parentId: 30, rootId: 30, content: '补充证词', status: 'VISIBLE', likes: 0, createdAt: '2026-07-30T01:10:00Z' }
  await page.route('**/api/posts/1001/comments?*', route => {
    const cursor = new URL(route.request().url()).searchParams.get('cursor')
    const data = cursor ? [{ root: { ...root, id: 20, content: '更早的讨论' }, replies: [] }] : [{ root, replies: [reply] }]
    return route.fulfill({ json: { code: 'OK', message: 'success', data, meta: { nextCursor: cursor ? '' : '30', hasMore: !cursor } } })
  })
  await page.route('**/api/comments', route => {
    const body = JSON.parse(route.request().postData() || '{}')
    const data = body.parentId
      ? { id: 98, postId: 1001, authorId: 2, author: '用户 2', parentId: body.parentId, rootId: body.parentId, content: body.content, status: 'VISIBLE', likes: 0, createdAt: '2026-07-30T02:10:00Z' }
      : { id: 99, postId: 1001, authorId: 2, author: '用户 2', content: body.content, status: 'VISIBLE', likes: 0, createdAt: '2026-07-30T02:00:00Z' }
    return route.fulfill({ json: { code: 'OK', message: 'success', data } })
  })
  await page.route('**/api/comments/*', route => route.fulfill({ json: { code: 'OK', message: 'success', data: null } }))
  await page.goto('/posts/1001')
  await expect(page.getByRole('button', { name: '加载更多讨论' })).toBeVisible()
  await page.getByRole('button', { name: '加载更多讨论' }).click()
  await expect(page.locator('.comment-thread')).toHaveCount(2)
  await page.getByLabel('写评论').fill('新的关键线索')
  await page.getByRole('button', { name: '发表评论' }).click()
  const createdThread = page.locator('[data-comment-id="99"]')
  await expect(createdThread).toBeVisible()
  await createdThread.getByRole('button', { name: '删除' }).click()
  await expect(createdThread).toContainText('评论已删除')
  const firstThread = page.locator('.comment-thread').filter({ hasText: '第一条线索' })
  await firstThread.getByRole('button', { name: '回复' }).click()
  await firstThread.getByLabel('回复评论').fill('我核对了这条证词')
  await firstThread.getByRole('button', { name: '发送回复' }).click()
  await expect(firstThread).toContainText('我核对了这条证词')
})

test('moderator must provide a reason before hiding a comment', async ({ page }) => {
  await authenticate(page, ['USER', 'ADMIN'])
  const root = { id: 40, postId: 1001, authorId: 7, author: '用户 7', content: '泄露隐私的文本', status: 'VISIBLE', likes: 0, createdAt: '2026-07-30T01:00:00Z' }
  let submittedReason = ''
  await page.route('**/api/posts/1001/comments?*', route => route.fulfill({ json: { code: 'OK', message: 'success', data: [{ root, replies: [] }], meta: { nextCursor: '', hasMore: false } } }))
  await page.route('**/api/comments/40/hide', route => { submittedReason = JSON.parse(route.request().postData() || '{}').reason; return route.fulfill({ json: { code: 'OK', message: 'success', data: null } }) })
  await page.goto('/posts/1001')
  const thread = page.locator('[data-comment-id="40"]')
  await thread.getByRole('button', { name: '隐藏' }).click()
  await thread.getByLabel('隐藏理由').fill('包含未经授权的个人信息')
  await thread.getByRole('button', { name: '确认隐藏' }).click()
  await expect(thread).toContainText('评论已隐藏')
  expect(submittedReason).toBe('包含未经授权的个人信息')
})

test('version conflict keeps local draft and blocks stale submission', async ({ page }) => {
  await authenticate(page)
  const draft = { id: 11, authorId: 2, title: '本地草稿', content: '本地正文', tags: ['推理'], type: 'TEXT', mediaIds: [], theme: 'DEDUCTION', contentKind: 'CASE_FILE', difficulty: 'MEDIUM', solvable: true, clues: '', suspects: '', timeline: '', question: '', answerKeywords: '', solution: '', status: 'DRAFT', version: 3, lastSavedAt: '2026-07-30T01:00:00Z', reviewReason: null }
  const serverDraft = { ...draft, title: '服务端最新草稿', content: '来自另一台设备的正文', version: 4 }
  let draftReads = 0; let submitCalls = 0
  await page.route('**/api/posts/drafts?size=50', route => route.fulfill({ json: { code: 'OK', message: 'success', data: [draft] } }))
  await page.route('**/api/posts/drafts/11/audits', route => route.fulfill({ json: { code: 'OK', message: 'success', data: [] } }))
  await page.route('**/api/posts/drafts/11/submit?*', route => { submitCalls += 1; return route.fulfill({ json: { code: 'OK', message: 'success', data: serverDraft } }) })
  await page.route('**/api/posts/drafts/11', route => {
    if (route.request().method() === 'PUT') return route.fulfill({ status: 409, json: { code: 'VERSION_CONFLICT', message: '草稿已在其他位置更新', data: null, traceId: 'conflict-test' } })
    draftReads += 1
    return route.fulfill({ json: { code: 'OK', message: 'success', data: draftReads === 1 ? draft : serverDraft } })
  })
  await page.goto('/write/11')
  await expect(page.getByLabel('正文 Markdown')).toHaveValue('本地正文')
  await page.getByLabel('正文 Markdown').fill('尚未保存的本地修改')
  await page.getByRole('button', { name: '提交审核' }).click()
  await expect(page.getByRole('alert')).toContainText('当前编辑内容仍保留')
  await expect(page.getByLabel('正文 Markdown')).toHaveValue('尚未保存的本地修改')
  await expect(page.getByRole('button', { name: '提交审核' })).toBeDisabled()
  expect(submitCalls).toBe(0)
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '加载服务端版本' }).click()
  await expect(page.getByLabel('正文 Markdown')).toHaveValue('来自另一台设备的正文')
  await expect(page.getByRole('button', { name: '提交审核' })).toBeEnabled()
})

test('responsive shell has no horizontal overflow and uses the correct navigation', async ({ page }, testInfo) => {
  await page.goto('/')
  if (testInfo.project.name === 'mobile') await expect(page.locator('.mobile-nav')).toBeVisible()
  else await expect(page.locator('.mobile-nav')).toBeHidden()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
})
