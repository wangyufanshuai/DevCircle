export type ApiResponse<T> = {
  code: string
  message: string
  data: T
  timestamp: string
}

export type PageResponse<T> = {
  records: T[]
  total: number
  page: number
  size: number
}

export type Post = {
  id: number
  title: string
  excerpt: string
  author: string
  tags: string[]
  likes: number
  comments: number
  views: number
  status: 'PUBLISHED' | 'PENDING'
  createdAt?: string
}

export type RecommendedPost = {
  post: Post
  reason: string
  score: number
}

export type AuditItem = {
  postId: number
  title: string
  status: string
  author: string
}

export type NotificationItem = {
  id: number
  type: string
  title: string
  content: string
  read: boolean
  createdAt: string
}

