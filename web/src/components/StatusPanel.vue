<script setup lang="ts">
defineProps<{ title: string; detail?: string; actionLabel?: string }>()
const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <section class="status-panel" role="status">
    <span class="status-kicker">观测站暂时没有回应</span>
    <h2>{{ title }}</h2><p>{{ detail ?? '请稍后重试，内容不会被悄悄替换成演示数据。' }}</p>
    <button v-if="actionLabel" class="button button-primary" type="button" @click="emit('retry')">{{ actionLabel }}</button>
  </section>
</template>
