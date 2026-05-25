import { expect, test } from '@playwright/test'

const mojibakePattern = new RegExp('[\\uFFFD\\u951F\\u9428\\u93BA\\u93C4\\u7039\\u9359]')

test.beforeEach(async ({ page }) => {
  await page.route('**/api/recommend/feed**', (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'OK',
        message: 'success',
        data: {
          records: [
            {
              post: {
                id: 1001,
                title: 'Spring Cloud 微服务里的 Outbox 可靠事件实践',
                excerpt: '用本地事务、Kafka 和消费端幂等替代 XA，保证内容发布、搜索索引和通知推送最终一致。',
                author: 'lin',
                tags: ['Spring Cloud', 'Kafka', 'MySQL'],
                likes: 328,
                comments: 42,
                views: 9150,
                status: 'PUBLISHED'
              },
              reason: '你关注了 Spring Cloud 和可靠消息',
              score: 92,
              liked: false,
              favorited: false
            },
            {
              post: {
                id: 1003,
                title: 'Redis 热点缓存、限流和点赞幂等的工程边界',
                excerpt: '从详情缓存、布隆过滤、令牌桶到互动服务的幂等键设计。',
                author: 'zhou',
                tags: ['Redis', '限流'],
                likes: 156,
                comments: 18,
                views: 3980,
                status: 'PUBLISHED'
              },
              reason: '与你收藏的缓存文章相似',
              score: 84,
              liked: false,
              favorited: false
            }
          ],
          nextCursor: '2',
          hasMore: false
        },
        timestamp: new Date().toISOString()
      })
    })
  )
  await page.route('**/api/admin/audits/posts', (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'OK',
        message: 'success',
        data: [{ postId: 1002, title: 'Elasticsearch 技术社区搜索索引怎么设计', status: 'PENDING', author: 'chen' }],
        timestamp: new Date().toISOString()
      })
    })
  )
  await page.route('**/api/notifications', (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'OK',
        message: 'success',
        data: [{ id: 1, type: 'AUDIT', title: '文章审核通过', content: '你的文章已经进入推荐流。', read: false, createdAt: new Date().toISOString() }],
        timestamp: new Date().toISOString()
      })
    })
  )
  await page.route('**/api/recommend/exposures', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { recorded: 2 }, timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/posts/*/like', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { targetId: 1001, type: 'LIKE', active: true, duplicated: false, idempotencyKey: 'test' }, timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/posts/*/favorite', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { targetId: 1001, type: 'FAVORITE', active: true, duplicated: false, idempotencyKey: 'test' }, timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/posts', (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'OK',
        message: 'success',
        data: { id: 2001, title: '测试发布文章', excerpt: '测试内容', author: 'demo', tags: ['Kafka'], likes: 0, comments: 0, views: 0, status: 'PENDING' },
        timestamp: new Date().toISOString()
      })
    })
  )
})

test('renders feed and filters by keyword without mojibake', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '推荐流' })).toBeVisible()
  await expect(page.locator('body')).not.toContainText(mojibakePattern)
  await page.getByPlaceholder('搜索问题、文章和标签').fill('Redis')
  await expect(page.getByText('Redis 热点缓存、限流和点赞幂等的工程边界')).toBeVisible()
  await expect(page.getByText('Spring Cloud 微服务里的 Outbox 可靠事件实践')).not.toBeVisible()
})

test('approves pending audit item', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: '通过' }).click()
  await expect(page.getByText(/已通过内容/)).toBeVisible()
})

test('publishes, likes and favorites a feed post', async ({ page }) => {
  await page.goto('/')
  await page.getByPlaceholder('标题').fill('测试发布文章')
  await page.getByPlaceholder('正文，提交后写入帖子和 Outbox 事件').fill('测试内容')
  await page.getByRole('button', { name: '发布文章' }).click()
  await expect(page.getByText('文章 #2001 已提交，状态：PENDING，已进入审核和事件队列')).toBeVisible()
  await expect(page.locator('body')).not.toContainText(mojibakePattern)
  await page.getByRole('button', { name: '点赞' }).first().click()
  await expect(page.getByRole('button', { name: '已赞' }).first()).toBeVisible()
  await page.getByRole('button', { name: '收藏' }).first().click()
  await expect(page.getByRole('button', { name: '已收藏' }).first()).toBeVisible()
})

test('mobile viewport has no horizontal overflow', async ({ page, isMobile }) => {
  test.skip(!isMobile, 'mobile-only layout assertion')
  await page.goto('/')
  await expect(page.locator('body')).not.toContainText(mojibakePattern)
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)
  expect(overflow).toBe(false)
})
