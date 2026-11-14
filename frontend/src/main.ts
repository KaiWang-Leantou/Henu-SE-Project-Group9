/**
 * main.ts — 应用入口：装配路由并挂载 Vue 外壳
 */
import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router'
import './styles.css'

createApp(App).use(router).mount('#app')
