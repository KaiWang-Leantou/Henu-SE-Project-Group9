<script setup lang="ts">
/**
 * BoardCanvas.vue — 画布外壳：组装渲染器与输入层，并把内核事件映射为重绘
 *
 * 所有 canvas 逻辑都在 src/canvas/*（纯 TS）里，本组件只负责挂载、尺寸和事件转接。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { CanvasInput, type InputTool, type PointerStrokeMeta } from '../canvas/input'
import { CanvasRenderer, type StrokePreview } from '../canvas/renderer'
import { createViewport, resetViewport, type Viewport } from '../canvas/viewport'
import type { StrokeProgressPayload, WhiteboardKernel } from '../kernel/kernel'
import type { Stroke } from '../kernel/types'
import RemoteCursorLayer from './RemoteCursorLayer.vue'
import type { RemoteCursorView } from './cursor'

const props = defineProps<{
  kernel: WhiteboardKernel
  pageId: string
  tool: InputTool
  color: string
  width: number
  /** 远端光标：DOM overlay 渲染，坐标是世界坐标（docs/07 §5） */
  cursors: RemoteCursorView[]
  readOnly: boolean
}>()

const emit = defineEmits<{
  (e: 'stroke-append', payload: {
    pageId: string
    strokeId: string
    tool: 'pen'
    color: string
    width: number
    pts: number[]
  }): void
  (e: 'stroke-end', payload: { strokeId: string }): void
  (e: 'cursor', payload: { x: number; y: number }): void
  (e: 'viewport', payload: { scale: number }): void
}>()

const hostRef = ref<HTMLDivElement | null>(null)
const baseRef = ref<HTMLCanvasElement | null>(null)
const overlayRef = ref<HTMLCanvasElement | null>(null)
const scale = ref(1)
const zoomText = computed(() => `${Math.round(scale.value * 100)}%`)

/** 与渲染器 / 输入层共享的视口对象（原地修改） */
const viewport = createViewport()

function snapshotOf(source: Viewport): { scale: number; tx: number; ty: number } {
  return { scale: source.scale, tx: source.tx, ty: source.ty }
}

/**
 * 视口快照：DOM 光标层需要响应 scale/tx/ty 变化，但视口本身是可变对象，
 * 这里在每次平移/缩放后同步一份不可变快照给 Vue。
 */
const viewState = ref(snapshotOf(viewport))

function syncViewport(): void {
  viewState.value = snapshotOf(viewport)
}
/** 他人绘制中的笔划预览，key = strokeId */
const remoteStrokes = new Map<string, StrokePreview>()
const cleanups: Array<() => void> = []

let renderer: CanvasRenderer | null = null
let input: CanvasInput | null = null
let resizeObserver: ResizeObserver | null = null
let pruneTimer: number | null = null

onMounted(() => {
  const host = hostRef.value
  const base = baseRef.value
  const overlay = overlayRef.value
  if (!host || !base || !overlay) return

  try {
    renderer = new CanvasRenderer(base, overlay, viewport)
  } catch (error) {
    console.error('[board] 初始化渲染器失败', error)
    return
  }
  renderer.setActivePage(props.pageId)
  renderer.resize()
  renderer.drawDoc(props.kernel.doc, viewport)
  syncViewport()

  input = new CanvasInput({
    canvas: overlay,
    viewport,
    getDoc: () => props.kernel.doc,
    getPageId: () => props.pageId,
    getAuthor: () => props.kernel.author,
    getTool: () => props.tool,
    getStyle: () => ({ color: props.color, width: props.width }),
    isReadonly: () => props.readOnly,
    onPreview: (preview) => {
      renderer?.setLiveStroke(preview)
      renderer?.requestFrame()
    },
    onPoints: (meta, points) => {
      // 由上层决定发不发网络：离线时 App 不会调用 RoomClient
      emit('stroke-append', {
        pageId: meta.pageId,
        strokeId: meta.strokeId,
        tool: meta.tool,
        color: meta.color,
        width: meta.width,
        pts: points,
      })
    },
    onStrokeEnd: (meta, stroke) => {
      renderer?.setLiveStroke(null)
      commitStroke(meta, stroke)
      emit('stroke-end', { strokeId: meta.strokeId })
    },
    onErase: (pageId, ids) => {
      void props.kernel
        .dispatch({ t: 'stroke.erase', pageId, ids })
        .catch((error) => console.warn('[board] 橡皮删除失败', error))
    },
    onCursor: (x, y) => emit('cursor', { x, y }),
    onViewportChange: () => {
      scale.value = viewport.scale
      syncViewport()
      emit('viewport', { scale: viewport.scale })
    },
    onDirty: () => renderer?.requestFrame(),
  })

  // 内核事件 → 重绘
  const redraw = (): void => {
    renderer?.drawDoc(props.kernel.doc, viewport)
  }
  cleanups.push(props.kernel.on('op', redraw))
  cleanups.push(props.kernel.on('pagerefresh', redraw))
  cleanups.push(
    props.kernel.on('doc', () => {
      renderer?.setActivePage(props.pageId)
      redraw()
    }),
  )
  cleanups.push(
    props.kernel.on('stroke-progress', (payload: StrokeProgressPayload) => {
      if (payload.pageId !== props.pageId) return
      let preview = remoteStrokes.get(payload.strokeId)
      if (!preview) {
        preview = {
          strokeId: payload.strokeId,
          pageId: payload.pageId,
          author: payload.author,
          tool: 'pen',
          color: payload.color,
          width: payload.width,
          points: [],
          updatedAt: Date.now(),
        }
        remoteStrokes.set(payload.strokeId, preview)
      }
      preview.points.push(...payload.points)
      preview.updatedAt = Date.now()
      renderer?.upsertRemoteStroke(preview)
      renderer?.requestFrame()
    }),
  )
  cleanups.push(
    props.kernel.on('stroke-progress-end', (payload) => {
      remoteStrokes.delete(payload.strokeId)
      renderer?.removeRemoteStroke(payload.strokeId)
      renderer?.requestFrame()
    }),
  )

  const handleResize = (): void => {
    renderer?.resize()
    renderer?.requestFrame()
  }
  resizeObserver = new ResizeObserver(handleResize)
  resizeObserver.observe(host)
  window.addEventListener('resize', handleResize)

  // 超时未抬笔的远端预览会由渲染器内部丢弃，这里周期性触发一帧完成清理
  pruneTimer = window.setInterval(() => {
    const now = Date.now()
    for (const [strokeId, preview] of remoteStrokes) {
      if (now - preview.updatedAt > 8000) {
        remoteStrokes.delete(strokeId)
        renderer?.removeRemoteStroke(strokeId)
      }
    }
    renderer?.requestFrame()
  }, 2000)

  cleanups.push(() => window.removeEventListener('resize', handleResize))
})

onBeforeUnmount(() => {
  for (const cleanup of cleanups) cleanup()
  cleanups.length = 0
  if (pruneTimer !== null) {
    window.clearInterval(pruneTimer)
    pruneTimer = null
  }
  resizeObserver?.disconnect()
  resizeObserver = null
  input?.destroy()
  input = null
  renderer?.destroy()
  renderer = null
  remoteStrokes.clear()
})

// 翻页 / 换页重绘（他人光标由 DOM 光标层自行响应，不经过 canvas）
watch(
  () => props.pageId,
  (pageId) => {
    renderer?.setActivePage(pageId)
    renderer?.drawDoc(props.kernel.doc, viewport)
  },
)

/** 本地乐观应用：笔划走 stroke.append / stroke.end 通道，所以这里只落本地 */
function commitStroke(meta: PointerStrokeMeta, stroke: Stroke): void {
  void props.kernel
    .dispatch({ t: 'stroke.add', pageId: meta.pageId, stroke }, { localOnly: true })
    .catch((error) => console.warn('[board] 笔划落本地失败', error))
}

function onResetView(): void {
  resetViewport(viewport)
  scale.value = viewport.scale
  syncViewport()
  renderer?.markDirty()
  renderer?.requestFrame()
  emit('viewport', { scale: viewport.scale })
}

/**
 * 导出当前文档画布为缩略图：长边压到 512px、webp 质量 0.6（docs/04 §7）。
 * 只读取渲染器的底图画布，不改动 kernel / canvas 逻辑；白底避免透明缩略图发灰。
 */
function exportThumbnail(maxEdge = 512, quality = 0.6): string | null {
  const source = baseRef.value
  if (!source || source.width === 0 || source.height === 0) return null

  const longest = Math.max(source.width, source.height)
  const scaleRatio = Math.min(1, maxEdge / longest)
  const width = Math.max(1, Math.round(source.width * scaleRatio))
  const height = Math.max(1, Math.round(source.height * scaleRatio))

  const target = document.createElement('canvas')
  target.width = width
  target.height = height
  const context = target.getContext('2d')
  if (!context) return null

  context.fillStyle = '#ffffff'
  context.fillRect(0, 0, width, height)
  context.drawImage(source, 0, 0, width, height)

  try {
    return target.toDataURL('image/webp', quality)
  } catch {
    // 个别浏览器对 webp 编码有限制；返回 null 由上层提示
    return null
  }
}

defineExpose({ exportThumbnail })
</script>

<template>
  <div ref="hostRef" class="board" :class="`board--${tool}`">
    <canvas ref="baseRef" class="board__layer board__layer--base" />
    <canvas ref="overlayRef" class="board__layer board__layer--overlay" />

    <!-- 远端光标层：位于两块 canvas 之上、HUD 之下，纯 DOM + CSS 实现（docs/07 §5） -->
    <RemoteCursorLayer
      :cursors="cursors"
      :scale="viewState.scale"
      :tx="viewState.tx"
      :ty="viewState.ty"
    />

    <div class="board__hud">
      <span class="board__zoom">{{ zoomText }}</span>
      <button class="btn btn--ghost btn--xs" type="button" @click="onResetView">重置视图</button>
    </div>

    <p class="board__hint">滚轮缩放 · 中键 / 右键拖拽平移 · Ctrl+Z 撤销</p>
    <p v-if="readOnly" class="board__readonly">只读模式：当前角色不能绘制</p>
  </div>
</template>
