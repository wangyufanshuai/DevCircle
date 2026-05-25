import type { AuditItem, FeedItem, LeaderboardEntry, NotificationItem, Post } from './types'

const image = (id: number, seed: string, height = 960) => ({
  mediaId: id,
  objectKey: `demo/${seed}.webp`,
  url: `https://picsum.photos/seed/${seed}/720/${height}`,
  contentType: 'image/webp',
  size: 120000 + id,
  width: 720,
  height,
  status: 'PENDING_REVIEW'
})

const video = (id: number) => ({
  mediaId: id,
  objectKey: `demo/video-${id}.mp4`,
  url: 'https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4',
  contentType: 'video/mp4',
  size: 1200000 + id,
  width: 720,
  height: 1280,
  durationSeconds: 18,
  status: 'PENDING_REVIEW'
})

export const mockPosts: Post[] = [
  {
    id: 1001,
    type: 'TEXT',
    theme: 'DEDUCTION',
    contentKind: 'CASE_FILE',
    difficulty: 'HARD',
    solvable: true,
    solvedCount: 31,
    title: '雾港钟楼失踪案',
    excerpt: '凌晨零点钟声响起后，守夜人从封闭钟楼消失，只留下停在 00:07 的怀表、潮湿鞋印和一页被撕掉的航海日志。',
    author: '林墨',
    tags: ['密室', '时间线', '海港'],
    mediaAssets: [],
    likes: 428,
    comments: 86,
    views: 15800,
    status: 'PUBLISHED'
  },
  {
    id: 1002,
    type: 'TEXT',
    theme: 'DEDUCTION',
    contentKind: 'PUZZLE',
    difficulty: 'MEDIUM',
    solvable: true,
    solvedCount: 54,
    title: '列车第七节车厢的无声证词',
    excerpt: '三名乘客都说自己没有离座，但餐车账单、雨伞水痕和被调快的车厢钟暴露了真正的移动路线。',
    author: '顾白',
    tags: ['逻辑谜题', '列车', '证词'],
    mediaAssets: [],
    likes: 351,
    comments: 64,
    views: 12200,
    status: 'PUBLISHED'
  },
  {
    id: 1003,
    type: 'IMAGE',
    theme: 'SCI_FI',
    contentKind: 'WORLDBUILDING',
    difficulty: 'MEDIUM',
    solvable: false,
    solvedCount: 0,
    title: '木卫二冰下城市设定集',
    excerpt: '多图拆解冰壳钻井、潮汐能源塔、低温生态穹顶和迁徙路线，适合科幻世界观共创。',
    author: '陈星',
    tags: ['世界观', '木卫二', '城市'],
    mediaAssets: [image(201, 'europa-city-1', 960), image(202, 'europa-city-2', 820), image(203, 'europa-city-3', 1040)],
    likes: 312,
    comments: 44,
    views: 9360,
    status: 'PUBLISHED'
  },
  {
    id: 1004,
    type: 'TEXT',
    theme: 'SCI_FI',
    contentKind: 'FICTION',
    difficulty: 'EASY',
    solvable: false,
    solvedCount: 0,
    title: '短篇：给火星雨季的最后一封信',
    excerpt: '通信延迟二十二分钟，地球回信抵达时，火星穹顶外的雨已经变成蓝色冰晶。',
    author: '许澜',
    tags: ['火星', '短篇', '移民'],
    mediaAssets: [],
    likes: 489,
    comments: 91,
    views: 18200,
    status: 'PUBLISHED'
  },
  {
    id: 1005,
    type: 'VIDEO',
    theme: 'COSMOS',
    contentKind: 'OBSERVATION_LOG',
    difficulty: 'EASY',
    solvable: false,
    solvedCount: 0,
    title: '猎户臂边缘的一分钟观测日志',
    excerpt: '用纵向视频记录星云光谱、脉冲星信号和无人探测器回传的异常闪烁。',
    author: '王遥',
    tags: ['观测日志', '星云', '探测器'],
    mediaAssets: [video(301)],
    likes: 197,
    comments: 28,
    views: 6100,
    status: 'PUBLISHED'
  },
  {
    id: 1006,
    type: 'IMAGE',
    theme: 'COSMOS',
    contentKind: 'COSMIC_ARTICLE',
    difficulty: 'MEDIUM',
    solvable: false,
    solvedCount: 0,
    title: '银河系旋臂档案',
    excerpt: '把恒星形成区、尘埃带、超新星遗迹和航线窗口整理成一组可读的宇宙档案卡。',
    author: '周望',
    tags: ['银河系', '旋臂', '档案'],
    mediaAssets: [image(401, 'galaxy-arm-1', 900), image(402, 'galaxy-arm-2', 780)],
    likes: 265,
    comments: 39,
    views: 8400,
    status: 'PUBLISHED'
  },
  {
    id: 1007,
    type: 'TEXT',
    theme: 'DEDUCTION',
    contentKind: 'CASE_FILE',
    difficulty: 'MEDIUM',
    solvable: true,
    solvedCount: 18,
    title: '旧书店的红色书签',
    excerpt: '死者夹在《时间机器》里的红色书签不是标记页码，而是指向书架倒影里的第四个名字。',
    author: '沈岚',
    tags: ['书店', '倒影', '暗号'],
    mediaAssets: [],
    likes: 284,
    comments: 51,
    views: 9900,
    status: 'PUBLISHED'
  },
  {
    id: 1008,
    type: 'IMAGE',
    theme: 'SCI_FI',
    contentKind: 'WORLDBUILDING',
    difficulty: 'HARD',
    solvable: false,
    solvedCount: 0,
    title: '环土星共和国舰队识别图谱',
    excerpt: '战术舰、农业环舱、远距通信帆和自治港口的视觉设定，包含编号体系和政治派系。',
    author: '陆离',
    tags: ['舰队', '土星', '设定'],
    mediaAssets: [image(501, 'saturn-fleet-1', 1100), image(502, 'saturn-fleet-2', 860)],
    likes: 378,
    comments: 70,
    views: 14600,
    status: 'PUBLISHED'
  },
  {
    id: 1009,
    type: 'TEXT',
    theme: 'COSMOS',
    contentKind: 'COSMIC_ARTICLE',
    difficulty: 'HARD',
    solvable: false,
    solvedCount: 0,
    title: '如果暗物质有天气',
    excerpt: '用可解释模型模拟暗物质晕的密度波动，讨论它会怎样影响星系盘、引力透镜和恒星流。',
    author: '闻舟',
    tags: ['暗物质', '科普', '星系'],
    mediaAssets: [],
    likes: 421,
    comments: 73,
    views: 16900,
    status: 'PUBLISHED'
  },
  {
    id: 1010,
    type: 'VIDEO',
    theme: 'SCI_FI',
    contentKind: 'FICTION',
    difficulty: 'MEDIUM',
    solvable: false,
    solvedCount: 0,
    title: '垂直短剧：仿生人第九次醒来',
    excerpt: '每次重启都少一段记忆，但墙上的宇宙微波背景图多出一个红点。',
    author: '白夜',
    tags: ['仿生人', '短剧', '记忆'],
    mediaAssets: [video(601)],
    likes: 236,
    comments: 42,
    views: 7800,
    status: 'PUBLISHED'
  },
  {
    id: 1011,
    type: 'TEXT',
    theme: 'DEDUCTION',
    contentKind: 'PUZZLE',
    difficulty: 'EASY',
    solvable: true,
    solvedCount: 77,
    title: '三枚硬币和一盏坏掉的路灯',
    excerpt: '不用复杂数学，只靠目击者站位、路灯闪烁频率和硬币落点就能排除两个假证词。',
    author: '叶青',
    tags: ['入门谜题', '目击者', '硬币'],
    mediaAssets: [],
    likes: 229,
    comments: 33,
    views: 7200,
    status: 'PUBLISHED'
  },
  {
    id: 1012,
    type: 'IMAGE',
    theme: 'COSMOS',
    contentKind: 'OBSERVATION_LOG',
    difficulty: 'MEDIUM',
    solvable: false,
    solvedCount: 0,
    title: '旅行者号未归档影像精选',
    excerpt: '用图像卡片展示深空探测器可能经过的尘埃云、冰巨行星边缘和恒星际介质。',
    author: '高远',
    tags: ['旅行者', '深空', '影像'],
    mediaAssets: [image(701, 'voyager-1', 980), image(702, 'voyager-2', 840), image(703, 'voyager-3', 1060)],
    likes: 318,
    comments: 49,
    views: 10800,
    status: 'PUBLISHED'
  }
]

const reasons = [
  '可解谜案件，已有 31 人完成推理',
  '逻辑谜题热度上升，适合快速挑战',
  '科幻世界观设定被多次收藏',
  '短篇阅读完成率高，近期讨论活跃',
  '宇宙观测日志进入编辑精选',
  '银河档案与深空关键词匹配',
  '推理馆新案，线索完整度高',
  '环土星设定图谱互动热度高',
  '宇宙科普主题近期搜索上升',
  '科幻短剧适合纵向 Feed 展示',
  '入门谜题完成率高，适合新用户',
  '深空影像与宇宙档案偏好相关'
]

export const mockRecommendations: FeedItem[] = mockPosts.map((post, index) => ({
  post,
  reason: reasons[index],
  score: 98 - index * 3,
  liked: false,
  favorited: false,
  algorithm: 'THEME_LINEAR_MODEL',
  mediaType: post.type,
  features: {
    themeAffinity: post.theme === 'DEDUCTION' ? 0.9 : 0.7,
    hot: Math.min(1, post.likes / 520),
    freshness: Math.max(0.2, 1 - index * 0.04),
    solvedSignal: post.solvable ? Math.min(1, post.solvedCount / 80) : 0
  }
}))

export const mockAudits: AuditItem[] = [
  { postId: 1003, title: '木卫二冰下城市设定集', status: 'PENDING', author: '陈星' },
  { postId: 1010, title: '垂直短剧：仿生人第九次醒来', status: 'PENDING', author: '白夜' },
  { postId: 1001, title: '雾港钟楼失踪案', status: 'PENDING', author: '林墨' }
]

export const mockNotifications: NotificationItem[] = [
  { id: 1, type: 'DEDUCTION', title: '推理答案已判定', content: '你提交的钟楼案答案获得 20 积分。', read: false, createdAt: new Date().toISOString() },
  { id: 2, type: 'INTERACTION', title: '科幻设定收到评论', content: '陈星回复了你的木卫二城市设定建议。', read: true, createdAt: new Date().toISOString() },
  { id: 3, type: 'FOLLOW', title: '新增关注者', content: '王遥关注了你的宇宙档案。', read: false, createdAt: new Date().toISOString() },
  { id: 4, type: 'SYSTEM', title: '主题索引重建完成', content: '推理、科幻、宇宙内容已同步到搜索索引。', read: true, createdAt: new Date().toISOString() }
]

export const mockLeaderboard: LeaderboardEntry[] = [
  { userId: 7, username: '林墨', correctCount: 19, totalPoints: 380 },
  { userId: 12, username: '陈星', correctCount: 14, totalPoints: 280 },
  { userId: 18, username: '王遥', correctCount: 11, totalPoints: 220 },
  { userId: 2, username: 'demo', correctCount: 6, totalPoints: 120 }
]
