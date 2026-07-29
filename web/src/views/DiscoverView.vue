<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { Search } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import PostCard from '../components/PostCard.vue'
import StatusPanel from '../components/StatusPanel.vue'
import { fetchPosts, searchPosts } from '../api/client'
import type { Post, Theme } from '../api/types'

const route = useRoute(); const router = useRouter(); const posts = ref<Post[]>([]); const keyword = ref(typeof route.query.q === 'string' ? route.query.q : ''); const theme = ref<Theme | ''>(''); const loading = ref(false); const error = ref('')
async function load() { loading.value = true; error.value = ''; try { posts.value = keyword.value.trim() ? (await searchPosts(keyword.value, theme.value || undefined)).records : (await fetchPosts(1, theme.value || undefined)).records } catch (reason) { error.value = reason instanceof Error ? reason.message : '发现内容暂时不可用' } finally { loading.value = false } }
async function submit() { await router.replace({ query: keyword.value ? { q: keyword.value } : {} }); await load() }
watch(() => route.query.q, value => { keyword.value = typeof value === 'string' ? value : '' })
onMounted(load)
</script>

<template>
  <div class="page-stack"><section class="page-intro"><span class="eyebrow">DISCOVERY / 发现</span><h1>在不同轨道，找到下一篇。</h1><p>按主题、内容类型或关键词寻找案件、设定和观测记录。</p></section>
    <form class="discover-tools" @submit.prevent="submit"><Search :size="18" /><label class="sr-only" for="discover-input">搜索内容</label><input id="discover-input" v-model="keyword" placeholder="搜索标题、作者或线索" /><select v-model="theme" aria-label="按主题筛选"><option value="">全部主题</option><option value="DEDUCTION">推理馆</option><option value="SCI_FI">科幻馆</option><option value="COSMOS">宇宙档案</option></select><button class="button button-primary" type="submit">搜索</button></form>
    <StatusPanel v-if="error" title="发现服务暂时离线" :detail="error" action-label="重试" @retry="load" /><div v-else-if="loading" class="loading-line">正在扫描内容索引…</div><div v-else-if="posts.length" class="post-grid"><PostCard v-for="post in posts" :key="post.id" :post="post" /></div><section v-else class="empty-panel"><h2>没有找到匹配内容</h2><p>换一个关键词，或从三个主题频道开始探索。</p></section>
  </div>
</template>
