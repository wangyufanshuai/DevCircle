<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowRight, Play, Sparkles } from 'lucide-vue-next'
import { RouterLink } from 'vue-router'
import PostCard from '../components/PostCard.vue'
import StatusPanel from '../components/StatusPanel.vue'
import { currentSession, fetchDashboard, fetchFeed, interact, recordExposures } from '../api/client'
import type { Dashboard, FeedItem } from '../api/types'

const feed = ref<FeedItem[]>([])
const loading = ref(true)
const error = ref('')
const dashboard = ref<Dashboard | null>(null)
const dashboardError = ref('')
const dashboardLoading = ref(false)
const hero = computed(() => feed.value[0]?.post)
function normalizedTrackState(item: Dashboard['readingProgress'][number]) {
  return item.trackState ?? (item.completed ? 'COMPLETED' : 'IN_PROGRESS')
}
const activeReadingTracks = computed(() => dashboard.value?.readingProgress
  .filter(item => item.available !== false && normalizedTrackState(item) === 'IN_PROGRESS') ?? [])
const resolvedTrackCount = computed(() => dashboard.value?.readingProgress
  .filter(item => normalizedTrackState(item) !== 'IN_PROGRESS').length ?? 0)
const channels = [
  { key: 'DEDUCTION', title: '推理馆', subtitle: '案件、线索与可验证的推理' },
  { key: 'SCI_FI', title: '科幻馆', subtitle: '短篇、设定与未知世界' },
  { key: 'COSMOS', title: '宇宙档案', subtitle: '观测、科学与宇宙想象' }
]
async function load() {
  loading.value = true; error.value = ''
  try {
    const result = await fetchFeed('home')
    feed.value = result.records
    if (result.records.length) void recordExposures(result.records.map(item => item.post.id), 'home').catch(reason => console.warn('exposure logging failed', reason))
    await loadDashboard()
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '内容暂时不可用' } finally { loading.value = false }
}
async function loadDashboard() {
  dashboardError.value = ''
  if (!currentSession()) { dashboard.value = null; return }
  dashboardLoading.value = true
  try { dashboard.value = await fetchDashboard() }
  catch (reason) { dashboard.value = null; dashboardError.value = reason instanceof Error ? reason.message : '继续解谜暂时无法加载' }
  finally { dashboardLoading.value = false }
}
async function onInteract(item: FeedItem, type: 'like' | 'favorite') {
  try { await interact(item.post.id, type) } catch (reason) { error.value = reason instanceof Error ? reason.message : '互动暂时不可用' }
}
onMounted(load)
</script>

<template>
  <div class="page-stack home-page">
    <section class="hero-observatory">
      <div class="hero-copy">
        <span class="eyebrow">MYSTICVERSE / OBSERVATORY 07</span>
        <h1>把线索放回星图，<br /><em>让故事继续发光。</em></h1>
        <p>从一宗案件开始，穿过科幻设定与宇宙档案，读者和解谜玩家在这里共同确认每一条线索。</p>
        <div class="hero-actions"><RouterLink class="button button-primary" to="/channels/DEDUCTION"><Play :size="16" /> 进入推理馆</RouterLink><RouterLink class="text-button" to="/discover">浏览全部内容 <ArrowRight :size="16" /></RouterLink></div>
      </div>
      <div class="hero-signal"><span>今日观测</span><strong>{{ hero?.title ?? '等待第一束内容信号' }}</strong><small>{{ hero ? hero.excerpt : '登录后可保存阅读进度、收藏案件并参与解题。' }}</small></div>
    </section>

    <section class="channel-switcher" aria-label="主题频道">
      <RouterLink v-for="channel in channels" :key="channel.key" :to="`/channels/${channel.key}`" class="channel-tab"><span class="channel-dot" :class="`dot-${channel.key.toLowerCase()}`"></span><span><strong>{{ channel.title }}</strong><small>{{ channel.subtitle }}</small></span><ArrowRight :size="16" /></RouterLink>
    </section>

    <section v-if="activeReadingTracks.length" class="continue-orbit">
      <div class="section-heading"><div><span class="eyebrow">CONTINUE ORBIT</span><h2>继续上次的观测</h2><p>阅读进度已经保存，可以从离开的地方继续。</p></div><RouterLink class="text-button" to="/me">查看全部 <ArrowRight :size="16" /></RouterLink></div>
      <div class="orbit-row"><RouterLink v-for="item in activeReadingTracks.slice(0, 4)" :key="item.postId" :to="`/posts/${item.postId}`"><span>{{ item.theme }}</span><strong>{{ item.title }}</strong><small>阅读 {{ item.progressPercent }}%</small><i><b :style="{ width: `${item.progressPercent}%` }" /></i></RouterLink></div>
    </section>
    <section v-else-if="dashboardError || dashboardLoading" class="continue-orbit">
      <StatusPanel v-if="dashboardError" title="继续解谜暂时无法加载" :detail="dashboardError" action-label="重试状态" @retry="loadDashboard" />
      <div v-else class="loading-line" aria-live="polite">正在同步你的观测进度…</div>
    </section>
    <section v-else-if="dashboard" class="continue-orbit continue-orbit-empty">
      <div class="section-heading"><div><span class="eyebrow">CONTINUE ORBIT</span><h2>继续轨道已清空</h2><p>{{ resolvedTrackCount ? `已有 ${resolvedTrackCount} 条观测进入历史记录。` : '打开一篇内容后，阅读进度会出现在这里。' }}</p></div><RouterLink class="text-button" to="/discover">寻找下一篇 <ArrowRight :size="16" /></RouterLink></div>
    </section>

    <section class="section-heading"><div><span class="eyebrow">EDITOR'S DESK</span><h2>今日精选</h2><p>少一点噪声，多一点值得读完的内容。</p></div><RouterLink class="text-button" to="/discover">查看全部 <ArrowRight :size="16" /></RouterLink></section>
    <StatusPanel v-if="error" title="内容暂时无法加载" :detail="error" action-label="重新连接" @retry="load" />
    <div v-else-if="loading" class="loading-line" aria-live="polite">正在连接观测站…</div>
    <div v-else-if="feed.length" class="post-grid featured-grid"><PostCard v-for="item in feed.slice(0, 6)" :key="item.post.id" :post="item.post" :recommendation="item" @interact="onInteract(item, $event)" /></div>
    <section v-else class="empty-panel"><Sparkles :size="22" /><h3>今天还没有新的观测记录</h3><p>去发现页探索主题，或成为第一个提交案件的人。</p><RouterLink class="button button-primary" to="/write">开始创作</RouterLink></section>
  </div>
</template>
