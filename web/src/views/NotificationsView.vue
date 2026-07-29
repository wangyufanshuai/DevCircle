<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Bell, Check, CheckCheck, Filter } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { fetchNotifications, markAllNotificationsRead, markNotificationRead } from '../api/client'
import type { NotificationItem } from '../api/types'
import StatusPanel from '../components/StatusPanel.vue'

const router = useRouter(); const items = ref<NotificationItem[]>([]); const error = ref(''); const loading = ref(true); const type = ref('')
const unread = computed(() => items.value.filter(item => !item.read).length)
async function load() { loading.value = true; error.value = ''; try { items.value = await fetchNotifications(type.value) } catch (reason) { error.value = reason instanceof Error ? reason.message : '通知暂时不可用' } finally { loading.value = false } }
async function open(item: NotificationItem) {
  try { if (!item.read) { await markNotificationRead(item.id); item.read = true } }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '标记失败'; return }
  if (item.targetRoute) await router.push(item.targetRoute)
}
async function markAll() {
  try { await markAllNotificationsRead(); items.value.forEach(item => { item.read = true }) }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '全部已读操作失败' }
}
onMounted(load)
</script>

<template>
  <div class="page-stack narrow-page notification-page">
    <section class="page-intro notification-intro"><div><span class="eyebrow">SIGNAL INBOX</span><h1>通知</h1><p>审核、互动和系统消息都汇集在这里。</p></div><button v-if="unread" class="button button-quiet" type="button" @click="markAll"><CheckCheck :size="16" /> 全部已读</button></section>
    <div class="notification-tools"><Filter :size="16" /><label class="sr-only" for="notification-type">通知类型</label><select id="notification-type" v-model="type" @change="load"><option value="">全部信号</option><option value="AUDIT">审核</option><option value="INTERACTION">互动</option><option value="SYSTEM">系统</option></select><span>{{ unread }} 条未读</span></div>
    <StatusPanel v-if="error && !items.length" title="通知暂时无法加载" :detail="error" action-label="重试" @retry="load" />
    <div v-else-if="loading" class="loading-line">正在接收信号…</div>
    <section v-else-if="items.length" class="notification-list"><button v-for="item in items" :key="item.id" type="button" :class="['notification-row', { unread: !item.read }]" @click="open(item)"><span class="notification-icon"><Bell v-if="!item.read" :size="17" /><Check v-else :size="17" /></span><span><strong>{{ item.title }}</strong><p>{{ item.content }}</p><small>{{ item.createdAt }}<template v-if="item.targetRoute"> · 查看相关内容</template></small></span></button></section>
    <section v-else class="empty-panel"><Bell :size="22" /><h2>收件箱很安静</h2><p>有新的审核或互动消息时，会在这里出现。</p></section>
  </div>
</template>
