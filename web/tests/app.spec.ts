import { expect, test } from '@playwright/test'

const mojibakePattern = new RegExp('[\\uFFFD\\u951F\\u9428\\u93BA\\u93C4\\u7039\\u9359]')

const post = (id: number, theme: 'DEDUCTION' | 'SCI_FI' | 'COSMOS', title: string, type: 'TEXT' | 'IMAGE' | 'VIDEO' = 'TEXT') => ({
  id,
  type,
  theme,
  contentKind: theme === 'DEDUCTION' ? 'CASE_FILE' : theme === 'SCI_FI' ? 'WORLDBUILDING' : 'COSMIC_ARTICLE',
  difficulty: theme === 'DEDUCTION' ? 'HARD' : 'MEDIUM',
  solvable: theme === 'DEDUCTION',
  solvedCount: theme === 'DEDUCTION' ? 31 : 0,
  title,
  excerpt: theme === 'DEDUCTION' ? '凌晨零点钟声响起后，守夜人从封闭钟楼消失。' : '主题内容摘要，用于验证三主题平台。',
  author: theme === 'DEDUCTION' ? '林墨' : theme === 'SCI_FI' ? '陈星' : '王遥',
  tags: theme === 'DEDUCTION' ? ['密室', '时间线'] : theme === 'SCI_FI' ? ['世界观', '木卫二'] : ['宇宙', '档案'],
  mediaAssets: type === 'IMAGE' ? [{ mediaId: id, objectKey: 'demo/image.webp', url: 'https://picsum.photos/seed/test-theme/720/960', contentType: 'image/webp', size: 120000, width: 720, height: 960, status: 'PENDING_REVIEW' }] : [],
  likes: 300,
  comments: 40,
  views: 9000,
  status: 'PUBLISHED'
})

const records = [
  { post: post(1001, 'DEDUCTION', '雾港钟楼失踪案'), reason: '可解谜案件，已有 31 人完成推理', score: 96, liked: false, favorited: false, algorithm: 'THEME_LINEAR_MODEL', mediaType: 'TEXT', features: {} },
  { post: post(1002, 'SCI_FI', '木卫二冰下城市设定集', 'IMAGE'), reason: '科幻世界观设定被多次收藏', score: 88, liked: false, favorited: false, algorithm: 'THEME_LINEAR_MODEL', mediaType: 'IMAGE', features: {} },
  { post: post(1003, 'COSMOS', '银河系旋臂档案'), reason: '宇宙档案与深空关键词匹配', score: 84, liked: false, favorited: false, algorithm: 'THEME_LINEAR_MODEL', mediaType: 'TEXT', features: {} }
]

test.beforeEach(async ({ page }) => {
  await page.route('**/api/recommend/feed**', (route) => {
    const scene = new URL(route.request().url()).searchParams.get('scene') ?? 'mixed'
    const themeMap: Record<string, string> = { deduction: 'DEDUCTION', sci_fi: 'SCI_FI', cosmos: 'COSMOS' }
    const filtered = scene === 'mixed' || scene === 'home' ? records : records.filter((item) => item.post.theme === themeMap[scene])
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { records: filtered, nextCursor: String(filtered.length), hasMore: false }, timestamp: new Date().toISOString() }) })
  })
  await page.route('**/api/admin/audits/posts', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: [{ postId: 1002, title: '木卫二冰下城市设定集', status: 'PENDING', author: '陈星' }], timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/notifications', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: [{ id: 1, type: 'DEDUCTION', title: '推理答案已判定', content: '你提交的钟楼案答案获得 20 积分。', read: false, createdAt: new Date().toISOString() }], timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/deduction/leaderboard', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: [{ userId: 7, username: '林墨', correctCount: 19, totalPoints: 380 }], timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/deduction/cases/*/answers', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { postId: 1001, userId: 2, correct: true, duplicated: false, points: 20, explanation: '推理成立：关键是钟楼夹层。' }, timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/recommend/exposures', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { recorded: 3 }, timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/posts/*/like', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { targetId: 1001, type: 'LIKE', active: true, duplicated: false, idempotencyKey: 'test' }, timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/posts/*/favorite', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { targetId: 1001, type: 'FAVORITE', active: true, duplicated: false, idempotencyKey: 'test' }, timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/users/*/follow', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { targetUserId: 99, followed: true, duplicated: false }, timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/auth/oauth2/callback/**', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { username: 'github-user' }, timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/posts', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { ...post(2001, 'DEDUCTION', '测试发布案件'), status: 'PENDING' }, timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/search/posts**', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { records: [records[0].post], total: 1, page: 1, size: 10 }, timestamp: new Date().toISOString() }) })
  )
})

test('loads MysticVerse theme feed and has no mojibake', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '把普通社交 Feed 改造成推理、科幻与宇宙的内容宇宙' })).toBeVisible()
  await expect(page.getByText('MysticVerse')).toHaveCount(1)
  await expect(page.getByRole('heading', { name: '把普通社交 Feed 改造成推理、科幻与宇宙的内容宇宙' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '雾港钟楼失踪案' }).first()).toBeVisible()
  await expect(page.locator('body')).not.toContainText(mojibakePattern)
})

test('switches three theme channels and submits deduction answer', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: /推理馆/ }).click()
  await expect(page.getByRole('heading', { name: '推理馆' })).toBeVisible()
  await page.getByRole('button', { name: '提交答案' }).click()
  await expect(page.getByText(/推理正确/)).toBeVisible()
  await page.getByRole('button', { name: /科幻馆/ }).click()
  await expect(page.getByRole('heading', { name: '木卫二冰下城市设定集' })).toBeVisible()
  await page.getByRole('button', { name: /宇宙档案/ }).click()
  await expect(page.getByRole('heading', { name: '银河系旋臂档案' })).toBeVisible()
})

test('searches, publishes, likes, follows, logs in and approves audit', async ({ page }) => {
  await page.goto('/')
  await page.getByPlaceholder('搜索案件、科幻短篇、宇宙档案、线索或标签').fill('钟楼')
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { name: '搜索结果' })).toBeVisible()

  await page.getByText('GitHub 登录').click()
  await expect(page.getByText(/github 登录成功/)).toBeVisible()
  await page.getByRole('button', { name: /关注/ }).first().click()
  await expect(page.getByText(/关注创作者/)).toBeVisible()

  await page.getByRole('button', { name: /创作台/ }).click()
  await page.getByPlaceholder('标题，例如：雾港钟楼失踪案').fill('测试发布案件')
  await page.getByPlaceholder('正文：案件叙述、科幻短篇、世界观设定或宇宙档案').fill('测试案件正文')
  await page.getByRole('button', { name: '提交到审核与事件队列' }).click()
  await expect(page.getByText(/内容 #2001 已提交/)).toBeVisible()

  await page.getByRole('button', { name: /点赞/ }).first().click()
  await expect(page.getByRole('button', { name: /已赞/ }).first()).toBeVisible()
  await page.getByRole('button', { name: '收藏' }).first().click()
  await expect(page.getByRole('button', { name: '已收藏' }).first()).toBeVisible()
  await page.getByRole('button', { name: '通过' }).click()
  await expect(page.getByText(/已通过内容/)).toBeVisible()
})

test('mobile viewport has no horizontal overflow', async ({ page, isMobile }) => {
  await page.goto('/')
  await expect(page.locator('body')).not.toContainText(mojibakePattern)
  if (isMobile) {
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)
    expect(overflow).toBe(false)
  }
})
