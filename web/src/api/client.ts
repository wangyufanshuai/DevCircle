import { mockAudits, mockNotifications, mockPosts, mockRecommendations } from './mock'
import type { ApiResponse, AuditItem, CreatePostRequest, CursorPage, FeedItem, FileUploadResult, FollowResult, InteractionResult, NotificationItem, PageResponse, Post } from './types'

const token = 'Bearer demo-access-token-admin'

async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url, { headers: { Authorization: token } })
  if (!response.ok) throw new Error(`Request failed: ${response.status}`)
  const body = (await response.json()) as ApiResponse<T>
  return body.data
}

async function postJson<T>(url: string, payload?: unknown, extraHeaders: Record<string, string> = {}): Promise<T> {
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      Authorization: token,
      'Content-Type': 'application/json',
      ...extraHeaders
    },
    body: payload === undefined ? undefined : JSON.stringify(payload)
  })
  if (!response.ok) throw new Error(`Request failed: ${response.status}`)
  const body = (await response.json()) as ApiResponse<T>
  return body.data
}

function fallbackFeed(scene: string): CursorPage<FeedItem> {
  const records = scene === 'home'
    ? mockRecommendations
    : mockRecommendations.filter((item) => item.post.type.toLowerCase() === scene)
  return { records, nextCursor: String(records.length), hasMore: false }
}

export async function fetchPosts(type?: string): Promise<Post[]> {
  try {
    const query = type ? `?type=${encodeURIComponent(type)}` : ''
    return (await getJson<PageResponse<Post>>(`/api/posts${query}`)).records
  } catch {
    return type ? mockPosts.filter((post) => post.type === type) : mockPosts
  }
}

export async function fetchFeed(scene = 'home', cursor?: string): Promise<CursorPage<FeedItem>> {
  try {
    return await getJson<CursorPage<FeedItem>>(`/api/recommend/feed?scene=${scene}&size=3${cursor ? `&cursor=${cursor}` : ''}`)
  } catch {
    return fallbackFeed(scene)
  }
}

export async function recordExposures(postIds: number[], scene = 'home_feed'): Promise<void> {
  try {
    await postJson('/api/recommend/exposures', { userId: 2, postIds, scene })
  } catch {
    // Exposure recording is best-effort in local frontend demos.
  }
}

export async function createPost(payload: CreatePostRequest): Promise<Post> {
  try {
    return await postJson<Post>('/api/posts', payload)
  } catch {
    return {
      id: Date.now(),
      type: payload.type ?? 'TEXT',
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
    return { targetId: postId, type: type.toUpperCase(), active: true, duplicated: false, idempotencyKey }
  }
}

export async function followUser(userId: number): Promise<FollowResult> {
  try {
    return await postJson<FollowResult>(`/api/users/${userId}/follow`)
  } catch {
    return { targetUserId: userId, followed: true, duplicated: false }
  }
}

export async function demoOAuthLogin(provider: 'github' | 'gitee'): Promise<string> {
  try {
    const response = await getJson<{ username: string }>(`/api/auth/oauth2/callback/${provider}?code=demo-code&state=devcircle`)
    return `${provider} 登录成功：${response.username}`
  } catch {
    return `${provider} 演示登录成功：${provider}-user`
  }
}

export async function searchPosts(keyword: string): Promise<Post[]> {
  if (!keyword.trim()) return []
  try {
    return (await getJson<PageResponse<Post>>(`/api/search/posts?keyword=${encodeURIComponent(keyword)}`)).records
  } catch {
    return mockPosts.filter((post) =>
      [post.title, post.excerpt, post.author, ...post.tags].some((value) => value.toLowerCase().includes(keyword.toLowerCase()))
    )
  }
}

export async function fetchAudits(): Promise<AuditItem[]> {
  try {
    return await getJson<AuditItem[]>('/api/admin/audits/posts')
  } catch {
    return mockAudits
  }
}

export async function fetchNotifications(): Promise<NotificationItem[]> {
  try {
    return await getJson<NotificationItem[]>('/api/notifications')
  } catch {
    return mockNotifications
  }
}
