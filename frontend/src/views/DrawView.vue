<script setup lang="ts">
/**
 * DrawView.vue — 画画：我的作品网格 + 全部/已归档/回收站 + 版本与归档操作
 *
 * 对应 docs/07 §4 / §4.2：
 *   - 分段控件 → GET /api/boards?status=active|archived|trashed（回收站必须显式传 status）
 *   - 卡片菜单 → 打开 / 改名 / 存为版本 / 归档 / 删除 / 恢复 / 复制
 *   - 卡片右上「历史」→ 版本抽屉（components/VersionDrawer.vue）
 *
 * 归档 / 回收站中的板按只读态处理：除「取消归档 / 恢复 / 复制」外的写操作一律禁用。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppHeader from '../components/AppHeader.vue'
import AppToast from '../components/AppToast.vue'
import SettingsDrawer from '../components/SettingsDrawer.vue'
import VersionDrawer from '../components/VersionDrawer.vue'
import { useToast } from '../components/useToast'
import { describeApiError } from '../auth/api'
import { isGuest } from '../auth/session'
import { boardId as newBoardId } from '../kernel/ids'
import { formatDateTime, formatRelativeTime, parseTime } from '../utils/time'
import {
  createBoard,
  createVersion,
  duplicateBoard,
  listBoards,
  renameBoard,
  restoreBoardFromTrash,
  setBoardStatus,
  trashBoard,
  type BoardRole,
  type BoardSummary,
} from '../works/boardsApi'

type Segment = 'active' | 'archived' | 'trashed'
type LoadState = 'loading' | 'ready' | 'error' | 'guest'

interface SegmentOption {
  value: Segment
  label: string
  hint: string
}

interface ConfirmState {
  title: string
  desc: string
  confirmLabel: string
  danger?: boolean
  run: () => Promise<void>
}

const SEGMENTS: SegmentOption[] = [
  { value: 'active', label: '全部', hint: '我创建的 + 我参与的 active 板' },
  { value: 'archived', label: '已归档', hint: '只读，可取消归档或复制' },
  { value: 'trashed', label: '回收站', hint: '30 天内可恢复' },
]

const ROLE_PRIORITY: Record<BoardRole, number> = { owner: 3, editor: 2, viewer: 1 }

const router = useRouter()
const guestMode = isGuest()
const { toast, showToast } = useToast()

const state = ref<LoadState>(guestMode ? 'guest' : 'loading')
const segment = ref<Segment>('active')
const search = ref('')
const boards = ref<BoardSummary[]>([])
const errorText = ref('')
const refreshing = ref(false)
const menuId = ref<string | null>(null)
const busyId = ref('')

const settingsOpen = ref(false)
const versionOpen = ref(false)
const versionTarget = ref<BoardSummary | null>(null)

const createOpen = ref(false)
const createName = ref('')
const createError = ref('')
const creating = ref(false)
const createInput = ref<HTMLInputElement | null>(null)

const renameTarget = ref<BoardSummary | null>(null)
const renameName = ref('')
const renameError = ref('')
const renaming = ref(false)
const renameInput = ref<HTMLInputElement | null>(null)

const saveTarget = ref<BoardSummary | null>(null)
const saveLabel = ref('')
const saveError = ref('')
const savingVersion = ref(false)

const confirmState = ref<ConfirmState | null>(null)
const confirmBusy = ref(false)

let requestSeq = 0
let searchTimer: number | null = null
let mounted = false

const activeSegment = computed(
  () => SEGMENTS.find((item) => item.value === segment.value) ?? SEGMENTS[0],
)
const hasKeyword = computed(() => search.value.trim().length > 0)
const canCreate = computed(() => !guestMode)

const visibleBoards = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  const filtered = keyword
    ? boards.value.filter((board) => board.name.toLowerCase().includes(keyword))
    : boards.value
  return [...filtered].sort(
    (left, right) =>
      timeOf(right.lastSavedAt ?? right.createdAt) - timeOf(left.lastSavedAt ?? left.createdAt),
  )
})

function timeOf(value: string | null): number {
  return parseTime(value)?.getTime() ?? 0
}

function roleLabel(role: BoardRole): string {
  if (role === 'owner') return '所有者'
  if (role === 'editor') return '编辑者'
  return '只读'
}

function isOwner(board: BoardSummary): boolean {
  return board.role === 'owner'
}

function isArchived(board: BoardSummary): boolean {
  return board.status === 'archived'
}

function isTrashed(board: BoardSummary): boolean {
  return board.status === 'trashed'
}

/** 归档 / 回收站 = 只读（docs/07 §2） */
function isReadOnly(board: BoardSummary): boolean {
  return isArchived(board) || isTrashed(board)
}

function canSaveVersion(board: BoardSummary): boolean {
  return !guestMode && board.role !== 'viewer' && !isReadOnly(board)
}

function statusLabel(board: BoardSummary): string {
  if (isTrashed(board)) return '回收站'
  if (isArchived(board)) return '已归档'
  if (board.status === 'draft') return '草稿'
  return '进行中'
}

function dedupe(items: BoardSummary[]): BoardSummary[] {
  const merged = new Map<string, BoardSummary>()
  for (const item of items) {
    const existing = merged.get(item.id)
    if (!existing || ROLE_PRIORITY[item.role] > ROLE_PRIORITY[existing.role]) {
      merged.set(item.id, item)
    }
  }
  return Array.from(merged.values())
}

// ---------------------------------------------------------------------------
// 数据加载
// ---------------------------------------------------------------------------

async function loadBoards(options: { silent?: boolean } = {}): Promise<void> {
  if (guestMode) {
    state.value = 'guest'
    return
  }

  const seq = ++requestSeq
  if (options.silent) {
    refreshing.value = true
  } else {
    state.value = 'loading'
    errorText.value = ''
  }

  const keyword = search.value.trim()
  try {
    let items: BoardSummary[]
    if (segment.value === 'active') {
      // 全部 = 我创建的 + 我参与的
      const results = await Promise.allSettled([
        listBoards({ scope: 'mine', status: 'active', q: keyword, page: 1, size: 50 }),
        listBoards({ scope: 'joined', status: 'active', q: keyword, page: 1, size: 50 }),
      ])
      if (seq !== requestSeq) return
      let successCount = 0
      let lastError: unknown = null
      const merged: BoardSummary[] = []
      for (const result of results) {
        if (result.status === 'fulfilled') {
          successCount += 1
          merged.push(...result.value.items)
        } else {
          lastError = result.reason
        }
      }
      if (successCount === 0) throw lastError ?? new Error('作品库加载失败')
      items = dedupe(merged)
    } else {
      // 已归档 / 回收站：只关心自己的板，且状态要显式传
      const data = await listBoards({
        scope: 'mine',
        status: segment.value,
        q: keyword,
        page: 1,
        size: 50,
      })
      if (seq !== requestSeq) return
      items = data.items
    }

    boards.value = items
    state.value = 'ready'
    errorText.value = ''
  } catch (error) {
    if (seq !== requestSeq) return
    const message = describeApiError(error)
    errorText.value = message
    if (options.silent && boards.value.length > 0) {
      showToast(message, 'error')
    } else {
      state.value = 'error'
    }
  } finally {
    if (seq === requestSeq) refreshing.value = false
  }
}

watch(segment, () => {
  menuId.value = null
  void loadBoards()
})

watch(search, () => {
  if (searchTimer !== null) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    searchTimer = null
    if (mounted && state.value !== 'guest') void loadBoards({ silent: true })
  }, 300)
})

// ---------------------------------------------------------------------------
// 卡片菜单 / 打开
// ---------------------------------------------------------------------------

function toggleMenu(id: string, event: MouseEvent): void {
  event.stopPropagation()
  menuId.value = menuId.value === id ? null : id
}

function closeMenu(): void {
  menuId.value = null
}

function openBoard(board: BoardSummary): void {
  menuId.value = null
  if (isTrashed(board)) {
    showToast('回收站中的作品需先恢复才能打开', 'warn')
    return
  }
  void router.push(`/board/${board.id}`)
}

function openHistory(board: BoardSummary): void {
  menuId.value = null
  versionTarget.value = board
  versionOpen.value = true
}

function onVersionNotice(payload: { kind: 'info' | 'error'; text: string }): void {
  showToast(payload.text, payload.kind)
}

function handleVersionRestored(): void {
  // /draw 不在房间里，回滚后只需要刷新列表；房间里重连由 BoardView 处理
  void loadBoards({ silent: true })
}

function handleVersionCreated(): void {
  void loadBoards({ silent: true })
}

// ---------------------------------------------------------------------------
// 新建 / 改名
// ---------------------------------------------------------------------------

function openCreate(): void {
  if (guestMode) {
    showToast('登录后才能创建作品，请先注册或登录', 'error')
    return
  }
  closeMenu()
  createName.value = ''
  createError.value = ''
  createOpen.value = true
  void nextTick(() => createInput.value?.focus())
}

function closeCreate(): void {
  if (creating.value) return
  createOpen.value = false
}

async function submitCreate(): Promise<void> {
  if (creating.value) return
  const name = createName.value.trim()
  if (!name) {
    createError.value = '请输入作品名称'
    return
  }
  if (Array.from(name).length > 50) {
    createError.value = '作品名称最多 50 个字符'
    return
  }

  creating.value = true
  createError.value = ''
  try {
    const created = await createBoard(name)
    if (!created?.id) throw new Error('服务器未返回白板 id')
    createOpen.value = false
    await router.push(`/board/${created.id}`)
  } catch (error) {
    createError.value = describeApiError(error)
  } finally {
    creating.value = false
  }
}

function openRename(board: BoardSummary): void {
  menuId.value = null
  if (!isOwner(board)) {
    showToast('只有房主可以改名', 'error')
    return
  }
  if (isReadOnly(board)) {
    showToast('作品已归档，只读', 'warn')
    return
  }
  renameTarget.value = board
  renameName.value = board.name
  renameError.value = ''
  void nextTick(() => renameInput.value?.focus())
}

function closeRename(): void {
  if (renaming.value) return
  renameTarget.value = null
  renameName.value = ''
  renameError.value = ''
}

async function submitRename(): Promise<void> {
  const target = renameTarget.value
  if (!target || renaming.value) return
  const name = renameName.value.trim()
  if (!name) {
    renameError.value = '请输入作品名称'
    return
  }
  if (Array.from(name).length > 50) {
    renameError.value = '作品名称最多 50 个字符'
    return
  }
  if (name === target.name) {
    closeRename()
    return
  }

  renaming.value = true
  renameError.value = ''
  try {
    await renameBoard(target.id, name)
    boards.value = boards.value.map((board) =>
      board.id === target.id ? { ...board, name } : board,
    )
    renameTarget.value = null
    showToast('作品名称已更新')
  } catch (error) {
    renameError.value = describeApiError(error)
  } finally {
    renaming.value = false
  }
}

// ---------------------------------------------------------------------------
// 存为版本（卡片菜单入口）
// ---------------------------------------------------------------------------

function openSaveVersion(board: BoardSummary): void {
  menuId.value = null
  if (!canSaveVersion(board)) {
    showToast(isReadOnly(board) ? '作品已归档，只读' : '当前角色不能存档', 'warn')
    return
  }
  saveTarget.value = board
  saveLabel.value = ''
  saveError.value = ''
}

function closeSaveVersion(): void {
  if (savingVersion.value) return
  saveTarget.value = null
  saveLabel.value = ''
  saveError.value = ''
}

async function submitSaveVersion(): Promise<void> {
  const target = saveTarget.value
  if (!target || savingVersion.value) return
  savingVersion.value = true
  saveError.value = ''
  try {
    await createVersion(target.id, { label: saveLabel.value.trim() || undefined })
    saveTarget.value = null
    saveLabel.value = ''
    showToast('已存为版本，可在「历史」里查看')
    void loadBoards({ silent: true })
  } catch (error) {
    saveError.value = describeApiError(error)
  } finally {
    savingVersion.value = false
  }
}

// ---------------------------------------------------------------------------
// 归档 / 回收站 / 复制
// ---------------------------------------------------------------------------

function askConfirm(next: ConfirmState): void {
  menuId.value = null
  confirmState.value = next
}

function closeConfirm(): void {
  if (confirmBusy.value) return
  confirmState.value = null
}

async function runConfirm(): Promise<void> {
  const current = confirmState.value
  if (!current || confirmBusy.value) return
  confirmBusy.value = true
  try {
    await current.run()
    confirmState.value = null
  } catch (error) {
    showToast(describeApiError(error), 'error')
    confirmState.value = null
  } finally {
    confirmBusy.value = false
  }
}

function archiveBoard(board: BoardSummary): void {
  if (!isOwner(board)) {
    showToast('只有房主可以归档', 'error')
    return
  }
  askConfirm({
    title: '归档这件作品？',
    desc: '归档后作品变为只读：仍可打开查看、复制或取消归档，但不能继续绘制、改名或存档。',
    confirmLabel: '归档',
    run: async () => {
      await setBoardStatus(board.id, 'archived')
      showToast('已归档，作品现在是只读状态')
      await loadBoards({ silent: true })
    },
  })
}

function unarchiveBoard(board: BoardSummary): void {
  if (!isOwner(board)) {
    showToast('只有房主可以取消归档', 'error')
    return
  }
  askConfirm({
    title: '取消归档？',
    desc: '取消后作品恢复为进行中，可以继续绘制与协作。',
    confirmLabel: '取消归档',
    run: async () => {
      await setBoardStatus(board.id, 'active')
      showToast('已取消归档')
      await loadBoards({ silent: true })
    },
  })
}

function trashBoardAction(board: BoardSummary): void {
  if (!isOwner(board)) {
    showToast('只有房主可以删除', 'error')
    return
  }
  askConfirm({
    title: '移入回收站？',
    desc: '作品会进入回收站并保留 30 天，期间可以恢复；期间作品不可编辑。',
    confirmLabel: '移入回收站',
    danger: true,
    run: async () => {
      await trashBoard(board.id)
      showToast('已移入回收站')
      await loadBoards({ silent: true })
    },
  })
}

function restoreTrashAction(board: BoardSummary): void {
  if (!isOwner(board)) {
    showToast('只有房主可以恢复', 'error')
    return
  }
  askConfirm({
    title: '从回收站恢复？',
    desc: '恢复后作品回到「全部」，状态变为进行中。',
    confirmLabel: '恢复',
    run: async () => {
      await restoreBoardFromTrash(board.id)
      showToast('已从回收站恢复')
      await loadBoards({ silent: true })
    },
  })
}

async function duplicate(board: BoardSummary): Promise<void> {
  menuId.value = null
  if (busyId.value === board.id) return
  if (guestMode) {
    showToast('游客不能复制作品，请先注册或登录', 'error')
    return
  }
  busyId.value = board.id
  try {
    const copy = await duplicateBoard(board.id)
    showToast('已复制为新的作品，正在打开…')
    await router.push(`/board/${copy.id}`)
  } catch (error) {
    showToast(describeApiError(error), 'error')
  } finally {
    busyId.value = ''
  }
}

// ---------------------------------------------------------------------------
// 其它
// ---------------------------------------------------------------------------

function goLogin(): void {
  void router.push({ path: '/login', query: { redirect: '/draw' } })
}

function enterBlankBoard(): void {
  void router.push(`/board/${newBoardId()}`)
}

function handleSettings(): void {
  settingsOpen.value = true
}

function handleKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape') return
  if (confirmState.value) {
    closeConfirm()
    return
  }
  if (createOpen.value) {
    closeCreate()
    return
  }
  if (renameTarget.value) {
    closeRename()
    return
  }
  if (saveTarget.value) {
    closeSaveVersion()
    return
  }
  menuId.value = null
}

onMounted(() => {
  mounted = true
  if (!guestMode) void loadBoards()
  document.addEventListener('click', closeMenu)
  window.addEventListener('keydown', handleKeydown)
})

onBeforeUnmount(() => {
  mounted = false
  document.removeEventListener('click', closeMenu)
  window.removeEventListener('keydown', handleKeydown)
  if (searchTimer !== null) window.clearTimeout(searchTimer)
})
</script>

<template>
  <div class="page-shell">
    <AppHeader
      title="画画"
      subtitle="作品库 · 版本历史 · 归档与回收站"
      back-to="/home"
      back-label="主页"
      @settings="handleSettings"
    />

    <main class="page-main">
      <div class="draw-toolbar">
        <div class="segmented draw-segmented" role="group" aria-label="作品状态">
          <button
            v-for="option in SEGMENTS"
            :key="option.value"
            type="button"
            :class="{ 'is-active': segment === option.value }"
            :disabled="state === 'guest'"
            :title="option.hint"
            @click="segment = option.value"
          >
            {{ option.label }}
          </button>
        </div>

        <label class="search-box draw-search">
          <span class="search-box__icon" aria-hidden="true">⌕</span>
          <input
            v-model="search"
            class="input"
            type="search"
            :disabled="state === 'guest'"
            placeholder="搜索作品名称"
            aria-label="搜索作品名称"
          />
          <span v-if="refreshing" class="search-box__state">搜索中…</span>
        </label>

        <div class="draw-toolbar__right">
          <span class="draw-toolbar__hint">{{ activeSegment.hint }}</span>
          <button class="btn btn--primary" type="button" :disabled="!canCreate" @click="openCreate">
            + 新建白板
          </button>
        </div>
      </div>

      <div v-if="state === 'loading'" class="works-grid" aria-label="作品加载中">
        <div v-for="index in 6" :key="index" class="skeleton-card" aria-hidden="true">
          <div class="skeleton-card__thumb" />
          <div class="skeleton-card__body">
            <div class="skeleton-line skeleton-line--title" />
            <div class="skeleton-line skeleton-line--short" />
          </div>
        </div>
      </div>

      <section v-else-if="state === 'error'" class="works-state">
        <div class="works-state__icon works-state__icon--error" aria-hidden="true">!</div>
        <h2>作品库加载失败</h2>
        <p>{{ errorText || '无法连接服务器，请确认后端已启动' }}</p>
        <div class="works-state__actions">
          <button class="btn btn--primary" type="button" @click="loadBoards()">重新加载</button>
          <button class="btn" type="button" @click="router.push('/home')">回主页</button>
        </div>
      </section>

      <section v-else-if="state === 'guest'" class="works-state">
        <div class="works-state__icon" aria-hidden="true">游</div>
        <h2>游客模式</h2>
        <p>
          游客看不到作品库，也不能创建、存档或回滚。你仍然可以加入公开白板协作，注册后保留当前身份参与过的记录。
        </p>
        <div class="works-state__actions">
          <button class="btn btn--primary" type="button" @click="enterBlankBoard">进入空白画板</button>
          <button class="btn" type="button" @click="goLogin">登录 / 注册</button>
        </div>
      </section>

      <section v-else-if="visibleBoards.length === 0" class="works-state">
        <div class="works-state__icon" aria-hidden="true">{{ hasKeyword ? '?' : segment === 'trashed' ? '♻' : '+' }}</div>
        <h2 v-if="hasKeyword">没有找到匹配的作品</h2>
        <h2 v-else-if="segment === 'archived'">还没有归档的作品</h2>
        <h2 v-else-if="segment === 'trashed'">回收站是空的</h2>
        <h2 v-else>还没有作品</h2>
        <p v-if="hasKeyword">换个关键词试试，或清空搜索查看全部作品。</p>
        <p v-else-if="segment === 'archived'">
          在「全部」里对作品点「归档」，作品会变只读并出现在这里。
        </p>
        <p v-else-if="segment === 'trashed'">
          回收站只会收到你主动删除的作品，30 天内都可以恢复。
        </p>
        <p v-else>点右上角「新建白板」开始创作，画布会自动保存。</p>
        <div class="works-state__actions">
          <button v-if="hasKeyword" class="btn" type="button" @click="search = ''">清空搜索</button>
          <button v-else-if="segment !== 'active'" class="btn" type="button" @click="segment = 'active'">
            回到全部
          </button>
          <button v-else class="btn btn--primary" type="button" @click="openCreate">新建白板</button>
        </div>
      </section>

      <div v-else class="works-grid">
        <article
          v-for="board in visibleBoards"
          :key="board.id"
          class="work-card"
          :class="{ 'work-card--readonly': isReadOnly(board) }"
          tabindex="0"
          @click="openBoard(board)"
          @keydown.enter="openBoard(board)"
        >
          <div class="work-card__thumb">
            <img
              v-if="board.thumbnail"
              :src="board.thumbnail"
              :alt="`${board.name} 的缩略图`"
              loading="lazy"
            />
            <div v-else class="work-card__placeholder" aria-hidden="true">画</div>
            <span class="work-card__status" :class="`work-card__status--${board.status}`">
              {{ statusLabel(board) }}
            </span>
          </div>

          <div class="work-card__body">
            <div class="work-card__head">
              <h2 class="work-card__name" :title="board.name">{{ board.name }}</h2>
              <span class="badge" :class="`badge--${board.role}`" :title="`角色：${board.role}`">
                {{ roleLabel(board.role) }}
              </span>
            </div>
            <div class="work-card__meta">
              <span :title="formatDateTime(board.lastSavedAt)">
                {{ board.lastSavedAt ? `最后保存 ${formatRelativeTime(board.lastSavedAt)}` : '尚未保存' }}
              </span>
              <span class="dot-sep">·</span>
              <span :title="formatDateTime(board.createdAt)">
                创建于 {{ formatRelativeTime(board.createdAt) }}
              </span>
            </div>
          </div>

          <div class="work-card__menu" @click.stop>
            <button
              class="btn btn--xs work-card__history"
              type="button"
              title="查看版本历史"
              @click.stop="openHistory(board)"
            >
              历史
            </button>
            <button
              class="icon-btn"
              type="button"
              aria-label="更多操作"
              :aria-expanded="menuId === board.id"
              @click.stop="toggleMenu(board.id, $event)"
            >
              ⋯
            </button>
            <div v-if="menuId === board.id" class="menu" role="menu">
              <button class="menu__item" type="button" role="menuitem" @click.stop="openBoard(board)">
                打开
              </button>
              <button
                class="menu__item"
                type="button"
                role="menuitem"
                :disabled="!isOwner(board) || isReadOnly(board)"
                @click.stop="openRename(board)"
              >
                改名
              </button>
              <button
                v-if="!isTrashed(board)"
                class="menu__item"
                type="button"
                role="menuitem"
                :disabled="!canSaveVersion(board)"
                @click.stop="openSaveVersion(board)"
              >
                存为版本
              </button>
              <button class="menu__item" type="button" role="menuitem" @click.stop="openHistory(board)">
                版本历史
              </button>
              <button
                v-if="!isTrashed(board) && !isArchived(board)"
                class="menu__item"
                type="button"
                role="menuitem"
                :disabled="!isOwner(board)"
                @click.stop="archiveBoard(board)"
              >
                归档（只读）
              </button>
              <button
                v-if="isArchived(board)"
                class="menu__item"
                type="button"
                role="menuitem"
                :disabled="!isOwner(board)"
                @click.stop="unarchiveBoard(board)"
              >
                取消归档
              </button>
              <button
                v-if="!isTrashed(board)"
                class="menu__item menu__item--danger"
                type="button"
                role="menuitem"
                :disabled="!isOwner(board)"
                @click.stop="trashBoardAction(board)"
              >
                删除进回收站
              </button>
              <button
                v-if="isTrashed(board)"
                class="menu__item"
                type="button"
                role="menuitem"
                :disabled="!isOwner(board)"
                @click.stop="restoreTrashAction(board)"
              >
                从回收站恢复
              </button>
              <button
                class="menu__item"
                type="button"
                role="menuitem"
                :disabled="busyId === board.id"
                @click.stop="duplicate(board)"
              >
                {{ busyId === board.id ? '复制中…' : '复制' }}
              </button>
              <p v-if="!isOwner(board)" class="menu__hint">仅房主可改名 / 归档 / 删除</p>
              <p v-else-if="isReadOnly(board)" class="menu__hint">只读作品：可取消归档 / 恢复 / 复制</p>
            </div>
          </div>
        </article>
      </div>

      <p v-if="state === 'ready' && visibleBoards.length > 0" class="works-count">
        共 {{ visibleBoards.length }} 个作品
        <template v-if="segment === 'active'">（我创建的 + 我参与的）</template>
      </p>
    </main>

    <SettingsDrawer v-model:open="settingsOpen" />

    <VersionDrawer
      v-model:open="versionOpen"
      :board-id="versionTarget?.id ?? ''"
      :board-name="versionTarget?.name"
      :can-restore="!!versionTarget && isOwner(versionTarget) && !isReadOnly(versionTarget)"
      :can-create="!!versionTarget && canSaveVersion(versionTarget)"
      :disabled-reason="versionTarget ? '只读作品不能存档 / 回滚' : ''"
      @restored="handleVersionRestored"
      @created="handleVersionCreated"
      @notice="onVersionNotice"
    />

    <Teleport to="body">
      <div v-if="createOpen" class="modal-backdrop" @click.self="closeCreate">
        <form class="modal" role="dialog" aria-modal="true" aria-labelledby="draw-create-title" @submit.prevent="submitCreate">
          <h2 id="draw-create-title">新建白板</h2>
          <p class="modal__desc">创建后自动进入画布，服务端会持续自动保存。</p>
          <div class="field">
            <label class="field__label" for="draw-create-name">作品名称</label>
            <input
              id="draw-create-name"
              ref="createInput"
              v-model="createName"
              class="input"
              :class="{ 'is-invalid': createError }"
              type="text"
              maxlength="50"
              placeholder="例如：动物速写"
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

      <div v-if="renameTarget" class="modal-backdrop" @click.self="closeRename">
        <form class="modal" role="dialog" aria-modal="true" aria-labelledby="draw-rename-title" @submit.prevent="submitRename">
          <h2 id="draw-rename-title">重命名作品</h2>
          <p class="modal__desc">仅房主可以修改白板名称。</p>
          <div class="field">
            <label class="field__label" for="draw-rename-name">作品名称</label>
            <input
              id="draw-rename-name"
              ref="renameInput"
              v-model="renameName"
              class="input"
              :class="{ 'is-invalid': renameError }"
              type="text"
              maxlength="50"
              @input="renameError = ''"
            />
          </div>
          <p v-if="renameError" class="form-error" role="alert">{{ renameError }}</p>
          <div class="modal__actions">
            <button class="btn" type="button" :disabled="renaming" @click="closeRename">取消</button>
            <button class="btn btn--primary" type="submit" :disabled="renaming || !renameName.trim()">
              {{ renaming ? '保存中…' : '保存' }}
            </button>
          </div>
        </form>
      </div>

      <div v-if="saveTarget" class="modal-backdrop" @click.self="closeSaveVersion">
        <form class="modal" role="dialog" aria-modal="true" aria-labelledby="draw-version-title" @submit.prevent="submitSaveVersion">
          <h2 id="draw-version-title">存为版本</h2>
          <p class="modal__desc">
            为「{{ saveTarget.name }}」的当前内容打一个手动存档点，永久保留（MANUAL）。
          </p>
          <div class="field">
            <label class="field__label" for="draw-version-label">备注（可选）</label>
            <input
              id="draw-version-label"
              v-model="saveLabel"
              class="input"
              type="text"
              maxlength="100"
              placeholder="例如：线稿完成"
              @input="saveError = ''"
            />
          </div>
          <p v-if="saveError" class="form-error" role="alert">{{ saveError }}</p>
          <div class="modal__actions">
            <button class="btn" type="button" :disabled="savingVersion" @click="closeSaveVersion">取消</button>
            <button class="btn btn--primary" type="submit" :disabled="savingVersion">
              {{ savingVersion ? '存档中…' : '存为版本' }}
            </button>
          </div>
        </form>
      </div>

      <div v-if="confirmState" class="modal-backdrop" @click.self="closeConfirm">
        <div class="modal" role="dialog" aria-modal="true" aria-labelledby="draw-confirm-title">
          <h2 id="draw-confirm-title">{{ confirmState.title }}</h2>
          <p class="modal__desc">{{ confirmState.desc }}</p>
          <div class="modal__actions">
            <button class="btn" type="button" :disabled="confirmBusy" @click="closeConfirm">取消</button>
            <button
              class="btn"
              :class="confirmState.danger ? 'btn--danger' : 'btn--primary'"
              type="button"
              :disabled="confirmBusy"
              @click="runConfirm"
            >
              {{ confirmBusy ? '处理中…' : confirmState.confirmLabel }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>

    <AppToast :toast="toast" />
  </div>
</template>
