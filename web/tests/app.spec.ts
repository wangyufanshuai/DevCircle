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
  status: 'PUBLISHED',
  clues: theme === 'DEDUCTION' ? ['怀表停在 00:07', '潮湿鞋印只到楼梯口', '航海日志缺页'] : [],
  suspects: theme === 'DEDUCTION' ? ['守夜人韩策', '钟表匠许临', '记者白棠'] : [],
  timeline: theme === 'DEDUCTION' ? ['23:40 最后一班船靠岸', '00:07 钟楼短暂停电', '00:20 现场封锁'] : [],
  question: theme === 'DEDUCTION' ? '真正制造不在场证明的人是谁？' : '',
  answerKeywords: theme === 'DEDUCTION' ? ['夹层', '怀表'] : [],
  explanation: theme === 'DEDUCTION' ? '关键是钟楼夹层和怀表时间。' : '',
  worldbuilding: theme === 'SCI_FI' ? ['核心地点：木卫二冰下城', '技术概念：潮汐能源'] : [],
  cosmicFacts: theme === 'COSMOS' ? ['天体对象：银河系旋臂', '观测手段：光谱'] : [],
  relatedPostIds: [1002, 1003]
})

const basePosts = [
  post(1001, 'DEDUCTION', '雾港钟楼失踪案'),
  post(1002, 'SCI_FI', '木卫二冰下城市设定集', 'IMAGE'),
  post(1003, 'COSMOS', '银河系旋臂档案')
]

const records = Array.from({ length: 42 }, (_, index) => {
  const source = basePosts[index % basePosts.length]
  const itemPost = { ...source, id: source.id + index * 10, title: `${source.title} ${index + 1}` }
  return { post: itemPost, reason: source.solvable ? '可解谜案件，已有 31 人完成推理' : '主题内容库精选', score: 120 - index, liked: false, favorited: false, algorithm: 'THEME_LINEAR_MODEL', mediaType: source.type, features: {} }
})

test.beforeEach(async ({ page }) => {
  await page.route('**/api/recommend/feed**', (route) => {
    const url = new URL(route.request().url())
    const scene = url.searchParams.get('scene') ?? 'mixed'
    const cursor = Number(url.searchParams.get('cursor') ?? '0')
    const size = Number(url.searchParams.get('size') ?? '12')
    const themeMap: Record<string, string> = { deduction: 'DEDUCTION', sci_fi: 'SCI_FI', cosmos: 'COSMOS' }
    const filtered = scene === 'mixed' || scene === 'home' ? records : records.filter((item) => item.post.theme === themeMap[scene])
    const pageRecords = filtered.slice(cursor, cursor + size)
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { records: pageRecords, nextCursor: String(cursor + pageRecords.length), hasMore: cursor + pageRecords.length < filtered.length }, timestamp: new Date().toISOString() }) })
  })
  await page.route('**/api/posts/*/related**', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: [basePosts[1], basePosts[2]], timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/posts/1001', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: basePosts[0], timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/admin/audits/posts', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: [{ postId: 1002, title: '木卫二冰下城市设定集', status: 'PENDING', author: '陈星' }], timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/notifications', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: [{ id: 1, type: 'DEDUCTION', title: '推理答案已判定', content: '你提交的钟楼案答案获得 20 积分。', read: false, createdAt: new Date().toISOString() }], timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/deduction/leaderboard', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: [{ userId: 7, username: '林墨', correctCount: 39, totalPoints: 780 }], timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/deduction/cases/*/answers', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { postId: 1001, userId: 2, correct: true, duplicated: false, points: 20, explanation: '推理成立：关键是钟楼夹层。' }, timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/recommend/exposures', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { recorded: 12 }, timestamp: new Date().toISOString() }) })
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
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { ...basePosts[0], id: 2001, title: '测试发布案件', status: 'PENDING' }, timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/search/posts**', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { records: [basePosts[0]], total: 1, page: 1, size: 10 }, timestamp: new Date().toISOString() }) })
  )
})

test('loads dense MysticVerse feed and has no mojibake', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByText('MysticVerse')).toHaveCount(1)
  await expect(page.getByRole('heading', { name: '120 条主题实例，把推理馆、科幻馆和宇宙档案做成可读的内容平台' })).toBeVisible()
  await expect(page.locator('.post-card').filter({ hasText: 'THEME_LINEAR_MODEL' })).toHaveCount(12)
  await expect(page.locator('body')).not.toContainText(mojibakePattern)
})

test('opens detail panel with clues and related content', async ({ page }) => {
  await page.goto('/')
  await page
    .getByRole('article')
    .filter({ has: page.getByRole('heading', { name: '雾港钟楼失踪案 1', exact: true }) })
    .getByRole('button', { name: '查看详情' })
    .click()
  await expect(page.getByRole('heading', { name: '内容详情' })).toBeVisible()
  await expect(page.getByText('线索板', { exact: true })).toBeVisible()
  await expect(page.getByText('怀表停在 00:07')).toBeVisible()
  await expect(page.getByText('相关内容', { exact: true })).toBeVisible()
  await page.getByPlaceholder('提交详情页推理答案').fill('钟楼夹层')
  await page.getByRole('button', { name: '提交', exact: true }).click()
  await expect(page.getByText(/推理正确/).first()).toBeVisible()
})

test('switches theme channels, searches, publishes and approves audit', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: /推理馆/ }).click()
  await expect(page.getByRole('heading', { name: '推理馆', exact: true })).toBeVisible()
  await page.getByRole('button', { name: /科幻馆/ }).click()
  await expect(page.getByRole('heading', { name: /木卫二冰下城市设定集/ }).first()).toBeVisible()
  await page.getByRole('button', { name: /宇宙档案/ }).click()
  await expect(page.getByRole('heading', { name: /银河系旋臂档案/ }).first()).toBeVisible()

  await page.getByPlaceholder('搜索案件、科幻短篇、宇宙档案、线索或标签').fill('钟楼')
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { name: '搜索结果' })).toBeVisible()

  await page.getByRole('button', { name: /创作台/ }).click()
  await page.getByPlaceholder('标题，例如：雾港钟楼失踪案').fill('测试发布案件')
  await page.getByPlaceholder('正文：案件叙述、科幻短篇、世界观设定或宇宙档案').fill('测试案件正文')
  await page.getByRole('button', { name: '提交到审核与事件队列' }).click()
  await expect(page.getByText(/内容 #2001 已提交/)).toBeVisible()

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
