/**
 * settings.ts — 本机视图设置（远端光标外观 / 深色模式）
 *
 * 契约：docs/07 §6「设置抽屉」。所有设置只影响本机视图，绝不同步到服务器；
 * 存储键固定为 `wb.settings.*`，刷新后保持。
 *
 * 这是全局单例：任意组件 `import { settings }` 即可读写；
 * 写入后由模块级 watch 自动落盘并应用主题，调用方不需要关心持久化。
 */
import { reactive, watch } from 'vue'

/** 光标大小档位（docs/07 §5：12 / 16 / 20 px） */
export type CursorSize = 'small' | 'medium' | 'large'

/** 主题（docs/07 §6 标为可选项） */
export type ThemeMode = 'light' | 'dark'

export interface AppSettings {
  /** 'auto' = 使用服务端 members[].color；否则为 #RRGGBB */
  cursorColor: string
  /** 0.2 ~ 1.0 */
  cursorOpacity: number
  cursorSize: CursorSize
  showCursorName: boolean
  theme: ThemeMode
}

/** docs/07 §6：自动 / 12 色板。色板同时用作「服务端没下发 color」时的兜底分配 */
export const CURSOR_PALETTE: readonly string[] = [
  '#ef4444',
  '#f97316',
  '#f59e0b',
  '#84cc16',
  '#22c55e',
  '#10b981',
  '#06b6d4',
  '#3b82f6',
  '#6366f1',
  '#8b5cf6',
  '#ec4899',
  '#64748b',
]

/** 光标大小 → 像素（docs/07 §5） */
export const CURSOR_SIZE_PX: Record<CursorSize, number> = {
  small: 12,
  medium: 16,
  large: 20,
}

export const CURSOR_SIZE_OPTIONS: ReadonlyArray<{ value: CursorSize; label: string }> = [
  { value: 'small', label: '小' },
  { value: 'medium', label: '中' },
  { value: 'large', label: '大' },
]

export const MIN_CURSOR_OPACITY = 0.2
export const MAX_CURSOR_OPACITY = 1

export const DEFAULT_SETTINGS: Readonly<AppSettings> = Object.freeze({
  cursorColor: 'auto',
  cursorOpacity: 0.85,
  cursorSize: 'medium',
  showCursorName: true,
  theme: 'light',
})

/** docs/07 §6 规定的存储键 */
export const SETTINGS_KEYS = {
  cursorColor: 'wb.settings.cursorColor',
  cursorOpacity: 'wb.settings.cursorOpacity',
  cursorSize: 'wb.settings.cursorSize',
  showCursorName: 'wb.settings.showCursorName',
  theme: 'wb.settings.theme',
} as const

function getLocalStorage(): Storage | null {
  try {
    // 隐私模式下 localStorage 可能抛错：此时退化为仅内存设置
    return typeof window === 'undefined' ? null : window.localStorage
  } catch {
    return null
  }
}

function readRaw(key: string): string | null {
  try {
    return getLocalStorage()?.getItem(key) ?? null
  } catch {
    return null
  }
}

function writeRaw(key: string, value: string): void {
  try {
    getLocalStorage()?.setItem(key, value)
  } catch {
    // 忽略：写不进去也不影响本次会话
  }
}

function isHexColor(value: string): boolean {
  return /^#[0-9a-fA-F]{6}$/.test(value)
}

function normalizeColor(raw: string | null): string {
  if (!raw) return DEFAULT_SETTINGS.cursorColor
  if (raw === 'auto') return 'auto'
  return isHexColor(raw) ? raw.toLowerCase() : DEFAULT_SETTINGS.cursorColor
}

export function clampCursorOpacity(value: number): number {
  if (!Number.isFinite(value)) return DEFAULT_SETTINGS.cursorOpacity
  return Math.min(MAX_CURSOR_OPACITY, Math.max(MIN_CURSOR_OPACITY, value))
}

function normalizeOpacity(raw: string | null): number {
  if (raw === null || raw.trim() === '') return DEFAULT_SETTINGS.cursorOpacity
  return clampCursorOpacity(Number.parseFloat(raw))
}

function normalizeSize(raw: string | null): CursorSize {
  return raw === 'small' || raw === 'medium' || raw === 'large'
    ? raw
    : DEFAULT_SETTINGS.cursorSize
}

function normalizeBool(raw: string | null, fallback: boolean): boolean {
  if (raw === 'true' || raw === '1') return true
  if (raw === 'false' || raw === '0') return false
  return fallback
}

function normalizeTheme(raw: string | null): ThemeMode {
  return raw === 'dark' ? 'dark' : 'light'
}

function loadSettings(): AppSettings {
  return {
    cursorColor: normalizeColor(readRaw(SETTINGS_KEYS.cursorColor)),
    cursorOpacity: normalizeOpacity(readRaw(SETTINGS_KEYS.cursorOpacity)),
    cursorSize: normalizeSize(readRaw(SETTINGS_KEYS.cursorSize)),
    showCursorName: normalizeBool(
      readRaw(SETTINGS_KEYS.showCursorName),
      DEFAULT_SETTINGS.showCursorName,
    ),
    theme: normalizeTheme(readRaw(SETTINGS_KEYS.theme)),
  }
}

/** 全局唯一的本机设置对象（直接改字段即可，watch 会负责落盘） */
export const settings = reactive<AppSettings>(loadSettings())

/** 把主题写到 <html data-theme>，CSS 变量随之切换 */
export function applyTheme(theme: ThemeMode): void {
  if (typeof document === 'undefined') return
  document.documentElement.dataset.theme = theme
}

function persist(): void {
  writeRaw(SETTINGS_KEYS.cursorColor, settings.cursorColor)
  writeRaw(SETTINGS_KEYS.cursorOpacity, `${settings.cursorOpacity}`)
  writeRaw(SETTINGS_KEYS.cursorSize, settings.cursorSize)
  writeRaw(SETTINGS_KEYS.showCursorName, settings.showCursorName ? 'true' : 'false')
  writeRaw(SETTINGS_KEYS.theme, settings.theme)
}

// 模块加载即应用一次主题：避免刷新后先闪一下浅色
applyTheme(settings.theme)

watch(
  () => settings.theme,
  (theme) => applyTheme(theme),
)

watch(settings, persist, { deep: true })

/** 恢复默认（docs/07 §6 全部选项） */
export function resetSettings(): void {
  settings.cursorColor = DEFAULT_SETTINGS.cursorColor
  settings.cursorOpacity = DEFAULT_SETTINGS.cursorOpacity
  settings.cursorSize = DEFAULT_SETTINGS.cursorSize
  settings.showCursorName = DEFAULT_SETTINGS.showCursorName
  settings.theme = DEFAULT_SETTINGS.theme
}

/** 光标大小 → 像素 */
export function cursorSizePx(size: CursorSize = settings.cursorSize): number {
  return CURSOR_SIZE_PX[size] ?? CURSOR_SIZE_PX.medium
}
