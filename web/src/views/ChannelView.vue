<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ArrowRight } from 'lucide-vue-next'
import { RouterLink, useRoute } from 'vue-router'
import PostCard from '../components/PostCard.vue'
import StatusPanel from '../components/StatusPanel.vue'
import { fetchPosts } from '../api/client'
import type { Post, Theme } from '../api/types'

const route = useRoute()
const posts = ref<Post[]>([])
const loading = ref(false)
const error = ref('')
const theme = computed(() => String(route.params.theme).toUpperCase() as Theme)
const configs: Record<Theme, { title: string; subtitle: string }> = {
  DEDUCTION: { title: '推理馆', subtitle: '把现场、证词和时间线放在同一张桌上。' },
  SCI_FI: { title: '科幻馆', subtitle: '阅读那些还没有被现实命名的世界。' },
  COSMOS: { title: '宇宙档案', subtitle: '观测远方，也重新观测我们自己。' }
}
async function load() {
  loading.value = true; error.value = ''
  try { posts.value = (await fetchPosts(1, theme.value)).records }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '频道暂时不可用' }
  finally { loading.value = false }
}
watch(theme, load)
onMounted(load)
</script>

<template>
  <div class="page-stack">
    <section class="channel-hero" :class="`channel-${theme.toLowerCase()}`">
      <div><span class="eyebrow">THEME CHANNEL</span><h1>{{ configs[theme]?.title }}</h1><p>{{ configs[theme]?.subtitle }}</p><RouterLink class="button button-primary" to="/discover">探索频道 <ArrowRight :size="16" /></RouterLink></div>
    </section>
    <section class="section-heading"><div><span class="eyebrow">LATEST SIGNALS</span><h2>最近更新</h2></div><span class="muted">每次加载 20 条内容</span></section>
    <StatusPanel v-if="error" title="频道暂时无法加载" :detail="error" action-label="重试" @retry="load" />
    <div v-else-if="loading" class="loading-line">正在整理档案…</div>
    <div v-else-if="posts.length" class="post-grid"><PostCard v-for="post in posts" :key="post.id" :post="post" /></div>
    <section v-else class="empty-panel"><h2>这个频道还在等待新的信号</h2><p>成为第一个提交内容的人。</p><RouterLink class="button button-primary" to="/write">开始创作</RouterLink></section>
  </div>
</template>
