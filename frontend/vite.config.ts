import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 前端开发服务器：/api 与 /ws 都代理到本地后端（docs/02 §1 约定 WS 路径为 /ws）
const backendProxy = {
  '/api': {
    target: 'http://localhost:8080',
    changeOrigin: true,
  },
  '/ws': {
    target: 'ws://localhost:8080',
    ws: true,
  },
}

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: backendProxy,
  },
  // preview 也代理到同一后端，便于 build 后用 preview 联调 REST / WS
  preview: {
    port: 4173,
    strictPort: true,
    proxy: backendProxy,
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
})
