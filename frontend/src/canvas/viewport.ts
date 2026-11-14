/**
 * viewport.ts — 视口数学：世界坐标 ⇄ 屏幕坐标、缩放、平移
 *
 * 纯数学模块，不碰 DOM。约定：
 *   screen = world * scale + (tx, ty)
 *   world  = (screen - t) / scale
 * 任何持久化/网络数据都不含屏幕坐标（docs/01 §1）。
 */

export interface Viewport {
  /** 缩放系数，1 = 100% */
  scale: number
  /** 屏幕 x 平移（CSS px） */
  tx: number
  /** 屏幕 y 平移（CSS px） */
  ty: number
}

export interface Point {
  x: number
  y: number
}

export const MIN_SCALE = 0.1
export const MAX_SCALE = 8

/** 滚轮 delta → 缩放指数的灵敏度（越小越跟手） */
const ZOOM_SPEED = 0.0015

export function createViewport(partial: Partial<Viewport> = {}): Viewport {
  return {
    scale: clampScale(partial.scale ?? 1),
    tx: partial.tx ?? 0,
    ty: partial.ty ?? 0,
  }
}

export function clampScale(scale: number): number {
  if (!Number.isFinite(scale)) return 1
  return Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale))
}

/** 屏幕坐标（canvas 左上角为原点）→ 世界坐标 */
export function screenToWorld(viewport: Viewport, sx: number, sy: number): Point {
  return {
    x: (sx - viewport.tx) / viewport.scale,
    y: (sy - viewport.ty) / viewport.scale,
  }
}

/** 世界坐标 → 屏幕坐标（canvas 左上角为原点） */
export function worldToScreen(viewport: Viewport, wx: number, wy: number): Point {
  return {
    x: wx * viewport.scale + viewport.tx,
    y: wy * viewport.scale + viewport.ty,
  }
}

/**
 * 以屏幕点 (sx, sy) 为锚点缩放。
 * @param delta 滚轮事件的 deltaY（正数 = 缩小，负数 = 放大）
 * @returns 缩放后的 scale
 */
export function zoomAt(viewport: Viewport, sx: number, sy: number, delta: number): number {
  const anchor = screenToWorld(viewport, sx, sy)
  const factor = Math.exp(-delta * ZOOM_SPEED)
  const nextScale = clampScale(viewport.scale * factor)
  viewport.scale = nextScale
  // 保持锚点下的世界坐标不动：screen = world * scale + t  ⇒  t = screen - world * scale
  viewport.tx = sx - anchor.x * nextScale
  viewport.ty = sy - anchor.y * nextScale
  return nextScale
}

/** 屏幕像素位移平移（拖拽画布） */
export function panBy(viewport: Viewport, dx: number, dy: number): void {
  viewport.tx += dx
  viewport.ty += dy
}

/** 重置到初始视图 */
export function resetViewport(viewport: Viewport): void {
  viewport.scale = 1
  viewport.tx = 0
  viewport.ty = 0
}
