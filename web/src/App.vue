<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NConfigProvider, NIcon, NInput, NSelect, NTag } from 'naive-ui'
import { Bell, BookOpen, Check, Compass, FileText, Heart, Orbit, PenLine, Search, Shield, Sparkles, Telescope, UserPlus, X } from 'lucide-vue-next'
import { createPost, demoOAuthLogin, fetchAudits, fetchFeed, fetchLeaderboard, fetchNotifications, followUser, interact, recordExposures, searchPosts, submitDeductionAnswer } from './api/client'
import type { AuditItem, FeedItem, LeaderboardEntry, NotificationItem, Post } from './api/types'

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
const notifications = ref<NotificationItem[]>([])
const leaderboard = ref<LeaderboardEntry[]>([])
const keyword = ref('')
const selectedNav = ref('mixed')
const scene = ref('mixed')
const selectedSearchTheme = ref('mixed')
const approved = ref<number[]>([])
const rejected = ref<number[]>([])
const message = ref('')
const nextCursor = ref('')
const hasMore = ref(false)
const loadingFeed = ref(false)
const publishing = ref(false)
const answerText = ref('守夜人藏在钟楼夹层')
const answerMessage = ref('')
const draft = ref({
  title: '',
  content: '',
  tags: '密室,时间线',
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
const themeStats = computed(() => [
  { label: '推理案件', value: visibleFeed.value.filter((item) => item.post.theme === 'DEDUCTION').length, hint: '可答题、可积分' },
  { label: '科幻创作', value: visibleFeed.value.filter((item) => item.post.theme === 'SCI_FI').length, hint: '短篇与世界观' },
  { label: '宇宙档案', value: visibleFeed.value.filter((item) => item.post.theme === 'COSMOS').length, hint: '观测和科普' },
  { label: '未读通知', value: unreadCount.value, hint: '互动与审核反馈' }
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
const firstSolvableCase = computed(() => visibleFeed.value.find((item) => item.post.solvable)?.post)

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
    await recordExposures(page.records.map((item) => item.post.id), `${scene.value}_feed`)
  } finally {
    loadingFeed.value = false
  }
}

async function switchNav(key: string) {
  selectedNav.value = key
  searchResults.value = []
  if (['mixed', 'deduction', 'sci_fi', 'cosmos'].includes(key)) {
    scene.value = key
    await loadFeed()
  }
}

async function runSearch() {
  searchResults.value = await searchPosts(keyword.value)
  if (selectedSearchTheme.value !== 'mixed') {
    const map: Record<string, string> = { deduction: 'DEDUCTION', sci_fi: 'SCI_FI', cosmos: 'COSMOS' }
    searchResults.value = searchResults.value.filter((post) => post.theme === map[selectedSearchTheme.value])
  }
  selectedNav.value = 'search'
}

async function toggleInteraction(postId: number, type: 'like' | 'favorite') {
  await interact(postId, type)
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
    feedItems.value = [wrapPost(post, '刚刚提交，已进入审核与事件队列', 'LOCAL_CREATE'), ...feedItems.value]
    selectedNav.value = 'mixed'
    message.value = `内容 #${post.id} 已提交：${themeLabel(post.theme)} / ${kindLabel(post.contentKind)} / ${post.status}`
  } finally {
    publishing.value = false
  }
}

async function answerCase(postId: number) {
  const result = await submitDeductionAnswer(postId, answerText.value)
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

async function login(provider: 'github' | 'gitee') {
  message.value = await demoOAuthLogin(provider)
}

async function follow(author: string) {
  const id = author.split('').reduce((sum, char) => sum + char.charCodeAt(0), 0)
  const result = await followUser(id)
  message.value = result.duplicated ? `已关注创作者 ${author}` : `关注创作者 ${author} 成功`
}

onMounted(async () => {
  try {
    const [auditData, notificationData, leaderboardData] = await Promise.all([fetchAudits(), fetchNotifications(), fetchLeaderboard()])
    audits.value = auditData
    notifications.value = notificationData
    leaderboard.value = leaderboardData
  } catch {
    message.value = '接口暂不可用，已切换到本地演示数据。'
  }
  await loadFeed()
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

      <main class="workspace">
        <header class="topbar">
          <n-input v-model:value="keyword" size="large" placeholder="搜索案件、科幻短篇、宇宙档案、线索或标签" clearable @keyup.enter="runSearch">
            <template #prefix><n-icon :component="Search" /></template>
          </n-input>
          <n-select v-model:value="selectedSearchTheme" :options="themeOptions" size="large" class="theme-filter" />
          <div class="topbar-actions">
            <button class="oauth-button" @click="login('github')">GitHub 登录</button>
            <button class="oauth-button" @click="login('gitee')">Gitee 登录</button>
            <button class="icon-button" aria-label="通知"><n-icon :component="Bell" /><span v-if="unreadCount" class="notice-dot">{{ unreadCount }}</span></button>
          </div>
        </header>

        <section class="hero-panel">
          <div>
            <p class="eyebrow">DevCircle v0.8 产品重塑</p>
            <h1>把普通社交 Feed 改造成推理、科幻与宇宙的内容宇宙</h1>
            <p>案件可提交答案，科幻可沉淀世界观，宇宙档案可按主题检索；推荐模型用主题偏好、热度、难度、曝光记录和解谜状态排序。</p>
          </div>
          <div class="hero-actions">
            <n-select v-model:value="scene" :options="themeOptions" size="medium" @update:value="loadFeed()" />
            <n-button type="primary" :loading="loadingFeed" @click="loadFeed()">刷新推荐</n-button>
          </div>
        </section>

        <section class="content-grid">
          <div class="feed-column">
            <div class="section-heading">
              <div>
                <h2>{{ activeTitle }}</h2>
                <p>内容按主题、类型、难度和互动信号组织，不再是空泛的通用社交列表。</p>
              </div>
              <n-button v-if="hasMore && searchResults.length === 0" secondary @click="loadFeed(nextCursor)">加载更多</n-button>
            </div>

            <div v-if="message" class="audit-message">{{ message }}</div>

            <section v-if="selectedNav === 'write'" class="post-card composer">
              <h2>创作台</h2>
              <div class="form-grid">
                <n-select v-model:value="draft.theme" :options="[{ label: '推理', value: 'DEDUCTION' }, { label: '科幻', value: 'SCI_FI' }, { label: '宇宙', value: 'COSMOS' }]" />
                <n-select v-model:value="draft.contentKind" :options="contentKindOptions" />
                <n-select v-model:value="draft.difficulty" :options="difficultyOptions" />
                <n-select v-model:value="draft.type" :options="[{ label: '文字', value: 'TEXT' }, { label: '图片', value: 'IMAGE' }, { label: '视频', value: 'VIDEO' }]" />
              </div>
              <n-input v-model:value="draft.title" placeholder="标题，例如：雾港钟楼失踪案" />
              <n-input v-model:value="draft.content" type="textarea" placeholder="正文：案件叙述、科幻短篇、世界观设定或宇宙档案" />
              <n-input v-model:value="draft.tags" placeholder="标签，用英文逗号分隔" />
              <n-input v-model:value="draft.clues" type="textarea" placeholder="推理线索；非推理内容可留作设定要点" />
              <n-input v-model:value="draft.solution" placeholder="隐藏答案或作者解析" />
              <label class="check-row"><input v-model="draft.solvable" type="checkbox" /> 允许用户提交推理答案并参与积分</label>
              <n-button type="primary" :disabled="!draft.title || !draft.content" :loading="publishing" @click="publishPost">提交到审核与事件队列</n-button>
            </section>

            <section v-if="selectedNav !== 'write' && firstSolvableCase" class="case-panel">
              <div>
                <p class="eyebrow">推理挑战</p>
                <h2>{{ firstSolvableCase.title }}</h2>
                <p>{{ firstSolvableCase.excerpt }}</p>
              </div>
              <div class="answer-box">
                <n-input v-model:value="answerText" placeholder="提交你的推理结论" />
                <n-button type="primary" @click="answerCase(firstSolvableCase.id)">提交答案</n-button>
              </div>
              <div v-if="answerMessage" class="audit-message">{{ answerMessage }}</div>
            </section>

            <article v-for="item in visibleFeed" :key="item.post.id" class="post-card" :class="`theme-${item.post.theme.toLowerCase()}`">
              <div class="post-meta">
                <span>{{ themeLabel(item.post.theme) }}</span>
                <span>{{ kindLabel(item.post.contentKind) }}</span>
                <span>{{ difficultyLabel(item.post.difficulty) }}</span>
                <span>{{ item.post.status === 'PENDING' ? '待审核' : '已发布' }}</span>
                <span>{{ item.algorithm }}</span>
              </div>
              <h2>{{ item.post.title }}</h2>
              <p>{{ item.post.excerpt }}</p>
              <div v-if="item.post.mediaAssets.length" class="inline-media">
                <img v-if="item.post.type === 'IMAGE'" :src="item.post.mediaAssets[0].url" :alt="item.post.title" />
                <video v-if="item.post.type === 'VIDEO'" :src="item.post.mediaAssets[0].url" controls muted playsinline />
              </div>
              <div class="tag-row"><n-tag v-for="tag in item.post.tags" :key="tag" size="small" round>{{ tag }}</n-tag></div>
              <footer>
                <span>{{ item.post.likes }} 赞</span>
                <span>{{ item.post.comments }} 评论</span>
                <span>{{ item.post.views }} 浏览</span>
                <span v-if="item.post.solvable">{{ item.post.solvedCount }} 人解出</span>
                <span>分数 {{ Math.round(item.score) }}</span>
                <button class="text-action" @click="toggleInteraction(item.post.id, 'like')"><n-icon :component="Heart" /> {{ item.liked ? '已赞' : '点赞' }}</button>
                <button class="text-action" @click="toggleInteraction(item.post.id, 'favorite')">{{ item.favorited ? '已收藏' : '收藏' }}</button>
                <button class="text-action" @click="follow(item.post.author)"><n-icon :component="UserPlus" /> 关注</button>
                <span class="reason">{{ item.reason }}</span>
              </footer>
            </article>
            <div v-if="!loadingFeed && visibleFeed.length === 0" class="empty-state post-empty">没有匹配的主题内容</div>
          </div>

          <aside class="right-rail">
            <section class="rail-panel metric-panel">
              <h2>主题概览</h2>
              <div class="metric-grid">
                <div v-for="stat in themeStats" :key="stat.label" class="metric-card">
                  <strong>{{ stat.value }}</strong>
                  <span>{{ stat.label }}</span>
                  <small>{{ stat.hint }}</small>
                </div>
              </div>
            </section>
            <section class="rail-panel">
              <h2><n-icon :component="Telescope" /> 宇宙观测信号</h2>
              <div class="trend-list"><span>星云</span><span>暗物质</span><span>旅行者</span><span>旋臂</span><span>脉冲星</span></div>
            </section>
            <section class="rail-panel">
              <h2><n-icon :component="BookOpen" /> 解谜排行榜</h2>
              <div v-for="entry in leaderboard" :key="entry.userId" class="author-row">
                <div><strong>{{ entry.username }}</strong><span>{{ entry.correctCount }} 题正确</span></div>
                <em>{{ entry.totalPoints }}</em>
              </div>
            </section>
            <section class="rail-panel moderation">
              <div class="panel-title"><h2>待审核</h2><strong>{{ pendingPosts.length }}</strong></div>
              <div v-for="post in pendingPosts" :key="post.postId" class="audit-row">
                <div><strong>{{ post.title }}</strong><span>{{ post.author }} 提交</span></div>
                <div class="audit-actions">
                  <button class="approve" @click="approve(post.postId)" aria-label="通过"><n-icon :component="Check" /></button>
                  <button class="reject" @click="reject(post.postId)" aria-label="驳回"><n-icon :component="X" /></button>
                </div>
              </div>
              <div v-if="pendingPosts.length === 0" class="empty-state">审核队列已清空</div>
            </section>
            <section class="rail-panel">
              <h2>通知</h2>
              <div v-for="item in notifications" :key="item.id" class="notification-row" :class="{ unread: !item.read }"><strong>{{ item.title }}</strong><span>{{ item.content }}</span></div>
            </section>
          </aside>
        </section>
      </main>
    </div>
  </n-config-provider>
</template>
