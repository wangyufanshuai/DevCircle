import type { AuditItem, NotificationItem, Post, RecommendedPost } from './types'

export const mockPosts: Post[] = [
  {
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
  {
    id: 1002,
    title: 'Elasticsearch 技术社区搜索索引怎么设计',
    excerpt: '帖子标题、正文、标签、作者和热度分如何共同影响搜索结果排序。',
    author: 'chen',
    tags: ['Elasticsearch', '搜索'],
    likes: 211,
    comments: 27,
    views: 6020,
    status: 'PENDING'
  },
  {
    id: 1003,
    title: 'Redis 热点缓存、限流和点赞幂等的工程边界',
    excerpt: '从详情缓存、布隆过滤、令牌桶到互动服务的幂等键设计。',
    author: 'zhou',
    tags: ['Redis', '限流'],
    likes: 156,
    comments: 18,
    views: 3980,
    status: 'PUBLISHED'
  }
]

export const mockRecommendations: RecommendedPost[] = mockPosts.map((post, index) => ({
  post,
  reason: ['你关注了 Spring Cloud 和可靠消息', '近期搜索热度上升', '与你收藏的缓存文章相似'][index],
  score: 0.92 - index * 0.08
}))

export const mockAudits: AuditItem[] = [
  { postId: 1002, title: 'Elasticsearch 技术社区搜索索引怎么设计', status: 'PENDING', author: 'chen' }
]

export const mockNotifications: NotificationItem[] = [
  { id: 1, type: 'AUDIT', title: '文章审核通过', content: '你的文章已经进入推荐流。', read: false, createdAt: new Date().toISOString() },
  { id: 2, type: 'INTERACTION', title: '收到新的评论', content: 'zhou 评论了你的文章。', read: true, createdAt: new Date().toISOString() }
]

