/**
 * ids.ts — 轻量 ID 生成器（不依赖第三方库，浏览器与 Node 均可运行）
 *
 * 前缀按 docs/01 §3：b_ 白板、p_ 页面、s_ 笔划、o_ 图形/文本、u_ 用户、c_ 客户端请求。
 */

const ALPHABET = '0123456789abcdefghijklmnopqrstuvwxyz'

function randomBytes(length: number): Uint8Array {
  const bytes = new Uint8Array(length)
  const cryptoObject = typeof globalThis === 'undefined' ? undefined : globalThis.crypto
  if (cryptoObject && typeof cryptoObject.getRandomValues === 'function') {
    cryptoObject.getRandomValues(bytes)
    return bytes
  }
  // 极老环境兜底：Math.random 不用于安全场景，仅用于 id
  for (let i = 0; i < length; i += 1) {
    bytes[i] = Math.floor(Math.random() * 256)
  }
  return bytes
}

/** 随机小写字母数字串 */
export function randomToken(length = 10): string {
  const bytes = randomBytes(length)
  let token = ''
  for (let i = 0; i < length; i += 1) {
    token += ALPHABET[bytes[i] % ALPHABET.length]
  }
  return token
}

/** 白板 id，形如 b_7f3a2c9d */
export function boardId(): string {
  return `b_${randomToken(8)}`
}

/** 页面 id，形如 p_1a2b3c4d */
export function pageId(): string {
  return `p_${randomToken(8)}`
}

/** 笔划 id，形如 s_9f21ab3c */
export function strokeId(): string {
  return `s_${randomToken(10)}`
}

/** 图形 / 文本 id，形如 o_5e6f7a8b */
export function objectId(): string {
  return `o_${randomToken(10)}`
}

/** 匿名用户 id，形如 u_2a3b4c5d */
export function userId(): string {
  return `u_${randomToken(8)}`
}

/** 客户端请求 id（cid），用于 ack 对账；时间前缀便于排查 */
export function clientCmdId(): string {
  return `c_${Date.now().toString(36)}${randomToken(6)}`
}
