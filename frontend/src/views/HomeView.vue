<script setup lang="ts">
/**
 * HomeView.vue — 主界面（docs/07 §4 / §4.1）
 *
 * 三张入口卡：画画 / 联机 / 你画我猜（V2 敬请期待）。
 * 「最近作品缩略图」来自 GET /api/boards；后端不可达时卡片照常可点，只给一条提示条，
 * 不出现白屏或整页错误态。
 */
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import AppHeader from '../components/AppHeader.vue'
import SettingsDrawer from '../components/SettingsDrawer.vue'
import { currentUser, isGuest } from '../auth/session'
import { listBoards, type BoardSummary } from '../works/boardsApi'

const settingsOpen = ref(false)

const user = currentUser()
const guestMode = isGuest()
const userName = computed(() => user?.nickname ?? '用户')

const recentMine = ref<BoardSummary[]>([])
const recentJoined = ref<BoardSummary[]>([])
const loading = ref(true)
const offline = ref(false)

function greeting(): string {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 12) return '早上好'
  if (hour < 18) return '下午好'
  return '晚上好'
}

/**
 * 拉两张卡的缩略图：我的作品 / 我参与的公开板。
 * 任一成功就算在线；都失败时展示提示条，卡片入口仍然可用。
 */
async function loadRecent(): Promise<void> {
  // 游客的作品库列表可能被后端拒绝，此时不该误报「无法连接服务器」
  if (guestMode) {
    loading.value = false
    offline.value = false
    return
  }
  loading.value = true
  const results = await Promise.allSettled([
    listBoards({ scope: 'mine', status: 'active', page: 1, size: 3 }),
    listBoards({ scope: 'joined', status: 'active', page: 1, size: 3 }),
  ])
  let successCount = 0
  if (results[0].status === 'fulfilled') {
    recentMine.value = results[0].value.items
    successCount += 1
  }
  if (results[1].status === 'fulfilled') {
    recentJoined.value = results[1].value.items
    successCount += 1
  }
  offline.value = successCount === 0
  loading.value = false
}

function handleSettings(): void {
  settingsOpen.value = true
}

onMounted(() => {
  void loadRecent()
})
</script>

<template>
  <div class="page-shell">
    <AppHeader
      title="协同白板"
      subtitle="画画 · 联机 · 你画我猜"
      @settings="handleSettings"
    />

    <main class="page-main home-main">
      <section class="home-hero">
        <div>
          <h2 class="home-hero__title">{{ greeting() }}，{{ userName }}</h2>
          <p class="home-hero__sub">
            选一个入口开始：作品库归档历史、联机协作，或者去画布继续上一笔。
          </p>
        </div>
        <div class="home-hero__side">
          <span v-if="guestMode" class="badge badge--guest">游客</span>
          <span v-if="guestMode" class="home-hero__hint">
            游客可进公开板协作；创建 / 存档 / 回滚需要注册账号
          </span>
          <RouterLink v-else-if="loading" class="home-hero__hint" to="/draw">加载最近作品…</RouterLink>
        </div>
      </section>

      <div v-if="offline" class="page-banner page-banner--warn" role="status">
        无法连接服务器，最近作品缩略图暂不可用；三个入口仍然可以打开。
        <button class="btn btn--xs" type="button" @click="loadRecent">重试</button>
      </div>

      <section class="home-cards" aria-label="功能入口">
        <RouterLink class="home-card" to="/draw">
          <div class="home-card__icon home-card__icon--draw" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
              <path d="M4 20l1.2-4.2L16.4 4.6a2 2 0 0 1 2.8 0l.6.6a2 2 0 0 1 0 2.8L8.2 18.8 4 20z" />
              <path d="M14.6 6.4l3 3" />
            </svg>
          </div>
          <div class="home-card__body">
            <div class="home-card__head">
              <h3 class="home-card__title">画画</h3>
              <span class="badge">作品库</span>
            </div>
            <p class="home-card__desc">
              我的作品网格、全部 / 已归档 / 回收站分段，以及版本历史与回滚。
            </p>
            <span class="home-card__cta">进入作品库 →</span>
          </div>
          <div class="home-card__thumbs" aria-hidden="true">
            <template v-if="recentMine.length > 0">
              <span v-for="board in recentMine" :key="board.id" class="home-thumb">
                <img v-if="board.thumbnail" :src="board.thumbnail" :alt="board.name" />
                <span v-else class="home-thumb__empty">画</span>
              </span>
            </template>
            <span v-else class="home-thumb home-thumb--empty">暂无作品</span>
          </div>
        </RouterLink>

        <RouterLink class="home-card" to="/collab">
          <div class="home-card__icon home-card__icon--collab" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
              <circle cx="9" cy="8" r="3" />
              <path d="M3.5 19a5.5 5.5 0 0 1 11 0" />
              <path d="M16 5.5a3 3 0 0 1 0 5.8" />
              <path d="M17 14.2A5 5 0 0 1 20.5 19" />
            </svg>
          </div>
          <div class="home-card__body">
            <div class="home-card__head">
              <h3 class="home-card__title">联机</h3>
              <span class="badge badge--editor">协作</span>
            </div>
            <p class="home-card__desc">
              选一块板进入房间、新建白板并复制邀请链接；归档作品可「复制后联机」。
            </p>
            <span class="home-card__cta">去联机 →</span>
          </div>
          <div class="home-card__thumbs" aria-hidden="true">
            <template v-if="recentJoined.length > 0">
              <span v-for="board in recentJoined" :key="board.id" class="home-thumb">
                <img v-if="board.thumbnail" :src="board.thumbnail" :alt="board.name" />
                <span v-else class="home-thumb__empty">画</span>
              </span>
            </template>
            <span v-else class="home-thumb home-thumb--empty">暂无协作</span>
          </div>
        </RouterLink>

        <RouterLink class="home-card home-card--soon" to="/guess">
          <div class="home-card__icon home-card__icon--game" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
              <rect x="2.5" y="7" width="19" height="10" rx="3" />
              <path d="M7 10.5v3M5.5 12h3" />
              <circle cx="16.5" cy="11" r="1" />
              <circle cx="18.8" cy="13.2" r="1" />
            </svg>
          </div>
          <div class="home-card__body">
            <div class="home-card__head">
              <h3 class="home-card__title">你画我猜</h3>
              <span class="badge badge--soon">敬请期待</span>
            </div>
            <p class="home-card__desc">
              基于同一块画布的房间游戏：轮流画、猜词计分。V2 上线，画布页不再挂游戏浮层。
            </p>
            <span class="home-card__cta">看看规划 →</span>
          </div>
          <div class="home-card__thumbs home-card__thumbs--soon" aria-hidden="true">
            <span class="home-thumb home-thumb--empty">V2</span>
          </div>
        </RouterLink>
      </section>

      <p class="home-tip">
        提示：设置抽屉里可以调远端光标的颜色 / 透明度 / 大小，只影响本机视图。
      </p>
    </main>

    <SettingsDrawer v-model:open="settingsOpen" />
  </div>
</template>
