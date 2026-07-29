import type {
  ApiResponse, CommentItem, CursorPage, DeductionAnswerResult, Draft, FeedItem, InteractionResult,
  LoginResponse, ModerationTask, NotificationItem, PageResponse, Post, SaveDraftRequest,
  Theme, UploadIntent, UserSession
} from './types'

const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')
let accessToken = ''
let session: UserSession | null = null
let refreshPromise: Promise<LoginResponse | null> | null = null

export class ApiError extends Error {
  constructor(public code: string, message: string, public traceId?: string, public fieldErrors?: Record<string, string>) {
    super(message)
  }
}

export function currentSession() { return session }
export function hasRole(role: string) { return session?.roles.includes(role) ?? false }
export function clearSession() { accessToken = ''; session = null }
export function idempotencyKey() { return crypto.randomUUID() }

function cookie(name: string): string {
  const prefix = `${encodeURIComponent(name)}=`
  const entry = document.cookie.split('; ').find(value => value.startsWith(prefix))
  return entry ? decodeURIComponent(entry.slice(prefix.length)) : ''
}

async function parse<T>(response: Response): Promise<T> {
  let body: ApiResponse<T> | null = null
  try { body = await response.json() as ApiResponse<T> } catch { /* sanitized below */ }
  if (!response.ok || !body || body.code !== 'OK') {
    const fields = body?.fieldErrors ?? body?.meta?.fieldErrors as Record<string, string> | undefined
    throw new ApiError(body?.code ?? 'REQUEST_FAILED', body?.message || '请求未完成，请稍后重试', body?.traceId, fields)
  }
  return body.data
}

async function refresh(): Promise<LoginResponse | null> {
  if (refreshPromise) return refreshPromise
  refreshPromise = (async () => {
    const csrf = cookie('dc_csrf')
    if (!csrf) return null
    const response = await fetch(`${API_BASE}/api/auth/refresh`, {
      method: 'POST', credentials: 'include', headers: { 'X-CSRF-Token': csrf }
    })
    if (!response.ok) { clearSession(); return null }
    const data = await parse<LoginResponse>(response)
    accessToken = data.accessToken
    session = data.user
    return data
  })().finally(() => { refreshPromise = null })
  return refreshPromise
}

async function request<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  const headers = new Headers(init.headers)
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
  if (init.body !== undefined && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  let response = await fetch(`${API_BASE}${path}`, { ...init, headers, credentials: 'include' })
  if (response.status === 401 && retry && !path.startsWith('/api/auth/')) {
    if (await refresh()) {
      const retryHeaders = new Headers(headers)
      retryHeaders.set('Authorization', `Bearer ${accessToken}`)
      response = await fetch(`${API_BASE}${path}`, { ...init, headers: retryHeaders, credentials: 'include' })
    }
  }
  return parse<T>(response)
}

function write<T>(path: string, method: 'POST' | 'PUT' | 'DELETE', payload?: unknown, key = idempotencyKey()) {
  return request<T>(path, { method, headers: { 'Idempotency-Key': key }, body: payload === undefined ? undefined : JSON.stringify(payload) })
}

export async function restoreSession() { try { return await refresh() } catch { clearSession(); return null } }
export async function register(email: string, password: string, displayName: string) {
  const data = await request<LoginResponse>('/api/auth/register', { method: 'POST', body: JSON.stringify({ email, password, displayName }) }, false)
  accessToken = data.accessToken; session = data.user; return data
}
export async function login(email: string, password: string) {
  const data = await request<LoginResponse>('/api/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }, false)
  accessToken = data.accessToken; session = data.user; return data
}
export async function logout() {
  const csrf = cookie('dc_csrf')
  await request<void>('/api/auth/logout', { method: 'POST', headers: { 'X-CSRF-Token': csrf } }, false)
  clearSession()
}
export async function startGitHubLogin() {
  const { authorizationUrl } = await request<{ authorizationUrl: string }>('/api/auth/oauth2/github/start')
  window.location.assign(authorizationUrl)
}

export function fetchFeed(scene = 'home', cursor?: string) {
  return request<CursorPage<FeedItem>>(`/api/recommend/feed?scene=${encodeURIComponent(scene)}&size=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`)
}
export function fetchPosts(page = 1, theme?: Theme) {
  return request<PageResponse<Post>>(`/api/posts?page=${page}&size=20${theme ? `&theme=${theme}` : ''}`)
}
export function fetchPost(id: number) { return request<Post>(`/api/posts/${id}`) }
export function fetchRelated(id: number) { return request<Post[]>(`/api/posts/${id}/related?size=6`) }
export function searchPosts(keyword: string, theme?: Theme) {
  return request<PageResponse<Post>>(`/api/search/posts?keyword=${encodeURIComponent(keyword)}${theme ? `&theme=${theme}` : ''}`)
}

export function listDrafts() { return request<Draft[]>('/api/posts/drafts?size=50') }
export function fetchDraft(id: number) { return request<Draft>(`/api/posts/drafts/${id}`) }
export function createDraft(payload: SaveDraftRequest) { return write<Draft>('/api/posts/drafts', 'POST', payload) }
export function updateDraft(id: number, payload: SaveDraftRequest) { return write<Draft>(`/api/posts/drafts/${id}`, 'PUT', payload) }
export function submitDraft(id: number, version: number) { return write<Draft>(`/api/posts/drafts/${id}/submit?version=${version}`, 'POST') }

export async function uploadMedia(file: File): Promise<number> {
  const intent = await write<UploadIntent>('/api/files/intents', 'POST', { filename: file.name, contentType: file.type, size: file.size })
  const upload = await fetch(intent.uploadUrl, { method: 'PUT', headers: intent.requiredHeaders, body: file })
  if (!upload.ok) throw new ApiError('UPLOAD_FAILED', '媒体上传失败，请重试')
  const completed = await write<{ mediaId: number }>(`/api/files/${intent.mediaId}/complete`, 'POST')
  return completed.mediaId
}

export function interact(postId: number, type: 'like' | 'favorite') { return write<InteractionResult>(`/api/posts/${postId}/${type}`, 'POST') }
export function recordExposures(postIds: number[], scene: string) { return write<void>('/api/recommend/exposures', 'POST', { postIds, scene }) }
export function recordRecommendEvent(item: FeedItem, eventType: string) {
  return write<void>('/api/recommend/events', 'POST', { postId: item.post.id, eventType, scene: item.post.theme.toLowerCase(), experimentId: item.experimentId, bucket: item.bucket })
}
export function answerCase(postId: number, answer: string) { return write<DeductionAnswerResult>(`/api/deduction/cases/${postId}/answers`, 'POST', { answer }) }
export function fetchComments(postId: number) { return request<CommentItem[]>(`/api/comments?postId=${postId}&size=50`) }
export function createComment(postId: number, content: string, parentId?: number) { return write<CommentItem>('/api/comments', 'POST', { postId, content, parentId }) }
export function deleteComment(id: number) { return write<void>(`/api/comments/${id}`, 'DELETE') }
export function fetchNotifications() { return request<NotificationItem[]>('/api/notifications') }
export function markNotificationRead(id: number) { return write<void>(`/api/notifications/${id}/read`, 'POST') }
export function fetchModerationTasks() { return request<ModerationTask[]>('/api/admin/moderation/tasks?status=PENDING&page=1&size=20') }
export function moderateTask(id: number, action: string, reason: string) { return write<void>(`/api/admin/moderation/tasks/${id}/${action}`, 'POST', { reason }) }
