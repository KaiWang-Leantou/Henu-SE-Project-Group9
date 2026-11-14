/**
 * boardsApi.ts — 作品库 / 版本 / 生命周期 REST
 *
 * 路径、参数与字段严格对齐 docs/05 §2 与 docs/07 §1–§2；所有请求经 auth/api.ts 统一带 token。
 * 列表 / 版本接口都在这里做一层「字段归一化」，后端字段小差异不会把页面带崩。
 */
import { apiFetch } from '../auth/api'

export type BoardRole = 'owner' | 'editor' | 'viewer'

/** docs/07 §2：列表状态过滤（trashed 必须显式传，不传时后端不返回回收站） */
export type BoardStatus = 'active' | 'draft' | 'archived' | 'trashed'

/** docs/05 §2.1 列表项 */
export interface BoardSummary {
  id: string
  name: string
  role: BoardRole
  status: string
  thumbnail: string | null
  lastSavedAt: string | null
  createdAt: string | null
}

export interface BoardListData {
  total: number
  page: number
  size: number
  items: BoardSummary[]
}

/** docs/05 §2.3 详情（这里只声明前端用到的字段） */
export interface BoardDetail {
  id: string
  name: string
  status?: string
  public?: boolean
  role: BoardRole
  owner?: { id: string; nickname: string }
  members?: Array<{ id: string; nickname: string; role: BoardRole }>
  lastSavedAt?: string | null
  thumbnail?: string | null
}

export interface BoardListQuery {
  scope: 'mine' | 'joined' | 'all'
  q?: string
  status?: BoardStatus
  page?: number
  size?: number
}

export interface CreateBoardResult {
  id: string
  name?: string
  role?: BoardRole
  status?: string
  createdAt?: string
}

/** docs/07 §1.3 版本类型 */
export type BoardVersionKind = 'AUTO' | 'MANUAL' | 'PRE_RESTORE' | 'UNKNOWN'

/** docs/07 §1.3 版本时间轴条目（已归一化） */
export interface BoardVersion {
  id: string
  versionNo: number | null
  kind: BoardVersionKind
  label: string | null
  docSeq: number | null
  authorId: string | null
  authorName: string
  createdAt: string | null
  thumbnail: string | null
}

export interface VersionListData {
  total: number
  page: number
  size: number
  items: BoardVersion[]
}

export interface CreateVersionInput {
  label?: string
  thumbnail?: string
}


function asRole(value: unknown): BoardRole {
  return value === 'owner' || value === 'editor' || value === 'viewer' ? value : 'viewer'
}

function normalizeSummary(raw: unknown): BoardSummary | null {
  if (typeof raw !== 'object' || raw === null) return null
  const record = raw as Record<string, unknown>
  if (typeof record.id !== 'string' || !record.id) return null
  return {
    id: record.id,
    name: typeof record.name === 'string' && record.name.trim() ? record.name : '未命名白板',
    role: asRole(record.role),
    // 后端数据库存大写、REST 输出小写；这里统一小写，避免 status=ARCHIVED 时页面判断失效
    status: typeof record.status === 'string' ? record.status.toLowerCase() : 'active',
    thumbnail: typeof record.thumbnail === 'string' && record.thumbnail ? record.thumbnail : null,
    lastSavedAt: typeof record.lastSavedAt === 'string' ? record.lastSavedAt : null,
    createdAt: typeof record.createdAt === 'string' ? record.createdAt : null,
  }
}

/** GET /api/boards?scope=...&status=...&q=...&page=...&size=... */
export async function listBoards(query: BoardListQuery): Promise<BoardListData> {
  const params = new URLSearchParams({
    scope: query.scope,
    status: query.status ?? 'active',
    page: `${query.page ?? 1}`,
    size: `${query.size ?? 20}`,
  })
  const keyword = query.q?.trim()
  if (keyword) params.set('q', keyword)

  const data: Partial<BoardListData> =
    (await apiFetch<Partial<BoardListData> | null>(`/api/boards?${params.toString()}`)) ?? {}
  const rawItems = Array.isArray(data.items) ? data.items : []
  const items = rawItems
    .map((item) => normalizeSummary(item))
    .filter((item): item is BoardSummary => item !== null)

  return {
    total: typeof data.total === 'number' ? data.total : items.length,
    page: typeof data.page === 'number' ? data.page : query.page ?? 1,
    size: typeof data.size === 'number' ? data.size : query.size ?? 20,
    items,
  }
}

/** POST /api/boards（游客会收到 403 FORBIDDEN，文案由后端给出） */
export async function createBoard(name: string): Promise<CreateBoardResult> {
  return apiFetch<CreateBoardResult>('/api/boards', {
    method: 'POST',
    body: JSON.stringify({ name }),
  })
}

/** GET /api/boards/{id} */
export async function getBoard(id: string): Promise<BoardDetail> {
  const data = await apiFetch<BoardDetail | null>(`/api/boards/${encodeURIComponent(id)}`)
  if (!data || typeof data !== 'object') {
    throw new Error('服务器未返回白板信息')
  }
  return { ...data, role: asRole(data.role) }
}

/** PATCH /api/boards/{id}（仅 owner） */
export async function renameBoard(id: string, name: string): Promise<void> {
  await apiFetch<null>(`/api/boards/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify({ name }),
  })
}

/** PUT /api/boards/{id}/thumbnail（EDITOR+，游客不可） */
export async function updateThumbnail(id: string, dataUrl: string): Promise<void> {
  await apiFetch<null>(`/api/boards/${encodeURIComponent(id)}/thumbnail`, {
    method: 'PUT',
    body: JSON.stringify({ dataUrl }),
  })
}

// ---------------------------------------------------------------------------
// docs/07 §2 生命周期：归档 / 回收站 / 复制
// ---------------------------------------------------------------------------

/**
 * PATCH /api/boards/{id} 归档 / 取消归档（owner）。
 * 归档后板进入只读态：REST 写接口 403、WS 写命令返回 READ_ONLY。
 */
export async function setBoardStatus(id: string, status: 'active' | 'archived'): Promise<void> {
  await apiFetch<null>(`/api/boards/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify({ status }),
  })
}

/** DELETE /api/boards/{id}：软删除进回收站（owner，30 天内可恢复） */
export async function trashBoard(id: string): Promise<void> {
  await apiFetch<null>(`/api/boards/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

/** POST /api/boards/{id}/trash/restore：从回收站恢复为 ACTIVE（owner） */
export async function restoreBoardFromTrash(id: string): Promise<void> {
  await apiFetch<null>(`/api/boards/${encodeURIComponent(id)}/trash/restore`, { method: 'POST' })
}

/**
 * POST /api/boards/{id}/duplicate：复制成新板（ACTIVE，owner = 自己）。
 * 用于「从归档作品继续联机」——归档板只读，复制出的新板才能继续画。
 */
export async function duplicateBoard(id: string): Promise<CreateBoardResult> {
  const data = await apiFetch<unknown>(`/api/boards/${encodeURIComponent(id)}/duplicate`, {
    method: 'POST',
  })
  const record = typeof data === 'object' && data !== null ? (data as Record<string, unknown>) : {}
  // 兼容 { id } 与 { board: { id } } 两种返回
  const nested =
    typeof record.board === 'object' && record.board !== null
      ? (record.board as Record<string, unknown>)
      : record
  const newId =
    typeof nested.id === 'string' && nested.id
      ? nested.id
      : typeof record.id === 'string'
        ? record.id
        : ''
  if (!newId) throw new Error('服务器未返回复制后的白板 id')
  return {
    id: newId,
    name: typeof nested.name === 'string' ? nested.name : undefined,
    role: nested.role !== undefined ? asRole(nested.role) : undefined,
    status: typeof nested.status === 'string' ? nested.status : undefined,
    createdAt: typeof nested.createdAt === 'string' ? nested.createdAt : undefined,
  }
}

// ---------------------------------------------------------------------------
// docs/07 §1 版本历史：时间轴 / 手动存档 / 回滚
// ---------------------------------------------------------------------------

function normalizeVersionKind(raw: unknown): BoardVersionKind {
  const value = typeof raw === 'string' ? raw.toUpperCase() : ''
  if (value === 'AUTO') return 'AUTO'
  if (value === 'MANUAL') return 'MANUAL'
  if (value === 'PRE_RESTORE') return 'PRE_RESTORE'
  return 'UNKNOWN'
}

/** AUTO / MANUAL / PRE_RESTORE → 中文标签（docs/07 §4.2） */
export function versionKindLabel(kind: BoardVersionKind | string): string {
  switch (normalizeVersionKind(kind)) {
    case 'AUTO':
      return '自动存档'
    case 'MANUAL':
      return '手动存档'
    case 'PRE_RESTORE':
      return '回滚前存档'
    default:
      return '历史版本'
  }
}

function normalizeAuthor(raw: unknown): { id: string | null; name: string } {
  if (typeof raw === 'string' && raw.trim()) return { id: null, name: raw.trim() }
  if (typeof raw === 'object' && raw !== null) {
    const record = raw as Record<string, unknown>
    const name =
      typeof record.nickname === 'string'
        ? record.nickname
        : typeof record.name === 'string'
          ? record.name
          : ''
    const id = typeof record.id === 'string' ? record.id : null
    return { id, name: name.trim() }
  }
  return { id: null, name: '' }
}

function normalizeVersion(raw: unknown): BoardVersion | null {
  if (typeof raw !== 'object' || raw === null) return null
  const record = raw as Record<string, unknown>
  const id = typeof record.id === 'string' ? record.id : ''
  if (!id) return null
  const author = normalizeAuthor(record.author)
  return {
    id,
    versionNo: typeof record.versionNo === 'number' ? record.versionNo : null,
    kind: normalizeVersionKind(record.kind),
    label: typeof record.label === 'string' && record.label.trim() ? record.label.trim() : null,
    docSeq: typeof record.docSeq === 'number' ? record.docSeq : null,
    authorId: author.id,
    authorName: author.name,
    createdAt: typeof record.createdAt === 'string' ? record.createdAt : null,
    thumbnail: typeof record.thumbnail === 'string' && record.thumbnail ? record.thumbnail : null,
  }
}

/**
 * GET /api/boards/{id}/versions?page=&size=（时间轴，服务端倒序）。
 * 后端未上线该接口时会抛 ApiError（404 / 405），由版本抽屉展示错误态。
 */
export async function listVersions(
  boardId: string,
  query: { page?: number; size?: number } = {},
): Promise<VersionListData> {
  const page = query.page ?? 1
  const size = query.size ?? 20
  const params = new URLSearchParams({ page: `${page}`, size: `${size}` })
  const data = await apiFetch<unknown>(
    `/api/boards/${encodeURIComponent(boardId)}/versions?${params.toString()}`,
  )

  const record = typeof data === 'object' && data !== null ? (data as Record<string, unknown>) : null
  const rawItems = Array.isArray(data)
    ? data
    : Array.isArray(record?.items)
      ? (record?.items as unknown[])
      : []
  const items = rawItems
    .map((item) => normalizeVersion(item))
    .filter((item): item is BoardVersion => item !== null)

  return {
    total: typeof record?.total === 'number' ? record.total : items.length,
    page: typeof record?.page === 'number' ? record.page : page,
    size: typeof record?.size === 'number' ? record.size : size,
    items,
  }
}

/** POST /api/boards/{id}/versions：手动存档（EDITOR+，游客不可） */
export async function createVersion(
  boardId: string,
  input: CreateVersionInput = {},
): Promise<BoardVersion | null> {
  const body: Record<string, string> = {}
  const label = input.label?.trim()
  if (label) body.label = label
  if (input.thumbnail) body.thumbnail = input.thumbnail

  const data = await apiFetch<unknown>(`/api/boards/${encodeURIComponent(boardId)}/versions`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
  const record = typeof data === 'object' && data !== null ? (data as Record<string, unknown>) : null
  const nested =
    record && typeof record.version === 'object' && record.version !== null
      ? record.version
      : record
  return normalizeVersion(nested)
}

/**
 * POST /api/boards/{id}/versions/{vid}/restore：回滚（owner）。
 * 服务端语义见 docs/07 §1.4：当前态先存 PRE_RESTORE，再覆盖 doc 并广播 init。
 */
export async function restoreVersion(boardId: string, versionId: string): Promise<void> {
  await apiFetch<unknown>(
    `/api/boards/${encodeURIComponent(boardId)}/versions/${encodeURIComponent(versionId)}/restore`,
    { method: 'POST', body: JSON.stringify({}) },
  )
}
