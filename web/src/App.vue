<script setup lang="ts">
import { onMounted } from 'vue'
import AppShell from './components/AppShell.vue'
import { restoreSession } from './api/client'

onMounted(() => { void restoreSession() })
</script>

<template>
  <AppShell><router-view /></AppShell>
</template>
