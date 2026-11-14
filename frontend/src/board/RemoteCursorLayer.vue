<script setup lang="ts">
/**
 * RemoteCursorLayer.vue — 远端光标 DOM overlay（docs/07 §5）
 *
 * 为什么用 DOM 而不是画进 canvas：昵称气泡要跟随设置实时改（颜色 / 透明度 / 大小 / 是否显示）
 * 且 2s 无更新要淡出，用 CSS 更自然，也不必改动 src/canvas 渲染内核。
 *
 * 坐标链路：服务端下发世界坐标 → 与画布共享同一个 viewport → worldToScreen → CSS transform。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { RemoteCursorView } from './cursor'
import { worldToScreen } from '../canvas/viewport'
import { cursorSizePx, settings } from '../settings/settings'

const props = defineProps<{
  cursors: RemoteCursorView[]
  /** 与 BoardCanvas 内渲染器共享的视口参数：screen = world * scale + t */
  scale: number
  tx: number
  ty: number
}>()

/** 2s 内没收到新位置就淡出（docs/07 §5） */
const HOLD_MS = 2000
/** 淡出时长；淡完即从 DOM 移除以省渲染 */
const FADE_MS = 800

const now = ref(Date.now())
let ticker: number | null = null

interface RenderCursor {
  key: string
  name: string
  left: number
  top: number
  opacity: number
  color: string
  size: number
}

const visibleCursors = computed<RenderCursor[]>(() => {
  const { scale, tx, ty } = props
  const current = now.value
  const size = cursorSizePx()
  const result: RenderCursor[] = []

  for (const cursor of props.cursors) {
    // 每次位置更新都会刷新 at，因此 age 反应的是「停止移动了多久」
    const age = current - cursor.at
    const fade =
      age <= HOLD_MS ? 1 : Math.max(0, 1 - (age - HOLD_MS) / FADE_MS)
    if (fade <= 0) continue

    const point = worldToScreen({ scale, tx, ty }, cursor.x, cursor.y)
    result.push({
      key: cursor.actor,
      name: cursor.name || cursor.actor,
      left: point.x,
      top: point.y,
      opacity: settings.cursorOpacity * fade,
      color: settings.cursorColor === 'auto' ? cursor.color : settings.cursorColor,
      size,
    })
  }

  return result
})

onMounted(() => {
  // 150ms 一次足够平滑，只在有光标时才有意义
  ticker = window.setInterval(() => {
    if (props.cursors.length === 0) return
    now.value = Date.now()
  }, 150)
})

onBeforeUnmount(() => {
  if (ticker !== null) {
    window.clearInterval(ticker)
    ticker = null
  }
})

// 新位置到达时立刻刷新，避免淡出中的光标闪一下
watch(
  () => props.cursors,
  () => {
    now.value = Date.now()
  },
  { deep: true },
)
</script>

<template>
  <div class="cursor-layer" aria-hidden="true">
    <div
      v-for="cursor in visibleCursors"
      :key="cursor.key"
      class="cursor-marker"
      :style="{
        transform: `translate(${cursor.left}px, ${cursor.top}px)`,
        opacity: cursor.opacity,
        color: cursor.color,
        '--cursor-size': `${cursor.size}px`,
      }"
    >
      <span class="cursor-marker__arrow" />
      <span
        v-if="settings.showCursorName"
        class="cursor-marker__name"
        :style="{ background: cursor.color }"
      >
        {{ cursor.name }}
      </span>
    </div>
  </div>
</template>
