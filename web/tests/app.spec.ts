import { expect, test } from '@playwright/test'

const post = {
  id: 1001, type: 'TEXT', theme: 'DEDUCTION', contentKind: 'CASE_FILE', difficulty: 'HARD', solvable: true,
  solvedCount: 58, title: '雾港钟楼失踪案', excerpt: '钟声停在 00:07，鞋印只到楼梯口。', markdown: '# 雾港钟楼\n\n钟声停在 00:07，鞋印只到楼梯口。', author: { id: 7, username: '林墨', bio: '记录异常信号', followerCount: 18, followedByViewer: false },
  tags: ['推理', '时间线'], media: [], likes: 520, comments: 4, views: 12800, status: 'PUBLISHED', clues: ['怀表与公开记录的时间不一致。', '现场只留下向内的擦痕。'],
  question: '谁制造了不在场证明？', worldbuilding: [], cosmicFacts: [], relatedPostIds: [], viewerState: { liked: false, favorited: false, readingProgress: 0, readingAnchor: null, readingCompleted: false }, deductionState: { attempted: false, solved: false, revealed: false, points: 0, solutionUnlocked: false }, solution: null
}
const feed = Array.from({ length: 8 }, (_, index) => ({ post: { ...post, id: post.id + index, title: `${post.title} ${index + 1}` }, reason: '与你近期感兴趣的主题相关', score: 100 - index, liked: false, favorited: false, algorithm: 'EXPLAINABLE_RULE_V1', features: {} }))
const dashboard = { profile: { id: 2, username: '观测者', bio: '追踪星图中的异常', followers: 3, following: 5, status: 'ACTIVE' }, readingProgress: [], submissions: [], favorites: [{ id: 1001, title: '雾港钟楼失踪案', excerpt: '钟声停在 00:07。', theme: 'DEDUCTION', contentKind: 'CASE_FILE', difficulty: 'HARD', createdAt: '2026-07-30T00:00:00Z' }], unreadNotifications: 0, deductionStats: { attempts: 6, correctCount: 4, revealedCount: 1, totalPoints: 80 } }
const unhandledApiRequests = new WeakMap<import('@playwright/test').Page, string[]>()

async function authenticate(page: import('@playwright/test').Page, roles: string[] = ['USER']) {
  await page.context().addCookies([{ name: 'dc_csrf', value: 'test-csrf', domain: '127.0.0.1', path: '/' }])
  await page.route('**/api/auth/refresh', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { accessToken: 'access-token', expiresAt: '2099-01-01T00:00:00Z', user: { userId: 2, displayName: '观测者', roles } } } }))
}

test.beforeEach(async ({ page }) => {
  const unhandled: string[] = []
  unhandledApiRequests.set(page, unhandled)
  await page.route('**/*', route => {
    const request = route.request()
    const url = new URL(request.url())
    if (!url.pathname.startsWith('/api/')) return route.fallback()
    unhandled.push(`${request.method()} ${url.pathname}${url.search}`)
    return route.fulfill({
      status: 503,
      json: { code: 'UNHANDLED_TEST_ROUTE', message: 'Playwright API route is not declared', data: null }
    })
  })
  await page.route('**/api/recommend/feed**', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { records: feed, nextCursor: '8', hasMore: false }, traceId: 'test' } }))
  await page.route('**/api/posts?*', route => route.fulfill({ json: { code: 'OK', message: 'success', data: feed.map(item => item.post), meta: { nextCursor: '', hasMore: false } } }))
  await page.route('**/api/posts/1001', route => route.fulfill({ json: { code: 'OK', message: 'success', data: post } }))
  await page.route('**/api/users/7', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { id: 7, username: '林墨', bio: '记录异常信号', followers: 18, following: 4, status: 'ACTIVE' } } }))
  await page.route('**/api/users/batch?*', route => route.fulfill({ json: { code: 'OK', message: 'success', data: [{ id: 7, username: '林墨', bio: '记录异常信号', followers: 18, following: 4, status: 'ACTIVE' }] } }))
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
  await page.route('**/api/admin/appeals?*', route => route.fulfill({ json: { code: 'OK', message: 'success', data: [] } }))
  await page.route('**/api/me/dashboard', route => route.fulfill({ json: { code: 'OK', message: 'success', data: dashboard } }))
})

test.afterEach(async ({ page }) => {
  if (!page.isClosed()) {
    await page.goto('about:blank').catch(() => undefined)
  }
  expect(unhandledApiRequests.get(page) ?? [], 'every mocked API request must have an explicit route').toEqual([])
})

test('desktop home has observatory hierarchy and theme links', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: /把线索放回星图/ })).toBeVisible()
  await expect(page.getByRole('region', { name: '主题频道' }).getByRole('link', { name: /推理馆/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /进入推理馆/ })).toBeVisible()
})

test('home explains dashboard failure instead of silently hiding reader state', async ({ page }) => {
  await authenticate(page)
  await page.route('**/api/me/dashboard', route => route.fulfill({ status: 503, json: { code: 'DEPENDENCY_UNAVAILABLE', message: '个人中心暂时无法加载，请稍后重试', data: null } }))
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '继续解谜暂时无法加载' })).toBeVisible()
  await expect(page.getByRole('button', { name: '重试状态' })).toBeVisible()
})

test('formal post route opens case and submits deduction', async ({ page }) => {
  await authenticate(page)
  await page.goto('/posts/1001')
  await expect(page.getByRole('heading', { name: '雾港钟楼失踪案', exact: true })).toBeVisible()
  await page.getByLabel('推理结论').fill('夹层藏身，怀表')
  await page.getByRole('button', { name: '提交推理' }).click()
  await expect(page.getByRole('status')).toContainText('推理成立')
})

test('comment outage keeps the case readable and can recover locally', async ({ page }) => {
  const root = { id: 60, postId: 1001, authorId: 8, author: '沈岚', content: '评论服务恢复后的线索', status: 'VISIBLE', likes: 0, createdAt: '2026-07-30T01:00:00Z' }
  let commentRequests = 0
  await page.route('**/api/posts/1001/comments?*', route => {
    commentRequests += 1
    if (commentRequests === 1) {
      return route.fulfill({ status: 503, json: { code: 'DEPENDENCY_UNAVAILABLE', message: 'dc-comment connection refused', data: null } })
    }
    return route.fulfill({ json: { code: 'OK', message: 'success', data: [{ root, replies: [] }], meta: { nextCursor: '', hasMore: false } } })
  })

  await page.goto('/posts/1001')

  await expect(page.getByRole('heading', { name: post.title, exact: true })).toBeVisible()
  await expect(page.getByLabel('推理结论')).toBeVisible()
  await expect(page.getByRole('alert').filter({ hasText: '评论暂时无法加载，请重试。' })).toBeVisible()
  await expect(page.getByText('案件暂时无法打开')).toHaveCount(0)
  await expect(page.getByText('dc-comment connection refused')).toHaveCount(0)

  await page.getByRole('button', { name: '重试加载评论' }).click()
  await expect(page.locator('[data-comment-id="60"]')).toContainText('评论服务恢复后的线索')
  await expect(page.getByText('评论暂时无法加载，请重试。')).toHaveCount(0)
  expect(commentRequests).toBe(2)
})

test('authenticated mutating requests forward the readable csrf cookie', async ({ page }) => {
  await authenticate(page)
  let csrfHeader = ''
  page.on('request', request => {
    const pathname = new URL(request.url()).pathname
    if (pathname === '/api/posts/1001/like') csrfHeader = request.headers()['x-csrf-token'] ?? ''
  })
  await page.goto('/posts/1001')
  await page.locator('.detail-actions').getByRole('button', { name: /喜欢/ }).click()
  expect(csrfHeader).toBe('test-csrf')
})

test('reader tracks separate active solved revealed completed and unavailable content', async ({ page }) => {
  await authenticate(page)
  const trackDashboard = {
    ...dashboard,
    readingProgress: [
      { postId: 1001, title: '仍在阅读的钟楼', theme: 'DEDUCTION', progressPercent: 42, completed: false, lastReadAt: '2026-07-30T03:00:00Z', available: true, trackState: 'IN_PROGRESS' },
      { postId: 1002, title: '已经解开的密室', theme: 'DEDUCTION', progressPercent: 76, completed: false, lastReadAt: '2026-07-30T02:00:00Z', available: true, trackState: 'SOLVED' },
      { postId: 1003, title: '主动查看答案的档案', theme: 'COSMOS', progressPercent: 61, completed: false, lastReadAt: '2026-07-30T01:00:00Z', available: true, trackState: 'REVEALED' },
      { postId: 1004, title: '已经读完的航行日志', theme: 'SCI_FI', progressPercent: 100, completed: true, lastReadAt: '2026-07-30T00:30:00Z', available: true, trackState: 'COMPLETED' },
      { postId: 1005, title: '内容已不可用', theme: null, progressPercent: 33, completed: false, lastReadAt: '2026-07-30T00:00:00Z', available: false, trackState: 'UNAVAILABLE' }
    ]
  }
  await page.route('**/api/me/dashboard', route => route.fulfill({ json: { code: 'OK', message: 'success', data: trackDashboard } }))

  await page.goto('/')
  await expect(page.getByRole('heading', { name: '继续上次的观测' })).toBeVisible()
  await expect(page.getByRole('link', { name: /仍在阅读的钟楼/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /已经解开的密室/ })).toHaveCount(0)

  await page.goto('/me')
  await expect(page.getByRole('heading', { name: '阅读与解谜轨道' })).toBeVisible()
  await expect(page.getByText('已解开谜题', { exact: true })).toBeVisible()
  await expect(page.getByText('已查看答案', { exact: true })).toBeVisible()
  await expect(page.getByText('已完成阅读', { exact: true })).toBeVisible()
  const unavailable = page.locator('.progress-row.is-unavailable')
  await expect(unavailable).toContainText('内容已不可用')
  await expect(unavailable.locator('a')).toHaveCount(0)
})

test('reading progress exposes sync failure, retries idempotently and saves a content anchor on exit', async ({ page }) => {
  await authenticate(page)
  const longMarkdown = `# 第一节 **钟声**\n\n${'钟楼里的线索仍在延伸。'.repeat(120)}\n\n## 第二节\n\n${'观测者继续核对时间线。'.repeat(120)}`
  await page.route('**/api/posts/1001', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { ...post, markdown: longMarkdown } } }))
  const progressRequests: Array<{ key: string | null; payload: { progressPercent: number; anchor: string | null; completed: boolean } }> = []
  await page.route('**/api/posts/1001/reading-progress', route => {
    const payload = JSON.parse(route.request().postData() || '{}')
    progressRequests.push({ key: route.request().headers()['idempotency-key'] ?? null, payload })
    if (progressRequests.length === 1) {
      return route.fulfill({ status: 503, json: { code: 'DEPENDENCY_UNAVAILABLE', message: '阅读进度暂时无法同步', data: null } })
    }
    return route.fulfill({ json: { code: 'OK', message: 'success', data: { postId: 1001, ...payload, lastReadAt: '2026-07-30T04:00:00Z' } } })
  })

  await page.goto('/posts/1001')
  await expect(page.locator('#reading-section-1 strong')).toHaveText('钟声')
  const sync = page.locator('.progress-sync')
  await expect(sync).toContainText('阅读进度暂时无法同步')
  await sync.getByRole('button', { name: '重试' }).click()
  await expect(sync).toContainText('阅读进度已同步')
  expect(progressRequests[1].key).toBe(progressRequests[0].key)

  const secondSection = page.locator('#reading-section-2')
  await secondSection.evaluate(element => element.scrollIntoView({ block: 'start' }))
  await expect.poll(() => secondSection.evaluate(element => Math.round(element.getBoundingClientRect().top))).toBeLessThan(120)
  await page.getByRole('link', { name: '返回发现' }).click()
  await expect(page).toHaveURL(/\/discover$/)
  expect(progressRequests.at(-1)?.payload.anchor).toBe('reading-section-2')
})

test('anonymous interaction enters login and returns to the same post', async ({ page }) => {
  await page.goto('/posts/1001')
  await expect(page.getByRole('link', { name: '登录参与讨论' })).toBeVisible()
  await page.getByRole('button', { name: '关注作者' }).click()
  await expect(page).toHaveURL(/\/auth\?redirect=\/posts\/1001$/)
})

test('failed optimistic like rolls back and shows a readable error', async ({ page }) => {
  await authenticate(page)
  await page.route('**/api/posts/1001/like', route => route.fulfill({ status: 503, json: { code: 'SERVICE_UNAVAILABLE', message: '喜欢操作暂时不可用', data: null, traceId: 'like-test' } }))
  await page.goto('/posts/1001')
  const like = page.getByRole('button', { name: /喜欢 520/ })
  await like.click()
  await expect(like).toHaveAttribute('aria-pressed', 'false')
  await expect(like).toContainText('喜欢 520')
  await expect(page.getByRole('alert')).toContainText('喜欢操作暂时不可用')
})

test('write route exposes draft editor and safe preview', async ({ page }) => {
  await authenticate(page)
  await page.goto('/write')
  await expect(page.getByRole('heading', { name: '把你的观测记录放上来。' })).toBeVisible()
  await expect(page.getByLabel('正文 Markdown')).toBeVisible()
  await expect(page.getByText('实时预览')).toBeVisible()
  await page.getByLabel('正文 Markdown').fill('![远程跟踪图](https://tracker.example/pixel.png)\n\n<img src=x onerror=alert(1)>')
  await expect(page.locator('.inline-media-unavailable')).toContainText('外部图片已阻止')
  await expect(page.locator('.markdown-preview img')).toHaveCount(0)
})

test('detail resolves attached inline media without duplicating it in the gallery', async ({ page }) => {
  const storedMediaUrl = 'http://minio:9000/devcircle/private/8.png'
  const signedMediaUrl = '/media/evidence.png'
  const mediaPost = {
    ...post,
    markdown: '# 雾港钟楼\n\n![停摆的怀表](media://8)\n\n![远程图片](https://tracker.example/pixel.png)',
    media: [{ mediaId: 8, objectKey: 'private/8.png', url: storedMediaUrl, contentType: 'image/png', size: 12, status: 'APPROVED', role: 'INLINE', altText: '证物桌上停摆的怀表', sortOrder: 0 }]
  }
  let accessCalls = 0
  await page.route('**/api/posts/1001', route => route.fulfill({ json: { code: 'OK', message: 'success', data: mediaPost } }))
  await page.route('**/api/files/access?*', route => { accessCalls += 1; return route.fulfill({ json: { code: 'OK', message: 'success', data: [{ mediaId: 8, url: signedMediaUrl, expiresAt: '2026-08-10T01:00:00Z' }] } }) })
  await page.route('**/media/evidence.png', route => route.fulfill({ status: 200, contentType: 'image/png', body: Buffer.from([0x89, 0x50, 0x4e, 0x47]) }))
  await page.goto('/posts/1001')
  await expect(page.locator('.inline-media img')).toHaveAttribute('alt', '证物桌上停摆的怀表')
  await expect(page.locator('.detail-media-grid')).toHaveCount(0)
  await expect(page.locator('.inline-media img')).toHaveAttribute('src', /\/media\/evidence\.png$/)
  expect(accessCalls).toBe(1)
})

test('detail batches author profiles instead of issuing per-comment requests', async ({ page }) => {
  const root = { id: 50, postId: 1001, authorId: 8, author: '用户 8', content: '第一份证词', status: 'VISIBLE', likes: 0, createdAt: '2026-07-30T01:00:00Z' }
  const reply = { id: 51, postId: 1001, authorId: 9, author: '用户 9', parentId: 50, rootId: 50, content: '补充证词', status: 'VISIBLE', likes: 0, createdAt: '2026-07-30T01:10:00Z' }
  let batchCalls = 0; const individualProfileRequests: string[] = []
  page.on('request', request => { if (/\/api\/users\/\d+$/.test(new URL(request.url()).pathname)) individualProfileRequests.push(request.url()) })
  await page.route('**/api/posts/1001/comments?*', route => route.fulfill({ json: { code: 'OK', message: 'success', data: [{ root, replies: [reply] }], meta: { nextCursor: '', hasMore: false } } }))
  await page.route('**/api/users/batch?*', route => {
    batchCalls += 1
    expect(new URL(route.request().url()).searchParams.get('ids')?.split(',').sort()).toEqual(['8', '9'])
    return route.fulfill({ json: { code: 'OK', message: 'success', data: [
      { id: 7, username: '林墨', bio: '记录异常信号', followers: 18, following: 4, status: 'ACTIVE' },
      { id: 8, username: '沈岚', bio: '核对时间线', followers: 6, following: 3, status: 'ACTIVE' },
      { id: 9, username: '北辰', bio: '整理证物', followers: 5, following: 2, status: 'ACTIVE' }
    ] } })
  })
  await page.goto('/posts/1001')
  await expect(page.locator('.comment-thread')).toContainText('沈岚')
  await expect(page.locator('.comment-thread')).toContainText('北辰')
  expect(batchCalls).toBe(1)
  expect(individualProfileRequests).toEqual([])
})

test('detail consumes composed follow and interaction state without extra browser requests', async ({ page }) => {
  await authenticate(page)
  const composed = {
    ...post,
    author: { ...post.author, followedByViewer: true },
    viewerState: { ...post.viewerState, liked: true, favorited: true }
  }
  const redundantRequests: string[] = []
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (path.endsWith('/interaction-state') || path.endsWith('/follow-state') || path === '/api/files/access') {
      redundantRequests.push(path)
    }
  })
  await page.route('**/api/posts/1001', route => route.fulfill({ json: { code: 'OK', message: 'success', data: composed } }))

  await page.goto('/posts/1001')

  await expect(page.getByRole('button', { name: /已喜欢/ })).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByRole('button', { name: /已收藏/ })).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByRole('button', { name: '已关注' })).toHaveAttribute('aria-pressed', 'true')
  expect(redundantRequests).toEqual([])
})

test('following an author updates the visible follower count and rolls back on failure', async ({ page }) => {
  await authenticate(page)
  let attempts = 0
  await page.route('**/api/users/7/follow', route => {
    attempts += 1
    if (attempts === 1) return route.fulfill({ json: { code: 'OK', message: 'success', data: { targetUserId: 7, followed: true, duplicated: false } } })
    return route.fulfill({ status: 503, json: { code: 'DEPENDENCY_UNAVAILABLE', message: '关注状态暂时无法同步' } })
  })
  await page.goto('/posts/1001')
  await expect(page.getByText('18 位关注者')).toBeVisible()

  await page.getByRole('button', { name: '关注作者' }).click()
  await expect(page.getByText('19 位关注者')).toBeVisible()
  await page.getByRole('button', { name: '已关注' }).click()
  await expect(page.getByText('19 位关注者')).toBeVisible()
  await expect(page.getByRole('alert')).toContainText('关注状态暂时无法同步')
})

test('admin route keeps review reason visible', async ({ page }) => {
  await authenticate(page, ['USER', 'ADMIN'])
  await page.route('**/api/admin/moderation/tasks**', route => route.fulfill({ json: { code: 'OK', message: 'success', data: [{ taskId: 1, postId: 1001, title: '雾港钟楼失踪案', queue: 'manual-review', riskLevel: 'MEDIUM', riskScore: 42, reason: '需要人工确认', status: 'PENDING' }] } }))
  await page.goto('/admin')
  await expect(page.getByRole('heading', { name: '内容审核台' })).toBeVisible()
  await expect(page.getByLabel('审核理由')).toBeVisible()
})

test('admin accepts an appeal with a reason and idempotency key', async ({ page }) => {
  await authenticate(page, ['USER', 'ADMIN'])
  await page.route('**/api/admin/moderation/tasks**', route => route.fulfill({ json: { code: 'OK', message: 'success', data: [] } }))
  await page.route('**/api/admin/appeals?*', route => route.fulfill({ json: { code: 'OK', message: 'success', data: [{ appealId: 5, postId: 12, title: '需要补证的钟楼档案', userId: 2, reason: '附件已经包含来源说明，请重新核对。', status: 'PENDING', createdAt: '2026-07-30T02:00:00Z' }] } }))
  let decisionReason = ''; let idempotencyKey = ''
  await page.route('**/api/admin/appeals/5/approve', route => {
    decisionReason = JSON.parse(route.request().postData() || '{}').reason
    idempotencyKey = route.request().headers()['idempotency-key'] ?? ''
    return route.fulfill({ json: { code: 'OK', message: 'success', data: { appealId: 5, status: 'APPROVED', postStatus: 'PENDING' } } })
  })
  await page.goto('/admin')
  await expect(page.getByRole('heading', { name: '作者申诉' })).toBeVisible()
  await page.getByRole('button', { name: '受理并复审' }).click()
  await expect(page.getByRole('alert')).toContainText('必须填写决定理由')
  await page.getByLabel('申诉决定理由').fill('附件证据有效，重新进入人工复审队列。')
  await page.getByRole('button', { name: '受理并复审' }).click()
  await expect(page.getByRole('heading', { name: '作者申诉' })).toHaveCount(0)
  expect(decisionReason).toBe('附件证据有效，重新进入人工复审队列。')
  expect(idempotencyKey).not.toBe('')
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

test('oauth account conflict returns a safe and actionable login message', async ({ page }) => {
  let refreshRequests = 0
  page.on('request', request => {
    if (new URL(request.url()).pathname === '/api/auth/refresh') refreshRequests += 1
  })

  await page.goto('/auth/callback?error=OAUTH_ACCOUNT_CONFLICT')

  await expect(page.getByRole('heading', { name: '登录没有完成' })).toBeVisible()
  await expect(page.getByText('这个邮箱已经注册。请使用邮箱密码登录；系统不会自动合并账号。')).toBeVisible()
  await expect(page.getByRole('link', { name: '返回登录' })).toHaveAttribute('href', '/auth')
  expect(refreshRequests).toBe(0)
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
  await expect(page.getByText('答对 4 / 尝试 6')).toBeVisible()
  await expect(page.getByRole('link', { name: /雾港钟楼失踪案/ })).toHaveAttribute('href', '/posts/1001')
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

test('discovery restores the loaded cursor after browser back navigation', async ({ page }) => {
  const cursors: string[] = []
  await page.route('**/api/posts?*', route => {
    const url = new URL(route.request().url())
    const cursor = url.searchParams.get('cursor') ?? ''
    cursors.push(cursor)
    const records = cursor === ''
      ? [{ ...post, id: 1001, title: '第一页档案' }]
      : cursor === 'c1'
        ? [{ ...post, id: 1002, title: '第二页档案' }]
        : [{ ...post, id: 1003, title: '第三页档案' }]
    const nextCursor = cursor === '' ? 'c1' : cursor === 'c1' ? 'c2' : ''
    return route.fulfill({ json: { code: 'OK', message: 'success', data: records, meta: { nextCursor, hasMore: Boolean(nextCursor) } } })
  })
  await page.goto('/discover')
  await page.getByRole('button', { name: '加载更多' }).click()
  await page.getByRole('link', { name: /第一页档案/ }).click()
  await page.goBack()
  await expect(page.getByRole('link', { name: /第二页档案/ })).toBeVisible()
  expect(cursors).toEqual(['', 'c1', '', 'c1'])
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

test('author can appeal, revise and resubmit a changes-required draft', async ({ page }) => {
  await authenticate(page)
  let draft = { id: 12, authorId: 2, title: '需要补证的钟楼档案', content: '原始正文', tags: ['推理'], type: 'TEXT', mediaIds: [], media: [], theme: 'DEDUCTION', contentKind: 'CASE_FILE', difficulty: 'MEDIUM', solvable: true, clues: '', suspects: '', timeline: '', question: '', answerKeywords: '', solution: '', status: 'CHANGES_REQUIRED', version: 3, lastSavedAt: '2026-07-30T01:00:00Z', reviewReason: '请补充怀表来源并说明时间线矛盾' }
  const audits = [{ id: 1, postId: 12, auditorId: 9, action: 'REQUIRE_CHANGES', reason: draft.reviewReason, createdAt: '2026-07-30T01:30:00Z' }]
  let savedContent = ''; let submitCalls = 0
  await page.route('**/api/posts/drafts?size=50', route => route.fulfill({ json: { code: 'OK', message: 'success', data: [draft] } }))
  await page.route('**/api/posts/drafts/12/audits', route => route.fulfill({ json: { code: 'OK', message: 'success', data: audits } }))
  await page.route('**/api/posts/12/appeals', route => {
    const reason = JSON.parse(route.request().postData() || '{}').reason
    audits.push({ id: 2, postId: 12, auditorId: 2, action: 'APPEAL_CREATED', reason, createdAt: '2026-07-30T02:00:00Z' })
    return route.fulfill({ json: { code: 'OK', message: 'success', data: { appealId: 5, postId: 12, status: 'PENDING' } } })
  })
  await page.route('**/api/posts/drafts/12/submit?*', route => {
    submitCalls += 1; draft = { ...draft, status: 'PENDING', version: draft.version + 1, reviewReason: null }
    return route.fulfill({ json: { code: 'OK', message: 'success', data: draft } })
  })
  await page.route('**/api/posts/drafts/12', route => {
    if (route.request().method() === 'PUT') {
      const body = JSON.parse(route.request().postData() || '{}'); savedContent = body.content
      draft = { ...draft, ...body, version: draft.version + 1 }
    }
    return route.fulfill({ json: { code: 'OK', message: 'success', data: draft } })
  })
  await page.goto('/write/12')
  await expect(page.getByText('审核意见：请补充怀表来源并说明时间线矛盾')).toBeVisible()
  await expect(page.getByText('要求修改', { exact: true })).toBeVisible()
  await page.getByLabel('申诉理由').fill('短')
  await page.getByRole('button', { name: '提交申诉' }).click()
  await expect(page.getByRole('alert')).toContainText('至少 4 个字符')
  await page.getByLabel('申诉理由').fill('审核意见遗漏了附件中的来源说明，请重新核对。')
  await page.getByRole('button', { name: '提交申诉' }).click()
  await expect(page.getByRole('button', { name: '申诉已提交' })).toBeDisabled()
  await expect(page.getByText('提交申诉', { exact: true })).toBeVisible()
  await page.getByLabel('正文 Markdown').fill('补充后的正文：怀表来自失踪者的储物柜。')
  await page.getByRole('button', { name: '重新提交审核' }).click()
  await expect(page.getByText('等待审核', { exact: true })).toBeVisible()
  await expect(page.getByLabel('正文 Markdown')).toBeDisabled()
  expect(savedContent).toContain('怀表来自失踪者的储物柜')
  expect(submitCalls).toBe(1)
})

test('creator persists media role alt text and canonical order', async ({ page }) => {
  await authenticate(page)
  let nextMediaId = 200; let savedBody: any = null
  await page.route('**/api/files/intents', route => {
    const mediaId = ++nextMediaId; const origin = new URL(route.request().url()).origin
    return route.fulfill({ json: { code: 'OK', message: 'success', data: { mediaId, objectKey: `private/${mediaId}.png`, uploadUrl: `${origin}/direct-upload/${mediaId}`, requiredHeaders: { 'Content-Type': 'image/png' }, expiresAt: '2099-01-01T00:00:00Z' } } })
  })
  await page.route('**/direct-upload/*', route => route.fulfill({ status: 200, body: '' }))
  await page.route('**/api/files/*/complete', route => { const mediaId = Number(new URL(route.request().url()).pathname.split('/').at(-2)); return route.fulfill({ json: { code: 'OK', message: 'success', data: { mediaId, objectKey: `private/${mediaId}.png`, url: '', contentType: 'image/png', size: 12, status: 'PENDING_REVIEW' } } }) })
  await page.route('**/api/posts/drafts', route => {
    savedBody = JSON.parse(route.request().postData() || '{}')
    return route.fulfill({ json: { code: 'OK', message: 'success', data: { ...savedBody, id: 77, authorId: 2, status: 'DRAFT', version: 0, lastSavedAt: '2026-07-30T03:00:00Z', reviewReason: null } } })
  })
  await page.goto('/write')
  await page.getByLabel('标题').fill('双媒体观测记录')
  await page.getByLabel('正文 Markdown').fill('正文中的两张观测图。')
  const upload = page.getByLabel('上传封面或媒体')
  await upload.setInputFiles({ name: 'station-cover.png', mimeType: 'image/png', buffer: Buffer.from([0x89, 0x50, 0x4e, 0x47]) })
  await expect(page.getByLabel('媒体 #201 替代文本')).toHaveValue('station cover')
  await upload.setInputFiles({ name: 'evidence-clock.png', mimeType: 'image/png', buffer: Buffer.from([0x89, 0x50, 0x4e, 0x47]) })
  await page.getByLabel('媒体 #202 角色').selectOption('COVER')
  await expect(page.getByLabel('媒体 #201 角色')).toHaveValue('INLINE')
  await page.getByLabel('媒体 #202 替代文本').fill('证物桌上的停摆怀表')
  await page.getByRole('button', { name: '插入正文媒体 #201' }).click()
  await expect(page.getByLabel('正文 Markdown')).toHaveValue(/!\[station cover]\(media:\/\/201\)/)
  await page.getByRole('button', { name: '上移媒体 #202' }).click()
  await page.getByRole('button', { name: '保存草稿' }).click()
  await expect(page).toHaveURL(/\/write\/77$/)
  expect(savedBody.mediaIds).toEqual([202, 201])
  expect(savedBody.content).toContain('![station cover](media://201)')
  expect(savedBody.media).toEqual([
    { mediaId: 202, role: 'COVER', altText: '证物桌上的停摆怀表', sortOrder: 0 },
    { mediaId: 201, role: 'INLINE', altText: 'station cover', sortOrder: 1 }
  ])
})

test('responsive shell has no horizontal overflow and uses the correct navigation', async ({ page }, testInfo) => {
  await page.goto('/')
  if (testInfo.project.name === 'mobile') await expect(page.locator('.mobile-nav')).toBeVisible()
  else await expect(page.locator('.mobile-nav')).toBeHidden()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
})
