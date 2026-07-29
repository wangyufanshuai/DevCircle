import { createRouter, createWebHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import DiscoverView from './views/DiscoverView.vue'
import ChannelView from './views/ChannelView.vue'
import PostDetailView from './views/PostDetailView.vue'
import WriteView from './views/WriteView.vue'
import NotificationsView from './views/NotificationsView.vue'
import ProfileView from './views/ProfileView.vue'
import AuthView from './views/AuthView.vue'
import AuthCallbackView from './views/AuthCallbackView.vue'
import AdminView from './views/AdminView.vue'

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: (to, _from, saved) => saved ?? { top: 0 },
  routes: [
    { path: '/', component: HomeView },
    { path: '/discover', component: DiscoverView },
    { path: '/channels/:theme', component: ChannelView },
    { path: '/posts/:id(\\d+)', component: PostDetailView },
    { path: '/write/:draftId(\\d+)?', component: WriteView, meta: { requiresAuth: true } },
    { path: '/notifications', component: NotificationsView, meta: { requiresAuth: true } },
    { path: '/me', component: ProfileView, meta: { requiresAuth: true } },
    { path: '/auth', component: AuthView },
    { path: '/auth/callback', component: AuthCallbackView },
    { path: '/admin', component: AdminView, meta: { requiresAdmin: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth) {
    // The backend remains the authority. The shell lets the page render and the API
    // client performs refresh, so an expired session can recover without a redirect loop.
  }
  return true
})

export default router
