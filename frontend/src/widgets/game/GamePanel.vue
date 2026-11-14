<script setup lang="ts">
/**
 * GamePanel.vue — 「你画我猜」占位面板（V1 不实现游戏逻辑，只验证挂载点与内核接缝）
 */
import { computed } from 'vue'
import type { WhiteboardKernel } from '../../kernel/kernel'

const props = defineProps<{
  kernel?: WhiteboardKernel
}>()

const boardId = computed(() => props.kernel?.boardId ?? '未连接')
</script>

<template>
  <section class="game-panel" aria-label="你画我猜">
    <header class="game-panel__head">
      <span class="game-panel__title">你画我猜（V2）</span>
      <span class="badge">占位</span>
    </header>

    <p class="game-panel__desc">游戏部件挂载点已就绪，V1 骨架只提供占位面板。</p>

    <dl class="game-panel__rows">
      <div>
        <dt>状态</dt>
        <dd>未开始</dd>
      </div>
      <div>
        <dt>模式</dt>
        <dd>classic / relay</dd>
      </div>
      <div>
        <dt>白板</dt>
        <dd class="game-panel__mono">{{ boardId }}</dd>
      </div>
    </dl>

    <ul class="game-panel__todo">
      <li>会话状态机（V2）</li>
      <li>词库与计分（V2）</li>
      <li>画手写权限策略（V2）</li>
    </ul>

    <button class="btn btn--primary game-panel__btn" type="button" disabled>开始游戏（V2）</button>

    <p class="game-panel__note">
      白板不知道游戏：游戏逻辑通过 Policy + 本挂载点接入，卸载后白板照常运行。
    </p>
  </section>
</template>
