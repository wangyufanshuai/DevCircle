<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Bell, Check, CheckCheck, Filter, LoaderCircle } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { fetchNotifications, fetchUnreadNotificationCount, markAllNotificationsRead, markNotificationRead } from '../api/client'
import type { NotificationItem } from '../api/types'
import StatusPanel from '../components/StatusPanel.vue'

const route = useRoute(); const router = useRouter(); const items = ref<NotificationItem[]>([]); const error = ref(''); const notice = ref(''); const loading = ref(true); const loadingMore = ref(false)
const type = ref(typeof route.query.type === 'string' ? route.query.type : ''); const cursor = ref(''); const hasMore = ref(false); const unreadCount = ref(0)
const unread = computed(() => unreadCount.value)
async function load(reset = true) {
  error.value = ''
  if (reset) { loading.value = true; items.value = []; cursor.value = ''; notice.value = '' } else loadingMore.value = true
  try {
    const [page, count] = await Promise.all([fetchNotifications(reset ? '' : cursor.value, type.value), reset ? fetchUnreadNotificationCount() : Promise.resolve(unreadCount.value)])
    items.value = reset ? page.records : [...items.value, ...page.records.filter(candidate => !items.value.some(item => item.id === candidate.id))]
    cursor.value = page.nextCursor; hasMore.value = page.hasMore; unreadCount.value = count
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '通知暂时不可用' }
  finally { loading.value = false; loadingMore.value = false }
}
async function applyType() { await router.replace({ query: type.value ? { type: type.value } : {} }); await load(true) }
async function open(item: NotificationItem) {
  notice.value = ''; error.value = ''
  const wasUnread = !item.read
  try { if (!item.read) { await markNotificationRead(item.id); item.read = true } }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '标记失败'; return }
  if (wasUnread) unreadCount.value = Math.max(0, unreadCount.value - 1)
  if (item.targetRoute?.startsWith('/') && !item.targetRoute.startsWith('//')) await router.push(item.targetRoute)
  else notice.value = '这条通知关联的内容已不可用。'
}
async function markAll() {
  error.value = ''
  try { await markAllNotificationsRead(); items.value.forEach(item => { item.read = true }); unreadCount.value = 0 }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '全部已读操作失败' }
}
onMounted(load)
</script>

<template>
  <div class="page-stack narrow-page notification-page">
    <section class="page-intro notification-intro"><div><span class="eyebrow">SIGNAL INBOX</span><h1>通知</h1><p>审核、互动和系统消息都汇集在这里。</p></div><button v-if="unread" class="button button-quiet" type="button" @click="markAll"><CheckCheck :size="16" /> 全部已读</button></section>
    <div class="notification-tools"><Filter :size="16" /><label class="sr-only" for="notification-type">通知类型</label><select id="notification-type" v-model="type" @change="applyType"><option value="">全部信号</option><option value="AUDIT">审核</option><option value="INTERACTION">互动</option><option value="SYSTEM">系统</option></select><span>{{ unread }} 条未读</span></div>
    <p v-if="notice" class="form-error" role="status">{{ notice }}</p>
    <p v-if="error && items.length" class="form-error" role="alert">{{ error }}</p>
    <StatusPanel v-if="error && !items.length" title="通知暂时无法加载" :detail="error" action-label="重试" @retry="load" />
    <div v-else-if="loading" class="loading-line">正在接收信号…</div>
    <template v-else-if="items.length"><section class="notification-list"><button v-for="item in items" :key="item.id" type="button" :class="['notification-row', { unread: !item.read }]" @click="open(item)"><span class="notification-icon"><Bell v-if="!item.read" :size="17" /><Check v-else :size="17" /></span><span><strong>{{ item.title }}</strong><p>{{ item.content }}</p><small>{{ item.createdAt }}<template v-if="item.targetRoute"> · 查看相关内容</template></small></span></button></section><button v-if="hasMore" class="button button-quiet load-more" type="button" :disabled="loadingMore" @click="load(false)"><LoaderCircle v-if="loadingMore" :size="16" />{{ loadingMore ? '正在加载…' : '加载更多通知' }}</button></template>
    <section v-else class="empty-panel"><Bell :size="22" /><h2>收件箱很安静</h2><p>有新的审核或互动消息时，会在这里出现。</p></section>
  </div>
</template>
