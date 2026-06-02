import type { AuditItem, FeedItem, LeaderboardEntry, NotificationItem, Post } from './types'

const authors = ['林墨', '顾白', '沈岚', '陈星', '许澜', '陆离', '王遥', '周望', '闻舟', '高远']
const difficulties = ['EASY', 'MEDIUM', 'HARD'] as const

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

const video = (id: number, seed: string) => ({
  mediaId: id,
  objectKey: `demo/${seed}.mp4`,
  url: 'https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4',
  contentType: 'video/mp4',
  size: 1200000 + id,
  width: 720,
  height: 1280,
  durationSeconds: 18,
  status: 'PENDING_REVIEW'
})

function relatedIds(base: number, index: number, count = 40) {
  return [base + ((index + 1) % count), base + ((index + 7) % count), base + ((index + 13) % count)]
}

function minute(index: number, seed: number) {
  return String((index * 7 + seed) % 60).padStart(2, '0')
}

function deduction(index: number): Post {
  const places = ['雾港钟楼', '蓝线列车', '旧书店密室', '雪夜旅馆', '镜厅剧院', '孤岛灯塔', '地下档案室', '黑河码头', '红杉疗养院', '北境邮局']
  const hooks = ['失踪案', '无声证词', '红色书签', '第十三间房', '倒影谋杀', '潮汐密室', '缺页卷宗', '午夜脚印', '匿名电报', '封存照片']
  const series = ['密室', '时间线', '证词矛盾', '物证误导', '密码谜题', '航海', '列车', '剧院', '档案室', '冷案复盘']
  const methods = ['夹层藏身', '调换时钟', '伪造脚印', '镜面反射', '潮汐延迟', '证词串供', '密码替换', '灯塔盲区', '列车换厢', '档案重排']
  const objects = ['怀表', '航海日志', '湿鞋印', '红色书签', '破裂镜片', '煤油灯芯', '车票孔位', '锁孔铜屑', '相机底片', '电报时间戳']
  const place = places[index % places.length]
  const method = methods[index % methods.length]
  const object = objects[index % objects.length]
  const id = 1001 + index
  return {
    id,
    type: 'TEXT',
    theme: 'DEDUCTION',
    contentKind: index % 3 === 1 ? 'PUZZLE' : 'CASE_FILE',
    difficulty: difficulties[index % 3],
    solvable: true,
    solvedCount: 18 + index,
    title: `${place}${hooks[index % hooks.length]}`,
    excerpt: `一桩围绕${place}展开的${series[index % series.length]}推理内容，线索覆盖时间线、证词、物证和现场痕迹。`,
    author: authors[index % authors.length],
    tags: ['推理', series[index % series.length], method, index % 3 === 1 ? '逻辑谜题' : '案件档案'],
    mediaAssets: [],
    likes: 220 + index * 11,
    comments: 30 + (index % 35),
    views: 5200 + index * 360,
    status: 'PUBLISHED',
    clues: [
      `${object}与公开记录的时间不一致`,
      `时间线在 00:${minute(index, 7)} 到 00:${minute(index, 19)} 之间出现空档`,
      `两名证人的描述都避开了${place}的后门`,
      '现场只留下向内的擦痕，没有对应的离开痕迹',
      `第 ${(index % 7) + 1} 份记录被人故意改写了顺序`
    ],
    suspects: [`${place}管理员`, '钟表匠许临', '记者白棠', '码头调度员周野'],
    timeline: [
      `22:${minute(index, 11)} 受害者最后一次公开露面`,
      `23:${minute(index, 23)} 关键证人离开现场`,
      `00:${minute(index, 7)} 现场出现第一次异常`,
      `00:${minute(index, 19)} 巡查人员封锁入口`,
      `01:${minute(index, 31)} 档案员发现记录被调换`
    ],
    question: '谁制造了关键的不在场证明？真正的手法和决定性证据分别是什么？',
    answerKeywords: [method, object, '时间差'],
    explanation: `关键不是最显眼的证词，而是${object}与时间线空档之间的矛盾。凶手利用${method}制造时间差。`,
    relatedPostIds: relatedIds(1001, index, 100)
  }
}

function scifi(index: number): Post {
  const worlds = ['木卫二冰下城', '环土星共和国', '火星雨季穹顶', '近光速方舟', '仿生人记忆庭', '月背低温港', '金星云海电梯', '深空电台']
  const kinds = ['设定集', '舰队识别图谱', '短篇', '殖民纪要', '技术白皮书', '派系档案', '城市地图', '通信残章']
  const world = worlds[index % worlds.length]
  const id = 2001 + index
  const type = index % 5 === 0 ? 'VIDEO' : index % 3 === 0 ? 'IMAGE' : 'TEXT'
  return {
    id,
    type,
    theme: 'SCI_FI',
    contentKind: index % 2 === 0 ? 'WORLDBUILDING' : 'FICTION',
    difficulty: difficulties[index % 3],
    solvable: false,
    solvedCount: 0,
    title: `${world}${kinds[index % kinds.length]}`,
    excerpt: `围绕${world}展开的科幻内容，包含社会结构、技术设定、人物冲突和可继续扩写的章节钩子。`,
    author: authors[(index + 3) % authors.length],
    tags: ['科幻', index % 2 === 0 ? '世界观' : '短篇', world.slice(0, 3)],
    mediaAssets: type === 'IMAGE' ? [image(id, `scifi-${index}`, 860 + (index % 4) * 70)] : type === 'VIDEO' ? [video(id, `scifi-${index}`)] : [],
    likes: 210 + index * 8,
    comments: 18 + index,
    views: 4100 + index * 390,
    status: 'PUBLISHED',
    worldbuilding: [`核心地点：${world}`, '技术概念：潮汐能源、记忆备份或低温生态', '冲突来源：自治城市、航线垄断和身份边界', '可扩展方向：章节连载、派系年表、装备图谱'],
    relatedPostIds: relatedIds(2001, index)
  }
}

function cosmos(index: number): Post {
  const objects = ['银河系旋臂', '猎户座星云', '暗物质晕', '旅行者号航迹', '脉冲星灯塔', '系外行星海洋', '超新星遗迹', '柯伊伯带边界']
  const angles = ['档案', '观测日志', '科普札记', '任务简报', '异常信号', '影像精选', '数据卡', '深空路线']
  const object = objects[index % objects.length]
  const id = 3001 + index
  const type = index % 4 === 0 ? 'IMAGE' : index % 6 === 0 ? 'VIDEO' : 'TEXT'
  return {
    id,
    type,
    theme: 'COSMOS',
    contentKind: index % 2 === 0 ? 'COSMIC_ARTICLE' : 'OBSERVATION_LOG',
    difficulty: difficulties[index % 3],
    solvable: false,
    solvedCount: 0,
    title: `${object}${angles[index % angles.length]}`,
    excerpt: `一份关于${object}的宇宙档案，整理观测背景、关键现象、科普解释和可视化阅读线索。`,
    author: authors[(index + 6) % authors.length],
    tags: ['宇宙', index % 2 === 0 ? '档案' : '观测', object.slice(0, 3)],
    mediaAssets: type === 'IMAGE' ? [image(id, `cosmos-${index}`, 900 + (index % 5) * 50)] : type === 'VIDEO' ? [video(id, `cosmos-${index}`)] : [],
    likes: 190 + index * 10,
    comments: 16 + index,
    views: 3800 + index * 430,
    status: 'PUBLISHED',
    cosmicFacts: [`天体对象：${object}`, '观测手段：光谱、引力透镜、射电或深空探测器', '核心问题：它如何改变我们对宇宙结构的理解', '延伸阅读：任务年表、图像档案和相关现象'],
    relatedPostIds: relatedIds(3001, index)
  }
}

export const mockPosts: Post[] = [
  ...Array.from({ length: 100 }, (_, index) => deduction(index)),
  ...Array.from({ length: 40 }, (_, index) => scifi(index)),
  ...Array.from({ length: 40 }, (_, index) => cosmos(index))
]

export const mockRecommendations: FeedItem[] = mockPosts.map((post, index) => ({
  post,
  reason: post.solvable
    ? `可解谜案件，已有 ${post.solvedCount} 人完成推理`
    : post.theme === 'SCI_FI'
      ? (post.contentKind === 'WORLDBUILDING' ? '世界观设定完整度高，适合系列共创' : '科幻短篇讨论活跃')
      : '宇宙档案热度上升，适合延伸阅读',
  score: 140 - Math.min(index, 100) * 0.6 + post.likes / 120,
  liked: false,
  favorited: false,
  algorithm: 'THEME_LINEAR_MODEL',
  mediaType: post.type,
  experimentId: post.theme === 'DEDUCTION' ? 'exp-deduction-solve-v1' : 'exp-theme-feed-v1',
  bucket: index % 2 === 0 ? 'control' : 'treatment',
  recallSources: post.theme === 'DEDUCTION' ? ['theme', 'solvable', 'difficulty'] : ['theme', 'hot'],
  features: {
    themeAffinity: post.theme === 'DEDUCTION' ? 0.96 : 0.75,
    hot: Math.min(1, post.likes / 700),
    freshness: Math.max(0.2, 1 - index * 0.003),
    solvedSignal: post.solvable ? Math.min(1, post.solvedCount / 120) : 0
  }
}))

export const mockAudits: AuditItem[] = mockPosts.slice(0, 6).map((post) => ({
  postId: post.id,
  title: post.title,
  status: 'PENDING',
  author: post.author
}))

export const mockNotifications: NotificationItem[] = [
  { id: 1, type: 'DEDUCTION', title: '推理答案已判定', content: '你提交的钟楼案答案获得 20 积分。', read: false, createdAt: new Date().toISOString() },
  { id: 2, type: 'SCI_FI', title: '科幻设定收到评论', content: '陈星回复了你的木卫二城市设定建议。', read: true, createdAt: new Date().toISOString() },
  { id: 3, type: 'COSMOS', title: '宇宙档案被收藏', content: '银河系旋臂档案进入本周精选。', read: false, createdAt: new Date().toISOString() },
  { id: 4, type: 'SYSTEM', title: '推理馆内容扩充完成', content: '100 条推理案件已同步到主题内容库。', read: true, createdAt: new Date().toISOString() }
]

export const mockLeaderboard: LeaderboardEntry[] = [
  { userId: 7, username: '林墨', correctCount: 58, totalPoints: 1160 },
  { userId: 12, username: '顾白', correctCount: 46, totalPoints: 920 },
  { userId: 18, username: '沈岚', correctCount: 39, totalPoints: 780 },
  { userId: 2, username: 'demo', correctCount: 12, totalPoints: 240 }
]
