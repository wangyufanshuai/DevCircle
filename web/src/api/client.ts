import { mockAudits, mockLeaderboard, mockNotifications, mockPosts, mockRecommendations } from './mock'
import type {
  ApiResponse,
  AuditItem,
  CreatePostRequest,
  CursorPage,
  DeductionAnswerResult,
  DegradationNotice,
  DegradationOverview,
  FeedItem,
  FileUploadResult,
  FollowResult,
  InteractionResult,
  LeaderboardEntry,
  ModerationTask,
  NotificationItem,
  OpsHealth,
  OutboxEvent,
  PageResponse,
  Post,
  RecommendExperiment,
  RiskUser
} from './types'

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')
const DEMO_TOKEN = import.meta.env.VITE_DEMO_TOKEN ?? 'Bearer demo-access-token-demo'
const DEMO_ADMIN_TOKEN = import.meta.env.VITE_DEMO_ADMIN_TOKEN ?? 'Bearer demo-access-token-admin'
const ENABLE_DEMO_FALLBACK = import.meta.env.VITE_ENABLE_DEMO_FALLBACK !== 'false'
const TOKEN_KEY = 'devcircle.accessToken'

export function currentToken(): string {
  return localStorage.getItem(TOKEN_KEY) || DEMO_ADMIN_TOKEN || DEMO_TOKEN
}

export function setAccessToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

function endpoint(path: string): string {
  return path.startsWith('http') ? path : `${API_BASE_URL}${path}`
}

function markDegraded(feature: string, reason: string, source = 'MOCK_FALLBACK'): void {
  const detail: DegradationNotice = { feature, source, reason, at: new Date().toISOString() }
  window.dispatchEvent(new CustomEvent('devcircle-api-degraded', { detail }))
}

async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Authorization', currentToken())
  if (init.body !== undefined && !headers.has('Content-Type') && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }
  const response = await fetch(endpoint(path), { ...init, headers })
  if (!response.ok) throw new Error(`HTTP ${response.status} ${response.statusText}`)
  const body = (await response.json()) as ApiResponse<T>
  if (body.code !== 'OK') throw new Error(body.message || body.code)
  return body.data
}

async function getJson<T>(path: string): Promise<T> {
  return requestJson<T>(path)
}

async function postJson<T>(path: string, payload?: unknown, extraHeaders: Record<string, string> = {}): Promise<T> {
  return requestJson<T>(path, {
    method: 'POST',
    headers: extraHeaders,
    body: payload === undefined ? undefined : JSON.stringify(payload)
  })
}

function fallbackAllowed(feature: string, error: unknown): void {
  if (!ENABLE_DEMO_FALLBACK) throw error
  markDegraded(feature, error instanceof Error ? error.message : '后端接口不可用')
}

function fallbackFeed(scene: string, reason = '后端推荐接口不可用'): CursorPage<FeedItem> {
  const themeMap: Record<string, string> = { deduction: 'DEDUCTION', sci_fi: 'SCI_FI', cosmos: 'COSMOS' }
  const records = scene === 'home' || scene === 'mixed'
    ? mockRecommendations
    : mockRecommendations.filter((item) => item.post.theme === themeMap[scene])
  return { records, nextCursor: String(records.length), hasMore: false, degraded: true, source: 'MOCK_FALLBACK', degradationReason: reason }
}

export async function fetchPosts(type?: string): Promise<Post[]> {
  try {
    const query = type ? `?type=${encodeURIComponent(type)}` : ''
    return (await getJson<PageResponse<Post>>(`/api/posts${query}`)).records
  } catch (error) {
    fallbackAllowed('posts', error)
    return type ? mockPosts.filter((post) => post.type === type) : mockPosts
  }
}

export async function fetchFeed(scene = 'home', cursor?: string): Promise<CursorPage<FeedItem>> {
  try {
    return await getJson<CursorPage<FeedItem>>(`/api/recommend/feed?scene=${scene}&size=12${cursor ? `&cursor=${cursor}` : ''}`)
  } catch (error) {
    fallbackAllowed('feed', error)
    return fallbackFeed(scene, error instanceof Error ? error.message : '推荐接口不可用')
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
  } catch (error) {
    fallbackAllowed('recommend-event', error)
  }
}

export async function createPost(payload: CreatePostRequest): Promise<Post> {
  try {
    return await postJson<Post>('/api/posts', payload)
  } catch (error) {
    fallbackAllowed('create-post', error)
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
    const response = await fetch(endpoint('/api/files'), { method: 'POST', headers: { Authorization: currentToken() }, body: form })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const body = (await response.json()) as ApiResponse<FileUploadResult>
    return body.data
  } catch (error) {
    fallbackAllowed('upload', error)
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
  } catch (error) {
    fallbackAllowed('interaction', error)
    return { targetId: postId, type: type.toUpperCase(), active: true, duplicated: false, idempotencyKey }
  }
}

export async function followUser(userId: number): Promise<FollowResult> {
  try {
    return await postJson<FollowResult>(`/api/users/${userId}/follow`)
  } catch (error) {
    fallbackAllowed('follow', error)
    return { targetUserId: userId, followed: true, duplicated: false }
  }
}

export async function demoOAuthLogin(provider: 'github' | 'gitee'): Promise<string> {
  try {
    const response = await getJson<{ username: string }>(`/api/auth/oauth2/callback/${provider}?code=demo-code&state=devcircle`)
    setAccessToken(currentToken())
    return `${provider} 登录成功：${response.username}`
  } catch (error) {
    fallbackAllowed('oauth2', error)
    setAccessToken(provider === 'github' ? DEMO_ADMIN_TOKEN : DEMO_TOKEN)
    return `${provider} 演示登录成功：${provider}-user`
  }
}

export async function searchPosts(keyword: string): Promise<Post[]> {
  if (!keyword.trim()) return []
  try {
    return (await getJson<PageResponse<Post>>(`/api/search/posts?keyword=${encodeURIComponent(keyword)}`)).records
  } catch (error) {
    fallbackAllowed('search', error)
    return mockPosts.filter((post) => [post.title, post.excerpt, post.author, ...post.tags].some((value) => value.toLowerCase().includes(keyword.toLowerCase())))
  }
}

export async function fetchPostDetail(postId: number): Promise<Post> {
  try {
    return await getJson<Post>(`/api/posts/${postId}`)
  } catch (error) {
    fallbackAllowed('post-detail', error)
    return mockPosts.find((post) => post.id === postId) ?? mockPosts[0]
  }
}

export async function fetchRelatedPosts(postId: number): Promise<Post[]> {
  try {
    return await getJson<Post[]>(`/api/posts/${postId}/related?size=6`)
  } catch (error) {
    fallbackAllowed('related', error)
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
  } catch (error) {
    fallbackAllowed('deduction-answer', error)
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
  } catch (error) {
    fallbackAllowed('leaderboard', error)
    return mockLeaderboard
  }
}

export async function fetchAudits(): Promise<AuditItem[]> {
  try {
    return await getJson<AuditItem[]>('/api/admin/audits/posts')
  } catch (error) {
    fallbackAllowed('audits', error)
    return mockAudits
  }
}

export async function fetchModerationTasks(): Promise<ModerationTask[]> {
  try {
    return await getJson<ModerationTask[]>('/api/admin/moderation/tasks?status=PENDING&page=1&size=20')
  } catch (error) {
    fallbackAllowed('moderation-tasks', error)
    return [
      { taskId: 1, postId: 1002, title: '雾港钟楼的第十三声', queue: 'manual-review', riskLevel: 'MEDIUM', riskScore: 62, reason: '标题与正文高度重复', status: 'PENDING' },
      { taskId: 2, postId: 1043, title: '土卫六雨季城市设定稿', queue: 'fast-lane', riskLevel: 'LOW', riskScore: 18, reason: '常规机审通过后等待人工抽样', status: 'PENDING' }
    ]
  }
}

export async function moderationAction(taskId: number, action: 'approve' | 'reject' | 'escalate' | 'require-changes'): Promise<void> {
  try {
    await postJson(`/api/admin/moderation/tasks/${taskId}/${action}`)
  } catch (error) {
    fallbackAllowed(`moderation-${action}`, error)
  }
}

export async function fetchRiskUsers(): Promise<RiskUser[]> {
  try {
    return await getJson<RiskUser[]>('/api/admin/risk/users?page=1&size=20')
  } catch (error) {
    fallbackAllowed('risk-users', error)
    return [
      { userId: 12, status: 'WATCH', riskScore: 58, reason: '短时间高频发帖' },
      { userId: 18, status: 'NO_POST', riskScore: 91, reason: '多次命中敏感标签' }
    ]
  }
}

export async function fetchRecommendExperiments(): Promise<RecommendExperiment[]> {
  try {
    return await getJson<RecommendExperiment[]>('/api/admin/recommend/experiments')
  } catch (error) {
    fallbackAllowed('recommend-experiments', error)
    return [
      { experimentId: 'exp-theme-feed-v1', name: '主题内容多路召回实验', scene: 'home', status: 'ACTIVE', trafficPercent: 100 },
      { experimentId: 'exp-deduction-solve-v1', name: '推理馆解谜完成率实验', scene: 'deduction', status: 'ACTIVE', trafficPercent: 100 }
    ]
  }
}

export async function fetchNotifications(): Promise<NotificationItem[]> {
  try {
    return await getJson<NotificationItem[]>('/api/notifications')
  } catch (error) {
    fallbackAllowed('notifications', error)
    return mockNotifications
  }
}

export async function fetchOpsHealth(): Promise<OpsHealth> {
  try {
    return await getJson<OpsHealth>('/api/admin/ops/health')
  } catch (error) {
    fallbackAllowed('ops-health', error)
    return { database: 'DEGRADED', outboxBacklog: 0, source: 'MOCK_FALLBACK', degraded: true, degradationReason: error instanceof Error ? error.message : '运维接口不可用' }
  }
}

export async function fetchOutboxEvents(): Promise<OutboxEvent[]> {
  try {
    return await getJson<OutboxEvent[]>('/api/admin/ops/outbox?status=NEW&page=1&size=10')
  } catch (error) {
    fallbackAllowed('ops-outbox', error)
    return [{ eventId: 'demo-outbox', topic: 'post.created', status: 'NEW', source: 'MOCK_FALLBACK' }]
  }
}

export async function replayOutbox(topic?: string): Promise<void> {
  try {
    await postJson(`/api/admin/ops/outbox/replay?batchSize=100${topic ? `&topic=${encodeURIComponent(topic)}` : ''}`)
  } catch (error) {
    fallbackAllowed('ops-outbox-replay', error)
  }
}

export async function fetchDegradationOverview(): Promise<DegradationOverview> {
  try {
    return await getJson<DegradationOverview>('/api/admin/ops/degradation')
  } catch (error) {
    fallbackAllowed('ops-degradation', error)
    return { degraded: true, source: 'MOCK_FALLBACK', reason: error instanceof Error ? error.message : '运维降级接口不可用' }
  }
}
