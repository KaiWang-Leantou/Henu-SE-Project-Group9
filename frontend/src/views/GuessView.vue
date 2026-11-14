<script setup lang="ts">
/**
 * GuessView.vue — 「你画我猜」占位页（docs/07 §4：V2 前只提示敬请期待）
 *
 * 画布页不再挂游戏浮层；游戏入口只在 /home 卡片与这个占位页。
 */
import AppHeader from '../components/AppHeader.vue'
import SettingsDrawer from '../components/SettingsDrawer.vue'
import { ref } from 'vue'

const settingsOpen = ref(false)
</script>

<template>
  <div class="page-shell">
    <AppHeader
      title="你画我猜"
      subtitle="房间游戏 · V2 规划中"
      back-to="/home"
      back-label="主页"
      @settings="settingsOpen = true"
    />

    <main class="page-main">
      <section class="soon-card">
        <div class="soon-card__icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
            <rect x="2.5" y="7" width="19" height="10" rx="3" />
            <path d="M7 10.5v3M5.5 12h3" />
            <circle cx="16.5" cy="11" r="1" />
            <circle cx="18.8" cy="13.2" r="1" />
          </svg>
        </div>
        <h2>你画我猜 · 敬请期待</h2>
        <p>
          V2 会把游戏状态机、词库计分和「只有当前画手能画」的写权限策略接到现有画布上；
          V1 / Sprint B 期间画布页保持干净，不常驻游戏浮层。
        </p>
        <ul class="soon-card__list">
          <li>房间内轮流做画手，其余人猜词计分</li>
          <li>复用同一块白板与同一条 WebSocket 通道（ch = game）</li>
          <li>通过 Policy 收紧写权限：非画手只读</li>
        </ul>
        <div class="soon-card__actions">
          <RouterLink class="btn btn--primary" to="/home">回主页</RouterLink>
          <RouterLink class="btn" to="/draw">先去画画</RouterLink>
        </div>
      </section>
    </main>

    <SettingsDrawer v-model:open="settingsOpen" />
  </div>
</template>
