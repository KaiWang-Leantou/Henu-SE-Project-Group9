<script setup lang="ts">
/**
 * BoardView.vue — 画布页：内核 + 网络编排，顶栏接入作品库返回、改名、登录态与自动保存
 *
 * 渲染与协同逻辑仍由 BoardCanvas / RoomClient / kernel 负责；本页只做路由、会话与顶栏状态。
 * 连不上服务器时进入离线模式，界面照常可用。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { describeApiError } from '../auth/api'
import {
  currentUser,
  ensureGuest,
  isGuest as isGuestSession,
  isLoggedIn,
} from '../auth/session'
import { getToken } from '../auth/storage'
import BoardCanvas from '../board/BoardCanvas.vue'
import Toolbar from '../board/Toolbar.vue'
import { fallbackCursorColor, type RemoteCursorView } from '../board/cursor'
import SettingsDrawer from '../components/SettingsDrawer.vue'
import VersionDrawer from '../components/VersionDrawer.vue'
import type { InputTool } from '../canvas/input'
import { createPage } from '../kernel/doc'
import { userId as newUserId } from '../kernel/ids'
import { WhiteboardKernel, type KernelTransport } from '../kernel/kernel'
import { allowAllPolicy, readonlyPolicy } from '../kernel/policy'
import type { BoardCommand } from '../kernel/types'
import { RoomClient, type RoomStatus } from '../net/client'
import type { Member } from '../net/protocol'
import { boardInviteUrl, copyText } from '../utils/clipboard'
import { formatClock } from '../utils/time'
import { getBoard, renameBoard, updateThumbnail } from '../works/boardsApi'

interface StrokeAppendPayload {
  pageId: string
  strokeId: string
  tool: 'pen'
  color: string
  width: number
  pts: number[]
}

interface BoardCanvasHandle {
  exportThumbnail: () => string | null
}

type ToastKind = 'info' | 'warn' | 'error'

const route = useRoute()
const router = useRouter()

// ---------------------------------------------------------------------------
// 本地身份 / 房间号（boardId 来自 /board/:id；用户 id 存 sessionStorage，便于多开标签页模拟多人）
// ---------------------------------------------------------------------------

function getSessionStorage(): Storage | null {
  try {
    return window.sessionStorage
  } catch {
    return null
  }
}

function readStorage(storage: Storage | null, key: string): string | null {
  try {
    return storage?.getItem(key) ?? null
  } catch {
    return null
  }
}

function writeStorage(storage: Storage | null, key: string, value: string): void {
  try {
    storage?.setItem(key, value)
  } catch {
    // 隐私模式下忽略
  }
}

function resolveBoardId(): string {
  const raw = route.params.id
  const value = Array.isArray(raw) ? raw[0] : raw
  return typeof value === 'string' ? value.trim() : ''
}

function resolveAuthor(): string {
  const session = getSessionStorage()
  const storedUser = currentUser()
  if (storedUser?.id) return storedUser.id
  const stored = readStorage(session, 'wb.userId')
  if (stored) return stored
  const created = newUserId()
  writeStorage(session, 'wb.userId', created)
  return created
}

function resolveNickname(): string {
  const user = currentUser()
  if (user?.nickname) return user.nickname
  const session = getSessionStorage()
  const stored = readStorage(session, 'wb.nickname')
  if (stored) return stored
  const created = `游客${Math.floor(1000 + Math.random() * 9000)}`
  writeStorage(session, 'wb.nickname', created)
  return created
}

function defaultBoardName(id: string): string {
  return id ? `白板 ${id.slice(-6)}` : '协同白板'
}

const routeBoardId = resolveBoardId()
const boardId = ref(routeBoardId || 'b_local')
const nickname = ref(resolveNickname())
const kernel = new WhiteboardKernel({ boardId: boardId.value, author: resolveAuthor() })
const client = new RoomClient()

const status = ref<RoomStatus>('idle')
const statusDetail = ref('')
const members = ref<Member[]>([])
const me = ref<Member | null>(null)
const pages = ref<Array<{ id: string; name: string }>>([])
const activePageId = ref('')
const tool = ref<InputTool>('pen')
const color = ref('#111827')
const width = ref(4)
const cursors = ref<RemoteCursorView[]>([])
const revision = ref(0)
const toast = ref<{ kind: ToastKind; text: string } | null>(null)

const boardName = ref(defaultBoardName(boardId.value))
const boardRole = ref<Member['role'] | null>(null)
const boardStatus = ref('active')
const lastSavedAt = ref<string | null>(null)
const isGuestUser = ref(isGuestSession())
const editingName = ref(false)
const nameDraft = ref('')
const nameInputRef = ref<HTMLInputElement | null>(null)
const renamingBoard = ref(false)
const uploadingThumbnail = ref(false)
const canvasRef = ref<BoardCanvasHandle | null>(null)

// 抽屉 / 弹窗
const versionOpen = ref(false)
const settingsOpen = ref(false)
const inviteOpen = ref(false)

const cleanups: Array<() => void> = []
const cursorMap = new Map<string, RemoteCursorView>()
let cursorPruneTimer: number | null = null
let toastTimer: number | null = null

const transport: KernelTransport = {
  send: async (cmd) => {
    client.sendOp('board', cmd)
  },
}

// ---------------------------------------------------------------------------
// 派生状态
// ---------------------------------------------------------------------------

const statusText = computed(() => {
  switch (status.value) {
    case 'connecting':
      return '连接中…'
    case 'online':
      return '在线协作'
    case 'reconnecting':
      return '重连中…'
    case 'offline':
      return '离线模式'
    case 'closed':
      return '已断开'
    default:
      return '未连接'
  }
})

const pageTotal = computed(() => Math.max(1, pages.value.length))
const pageIndex = computed(() => {
  const index = pages.value.findIndex((page) => page.id === activePageId.value)
  return index < 0 ? 0 : index
})
const activePageName = computed(
  () => pages.value.find((page) => page.id === activePageId.value)?.name ?? '—',
)
const elementCount = computed(() => {
  void revision.value
  const page = kernel.doc.pages.find((item) => item.id === activePageId.value)
  return page?.elements.length ?? 0
})
const onlineMembers = computed(() => members.value.filter((member) => member.online))
const visibleMembers = computed(() => onlineMembers.value.slice(0, 6))
const isOwner = computed(() => me.value?.role === 'owner')
/** 归档 / 回收站的板只读（docs/07 §2） */
const archived = computed(
  () => boardStatus.value === 'archived' || boardStatus.value === 'trashed',
)
const readOnly = computed(
  () => archived.value || (status.value === 'online' && me.value?.role === 'viewer'),
)
const canUndo = computed(() => {
  void revision.value
  return !readOnly.value && kernel.canUndo
})
const canClear = computed(() => {
  if (readOnly.value) return false
  // 离线时自己做主；在线时 page.clear 只有 owner 能发（docs/02 §5）
  return status.value !== 'online' || isOwner.value
})
const showOfflineBanner = computed(() => status.value === 'offline')
const isGuest = computed(() => isGuestUser.value || me.value?.guest === true)
const canRename = computed(
  () => !archived.value && (boardRole.value === null || boardRole.value === 'owner'),
)
const canUploadThumbnail = computed(() => !archived.value && boardRole.value !== 'viewer')
const canRestoreVersion = computed(() => !archived.value && isOwner.value)
const canCreateVersion = computed(
  () => !archived.value && !isGuest.value && boardRole.value !== 'viewer',
)
const inviteUrl = computed(() => boardInviteUrl(boardId.value))
const saveLabel = computed(() =>
  lastSavedAt.value ? `已自动保存 ${formatClock(lastSavedAt.value)}` : '尚未自动保存',
)

// ---------------------------------------------------------------------------
// 工具函数
// ---------------------------------------------------------------------------

function showToast(text: string, kind: ToastKind = 'info'): void {
  toast.value = { kind, text }
  if (toastTimer !== null) window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => {
    toast.value = null
    toastTimer = null
  }, 5000)
}

function initialOf(name: string): string {
  const chars = Array.from(name.trim())
  return chars.length > 0 ? chars[0].toUpperCase() : '?'
}

function roleLabel(role: Member['role']): string {
  if (role === 'owner') return '房主'
  if (role === 'editor') return '可编辑'
  return '只读'
}

function memberName(actor: string): string {
  return members.value.find((member) => member.id === actor)?.name ?? `用户 ${actor.slice(-4)}`
}

/**
 * 远端光标颜色：docs/07 §5 规定优先用 members[].color（服务端按 userId 哈希分配），
 * 老服务端没下发时按 actor 稳定散列到同一套 12 色板。
 */
function memberColor(actor: string): string {
  const member = members.value.find((item) => item.id === actor)
  return member?.color || fallbackCursorColor(actor)
}

function flushCursors(): void {
  cursors.value = Array.from(cursorMap.values())
}

function syncFromKernel(): void {
  pages.value = kernel.doc.pages.map((page) => ({ id: page.id, name: page.name }))
  if (!pages.value.some((page) => page.id === activePageId.value)) {
    activePageId.value = pages.value[0]?.id ?? ''
  }
  revision.value += 1
}

// ---------------------------------------------------------------------------
// 用户操作
// ---------------------------------------------------------------------------

async function handleUndo(): Promise<void> {
  if (readOnly.value) {
    showToast('只读模式不能撤销', 'warn')
    return
  }
  const done = await kernel.undo()
  if (!done) showToast('没有可撤销的笔划（只能撤销自己的笔划）')
}

async function handleClear(): Promise<void> {
  if (!canClear.value) return
  if (!window.confirm('确定清空当前页？该操作不可撤销。')) return
  try {
    await kernel.dispatch({ t: 'page.clear', pageId: activePageId.value })
  } catch (error) {
    showToast(error instanceof Error ? error.message : String(error), 'error')
  }
}

async function handleAddPage(): Promise<void> {
  const page = createPage(`第 ${pages.value.length + 1} 页`)
  try {
    await kernel.dispatch({ t: 'page.add', page })
    activePageId.value = page.id
  } catch (error) {
    showToast(error instanceof Error ? error.message : String(error), 'error')
  }
}

function goPrevPage(): void {
  if (pageIndex.value <= 0) return
  activePageId.value = pages.value[pageIndex.value - 1].id
}

function goNextPage(): void {
  if (pageIndex.value >= pages.value.length - 1) return
  activePageId.value = pages.value[pageIndex.value + 1].id
}

function handleStrokeAppend(payload: StrokeAppendPayload): void {
  if (!client.isReady) return
  try {
    client.sendStrokeAppend(
      payload.pageId,
      payload.strokeId,
      payload.tool,
      payload.color,
      payload.width,
      payload.pts,
    )
  } catch (error) {
    console.warn('[net] stroke.append 发送失败', error)
  }
}

function handleStrokeEnd(payload: { strokeId: string }): void {
  if (!client.isReady) return
  try {
    client.sendStrokeEnd(payload.strokeId)
  } catch (error) {
    console.warn('[net] stroke.end 发送失败', error)
  }
}

function handleCursor(position: { x: number; y: number }): void {
  if (!client.isReady) return
  client.sendCursor(position.x, position.y)
}

function handleKeydown(event: KeyboardEvent): void {
  const target = event.target as HTMLElement | null
  const tag = target?.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || target?.isContentEditable) return
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') {
    event.preventDefault()
    void handleUndo()
  }
}

function goDraw(): void {
  void router.replace('/draw')
}

function startRename(): void {
  if (renamingBoard.value) return
  if (archived.value) {
    showToast('作品已归档，只读：不能改名', 'warn')
    return
  }
  if (!canRename.value) {
    showToast('只有房主可以改名', 'warn')
    return
  }
  nameDraft.value = boardName.value
  editingName.value = true
  void nextTick(() => {
    nameInputRef.value?.focus()
    nameInputRef.value?.select()
  })
}

function cancelRename(): void {
  editingName.value = false
  nameDraft.value = boardName.value
}

async function commitRename(): Promise<void> {
  if (!editingName.value || renamingBoard.value) return
  const name = nameDraft.value.trim()
  if (!name || name === boardName.value) {
    editingName.value = false
    return
  }
  if (Array.from(name).length > 50) {
    showToast('白板名最多 50 个字符', 'warn')
    return
  }

  renamingBoard.value = true
  try {
    await renameBoard(boardId.value, name)
    boardName.value = name
    editingName.value = false
    showToast('白板名已更新')
  } catch (error) {
    showToast(describeApiError(error), 'error')
  } finally {
    renamingBoard.value = false
  }
}

async function loadBoardMeta(): Promise<void> {
  try {
    const detail = await getBoard(boardId.value)
    if (detail.name) boardName.value = detail.name
    if (detail.status) boardStatus.value = detail.status.toLowerCase()
    // WS init 的 me.role / savedAt 更权威：晚到的 REST 响应不能覆盖它
    if (detail.role && me.value === null) boardRole.value = detail.role
    if (detail.lastSavedAt && !lastSavedAt.value) lastSavedAt.value = detail.lastSavedAt
    if (archived.value) showToast('作品已归档，只读：可以查看 / 回滚 / 复制，不能绘制', 'warn')
  } catch (error) {
    // 后端未启动 / 白板尚不存在（WS join 会自动创建）时，画布仍可离线使用，不打断演示
    console.warn('[board] 读取白板信息失败', error)
  }
}

async function handleUploadThumbnail(): Promise<void> {
  if (uploadingThumbnail.value) return
  if (archived.value) {
    showToast('作品已归档，只读：不能上传缩略图', 'warn')
    return
  }
  if (isGuest.value) {
    showToast('游客不能上传缩略图，请先注册或登录', 'warn')
    return
  }
  if (!canUploadThumbnail.value) {
    showToast('当前角色没有上传缩略图的权限', 'warn')
    return
  }
  const dataUrl = canvasRef.value?.exportThumbnail() ?? null
  if (!dataUrl) {
    showToast('画布尚未准备好，无法生成缩略图', 'error')
    return
  }

  uploadingThumbnail.value = true
  try {
    await updateThumbnail(boardId.value, dataUrl)
    showToast('缩略图已上传')
  } catch (error) {
    showToast(describeApiError(error), 'error')
  } finally {
    uploadingThumbnail.value = false
  }
}

// ---------------------------------------------------------------------------
// 邀请链接 / 版本抽屉 / 设置
// ---------------------------------------------------------------------------

function openInvite(): void {
  inviteOpen.value = true
}

function closeInvite(): void {
  inviteOpen.value = false
}

function selectInvite(event: FocusEvent): void {
  const target = event.target
  if (target instanceof HTMLInputElement) target.select()
}

async function copyInvite(): Promise<void> {
  const ok = await copyText(inviteUrl.value)
  showToast(ok ? '邀请链接已复制' : '复制失败，请手动选中链接复制', ok ? 'info' : 'error')
}

function openVersions(): void {
  versionOpen.value = true
}

function handleVersionNotice(payload: { kind: 'info' | 'error'; text: string }): void {
  showToast(payload.text, payload.kind)
}

/** 回滚完成：若当前在房间里则重连，让画布立刻换成本地最新的服务端文档 */
async function handleVersionRestored(): Promise<void> {
  if (status.value === 'offline' || status.value === 'idle' || status.value === 'closed') {
    void loadBoardMeta()
    return
  }
  try {
    await client.connect(boardId.value, nickname.value, getToken() ?? undefined)
    showToast('已重新同步房间内容')
  } catch (error) {
    console.warn('[board] 回滚后重连失败', error)
    showToast('回滚完成，但画布重连失败，请点「重试连接」', 'warn')
  }
}

// ---------------------------------------------------------------------------
// 会话与网络连接
// ---------------------------------------------------------------------------

async function ensureSession(): Promise<void> {
  const user = currentUser()
  if (isLoggedIn() && user) {
    nickname.value = user.nickname
    isGuestUser.value = user.guest
    return
  }

  try {
    // docs/04 §3.4：首次访问自动建游客身份，保住「打开就能画」的演示体验
    const guestUser = await ensureGuest(nickname.value)
    nickname.value = guestUser.nickname
    isGuestUser.value = guestUser.guest
  } catch (error) {
    console.warn('[auth] 游客会话创建失败，将以匿名方式连接画布', error)
  }
}

async function connectToServer(): Promise<void> {
  status.value = 'connecting'
  statusDetail.value = ''
  try {
    await client.connect(boardId.value, nickname.value, getToken() ?? undefined)
    status.value = 'online'
  } catch (error) {
    status.value = 'offline'
    statusDetail.value = error instanceof Error ? error.message : String(error)
    kernel.setTransport(null)
    showToast('协作服务器不可达，已进入离线模式：仍可本地绘制、撤销、翻页', 'warn')
  }
}

function hasLocalContent(): boolean {
  return kernel.doc.pages.some((page) => page.elements.length > 0)
}

function retryConnect(): void {
  if (status.value === 'connecting' || status.value === 'reconnecting') return
  // 离线期间画的内容只在本机内存；连接成功后 init 会以服务端文档为准
  if (status.value === 'offline' && hasLocalContent()) {
    const confirmed = window.confirm(
      '重新连接会以服务器文档为准，离线期间未同步的绘制将丢失。确定继续？',
    )
    if (!confirmed) return
  }
  void connectToServer()
}

function wireNetwork(): void {
  cleanups.push(
    client.on('status', (payload) => {
      status.value = payload.status
      statusDetail.value = payload.detail ?? ''
      if (payload.status === 'online') {
        kernel.setTransport(transport)
      } else {
        kernel.setTransport(null)
      }
    }),
  )

  cleanups.push(
    client.on('init', (message) => {
      me.value = message.me
      members.value = message.members
      boardRole.value = message.me.role
      if (message.boardId) boardId.value = message.boardId
      if (typeof message.me.guest === 'boolean') isGuestUser.value = message.me.guest
      if (message.savedAt) lastSavedAt.value = message.savedAt
      // 匿名 V1 由服务端分配 userId；本地笔划归属以此为准
      kernel.setAuthor(message.me.id)
      kernel.setPolicy(message.me.role === 'viewer' ? readonlyPolicy : allowAllPolicy)
      kernel.replaceDoc(message.doc)
      syncFromKernel()
      // init 带回了最新的 members[].color，刷新已有光标的配色
      for (const cursor of cursorMap.values()) {
        cursor.color = memberColor(cursor.actor)
      }
      flushCursors()
      showToast(`已加入房间 ${message.boardId}`)
    }),
  )

  // docs/04 §9：快照落库后刷新「已自动保存 HH:mm:ss」
  cleanups.push(
    client.on('saved', (message) => {
      lastSavedAt.value = message.at || new Date().toISOString()
    }),
  )

  cleanups.push(
    client.on('op', (message) => {
      if (message.ch !== 'board') return
      kernel.applyRemote(message.seq, message.cmd as BoardCommand, message.actor)
    }),
  )

  cleanups.push(
    client.on('stroke-append', (message) => {
      kernel.relayStrokeProgress({
        pageId: message.pageId,
        strokeId: message.strokeId,
        author: message.actor,
        tool: message.tool,
        color: message.color,
        width: message.width,
        points: message.pts,
      })
    }),
  )

  cleanups.push(
    client.on('stroke-end', (message) => {
      kernel.relayStrokeProgressEnd(message.strokeId)
    }),
  )

  cleanups.push(
    client.on('presence', (list) => {
      members.value = list
      const onlineIds = new Set(list.filter((member) => member.online).map((member) => member.id))
      let changed = false
      for (const actor of Array.from(cursorMap.keys())) {
        if (!onlineIds.has(actor)) {
          cursorMap.delete(actor)
          changed = true
        }
      }
      // 服务端可能刷新颜色 / 昵称（members[].color）：同步到已有光标上
      for (const cursor of cursorMap.values()) {
        const nextColor = memberColor(cursor.actor)
        const nextName = memberName(cursor.actor)
        if (cursor.color !== nextColor || cursor.name !== nextName) {
          cursor.color = nextColor
          cursor.name = nextName
          changed = true
        }
      }
      if (changed) flushCursors()
    }),
  )

  cleanups.push(
    client.on('cursor', (message) => {
      if (message.actor === me.value?.id || message.actor === kernel.author) return
      cursorMap.set(message.actor, {
        actor: message.actor,
        name: memberName(message.actor),
        x: message.x,
        y: message.y,
        at: Date.now(),
        color: memberColor(message.actor),
      })
      flushCursors()
    }),
  )

  // 回滚 / 归档后的全量重同步（docs/07 §3）
  cleanups.push(
    client.on('board-reload', (message) => {
      console.info('[net] board.reload', message.reason ?? '')
      cursorMap.clear()
      flushCursors()
      showToast('画布已变更（回滚 / 归档），正在重新同步…', 'warn')
    }),
  )

  cleanups.push(
    client.on('seq-gap', (payload) => {
      console.warn('[net] seq 跳变', payload)
      showToast(
        `序号不连续（期望 ${payload.expected}，收到 ${payload.received}），正在重新同步…`,
        'warn',
      )
    }),
  )

  cleanups.push(
    client.on('error', (payload) => {
      console.warn('[net] 服务端错误', payload)
      // docs/07 §2：归档 / 回收站板的写命令一律被服务端拒绝
      if (payload.code === 'READ_ONLY') {
        boardStatus.value = 'archived'
        showToast('作品已归档，只读：服务端拒绝了这次写入', 'error')
        return
      }
      showToast(`[${payload.code ?? 'ERROR'}] ${payload.msg}`, 'error')
    }),
  )

  cleanups.push(
    client.on('log', (payload) => {
      if (payload.level === 'warn') console.warn('[net]', payload.message)
      else if (payload.level === 'error') console.error('[net]', payload.message)
      else console.info('[net]', payload.message)
    }),
  )
}

function wireKernel(): void {
  const sync = (): void => syncFromKernel()
  cleanups.push(kernel.on('op', sync))
  cleanups.push(kernel.on('pagerefresh', sync))
  cleanups.push(kernel.on('doc', sync))
  cleanups.push(
    kernel.on('error', (payload) => {
      console.warn('[kernel]', payload.message, payload.error)
      showToast(payload.message, 'warn')
    }),
  )
}

// ---------------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------------

// 首帧渲染前先同步一次页列表，避免画布组件拿到空 pageId
syncFromKernel()

async function bootstrap(): Promise<void> {
  if (!routeBoardId) {
    await router.replace('/draw')
    return
  }
  // 先给用户「连接中」反馈；游客接口慢时也不会显得页面卡死
  status.value = 'connecting'
  await ensureSession()
  void loadBoardMeta()
  await connectToServer()

  // 新建 / 复制后带 ?invite=1 进入房间：自动弹出邀请链接（docs/07 §4.3）
  if (route.query.invite === '1') {
    inviteOpen.value = true
    // 清掉 query，避免刷新时反复弹窗（App.vue 按 path 做 key，不会重挂画布）
    void router.replace(`/board/${boardId.value}`)
  }
}

onMounted(() => {
  wireKernel()
  wireNetwork()

  cursorPruneTimer = window.setInterval(() => {
    const now = Date.now()
    let changed = false
    // 淡出由 RemoteCursorLayer 负责（2s 稳定 + 0.8s 淡出），这里只做兜底回收
    for (const [actor, cursor] of cursorMap) {
      if (now - cursor.at > 15000) {
        cursorMap.delete(actor)
        changed = true
      }
    }
    if (changed) flushCursors()
  }, 3000)

  cleanups.push(() => window.removeEventListener('keydown', handleKeydown))
  window.addEventListener('keydown', handleKeydown)

  void bootstrap()
})

onBeforeUnmount(() => {
  for (const cleanup of cleanups) cleanup()
  cleanups.length = 0
  client.dispose()
  if (cursorPruneTimer !== null) {
    window.clearInterval(cursorPruneTimer)
    cursorPruneTimer = null
  }
  if (toastTimer !== null) {
    window.clearTimeout(toastTimer)
    toastTimer = null
  }
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar topbar--board">
      <div class="topbar__left">
        <button class="btn btn--ghost btn--xs board-back" type="button" @click="goDraw">
          ← 作品
        </button>

        <div class="brand brand--board">
          <div class="brand__mark" aria-hidden="true">画</div>
          <div class="brand__text">
            <div class="board-title-row">
              <input
                v-if="editingName"
                ref="nameInputRef"
                v-model="nameDraft"
                class="board-title-input"
                maxlength="50"
                :disabled="renamingBoard"
                @keydown.enter.prevent="commitRename"
                @keydown.esc.prevent="cancelRename"
                @blur="commitRename"
              />
              <h1
                v-else
                class="brand__title board-title"
                :title="canRename ? '点击改名' : '只有房主可以改名'"
                @click="startRename"
              >
                {{ boardName }}
              </h1>
              <button
                v-if="!editingName && canRename"
                class="icon-btn icon-btn--tiny"
                type="button"
                title="改名"
                aria-label="改名"
                @click="startRename"
              >
                ✎
              </button>
            </div>
            <div class="board-subline">
              <span
                class="save-state"
                :class="{ 'save-state--empty': !lastSavedAt }"
                aria-live="polite"
              >
                {{ saveLabel }}
              </span>
              <span v-if="isGuest" class="badge badge--guest">游客</span>
              <span v-if="renamingBoard" class="board-subline__hint">保存中…</span>
            </div>
          </div>
        </div>
      </div>

      <div class="topbar__right">
        <div v-if="onlineMembers.length > 0" class="members">
          <span class="members__label">在线 {{ onlineMembers.length }}</span>
          <div class="members__list">
            <span
              v-for="member in visibleMembers"
              :key="member.id"
              class="avatar"
              :title="`${member.name} · ${roleLabel(member.role)}`"
            >
              {{ initialOf(member.name) }}
            </span>
            <span v-if="onlineMembers.length > visibleMembers.length" class="avatar avatar--more">
              +{{ onlineMembers.length - visibleMembers.length }}
            </span>
          </div>
        </div>

        <button
          class="btn btn--xs board-thumb"
          type="button"
          title="查看版本历史 / 存为版本 / 回滚"
          @click="openVersions"
        >
          版本
        </button>

        <button
          class="btn btn--xs board-thumb"
          type="button"
          title="复制邀请链接，拿到链接的登录用户可进房协作"
          @click="openInvite"
        >
          邀请
        </button>

        <button
          class="btn btn--xs board-thumb"
          type="button"
          :disabled="uploadingThumbnail"
          :title="isGuest ? '游客登录后才能上传缩略图' : '用当前画布生成 512px 缩略图'"
          @click="handleUploadThumbnail"
        >
          {{ uploadingThumbnail ? '上传中…' : '上传缩略图' }}
        </button>

        <button class="icon-btn app-icon-btn" type="button" title="设置（远端光标外观）" aria-label="设置" @click="settingsOpen = true">
          ⚙
        </button>

        <div class="conn" :class="`conn--${status}`" :title="statusDetail || statusText">
          <span class="conn__dot" aria-hidden="true" />
          <span class="conn__text">{{ statusText }}</span>
          <button
            v-if="status === 'offline' || status === 'closed'"
            class="btn btn--ghost btn--xs"
            type="button"
            @click="retryConnect"
          >
            重试连接
          </button>
        </div>
      </div>
    </header>

    <div class="workspace">
      <Toolbar
        :tool="tool"
        :color="color"
        :width="width"
        :can-undo="canUndo"
        :can-clear="canClear"
        :page-index="pageIndex"
        :page-count="pageTotal"
        @update:tool="tool = $event"
        @update:color="color = $event"
        @update:width="width = $event"
        @undo="handleUndo"
        @clear="handleClear"
        @add-page="handleAddPage"
        @prev-page="goPrevPage"
        @next-page="goNextPage"
      />

      <main class="stage">
        <BoardCanvas
          ref="canvasRef"
          :kernel="kernel"
          :page-id="activePageId"
          :tool="tool"
          :color="color"
          :width="width"
          :cursors="cursors"
          :read-only="readOnly"
          @stroke-append="handleStrokeAppend"
          @stroke-end="handleStrokeEnd"
          @cursor="handleCursor"
        />

        <!-- 你画我猜浮层已移除（docs/07 §4）：游戏入口只在 /home，V2 前不占画布空间 -->
        <div v-if="archived" class="archive-banner">
          <span class="archive-banner__dot" aria-hidden="true" />
          <span>作品已归档，只读：可以查看 / 回滚 / 复制，不能继续绘制</span>
          <button class="btn btn--xs" type="button" @click="openVersions">版本</button>
        </div>

        <div
          v-if="showOfflineBanner"
          class="offline-banner"
          :class="{ 'offline-banner--below': archived }"
        >
          <span class="offline-banner__dot" aria-hidden="true" />
          <span>离线模式：服务器未连接，绘制仅保存在本页内存</span>
          <button class="btn btn--xs" type="button" @click="retryConnect">重试</button>
        </div>

        <transition name="toast">
          <div v-if="toast" class="toast" :class="`toast--${toast.kind}`">{{ toast.text }}</div>
        </transition>
      </main>
    </div>

    <footer class="statusbar">
      <div class="statusbar__side">
        <span class="pill">{{ boardId }}</span>
        <span>{{ nickname }}</span>
        <span v-if="isGuest" class="badge badge--guest">游客</span>
      </div>
      <div class="statusbar__center">
        <span>{{ activePageName }}</span>
        <span class="dot-sep">·</span>
        <span>{{ elementCount }} 个元素</span>
        <span class="dot-sep">·</span>
        <span>{{ pages.length }} 页</span>
      </div>
      <div class="statusbar__side">
        <span>第 {{ pageIndex + 1 }} / {{ pageTotal }} 页</span>
        <span class="dot-sep">·</span>
        <span>{{ statusText }}</span>
      </div>
    </footer>

    <!-- 版本历史抽屉：/board 内可直接存为版本 / 回滚，回滚后自动重连刷新画布 -->
    <VersionDrawer
      v-model:open="versionOpen"
      :board-id="boardId"
      :board-name="boardName"
      :can-restore="canRestoreVersion"
      :can-create="canCreateVersion"
      :disabled-reason="
        archived
          ? '作品已归档，只读：不能存档 / 回滚'
          : isGuest
            ? '游客不能存档 / 回滚，请先注册或登录'
            : '当前角色不能存档'
      "
      @restored="handleVersionRestored"
      @notice="handleVersionNotice"
    />

    <SettingsDrawer v-model:open="settingsOpen" />

    <Teleport to="body">
      <div v-if="inviteOpen" class="modal-backdrop" @click.self="closeInvite">
        <div class="modal" role="dialog" aria-modal="true" aria-labelledby="board-invite-title">
          <h2 id="board-invite-title">邀请链接</h2>
          <p class="modal__desc">
            把链接发给同学，登录后打开即可进入「{{ boardName }}」协作（链接即分享）。
          </p>
          <div class="invite-row">
            <input
              class="input invite-input"
              type="text"
              readonly
              :value="inviteUrl"
              @focus="selectInvite"
            />
            <button class="btn btn--primary" type="button" @click="copyInvite">复制</button>
          </div>
          <div class="modal__actions">
            <button class="btn" type="button" @click="closeInvite">关闭</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>
