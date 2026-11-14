/**
 * router.ts — 路由表：/home、/draw、/collab、/board/:id（+ /login、/guess 占位）
 *
 * 信息架构见 docs/07 §4：
 *   /            → /home（未登录先去 /login）
 *   /login       → 登录 / 注册
 *   /home        → 三张入口卡
 *   /draw        → 画画：作品网格 + 全部/已归档/回收站
 *   /collab      → 联机：选板 / 新建 / 邀请
 *   /guess       → 你画我猜占位（V2）
 *   /board/:id   → 画布
 *
 * 旧链接兼容：/?board=xxx 与 /board（无 id）仍然跳到对应画布；/works 重定向到 /draw。
 */
import { createRouter, createWebHistory, type RouteLocation } from 'vue-router'
import { hasToken } from './auth/storage'

/** 读取旧版链接里的 ?board=（没有路径 id 时使用） */
function legacyBoardId(to: RouteLocation): string {
  const raw = to.query.board
  const value = Array.isArray(raw) ? raw[0] : raw
  return typeof value === 'string' && value.trim() ? value.trim() : ''
}

/** 根路径 / 与 /board（无 id）共用的跳转规则 */
function redirectFromRoot(to: RouteLocation) {
  const legacy = legacyBoardId(to)
  if (legacy) {
    return { path: `/board/${encodeURIComponent(legacy)}` }
  }
  return hasToken() ? { path: '/home' } : { path: '/login' }
}

const PAGE_TITLES: Record<string, string> = {
  login: '登录',
  home: '主页',
  draw: '画画',
  collab: '联机',
  guess: '你画我猜',
  board: '协同白板',
}

export const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    {
      path: '/',
      name: 'root',
      redirect: (to) => redirectFromRoot(to),
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('./views/LoginView.vue'),
    },
    {
      path: '/home',
      name: 'home',
      component: () => import('./views/HomeView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/draw',
      name: 'draw',
      component: () => import('./views/DrawView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/collab',
      name: 'collab',
      component: () => import('./views/CollabView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/guess',
      name: 'guess',
      component: () => import('./views/GuessView.vue'),
      meta: { requiresAuth: true },
    },
    {
      // 旧书签兼容：作品库已并入 /draw
      path: '/works',
      redirect: '/draw',
    },
    {
      // 兼容 /board?board=xxx 与 /board（无 id）
      path: '/board',
      name: 'board-index',
      redirect: (to) => redirectFromRoot(to),
    },
    {
      path: '/board/:id',
      name: 'board',
      component: () => import('./views/BoardView.vue'),
      // 画布页不强制登录：首次进入由 BoardView 自动建游客会话（docs/04 §3.4）
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      redirect: '/',
    },
  ],
})

router.beforeEach((to) => {
  // 未登录访问需要登录的页面：跳登录并记住去向；画布页由 BoardView 自动建游客
  if (to.meta.requiresAuth && !hasToken()) {
    return {
      path: '/login',
      query: { redirect: to.fullPath },
    }
  }
  return true
})

router.afterEach((to) => {
  const name = typeof to.name === 'string' ? to.name : ''
  const title = PAGE_TITLES[name] ?? '协同白板'
  document.title = `${title} · 协同白板`
})
