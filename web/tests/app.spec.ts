import { expect, test } from '@playwright/test'

const mojibakePattern = new RegExp('[\\uFFFD\\u951F\\u9428\\u93BA\\u93C4\\u7039\\u9359]')

const feedRecords = [
  {
    post: {
      id: 1001,
      type: 'TEXT',
      title: 'Spring Cloud 微服务里的 Outbox 可靠事件实践',
      excerpt: '用本地事务、Kafka 和消费端幂等替代 XA。',
      author: 'lin',
      tags: ['Spring Cloud', 'Kafka', 'MySQL'],
      mediaAssets: [],
      likes: 328,
      comments: 42,
      views: 9150,
      status: 'PUBLISHED'
    },
    reason: '你关注了 Spring Cloud 和可靠消息',
    score: 92,
    liked: false,
    favorited: false,
    algorithm: 'LOCAL_LINEAR_MODEL',
    mediaType: 'TEXT',
    features: { tagMatch: 1, hot: 0.8 }
  },
  {
    post: {
      id: 1002,
      type: 'IMAGE',
      title: 'Elasticsearch 搜索索引设计图解',
      excerpt: '用多图说明字段映射、标签过滤、排序和补偿重建策略。',
      author: 'chen',
      tags: ['Elasticsearch', '搜索'],
      mediaAssets: [{ mediaId: 201, objectKey: 'demo/es.webp', url: 'https://picsum.photos/seed/test-es/720/960', contentType: 'image/webp', size: 120000, width: 720, height: 960, status: 'PENDING_REVIEW' }],
      likes: 211,
      comments: 27,
      views: 6020,
      status: 'PUBLISHED'
    },
    reason: '图片内容和搜索标签匹配',
    score: 84,
    liked: false,
    favorited: false,
    algorithm: 'LOCAL_LINEAR_MODEL',
    mediaType: 'IMAGE',
    features: { tagMatch: 0.5, hot: 0.6 }
  },
  {
    post: {
      id: 1003,
      type: 'VIDEO',
      title: 'Kafka 事件驱动通知系统演示',
      excerpt: '纵向视频讲解站内信、WebSocket 和重复消费处理。',
      author: 'wang',
      tags: ['Kafka', 'WebSocket'],
      mediaAssets: [{ mediaId: 301, objectKey: 'demo/video.mp4', url: 'https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4', contentType: 'video/mp4', size: 1200000, width: 720, height: 1280, durationSeconds: 18, status: 'PENDING_REVIEW' }],
      likes: 97,
      comments: 14,
      views: 3100,
      status: 'PUBLISHED'
    },
    reason: '近期 Kafka 视频互动热度上升',
    score: 78,
    liked: false,
    favorited: false,
    algorithm: 'LOCAL_LINEAR_MODEL',
    mediaType: 'VIDEO',
    features: { tagMatch: 0.5, hot: 0.7 }
  }
]

test.beforeEach(async ({ page }) => {
  await page.route('**/api/recommend/feed**', (route) => {
    const url = new URL(route.request().url())
    const scene = url.searchParams.get('scene') ?? 'home'
    const records = scene === 'home' ? feedRecords : feedRecords.filter((item) => item.post.type.toLowerCase() === scene)
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ code: 'OK', message: 'success', data: { records, nextCursor: String(records.length), hasMore: false }, timestamp: new Date().toISOString() })
    })
  })
  await page.route('**/api/admin/audits/posts', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: [{ postId: 1002, title: 'Elasticsearch 搜索索引设计图解', status: 'PENDING', author: 'chen' }], timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/notifications', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: [{ id: 1, type: 'AUDIT', title: '文章审核通过', content: '你的文章已经进入推荐流。', read: false, createdAt: new Date().toISOString() }], timestamp: new Date().toISOString() }) })
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
  await page.route('**/api/files', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { mediaId: 5001, objectKey: 'demo/upload.png', url: 'https://picsum.photos/seed/upload/720/960', contentType: 'image/png', size: 10, width: 720, height: 960, status: 'PENDING_REVIEW' }, timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/posts', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { id: 2001, type: 'IMAGE', title: '测试发布图片内容', excerpt: '测试内容', author: 'demo', tags: ['Kafka'], mediaAssets: [], likes: 0, comments: 0, views: 0, status: 'PENDING' }, timestamp: new Date().toISOString() }) })
  )
  await page.route('**/api/search/posts**', (route) =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: 'success', data: { records: [feedRecords[1].post], total: 1, page: 1, size: 10 }, timestamp: new Date().toISOString() }) })
  )
})

test('loads recommendation feed and has no mojibake', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '推荐流' })).toBeVisible()
  await expect(page.getByText('Spring Cloud 微服务里的 Outbox 可靠事件实践')).toBeVisible()
  await expect(page.locator('body')).not.toContainText(mojibakePattern)
})

test('shows image waterfall and video feed', async ({ page }) => {
  await page.goto('/')
  await page.locator('.nav-item').nth(1).click()
  await expect(page.getByRole('heading', { name: '图片瀑布流' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Elasticsearch 搜索索引设计图解' })).toBeVisible()
  await page.locator('.nav-item').nth(2).click()
  await expect(page.getByRole('heading', { name: '视频 Feed' })).toBeVisible()
  await expect(page.locator('video')).toHaveCount(1)
})

test('searches, publishes, uploads, likes, follows and logs in', async ({ page }) => {
  await page.goto('/')
  await page.getByPlaceholder('搜索问题、文章、图片、视频和标签').fill('Elasticsearch')
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { name: '搜索结果' })).toBeVisible()

  await page.getByText('GitHub 登录').click()
  await expect(page.getByText(/github 登录成功/)).toBeVisible()
  await page.getByRole('button', { name: /关注/ }).first().click()
  await expect(page.getByText(/关注作者/)).toBeVisible()

  await page.getByPlaceholder('标题').fill('测试发布图片内容')
  await page.getByPlaceholder('正文，提交后写入帖子、媒体关联和 Outbox 事件').fill('测试内容')
  await page.setInputFiles('input[type="file"]', { name: 'cover.png', mimeType: 'image/png', buffer: Buffer.from('demo') })
  await expect(page.getByText(/媒体 5001 已上传/)).toBeVisible()
  await page.getByRole('main').getByRole('button', { name: '发布内容' }).click()
  await expect(page.getByText(/内容 #2001 已提交/)).toBeVisible()

  await page.getByRole('button', { name: /点赞/ }).first().click()
  await expect(page.getByRole('button', { name: /已赞/ }).first()).toBeVisible()
  await page.getByRole('button', { name: '收藏' }).first().click()
  await expect(page.getByRole('button', { name: '已收藏' }).first()).toBeVisible()
})

test('approves pending audit item and mobile viewport has no overflow', async ({ page, isMobile }) => {
  await page.goto('/')
  await page.getByRole('button', { name: '通过' }).click()
  await expect(page.getByText(/已通过内容/)).toBeVisible()
  await expect(page.locator('body')).not.toContainText(mojibakePattern)
  if (isMobile) {
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth)
    expect(overflow).toBe(false)
  }
})
