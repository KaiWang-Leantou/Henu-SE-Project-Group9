<script setup lang="ts">
/**
 * CollabView.vue — 联机：选板进房 / 新建 / 邀请链接（docs/07 §4.3）
 *
 * 列表来源：
 *   - 我的 active 板            GET /api/boards?scope=mine&status=active
 *   - 我的 archived 板（可复制） GET /api/boards?scope=mine&status=archived
 *   - 他人公开板（可空）         GET /api/boards?scope=all&status=active
 *
 * 归档板只读，所以「进入房间」只能看不能画；想继续画走「复制后联机」（docs/07 §2 duplicate）。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppHeader from '../components/AppHeader.vue'
import AppToast from '../components/AppToast.vue'
import SettingsDrawer from '../components/SettingsDrawer.vue'
import { useToast } from '../components/useToast'
import { describeApiError } from '../auth/api'
import { isGuest } from '../auth/session'
import { boardInviteUrl, copyText } from '../utils/clipboard'
import { formatDateTime, formatRelativeTime } from '../utils/time'
import {
  createBoard,
  duplicateBoard,
  listBoards,
  type BoardRole,
  type BoardSummary,
} from '../works/boardsApi'

type LoadState = 'loading' | 'ready' | 'error'

/** 同一块板既在 mine 又在 joined 时保留权限更高的那条 */
const ROLE_PRIORITY: Record<BoardRole, number> = { owner: 3, editor: 2, viewer: 1 }

const router = useRouter()
const guestMode = isGuest()
const { toast, showToast } = useToast()

const state = ref<LoadState>('loading')
const errorText = ref('')
const refreshing = ref(false)

const mineActive = ref<BoardSummary[]>([])
const mineArchived = ref<BoardSummary[]>([])
const publicBoards = ref<BoardSummary[]>([])

const settingsOpen = ref(false)
const busyId = ref('')

const createOpen = ref(false)
const createName = ref('')
const createError = ref('')
const creating = ref(false)
const createInput = ref<HTMLInputElement | null>(null)

const inviteTarget = ref<BoardSummary | null>(null)

let requestSeq = 0

const inviteUrl = computed(() =>
  inviteTarget.value ? boardInviteUrl(inviteTarget.value.id) : '',
)

/** 他人公开板：scope=all 会包含自己的板，这里剔掉 */
const othersPublic = computed(() => {
  const ownIds = new Set(
    [...mineActive.value, ...mineArchived.value].map((board) => board.id),
  )
  return publicBoards.value.filter((board) => !ownIds.has(board.id))
})

const hasAnyBoard = computed(
  () => mineActive.value.length + mineArchived.value.length + othersPublic.value.length > 0,
)

async function loadBoards(options: { silent?: boolean } = {}): Promise<void> {
  const seq = ++requestSeq
  if (!options.silent) {
    state.value = 'loading'
    errorText.value = ''
  } else {
    refreshing.value = true
  }

  // 游客看不到「我的板」，只拉别人公开的白板
  const requests = guestMode
    ? [listBoards({ scope: 'all', status: 'active', page: 1, size: 50 })]
    : [
        listBoards({ scope: 'mine', status: 'active', page: 1, size: 50 }),
        listBoards({ scope: 'joined', status: 'active', page: 1, size: 50 }),
        listBoards({ scope: 'mine', status: 'archived', page: 1, size: 50 }),
        listBoards({ scope: 'all', status: 'active', page: 1, size: 50 }),
      ]
  const results = await Promise.allSettled(requests)
  if (seq !== requestSeq) return

  let successCount = 0
  let lastError: unknown = null
  const [mineResult, joinedResult, archivedResult, publicResult] = guestMode
    ? [null, null, null, results[0]]
    : results

  // docs/07 §4.3 的「我参与 / 我的 active 板」：mine + joined 去重（owner 优先）
  const activeItems: BoardSummary[] = []
  if (mineResult?.status === 'fulfilled') {
    activeItems.push(...mineResult.value.items)
    successCount += 1
  } else if (mineResult) {
    lastError = mineResult.reason
  }
  if (joinedResult?.status === 'fulfilled') {
    activeItems.push(...joinedResult.value.items)
    successCount += 1
  } else if (joinedResult) {
    lastError = lastError ?? joinedResult.reason
  }
  const merged = new Map<string, BoardSummary>()
  for (const item of activeItems) {
    const existing = merged.get(item.id)
    if (!existing || ROLE_PRIORITY[item.role] > ROLE_PRIORITY[existing.role]) {
      merged.set(item.id, item)
    }
  }
  mineActive.value = Array.from(merged.values())

  if (archivedResult?.status === 'fulfilled') {
    mineArchived.value = archivedResult.value.items
    successCount += 1
  } else if (archivedResult) {
    lastError = lastError ?? archivedResult.reason
  }
  if (publicResult?.status === 'fulfilled') {
    publicBoards.value = publicResult.value.items
    successCount += 1
  } else if (publicResult) {
    lastError = lastError ?? publicResult.reason
  }

  if (successCount === 0) {
    errorText.value = describeApiError(lastError)
    state.value = 'error'
  } else {
    state.value = 'ready'
    errorText.value = ''
  }
  refreshing.value = false
}

// ---------------------------------------------------------------------------
// 进房 / 复制后联机
// ---------------------------------------------------------------------------

function enterRoom(board: BoardSummary): void {
  void router.push(`/board/${board.id}`)
}

/**
 * 「复制后联机」：归档板只读，先 duplicate 成自己的新 ACTIVE 板，再直接进房并弹邀请链接。
 */
async function duplicateAndJoin(board: BoardSummary): Promise<void> {
  if (guestMode) {
    showToast('游客不能复制作品，请先注册或登录', 'error')
    return
  }
  if (busyId.value === board.id) return
  busyId.value = board.id
  try {
    const copy = await duplicateBoard(board.id)
    showToast('已复制为新的作品，正在进入房间…')
    await router.push(`/board/${copy.id}?invite=1`)
  } catch (error) {
    showToast(describeApiError(error), 'error')
  } finally {
    busyId.value = ''
  }
}

/** 新建 → 直接进房，并由 BoardView 用 ?invite=1 弹出邀请链接 */
async function submitCreate(): Promise<void> {
  if (creating.value) return
  if (guestMode) {
    showToast('登录后才能创建白板，请先注册或登录', 'error')
    return
  }
  const name = createName.value.trim()
  if (!name) {
    createError.value = '请输入白板名称'
    return
  }
  if (Array.from(name).length > 50) {
    createError.value = '名称最多 50 个字符'
    return
  }

  creating.value = true
  createError.value = ''
  try {
    const created = await createBoard(name)
    if (!created?.id) throw new Error('服务器未返回白板 id')
    createOpen.value = false
    await router.push(`/board/${created.id}?invite=1`)
  } catch (error) {
    createError.value = describeApiError(error)
  } finally {
    creating.value = false
  }
}

function openCreate(): void {
  if (guestMode) {
    showToast('登录后才能创建白板，请先注册或登录', 'error')
    return
  }
  createName.value = ''
  createError.value = ''
  createOpen.value = true
  void nextTick(() => createInput.value?.focus())
}

function closeCreate(): void {
  if (creating.value) return
  createOpen.value = false
}

// ---------------------------------------------------------------------------
// 邀请链接
// ---------------------------------------------------------------------------

function openInvite(board: BoardSummary): void {
  inviteTarget.value = board
}

function closeInvite(): void {
  inviteTarget.value = null
}

async function copyInviteFromModal(): Promise<void> {
  const ok = await copyText(inviteUrl.value)
  showToast(ok ? '邀请链接已复制' : '复制失败，请手动选中链接复制', ok ? 'info' : 'error')
}

/** 聚焦只读输入框时自动全选，方便手动复制 */
function selectInvite(event: FocusEvent): void {
  const target = event.target
  if (target instanceof HTMLInputElement) target.select()
}

// ---------------------------------------------------------------------------
// 其它
// ---------------------------------------------------------------------------

function handleSettings(): void {
  settingsOpen.value = true
}

function handleKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape') return
  if (createOpen.value) {
    closeCreate()
    return
  }
  if (inviteTarget.value) closeInvite()
}

onMounted(() => {
  void loadBoards()
  window.addEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown)
})
</script>

<template>
  <div class="page-shell">
    <AppHeader
      title="联机"
      subtitle="选一块板进房间，或新建白板并分享邀请链接"
      back-to="/home"
      back-label="主页"
      @settings="handleSettings"
    />

    <main class="page-main">
      <div class="collab-toolbar">
        <div>
          <h2 class="section-title">联机白板</h2>
          <p class="section-sub">
            进入房间后，右上角可以复制邀请链接；归档作品只读，请用「复制后联机」。
          </p>
        </div>
        <div class="collab-toolbar__actions">
          <button class="btn" type="button" :disabled="refreshing" @click="loadBoards({ silent: true })">
            {{ refreshing ? '刷新中…' : '刷新' }}
          </button>
          <button class="btn btn--primary" type="button" :disabled="guestMode" @click="openCreate">
            + 新建白板
          </button>
        </div>
      </div>

      <p v-if="guestMode" class="page-banner page-banner--info">
        当前是游客身份：可以进入公开板协作，但创建 / 复制 / 存档需要注册账号。
      </p>

      <div v-if="state === 'loading'" class="collab-grid" aria-label="白板加载中">
        <div v-for="index in 3" :key="index" class="skeleton-card" aria-hidden="true">
          <div class="skeleton-card__thumb" />
          <div class="skeleton-card__body">
            <div class="skeleton-line skeleton-line--title" />
            <div class="skeleton-line skeleton-line--short" />
          </div>
        </div>
      </div>

      <section v-else-if="state === 'error'" class="works-state">
        <div class="works-state__icon works-state__icon--error" aria-hidden="true">!</div>
        <h2>白板列表加载失败</h2>
        <p>{{ errorText || '无法连接服务器，请确认后端已启动' }}</p>
        <div class="works-state__actions">
          <button class="btn btn--primary" type="button" @click="loadBoards()">重新加载</button>
          <button class="btn" type="button" @click="router.push('/home')">回主页</button>
        </div>
      </section>

      <section v-else-if="!hasAnyBoard" class="works-state">
        <div class="works-state__icon" aria-hidden="true">+</div>
        <h2>还没有可联机的白板</h2>
        <p>新建一块白板即可获得邀请链接，拿到链接的人都能进入房间协作。</p>
        <div class="works-state__actions">
          <button class="btn btn--primary" type="button" :disabled="guestMode" @click="openCreate">
            新建白板
          </button>
        </div>
      </section>

      <template v-else>
        <section v-if="!guestMode" class="collab-section">
          <h3 class="collab-section__title">
            我的 / 我参与的 <span class="collab-section__count">{{ mineActive.length }}</span>
          </h3>
          <p v-if="mineActive.length === 0" class="collab-section__empty">
            暂无进行中的白板，点右上角「新建白板」。
          </p>
          <div v-else class="collab-grid">
            <article v-for="board in mineActive" :key="board.id" class="collab-card">
              <div class="collab-card__thumb">
                <img v-if="board.thumbnail" :src="board.thumbnail" :alt="`${board.name} 的缩略图`" loading="lazy" />
                <span v-else class="work-card__placeholder" aria-hidden="true">画</span>
              </div>
              <div class="collab-card__body">
                <h4 class="collab-card__name" :title="board.name">{{ board.name }}</h4>
                <p class="collab-card__meta" :title="formatDateTime(board.lastSavedAt)">
                  最后保存 {{ formatRelativeTime(board.lastSavedAt) }}
                </p>
              </div>
              <div class="collab-card__actions">
                <button class="btn btn--primary btn--xs" type="button" @click="enterRoom(board)">
                  进入房间
                </button>
                <button class="btn btn--xs" type="button" @click="openInvite(board)">
                  邀请链接
                </button>
              </div>
            </article>
          </div>
        </section>

        <section v-if="mineArchived.length > 0" class="collab-section">
          <h3 class="collab-section__title">
            已归档 <span class="collab-section__count">{{ mineArchived.length }}</span>
            <span class="collab-section__hint">只读，复制后可继续联机</span>
          </h3>
          <div class="collab-grid">
            <article v-for="board in mineArchived" :key="board.id" class="collab-card collab-card--muted">
              <div class="collab-card__thumb">
                <img v-if="board.thumbnail" :src="board.thumbnail" :alt="`${board.name} 的缩略图`" loading="lazy" />
                <span v-else class="work-card__placeholder" aria-hidden="true">画</span>
              </div>
              <div class="collab-card__body">
                <h4 class="collab-card__name" :title="board.name">{{ board.name }}</h4>
                <p class="collab-card__meta">已归档 · 只读</p>
              </div>
              <div class="collab-card__actions">
                <button class="btn btn--xs" type="button" @click="enterRoom(board)">只读查看</button>
                <button
                  class="btn btn--primary btn--xs"
                  type="button"
                  :disabled="busyId === board.id"
                  @click="duplicateAndJoin(board)"
                >
                  {{ busyId === board.id ? '复制中…' : '复制后联机' }}
                </button>
              </div>
            </article>
          </div>
        </section>

        <section class="collab-section">
          <h3 class="collab-section__title">
            他人公开板 <span class="collab-section__count">{{ othersPublic.length }}</span>
            <span class="collab-section__hint">链接即分享，可留空</span>
          </h3>
          <p v-if="othersPublic.length === 0" class="collab-section__empty">
            暂无他人公开的白板。粘贴邀请链接也能直接进房。
          </p>
          <div v-else class="collab-grid">
            <article v-for="board in othersPublic" :key="board.id" class="collab-card">
              <div class="collab-card__thumb">
                <img v-if="board.thumbnail" :src="board.thumbnail" :alt="`${board.name} 的缩略图`" loading="lazy" />
                <span v-else class="work-card__placeholder" aria-hidden="true">画</span>
              </div>
              <div class="collab-card__body">
                <h4 class="collab-card__name" :title="board.name">{{ board.name }}</h4>
                <p class="collab-card__meta" :title="formatDateTime(board.lastSavedAt)">
                  最后保存 {{ formatRelativeTime(board.lastSavedAt) }}
                </p>
              </div>
              <div class="collab-card__actions">
                <button class="btn btn--primary btn--xs" type="button" @click="enterRoom(board)">
                  进入房间
                </button>
                <button class="btn btn--xs" type="button" @click="openInvite(board)">
                  邀请链接
                </button>
              </div>
            </article>
          </div>
        </section>
      </template>
    </main>

    <SettingsDrawer v-model:open="settingsOpen" />

    <Teleport to="body">
      <div v-if="createOpen" class="modal-backdrop" @click.self="closeCreate">
        <form class="modal" role="dialog" aria-modal="true" aria-labelledby="collab-create-title" @submit.prevent="submitCreate">
          <h2 id="collab-create-title">新建白板</h2>
          <p class="modal__desc">创建后直接进入房间，并显示可复制的邀请链接。</p>
          <div class="field">
            <label class="field__label" for="collab-create-name">白板名称</label>
            <input
              id="collab-create-name"
              ref="createInput"
              v-model="createName"
              class="input"
              :class="{ 'is-invalid': createError }"
              type="text"
              maxlength="50"
              placeholder="例如：联机草稿"
              @input="createError = ''"
            />
          </div>
          <p v-if="createError" class="form-error" role="alert">{{ createError }}</p>
          <div class="modal__actions">
            <button class="btn" type="button" :disabled="creating" @click="closeCreate">取消</button>
            <button class="btn btn--primary" type="submit" :disabled="creating || !createName.trim()">
              {{ creating ? '创建中…' : '创建并进入' }}
            </button>
          </div>
        </form>
      </div>

      <div v-if="inviteTarget" class="modal-backdrop" @click.self="closeInvite">
        <div class="modal" role="dialog" aria-modal="true" aria-labelledby="collab-invite-title">
          <h2 id="collab-invite-title">邀请链接</h2>
          <p class="modal__desc">拿到链接的登录用户会自动成为编辑者（链接即分享）。</p>
          <div class="invite-row">
            <input class="input invite-input" type="text" readonly :value="inviteUrl" @focus="selectInvite" />
            <button class="btn btn--primary" type="button" @click="copyInviteFromModal">复制</button>
          </div>
          <div class="modal__actions">
            <button class="btn" type="button" @click="closeInvite">关闭</button>
            <button class="btn btn--primary" type="button" @click="enterRoom(inviteTarget)">进入房间</button>
          </div>
        </div>
      </div>
    </Teleport>

    <AppToast :toast="toast" />
  </div>
</template>
