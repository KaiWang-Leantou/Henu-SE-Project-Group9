<script setup lang="ts">
/**
 * AppToast.vue — 全局提示条（fixed 定位，不占布局）
 */
import type { ToastState } from './useToast'

defineProps<{
  toast: ToastState | null
}>()
</script>

<template>
  <Teleport to="body">
    <transition name="toast">
      <div v-if="toast" class="works-toast" :class="`works-toast--${toast.kind}`" role="status">
        {{ toast.text }}
      </div>
    </transition>
  </Teleport>
</template>
