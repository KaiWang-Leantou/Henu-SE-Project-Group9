<script setup lang="ts">
/**
 * AppHeader.vue — /home、/draw、/collab、/guess 共用的顶栏
 *
 * docs/07 §4.1：顶部展示昵称、退出与设置入口；设置抽屉由父页面渲染，
 * 这里只发 `settings` 事件（避免每个页面重复写齿轮按钮）。
 */
import { computed } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { currentUser, isGuest, isLoggedIn, logout } from '../auth/session'

defineProps<{
  /** 页面主标题 */
  title: string
  /** 一行说明 */
  subtitle?: string
  /** 可选返回入口（如 /home） */
  backTo?: string
  backLabel?: string
}>()

const emit = defineEmits<{
  (e: 'settings'): void
  (e: 'logout'): void
}>()

const router = useRouter()
const user = currentUser()
const guestMode = isGuest()
const loggedIn = isLoggedIn()
const userName = computed(() => user?.nickname ?? '用户')

const navItems = [
  { to: '/home', label: '主页' },
  { to: '/draw', label: '画画' },
  { to: '/collab', label: '联机' },
] as const

function handleLogout(): void {
  logout()
  emit('logout')
  void router.replace('/login')
}
</script>

<template>
  <header class="app-topbar">
    <div class="app-topbar__left">
      <RouterLink v-if="backTo" class="btn btn--ghost btn--xs app-back" :to="backTo">
        ← {{ backLabel ?? '返回' }}
      </RouterLink>

      <RouterLink class="brand app-brand" to="/home" aria-label="回到主页">
        <div class="brand__mark" aria-hidden="true">画</div>
        <div class="brand__text">
          <h1 class="brand__title">{{ title }}</h1>
          <p v-if="subtitle" class="brand__sub">{{ subtitle }}</p>
        </div>
      </RouterLink>
    </div>

    <nav class="app-nav" aria-label="主导航">
      <RouterLink
        v-for="item in navItems"
        :key="item.to"
        class="app-nav__link"
        :to="item.to"
        active-class="is-active"
      >
        {{ item.label }}
      </RouterLink>
    </nav>

    <div class="app-topbar__right">
      <span class="user-chip" :title="loggedIn ? userName : '未登录'">
        <span class="user-chip__name">{{ loggedIn ? userName : '未登录' }}</span>
        <span v-if="guestMode" class="badge badge--guest">游客</span>
      </span>

      <button class="icon-btn app-icon-btn" type="button" title="设置" aria-label="设置" @click="emit('settings')">
        ⚙
      </button>

      <button v-if="loggedIn" class="btn" type="button" @click="handleLogout">退出登录</button>
      <RouterLink v-else class="btn btn--primary" to="/login">登录 / 注册</RouterLink>
    </div>
  </header>
</template>
