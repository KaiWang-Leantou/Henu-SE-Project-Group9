/**
 * clipboard.ts — 复制文本到剪贴板（邀请链接等）
 *
 * 优先用 navigator.clipboard（需要 HTTPS / localhost）；局域网 http 访问时
 * 自动退回到 textarea + execCommand，保证演示机也能复制。
 */
export async function copyText(text: string): Promise<boolean> {
  if (!text) return false

  try {
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 权限被拒 / 非安全上下文：走兜底
  }

  if (typeof document === 'undefined') return false

  try {
    const area = document.createElement('textarea')
    area.value = text
    area.setAttribute('readonly', 'readonly')
    area.style.position = 'fixed'
    area.style.top = '-1000px'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    const copied = document.execCommand('copy')
    document.body.removeChild(area)
    return copied
  } catch {
    return false
  }
}

/** 当前站点的白板邀请链接（链接即分享，docs/05 §4） */
export function boardInviteUrl(boardId: string): string {
  const origin =
    typeof window === 'undefined' || !window.location
      ? ''
      : `${window.location.protocol}//${window.location.host}`
  return `${origin}/board/${encodeURIComponent(boardId)}`
}
