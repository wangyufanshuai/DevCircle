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

export type CursorPage<T> = {
  records: T[]
  nextCursor: string
  hasMore: boolean
}

export type MediaAsset = {
  mediaId: number
  objectKey: string
  url: string
  contentType: string
  size: number
  width?: number
  height?: number
  durationSeconds?: number
  status: string
}

export type Post = {
  id: number
  type: 'TEXT' | 'IMAGE' | 'VIDEO'
  theme: 'DEDUCTION' | 'SCI_FI' | 'COSMOS'
  contentKind: 'CASE_FILE' | 'PUZZLE' | 'FICTION' | 'WORLDBUILDING' | 'COSMIC_ARTICLE' | 'OBSERVATION_LOG'
  difficulty: 'EASY' | 'MEDIUM' | 'HARD'
  solvable: boolean
  solvedCount: number
  title: string
  excerpt: string
  author: string
  tags: string[]
  mediaAssets: MediaAsset[]
  likes: number
  comments: number
  views: number
  status: 'PUBLISHED' | 'PENDING'
  createdAt?: string
}

export type FeedItem = {
  post: Post
  reason: string
  score: number
  liked: boolean
  favorited: boolean
  algorithm: string
  mediaType: string
  features: Record<string, number>
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

export type CreatePostRequest = {
  title: string
  content: string
  tags: string[]
  type?: 'TEXT' | 'IMAGE' | 'VIDEO'
  mediaIds?: number[]
  theme?: 'DEDUCTION' | 'SCI_FI' | 'COSMOS'
  contentKind?: 'CASE_FILE' | 'PUZZLE' | 'FICTION' | 'WORLDBUILDING' | 'COSMIC_ARTICLE' | 'OBSERVATION_LOG'
  difficulty?: 'EASY' | 'MEDIUM' | 'HARD'
  solvable?: boolean
  clues?: string
  solution?: string
}

export type InteractionResult = {
  targetId: number
  type: string
  active: boolean
  duplicated: boolean
  idempotencyKey: string
}

export type FileUploadResult = MediaAsset

export type FollowResult = {
  targetUserId: number
  followed: boolean
  duplicated: boolean
}

export type DeductionAnswerResult = {
  postId: number
  userId: number
  correct: boolean
  duplicated: boolean
  points: number
  explanation: string
}

export type LeaderboardEntry = {
  userId: number
  username: string
  correctCount: number
  totalPoints: number
}
