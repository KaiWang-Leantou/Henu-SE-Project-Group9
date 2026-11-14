/**
 * api.ts — REST 客户端：统一 Bearer、解析 {code,message,data}、401 清会话并跳登录
 *
 * 所有 /api 请求都走这里；后端不可达时抛 ApiError(network=true)，
 * 页面据此展示「无法连接服务器，请确认后端已启动」，不会白屏。
 */
import { router } from '../router'
import { clearSession, getToken } from './storage'

/** docs/05 §0 的统一返回信封 */
export interface ApiEnvelope<T> {
  code: string
  message: string
  data: T
}

/** 可读的接口错误；network=true 表示没拿到合法 HTTP/JSON 响应 */
export class ApiError extends Error {
  readonly code: string
  readonly status: number
  readonly network: boolean

  constructor(code: string, message: string, status = 0, network = false) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.network = network
  }
}

/** 登录 / 注册 / 游客请求本身返回 401 时只展示 message，不触发「过期跳登录」 */
const AUTH_ATTEMPT_PATHS = new Set(['/api/auth/login', '/api/auth/register', '/api/auth/guest'])

const STATUS_FALLBACK_MESSAGE: Record<number, string> = {
  400: '请求参数不合法',
  401: '登录已过期，请重新登录',
  403: '没有权限执行该操作',
  404: '资源不存在',
  409: '请求冲突，请稍后重试',
  500: '服务端异常，请稍后重试',
}

function pathnameOf(path: string): string {
  const index = path.indexOf('?')
  return index >= 0 ? path.slice(0, index) : path
}

/** 401 时清 token 并跳登录；redirect 保留当前页面，登录后可回来 */
function redirectToLogin(): void {
  if (router.currentRoute.value.path === '/login') return
  const current = router.currentRoute.value.fullPath
  void router.replace({
    path: '/login',
    query: current && current !== '/' ? { redirect: current } : {},
  })
}

function fallbackMessage(status: number): string {
  if (STATUS_FALLBACK_MESSAGE[status]) return STATUS_FALLBACK_MESSAGE[status]
  if (status >= 500) return '服务端异常，请稍后重试'
  return '请求失败，请稍后重试'
}

/**
 * 发起一次 /api 请求并返回 envelope.data。
 *
 * - 自动带 Authorization: Bearer <token>
 * - HTTP 401 且不是登录/注册/游客请求：清会话 → 跳 /login
 * - 网络错误 / 非 JSON 响应：抛 ApiError(network=true)
 */
export async function apiFetch<T = unknown>(
  path: string,
  init: RequestInit = {},
): Promise<T> {
  const headers = new Headers(init.headers)
  const token = getToken()
  if (token && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  if (
    init.body !== undefined &&
    init.body !== null &&
    !(init.body instanceof FormData) &&
    !headers.has('Content-Type')
  ) {
    headers.set('Content-Type', 'application/json')
  }

  let response: Response
  try {
    response = await fetch(path, { ...init, headers })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(
      'NETWORK_ERROR',
      '无法连接服务器，请确认后端已启动',
      0,
      true,
    )
  }

  let text = ''
  try {
    text = await response.text()
  } catch {
    throw new ApiError('NETWORK_ERROR', '读取服务器响应失败，请确认后端已启动', 0, true)
  }

  // 204 无正文（docs/05 §2.4 改名允许 204）
  if (response.status === 204) {
    return undefined as T
  }

  // 网关 / 开发代理在后端未启动时通常返回 502/503/504 或空 5xx；
  // 统一按「连不上后端」处理，页面可直接展示约定文案。
  if (
    response.status === 502 ||
    response.status === 503 ||
    response.status === 504 ||
    (!response.ok && response.status >= 500 && !text.trim())
  ) {
    throw new ApiError(
      'NETWORK_ERROR',
      '无法连接服务器，请确认后端已启动',
      response.status,
      true,
    )
  }

  let payload: unknown = null
  if (text.trim()) {
    try {
      payload = JSON.parse(text)
    } catch {
      // 典型场景：后端没起，Vite / preview 把 /api 回退成了 index.html
      throw new ApiError(
        'INVALID_RESPONSE',
        '无法连接服务器，请确认后端已启动',
        response.status,
        true,
      )
    }
  }

  const envelope =
    typeof payload === 'object' && payload !== null ? (payload as Record<string, unknown>) : null
  const code = typeof envelope?.code === 'string' ? envelope.code : ''
  const message =
    typeof envelope?.message === 'string' && envelope.message.trim()
      ? envelope.message.trim()
      : fallbackMessage(response.status)

  if (response.status === 401) {
    if (!AUTH_ATTEMPT_PATHS.has(pathnameOf(path))) {
      clearSession()
      redirectToLogin()
    }
    throw new ApiError(code || 'AUTH_REQUIRED', message, 401)
  }

  if (!response.ok) {
    throw new ApiError(code || `HTTP_${response.status}`, message, response.status)
  }

  if (!envelope || typeof envelope.code !== 'string') {
    throw new ApiError(
      'INVALID_RESPONSE',
      '服务器返回了无法识别的数据，请确认后端版本',
      response.status,
      true,
    )
  }

  if (envelope.code !== 'OK') {
    throw new ApiError(
      envelope.code,
      typeof envelope.message === 'string' && envelope.message.trim()
        ? envelope.message
        : fallbackMessage(response.status),
      response.status,
    )
  }

  return envelope.data as T
}

/** 把任意异常转成可展示的中文文案 */
export function describeApiError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.network) return '无法连接服务器，请确认后端已启动'
    // docs/07 §2：归档 / 回收站板的写入会被拒绝。
    // WS 用 READ_ONLY；REST 当前实现是 403 FORBIDDEN + 中文原因，这里统一成契约文案。
    if (
      error.code === 'READ_ONLY' ||
      (error.code === 'FORBIDDEN' && /归档|回收站|只读/.test(error.message))
    ) {
      return '作品已归档，只读'
    }
    return error.message || '请求失败，请稍后重试'
  }
  if (error instanceof Error && error.message) return error.message
  return '操作失败，请稍后重试'
}

/** 是否为「只读」拒绝（REST 403 READ_ONLY / WS error.code=READ_ONLY） */
export function isReadOnlyError(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false
  return (
    error.code === 'READ_ONLY' ||
    (error.code === 'FORBIDDEN' && /归档|回收站|只读/.test(error.message))
  )
}
