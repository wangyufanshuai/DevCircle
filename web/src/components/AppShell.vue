<script setup lang="ts">
import { computed, ref } from 'vue'
import { Bell, Compass, FilePenLine, LogIn, Search, Telescope, UserRound } from 'lucide-vue-next'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { currentSession, logout } from '../api/client'

const route = useRoute()
const router = useRouter()
const query = ref(typeof route.query.q === 'string' ? route.query.q : '')
const session = computed(() => currentSession())
const navItems = [
  { to: '/', label: '首页', icon: Compass },
  { to: '/discover', label: '发现', icon: Search },
  { to: '/channels/DEDUCTION', label: '推理馆', icon: Telescope },
  { to: '/channels/SCI_FI', label: '科幻馆', icon: Telescope },
  { to: '/channels/COSMOS', label: '宇宙档案', icon: Telescope }
]
const isActive = (to: string) => to === '/' ? route.path === '/' : route.path.startsWith(to)
async function search() {
  if (query.value.trim()) await router.push({ path: '/discover', query: { q: query.value.trim() } })
}
async function signOut() {
  try { await logout() } finally { await router.push('/') }
}
</script>

<template>
  <a class="skip-link" href="#main-content">跳到主要内容</a>
  <div class="app-shell">
    <header class="topbar">
      <RouterLink class="brand" to="/" aria-label="MysticVerse 首页">
        <span class="brand-mark">MV</span>
        <span><strong>MysticVerse</strong><small>DevCircle · 主题内容宇宙</small></span>
      </RouterLink>
      <form class="global-search" role="search" @submit.prevent="search">
        <Search :size="17" aria-hidden="true" />
        <label class="sr-only" for="global-search-input">搜索案件、科幻作品或宇宙档案</label>
        <input id="global-search-input" v-model="query" placeholder="搜索案件、科幻作品、宇宙档案" />
        <button type="submit">搜索</button>
      </form>
      <div class="top-actions">
        <RouterLink class="icon-link" to="/notifications" aria-label="通知"><Bell :size="19" /></RouterLink>
        <RouterLink v-if="session" class="profile-chip" to="/me"><UserRound :size="16" /> {{ session.displayName }}</RouterLink>
        <RouterLink v-else class="button button-quiet" to="/auth"><LogIn :size="16" /> 登录</RouterLink>
      </div>
    </header>

    <aside class="sidebar" aria-label="主导航">
      <nav class="nav-stack">
        <RouterLink v-for="item in navItems" :key="item.to" :to="item.to" class="nav-link" :class="{ active: isActive(item.to) }" :aria-current="isActive(item.to) ? 'page' : undefined">
          <component :is="item.icon" :size="17" aria-hidden="true" /> <span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar-note"><span>观测站 07</span><p>把每一条线索，放回它所属的星图。</p></div>
      <RouterLink class="button button-primary create-link" to="/write"><FilePenLine :size="16" /> 开始创作</RouterLink>
      <RouterLink v-if="session?.roles.includes('ADMIN')" class="nav-link admin-link" to="/admin">运营后台</RouterLink>
      <button v-if="session" class="logout-link" type="button" @click="signOut">退出登录</button>
    </aside>

    <main id="main-content" class="main-content"><slot /></main>

    <nav class="mobile-nav" aria-label="移动端导航">
      <RouterLink to="/" :class="{ active: isActive('/') }"><Compass :size="19" /><span>首页</span></RouterLink>
      <RouterLink to="/discover" :class="{ active: isActive('/discover') }"><Search :size="19" /><span>发现</span></RouterLink>
      <RouterLink to="/write"><FilePenLine :size="19" /><span>创作</span></RouterLink>
      <RouterLink to="/notifications"><Bell :size="19" /><span>通知</span></RouterLink>
      <RouterLink to="/me"><UserRound :size="19" /><span>我的</span></RouterLink>
    </nav>
  </div>
</template>
