<script setup lang="ts">
import { NButton, NInput, NSelect } from 'naive-ui'
import { contentKindOptions, difficultyOptions } from '../view-model'

defineProps<{
  draft: {
    title: string
    content: string
    tags: string
    type: 'TEXT' | 'IMAGE' | 'VIDEO'
    theme: 'DEDUCTION' | 'SCI_FI' | 'COSMOS'
    contentKind: 'CASE_FILE' | 'PUZZLE' | 'FICTION' | 'WORLDBUILDING' | 'COSMIC_ARTICLE' | 'OBSERVATION_LOG'
    difficulty: 'EASY' | 'MEDIUM' | 'HARD'
    solvable: boolean
    clues: string
    solution: string
  }
  publishing: boolean
}>()

const emit = defineEmits<{ publish: [] }>()
</script>

<template>
  <section class="workbench single">
    <div class="panel wide creator-panel">
      <p class="eyebrow">Creator desk</p>
      <h2>主题创作台</h2>
      <p>提交后进入审核与事件队列。推理内容可以填写线索和隐藏答案，科幻与宇宙内容可以作为设定、档案或观测日志发布。</p>
      <div class="form-grid">
        <label>标题<n-input v-model:value="draft.title" /></label>
        <label>标签<n-input v-model:value="draft.tags" placeholder="逗号分隔，例如 密室,时间线,钟楼" /></label>
        <label>主题<n-select v-model:value="draft.theme" :options="[
          { label: '推理', value: 'DEDUCTION' },
          { label: '科幻', value: 'SCI_FI' },
          { label: '宇宙', value: 'COSMOS' }
        ]" /></label>
        <label>内容类型<n-select v-model:value="draft.contentKind" :options="contentKindOptions" /></label>
        <label>难度<n-select v-model:value="draft.difficulty" :options="difficultyOptions" /></label>
        <label>媒介<n-select v-model:value="draft.type" :options="[
          { label: '文字', value: 'TEXT' },
          { label: '图片', value: 'IMAGE' },
          { label: '视频', value: 'VIDEO' }
        ]" /></label>
      </div>
      <label class="block-label">正文<n-input v-model:value="draft.content" type="textarea" :autosize="{ minRows: 5, maxRows: 8 }" /></label>
      <label class="block-label">推理线索<n-input v-model:value="draft.clues" type="textarea" :autosize="{ minRows: 3, maxRows: 5 }" /></label>
      <label class="block-label">隐藏答案 / 解析<n-input v-model:value="draft.solution" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" /></label>
      <n-button type="primary" size="large" :loading="publishing" @click="emit('publish')">提交审核与事件队列</n-button>
    </div>
  </section>
</template>
