<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { LoaderCircle, Search } from 'lucide-vue-next'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import PostCard from '../components/PostCard.vue'
import StatusPanel from '../components/StatusPanel.vue'
import { fetchPostsCursor, searchPostsCursor } from '../api/client'
import type { Post, Theme } from '../api/types'

const route = useRoute(); const router = useRouter()
const posts = ref<Post[]>([]); const keyword = ref(typeof route.query.q === 'string' ? route.query.q : '')
const theme = ref<Theme | ''>(typeof route.query.theme === 'string' ? route.query.theme as Theme : '')
const difficulty = ref(typeof route.query.difficulty === 'string' ? route.query.difficulty : '')
const contentKind = ref(typeof route.query.contentKind === 'string' ? route.query.contentKind : '')
const cursor = ref(''); const hasMore = ref(false); const loading = ref(false); const loadingMore = ref(false); const error = ref('')

async function load(reset = true, restoreCursor = '') {
  if (reset) { loading.value = true; posts.value = []; cursor.value = ''; error.value = '' } else loadingMore.value = true
  try {
    let nextCursor = reset ? '' : cursor.value
    let result: { records: Post[]; nextCursor: string; hasMore: boolean }
    do {
      if (keyword.value.trim()) {
        result = await searchPostsCursor(keyword.value.trim(), nextCursor, { theme: theme.value || undefined, contentKind: contentKind.value || undefined, difficulty: difficulty.value || undefined })
      } else {
        result = await fetchPostsCursor(nextCursor, { theme: theme.value || undefined, contentKind: contentKind.value || undefined, difficulty: difficulty.value || undefined })
      }
      posts.value = reset && nextCursor === '' ? result.records : [...posts.value, ...result.records]
      nextCursor = result.nextCursor
      cursor.value = result.nextCursor; hasMore.value = result.hasMore
      if (!restoreCursor || nextCursor === restoreCursor || !result.hasMore) break
    } while (true)
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '发现内容暂时不可用' }
  finally { loading.value = false; loadingMore.value = false }
}

async function submit() {
  await router.replace({ query: { ...(keyword.value.trim() && { q: keyword.value.trim() }), ...(theme.value && { theme: theme.value }), ...(contentKind.value && { contentKind: contentKind.value }), ...(difficulty.value && { difficulty: difficulty.value }) } })
  history.replaceState({ ...history.state, discoverCursor: '', discoverScroll: 0 }, '')
  await load(true)
}
function saveScroll() { history.replaceState({ ...history.state, discoverCursor: cursor.value, discoverScroll: window.scrollY }, '') }
onBeforeRouteLeave(() => saveScroll())
onMounted(async () => {
  const restoreCursor = typeof history.state?.discoverCursor === 'string' ? history.state.discoverCursor : ''
  await load(true, restoreCursor)
  requestAnimationFrame(() => window.scrollTo({ top: Number(history.state?.discoverScroll ?? 0), behavior: 'auto' }))
})
onBeforeUnmount(saveScroll)
</script>

<template>
  <div class="page-stack"><section class="page-intro"><span class="eyebrow">DISCOVERY / 发现</span><h1>在不同轨道，找到下一篇。</h1><p>按主题、难度或关键词寻找案件、设定和观测记录。</p></section>
    <form class="discover-tools discover-tools-wide" @submit.prevent="submit"><Search :size="18" /><label class="sr-only" for="discover-input">搜索内容</label><input id="discover-input" v-model="keyword" placeholder="搜索标题、作者或线索" /><select v-model="theme" aria-label="按主题筛选"><option value="">全部主题</option><option value="DEDUCTION">推理馆</option><option value="SCI_FI">科幻馆</option><option value="COSMOS">宇宙档案</option></select><select v-model="contentKind" aria-label="按内容类型筛选"><option value="">全部类型</option><option value="CASE_FILE">案件</option><option value="PUZZLE">谜题</option><option value="FICTION">小说</option><option value="WORLDBUILDING">世界观</option><option value="COSMIC_ARTICLE">宇宙文章</option><option value="OBSERVATION_LOG">观测日志</option></select><select v-model="difficulty" aria-label="按难度筛选"><option value="">全部难度</option><option value="EASY">入门</option><option value="MEDIUM">进阶</option><option value="HARD">高难</option></select><button class="button button-primary" type="submit">搜索</button></form>
    <StatusPanel v-if="error && !posts.length" title="发现服务暂时离线" :detail="error" action-label="重试" @retry="load(true)" /><div v-else-if="loading" class="loading-line">正在扫描内容索引…</div><template v-else-if="posts.length"><div class="post-grid"><PostCard v-for="post in posts" :key="post.id" :post="post" /></div><button v-if="hasMore" class="button button-quiet load-more" :disabled="loadingMore" type="button" @click="load(false)"><LoaderCircle v-if="loadingMore" :size="16" />{{ loadingMore ? '正在加载…' : '加载更多' }}</button></template><section v-else class="empty-panel"><h2>没有找到匹配内容</h2><p>换一个关键词，或从三个主题频道开始探索。</p></section>
  </div>
</template>
