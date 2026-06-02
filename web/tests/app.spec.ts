import { expect, test } from '@playwright/test'

const mojibakePattern = new RegExp('[\\uFFFD\\u93BA\\u7EC9\\u7039\\u9427\\u5987\\u934A\\u9350\\u6D93\\u9359\\u690B\\u6CCC\\u68F0\\u89D9\\u681D\\u935A\\u95C6\\u6FB6\\u923F\\u5002]')

const deductionPost = {
  id: 1001,
  type: 'TEXT',
  theme: 'DEDUCTION',
  contentKind: 'CASE_FILE',
  difficulty: 'HARD',
  solvable: true,
  solvedCount: 58,
  title: '雾港钟楼失踪案',
  excerpt: '一桩围绕雾港钟楼展开的密室推理内容，线索覆盖时间线、证词、物证和现场痕迹。',
  author: '林墨',
  tags: ['推理', '密室', '夹层藏身', '案件档案'],
  mediaAssets: [],
  likes: 520,
  comments: 44,
  views: 12800,
  status: 'PUBLISHED',
  clues: ['怀表与公开记录的时间不一致', '时间线在 00:07 到 00:19 之间出现空档', '两名证人的描述都避开了钟楼后门', '现场只留下向内的擦痕', '第 3 份记录被人改写顺序'],
  suspects: ['雾港钟楼管理员', '钟表匠许一', '记者白森', '码头调度员周野'],
  timeline: ['22:11 受害者最后一次公开露面', '23:23 关键证人离开现场', '00:07 现场出现第一次异常', '00:19 巡查人员封锁入口', '01:31 档案员发现记录被调换'],
  question: '谁制造了关键的不在场证明？真正的手法和决定性证据分别是什么？',
  answerKeywords: ['夹层藏身', '怀表', '时间差'],
  explanation: '关键不是最显眼的证词，而是怀表与时间线空档之间的矛盾。凶手利用夹层藏身制造时间差。',
  relatedPostIds: [1002, 1008, 1014]
}

const scifiPost = {
  ...deductionPost,
  id: 2001,
  theme: 'SCI_FI',
  contentKind: 'WORLDBUILDING',
  solvable: false,
  solvedCount: 0,
  title: '木卫二冰下城设定集',
  excerpt: '围绕木卫二冰下城展开的科幻内容，包含社会结构、技术设定和派系冲突。',
  tags: ['科幻', '世界观', '木卫二'],
  worldbuilding: ['核心地点：木卫二冰下城', '技术概念：潮汐能源与低温生态'],
  clues: [],
  suspects: [],
  timeline: []
}

const cosmosPost = {
  ...deductionPost,
  id: 3001,
  theme: 'COSMOS',
  contentKind: 'COSMIC_ARTICLE',
  solvable: false,
  solvedCount: 0,
  title: '银河系旋臂档案',
  excerpt: '一份关于银河系旋臂的宇宙档案，解释恒星形成区和观测线索。',
  tags: ['宇宙', '档案', '银河'],
  cosmicFacts: ['天体对象：银河系旋臂', '观测手段：光谱与射电巡天'],
  clues: [],
  suspects: [],
  timeline: []
}

const records = Array.from({ length: 24 }, (_, index) => {
  const source = index % 3 === 0 ? deductionPost : index % 3 === 1 ? scifiPost : cosmosPost
  const post = { ...source, id: source.id + index, title: `${source.title} ${index + 1}` }
  return {
    post,
    reason: post.theme === 'DEDUCTION' ? `可解谜案件，已有 ${post.solvedCount} 人完成推理` : '主题内容库精选',
    score: 140 - index,
    liked: false,
    favorited: false,
    algorithm: 'THEME_LINEAR_MODEL',
    mediaType: post.type,
    experimentId: post.theme === 'DEDUCTION' ? 'exp-deduction-solve-v1' : 'exp-theme-feed-v1',
    bucket: 'treatment',
    recallSources: ['theme', 'hot'],
    features: {}
  }
})

test.beforeEach(async ({ page }) => {
  await page.route('**/api/recommend/feed**', async (route) => {
    const url = new URL(route.request().url())
    const scene = url.searchParams.get('scene') ?? 'mixed'
    const cursor = Number(url.searchParams.get('cursor') ?? '0')
    const size = Number(url.searchParams.get('size') ?? '12')
    const themeMap: Record<string, string> = { deduction: 'DEDUCTION', sci_fi: 'SCI_FI', cosmos: 'COSMOS' }
    const filtered = scene === 'mixed' || scene === 'home' ? records : records.filter((item) => item.post.theme === themeMap[scene])
    const pageRecords = filtered.slice(cursor, cursor + size)
    await route.fulfill({ json: { code: 'OK', message: 'success', data: { records: pageRecords, nextCursor: String(cursor + pageRecords.length), hasMore: cursor + pageRecords.length < filtered.length, source: 'DB' }, timestamp: new Date().toISOString() } })
  })
  await page.route('**/api/posts/*/related**', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: [records[3].post, records[6].post], timestamp: new Date().toISOString() } }))
  await page.route('**/api/posts/1001', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: deductionPost, timestamp: new Date().toISOString() } }))
  await page.route('**/api/admin/audits/posts', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: [{ postId: 1002, title: '待审核推理案件', status: 'PENDING', author: '林墨' }], timestamp: new Date().toISOString() } }))
  await page.route('**/api/admin/moderation/tasks**', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: [{ taskId: 1, postId: 1002, title: '待审核推理案件', queue: 'manual-review', riskLevel: 'LOW', riskScore: 12, reason: '常规审核', status: 'PENDING' }], timestamp: new Date().toISOString() } }))
  await page.route('**/api/admin/risk/users**', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: [], timestamp: new Date().toISOString() } }))
  await page.route('**/api/admin/recommend/experiments', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: [{ experimentId: 'exp-deduction-solve-v1', name: '推理馆解谜完成率实验', scene: 'deduction', status: 'ACTIVE', trafficPercent: 100 }], timestamp: new Date().toISOString() } }))
  await page.route('**/api/admin/ops/health', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: { database: 'UP', outboxBacklog: 0, pendingModeration: 1, riskUsers: 0, posts: 180, source: 'DB' }, timestamp: new Date().toISOString() } }))
  await page.route('**/api/admin/ops/outbox**', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: [], timestamp: new Date().toISOString() } }))
  await page.route('**/api/admin/ops/degradation', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: { degraded: false, source: 'DB', outboxBacklog: 0, recommendEvents: 12, pendingModeration: 1 }, timestamp: new Date().toISOString() } }))
  await page.route('**/api/notifications', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: [{ id: 1, type: 'DEDUCTION', title: '推理答案已判定', content: '你提交的钟楼案答案获得 20 积分。', read: false, createdAt: new Date().toISOString() }], timestamp: new Date().toISOString() } }))
  await page.route('**/api/deduction/leaderboard', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: [{ userId: 7, username: '林墨', correctCount: 58, totalPoints: 1160 }], timestamp: new Date().toISOString() } }))
  await page.route('**/api/deduction/cases/*/answers', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: { postId: 1001, userId: 2, correct: true, duplicated: false, points: 20, explanation: '推理成立：关键是钟楼夹层和怀表。' }, timestamp: new Date().toISOString() } }))
  await page.route('**/api/recommend/exposures', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: { recorded: 12 }, timestamp: new Date().toISOString() } }))
  await page.route('**/api/recommend/events', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: { eventId: 'e1' }, timestamp: new Date().toISOString() } }))
  await page.route('**/api/posts/*/like', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: { targetId: 1001, type: 'LIKE', active: true, duplicated: false, idempotencyKey: 'test' }, timestamp: new Date().toISOString() } }))
  await page.route('**/api/posts/*/favorite', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: { targetId: 1001, type: 'FAVORITE', active: true, duplicated: false, idempotencyKey: 'test' }, timestamp: new Date().toISOString() } }))
  await page.route('**/api/users/*/follow', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: { targetUserId: 99, followed: true, duplicated: false }, timestamp: new Date().toISOString() } }))
  await page.route('**/api/auth/oauth2/callback/**', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: { username: 'github-user' }, timestamp: new Date().toISOString() } }))
  await page.route('**/api/search/posts**', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: { records: [deductionPost], total: 1, page: 1, size: 10 }, timestamp: new Date().toISOString() } }))
  await page.route('**/api/posts', (route) => route.fulfill({ json: { code: 'OK', message: 'success', data: { ...deductionPost, id: 9001, title: '测试发布推理案件', status: 'PENDING' }, timestamp: new Date().toISOString() } }))
})

test('loads MysticVerse feed without mojibake', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByText('MysticVerse')).toHaveCount(1)
  await expect(page.getByRole('heading', { name: '星环首页' })).toBeVisible()
  await expect(page.locator('.post-card')).toHaveCount(12)
  await expect(page.locator('body')).not.toContainText(mojibakePattern)
})

test('opens deduction detail and submits an answer', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: /推理馆/ }).click()
  await expect(page.getByRole('heading', { name: '推理馆' })).toBeVisible()
  await page.locator('.post-card').first().click()
  const drawer = page.locator('.detail-drawer')
  await expect(drawer.getByRole('heading', { name: '雾港钟楼失踪案', exact: true })).toBeVisible()
  await expect(drawer.getByRole('heading', { name: '线索板' })).toBeVisible()
  await expect(drawer.getByRole('heading', { name: '嫌疑人' })).toBeVisible()
  await expect(drawer.getByRole('heading', { name: '时间线' })).toBeVisible()
  await drawer.getByRole('textbox').fill('凶手躲在钟楼夹层，并利用怀表制造时间差')
  await drawer.getByRole('button', { name: '提交推理' }).click()
  await expect(drawer.getByText(/推理正确/)).toBeVisible()
})

test('searches deduction content and shows engineering admin panels', async ({ page }) => {
  await page.goto('/')
  await page.getByPlaceholder('搜索案件、科幻短篇、宇宙档案、线索或标签').fill('钟楼')
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { name: '搜索结果' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '雾港钟楼失踪案', exact: true })).toBeVisible()

  await page.getByRole('button', { name: /审核台/ }).click()
  await expect(page.getByRole('heading', { name: '治理审核队列' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '工程状态' })).toBeVisible()
})

test('mobile viewport has no horizontal overflow', async ({ page, isMobile }) => {
  await page.goto('/')
  await expect(page.locator('body')).not.toContainText(mojibakePattern)
  if (isMobile) {
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)
    expect(overflow).toBe(false)
  }
})
