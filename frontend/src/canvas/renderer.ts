/**
 * renderer.ts — Canvas 渲染层：双层 canvas + rAF 合帧 + DPR 适配 + 世界坐标变换
 *
 * 底层（base）：已落定元素（stroke / shape / text）
 * 顶层（overlay）：当前笔划 + 他人绘制中的笔划 + 他人光标
 *
 * 纯 TS：不 import vue。UI 只负责调用 drawDoc / setLiveStroke 等方法。
 */
import type { Doc, Element, Page, Shape, Stroke, Text } from '../kernel/types'
import { worldToScreen, type Viewport } from './viewport'

/** 绘制中的笔划预览（本地当前笔划 / 他人增量笔划） */
export interface StrokePreview {
  strokeId: string
  pageId: string
  author: string
  tool: 'pen'
  color: string
  width: number
  /** 累积点，扁平数组 [x1,y1,x2,y2,...] */
  points: number[]
  /** 本地时间戳，用于清理超时未抬笔的远端预览 */
  updatedAt: number
}

/** 他人光标 */
export interface RemoteCursor {
  actor: string
  name: string
  /** 世界坐标 */
  x: number
  y: number
  /** 本地收到的时间戳，用于过期清理 */
  at: number
}

/** 远端预览的超时时间：超过这个时间没有新的 stroke.append 就丢弃（防止残影） */
const REMOTE_PREVIEW_TTL = 8000
/** 最大 DPR，避免 4K 屏下像素缓冲过大 */
const MAX_DPR = 3

export class CanvasRenderer {
  private readonly base: HTMLCanvasElement
  private readonly overlay: HTMLCanvasElement
  private readonly baseCtx: CanvasRenderingContext2D
  private readonly overlayCtx: CanvasRenderingContext2D
  private viewport: Viewport
  private doc: Doc | null = null
  private activePageId = ''
  private liveStroke: StrokePreview | null = null
  private readonly remoteStrokes = new Map<string, StrokePreview>()
  private cursors: RemoteCursor[] = []
  private dpr = 1
  private cssWidth = 0
  private cssHeight = 0
  private frameHandle = 0
  private baseDirty = true
  private destroyed = false

  constructor(base: HTMLCanvasElement, overlay: HTMLCanvasElement, viewport: Viewport) {
    this.base = base
    this.overlay = overlay
    this.viewport = viewport

    const baseCtx = base.getContext('2d')
    const overlayCtx = overlay.getContext('2d')
    if (!baseCtx || !overlayCtx) {
      throw new Error('当前浏览器不支持 Canvas 2D')
    }
    this.baseCtx = baseCtx
    this.overlayCtx = overlayCtx
  }

  /** 当前页 id；drawDoc 只画这一页 */
  setActivePage(pageId: string): void {
    if (this.activePageId === pageId) return
    this.activePageId = pageId
    this.markDirty()
  }

  /** 全量重绘请求：记录文档与视口引用，下一帧绘制 */
  drawDoc(doc: Doc, viewport: Viewport): void {
    this.doc = doc
    this.viewport = viewport
    this.markDirty()
  }

  /** 本地当前笔划；传 null 清除 */
  setLiveStroke(preview: StrokePreview | null): void {
    this.liveStroke = preview
    this.markDirty()
  }

  /** 他人绘制中的笔划（增量追加后调用） */
  upsertRemoteStroke(preview: StrokePreview): void {
    this.remoteStrokes.set(preview.strokeId, preview)
    this.markDirty()
  }

  /** 他人抬笔 / 超时：移除预览，随后会有 stroke.add 落到文档 */
  removeRemoteStroke(strokeId: string): void {
    if (this.remoteStrokes.delete(strokeId)) {
      this.markDirty()
    }
  }

  setCursors(cursors: RemoteCursor[]): void {
    this.cursors = cursors
    this.markDirty()
  }

  markDirty(): void {
    this.baseDirty = true
    this.requestFrame()
  }

  /** 合帧：一帧内多次调用只画一次 */
  requestFrame(): void {
    if (this.destroyed || this.frameHandle !== 0) return
    if (this.cssWidth === 0 || this.cssHeight === 0) return
    this.frameHandle = requestAnimationFrame(this.renderFrame)
  }

  /**
   * 按 devicePixelRatio 调整像素缓冲，并同步 CSS 尺寸变化。
   * @returns 尺寸是否变化
   */
  resize(): boolean {
    const rect = this.overlay.getBoundingClientRect()
    const width = Math.max(1, Math.round(rect.width || this.overlay.clientWidth))
    const height = Math.max(1, Math.round(rect.height || this.overlay.clientHeight))
    const dpr = Math.min(MAX_DPR, Math.max(1, window.devicePixelRatio || 1))

    if (width === this.cssWidth && height === this.cssHeight && dpr === this.dpr) {
      return false
    }

    this.cssWidth = width
    this.cssHeight = height
    this.dpr = dpr

    for (const canvas of [this.base, this.overlay]) {
      canvas.width = Math.max(1, Math.round(width * dpr))
      canvas.height = Math.max(1, Math.round(height * dpr))
    }

    this.markDirty()
    this.requestFrame()
    return true
  }

  destroy(): void {
    this.destroyed = true
    if (this.frameHandle !== 0) {
      cancelAnimationFrame(this.frameHandle)
      this.frameHandle = 0
    }
    this.remoteStrokes.clear()
    this.cursors = []
    this.liveStroke = null
    this.doc = null
  }

  private readonly renderFrame = (): void => {
    this.frameHandle = 0
    if (this.destroyed || this.cssWidth === 0 || this.cssHeight === 0) return

    if (this.baseDirty) {
      this.drawBase()
      this.baseDirty = false
    }
    this.drawOverlay()
  }

  private activePage(): Page | null {
    if (!this.doc) return null
    return this.doc.pages.find((page) => page.id === this.activePageId) ?? null
  }

  private drawBase(): void {
    const ctx = this.baseCtx
    ctx.setTransform(1, 0, 0, 1, 0, 0)
    ctx.clearRect(0, 0, this.base.width, this.base.height)

    const page = this.activePage()
    if (!page || page.elements.length === 0) return

    this.applyWorldTransform(ctx)
    for (const element of page.elements) {
      drawElement(ctx, element)
    }
    ctx.setTransform(1, 0, 0, 1, 0, 0)
  }

  private drawOverlay(): void {
    const ctx = this.overlayCtx
    ctx.setTransform(1, 0, 0, 1, 0, 0)
    ctx.clearRect(0, 0, this.overlay.width, this.overlay.height)

    const now = Date.now()
    for (const [strokeId, preview] of this.remoteStrokes) {
      if (now - preview.updatedAt > REMOTE_PREVIEW_TTL) {
        this.remoteStrokes.delete(strokeId)
      }
    }

    this.applyWorldTransform(ctx)
    for (const preview of this.remoteStrokes.values()) {
      if (preview.pageId !== this.activePageId) continue
      drawStrokePath(ctx, preview.points, preview.color, preview.width)
    }
    if (this.liveStroke && this.liveStroke.pageId === this.activePageId) {
      drawStrokePath(ctx, this.liveStroke.points, this.liveStroke.color, this.liveStroke.width)
    }

    this.drawCursors(ctx)
  }

  private applyWorldTransform(ctx: CanvasRenderingContext2D): void {
    const { scale, tx, ty } = this.viewport
    ctx.setTransform(this.dpr * scale, 0, 0, this.dpr * scale, this.dpr * tx, this.dpr * ty)
  }

  private drawCursors(ctx: CanvasRenderingContext2D): void {
    if (this.cursors.length === 0 || !this.doc) return
    // 光标画在屏幕坐标系，避免缩放后标签变形
    ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0)
    for (const cursor of this.cursors) {
      const point = worldToScreen(this.viewport, cursor.x, cursor.y)
      if (
        point.x < -60 ||
        point.y < -60 ||
        point.x > this.cssWidth + 60 ||
        point.y > this.cssHeight + 60
      ) {
        continue
      }
      const color = cursorColor(cursor.actor)
      ctx.beginPath()
      ctx.arc(point.x, point.y, 4.5, 0, Math.PI * 2)
      ctx.fillStyle = color
      ctx.fill()
      ctx.lineWidth = 1.5
      ctx.strokeStyle = '#ffffff'
      ctx.stroke()

      const label = cursor.name || cursor.actor
      drawNamePill(ctx, label, point.x + 10, point.y + 10, color)
    }
    ctx.setTransform(1, 0, 0, 1, 0, 0)
  }
}

// ---------------------------------------------------------------------------
// 元素绘制
// ---------------------------------------------------------------------------

function drawElement(ctx: CanvasRenderingContext2D, element: Element): void {
  switch (element.kind) {
    case 'stroke':
      drawStroke(ctx, element)
      return
    case 'text':
      drawText(ctx, element)
      return
    default:
      drawShape(ctx, element)
  }
}

function drawStroke(ctx: CanvasRenderingContext2D, stroke: Stroke): void {
  drawStrokePath(ctx, stroke.points, stroke.color, stroke.width)
}

function drawStrokePath(
  ctx: CanvasRenderingContext2D,
  points: number[],
  color: string,
  width: number,
): void {
  const count = Math.floor(points.length / 2)
  if (count === 0) return

  ctx.strokeStyle = color
  ctx.fillStyle = color
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'
  ctx.lineWidth = Math.max(0.5, width)

  // 单点：画一个圆点（世界坐标下半径 = 线宽 / 2）
  if (count === 1) {
    ctx.beginPath()
    ctx.arc(points[0], points[1], Math.max(0.4, width / 2), 0, Math.PI * 2)
    ctx.fill()
    return
  }

  ctx.beginPath()
  ctx.moveTo(points[0], points[1])
  if (count === 2) {
    ctx.lineTo(points[2], points[3])
  } else {
    // 中点二次贝塞尔，笔迹更顺滑
    for (let i = 1; i < count - 1; i += 1) {
      const x = points[i * 2]
      const y = points[i * 2 + 1]
      const nextX = points[(i + 1) * 2]
      const nextY = points[(i + 1) * 2 + 1]
      ctx.quadraticCurveTo(x, y, (x + nextX) / 2, (y + nextY) / 2)
    }
    ctx.lineTo(points[(count - 1) * 2], points[(count - 1) * 2 + 1])
  }
  ctx.stroke()
}

function drawShape(ctx: CanvasRenderingContext2D, shape: Shape): void {
  const strokeWidth = Math.max(0.5, shape.strokeWidth)
  ctx.save()
  ctx.lineWidth = strokeWidth
  ctx.strokeStyle = shape.stroke
  ctx.fillStyle = shape.fill ?? 'transparent'
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'

  if (shape.rotation) {
    const cx = shape.x + shape.w / 2
    const cy = shape.y + shape.h / 2
    ctx.translate(cx, cy)
    ctx.rotate(shape.rotation)
    ctx.translate(-cx, -cy)
  }

  if (shape.kind === 'line' || shape.kind === 'arrow') {
    const startX = shape.x
    const startY = shape.y
    const endX = shape.x + shape.w
    const endY = shape.y + shape.h
    ctx.beginPath()
    ctx.moveTo(startX, startY)
    ctx.lineTo(endX, endY)
    ctx.stroke()

    if (shape.kind === 'arrow') {
      const angle = Math.atan2(endY - startY, endX - startX)
      const length = Math.hypot(endX - startX, endY - startY)
      const head = Math.min(Math.max(8, strokeWidth * 3), Math.max(length * 0.5, 0.1))
      ctx.beginPath()
      ctx.moveTo(endX, endY)
      ctx.lineTo(endX - head * Math.cos(angle - Math.PI / 7), endY - head * Math.sin(angle - Math.PI / 7))
      ctx.lineTo(endX - head * Math.cos(angle + Math.PI / 7), endY - head * Math.sin(angle + Math.PI / 7))
      ctx.closePath()
      ctx.fillStyle = shape.stroke
      ctx.fill()
    }
    ctx.restore()
    return
  }

  const x = Math.min(shape.x, shape.x + shape.w)
  const y = Math.min(shape.y, shape.y + shape.h)
  const width = Math.abs(shape.w)
  const height = Math.abs(shape.h)

  ctx.beginPath()
  if (shape.kind === 'rect') {
    ctx.rect(x, y, width, height)
  } else {
    ctx.ellipse(x + width / 2, y + height / 2, width / 2, height / 2, 0, 0, Math.PI * 2)
  }
  if (shape.fill) ctx.fill()
  ctx.stroke()
  ctx.restore()
}

function drawText(ctx: CanvasRenderingContext2D, text: Text): void {
  const fontSize = Math.max(1, text.fontSize)
  ctx.save()
  ctx.fillStyle = text.color
  ctx.font = `${fontSize}px "Microsoft YaHei", "PingFang SC", "Segoe UI", sans-serif`
  ctx.textBaseline = 'top'
  const lines = text.text.split('\n')
  lines.forEach((line, index) => {
    ctx.fillText(line, text.x, text.y + index * fontSize * 1.3)
  })
  ctx.restore()
}

// ---------------------------------------------------------------------------
// 光标与工具
// ---------------------------------------------------------------------------

function drawNamePill(
  ctx: CanvasRenderingContext2D,
  label: string,
  x: number,
  y: number,
  color: string,
): void {
  const text = label.length > 12 ? `${label.slice(0, 12)}…` : label
  ctx.font = '12px "Microsoft YaHei", "PingFang SC", "Segoe UI", sans-serif'
  const textWidth = ctx.measureText(text).width
  const paddingX = 7
  const height = 20
  const width = textWidth + paddingX * 2

  roundRect(ctx, x, y, width, height, 6)
  ctx.fillStyle = color
  ctx.fill()
  ctx.fillStyle = '#ffffff'
  ctx.textBaseline = 'middle'
  ctx.fillText(text, x + paddingX, y + height / 2 + 0.5)
}

function roundRect(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  width: number,
  height: number,
  radius: number,
): void {
  const r = Math.min(radius, width / 2, height / 2)
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.lineTo(x + width - r, y)
  ctx.arcTo(x + width, y, x + width, y + r, r)
  ctx.lineTo(x + width, y + height - r)
  ctx.arcTo(x + width, y + height, x + width - r, y + height, r)
  ctx.lineTo(x + r, y + height)
  ctx.arcTo(x, y + height, x, y + height - r, r)
  ctx.lineTo(x, y + r)
  ctx.arcTo(x, y, x + r, y, r)
  ctx.closePath()
}

/** 由 actor id 稳定映射到一个颜色，保证同一用户每次颜色一致 */
export function cursorColor(actor: string): string {
  let hash = 0
  for (let i = 0; i < actor.length; i += 1) {
    hash = (hash * 31 + actor.charCodeAt(i)) % 360
  }
  const hue = ((hash % 360) + 360) % 360
  return `hsl(${hue}, 68%, 46%)`
}
