import { mockAudits, mockNotifications, mockPosts, mockRecommendations } from './mock'
import type { ApiResponse, AuditItem, NotificationItem, PageResponse, Post, RecommendedPost } from './types'

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

export async function fetchPosts(): Promise<Post[]> {
  try {
    return (await getJson<PageResponse<Post>>('/api/posts')).records
  } catch {
    return mockPosts
  }
}

export async function fetchRecommendations(): Promise<RecommendedPost[]> {
  try {
    return await getJson<RecommendedPost[]>('/api/recommend/feed')
  } catch {
    return mockRecommendations
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

