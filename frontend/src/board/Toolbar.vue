<script setup lang="ts">
/**
 * Toolbar.vue — 左侧工具栏：笔 / 橡皮 / 撤销 / 清空 / 加页 / 翻页，外加颜色与粗细
 *
 * 纯 UI：只往上 emit 意图，不直接操作内核。
 */
import type { InputTool } from '../canvas/input'

defineProps<{
  tool: InputTool
  color: string
  width: number
  canUndo: boolean
  canClear: boolean
  pageIndex: number
  pageCount: number
}>()

const emit = defineEmits<{
  (e: 'update:tool', tool: InputTool): void
  (e: 'update:color', color: string): void
  (e: 'update:width', width: number): void
  (e: 'undo'): void
  (e: 'clear'): void
  (e: 'add-page'): void
  (e: 'prev-page'): void
  (e: 'next-page'): void
}>()

/** 调色板：颜色值都落在 #RRGGBB，符合 docs/01 的 color 约定 */
const palette = ['#111827', '#ef4444', '#f59e0b', '#10b981', '#2563eb', '#7c3aed', '#db2777', '#6b7280']

function onWidthInput(event: Event): void {
  const target = event.target as HTMLInputElement
  emit('update:width', Number(target.value))
}
</script>

<template>
  <aside class="toolbar" aria-label="工具栏">
    <div class="toolbar__group">
      <span class="toolbar__label">工具</span>
      <button
        class="tool-btn"
        :class="{ 'is-active': tool === 'pen' }"
        type="button"
        title="画笔（左键绘制）"
        @click="emit('update:tool', 'pen')"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M4 20l4.5-1 9.5-9.5-3.5-3.5L5 15.5 4 20z" />
          <path d="M13.5 5.5L17 9" />
        </svg>
        <span class="tool-btn__text">画笔</span>
      </button>
      <button
        class="tool-btn"
        :class="{ 'is-active': tool === 'eraser' }"
        type="button"
        title="橡皮（只擦自己的笔划）"
        @click="emit('update:tool', 'eraser')"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M12 9l4-4a2 2 0 012.8 0l2.2 2.2a2 2 0 010 2.8L17 14" />
          <path d="M4 16l7-7 5 5-4 4H6l-2-2z" />
          <path d="M8 20h13" />
        </svg>
        <span class="tool-btn__text">橡皮</span>
      </button>
    </div>

    <div class="toolbar__group">
      <span class="toolbar__label">颜色</span>
      <div class="swatches">
        <button
          v-for="item in palette"
          :key="item"
          class="swatch"
          :class="{ 'is-active': item === color }"
          :style="{ backgroundColor: item }"
          type="button"
          :title="item"
          @click="emit('update:color', item)"
        />
      </div>
    </div>

    <div class="toolbar__group">
      <span class="toolbar__label">粗细 {{ width }}</span>
      <input
        class="width-slider"
        type="range"
        min="1"
        max="24"
        step="1"
        :value="width"
        aria-label="笔划粗细"
        @input="onWidthInput"
      />
    </div>

    <div class="toolbar__divider" />

    <div class="toolbar__group">
      <span class="toolbar__label">操作</span>
      <button
        class="tool-btn"
        type="button"
        title="撤销（Ctrl+Z，只撤自己的笔划）"
        :disabled="!canUndo"
        @click="emit('undo')"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M9 14L4 9l5-5" />
          <path d="M4 9h9a6 6 0 010 12H8" />
        </svg>
        <span class="tool-btn__text">撤销</span>
      </button>
      <button
        class="tool-btn"
        type="button"
        title="清空当前页（房主权限）"
        :disabled="!canClear"
        @click="emit('clear')"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M4 7h16" />
          <path d="M9 7V4h6v3" />
          <path d="M6 7l1 13h10l1-13" />
          <path d="M10 11v5M14 11v5" />
        </svg>
        <span class="tool-btn__text">清空</span>
      </button>
    </div>

    <div class="toolbar__divider" />

    <div class="toolbar__group">
      <span class="toolbar__label">页面</span>
      <button class="tool-btn" type="button" title="新增一页" @click="emit('add-page')">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M12 5v14M5 12h14" />
        </svg>
        <span class="tool-btn__text">加页</span>
      </button>
      <div class="page-nav">
        <button
          class="tool-btn tool-btn--compact"
          type="button"
          title="上一页"
          :disabled="pageIndex <= 0"
          @click="emit('prev-page')"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M15 6l-6 6 6 6" />
          </svg>
        </button>
        <span class="page-nav__text">{{ pageIndex + 1 }}/{{ pageCount }}</span>
        <button
          class="tool-btn tool-btn--compact"
          type="button"
          title="下一页"
          :disabled="pageIndex >= pageCount - 1"
          @click="emit('next-page')"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M9 6l6 6-6 6" />
          </svg>
        </button>
      </div>
    </div>
  </aside>
</template>
