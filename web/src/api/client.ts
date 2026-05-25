import { mockAudits, mockNotifications, mockPosts, mockRecommendations } from './mock'
import type { ApiResponse, AuditItem, CreatePostRequest, CursorPage, FeedItem, InteractionResult, NotificationItem, PageResponse, Post } from './types'

const token = 'Bearer demo-access-token-admin'

async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url, {
    headers: {
      Authorization: token
    }
  })
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status}`)
  }
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
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status}`)
  }
  const body = (await response.json()) as ApiResponse<T>
  return body.data
}

export async function fetchPosts(): Promise<Post[]> {
  try {
    return (await getJson<PageResponse<Post>>('/api/posts')).records
  } catch {
    return mockPosts
  }
}

export async function fetchFeed(cursor?: string): Promise<CursorPage<FeedItem>> {
  try {
    return await getJson<CursorPage<FeedItem>>(`/api/recommend/feed?size=3${cursor ? `&cursor=${cursor}` : ''}`)
  } catch {
    return { records: mockRecommendations, nextCursor: String(mockRecommendations.length), hasMore: false }
  }
}

export async function recordExposures(postIds: number[]): Promise<void> {
  try {
    await postJson('/api/recommend/exposures', { userId: 2, postIds, scene: 'home_feed' })
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
      title: payload.title,
      excerpt: payload.content.slice(0, 140),
      author: 'demo',
      tags: payload.tags,
      likes: 0,
      comments: 0,
      views: 0,
      status: 'PENDING'
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

export async function searchPosts(keyword: string): Promise<Post[]> {
  if (!keyword.trim()) {
    return []
  }
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
