<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NConfigProvider, NIcon, NInput, NSelect, NTag } from 'naive-ui'
import { Bell, Check, Compass, FileImage, FileText, Heart, PlaySquare, Plus, Search, Shield, Upload, UserPlus, X } from 'lucide-vue-next'
import { createPost, demoOAuthLogin, fetchAudits, fetchFeed, fetchNotifications, followUser, interact, recordExposures, searchPosts, uploadMedia } from './api/client'
import type { AuditItem, FeedItem, FileUploadResult, NotificationItem, Post } from './api/types'

const navItems = [
  { key: 'feed', label: '推荐', icon: Compass },
  { key: 'image', label: '图片', icon: FileImage },
  { key: 'video', label: '视频', icon: PlaySquare },
  { key: 'search', label: '搜索', icon: Search },
  { key: 'admin', label: '管理', icon: Shield }
]

const sceneOptions = [
  { label: '推荐', value: 'home' },
  { label: '图片', value: 'image' },
  { label: '视频', value: 'video' }
]

const feedItems = ref<FeedItem[]>([])
const searchResults = ref<Post[]>([])
const audits = ref<AuditItem[]>([])
const notifications = ref<NotificationItem[]>([])
const uploadedMedia = ref<FileUploadResult[]>([])
const keyword = ref('')
const selectedNav = ref('feed')
const scene = ref<'home' | 'image' | 'video'>('home')
const approved = ref<number[]>([])
const rejected = ref<number[]>([])
const auditMessage = ref('')
const nextCursor = ref('')
const hasMore = ref(false)
const loadingFeed = ref(false)
const publishing = ref(false)
const publishMessage = ref('')
const fallbackMessage = ref('')
const oauthMessage = ref('')
const followMessage = ref('')
const uploadMessage = ref('')
const draft = ref({ title: '', content: '', tags: 'Spring Cloud,Kafka', type: 'TEXT' as 'TEXT' | 'IMAGE' | 'VIDEO' })

const activeScene = computed(() => selectedNav.value === 'image' ? 'image' : selectedNav.value === 'video' ? 'video' : scene.value)
const unreadCount = computed(() => notifications.value.filter((item) => !item.read).length)
const pendingPosts = computed(() => audits.value.filter((post) => !approved.value.includes(post.postId) && !rejected.value.includes(post.postId)))
const visibleFeed = computed(() => {
  if (searchResults.value.length > 0) {
    return searchResults.value.map((post) => wrapPost(post, '搜索结果', 'SEARCH_FALLBACK'))
  }
  return feedItems.value
})
const imageItems = computed(() => visibleFeed.value.filter((item) => item.post.type === 'IMAGE'))
const videoItems = computed(() => visibleFeed.value.filter((item) => item.post.type === 'VIDEO'))

function wrapPost(post: Post, reason: string, algorithm: string): FeedItem {
  return { post, reason, score: 0, liked: false, favorited: false, algorithm, mediaType: post.type, features: {} }
}

function approve(id: number) {
  approved.value.push(id)
  auditMessage.value = `已通过内容 #${id}`
}

function reject(id: number) {
  rejected.value.push(id)
  auditMessage.value = `已驳回内容 #${id}`
}

async function loadFeed(cursor?: string) {
  loadingFeed.value = true
  try {
    const currentScene = activeScene.value
    const page = await fetchFeed(currentScene, cursor)
    feedItems.value = cursor ? [...feedItems.value, ...page.records] : page.records
    nextCursor.value = page.nextCursor
    hasMore.value = page.hasMore
    await recordExposures(page.records.map((item) => item.post.id), `${currentScene}_feed`)
  } finally {
    loadingFeed.value = false
  }
}

async function refreshFeed(nextScene = activeScene.value) {
  scene.value = nextScene as 'home' | 'image' | 'video'
  searchResults.value = []
  keyword.value = ''
  fallbackMessage.value = ''
  await loadFeed()
}

async function switchNav(key: string) {
  selectedNav.value = key
  if (key === 'feed' || key === 'image' || key === 'video') {
    await refreshFeed(key === 'feed' ? 'home' : key)
  }
}

async function loadMore() {
  if (hasMore.value) await loadFeed(nextCursor.value)
}

async function runSearch() {
  searchResults.value = await searchPosts(keyword.value)
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
      title: draft.value.title,
      content: draft.value.content,
      tags: draft.value.tags.split(',').map((tag) => tag.trim()).filter(Boolean),
      type: draft.value.type,
      mediaIds: uploadedMedia.value.map((item) => item.mediaId)
    })
    post.mediaAssets = uploadedMedia.value
    searchResults.value = []
    selectedNav.value = 'feed'
    feedItems.value = [wrapPost(post, '刚刚发布，已进入审核和事件队列', 'LOCAL_LINEAR_MODEL'), ...feedItems.value]
    publishMessage.value = `内容 #${post.id} 已提交，状态：${post.status}，已进入审核和事件队列`
    draft.value = { title: '', content: '', tags: 'Spring Cloud,Kafka', type: 'TEXT' }
    uploadedMedia.value = []
  } finally {
    publishing.value = false
  }
}

async function onFileChange(event: Event) {
  const files = (event.target as HTMLInputElement).files
  if (!files?.length) return
  const result = await uploadMedia(files[0])
  uploadedMedia.value.push(result)
  uploadMessage.value = `媒体 ${result.mediaId} 已上传，状态：${result.status}`
  if (result.contentType.startsWith('image/')) draft.value.type = 'IMAGE'
  if (result.contentType.startsWith('video/')) draft.value.type = 'VIDEO'
}

async function login(provider: 'github' | 'gitee') {
  oauthMessage.value = await demoOAuthLogin(provider)
}

async function follow(author: string) {
  const id = author.split('').reduce((sum, char) => sum + char.charCodeAt(0), 0)
  const result = await followUser(id)
  followMessage.value = result.duplicated ? `已关注作者 ${author}` : `关注作者 ${author} 成功`
}

onMounted(async () => {
  try {
    const [auditData, notificationData] = await Promise.all([fetchAudits(), fetchNotifications()])
    audits.value = auditData
    notifications.value = notificationData
  } catch {
    fallbackMessage.value = '接口暂不可用，已切换到本地演示数据。'
  }
  await loadFeed()
})
</script>

<template>
  <n-config-provider>
    <div class="app-shell">
      <aside class="sidebar">
        <div class="brand">
          <div class="brand-mark">D</div>
          <div>
            <strong>DevCircle</strong>
            <span>生产预备级社交内容平台</span>
          </div>
        </div>
        <nav class="nav-list" aria-label="主导航">
          <button v-for="item in navItems" :key="item.key" class="nav-item" :class="{ active: selectedNav === item.key }" @click="switchNav(item.key)">
            <n-icon :component="item.icon" />
            <span>{{ item.label }}</span>
          </button>
        </nav>
        <button class="write-button" @click="selectedNav = 'write'">
          <n-icon :component="Plus" />
          发布内容
        </button>
      </aside>

      <main class="workspace">
        <header class="topbar">
          <n-input v-model:value="keyword" size="large" placeholder="搜索问题、文章、图片、视频和标签" clearable @keyup.enter="runSearch">
            <template #prefix><n-icon :component="Search" /></template>
          </n-input>
          <div class="topbar-actions">
            <button class="oauth-button" @click="login('github')">GitHub 登录</button>
            <button class="oauth-button" @click="login('gitee')">Gitee 登录</button>
            <button class="icon-button" aria-label="通知"><n-icon :component="Bell" /><span v-if="unreadCount" class="notice-dot">{{ unreadCount }}</span></button>
            <div class="avatar">林</div>
          </div>
        </header>

        <section class="content-grid">
          <div class="feed-column">
            <div class="section-heading">
              <div>
                <h1>{{ selectedNav === 'image' ? '图片瀑布流' : selectedNav === 'video' ? '视频 Feed' : selectedNav === 'search' ? '搜索结果' : '推荐流' }}</h1>
                <p>本地轻量模型结合标签、热度、时效、关注关系、共同关注和曝光记录排序。</p>
              </div>
              <div class="heading-actions">
                <n-select v-model:value="scene" :options="sceneOptions" size="small" class="scene-select" @update:value="refreshFeed" />
                <n-button type="primary" secondary :loading="loadingFeed" @click="refreshFeed()">刷新推荐</n-button>
              </div>
            </div>

            <div v-if="fallbackMessage || oauthMessage || followMessage" class="audit-message">{{ fallbackMessage || oauthMessage || followMessage }}</div>

            <section class="post-card composer">
              <h2>发布内容</h2>
              <n-select v-model:value="draft.type" :options="[{ label: '文章', value: 'TEXT' }, { label: '图片', value: 'IMAGE' }, { label: '视频', value: 'VIDEO' }]" />
              <n-input v-model:value="draft.title" placeholder="标题" />
              <n-input v-model:value="draft.content" type="textarea" placeholder="正文，提交后写入帖子、媒体关联和 Outbox 事件" />
              <n-input v-model:value="draft.tags" placeholder="标签，用英文逗号分隔" />
              <label class="upload-box">
                <n-icon :component="Upload" />
                <span>上传图片或 mp4 视频</span>
                <input type="file" accept="image/png,image/jpeg,image/webp,video/mp4" @change="onFileChange" />
              </label>
              <div v-if="uploadMessage" class="audit-message">{{ uploadMessage }}</div>
              <n-button type="primary" :disabled="!draft.title || !draft.content" :loading="publishing" @click="publishPost">发布内容</n-button>
              <div v-if="publishMessage" class="audit-message">{{ publishMessage }}</div>
            </section>

            <div v-if="selectedNav === 'image'" class="masonry-grid">
              <article v-for="item in imageItems" :key="item.post.id" class="post-card media-card">
                <img v-for="asset in item.post.mediaAssets" :key="asset.mediaId" :src="asset.url" :alt="item.post.title" />
                <h2>{{ item.post.title }}</h2>
                <p>{{ item.reason }}</p>
                <button class="text-action" @click="follow(item.post.author)"><n-icon :component="UserPlus" /> 关注 {{ item.post.author }}</button>
              </article>
            </div>

            <div v-else-if="selectedNav === 'video'" class="video-feed">
              <article v-for="item in videoItems" :key="item.post.id" class="post-card video-card">
                <video v-if="item.post.mediaAssets[0]" :src="item.post.mediaAssets[0].url" controls playsinline muted />
                <div>
                  <h2>{{ item.post.title }}</h2>
                  <p>{{ item.post.excerpt }}</p>
                  <span class="reason">{{ item.reason }}</span>
                </div>
              </article>
            </div>

            <article v-else v-for="item in visibleFeed" :key="item.post.id" class="post-card">
              <div class="post-meta">
                <span>{{ item.post.author }}</span>
                <span>{{ item.post.type }}</span>
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
                <span>分数 {{ Math.round(item.score) }}</span>
                <button class="text-action" @click="toggleInteraction(item.post.id, 'like')"><n-icon :component="Heart" /> {{ item.liked ? '已赞' : '点赞' }}</button>
                <button class="text-action" @click="toggleInteraction(item.post.id, 'favorite')">{{ item.favorited ? '已收藏' : '收藏' }}</button>
                <button class="text-action" @click="follow(item.post.author)"><n-icon :component="UserPlus" /> 关注</button>
                <span class="reason">{{ item.reason }}</span>
              </footer>
            </article>
            <n-button v-if="hasMore && searchResults.length === 0" block secondary @click="loadMore">加载更多</n-button>
            <div v-if="!loadingFeed && visibleFeed.length === 0" class="empty-state post-empty">没有匹配的内容</div>
          </div>

          <aside class="right-rail">
            <section class="rail-panel">
              <h2>模型特征</h2>
              <div class="trend-list"><span>标签匹配</span><span>热度</span><span>新鲜度</span><span>关注作者</span><span>共同关注</span></div>
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
              <div v-if="auditMessage" class="audit-message">{{ auditMessage }}</div>
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
