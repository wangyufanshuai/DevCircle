<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NConfigProvider, NIcon, NInput, NTag } from 'naive-ui'
import { Bell, Check, Compass, FileText, Plus, Search, Shield, X } from 'lucide-vue-next'
import { fetchAudits, fetchNotifications, fetchRecommendations } from './api/client'
import type { AuditItem, NotificationItem, RecommendedPost } from './api/types'

const navItems = [
  { key: 'feed', label: '推荐流', icon: Compass },
  { key: 'search', label: '搜索', icon: Search },
  { key: 'write', label: '写文章', icon: FileText },
  { key: 'notice', label: '通知', icon: Bell },
  { key: 'admin', label: '管理端', icon: Shield }
]

const recommendations = ref<RecommendedPost[]>([])
const audits = ref<AuditItem[]>([])
const notifications = ref<NotificationItem[]>([])
const keyword = ref('')
const selectedNav = ref('feed')
const approved = ref<number[]>([])
const rejected = ref<number[]>([])
const auditMessage = ref('')

const filteredPosts = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  const posts = recommendations.value.map((item) => ({ ...item.post, reason: item.reason }))
  if (!q) return posts
  return posts.filter((post) =>
    [post.title, post.excerpt, post.author, ...post.tags].some((value) => value.toLowerCase().includes(q))
  )
})

const pendingPosts = computed(() => audits.value.filter((post) => !approved.value.includes(post.postId) && !rejected.value.includes(post.postId)))

const unreadCount = computed(() => notifications.value.filter((item) => !item.read).length)

function approve(id: number) {
  approved.value.push(id)
  auditMessage.value = `已通过内容 #${id}`
}

function reject(id: number) {
  rejected.value.push(id)
  auditMessage.value = `已驳回内容 #${id}`
}

onMounted(async () => {
  const [recommendationData, auditData, notificationData] = await Promise.all([
    fetchRecommendations(),
    fetchAudits(),
    fetchNotifications()
  ])
  recommendations.value = recommendationData
  audits.value = auditData
  notifications.value = notificationData
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
            <span>技术社区</span>
          </div>
        </div>
        <nav class="nav-list" aria-label="主导航">
          <button
            v-for="item in navItems"
            :key="item.key"
            class="nav-item"
            :class="{ active: selectedNav === item.key }"
            @click="selectedNav = item.key"
          >
            <n-icon :component="item.icon" />
            <span>{{ item.label }}</span>
          </button>
        </nav>
        <button class="write-button">
          <n-icon :component="Plus" />
          写文章
        </button>
      </aside>

      <main class="workspace">
        <header class="topbar">
          <n-input v-model:value="keyword" size="large" placeholder="搜索问题、文章和标签" clearable>
            <template #prefix>
              <n-icon :component="Search" />
            </template>
          </n-input>
          <div class="topbar-actions">
            <button class="icon-button" aria-label="通知">
              <n-icon :component="Bell" />
              <span v-if="unreadCount" class="notice-dot">{{ unreadCount }}</span>
            </button>
            <div class="avatar">林</div>
          </div>
        </header>

        <section class="content-grid">
          <div class="feed-column">
            <div class="section-heading">
              <div>
                <h1>推荐流</h1>
                <p>按关注标签、实时热度和互动质量排序</p>
              </div>
              <n-button type="primary" secondary>刷新推荐</n-button>
            </div>

            <article v-for="post in filteredPosts" :key="post.id" class="post-card">
              <div class="post-meta">
                <span>{{ post.author }}</span>
                <span>{{ post.status === 'PENDING' ? '待审核' : '已发布' }}</span>
              </div>
              <h2>{{ post.title }}</h2>
              <p>{{ post.excerpt }}</p>
              <div class="tag-row">
                <n-tag v-for="tag in post.tags" :key="tag" size="small" round>{{ tag }}</n-tag>
              </div>
              <footer>
                <span>{{ post.likes }} 赞</span>
                <span>{{ post.comments }} 评论</span>
                <span>{{ post.views }} 浏览</span>
                <span class="reason">{{ post.reason }}</span>
              </footer>
            </article>
            <div v-if="filteredPosts.length === 0" class="empty-state post-empty">没有匹配的内容</div>
          </div>

          <aside class="right-rail">
            <section class="rail-panel">
              <h2>趋势标签</h2>
              <div class="trend-list">
                <span>Spring Cloud</span>
                <span>Kafka</span>
                <span>Elasticsearch</span>
                <span>Redis</span>
                <span>OAuth2</span>
              </div>
            </section>

            <section class="rail-panel moderation">
              <div class="panel-title">
                <h2>待审核</h2>
                <strong>{{ pendingPosts.length }}</strong>
              </div>
              <div v-for="post in pendingPosts" :key="post.postId" class="audit-row">
                <div>
                  <strong>{{ post.title }}</strong>
                  <span>{{ post.author }} 提交</span>
                </div>
                <div class="audit-actions">
                  <button class="approve" @click="approve(post.postId)" aria-label="通过">
                    <n-icon :component="Check" />
                  </button>
                  <button class="reject" @click="reject(post.postId)" aria-label="驳回">
                    <n-icon :component="X" />
                  </button>
                </div>
              </div>
              <div v-if="auditMessage" class="audit-message">{{ auditMessage }}</div>
              <div v-if="pendingPosts.length === 0" class="empty-state">审核队列已清空</div>
            </section>

            <section class="mobile-preview">
              <div class="phone">
                <div class="phone-search">搜索</div>
                <div class="phone-card"></div>
                <div class="phone-card short"></div>
                <div class="phone-tabs">
                  <span></span><span></span><span></span>
                </div>
              </div>
            </section>
          </aside>
        </section>
      </main>
    </div>
  </n-config-provider>
</template>
