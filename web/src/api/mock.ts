import type { AuditItem, FeedItem, NotificationItem, Post } from './types'

export const mockPosts: Post[] = [
  {
    id: 1001,
    type: 'TEXT',
    title: 'Spring Cloud 微服务里的 Outbox 可靠事件实践',
    excerpt: '用本地事务、Kafka 和消费端幂等替代 XA，保证内容发布、搜索索引和通知推送最终一致。',
    author: 'lin',
    tags: ['Spring Cloud', 'Kafka', 'MySQL'],
    mediaAssets: [],
    likes: 328,
    comments: 42,
    views: 9150,
    status: 'PUBLISHED'
  },
  {
    id: 1002,
    type: 'IMAGE',
    title: 'Elasticsearch 搜索索引设计图解',
    excerpt: '用多图说明字段映射、标签过滤、排序和补偿重建策略。',
    author: 'chen',
    tags: ['Elasticsearch', '搜索'],
    mediaAssets: [
      { mediaId: 201, objectKey: 'demo/es-1.webp', url: 'https://picsum.photos/seed/devcircle-es-1/720/960', contentType: 'image/webp', size: 120000, width: 720, height: 960, status: 'PENDING_REVIEW' },
      { mediaId: 202, objectKey: 'demo/es-2.webp', url: 'https://picsum.photos/seed/devcircle-es-2/720/820', contentType: 'image/webp', size: 118000, width: 720, height: 820, status: 'PENDING_REVIEW' }
    ],
    likes: 211,
    comments: 27,
    views: 6020,
    status: 'PUBLISHED'
  },
  {
    id: 1003,
    type: 'VIDEO',
    title: 'Kafka 事件驱动通知系统演示',
    excerpt: '纵向视频讲解站内信、WebSocket 和重复消费处理。',
    author: 'wang',
    tags: ['Kafka', 'WebSocket'],
    mediaAssets: [
      { mediaId: 301, objectKey: 'demo/kafka-video.mp4', url: 'https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4', contentType: 'video/mp4', size: 1200000, width: 720, height: 1280, durationSeconds: 18, status: 'PENDING_REVIEW' }
    ],
    likes: 97,
    comments: 14,
    views: 3100,
    status: 'PUBLISHED'
  }
]

export const mockRecommendations: FeedItem[] = mockPosts.map((post, index) => ({
  post,
  reason: ['你关注了 Spring Cloud 和可靠消息', '图片内容和搜索标签匹配', '近期 Kafka 视频互动热度上升'][index],
  score: 92 - index * 8,
  liked: false,
  favorited: false,
  algorithm: 'LOCAL_LINEAR_MODEL',
  mediaType: post.type,
  features: { tagMatch: index === 0 ? 1 : 0.5, hot: 0.6, freshness: 0.8, followedAuthor: index === 0 ? 1 : 0 }
}))

export const mockAudits: AuditItem[] = [
  { postId: 1002, title: 'Elasticsearch 搜索索引设计图解', status: 'PENDING', author: 'chen' }
]

export const mockNotifications: NotificationItem[] = [
  { id: 1, type: 'AUDIT', title: '文章审核通过', content: '你的文章已经进入推荐流。', read: false, createdAt: new Date().toISOString() },
  { id: 2, type: 'INTERACTION', title: '收到新的评论', content: 'zhou 评论了你的文章。', read: true, createdAt: new Date().toISOString() }
]
