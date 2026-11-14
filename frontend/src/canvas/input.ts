/**
 * input.ts — 指针输入层：笔划采样、批量上报、橡皮命中、滚轮缩放与拖拽平移
 *
 * 纯 TS（不 import vue）。输入层只产生「意图」，是否发网络由外部回调决定：
 *   - 每 50ms 通过 onPoints 回调交出一批增量点（对应 stroke.append）
 *   - 抬笔通过 onStrokeEnd 交出完整 stroke（外部再 dispatch stroke.add）
 *   - 橡皮通过 onErase 交出被命中的笔划 id（外部再 dispatch stroke.erase）
 */
import { strokeId as newStrokeId } from '../kernel/ids'
import type { Doc, Page, Stroke } from '../kernel/types'
import type { StrokePreview } from './renderer'
import { panBy, screenToWorld, zoomAt, type Viewport } from './viewport'

export type InputTool = 'pen' | 'eraser'

/** 增量点上报间隔（docs/02 §9 要求 40–60ms 一批） */
export const STROKE_BATCH_MS = 50
/** 单笔点数上限（docs/01 §3） */
export const MAX_STROKE_POINTS = 4000
/** 橡皮半径（屏幕像素，命中时换算成世界坐标） */
export const ERASER_RADIUS_SCREEN_PX = 12

export interface PointerStrokeMeta {
  pageId: string
  strokeId: string
  tool: 'pen'
  color: string
  width: number
}

export interface CanvasInputOptions {
  /** 接收 pointer 事件的画布（顶层 canvas） */
  canvas: HTMLCanvasElement
  /** 与渲染器共享的视口对象（原地修改） */
  viewport: Viewport
  getDoc(): Doc
  getPageId(): string
  getAuthor(): string
  getTool(): InputTool
  getStyle(): { color: string; width: number }
  /** 只读模式（viewer 角色）下不产生任何本地操作 */
  isReadonly?(): boolean
  /** 绘制中的笔划预览，传 null 表示结束 */
  onPreview(preview: StrokePreview | null): void
  /** 每 50ms 一批增量点 */
  onPoints(meta: PointerStrokeMeta, points: number[]): void
  /** 抬笔：完整笔划 */
  onStrokeEnd(meta: PointerStrokeMeta, stroke: Stroke): void
  /** 橡皮命中（只包含自己的笔划） */
  onErase(pageId: string, ids: string[]): void
  /** 世界坐标光标（节流由网络层负责） */
  onCursor(x: number, y: number): void
  /** 缩放 / 平移后请求重绘 */
  onViewportChange(): void
  onDirty(): void
}

export class CanvasInput {
  private readonly opts: CanvasInputOptions
  private drawing = false
  private panning = false
  private erasing = false
  private pointerId: number | null = null
  private meta: PointerStrokeMeta | null = null
  private preview: StrokePreview | null = null
  private points: number[] = []
  private pending: number[] = []
  private batchTimer: number | null = null
  private readonly erased = new Set<string>()
  private lastEraseAt = 0
  private lastPanX = 0
  private lastPanY = 0
  private destroyed = false

  constructor(options: CanvasInputOptions) {
    this.opts = options
    const canvas = options.canvas
    canvas.addEventListener('pointerdown', this.handlePointerDown)
    canvas.addEventListener('pointermove', this.handlePointerMove)
    canvas.addEventListener('pointerup', this.handlePointerUp)
    canvas.addEventListener('pointercancel', this.handlePointerCancel)
    canvas.addEventListener('lostpointercapture', this.handleLostCapture)
    canvas.addEventListener('wheel', this.handleWheel, { passive: false })
    canvas.addEventListener('contextmenu', this.handleContextMenu)
  }

  destroy(): void {
    if (this.destroyed) return
    this.destroyed = true
    this.stopBatchTimer()
    const canvas = this.opts.canvas
    canvas.removeEventListener('pointerdown', this.handlePointerDown)
    canvas.removeEventListener('pointermove', this.handlePointerMove)
    canvas.removeEventListener('pointerup', this.handlePointerUp)
    canvas.removeEventListener('pointercancel', this.handlePointerCancel)
    canvas.removeEventListener('lostpointercapture', this.handleLostCapture)
    canvas.removeEventListener('wheel', this.handleWheel)
    canvas.removeEventListener('contextmenu', this.handleContextMenu)
    this.cancelStroke()
  }

  // -------------------------------------------------------------------------
  // 指针事件
  // -------------------------------------------------------------------------

  private readonly handlePointerDown = (event: PointerEvent): void => {
    if (this.destroyed) return
    if (this.opts.isReadonly?.()) return

    // 中键 / 右键：拖拽平移（不进入绘制）
    if (event.button === 1 || event.button === 2) {
      event.preventDefault()
      this.startPan(event)
      return
    }
    if (event.button !== 0) return
    event.preventDefault()

    if (this.opts.getTool() === 'eraser') {
      this.startErase(event)
    } else {
      this.startStroke(event)
    }
  }

  private readonly handlePointerMove = (event: PointerEvent): void => {
    if (this.destroyed) return

    if (this.panning) {
      panBy(this.opts.viewport, event.clientX - this.lastPanX, event.clientY - this.lastPanY)
      this.lastPanX = event.clientX
      this.lastPanY = event.clientY
      this.opts.onViewportChange()
      this.opts.onDirty()
      return
    }

    const world = this.toWorld(event)

    if (this.drawing) {
      // 合帧事件能拿到两次 rAF 之间的所有采样点，显著提升笔迹密度与平滑度
      const samples = coalescedEvents(event)
      for (const sample of samples) {
        const point = this.toWorld(sample)
        this.appendPoint(point.x, point.y)
      }
      if (this.preview) {
        this.preview.updatedAt = Date.now()
        // preview 与 this.points 共享同一个数组，渲染层直接读到最新点
        this.opts.onPreview(this.preview)
      }
    } else if (this.erasing) {
      this.eraseAt(world.x, world.y)
    }

    this.opts.onCursor(world.x, world.y)
  }

  private readonly handlePointerUp = (): void => {
    if (this.destroyed) return
    if (this.panning) {
      this.panning = false
      this.releaseCapture()
      return
    }
    if (this.drawing) {
      this.finishStroke()
      this.releaseCapture()
      return
    }
    if (this.erasing) {
      this.erasing = false
      this.erased.clear()
      this.releaseCapture()
    }
  }

  private readonly handlePointerCancel = (): void => {
    if (this.destroyed) return
    if (this.drawing) {
      // 取消：已经发出的 stroke.append 不会落定（服务端只认 stroke.end）
      this.cancelStroke()
    }
    this.panning = false
    this.erasing = false
    this.erased.clear()
    this.pointerId = null
  }

  private readonly handleLostCapture = (): void => {
    if (this.destroyed) return
    if (this.drawing) this.cancelStroke()
    this.panning = false
    this.erasing = false
  }

  private readonly handleContextMenu = (event: MouseEvent): void => {
    // 右键用于拖拽平移，屏蔽上下文菜单
    event.preventDefault()
  }

  private readonly handleWheel = (event: WheelEvent): void => {
    if (this.destroyed) return
    event.preventDefault()
    const screen = this.toScreen(event.clientX, event.clientY)

    let delta = event.deltaY
    if (event.deltaMode === 1) delta *= 16
    else if (event.deltaMode === 2) delta *= 100

    zoomAt(this.opts.viewport, screen.x, screen.y, delta)
    this.opts.onViewportChange()
    this.opts.onDirty()
  }

  // -------------------------------------------------------------------------
  // 笔划
  // -------------------------------------------------------------------------

  private startStroke(event: PointerEvent): void {
    const world = this.toWorld(event)
    const style = this.opts.getStyle()
    const meta: PointerStrokeMeta = {
      pageId: this.opts.getPageId(),
      strokeId: newStrokeId(),
      tool: 'pen',
      color: style.color,
      width: Math.min(64, Math.max(1, style.width)),
    }

    this.meta = meta
    this.drawing = true
    this.points = [world.x, world.y]
    this.pending = [world.x, world.y]
    this.preview = {
      strokeId: meta.strokeId,
      pageId: meta.pageId,
      author: this.opts.getAuthor(),
      tool: 'pen',
      color: meta.color,
      width: meta.width,
      points: this.points,
      updatedAt: Date.now(),
    }

    this.capture(event)
    this.opts.onPreview(this.preview)
    this.opts.onCursor(world.x, world.y)
    this.startBatchTimer()
  }

  private appendPoint(x: number, y: number): void {
    const points = this.points
    if (points.length >= MAX_STROKE_POINTS * 2) return

    // 世界坐标下 0.5px 内的重复点不采样，控制单笔体积
    if (points.length >= 2) {
      const dx = x - points[points.length - 2]
      const dy = y - points[points.length - 1]
      if (dx * dx + dy * dy < 0.25) return
    }

    points.push(x, y)
    this.pending.push(x, y)
  }

  private finishStroke(): void {
    this.stopBatchTimer()
    this.flush()

    const meta = this.meta
    const points = this.points
    if (meta && points.length >= 2) {
      const stroke: Stroke = {
        kind: 'stroke',
        id: meta.strokeId,
        author: this.opts.getAuthor(),
        createdAt: Date.now(),
        tool: 'pen',
        color: meta.color,
        width: meta.width,
        points: points.slice(),
      }
      this.meta = null
      this.points = []
      this.pending = []
      this.preview = null
      this.drawing = false
      this.opts.onPreview(null)
      this.opts.onStrokeEnd(meta, stroke)
      return
    }

    this.cancelStroke()
  }

  private cancelStroke(): void {
    this.stopBatchTimer()
    this.drawing = false
    this.meta = null
    this.points = []
    this.pending = []
    this.erasing = false
    this.erased.clear()
    if (this.preview) {
      this.preview = null
      this.opts.onPreview(null)
    }
  }

  private readonly flush = (): void => {
    if (!this.meta || this.pending.length === 0) return
    const batch = this.pending
    this.pending = []
    this.opts.onPoints({ ...this.meta }, batch)
  }

  private startBatchTimer(): void {
    this.stopBatchTimer()
    this.batchTimer = window.setInterval(this.flush, STROKE_BATCH_MS)
  }

  private stopBatchTimer(): void {
    if (this.batchTimer !== null) {
      window.clearInterval(this.batchTimer)
      this.batchTimer = null
    }
  }

  // -------------------------------------------------------------------------
  // 橡皮
  // -------------------------------------------------------------------------

  private startErase(event: PointerEvent): void {
    this.erasing = true
    this.erased.clear()
    this.lastEraseAt = 0
    this.capture(event)
    const world = this.toWorld(event)
    this.eraseAt(world.x, world.y)
  }

  private eraseAt(worldX: number, worldY: number): void {
    const now = Date.now()
    if (now - this.lastEraseAt < 30) return
    this.lastEraseAt = now

    const pageId = this.opts.getPageId()
    const page = this.opts.getDoc().pages.find((item) => item.id === pageId)
    if (!page) return

    const radius = ERASER_RADIUS_SCREEN_PX / this.opts.viewport.scale
    const hits = hitTestStrokes(page, this.opts.getAuthor(), worldX, worldY, radius)
    const ids = hits.filter((id) => !this.erased.has(id))
    if (ids.length === 0) return

    for (const id of ids) this.erased.add(id)
    this.opts.onErase(pageId, ids)
  }

  // -------------------------------------------------------------------------
  // 平移 / 坐标系
  // -------------------------------------------------------------------------

  private startPan(event: PointerEvent): void {
    this.panning = true
    this.lastPanX = event.clientX
    this.lastPanY = event.clientY
    this.capture(event)
  }

  private capture(event: PointerEvent): void {
    this.pointerId = event.pointerId
    try {
      this.opts.canvas.setPointerCapture(event.pointerId)
    } catch {
      // 某些合成事件 / 已释放的 pointer 会抛错，忽略即可
    }
  }

  private releaseCapture(): void {
    const pointerId = this.pointerId
    this.pointerId = null
    if (pointerId === null) return
    try {
      if (this.opts.canvas.hasPointerCapture(pointerId)) {
        this.opts.canvas.releasePointerCapture(pointerId)
      }
    } catch {
      // 忽略
    }
  }

  private toScreen(clientX: number, clientY: number): { x: number; y: number } {
    const rect = this.opts.canvas.getBoundingClientRect()
    return { x: clientX - rect.left, y: clientY - rect.top }
  }

  private toWorld(event: { clientX: number; clientY: number }): { x: number; y: number } {
    const screen = this.toScreen(event.clientX, event.clientY)
    return screenToWorld(this.opts.viewport, screen.x, screen.y)
  }
}

// ---------------------------------------------------------------------------
// 命中检测（纯函数，便于单测）
// ---------------------------------------------------------------------------

/** 点到线段的最短距离 */
export function distancePointToSegment(
  px: number,
  py: number,
  x1: number,
  y1: number,
  x2: number,
  y2: number,
): number {
  const dx = x2 - x1
  const dy = y2 - y1
  const lengthSquared = dx * dx + dy * dy
  if (lengthSquared === 0) {
    return Math.hypot(px - x1, py - y1)
  }
  // 投影参数夹到 [0, 1]
  let t = ((px - x1) * dx + (py - y1) * dy) / lengthSquared
  t = Math.max(0, Math.min(1, t))
  return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy))
}

/**
 * 找出命中点附近的笔划 id。
 * 只匹配 author 自己的笔划：橡皮不能删别人的（docs/02 §5）。
 */
export function hitTestStrokes(
  page: Page,
  author: string,
  worldX: number,
  worldY: number,
  radius: number,
): string[] {
  const hits: string[] = []
  for (const element of page.elements) {
    if (element.kind !== 'stroke' || element.author !== author) continue
    const points = element.points
    const threshold = radius + element.width / 2
    const count = Math.floor(points.length / 2)

    if (count === 1) {
      if (Math.hypot(worldX - points[0], worldY - points[1]) <= threshold) {
        hits.push(element.id)
      }
      continue
    }

    for (let i = 0; i < count - 1; i += 1) {
      const distance = distancePointToSegment(
        worldX,
        worldY,
        points[i * 2],
        points[i * 2 + 1],
        points[(i + 1) * 2],
        points[(i + 1) * 2 + 1],
      )
      if (distance <= threshold) {
        hits.push(element.id)
        break
      }
    }
  }
  return hits
}

/** getCoalescedEvents 兜底：老浏览器 / 合成事件返回空数组时退回当前事件 */
function coalescedEvents(event: PointerEvent): PointerEvent[] {
  if (typeof event.getCoalescedEvents === 'function') {
    const events = event.getCoalescedEvents()
    if (events.length > 0) return events
  }
  return [event]
}
