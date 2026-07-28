<script setup lang="ts">
import { computed, ref } from 'vue'
import { NIcon, NTag } from 'naive-ui'
import { Bookmark, Eye, Heart, RadioTower, Search, Telescope, UserPlus } from 'lucide-vue-next'
import type { FeedItem } from '../api/types'
import { channelItems, difficultyLabel, kindLabel, themeClass, themeLabel } from '../view-model'

const props = defineProps<{
  scene: string
  items: FeedItem[]
  loading: boolean
  hasMore: boolean
}>()

const emit = defineEmits<{
  open: [item: FeedItem]
  like: [postId: number]
  favorite: [postId: number]
  follow: [author: string]
  refresh: []
  loadMore: []
}>()

const activeFilter = ref('全部')

const channelMeta = computed(() => {
  if (props.scene === 'deduction') {
    return {
      cls: 'deduction-channel',
      eyebrow: 'Case room',
      title: '推理馆',
      subtitle: '案件档案、逻辑谜题、证词矛盾和高难挑战集中展示。',
      filters: ['全部', '密室', '时间线', '证词矛盾', '物证误导', '密码谜题', '未解案件']
    }
  }
  if (props.scene === 'sci_fi') {
    return {
      cls: 'scifi-channel',
      eyebrow: 'Starship archive',
      title: '科幻馆',
      subtitle: '世界观、短篇、技术设定、派系档案和媒体内容组合成星舰资料库。',
      filters: ['全部', '世界观', '短篇', '技术设定', '文明档案', '视频', '图像']
    }
  }
  if (props.scene === 'cosmos') {
    return {
      cls: 'cosmos-channel',
      eyebrow: 'Observatory',
      title: '宇宙档案',
      subtitle: '天体档案、观测日志、深空任务和科普解释以观测台方式呈现。',
      filters: ['全部', '恒星', '星云', '黑洞', '行星', '探测任务', '观测日志']
    }
  }
  return {
    cls: 'mixed-channel',
    eyebrow: 'Mixed feed',
    title: '混合推荐',
    subtitle: '推理、科幻、宇宙三主题按热度、兴趣和新鲜度混合召回。',
    filters: ['全部', '编辑精选', '最新', '热榜', '高难', '媒体']
  }
})

const visible = computed(() => {
  const base = channelItems(props.items, props.scene)
  if (activeFilter.value === '全部') return base
  if (activeFilter.value === '视频') return base.filter((item) => item.post.type === 'VIDEO')
  if (activeFilter.value === '图像') return base.filter((item) => item.post.type === 'IMAGE')
  if (activeFilter.value === '高难') return base.filter((item) => item.post.difficulty === 'HARD')
  if (activeFilter.value === '未解案件') return base.filter((item) => item.post.solvable)
  return base.filter((item) => item.post.tags.some((tag) => tag.includes(activeFilter.value)) || kindLabel(item.post.contentKind).includes(activeFilter.value))
})

const heroItem = computed(() => visible.value[0])
</script>

<template>
  <section class="channel-shell" :class="channelMeta.cls">
    <header class="channel-hero">
      <div>
        <p class="eyebrow">{{ channelMeta.eyebrow }}</p>
        <h1>{{ channelMeta.title }}</h1>
        <p>{{ channelMeta.subtitle }}</p>
      </div>
      <article v-if="heroItem" class="channel-feature" @click="emit('open', heroItem)">
        <span>频道主推</span>
        <strong>{{ heroItem.post.title }}</strong>
        <small>{{ heroItem.reason }}</small>
      </article>
    </header>

    <nav class="channel-tabs" :aria-label="`${channelMeta.title}栏目`">
      <button v-for="filter in channelMeta.filters" :key="filter" :class="{ active: activeFilter === filter }" @click="activeFilter = filter">
        {{ filter }}
      </button>
      <button class="refresh-tab" :disabled="loading" @click="emit('refresh')">刷新推荐</button>
    </nav>

    <div v-if="props.scene === 'deduction'" class="case-board">
      <article v-for="item in visible.slice(0, 12)" :key="item.post.id" class="case-file" @click="emit('open', item)">
        <span class="case-id">CASE {{ item.post.id }}</span>
        <h3>{{ item.post.title }}</h3>
        <p>{{ item.post.excerpt }}</p>
        <div class="case-metrics">
          <span>{{ difficultyLabel(item.post.difficulty) }}</span>
          <span>{{ item.post.clues?.length ?? 0 }} 条线索</span>
          <span>{{ item.post.suspects?.length ?? 0 }} 名嫌疑人</span>
          <span>{{ item.post.solvedCount }} 人已解</span>
        </div>
        <footer><n-icon :component="Search" />开始推理</footer>
      </article>
    </div>

    <div v-else-if="props.scene === 'sci_fi'" class="scifi-grid">
      <article v-for="item in visible.slice(0, 12)" :key="item.post.id" class="scifi-card" @click="emit('open', item)">
        <div class="holo-media">
          <img v-if="item.post.mediaAssets[0]?.contentType.startsWith('image')" :src="item.post.mediaAssets[0].url" alt="" />
          <video v-else-if="item.post.mediaAssets[0]?.contentType.startsWith('video')" :src="item.post.mediaAssets[0].url" muted loop />
          <div v-else class="holo-placeholder"><n-icon :component="RadioTower" /></div>
        </div>
        <div>
          <span class="ship-code">{{ item.post.tags[2] ?? '星舰资料' }}</span>
          <h3>{{ item.post.title }}</h3>
          <p>{{ item.post.excerpt }}</p>
          <footer>{{ item.post.tags.slice(1, 4).join(' / ') }}</footer>
        </div>
      </article>
    </div>

    <div v-else-if="props.scene === 'cosmos'" class="cosmos-atlas">
      <article v-for="item in visible.slice(0, 12)" :key="item.post.id" class="atlas-card" @click="emit('open', item)">
        <span>{{ kindLabel(item.post.contentKind) }}</span>
        <h3>{{ item.post.title }}</h3>
        <p>{{ item.post.excerpt }}</p>
        <div class="orbit-line"></div>
        <footer><n-icon :component="Telescope" />{{ item.post.tags.slice(1, 4).join(' / ') }}</footer>
      </article>
    </div>

    <div v-else class="mixed-feed">
      <article v-for="item in visible.slice(0, 18)" :key="item.post.id" class="content-card" :class="themeClass(item.post.theme)" @click="emit('open', item)">
        <div class="card-meta">
          <n-tag size="small">{{ themeLabel(item.post.theme) }}</n-tag>
          <n-tag size="small" :bordered="false">{{ kindLabel(item.post.contentKind) }}</n-tag>
          <span>{{ difficultyLabel(item.post.difficulty) }}</span>
        </div>
        <h3>{{ item.post.title }}</h3>
        <p>{{ item.post.excerpt }}</p>
        <footer>
          <span>{{ item.reason }}</span>
          <span><n-icon :component="Eye" />{{ item.post.views.toLocaleString() }}</span>
        </footer>
      </article>
    </div>

    <section class="dense-list">
      <div class="section-head compact">
        <div>
          <p class="eyebrow">All candidates</p>
          <h2>频道候选池</h2>
        </div>
        <small>{{ visible.length }} 条内容</small>
      </div>
      <article v-for="item in visible.slice(12, 30)" :key="item.post.id" class="row-card" @click="emit('open', item)">
        <strong>{{ item.post.title }}</strong>
        <span>{{ themeLabel(item.post.theme) }} / {{ kindLabel(item.post.contentKind) }} / {{ difficultyLabel(item.post.difficulty) }}</span>
        <p>{{ item.reason }}</p>
        <div>
          <button @click.stop="emit('like', item.post.id)"><n-icon :component="Heart" />{{ item.post.likes }}</button>
          <button @click.stop="emit('favorite', item.post.id)"><n-icon :component="Bookmark" />收藏</button>
          <button @click.stop="emit('follow', item.post.author)"><n-icon :component="UserPlus" />{{ item.post.author }}</button>
        </div>
      </article>
      <button v-if="hasMore" class="load-more" :disabled="loading" @click="emit('loadMore')">加载更多</button>
    </section>
  </section>
</template>
