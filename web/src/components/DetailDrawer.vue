<script setup lang="ts">
import { NButton, NIcon, NInput, NTag } from 'naive-ui'
import { X } from 'lucide-vue-next'
import type { Post } from '../api/types'
import { difficultyLabel, kindLabel, themeClass, themeLabel } from '../view-model'

defineProps<{
  post: Post
  related: Post[]
  answerText: string
  answerMessage: string
}>()

const emit = defineEmits<{
  close: []
  answer: [postId: number]
  openRelated: [post: Post]
  'update:answerText': [value: string]
}>()
</script>

<template>
  <section class="detail-drawer" :class="themeClass(post.theme)">
    <header class="detail-head">
      <div>
        <div class="card-meta">
          <n-tag>{{ themeLabel(post.theme) }}</n-tag>
          <n-tag :bordered="false">{{ kindLabel(post.contentKind) }}</n-tag>
          <span>{{ difficultyLabel(post.difficulty) }}</span>
        </div>
        <h2>{{ post.title }}</h2>
        <p>{{ post.excerpt }}</p>
      </div>
      <button class="icon-close" @click="emit('close')"><n-icon :component="X" /></button>
    </header>

    <div v-if="post.mediaAssets?.length" class="detail-media">
      <template v-for="asset in post.mediaAssets" :key="asset.mediaId">
        <img v-if="asset.contentType.startsWith('image')" :src="asset.url" alt="" />
        <video v-else-if="asset.contentType.startsWith('video')" :src="asset.url" controls />
      </template>
    </div>

    <div v-if="post.theme === 'DEDUCTION'" class="dossier-layout">
      <section class="evidence-board">
        <h3>线索板</h3>
        <ul><li v-for="clue in post.clues" :key="clue">{{ clue }}</li></ul>
      </section>
      <section class="suspect-board">
        <h3>嫌疑人</h3>
        <div class="suspect-grid">
          <span v-for="suspect in post.suspects" :key="suspect">{{ suspect }}</span>
        </div>
      </section>
      <section class="timeline-board">
        <h3>时间线</h3>
        <ol><li v-for="line in post.timeline" :key="line">{{ line }}</li></ol>
      </section>
      <section class="answer-panel">
        <h3>提交推理</h3>
        <p>{{ post.question }}</p>
        <n-input :value="answerText" type="textarea" :autosize="{ minRows: 3, maxRows: 5 }" @update:value="emit('update:answerText', $event)" />
        <n-button type="primary" @click="emit('answer', post.id)">提交推理</n-button>
        <p class="answer">{{ answerMessage }}</p>
      </section>
    </div>

    <div v-else-if="post.theme === 'SCI_FI'" class="lore-layout">
      <section v-for="item in post.worldbuilding" :key="item" class="lore-card">
        <span>WORLD NODE</span>
        <p>{{ item }}</p>
      </section>
    </div>

    <div v-else class="cosmic-layout">
      <section v-for="item in post.cosmicFacts" :key="item" class="cosmic-fact">
        <span>OBSERVATION</span>
        <p>{{ item }}</p>
      </section>
    </div>

    <section class="related-section">
      <div class="section-head compact">
        <div>
          <p class="eyebrow">Related</p>
          <h3>相关内容</h3>
        </div>
      </div>
      <div class="related-grid">
        <button v-for="item in related" :key="item.id" @click="emit('openRelated', item)">
          <strong>{{ item.title }}</strong>
          <span>{{ themeLabel(item.theme) }} / {{ difficultyLabel(item.difficulty) }}</span>
        </button>
      </div>
    </section>
  </section>
</template>
