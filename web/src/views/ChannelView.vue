<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowRight, LoaderCircle } from 'lucide-vue-next'
import { RouterLink, onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import PostCard from '../components/PostCard.vue'
import StatusPanel from '../components/StatusPanel.vue'
import { fetchPostsCursor } from '../api/client'
import type { Post, Theme } from '../api/types'

const route = useRoute(); const router = useRouter()
const posts = ref<Post[]>([]); const loading = ref(false); const loadingMore = ref(false); const error = ref('')
const cursor = ref(''); const hasMore = ref(false)
const theme = computed(() => String(route.params.theme).toUpperCase() as Theme)
const configs: Record<Theme, { title: string; subtitle: string }> = {
  DEDUCTION: { title: '推理馆', subtitle: '把现场、证词和时间线放在同一张桌上。' },
  SCI_FI: { title: '科幻馆', subtitle: '阅读那些还没有被现实命名的世界。' },
  COSMOS: { title: '宇宙档案', subtitle: '观测远方，也重新观测我们自己。' }
}

async function load(reset = true, restoreCursor = '') {
  if (reset) { loading.value = true; posts.value = []; cursor.value = ''; error.value = '' } else loadingMore.value = true
  try {
    let nextCursor = reset ? '' : cursor.value
    do {
      const result = await fetchPostsCursor(nextCursor, { theme: theme.value })
      posts.value = reset && nextCursor === '' ? result.records : [...posts.value, ...result.records]
      nextCursor = result.nextCursor
      cursor.value = result.nextCursor; hasMore.value = result.hasMore
      if (!restoreCursor || nextCursor === restoreCursor || !result.hasMore) break
    } while (true)
    await router.replace({ query: cursor.value ? { cursor: cursor.value } : {} })
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '频道暂时不可用' }
  finally { loading.value = false; loadingMore.value = false }
}

function saveScroll() { history.replaceState({ ...history.state, channelCursor: cursor.value, channelScroll: window.scrollY }, '') }
onBeforeRouteLeave(() => saveScroll())
watch(theme, () => {
  history.replaceState({ ...history.state, channelCursor: '', channelScroll: 0 }, '')
  void load(true)
})
onMounted(async () => {
  const restoreCursor = typeof history.state?.channelCursor === 'string' ? history.state.channelCursor : ''
  await load(true, restoreCursor)
  requestAnimationFrame(() => window.scrollTo({ top: Number(history.state?.channelScroll ?? 0), behavior: 'auto' }))
})
onBeforeUnmount(saveScroll)
</script>

<template>
  <div class="page-stack">
    <section class="channel-hero" :class="`channel-${theme.toLowerCase()}`"><div><span class="eyebrow">THEME CHANNEL</span><h1>{{ configs[theme]?.title }}</h1><p>{{ configs[theme]?.subtitle }}</p><RouterLink class="button button-primary" to="/discover">探索频道 <ArrowRight :size="16" /></RouterLink></div></section>
    <section class="section-heading"><div><span class="eyebrow">LATEST SIGNALS</span><h2>最近更新</h2></div><span class="muted">每次加载 20 条内容</span></section>
    <StatusPanel v-if="error && !posts.length" title="频道暂时无法加载" :detail="error" action-label="重试" @retry="load(true)" />
    <div v-else-if="loading" class="loading-line">正在整理档案…</div>
    <template v-else-if="posts.length"><div class="post-grid"><PostCard v-for="post in posts" :key="post.id" :post="post" /></div><button v-if="hasMore" class="button button-quiet load-more" :disabled="loadingMore" type="button" @click="load(false)"><LoaderCircle v-if="loadingMore" :size="16" />{{ loadingMore ? '正在加载…' : '加载更多' }}</button></template>
    <section v-else class="empty-panel"><h2>这个频道还在等待新的信号</h2><p>成为第一个提交内容的人。</p><RouterLink class="button button-primary" to="/write">开始创作</RouterLink></section>
  </div>
</template>
