<script setup lang="ts">
import { Bookmark, Eye, Heart, MessageCircle } from 'lucide-vue-next'
import { RouterLink } from 'vue-router'
import type { FeedItem, Post } from '../api/types'

const props = defineProps<{ post: Post; recommendation?: FeedItem }>()
const emit = defineEmits<{ interact: [type: 'like' | 'favorite'] }>()
const themeName: Record<string, string> = { DEDUCTION: '推理馆', SCI_FI: '科幻馆', COSMOS: '宇宙档案' }
</script>

<template>
  <article class="post-card">
    <div class="post-card-image" :class="`theme-${post.theme.toLowerCase()}`" aria-hidden="true"><span>{{ themeName[post.theme] }}</span></div>
    <div class="post-card-body">
      <div class="eyebrow-row"><span class="eyebrow">{{ themeName[post.theme] }}</span><span>{{ post.difficulty }}</span></div>
      <h3><RouterLink :to="`/posts/${post.id}`">{{ post.title }}</RouterLink></h3>
      <p>{{ post.excerpt }}</p>
      <div class="post-meta"><span>{{ post.author }}</span><span>{{ post.contentKind }}</span><span v-if="recommendation">{{ recommendation.reason }}</span></div>
      <div class="post-actions">
        <button type="button" :aria-label="`喜欢 ${post.title}`" @click="emit('interact', 'like')"><Heart :size="15" /> {{ post.likes }}</button>
        <button type="button" :aria-label="`收藏 ${post.title}`" @click="emit('interact', 'favorite')"><Bookmark :size="15" /> 收藏</button>
        <span><MessageCircle :size="15" /> {{ post.comments }}</span><span><Eye :size="15" /> {{ post.views }}</span>
      </div>
    </div>
  </article>
</template>
