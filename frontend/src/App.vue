<script setup lang="ts">
/**
 * App.vue — 应用外壳：只负责挂载路由视图（画布页已拆到 views/BoardView.vue）
 */
import { RouterView } from 'vue-router'
</script>

<template>
  <RouterView v-slot="{ Component, route }">
    <component :is="Component" :key="route.path" />
  </RouterView>
</template>
