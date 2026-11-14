/**
 * session.ts — 登录态：登录 / 注册 / 游客 / 升级 / 登出 + ensureGuest
 *
 * 所有成功响应都会把 {token,user} 写入 localStorage（wb.token / wb.user）；
 * 页面只通过本模块判断登录态，避免各自解析存储格式。
 */
import { apiFetch } from './api'
import {
  clearSession,
  getToken,
  getUser,
  normalizeUser,
  saveSession,
  type SessionUser,
} from './storage'

export type { SessionUser } from './storage'

/** docs/05 §1：认证接口统一返回 {token,user} */
interface AuthPayload {
  token: string
  user: unknown
}

export interface RegisterInput {
  username: string
  password: string
  nickname: string
}

export interface GuestInput {
  nickname?: string
}

let pendingGuest: Promise<SessionUser> | null = null

/** 当前是否持有 token（游客 token 也算） */
export function isLoggedIn(): boolean {
  return getToken() !== null
}

/** 当前用户是否为游客 */
export function isGuest(): boolean {
  return getUser()?.guest === true
}

export function currentUser(): SessionUser | null {
  return getUser()
}

export function token(): string | null {
  return getToken()
}

function persist(payload: AuthPayload): SessionUser {
  const user = normalizeUser(payload.user)
  if (!user) {
    throw new Error('服务器返回的用户信息不完整，请稍后重试')
  }
  if (typeof payload.token !== 'string' || !payload.token) {
    throw new Error('服务器返回的 token 不合法，请稍后重试')
  }
  saveSession(payload.token, user)
  return user
}

/** 登录；失败时后端 message 会由 apiFetch 抛出（INVALID_CREDENTIALS 等） */
export async function login(username: string, password: string): Promise<SessionUser> {
  const payload = await apiFetch<AuthPayload>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  })
  return persist(payload)
}

/** 注册；成功即写入登录态（调用方直接进 /home） */
export async function register(input: RegisterInput): Promise<SessionUser> {
  const payload = await apiFetch<AuthPayload>('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify(input),
  })
  return persist(payload)
}

/** 创建游客会话；nickname 可省略，由后端生成 */
export async function guest(nickname?: string): Promise<SessionUser> {
  const body: GuestInput = nickname ? { nickname } : {}
  const payload = await apiFetch<AuthPayload>('/api/auth/guest', {
    method: 'POST',
    body: JSON.stringify(body),
  })
  return persist(payload)
}

/** 游客升级：保留原 user.id，返回新的正式 token（docs/05 §1.4） */
export async function upgrade(input: RegisterInput): Promise<SessionUser> {
  const payload = await apiFetch<AuthPayload>('/api/auth/upgrade', {
    method: 'POST',
    body: JSON.stringify(input),
  })
  return persist(payload)
}

/**
 * 确保有可用会话：已有 token 直接返回本地 user；没有则创建游客。
 * 并发调用共享同一个 Promise，避免一次进场建出多个游客。
 */
export function ensureGuest(nickname?: string): Promise<SessionUser> {
  const existingToken = getToken()
  const existingUser = getUser()
  if (existingToken && existingUser) return Promise.resolve(existingUser)

  if (!pendingGuest) {
    pendingGuest = guest(nickname).finally(() => {
      pendingGuest = null
    })
  }
  return pendingGuest
}

/**
 * 登出：立即丢弃本地 token（docs/04 §3.2 允许 V1 仅前端丢弃），
 * 再尽力通知后端；通知失败不影响登出。
 */
export function logout(): void {
  const currentToken = getToken()
  clearSession()
  if (!currentToken) return
  try {
    void fetch('/api/auth/logout', {
      method: 'POST',
      headers: { Authorization: `Bearer ${currentToken}` },
    }).catch(() => {
      // 后端未实现 / 未启动时忽略
    })
  } catch {
    // 忽略
  }
}

/** 用 token 校验并刷新本地用户（docs/05 §5 建议启动时调用；失败由调用方决定是否降级） */
export async function fetchMe(): Promise<SessionUser> {
  const raw = await apiFetch<unknown>('/api/auth/me')
  const user = normalizeUser(raw)
  if (!user) throw new Error('服务器返回的用户信息不完整')
  const currentToken = getToken()
  if (currentToken) saveSession(currentToken, user)
  return user
}
