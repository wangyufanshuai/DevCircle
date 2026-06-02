<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { NButton, NConfigProvider, NIcon, NInput, NSelect, NTag } from 'naive-ui'
import { Bell, Check, Compass, Heart, Orbit, PenLine, Search, Shield, Sparkles, Telescope, UserPlus, X } from 'lucide-vue-next'
import { createPost, demoOAuthLogin, fetchAudits, fetchDegradationOverview, fetchFeed, fetchLeaderboard, fetchModerationTasks, fetchNotifications, fetchOpsHealth, fetchOutboxEvents, fetchPostDetail, fetchRecommendExperiments, fetchRelatedPosts, fetchRiskUsers, followUser, interact, moderationAction, recordExposures, recordRecommendEvent, replayOutbox, searchPosts, submitDeductionAnswer } from './api/client'
import type { AuditItem, DegradationNotice, DegradationOverview, FeedItem, LeaderboardEntry, ModerationTask, NotificationItem, OpsHealth, OutboxEvent, Post, RecommendExperiment, RiskUser } from './api/types'

const navItems = [
  { key: 'mixed', label: '星环首页', icon: Compass },
  { key: 'deduction', label: '推理馆', icon: Search },
  { key: 'sci_fi', label: '科幻馆', icon: Sparkles },
  { key: 'cosmos', label: '宇宙档案', icon: Orbit },
  { key: 'write', label: '创作台', icon: PenLine },
  { key: 'admin', label: '审核台', icon: Shield }
]

const themeOptions = [
  { label: '混合推荐', value: 'mixed' },
  { label: '推理', value: 'deduction' },
  { label: '科幻', value: 'sci_fi' },
  { label: '宇宙', value: 'cosmos' }
]

const contentKindOptions = [
  { label: '案件档案', value: 'CASE_FILE' },
  { label: '逻辑谜题', value: 'PUZZLE' },
  { label: '科幻短篇', value: 'FICTION' },
  { label: '世界观设定', value: 'WORLDBUILDING' },
  { label: '宇宙档案', value: 'COSMIC_ARTICLE' },
  { label: '观测日志', value: 'OBSERVATION_LOG' }
]

const difficultyOptions = [
  { label: '入门', value: 'EASY' },
  { label: '进阶', value: 'MEDIUM' },
  { label: '高难', value: 'HARD' }
]

const feedItems = ref<FeedItem[]>([])
const searchResults = ref<Post[]>([])
const audits = ref<AuditItem[]>([])
const moderationTasks = ref<ModerationTask[]>([])
const riskUsers = ref<RiskUser[]>([])
const recommendExperiments = ref<RecommendExperiment[]>([])
const opsHealth = ref<OpsHealth | null>(null)
const outboxEvents = ref<OutboxEvent[]>([])
const degradationOverview = ref<DegradationOverview | null>(null)
const degradationNotices = ref<DegradationNotice[]>([])
const notifications = ref<NotificationItem[]>([])
const leaderboard = ref<LeaderboardEntry[]>([])
const keyword = ref('')
const selectedNav = ref('mixed')
const scene = ref('mixed')
const selectedSearchTheme = ref('mixed')
const approved = ref<number[]>([])
const rejected = ref<number[]>([])
const message = ref('')
const degradedMessage = ref('')
const nextCursor = ref('')
const hasMore = ref(false)
const loadingFeed = ref(false)
const publishing = ref(false)
const answerText = ref('守夜人藏在钟楼夹层')
const answerMessage = ref('')
const selectedPost = ref<Post | null>(null)
const relatedPosts = ref<Post[]>([])
const draft = ref({
  title: '雾港钟楼的第十三声',
  content: '午夜后钟楼响了十三声，守夜人的怀表停在 00:07，鞋印只到楼梯口，航海日志缺了一页。',
  tags: '密室,时间线,钟楼',
  type: 'TEXT' as 'TEXT' | 'IMAGE' | 'VIDEO',
  theme: 'DEDUCTION' as 'DEDUCTION' | 'SCI_FI' | 'COSMOS',
  contentKind: 'CASE_FILE' as 'CASE_FILE' | 'PUZZLE' | 'FICTION' | 'WORLDBUILDING' | 'COSMIC_ARTICLE' | 'OBSERVATION_LOG',
  difficulty: 'MEDIUM' as 'EASY' | 'MEDIUM' | 'HARD',
  solvable: true,
  clues: '怀表停在 00:07；鞋印只到楼梯口；航海日志缺了一页',
  solution: '守夜人藏在钟楼夹层'
})

const unreadCount = computed(() => notifications.value.filter((item) => !item.read).length)
const pendingPosts = computed(() => audits.value.filter((post) => !approved.value.includes(post.postId) && !rejected.value.includes(post.postId)))
const visibleFeed = computed(() => searchResults.value.length > 0 ? searchResults.value.map((post) => wrapPost(post, '主题搜索命中', 'SEARCH')) : feedItems.value)
const firstSolvableCase = computed(() => visibleFeed.value.find((item) => item.post.solvable)?.post)
const themeStats = computed(() => [
  { label: '推理案件', value: visibleFeed.value.filter((item) => item.post.theme === 'DEDUCTION').length, hint: '可答题、可积分' },
  { label: '科幻创作', value: visibleFeed.value.filter((item) => item.post.theme === 'SCI_FI').length, hint: '短篇与世界观' },
  { label: '宇宙档案', value: visibleFeed.value.filter((item) => item.post.theme === 'COSMOS').length, hint: '观测与科普' },
  { label: '候选内容', value: visibleFeed.value.length, hint: '当前频道可见' }
])
const activeTitle = computed(() => {
  if (selectedNav.value === 'deduction') return '推理馆'
  if (selectedNav.value === 'sci_fi') return '科幻馆'
  if (selectedNav.value === 'cosmos') return '宇宙档案'
  if (selectedNav.value === 'write') return '创作台'
  if (selectedNav.value === 'admin') return '审核台'
  if (selectedNav.value === 'search') return '搜索结果'
  return '星环首页'
})

function wrapPost(post: Post, reason: string, algorithm: string): FeedItem {
  return { post, reason, score: 0, liked: false, favorited: false, algorithm, mediaType: post.type, features: {} }
}

function themeLabel(theme: string) {
  return theme === 'DEDUCTION' ? '推理' : theme === 'SCI_FI' ? '科幻' : '宇宙'
}

function kindLabel(kind: string) {
  return contentKindOptions.find((item) => item.value === kind)?.label ?? kind
}

function difficultyLabel(difficulty: string) {
  return difficultyOptions.find((item) => item.value === difficulty)?.label ?? difficulty
}

async function loadFeed(cursor?: string) {
  loadingFeed.value = true
  try {
    const page = await fetchFeed(scene.value, cursor)
    feedItems.value = cursor ? [...feedItems.value, ...page.records] : page.records
    nextCursor.value = page.nextCursor
    hasMore.value = page.hasMore
    if (page.degraded || page.source === 'SEED_FALLBACK' || page.source === 'MOCK_FALLBACK') {
      degradedMessage.value = `推荐流已进入显式降级：${page.source ?? 'UNKNOWN'}，原因：${page.degradationReason ?? '候选为空或依赖不可用'}`
    }
    await recordExposures(page.records.map((item) => item.post.id), `${scene.value}_feed`)
  } finally {
    loadingFeed.value = false
  }
}

async function switchNav(key: string) {
  selectedNav.value = key
  selectedPost.value = null
  relatedPosts.value = []
  searchResults.value = []
  if (['mixed', 'deduction', 'sci_fi', 'cosmos'].includes(key)) {
    scene.value = key
    await loadFeed()
  }
}

async function openDetail(post: Post) {
  const item = feedItems.value.find((candidate) => candidate.post.id === post.id)
  if (item) await recordRecommendEvent(item, 'detail_view')
  selectedPost.value = await fetchPostDetail(post.id)
  relatedPosts.value = await fetchRelatedPosts(post.id)
}

async function runSearch() {
  searchResults.value = await searchPosts(keyword.value)
  if (selectedSearchTheme.value !== 'mixed') {
    const map: Record<string, string> = { deduction: 'DEDUCTION', sci_fi: 'SCI_FI', cosmos: 'COSMOS' }
    searchResults.value = searchResults.value.filter((post) => post.theme === map[selectedSearchTheme.value])
  }
  selectedNav.value = 'search'
  selectedPost.value = null
}

async function toggleInteraction(postId: number, type: 'like' | 'favorite') {
  const item = feedItems.value.find((candidate) => candidate.post.id === postId)
  await interact(postId, type)
  if (item) await recordRecommendEvent(item, type)
  feedItems.value = feedItems.value.map((item) => item.post.id === postId
    ? {
        ...item,
        liked: type === 'like' ? true : item.liked,
        favorited: type === 'favorite' ? true : item.favorited,
        post: { ...item.post, likes: type === 'like' && !item.liked ? item.post.likes + 1 : item.post.likes }
      }
    : item)
}

async function publishPost() {
  publishing.value = true
  try {
    const post = await createPost({
      ...draft.value,
      tags: draft.value.tags.split(',').map((tag) => tag.trim()).filter(Boolean),
      mediaIds: []
    })
    feedItems.value = [wrapPost(post, '刚刚提交，已进入审核和事件队列', 'LOCAL_CREATE'), ...feedItems.value]
    selectedNav.value = 'mixed'
    message.value = `内容 #${post.id} 已提交：${themeLabel(post.theme)} / ${kindLabel(post.contentKind)} / ${post.status}`
  } finally {
    publishing.value = false
  }
}

async function answerCase(postId?: number) {
  const targetId = postId ?? firstSolvableCase.value?.id
  if (!targetId) return
  const result = await submitDeductionAnswer(targetId, answerText.value)
  const item = feedItems.value.find((candidate) => candidate.post.id === targetId)
  if (item) await recordRecommendEvent(item, 'answer')
  answerMessage.value = `${result.correct ? '推理正确' : '推理未闭合'}，获得 ${result.points} 分。${result.explanation}`
  leaderboard.value = await fetchLeaderboard()
}

async function approve(id: number) {
  approved.value.push(id)
  message.value = `已通过内容 #${id}`
}

async function reject(id: number) {
  rejected.value.push(id)
  message.value = `已驳回内容 #${id}`
}

async function handleModeration(taskId: number, action: 'approve' | 'reject' | 'escalate' | 'require-changes') {
  await moderationAction(taskId, action)
  moderationTasks.value = moderationTasks.value.map((item) => item.taskId === taskId ? { ...item, status: action.toUpperCase() } : item)
  message.value = `治理任务 #${taskId} 已执行 ${action}`
}

async function login(provider: 'github' | 'gitee') {
  message.value = await demoOAuthLogin(provider)
}

async function follow(author: string) {
  const id = author.split('').reduce((sum, char) => sum + char.charCodeAt(0), 0)
  const result = await followUser(id)
  message.value = result.duplicated ? `已关注创作者 ${author}` : `关注创作者 ${author} 成功`
}

function onDegraded(event: Event) {
  const detail = (event as CustomEvent<DegradationNotice>).detail
  degradationNotices.value = [detail, ...degradationNotices.value].slice(0, 6)
  degradedMessage.value = `接口 ${detail.feature} 暂不可用，来源 ${detail.source}，原因：${detail.reason}`
}

async function refreshOps() {
  const [health, outbox, degradation] = await Promise.all([
    fetchOpsHealth(),
    fetchOutboxEvents(),
    fetchDegradationOverview()
  ])
  opsHealth.value = health
  outboxEvents.value = outbox
  degradationOverview.value = degradation
}

async function handleReplayOutbox() {
  await replayOutbox()
  await refreshOps()
  message.value = '已触发 Outbox 重放，积压状态已刷新'
}

onMounted(async () => {
  window.addEventListener('devcircle-api-degraded', onDegraded)
  try {
    const [auditData, notificationData, leaderboardData, moderationData, riskData, experimentData, healthData, outboxData, degradationData] = await Promise.all([
      fetchAudits(),
      fetchNotifications(),
      fetchLeaderboard(),
      fetchModerationTasks(),
      fetchRiskUsers(),
      fetchRecommendExperiments(),
      fetchOpsHealth(),
      fetchOutboxEvents(),
      fetchDegradationOverview()
    ])
    audits.value = auditData
    notifications.value = notificationData
    leaderboard.value = leaderboardData
    moderationTasks.value = moderationData
    riskUsers.value = riskData
    recommendExperiments.value = experimentData
    opsHealth.value = healthData
    outboxEvents.value = outboxData
    degradationOverview.value = degradationData
  } catch {
    message.value = '接口暂不可用，已切换到本地演示数据。'
  }
  await loadFeed()
})

onUnmounted(() => {
  window.removeEventListener('devcircle-api-degraded', onDegraded)
})
</script>

<template>
  <n-config-provider>
    <div class="app-shell">
      <aside class="sidebar">
        <div class="brand">
          <div class="brand-mark">MV</div>
          <div>
            <strong>MysticVerse</strong>
            <span>推理 / 科幻 / 宇宙主题内容平台</span>
          </div>
        </div>
        <nav class="nav-list" aria-label="主导航">
          <button v-for="item in navItems" :key="item.key" class="nav-item" :class="{ active: selectedNav === item.key }" :aria-label="item.label" @click="switchNav(item.key)">
            <n-icon :component="item.icon" />
            <span>{{ item.label }}</span>
          </button>
        </nav>
        <button class="write-button" @click="switchNav('write')">
          <n-icon :component="PenLine" />
          发布主题内容
        </button>
      </aside>

      <main class="main">
        <header class="topbar">
          <div>
            <p class="eyebrow">DevCircle v1.3 deduction library acceptance track</p>
            <h1>{{ activeTitle }}</h1>
          </div>
          <div class="searchbar">
            <n-input v-model:value="keyword" size="large" placeholder="搜索案件、科幻短篇、宇宙档案、线索或标签" clearable @keyup.enter="runSearch">
              <template #prefix><n-icon :component="Search" /></template>
            </n-input>
            <n-select v-model:value="selectedSearchTheme" :options="themeOptions" size="large" class="theme-filter" />
            <n-button type="primary" @click="runSearch">搜索</n-button>
          </div>
          <div class="topbar-actions">
            <n-button secondary @click="login('github')">GitHub 登录</n-button>
            <n-button secondary @click="login('gitee')">Gitee 登录</n-button>
            <div class="bell"><n-icon :component="Bell" />{{ unreadCount }}</div>
          </div>
        </header>

        <div v-if="message || degradedMessage || degradationNotices.length" class="message-strip">
          <span v-if="message">{{ message }}</span>
          <span v-if="degradedMessage" class="degraded">{{ degradedMessage }}</span>
          <span v-for="notice in degradationNotices" :key="notice.at + notice.feature" class="degraded">
            {{ notice.feature }} / {{ notice.source }} / {{ notice.reason }}
          </span>
        </div>

        <section v-if="selectedNav !== 'write' && selectedNav !== 'admin'" class="dashboard-grid">
          <article v-for="stat in themeStats" :key="stat.label" class="stat-card">
            <span>{{ stat.label }}</span>
            <strong>{{ stat.value }}</strong>
            <small>{{ stat.hint }}</small>
          </article>
        </section>

        <section v-if="selectedNav === 'write'" class="workbench">
          <div class="panel wide">
            <h2>创作台</h2>
            <div class="form-grid">
              <label>标题<n-input v-model:value="draft.title" /></label>
              <label>标签<n-input v-model:value="draft.tags" placeholder="逗号分隔" /></label>
              <label>主题<n-select v-model:value="draft.theme" :options="[
                { label: '推理', value: 'DEDUCTION' },
                { label: '科幻', value: 'SCI_FI' },
                { label: '宇宙', value: 'COSMOS' }
              ]" /></label>
              <label>内容类型<n-select v-model:value="draft.contentKind" :options="contentKindOptions" /></label>
              <label>难度<n-select v-model:value="draft.difficulty" :options="difficultyOptions" /></label>
              <label>媒介<n-select v-model:value="draft.type" :options="[
                { label: '文字', value: 'TEXT' },
                { label: '图片', value: 'IMAGE' },
                { label: '视频', value: 'VIDEO' }
              ]" /></label>
            </div>
            <label class="block-label">正文<n-input v-model:value="draft.content" type="textarea" :autosize="{ minRows: 5, maxRows: 8 }" /></label>
            <label class="block-label">推理线索<n-input v-model:value="draft.clues" type="textarea" :autosize="{ minRows: 3, maxRows: 5 }" /></label>
            <label class="block-label">隐藏答案 / 解析<n-input v-model:value="draft.solution" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" /></label>
            <n-button type="primary" :loading="publishing" @click="publishPost">提交审核与事件队列</n-button>
          </div>
        </section>

        <section v-else-if="selectedNav === 'admin'" class="workbench">
          <div class="panel">
            <h2>治理审核队列</h2>
            <div v-for="item in moderationTasks" :key="item.taskId" class="audit-row">
              <div>
                <strong>{{ item.title }}</strong>
                <span>{{ item.queue }} / {{ item.riskLevel }} / 风险 {{ item.riskScore }} / {{ item.reason }}</span>
              </div>
              <div>
                <n-button size="small" type="success" @click="handleModeration(item.taskId, 'approve')"><n-icon :component="Check" />通过</n-button>
                <n-button size="small" type="error" @click="handleModeration(item.taskId, 'reject')"><n-icon :component="X" />驳回</n-button>
                <n-button size="small" @click="handleModeration(item.taskId, 'escalate')">复审</n-button>
                <n-button size="small" @click="handleModeration(item.taskId, 'require-changes')">修改</n-button>
              </div>
            </div>
          </div>
          <div class="panel">
            <h2>风险用户</h2>
            <div v-for="user in riskUsers" :key="user.userId" class="rank-row">
              <span>用户 #{{ user.userId }} / {{ user.status }}</span>
              <strong>{{ user.riskScore }} 风险分</strong>
              <small>{{ user.reason }}</small>
            </div>
          </div>
          <div class="panel">
            <h2>推荐实验</h2>
            <div v-for="exp in recommendExperiments" :key="exp.experimentId" class="rank-row">
              <span>{{ exp.name }}</span>
              <strong>{{ exp.status }} / {{ exp.scene }}</strong>
              <small>{{ exp.experimentId }} / {{ exp.trafficPercent }}% 流量</small>
            </div>
          </div>
          <div class="panel">
            <h2>工程状态</h2>
            <div class="rank-row">
              <span>数据库 {{ opsHealth?.database ?? 'UNKNOWN' }} / 来源 {{ opsHealth?.source ?? '-' }}</span>
              <strong>Outbox {{ opsHealth?.outboxBacklog ?? 0 }}</strong>
              <small>待审 {{ opsHealth?.pendingModeration ?? 0 }} / 风险用户 {{ opsHealth?.riskUsers ?? 0 }}</small>
            </div>
            <div class="rank-row">
              <span>推荐降级 {{ degradationOverview?.degraded ? '是' : '否' }} / {{ degradationOverview?.source ?? '-' }}</span>
              <strong>事件 {{ degradationOverview?.recommendEvents ?? 0 }}</strong>
              <small>{{ degradationOverview?.reason ?? '运行状态正常或暂无降级原因' }}</small>
            </div>
            <div v-for="event in outboxEvents" :key="event.eventId" class="rank-row">
              <span>{{ event.topic }} / {{ event.status }}</span>
              <strong>{{ event.aggregateType ?? 'OUTBOX' }}</strong>
              <small>{{ event.eventId }}</small>
            </div>
            <n-button secondary @click="refreshOps">刷新工程状态</n-button>
            <n-button secondary @click="handleReplayOutbox">重放 Outbox</n-button>
          </div>
          <div class="panel">
            <h2>解谜排行榜</h2>
            <div v-for="entry in leaderboard" :key="entry.userId" class="rank-row">
              <span>{{ entry.username }}</span>
              <strong>{{ entry.totalPoints }} 分</strong>
              <small>{{ entry.correctCount }} 题正确</small>
            </div>
          </div>
        </section>

        <section v-else class="content-layout">
          <section class="feed-column">
            <div class="section-head">
              <div>
                <h2>主题推荐流</h2>
                <p>数据库/搜索候选优先，候选为空时显式降级到种子内容库。</p>
              </div>
              <n-button :loading="loadingFeed" @click="loadFeed()">刷新推荐</n-button>
            </div>
            <article v-for="item in visibleFeed" :key="item.post.id" class="post-card" @click="openDetail(item.post)">
              <div class="post-meta">
                <n-tag size="small" type="info">{{ themeLabel(item.post.theme) }}</n-tag>
                <n-tag size="small">{{ kindLabel(item.post.contentKind) }}</n-tag>
                <n-tag size="small" :bordered="false">{{ difficultyLabel(item.post.difficulty) }}</n-tag>
                <span>{{ item.algorithm }}</span>
                <span>{{ item.experimentId }} / {{ item.bucket }}</span>
              </div>
              <h3>{{ item.post.title }}</h3>
              <p>{{ item.post.excerpt }}</p>
              <div v-if="item.post.mediaAssets?.length" class="media-strip">
                <img v-for="asset in item.post.mediaAssets.slice(0, 3)" :key="asset.mediaId" :src="asset.url" alt="" />
              </div>
              <div class="post-footer">
                <span>{{ item.reason }} · {{ item.recallSources?.join(' / ') }}</span>
                <div>
                  <button @click.stop="toggleInteraction(item.post.id, 'like')"><n-icon :component="Heart" />{{ item.post.likes }}</button>
                  <button @click.stop="toggleInteraction(item.post.id, 'favorite')"><n-icon :component="Telescope" />收藏</button>
                  <button @click.stop="follow(item.post.author)"><n-icon :component="UserPlus" />{{ item.post.author }}</button>
                </div>
              </div>
            </article>
            <n-button v-if="hasMore" block :loading="loadingFeed" @click="loadFeed(nextCursor)">加载更多</n-button>
          </section>

          <aside class="side-column">
            <section class="panel">
              <h2>案件答题</h2>
              <p>{{ firstSolvableCase?.title ?? '当前频道暂无可答题案件' }}</p>
              <n-input v-model:value="answerText" type="textarea" :autosize="{ minRows: 3, maxRows: 4 }" />
              <n-button type="primary" @click="answerCase(firstSolvableCase?.id)">提交推理</n-button>
              <p class="answer">{{ answerMessage }}</p>
            </section>
            <section class="panel">
              <h2>通知</h2>
              <div v-for="item in notifications" :key="item.id" class="notification-row" :class="{ unread: !item.read }">
                <strong>{{ item.title }}</strong>
                <span>{{ item.content }}</span>
              </div>
            </section>
          </aside>
        </section>

        <section v-if="selectedPost" class="detail-drawer">
          <div class="detail-head">
            <div>
              <n-tag type="info">{{ themeLabel(selectedPost.theme) }}</n-tag>
              <h2>{{ selectedPost.title }}</h2>
              <p>{{ selectedPost.excerpt }}</p>
            </div>
            <button @click="selectedPost = null"><n-icon :component="X" /></button>
          </div>
          <div class="detail-grid">
            <div v-if="selectedPost.clues?.length" class="detail-block"><h3>线索板</h3><ul><li v-for="clue in selectedPost.clues" :key="clue">{{ clue }}</li></ul></div>
            <div v-if="selectedPost.suspects?.length" class="detail-block"><h3>嫌疑人</h3><ul><li v-for="suspect in selectedPost.suspects" :key="suspect">{{ suspect }}</li></ul></div>
            <div v-if="selectedPost.timeline?.length" class="detail-block"><h3>时间线</h3><ul><li v-for="line in selectedPost.timeline" :key="line">{{ line }}</li></ul></div>
            <div v-if="selectedPost.worldbuilding?.length" class="detail-block"><h3>世界观</h3><ul><li v-for="item in selectedPost.worldbuilding" :key="item">{{ item }}</li></ul></div>
            <div v-if="selectedPost.cosmicFacts?.length" class="detail-block"><h3>宇宙档案</h3><ul><li v-for="item in selectedPost.cosmicFacts" :key="item">{{ item }}</li></ul></div>
          </div>
          <div v-if="selectedPost.solvable" class="detail-answer">
            <h3>提交推理</h3>
            <p>{{ selectedPost.question }}</p>
            <n-input v-model:value="answerText" type="textarea" :autosize="{ minRows: 3, maxRows: 5 }" />
            <n-button type="primary" @click="answerCase(selectedPost.id)">提交推理</n-button>
            <p class="answer">{{ answerMessage }}</p>
          </div>
          <h3>相关内容</h3>
          <div class="related-grid">
            <button v-for="post in relatedPosts" :key="post.id" @click="openDetail(post)">
              <strong>{{ post.title }}</strong>
              <span>{{ themeLabel(post.theme) }} / {{ difficultyLabel(post.difficulty) }}</span>
            </button>
          </div>
        </section>
      </main>
    </div>
  </n-config-provider>
</template>

<style scoped>
:global(*) { box-sizing: border-box; }
:global(body) { margin: 0; background: #0e1116; color: #eef2f7; font-family: Inter, "Microsoft YaHei", system-ui, sans-serif; }
button { font: inherit; }
.app-shell { min-height: 100vh; display: grid; grid-template-columns: 250px minmax(0, 1fr); }
.sidebar { position: sticky; top: 0; height: 100vh; padding: 22px 18px; background: #121821; border-right: 1px solid #26303d; display: flex; flex-direction: column; gap: 20px; }
.brand { display: flex; gap: 12px; align-items: center; }
.brand-mark { width: 44px; height: 44px; display: grid; place-items: center; border-radius: 8px; background: #d7fb62; color: #111; font-weight: 900; }
.brand strong, .brand span { display: block; }
.brand span { color: #9aa6b2; font-size: 12px; margin-top: 4px; }
.nav-list { display: grid; gap: 8px; }
.nav-item, .write-button { border: 0; border-radius: 8px; padding: 12px; display: flex; align-items: center; gap: 10px; color: #cbd5e1; background: transparent; cursor: pointer; }
.nav-item.active, .nav-item:hover, .write-button { background: #202a36; color: #fff; }
.write-button { margin-top: auto; justify-content: center; background: #d7fb62; color: #111; font-weight: 700; }
.main { padding: 28px; min-width: 0; }
.topbar { display: grid; grid-template-columns: minmax(240px, 1fr) minmax(320px, 620px) auto; gap: 16px; align-items: center; }
.eyebrow { margin: 0 0 4px; color: #8fb3ff; font-size: 12px; text-transform: uppercase; }
h1, h2, h3, p { margin-top: 0; }
.searchbar { display: grid; grid-template-columns: 1fr 150px auto; gap: 10px; }
.topbar-actions { display: flex; align-items: center; gap: 10px; }
.bell { min-width: 56px; height: 38px; border-radius: 8px; background: #1b2530; display: inline-flex; align-items: center; justify-content: center; gap: 6px; }
.message-strip { margin: 18px 0; padding: 12px 14px; border: 1px solid #344456; border-radius: 8px; background: #17202b; display: grid; gap: 6px; }
.degraded { color: #ffd166; }
.dashboard-grid { margin: 18px 0; display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.stat-card, .panel, .post-card, .detail-drawer { border: 1px solid #26303d; border-radius: 8px; background: #151c25; }
.stat-card { padding: 16px; }
.stat-card span, .stat-card small { display: block; color: #9aa6b2; }
.stat-card strong { display: block; font-size: 30px; margin: 8px 0; }
.content-layout, .workbench { display: grid; grid-template-columns: minmax(0, 1fr) 360px; gap: 18px; align-items: start; }
.workbench .wide { grid-column: 1 / -1; }
.feed-column { display: grid; gap: 12px; }
.section-head { display: flex; justify-content: space-between; gap: 16px; align-items: end; }
.section-head p { color: #9aa6b2; margin: 4px 0 0; }
.post-card { padding: 18px; cursor: pointer; transition: border-color .15s, transform .15s; }
.post-card:hover { border-color: #d7fb62; transform: translateY(-1px); }
.post-meta { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; color: #9aa6b2; font-size: 12px; }
.post-card h3 { margin: 12px 0 8px; font-size: 22px; }
.post-card p { color: #cbd5e1; line-height: 1.65; }
.media-strip { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin: 12px 0; }
.media-strip img { width: 100%; aspect-ratio: 4 / 3; object-fit: cover; border-radius: 6px; }
.post-footer { display: flex; justify-content: space-between; gap: 12px; align-items: center; color: #9aa6b2; }
.post-footer div { display: flex; flex-wrap: wrap; gap: 8px; }
.post-footer button, .detail-head button, .related-grid button { border: 1px solid #334155; background: #111821; color: #dbe4ef; border-radius: 8px; padding: 8px 10px; display: inline-flex; align-items: center; gap: 6px; cursor: pointer; }
.side-column, .workbench { display: grid; gap: 14px; }
.panel { padding: 18px; }
.panel p { color: #b6c2cf; }
.answer { color: #d7fb62; margin-top: 10px; }
.notification-row, .rank-row, .audit-row { padding: 12px 0; border-top: 1px solid #26303d; display: grid; gap: 4px; }
.notification-row.unread strong { color: #d7fb62; }
.notification-row span, .rank-row small, .audit-row span { color: #9aa6b2; }
.audit-row { grid-template-columns: 1fr auto; align-items: center; }
.audit-row div:last-child { display: flex; gap: 8px; }
.form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-bottom: 12px; }
label { display: grid; gap: 6px; color: #cbd5e1; }
.block-label { margin-bottom: 12px; }
.detail-drawer { margin-top: 20px; padding: 20px; }
.detail-head { display: flex; justify-content: space-between; gap: 16px; }
.detail-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.detail-block { background: #101720; border: 1px solid #26303d; border-radius: 8px; padding: 14px; }
.detail-block li { margin-bottom: 8px; color: #cbd5e1; }
.related-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.related-grid button { display: grid; text-align: left; }
.related-grid span { color: #9aa6b2; font-size: 12px; }
@media (max-width: 980px) {
  .app-shell { grid-template-columns: 1fr; }
  .sidebar { position: static; height: auto; padding: 14px; }
  .nav-list { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .write-button { margin-top: 0; }
  .main { padding: 16px; }
  .topbar, .content-layout, .workbench, .dashboard-grid, .detail-grid, .related-grid { grid-template-columns: 1fr; }
  .searchbar { grid-template-columns: 1fr; }
  .topbar-actions, .post-footer { align-items: stretch; flex-direction: column; }
  .form-grid { grid-template-columns: 1fr; }
}
</style>
