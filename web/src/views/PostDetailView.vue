<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowLeft, Bookmark, CheckCircle2, Eye, Heart, LoaderCircle, MessageCircle, Reply, Send, ShieldAlert, UserPlus } from 'lucide-vue-next'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  answerCase, createComment, currentSession, deleteComment, fetchComments, fetchFollowState,
  fetchInteractionState, fetchMediaAccess, fetchPost, fetchRelated, fetchUserProfiles, followUser, interact, revealAnswer,
  hideComment, saveReadingProgress, sessionState, unfollowUser
} from '../api/client'
import type { CommentItem, CommentThread, Post, PostDetail, UserProfile } from '../api/types'
import PostCard from '../components/PostCard.vue'
import StatusPanel from '../components/StatusPanel.vue'
import { markdownMediaIds, renderSafeMarkdown } from '../utils/markdown'

const route = useRoute()
const router = useRouter()
const post = ref<PostDetail | null>(null)
const related = ref<Post[]>([])
const threads = ref<CommentThread[]>([])
const answer = ref('')
const comment = ref('')
const replyTarget = ref<number | null>(null)
const replyText = ref('')
const commentCursor = ref('')
const commentHasMore = ref(false)
const loadingMoreComments = ref(false)
const moderationTarget = ref<number | null>(null)
const moderationReason = ref('')
const moderating = ref(false)
const userProfiles = new Map<number, UserProfile>()
const loading = ref(false)
const error = ref('')
const feedback = ref('')
const commentError = ref('')
const following = ref(false)
const savingProgress = ref(false)
let progressTimer: ReturnType<typeof setTimeout> | undefined
const id = computed(() => Number(route.params.id))
const session = sessionState
const canModerate = computed(() => session.value?.roles.some(role => role === 'ADMIN' || role === 'MODERATOR') ?? false)
const themeName: Record<string, string> = { DEDUCTION: '推理馆', SCI_FI: '科幻馆', COSMOS: '宇宙档案' }

const renderedMarkdown = computed(() => {
  if (!post.value?.markdown) return ''
  return renderSafeMarkdown(post.value.markdown, post.value.media)
})
const standaloneMedia = computed(() => { if (!post.value) return []; const placed = markdownMediaIds(post.value.markdown); return post.value.media.filter(media => media.role === 'COVER' || !placed.has(media.mediaId)) })

async function load() {
  loading.value = true
  error.value = ''
  userProfiles.clear()
  try {
    const [detail, relatedPosts, commentPage] = await Promise.all([
      fetchPost(id.value), fetchRelated(id.value), fetchComments(id.value)
    ])
    post.value = detail
    if (detail.media.length) {
      const access = await Promise.all(detail.media.map(media => fetchMediaAccess(media.mediaId)))
      const urls = new Map(access.map(item => [item.mediaId, item.url]))
      post.value.media = detail.media.map(media => ({ ...media, url: urls.get(media.mediaId) ?? '' }))
    }
    const initialComments = commentPage.records.flatMap(thread => [thread.root, ...thread.replies])
    await loadUserProfiles([detail.author.id, ...initialComments.map(item => item.authorId)])
    const profile = userProfiles.get(detail.author.id)
    if (profile) post.value.author = { ...post.value.author, username: profile.username, bio: profile.bio, followerCount: profile.followers }
    related.value = relatedPosts
    threads.value = await enrichCommentAuthors(commentPage.records)
    commentCursor.value = commentPage.nextCursor
    commentHasMore.value = commentPage.hasMore
    following.value = detail.author.followedByViewer === true
    if (currentSession()) {
      const [interactionState, followState] = await Promise.all([
        fetchInteractionState(detail.id), fetchFollowState(detail.author.id)
      ])
      post.value.viewerState.liked = interactionState.liked
      post.value.viewerState.favorited = interactionState.favorited
      following.value = followState.following
    }
    await nextTick()
    restoreAnchor(detail.viewerState.readingProgress)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '案件暂时无法打开'
  } finally {
    loading.value = false
  }
}

async function enrichCommentAuthors(records: CommentThread[]) {
  const comments = records.flatMap(thread => [thread.root, ...thread.replies])
  await loadUserProfiles(comments.map(item => item.authorId))
  comments.forEach(item => { item.author = userProfiles.get(item.authorId)?.username ?? item.author })
  return records
}

async function loadUserProfiles(ids: number[]) {
  const missing = [...new Set(ids)].filter(authorId => authorId > 0 && !userProfiles.has(authorId))
  if (!missing.length) return
  const profiles = await fetchUserProfiles(missing).catch(() => [])
  profiles.forEach(profile => userProfiles.set(profile.id, profile))
}

async function loadMoreComments() {
  if (!commentHasMore.value || loadingMoreComments.value) return
  loadingMoreComments.value = true; commentError.value = ''
  try {
    const page = await fetchComments(id.value, commentCursor.value)
    const records = await enrichCommentAuthors(page.records)
    const existing = new Set(threads.value.map(thread => thread.root.id))
    threads.value.push(...records.filter(thread => !existing.has(thread.root.id)))
    commentCursor.value = page.nextCursor; commentHasMore.value = page.hasMore
  } catch (reason) { commentError.value = reason instanceof Error ? reason.message : '更多评论暂时无法加载' }
  finally { loadingMoreComments.value = false }
}

function restoreAnchor(progress: number) {
  if (progress <= 0) return
  window.scrollTo({ top: (document.documentElement.scrollHeight - window.innerHeight) * progress / 100, behavior: 'auto' })
}

function scheduleProgressSave() {
  if (!session.value || !post.value || savingProgress.value) return
  if (progressTimer) clearTimeout(progressTimer)
  progressTimer = setTimeout(() => void persistProgress(), 650)
}

async function persistProgress() {
  if (!session.value || !post.value) return
  const max = Math.max(1, document.documentElement.scrollHeight - window.innerHeight)
  const percent = Math.min(100, Math.round((window.scrollY / max) * 100))
  const completed = percent >= 96
  savingProgress.value = true
  try {
    const saved = await saveReadingProgress(post.value.id, percent, `scroll:${Math.round(window.scrollY)}`, completed)
    post.value.viewerState.readingProgress = saved.progressPercent
  } catch {
    // Progress is secondary; a failed save must not interrupt reading.
  } finally {
    savingProgress.value = false
  }
}

async function toggleInteraction(type: 'like' | 'favorite') {
  if (!post.value || !await requireSession()) return
  const key = type === 'like' ? 'liked' : 'favorited'
  const active = !post.value.viewerState[key]
  post.value.viewerState[key] = active
  if (type === 'like') post.value.likes += active ? 1 : -1
  error.value = ''
  try {
    await interact(post.value.id, type, active)
  } catch (reason) {
    post.value.viewerState[key] = !active
    if (type === 'like') post.value.likes += active ? -1 : 1
    error.value = reason instanceof Error ? reason.message : '互动暂时不可用'
  }
}

async function toggleFollow() {
  if (!post.value || !await requireSession()) return
  const active = !following.value
  following.value = active
  error.value = ''
  try {
    if (!active) await unfollowUser(post.value.author.id)
    else await followUser(post.value.author.id)
  } catch (reason) {
    following.value = !active
    error.value = reason instanceof Error ? reason.message : '关注暂时不可用'
  }
}

async function requireSession() {
  if (session.value) return true
  await router.push({ path: '/auth', query: { redirect: route.fullPath } })
  return false
}

async function submitAnswer() {
  if (!post.value || !answer.value.trim() || !await requireSession()) return
  try {
    const result = await answerCase(post.value.id, answer.value.trim())
    post.value.deductionState = {
      attempted: true, solved: result.correct, revealed: result.revealed,
      points: result.points, solutionUnlocked: result.solutionUnlocked
    }
    post.value.solution = result.solution
    feedback.value = result.correct ? `推理成立，获得 ${result.points} 分。` : result.explanation
  } catch (reason) {
    feedback.value = reason instanceof Error ? reason.message : '提交推理失败，请稍后重试'
  }
}

async function reveal() {
  if (!post.value || !await requireSession() || !window.confirm('查看答案后，本题将不再获得积分。确定继续吗？')) return
  try {
    const result = await revealAnswer(post.value.id)
    post.value.deductionState = { attempted: true, solved: result.correct, revealed: true, points: 0, solutionUnlocked: true }
    post.value.solution = result.solution
    feedback.value = '答案已揭示，本题不再计分。'
  } catch (reason) {
    feedback.value = reason instanceof Error ? reason.message : '答案暂时无法揭示'
  }
}

function prependComment(item: CommentItem, parentId?: number) {
  if (session.value) { item.author = session.value.displayName; userProfiles.set(item.authorId, { id: item.authorId, username: item.author, followers: 0, following: 0, status: 'ACTIVE' }) }
  if (!parentId) {
    threads.value.unshift({ root: item, replies: [] })
    return
  }
  const thread = threads.value.find(candidate => candidate.root.id === parentId || candidate.root.id === item.rootId)
  thread?.replies.push(item)
}

async function submitComment(parentId?: number) {
  const content = (parentId ? replyText.value : comment.value).trim()
  if (!content) return
  commentError.value = ''
  try {
    const created = await createComment(id.value, content, parentId)
    prependComment(created, parentId)
    if (parentId) { replyText.value = ''; replyTarget.value = null } else comment.value = ''
  } catch (reason) {
    commentError.value = reason instanceof Error ? reason.message : '评论暂时不可用'
  }
}

async function removeComment(item: CommentItem) {
  commentError.value = ''
  try {
    await deleteComment(item.id)
    item.status = 'DELETED'
    item.content = '评论已删除'
  } catch (reason) {
    commentError.value = reason instanceof Error ? reason.message : '删除评论失败'
  }
}

function own(item: CommentItem) { return session.value?.userId === item.authorId }
function visible(item: CommentItem) { return item.status === 'VISIBLE' }
function commentText(item: CommentItem) {
  if (item.status === 'DELETED') return '评论已删除'
  if (item.status === 'HIDDEN') return '评论已隐藏'
  return item.content
}
function startModeration(item: CommentItem) {
  moderationTarget.value = item.id
  moderationReason.value = ''
  commentError.value = ''
}
async function moderateComment(item: CommentItem) {
  const reason = moderationReason.value.trim()
  if (reason.length < 4) { commentError.value = '请填写至少 4 个字符的隐藏理由'; return }
  moderating.value = true; commentError.value = ''
  try {
    await hideComment(item.id, reason)
    item.status = 'HIDDEN'; item.content = '评论已隐藏'
    moderationTarget.value = null; moderationReason.value = ''
  } catch (cause) { commentError.value = cause instanceof Error ? cause.message : '隐藏评论失败' }
  finally { moderating.value = false }
}

watch(id, load)
onMounted(() => {
  void load()
  window.addEventListener('scroll', scheduleProgressSave, { passive: true })
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', scheduleProgressSave)
  if (progressTimer) clearTimeout(progressTimer)
  void persistProgress()
})
</script>

<template>
  <div class="page-stack detail-page">
    <RouterLink class="back-link" to="/discover"><ArrowLeft :size="16" /> 返回发现</RouterLink>
    <StatusPanel v-if="error && !post" title="案件暂时无法打开" :detail="error" action-label="重试" @retry="load" />
    <div v-else-if="loading" class="loading-line">正在打开档案…</div>
    <article v-else-if="post" class="detail-layout">
      <header class="detail-header">
        <div class="detail-heading-copy">
          <span class="eyebrow">{{ themeName[post.theme] }} / {{ post.contentKind }}</span>
          <h1>{{ post.title }}</h1>
          <p class="detail-lede">{{ post.excerpt }}</p>
          <div class="post-meta"><span>{{ post.author.username }}</span><span>{{ post.difficulty }}</span><span><Eye :size="14" /> {{ post.views }} 次观测</span></div>
        </div>
        <div class="detail-actions" aria-label="内容互动">
          <button class="button button-quiet" :aria-pressed="post.viewerState.liked" type="button" @click="toggleInteraction('like')"><Heart :size="16" /> {{ post.viewerState.liked ? '已喜欢' : '喜欢' }} {{ post.likes }}</button>
          <button class="button button-quiet" :aria-pressed="post.viewerState.favorited" type="button" @click="toggleInteraction('favorite')"><Bookmark :size="16" /> {{ post.viewerState.favorited ? '已收藏' : '收藏' }}</button>
        </div>
      </header>
      <p v-if="error" class="form-error detail-feedback" role="alert">{{ error }}</p>

      <section class="author-strip">
        <div class="profile-avatar">{{ post.author.username.slice(0, 1).toUpperCase() }}</div>
        <div><strong>{{ post.author.username }}</strong><p>{{ post.author.bio || '正在记录宇宙中的异常信号。' }}</p></div>
        <button class="button button-quiet" type="button" @click="toggleFollow"><UserPlus :size="16" /> {{ following ? '已关注' : '关注作者' }}</button>
      </section>

      <section class="detail-body">
        <div class="reading-column">
          <div v-if="standaloneMedia.length" class="detail-media-grid">
            <template v-for="media in standaloneMedia" :key="media.mediaId"><video v-if="media.contentType.startsWith('video/')" :src="media.url" :aria-label="media.altText || post.title" controls preload="metadata" /><img v-else :src="media.url" :alt="media.altText || post.title" loading="lazy" decoding="async" /></template>
          </div>
          <div class="reading-copy markdown-body" v-html="renderedMarkdown" />
          <section v-if="post.question" class="case-question"><span class="eyebrow">CASE QUESTION</span><h2>案件问题</h2><p>{{ post.question }}</p></section>
          <section v-if="post.clues?.length" class="clue-list"><h2>已知线索</h2><ul><li v-for="clue in post.clues" :key="clue">{{ clue }}</li></ul></section>

          <section v-if="post.solvable" class="solve-box">
            <span class="eyebrow">DEDUCTION CHECK</span><h2>你的推理结论是什么？</h2>
            <template v-if="post.deductionState.solutionUnlocked">
              <p class="solution-unlocked"><CheckCircle2 :size="17" /> {{ post.deductionState.revealed ? '答案已揭示，本题不再计分。' : '推理成立，答案已解锁。' }}</p>
              <p class="solution-copy">{{ post.solution }}</p>
            </template>
            <template v-else>
              <label for="answer">推理结论</label>
              <textarea id="answer" v-model="answer" rows="3" placeholder="写下你的推理，不需要一次完美。" />
              <div class="solve-actions"><button class="button button-primary" type="button" @click="submitAnswer">提交推理 <Send :size="15" /></button><button class="button button-quiet" type="button" @click="reveal">查看答案</button></div>
            </template>
            <p v-if="feedback" class="inline-feedback" role="status"><MessageCircle :size="16" /> {{ feedback }}</p>
          </section>

          <section class="comments-section">
            <div class="section-heading"><div><span class="eyebrow">COMMUNITY NOTES</span><h2>读者讨论</h2></div><span class="muted"><MessageCircle :size="14" /> 已加载 {{ threads.length }} 个讨论串</span></div>
            <form v-if="session" class="comment-form" @submit.prevent="submitComment()"><label class="sr-only" for="comment">写评论</label><textarea id="comment" v-model="comment" rows="2" maxlength="2000" placeholder="留下你的观察…" /><button class="button button-primary" type="submit">发表评论</button></form>
            <div v-else class="comment-login"><p>登录后才能参与讨论、回复其他读者。</p><RouterLink class="button button-quiet" :to="{ path: '/auth', query: { redirect: route.fullPath } }">登录参与讨论</RouterLink></div>
            <p v-if="commentError" class="form-error" role="alert">{{ commentError }}</p>
            <div v-if="threads.length" class="comment-list">
              <article v-for="thread in threads" :key="thread.root.id" class="comment-thread" :data-comment-id="thread.root.id">
                <div class="comment-item"><div class="comment-avatar">{{ thread.root.author.slice(0, 1) }}</div><div class="comment-main"><strong>{{ thread.root.author }} <small v-if="canModerate && thread.root.status === 'HIDDEN'" class="moderation-status">已隐藏</small></strong><p>{{ commentText(thread.root) }}</p><small>{{ thread.root.createdAt }}</small><div class="comment-actions"><button v-if="session && visible(thread.root)" class="text-button" type="button" @click="replyTarget = replyTarget === thread.root.id ? null : thread.root.id"><Reply :size="14" /> 回复</button><button v-if="own(thread.root) && visible(thread.root)" class="text-button" type="button" @click="removeComment(thread.root)">删除</button><button v-if="canModerate && visible(thread.root)" class="text-button" type="button" @click="startModeration(thread.root)"><ShieldAlert :size="14" /> 隐藏</button></div></div></div>
                <div v-if="replyTarget === thread.root.id" class="reply-form"><label class="sr-only" :for="`reply-${thread.root.id}`">回复评论</label><textarea :id="`reply-${thread.root.id}`" v-model="replyText" rows="2" placeholder="回复这条观察…" /><button class="button button-quiet" type="button" @click="submitComment(thread.root.id)">发送回复</button></div>
                <form v-if="moderationTarget === thread.root.id" class="moderation-form" @submit.prevent="moderateComment(thread.root)"><label :for="`moderate-${thread.root.id}`">隐藏理由</label><textarea :id="`moderate-${thread.root.id}`" v-model="moderationReason" rows="2" minlength="4" maxlength="500" required /><div><button class="button button-primary" type="submit" :disabled="moderating">确认隐藏</button><button class="button button-quiet" type="button" @click="moderationTarget = null">取消</button></div></form>
                <template v-for="reply in thread.replies" :key="reply.id">
                  <div class="comment-item comment-reply"><div class="comment-avatar">{{ reply.author.slice(0, 1) }}</div><div class="comment-main"><strong>{{ reply.author }} <small v-if="canModerate && reply.status === 'HIDDEN'" class="moderation-status">已隐藏</small></strong><p>{{ commentText(reply) }}</p><small>{{ reply.createdAt }}</small><div class="comment-actions"><button v-if="own(reply) && visible(reply)" class="text-button" type="button" @click="removeComment(reply)">删除</button><button v-if="canModerate && visible(reply)" class="text-button" type="button" @click="startModeration(reply)"><ShieldAlert :size="14" /> 隐藏</button></div></div></div>
                  <form v-if="moderationTarget === reply.id" class="moderation-form comment-reply" @submit.prevent="moderateComment(reply)"><label :for="`moderate-${reply.id}`">隐藏理由</label><textarea :id="`moderate-${reply.id}`" v-model="moderationReason" rows="2" minlength="4" maxlength="500" required /><div><button class="button button-primary" type="submit" :disabled="moderating">确认隐藏</button><button class="button button-quiet" type="button" @click="moderationTarget = null">取消</button></div></form>
                </template>
              </article>
            </div>
            <p v-else class="muted empty-copy">还没有讨论，成为第一个留下线索的人。</p>
            <button v-if="commentHasMore" class="button button-quiet load-more" type="button" :disabled="loadingMoreComments" @click="loadMoreComments"><LoaderCircle v-if="loadingMoreComments" :size="16" />{{ loadingMoreComments ? '正在加载…' : '加载更多讨论' }}</button>
          </section>
        </div>
        <aside class="detail-aside">
          <div class="aside-card"><span class="eyebrow">CASE STATUS</span><strong>{{ post.status }}</strong><p>线索 {{ post.clues?.length ?? 0 }} 条 · 已有 {{ post.solvedCount }} 人参与</p><div v-if="post.viewerState.readingProgress" class="progress-meter"><span :style="{ width: `${post.viewerState.readingProgress}%` }" /><small>阅读进度 {{ post.viewerState.readingProgress }}%</small></div></div>
          <div v-if="related.length" class="related-list"><div class="section-heading"><h2>相邻轨道</h2></div><PostCard v-for="item in related" :key="item.id" :post="item" /></div>
        </aside>
      </section>
    </article>
  </div>
</template>
