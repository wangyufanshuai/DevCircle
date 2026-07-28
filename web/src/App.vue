<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { NButton, NConfigProvider, NIcon, NInput, NSelect } from 'naive-ui'
import { Bell, Compass, Orbit, PenLine, Search, Shield, Sparkles } from 'lucide-vue-next'
import AdminWorkbench from './components/AdminWorkbench.vue'
import ChannelView from './components/ChannelView.vue'
import DetailDrawer from './components/DetailDrawer.vue'
import PortalHome from './components/PortalHome.vue'
import WriteWorkbench from './components/WriteWorkbench.vue'
import {
  createPost,
  demoOAuthLogin,
  fetchAudits,
  fetchDegradationOverview,
  fetchFeed,
  fetchLeaderboard,
  fetchModerationTasks,
  fetchNotifications,
  fetchOpsHealth,
  fetchOutboxEvents,
  fetchPostDetail,
  fetchRecommendExperiments,
  fetchRelatedPosts,
  fetchRiskUsers,
  followUser,
  interact,
  moderationAction,
  recordExposures,
  recordRecommendEvent,
  replayOutbox,
  searchPosts,
  submitDeductionAnswer
} from './api/client'
import type { AuditItem, DegradationNotice, DegradationOverview, FeedItem, LeaderboardEntry, ModerationTask, NotificationItem, OpsHealth, OutboxEvent, Post, RecommendExperiment, RiskUser } from './api/types'
import { sceneToTheme, sceneTitle, themeOptions, wrapPost } from './view-model'

const navItems = [
  { key: 'mixed', label: '星环首页', icon: Compass },
  { key: 'deduction', label: '推理馆', icon: Search },
  { key: 'sci_fi', label: '科幻馆', icon: Sparkles },
  { key: 'cosmos', label: '宇宙档案', icon: Orbit },
  { key: 'write', label: '创作台', icon: PenLine },
  { key: 'admin', label: '运营台', icon: Shield }
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
const message = ref('')
const degradedMessage = ref('')
const nextCursor = ref('')
const hasMore = ref(false)
const loadingFeed = ref(false)
const publishing = ref(false)
const answerText = ref('凶手利用夹层藏身，并通过怀表制造时间差。')
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
  clues: '怀表停在 00:07；鞋印只到楼梯口；航海日志缺了一页。',
  solution: '守夜人藏在钟楼夹层，利用怀表制造时间差。'
})

const unreadCount = computed(() => notifications.value.filter((item) => !item.read).length)
const activeTitle = computed(() => sceneTitle(selectedNav.value))
const displayedItems = computed<FeedItem[]>(() => {
  const base = searchResults.value.length > 0 ? searchResults.value.map((post) => wrapPost(post, '主题搜索命中', 'SEARCH')) : feedItems.value
  if (selectedNav.value === 'search' && selectedSearchTheme.value !== 'mixed') {
    const theme = sceneToTheme(selectedSearchTheme.value)
    return base.filter((item) => item.post.theme === theme)
  }
  return base
})
const themeStats = computed(() => [
  { label: '推理案件', value: feedItems.value.filter((item) => item.post.theme === 'DEDUCTION').length, hint: '可答题、可积分' },
  { label: '科幻作品', value: feedItems.value.filter((item) => item.post.theme === 'SCI_FI').length, hint: '短篇与世界观' },
  { label: '宇宙档案', value: feedItems.value.filter((item) => item.post.theme === 'COSMOS').length, hint: '观测与科普' },
  { label: '候选内容', value: feedItems.value.length, hint: '当前推荐池' }
])

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

async function openItem(item: FeedItem) {
  await recordRecommendEvent(item, 'detail_view')
  selectedPost.value = await fetchPostDetail(item.post.id)
  relatedPosts.value = await fetchRelatedPosts(item.post.id)
}

async function openPost(post: Post) {
  const item = feedItems.value.find((candidate) => candidate.post.id === post.id)
  if (item) await openItem(item)
  else {
    selectedPost.value = await fetchPostDetail(post.id)
    relatedPosts.value = await fetchRelatedPosts(post.id)
  }
}

async function runSearch() {
  searchResults.value = await searchPosts(keyword.value)
  if (selectedSearchTheme.value !== 'mixed') {
    const theme = sceneToTheme(selectedSearchTheme.value)
    searchResults.value = searchResults.value.filter((post) => post.theme === theme)
  }
  selectedNav.value = 'search'
  selectedPost.value = null
}

async function toggleInteraction(postId: number, type: 'like' | 'favorite') {
  const item = feedItems.value.find((candidate) => candidate.post.id === postId)
  await interact(postId, type)
  if (item) await recordRecommendEvent(item, type)
  feedItems.value = feedItems.value.map((candidate) => candidate.post.id === postId
    ? {
        ...candidate,
        liked: type === 'like' ? true : candidate.liked,
        favorited: type === 'favorite' ? true : candidate.favorited,
        post: { ...candidate.post, likes: type === 'like' && !candidate.liked ? candidate.post.likes + 1 : candidate.post.likes }
      }
    : candidate)
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
    message.value = `内容 #${post.id} 已提交：${post.title} / ${post.status}`
  } finally {
    publishing.value = false
  }
}

async function answerCase(postId: number) {
  const result = await submitDeductionAnswer(postId, answerText.value)
  const item = feedItems.value.find((candidate) => candidate.post.id === postId)
  if (item) await recordRecommendEvent(item, 'answer')
  answerMessage.value = `${result.correct ? '推理正确' : '推理未闭合'}，获得 ${result.points} 分。${result.explanation}`
  leaderboard.value = await fetchLeaderboard()
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
  degradationNotices.value = [detail, ...degradationNotices.value].slice(0, 5)
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
  message.value = '已触发 Outbox 重放，积压状态已刷新。'
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
            <p class="eyebrow">DevCircle v1.4 cinematic portal</p>
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

        <div v-if="message || degradedMessage || degradationNotices.length" class="degradation-bar">
          <span v-if="message">{{ message }}</span>
          <span v-if="degradedMessage">{{ degradedMessage }}</span>
          <span v-for="notice in degradationNotices" :key="notice.at + notice.feature">
            {{ notice.feature }} / {{ notice.source }} / {{ notice.reason }}
          </span>
        </div>

        <section v-if="!['write', 'admin'].includes(selectedNav)" class="metric-strip">
          <article v-for="stat in themeStats" :key="stat.label">
            <span>{{ stat.label }}</span>
            <strong>{{ stat.value }}</strong>
            <small>{{ stat.hint }}</small>
          </article>
        </section>

        <WriteWorkbench v-if="selectedNav === 'write'" :draft="draft" :publishing="publishing" @publish="publishPost" />
        <AdminWorkbench
          v-else-if="selectedNav === 'admin'"
          :moderation-tasks="moderationTasks"
          :risk-users="riskUsers"
          :recommend-experiments="recommendExperiments"
          :ops-health="opsHealth"
          :outbox-events="outboxEvents"
          :degradation-overview="degradationOverview"
          :leaderboard="leaderboard"
          @moderation="handleModeration"
          @refresh-ops="refreshOps"
          @replay-outbox="handleReplayOutbox"
        />
        <PortalHome
          v-else-if="selectedNav === 'mixed'"
          :items="displayedItems"
          :leaderboard="leaderboard"
          @open="openItem"
          @navigate="switchNav"
        />
        <ChannelView
          v-else
          :scene="selectedNav"
          :items="displayedItems"
          :loading="loadingFeed"
          :has-more="hasMore"
          @open="openItem"
          @like="(id) => toggleInteraction(id, 'like')"
          @favorite="(id) => toggleInteraction(id, 'favorite')"
          @follow="follow"
          @refresh="() => loadFeed()"
          @load-more="() => loadFeed(nextCursor)"
        />

        <DetailDrawer
          v-if="selectedPost"
          :post="selectedPost"
          :related="relatedPosts"
          v-model:answer-text="answerText"
          :answer-message="answerMessage"
          @close="selectedPost = null"
          @answer="answerCase"
          @open-related="openPost"
        />
      </main>
    </div>
  </n-config-provider>
</template>
