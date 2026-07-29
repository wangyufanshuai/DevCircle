import { expect, test } from '@playwright/test'

const post = {
  id: 1001, type: 'TEXT', theme: 'DEDUCTION', contentKind: 'CASE_FILE', difficulty: 'HARD', solvable: true,
  solvedCount: 58, title: '雾港钟楼失踪案', excerpt: '钟声停在 00:07，鞋印只到楼梯口。', author: '林墨', tags: ['推理', '时间线'],
  mediaAssets: [], likes: 520, comments: 4, views: 12800, status: 'PUBLISHED', clues: ['怀表与公开记录的时间不一致。', '现场只留下向内的擦痕。'],
  question: '谁制造了不在场证明？', answerKeywords: ['夹层藏身', '怀表'], explanation: '关键是怀表与时间线之间的矛盾。'
}
const feed = Array.from({ length: 8 }, (_, index) => ({ post: { ...post, id: post.id + index, title: `${post.title} ${index + 1}` }, reason: '与你近期感兴趣的主题相关', score: 100 - index, liked: false, favorited: false, algorithm: 'EXPLAINABLE_RULE_V1', features: {} }))

test.beforeEach(async ({ page }) => {
  await page.route('**/api/recommend/feed**', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { records: feed, nextCursor: '8', hasMore: false }, traceId: 'test' } }))
  await page.route('**/api/posts?*', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { records: feed.map(item => item.post), total: 8, page: 1, size: 20 } } }))
  await page.route('**/api/posts/1001', route => route.fulfill({ json: { code: 'OK', message: 'success', data: post } }))
  await page.route('**/api/posts/*/related**', route => route.fulfill({ json: { code: 'OK', message: 'success', data: feed.slice(1, 3).map(item => item.post) } }))
  await page.route('**/api/comments?*', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { records: [] } } }))
  await page.route('**/api/recommend/exposures', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { recorded: 8 } } }))
  await page.route('**/api/posts/*/like', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { targetId: 1001, type: 'LIKE', active: true, duplicated: false, idempotencyKey: 'test' } } }))
  await page.route('**/api/posts/*/favorite', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { targetId: 1001, type: 'FAVORITE', active: true, duplicated: false, idempotencyKey: 'test' } } }))
  await page.route('**/api/deduction/cases/*/answers', route => route.fulfill({ json: { code: 'OK', message: 'success', data: { postId: 1001, userId: 2, correct: true, duplicated: false, points: 20, explanation: '推理成立' } } }))
  await page.route('**/api/notifications', route => route.fulfill({ json: { code: 'OK', message: 'success', data: [] } }))
  await page.route('**/api/posts/drafts?*', route => route.fulfill({ json: { code: 'OK', message: 'success', data: [] } }))
})

test('desktop home has observatory hierarchy and theme links', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '把线索放回星图，让故事继续发光。' })).toBeVisible()
  await expect(page.getByRole('region', { name: '主题频道' }).getByRole('link', { name: /推理馆/ })).toBeVisible()
  await expect(page.getByRole('link', { name: '开始创作' })).toBeVisible()
})

test('formal post route opens case and submits deduction', async ({ page }) => {
  await page.goto('/posts/1001')
  await expect(page.getByRole('heading', { name: '雾港钟楼失踪案' })).toBeVisible()
  await page.getByLabel('答案关键词').fill('夹层藏身，怀表')
  await page.getByRole('button', { name: '提交推理' }).click()
  await expect(page.getByText(/推理成立/)).toBeVisible()
})

test('write route exposes draft editor and safe preview', async ({ page }) => {
  await page.goto('/write')
  await expect(page.getByRole('heading', { name: '把你的观测记录放上来。' })).toBeVisible()
  await expect(page.getByLabel('正文 Markdown')).toBeVisible()
  await expect(page.getByText('实时预览')).toBeVisible()
})

test('admin route keeps review reason visible', async ({ page }) => {
  await page.route('**/api/admin/moderation/tasks**', route => route.fulfill({ json: { code: 'OK', message: 'success', data: [{ taskId: 1, postId: 1001, title: '雾港钟楼失踪案', queue: 'manual-review', riskLevel: 'MEDIUM', riskScore: 42, reason: '需要人工确认', status: 'PENDING' }] } }))
  await page.goto('/admin')
  await expect(page.getByRole('heading', { name: '内容审核台' })).toBeVisible()
  await expect(page.getByLabel('审核理由')).toBeVisible()
})

test('mobile viewport has no horizontal overflow and fixed bottom nav', async ({ page }) => {
  await page.goto('/')
  await expect(page.locator('.mobile-nav')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
})
