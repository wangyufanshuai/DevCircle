<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { restoreSession } from '../api/client'

const route = useRoute()
const router = useRouter()
const error = ref('')
const oauthErrors: Record<string, string> = {
  OAUTH_ACCOUNT_CONFLICT: '这个邮箱已经注册。请使用邮箱密码登录；系统不会自动合并账号。',
  AUTHENTICATION_FAILED: 'GitHub 没有返回可验证的账号信息，请确认账号拥有已验证邮箱后重试。',
  OAUTH_NOT_CONFIGURED: 'GitHub 登录暂未开放，请使用邮箱密码登录。'
}

onMounted(async () => {
  const oauthError = typeof route.query.error === 'string' ? route.query.error : ''
  if (oauthError) {
    sessionStorage.removeItem('dc_post_auth_redirect')
    error.value = oauthErrors[oauthError] ?? 'GitHub 登录没有完成，请重新尝试。'
    return
  }
  try {
    const result = await restoreSession()
    if (!result) throw new Error('登录会话没有建立')
    const saved = sessionStorage.getItem('dc_post_auth_redirect')
    sessionStorage.removeItem('dc_post_auth_redirect')
    const destination = saved?.startsWith('/') && !saved.startsWith('//') && !saved.startsWith('/auth') ? saved : '/me'
    await router.replace(destination)
  } catch (reason) {
    sessionStorage.removeItem('dc_post_auth_redirect')
    error.value = reason instanceof Error ? reason.message : '登录回调失败'
  }
})
</script>
<template><section class="status-panel auth-callback"><span class="eyebrow">GITHUB CALLBACK</span><h1>{{ error ? '登录没有完成' : '正在建立安全会话…' }}</h1><p>{{ error || '不会把访问令牌放进地址栏或本地存储。' }}</p><RouterLink v-if="error" class="button button-primary" to="/auth">返回登录</RouterLink></section></template>
