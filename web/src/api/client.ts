import { mockAudits, mockLeaderboard, mockNotifications, mockPosts, mockRecommendations } from './mock'
import type { ApiResponse, AuditItem, CreatePostRequest, CursorPage, DeductionAnswerResult, FeedItem, FileUploadResult, FollowResult, InteractionResult, LeaderboardEntry, ModerationTask, NotificationItem, PageResponse, Post, RecommendExperiment, RiskUser } from './types'

const token = 'Bearer demo-access-token-admin'

function markDegraded(feature: string): void {
  window.dispatchEvent(new CustomEvent('devcircle-api-degraded', { detail: feature }))
}

async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url, { headers: { Authorization: token } })
  if (!response.ok) throw new Error(`Request failed: ${response.status}`)
  const body = (await response.json()) as ApiResponse<T>
  return body.data
}

async function postJson<T>(url: string, payload?: unknown, extraHeaders: Record<string, string> = {}): Promise<T> {
  const response = await fetch(url, {
    method: 'POST',
    headers: { Authorization: token, 'Content-Type': 'application/json', ...extraHeaders },
    body: payload === undefined ? undefined : JSON.stringify(payload)
  })
  if (!response.ok) throw new Error(`Request failed: ${response.status}`)
  const body = (await response.json()) as ApiResponse<T>
  return body.data
}

function fallbackFeed(scene: string): CursorPage<FeedItem> {
  const themeMap: Record<string, string> = { deduction: 'DEDUCTION', sci_fi: 'SCI_FI', cosmos: 'COSMOS' }
  const records = scene === 'home' || scene === 'mixed'
    ? mockRecommendations
    : mockRecommendations.filter((item) => item.post.theme === themeMap[scene])
  return { records, nextCursor: String(records.length), hasMore: false, degraded: true, source: 'SEED_FALLBACK' }
}

export async function fetchPosts(type?: string): Promise<Post[]> {
  try {
    const query = type ? `?type=${encodeURIComponent(type)}` : ''
    return (await getJson<PageResponse<Post>>(`/api/posts${query}`)).records
  } catch {
    markDegraded('posts')
    return type ? mockPosts.filter((post) => post.type === type) : mockPosts
  }
}

export async function fetchFeed(scene = 'home', cursor?: string): Promise<CursorPage<FeedItem>> {
  try {
    return await getJson<CursorPage<FeedItem>>(`/api/recommend/feed?scene=${scene}&size=12${cursor ? `&cursor=${cursor}` : ''}`)
  } catch {
    markDegraded('feed')
    return fallbackFeed(scene)
  }
}

export async function recordExposures(postIds: number[], scene = 'home_feed'): Promise<void> {
  try {
    await postJson('/api/recommend/exposures', { userId: 2, postIds, scene })
  } catch {
  }
}

export async function recordRecommendEvent(item: FeedItem, eventType: 'click' | 'like' | 'favorite' | 'answer' | 'detail_view'): Promise<void> {
  try {
    await postJson('/api/recommend/events', {
      postId: item.post.id,
      eventType,
      scene: item.post.theme.toLowerCase(),
      experimentId: item.experimentId,
      bucket: item.bucket,
      requestId: `web-${Date.now()}`
    })
  } catch {
    markDegraded('recommend-event')
  }
}

export async function createPost(payload: CreatePostRequest): Promise<Post> {
  try {
    return await postJson<Post>('/api/posts', payload)
  } catch {
    markDegraded('create-post')
    return {
      id: Date.now(),
      type: payload.type ?? 'TEXT',
      theme: payload.theme ?? 'DEDUCTION',
      contentKind: payload.contentKind ?? 'CASE_FILE',
      difficulty: payload.difficulty ?? 'MEDIUM',
      solvable: payload.solvable ?? false,
      solvedCount: 0,
      title: payload.title,
      excerpt: payload.content.slice(0, 140),
      author: 'demo',
      tags: payload.tags,
      mediaAssets: [],
      likes: 0,
      comments: 0,
      views: 0,
      status: 'PENDING'
    }
  }
}

export async function uploadMedia(file: File): Promise<FileUploadResult> {
  const form = new FormData()
  form.append('file', file)
  try {
    const response = await fetch('/api/files', { method: 'POST', headers: { Authorization: token }, body: form })
    if (!response.ok) throw new Error(`Request failed: ${response.status}`)
    const body = (await response.json()) as ApiResponse<FileUploadResult>
    return body.data
  } catch {
    markDegraded('upload')
    return {
      mediaId: Date.now(),
      objectKey: `demo/${file.name}`,
      url: file.type.startsWith('video/') ? 'https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4' : 'https://picsum.photos/seed/upload-demo/720/960',
      contentType: file.type || 'image/png',
      size: file.size,
      width: file.type.startsWith('video/') ? 720 : 720,
      height: file.type.startsWith('video/') ? 1280 : 960,
      durationSeconds: file.type.startsWith('video/') ? 18 : undefined,
      status: 'PENDING_REVIEW'
    }
  }
}

export async function interact(postId: number, type: 'like' | 'favorite'): Promise<InteractionResult> {
  const idempotencyKey = `${type}-${postId}-${Date.now()}`
  try {
    return await postJson<InteractionResult>(`/api/posts/${postId}/${type}`, undefined, { 'Idempotency-Key': idempotencyKey })
  } catch {
    markDegraded('interaction')
    return { targetId: postId, type: type.toUpperCase(), active: true, duplicated: false, idempotencyKey }
  }
}

export async function followUser(userId: number): Promise<FollowResult> {
  try {
    return await postJson<FollowResult>(`/api/users/${userId}/follow`)
  } catch {
    markDegraded('follow')
    return { targetUserId: userId, followed: true, duplicated: false }
  }
}

export async function demoOAuthLogin(provider: 'github' | 'gitee'): Promise<string> {
  try {
    const response = await getJson<{ username: string }>(`/api/auth/oauth2/callback/${provider}?code=demo-code&state=devcircle`)
    return `${provider} 登录成功：${response.username}`
  } catch {
    markDegraded('oauth2')
    return `${provider} 演示登录成功：${provider}-user`
  }
}

export async function searchPosts(keyword: string): Promise<Post[]> {
  if (!keyword.trim()) return []
  try {
    return (await getJson<PageResponse<Post>>(`/api/search/posts?keyword=${encodeURIComponent(keyword)}`)).records
  } catch {
    markDegraded('search')
    return mockPosts.filter((post) => [post.title, post.excerpt, post.author, ...post.tags].some((value) => value.toLowerCase().includes(keyword.toLowerCase())))
  }
}

export async function fetchPostDetail(postId: number): Promise<Post> {
  try {
    return await getJson<Post>(`/api/posts/${postId}`)
  } catch {
    markDegraded('post-detail')
    return mockPosts.find((post) => post.id === postId) ?? mockPosts[0]
  }
}

export async function fetchRelatedPosts(postId: number): Promise<Post[]> {
  try {
    return await getJson<Post[]>(`/api/posts/${postId}/related?size=6`)
  } catch {
    markDegraded('related')
    const current = mockPosts.find((post) => post.id === postId) ?? mockPosts[0]
    return mockPosts
      .filter((post) => post.id !== postId)
      .filter((post) => post.theme === current.theme || post.tags.some((tag) => current.tags.includes(tag)))
      .slice(0, 6)
  }
}

export async function submitDeductionAnswer(postId: number, answer: string): Promise<DeductionAnswerResult> {
  try {
    return await postJson<DeductionAnswerResult>(`/api/deduction/cases/${postId}/answers`, { answer })
  } catch {
    markDegraded('deduction-answer')
    const correct = answer.includes('夹层') || answer.includes('钟楼')
    return {
      postId,
      userId: 2,
      correct,
      duplicated: false,
      points: correct ? 20 : 0,
      explanation: correct ? '推理成立：关键是停在 00:07 的怀表和钟楼夹层。' : '线索还没有闭合，建议重新检查怀表、鞋印和航海日志。'
    }
  }
}

export async function fetchLeaderboard(): Promise<LeaderboardEntry[]> {
  try {
    return await getJson<LeaderboardEntry[]>('/api/deduction/leaderboard')
  } catch {
    markDegraded('leaderboard')
    return mockLeaderboard
  }
}

export async function fetchAudits(): Promise<AuditItem[]> {
  try {
    return await getJson<AuditItem[]>('/api/admin/audits/posts')
  } catch {
    markDegraded('audits')
    return mockAudits
  }
}

export async function fetchModerationTasks(): Promise<ModerationTask[]> {
  try {
    return await getJson<ModerationTask[]>('/api/admin/moderation/tasks?status=PENDING&page=1&size=20')
  } catch {
    markDegraded('moderation-tasks')
    return [
      { taskId: 1, postId: 1002, title: '雾港钟楼的第十三声', queue: 'manual-review', riskLevel: 'MEDIUM', riskScore: 62, reason: '标题与正文高度重复', status: 'PENDING' },
      { taskId: 2, postId: 1043, title: '土卫六雨季城市设定稿', queue: 'fast-lane', riskLevel: 'LOW', riskScore: 18, reason: '常规机审通过后等待人工抽样', status: 'PENDING' }
    ]
  }
}

export async function moderationAction(taskId: number, action: 'approve' | 'reject' | 'escalate' | 'require-changes'): Promise<void> {
  try {
    await postJson(`/api/admin/moderation/tasks/${taskId}/${action}`)
  } catch {
    markDegraded(`moderation-${action}`)
  }
}

export async function fetchRiskUsers(): Promise<RiskUser[]> {
  try {
    return await getJson<RiskUser[]>('/api/admin/risk/users?page=1&size=20')
  } catch {
    markDegraded('risk-users')
    return [
      { userId: 12, status: 'WATCH', riskScore: 58, reason: '短时间高频发帖' },
      { userId: 18, status: 'NO_POST', riskScore: 91, reason: '多次命中敏感标签' }
    ]
  }
}

export async function fetchRecommendExperiments(): Promise<RecommendExperiment[]> {
  try {
    return await getJson<RecommendExperiment[]>('/api/admin/recommend/experiments')
  } catch {
    markDegraded('recommend-experiments')
    return [
      { experimentId: 'exp-theme-feed-v1', name: '主题内容多路召回实验', scene: 'home', status: 'ACTIVE', trafficPercent: 100 },
      { experimentId: 'exp-deduction-solve-v1', name: '推理馆解谜完成率实验', scene: 'deduction', status: 'ACTIVE', trafficPercent: 100 }
    ]
  }
}

export async function fetchNotifications(): Promise<NotificationItem[]> {
  try {
    return await getJson<NotificationItem[]>('/api/notifications')
  } catch {
    markDegraded('notifications')
    return mockNotifications
  }
}
