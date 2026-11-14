/**
 * storage.ts — 会话持久化：token / user 统一读写 localStorage（wb.token / wb.user）
 *
 * 单独成层是为了让 api.ts 与 router.ts 都能使用，而不产生模块循环依赖。
 */

/** 与 docs/05 §1 对齐的用户结构；username 游客为 null */
export interface SessionUser {
  id: string
  username: string | null
  nickname: string
  guest: boolean
}

export const TOKEN_KEY = 'wb.token'
export const USER_KEY = 'wb.user'

function getLocalStorage(): Storage | null {
  try {
    return window.localStorage
  } catch {
    // 隐私模式 / 禁用 localStorage：退化为仅内存会话
    return null
  }
}

export function getToken(): string | null {
  try {
    const token = getLocalStorage()?.getItem(TOKEN_KEY)
    return token && token.trim() ? token : null
  } catch {
    return null
  }
}

export function hasToken(): boolean {
  return getToken() !== null
}

/**
 * 把后端返回的 user 规整成前端结构。
 * docs/04 用过 isGuest，docs/05 用 guest；两种都接受，避免契约小差异导致登录态丢失。
 */
export function normalizeUser(raw: unknown): SessionUser | null {
  if (typeof raw !== 'object' || raw === null) return null
  const record = raw as Record<string, unknown>
  const id = typeof record.id === 'string' ? record.id : ''
  if (!id) return null

  const nickname =
    typeof record.nickname === 'string' && record.nickname.trim()
      ? record.nickname.trim()
      : '用户'
  const username =
    typeof record.username === 'string' && record.username.trim() ? record.username.trim() : null
  const guest =
    typeof record.guest === 'boolean'
      ? record.guest
      : typeof record.isGuest === 'boolean'
        ? record.isGuest
        : username === null

  return { id, username, nickname, guest }
}

export function getUser(): SessionUser | null {
  try {
    const raw = getLocalStorage()?.getItem(USER_KEY)
    if (!raw) return null
    return normalizeUser(JSON.parse(raw))
  } catch {
    return null
  }
}

export function saveSession(token: string, user: SessionUser): void {
  try {
    const storage = getLocalStorage()
    if (!storage) return
    storage.setItem(TOKEN_KEY, token)
    storage.setItem(USER_KEY, JSON.stringify(user))
  } catch {
    // 写入失败时本次会话仍保存在内存变量中，不影响当前页面
  }
}

export function clearSession(): void {
  try {
    const storage = getLocalStorage()
    if (!storage) return
    storage.removeItem(TOKEN_KEY)
    storage.removeItem(USER_KEY)
  } catch {
    // 忽略
  }
}
