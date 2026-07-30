<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Bell, BookOpen, Bookmark, FilePenLine, ShieldCheck, Trophy } from 'lucide-vue-next'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { fetchDashboard, logout, sessionState } from '../api/client'
import type { Dashboard } from '../api/types'
import StatusPanel from '../components/StatusPanel.vue'

const session = sessionState
const route = useRoute()
const router = useRouter()
const dashboard = ref<Dashboard | null>(null)
const loading = ref(true)
const error = ref('')
const logoutError = ref('')
const signingOut = ref(false)

async function load() {
  loading.value = true; error.value = ''
  try { dashboard.value = await fetchDashboard() }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '个人档案暂时无法加载' }
  finally { loading.value = false }
}
onMounted(load)

async function signOut() {
  signingOut.value = true; logoutError.value = ''
  try { await logout(); await router.replace('/') }
  catch (reason) { logoutError.value = reason instanceof Error ? reason.message : '退出登录失败，请重试' }
  finally { signingOut.value = false }
}
</script>

<template>
  <div class="page-stack profile-page">
    <p v-if="route.query.access === 'denied'" class="form-error" role="status">你的账号没有访问运营后台的权限。</p>
    <StatusPanel v-if="error && !dashboard" title="个人档案暂时无法加载" :detail="error" action-label="重试" @retry="load" />
    <div v-else-if="loading" class="loading-line">正在整理观测记录…</div>
    <template v-else-if="dashboard">
      <section class="profile-hero">
        <div class="profile-avatar">{{ dashboard.profile.username.slice(0, 1).toUpperCase() }}</div>
        <div><span class="eyebrow">OBSERVER PROFILE</span><h1>{{ dashboard.profile.username }}</h1><p>{{ dashboard.profile.bio || `观测者 #${dashboard.profile.id}` }}</p></div>
        <dl class="profile-counts"><div><dt>关注</dt><dd>{{ dashboard.profile.following }}</dd></div><div><dt>粉丝</dt><dd>{{ dashboard.profile.followers }}</dd></div><div><dt>收藏</dt><dd>{{ dashboard.favorites.length }}</dd></div></dl>
      </section>

      <section class="dashboard-stat-grid">
        <RouterLink to="/write"><FilePenLine :size="19" /><span>投稿记录</span><strong>{{ dashboard.submissions.length }}</strong></RouterLink>
        <RouterLink to="/notifications"><Bell :size="19" /><span>未读信号</span><strong>{{ dashboard.unreadNotifications }}</strong></RouterLink>
        <div><Trophy :size="19" /><span>解谜积分</span><strong>{{ dashboard.deductionStats.totalPoints }}</strong><small>答对 {{ dashboard.deductionStats.correctCount }} / 尝试 {{ dashboard.deductionStats.attempts }}</small></div>
        <div><Bookmark :size="19" /><span>收藏轨道</span><strong>{{ dashboard.favorites.length }}</strong></div>
      </section>

      <section>
        <div class="section-heading"><div><span class="eyebrow">CONTINUE ORBIT</span><h2>继续解谜轨道</h2></div><RouterLink class="text-button" to="/discover">发现更多</RouterLink></div>
        <div v-if="dashboard.readingProgress.length" class="progress-list">
          <RouterLink v-for="item in dashboard.readingProgress" :key="item.postId" :to="`/posts/${item.postId}`" class="progress-row"><BookOpen :size="18" /><span><strong>{{ item.title }}</strong><small>{{ item.theme }} · {{ item.completed ? '已完成' : `阅读 ${item.progressPercent}%` }}</small></span><div class="progress-track"><i :style="{ width: `${item.progressPercent}%` }" /></div></RouterLink>
        </div>
        <p v-else class="empty-copy muted">还没有保存的阅读轨道。</p>
      </section>

      <section>
        <div class="section-heading"><div><span class="eyebrow">SUBMISSIONS</span><h2>创作与审核</h2></div><RouterLink class="button button-quiet" to="/write">进入创作台</RouterLink></div>
        <div v-if="dashboard.submissions.length" class="submission-list"><RouterLink v-for="item in dashboard.submissions" :key="item.postId" :to="`/write/${item.postId}`"><span><strong>{{ item.title }}</strong><small>{{ item.reviewReason || '等待下一步处理' }}</small></span><em>{{ item.status }}</em></RouterLink></div>
        <p v-else class="empty-copy muted">还没有草稿或投稿。</p>
      </section>

      <RouterLink v-if="session?.roles.includes('ADMIN')" class="admin-dashboard-link" to="/admin"><ShieldCheck :size="19" /><span><strong>运营后台</strong><small>审核、风险和事件重放</small></span></RouterLink>
      <section class="profile-account-actions" aria-labelledby="account-actions-title">
        <div><h2 id="account-actions-title">账号安全</h2><p class="muted">退出后，本机内存中的访问令牌会立即清除。</p></div>
        <button class="button button-quiet" type="button" :disabled="signingOut" @click="signOut">{{ signingOut ? '正在退出…' : '退出登录' }}</button>
      </section>
      <p v-if="logoutError" class="form-error" role="alert">{{ logoutError }}</p>
    </template>
  </div>
</template>
