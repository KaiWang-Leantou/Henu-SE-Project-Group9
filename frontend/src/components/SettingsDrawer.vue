<script setup lang="ts">
/**
 * SettingsDrawer.vue — 右侧滑出的设置抽屉（docs/07 §6）
 *
 * 设置项：光标颜色（自动 / 12 色板）、透明度 0.2–1.0、大小、显示昵称、深色模式。
 * 写入的是全局单例 `settings`，由 settings.ts 落盘到 localStorage `wb.settings.*`；
 * 本组件不碰网络，设置只影响本机视图。
 */
import { computed, onBeforeUnmount, onMounted } from 'vue'
import {
  CURSOR_PALETTE,
  CURSOR_SIZE_OPTIONS,
  MAX_CURSOR_OPACITY,
  MIN_CURSOR_OPACITY,
  clampCursorOpacity,
  cursorSizePx,
  resetSettings,
  settings,
  type CursorSize,
} from '../settings/settings'

/** 抽屉开合由父页面控制：<SettingsDrawer v-model:open="settingsOpen" /> */
const open = defineModel<boolean>('open', { default: false })

const opacityPercent = computed(() => Math.round(settings.cursorOpacity * 100))
const previewColor = computed(() => (settings.cursorColor === 'auto' ? CURSOR_PALETTE[8] : settings.cursorColor))
const previewSize = computed(() => cursorSizePx())

function pickColor(value: string): void {
  settings.cursorColor = value
}

function setSize(size: CursorSize): void {
  settings.cursorSize = size
}

function toggleShowName(): void {
  settings.showCursorName = !settings.showCursorName
}

function toggleTheme(): void {
  settings.theme = settings.theme === 'dark' ? 'light' : 'dark'
}

function onOpacityInput(value: string): void {
  settings.cursorOpacity = clampCursorOpacity(Number.parseFloat(value))
}

function onOpacityReset(): void {
  // 双击滑杆标签：回到契约默认值 0.85
  settings.cursorOpacity = 0.85
}

function handleReset(): void {
  resetSettings()
}

function handleKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && open.value) open.value = false
}

onMounted(() => window.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', handleKeydown))
</script>

<template>
  <Teleport to="body">
    <transition name="drawer">
      <div v-if="open" class="drawer-backdrop" @click.self="open = false">
        <aside class="drawer" role="dialog" aria-modal="true" aria-label="设置">
          <header class="drawer__head">
            <div>
              <h2 class="drawer__title">设置</h2>
              <p class="drawer__sub">仅影响本机视图，不同步到服务器</p>
            </div>
            <button class="icon-btn" type="button" aria-label="关闭设置" @click="open = false">✕</button>
          </header>

          <div class="drawer__body">
            <section class="setting-group">
              <div class="setting-group__label">预览</div>
              <div class="cursor-preview" aria-hidden="true">
                <span
                  class="cursor-preview__mark"
                  :style="{
                    color: previewColor,
                    opacity: settings.cursorOpacity,
                    width: `${previewSize}px`,
                    height: `${previewSize}px`,
                  }"
                />
                <span
                  v-if="settings.showCursorName"
                  class="cursor-preview__name"
                  :style="{ background: previewColor, opacity: settings.cursorOpacity }"
                >
                  队友昵称
                </span>
              </div>
            </section>

            <section class="setting-group">
              <div class="setting-group__label">光标颜色</div>
              <div class="color-grid">
                <button
                  class="color-auto"
                  type="button"
                  :class="{ 'is-active': settings.cursorColor === 'auto' }"
                  :aria-pressed="settings.cursorColor === 'auto'"
                  title="自动：使用每位成员的服务端配色"
                  @click="pickColor('auto')"
                >
                  自动
                </button>
                <button
                  v-for="color in CURSOR_PALETTE"
                  :key="color"
                  class="color-swatch"
                  type="button"
                  :class="{ 'is-active': settings.cursorColor === color }"
                  :style="{ background: color }"
                  :title="color"
                  :aria-label="`光标颜色 ${color}`"
                  :aria-pressed="settings.cursorColor === color"
                  @click="pickColor(color)"
                />
              </div>
            </section>

            <section class="setting-group">
              <div class="setting-group__label">
                <span>光标透明度</span>
                <span class="setting-group__value" title="双击恢复默认 85%" @dblclick="onOpacityReset">
                  {{ opacityPercent }}%
                </span>
              </div>
              <input
                class="range"
                type="range"
                :min="MIN_CURSOR_OPACITY"
                :max="MAX_CURSOR_OPACITY"
                step="0.05"
                :value="settings.cursorOpacity"
                aria-label="光标透明度"
                @input="onOpacityInput(($event.target as HTMLInputElement).value)"
              />
              <p class="setting-group__hint">0.2 – 1.0，默认 85%</p>
            </section>

            <section class="setting-group">
              <div class="setting-group__label">光标大小</div>
              <div class="segmented segmented--full" role="group" aria-label="光标大小">
                <button
                  v-for="option in CURSOR_SIZE_OPTIONS"
                  :key="option.value"
                  type="button"
                  :class="{ 'is-active': settings.cursorSize === option.value }"
                  @click="setSize(option.value)"
                >
                  {{ option.label }}
                </button>
              </div>
              <p class="setting-group__hint">12 / 16 / 20 px</p>
            </section>

            <section class="setting-group">
              <div class="setting-row">
                <div>
                  <div class="setting-row__title">显示昵称</div>
                  <p class="setting-row__hint">在光标旁显示成员昵称气泡</p>
                </div>
                <button
                  class="switch"
                  type="button"
                  role="switch"
                  :aria-checked="settings.showCursorName"
                  :class="{ 'is-on': settings.showCursorName }"
                  @click="toggleShowName"
                >
                  <span class="switch__knob" />
                </button>
              </div>
            </section>

            <section class="setting-group">
              <div class="setting-row">
                <div>
                  <div class="setting-row__title">深色模式</div>
                  <p class="setting-row__hint">仅改变界面配色，画布保持白纸</p>
                </div>
                <button
                  class="switch"
                  type="button"
                  role="switch"
                  :aria-checked="settings.theme === 'dark'"
                  :class="{ 'is-on': settings.theme === 'dark' }"
                  @click="toggleTheme"
                >
                  <span class="switch__knob" />
                </button>
              </div>
            </section>
          </div>

          <footer class="drawer__foot">
            <button class="btn btn--xs" type="button" @click="handleReset">恢复默认</button>
            <button class="btn btn--primary btn--xs" type="button" @click="open = false">完成</button>
          </footer>
        </aside>
      </div>
    </transition>
  </Teleport>
</template>
