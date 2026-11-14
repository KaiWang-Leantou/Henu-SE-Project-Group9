/**
 * time.ts — 时间展示：相对时间（3 分钟前）与 HH:mm:ss（自动保存提示）
 */

/** 解析后端 ISO 时间；兼容 "2026-11-13 12:31:05" 这类 MySQL 格式 */
export function parseTime(value?: string | null): Date | null {
  if (!value || typeof value !== 'string') return null
  const normalized = value.includes('T') ? value : value.replace(' ', 'T')
  const date = new Date(normalized)
  return Number.isNaN(date.getTime()) ? null : date
}

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前 / 具体日期 */
export function formatRelativeTime(value?: string | null): string {
  const date = parseTime(value)
  if (!date) return '尚未保存'
  const diff = Date.now() - date.getTime()
  if (diff < 0) return '刚刚'

  const seconds = Math.floor(diff / 1000)
  if (seconds < 60) return '刚刚'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days} 天前`

  const year = date.getFullYear()
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return year === new Date().getFullYear() ? `${month}-${day}` : `${year}-${month}-${day}`
}

function pad(value: number): string {
  return `${value}`.padStart(2, '0')
}

/** HH:mm:ss；时间非法时返回占位 */
export function formatClock(value?: string | null): string {
  const date = parseTime(value) ?? (value ? null : new Date())
  if (!date) return '--:--:--'
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** YYYY-MM-DD HH:mm；用于 title 悬浮提示 */
export function formatDateTime(value?: string | null): string {
  const date = parseTime(value)
  if (!date) return '—'
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`
}
