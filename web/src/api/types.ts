export type ApiResponse<T> = {
  code: string
  message: string
  data: T
  meta?: Record<string, unknown>
  traceId?: string
  timestamp?: string
  fieldErrors?: Record<string, string>
}

export type PageResponse<T> = { records: T[]; total: number; page: number; size: number }
export type CursorPage<T> = { records: T[]; nextCursor: string; hasMore: boolean; source?: string }

export type Theme = 'DEDUCTION' | 'SCI_FI' | 'COSMOS'
export type ContentKind = 'CASE_FILE' | 'PUZZLE' | 'FICTION' | 'WORLDBUILDING' | 'COSMIC_ARTICLE' | 'OBSERVATION_LOG'
export type PostStatus = 'DRAFT' | 'PENDING' | 'PUBLISHED' | 'REJECTED' | 'CHANGES_REQUIRED'

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
  theme: Theme
  contentKind: ContentKind
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
  status: PostStatus
  createdAt?: string
  clues?: string[]
  suspects?: string[]
  timeline?: string[]
  question?: string
  answerKeywords?: string[]
  explanation?: string
  worldbuilding?: string[]
  cosmicFacts?: string[]
  relatedPostIds?: number[]
}

export type FeedItem = {
  post: Post
  reason: string
  score: number
  liked: boolean
  favorited: boolean
  algorithm: string
  mediaType?: string
  features: Record<string, number>
  experimentId?: string
  bucket?: string
  recallSources?: string[]
}

export type Draft = {
  id: number
  authorId: number
  title: string
  content: string
  tags: string[]
  type: 'TEXT' | 'IMAGE' | 'VIDEO'
  mediaIds: number[]
  theme: Theme
  contentKind: ContentKind
  difficulty: 'EASY' | 'MEDIUM' | 'HARD'
  solvable: boolean
  clues: string
  suspects: string
  timeline: string
  question: string
  answerKeywords: string
  solution: string
  status: PostStatus
  version: number
  lastSavedAt?: string
  submittedAt?: string
}

export type SaveDraftRequest = Omit<Draft, 'id' | 'authorId' | 'status' | 'version' | 'lastSavedAt' | 'submittedAt'> & { version?: number }
export type UserSession = { userId: number; displayName: string; roles: string[] }
export type LoginResponse = { accessToken: string; expiresAt: string; user: UserSession }
export type NotificationItem = { id: number; type: string; title: string; content: string; read: boolean; createdAt: string }
export type CommentItem = { id: number; postId: number; authorId: number; author: string; parentId?: number; rootId?: number; content: string; status: string; likes: number; createdAt: string; replies?: CommentItem[] }
export type ModerationTask = { taskId: number; postId: number; title: string; authorId?: number; queue: string; machineResult?: string; riskLevel: string; riskScore: number; reason: string; status: string; reviewLevel?: string; slaDeadline?: string }
export type UploadIntent = { mediaId: number; objectKey: string; uploadUrl: string; requiredHeaders: Record<string, string>; expiresAt: string }
export type InteractionResult = { targetId: number; type: string; active: boolean; duplicated: boolean; idempotencyKey: string }
export type DeductionAnswerResult = { postId: number; userId: number; correct: boolean; duplicated: boolean; points: number; explanation: string }

// Compatibility types for legacy presentation components retained during the router migration.
export type AuditItem = { postId: number; title: string; status: string; author: string }
export type LeaderboardEntry = { userId: number; username: string; correctCount: number; totalPoints: number }
export type RiskUser = { userId: number; status: string; riskScore: number; reason: string }
export type RecommendExperiment = { experimentId: string; name: string; scene: string; status: string; trafficPercent: number }
export type OpsHealth = { database: string; outboxBacklog: number; pendingModeration?: number; riskUsers?: number; posts?: number; source: string; degraded?: boolean; degradationReason?: string }
export type OutboxEvent = { id?: number; eventId: string; topic: string; aggregateType?: string; aggregateId?: string; status: string; createdAt?: string; publishedAt?: string; source?: string }
export type DegradationOverview = { degraded: boolean; source: string; reason?: string; outboxBacklog?: number; recommendImpressions?: number; recommendEvents?: number; pendingModeration?: number }
