<script setup lang="ts">
import { NIcon, NTag } from 'naive-ui'
import { ArrowRight, BookOpen, Orbit, RadioTower, Search, Sparkles, Trophy } from 'lucide-vue-next'
import type { FeedItem, LeaderboardEntry } from '../api/types'
import { difficultyLabel, kindLabel, themeClass, themeLabel, topByTheme } from '../view-model'

const props = defineProps<{
  items: FeedItem[]
  leaderboard: LeaderboardEntry[]
}>()

const emit = defineEmits<{
  open: [item: FeedItem]
  navigate: [key: string]
}>()
</script>

<template>
  <section class="portal-hero">
    <div class="hero-copy">
      <p class="eyebrow">MysticVerse v1.4 / cinematic themed portal</p>
      <h1>推理、科幻与宇宙档案的沉浸式内容宇宙</h1>
      <p>从案件线索板进入推理，从星舰资料库进入科幻，从观测台进入宇宙。首页不再是普通社交流，而是三主题内容平台的主控大厅。</p>
      <div class="hero-actions">
        <button @click="emit('navigate', 'deduction')"><n-icon :component="Search" />进入推理馆</button>
        <button @click="emit('navigate', 'sci_fi')"><n-icon :component="Sparkles" />查看科幻馆</button>
        <button @click="emit('navigate', 'cosmos')"><n-icon :component="Orbit" />打开宇宙档案</button>
      </div>
    </div>
    <div class="hero-feature" v-if="items[0]" @click="emit('open', items[0])">
      <span>今日主推</span>
      <strong>{{ items[0].post.title }}</strong>
      <p>{{ items[0].reason }}</p>
      <small>{{ themeLabel(items[0].post.theme) }} / {{ kindLabel(items[0].post.contentKind) }} / {{ difficultyLabel(items[0].post.difficulty) }}</small>
    </div>
  </section>

  <section class="theme-entry-grid">
    <button class="theme-entry deduction-entry" @click="emit('navigate', 'deduction')">
      <span>推理馆</span>
      <strong>案件档案室</strong>
      <small>100 个可解谜案件、线索板、嫌疑人和积分排行</small>
    </button>
    <button class="theme-entry scifi-entry" @click="emit('navigate', 'sci_fi')">
      <span>科幻馆</span>
      <strong>星舰资料库</strong>
      <small>世界观、技术设定、派系档案和短篇连载</small>
    </button>
    <button class="theme-entry cosmos-entry" @click="emit('navigate', 'cosmos')">
      <span>宇宙档案</span>
      <strong>深空观测台</strong>
      <small>天体档案、任务简报、观测日志和科普解释</small>
    </button>
  </section>

  <section class="home-mosaic">
    <div class="feature-column">
      <div class="section-head compact">
        <div>
          <p class="eyebrow">Editor's desk</p>
          <h2>编辑精选</h2>
        </div>
        <button class="text-link" @click="emit('navigate', 'mixed')">全部内容 <n-icon :component="ArrowRight" /></button>
      </div>
      <article v-for="item in items.slice(0, 6)" :key="item.post.id" class="editor-card" :class="themeClass(item.post.theme)" @click="emit('open', item)">
        <div>
          <n-tag size="small">{{ themeLabel(item.post.theme) }}</n-tag>
          <n-tag size="small" :bordered="false">{{ kindLabel(item.post.contentKind) }}</n-tag>
        </div>
        <h3>{{ item.post.title }}</h3>
        <p>{{ item.post.excerpt }}</p>
        <footer>{{ item.reason }} / {{ item.post.views.toLocaleString() }} 阅读</footer>
      </article>
    </div>

    <aside class="portal-rail">
      <section class="rail-card noir">
        <div class="rail-title"><n-icon :component="Trophy" /><strong>解谜榜</strong></div>
        <div v-for="entry in leaderboard.slice(0, 5)" :key="entry.userId" class="leader-row">
          <span>{{ entry.username }}</span>
          <strong>{{ entry.totalPoints }}</strong>
          <small>{{ entry.correctCount }} 题正确</small>
        </div>
      </section>
      <section class="rail-card">
        <div class="rail-title"><n-icon :component="BookOpen" /><strong>科幻连载</strong></div>
        <button v-for="item in topByTheme(items, 'SCI_FI', 4)" :key="item.post.id" class="mini-link" @click="emit('open', item)">
          {{ item.post.title }}
        </button>
      </section>
      <section class="rail-card observatory">
        <div class="rail-title"><n-icon :component="RadioTower" /><strong>宇宙热点</strong></div>
        <button v-for="item in topByTheme(items, 'COSMOS', 4)" :key="item.post.id" class="mini-link" @click="emit('open', item)">
          {{ item.post.title }}
        </button>
      </section>
    </aside>
  </section>
</template>
