/**
 * register.ts — 游戏部件挂载点约定 + 注册函数
 *
 * 白板不知道游戏（docs/00 约定之三）：
 *   - UI 从「挂载点」接入：宿主页渲染一个空的浮层容器 `#wb-game-mount`；
 *   - 写权限从 Policy 接入：V2 用 createGamePolicy() 限制只有当前画手能画。
 *
 * V1 / Sprint B 现状（docs/07 §4）：`BoardView` **不再** 渲染挂载点、也不再调用
 * registerGameWidget()，画布页右侧不再常驻「你画我猜」浮层；
 * 「你画我猜」入口只在 /home（跳到 /guess 占位页）。本文件保留给 V2 复用。
 */
import { createApp, h, type App as VueApp } from 'vue'
import type { WhiteboardKernel } from '../../kernel/kernel'
import { allowAllPolicy, type Policy } from '../../kernel/policy'
import GamePanel from './GamePanel.vue'

/**
 * 挂载点约定（V2 宿主页按此提供容器即可）：
 * - 宿主页渲染一个空的 `.game-layer#wb-game-mount` 容器；
 * - 游戏部件只能往这个容器里挂，不侵入工具栏 / 画布；
 * - 卸载游戏时清空容器即可，白板功能不受影响。
 */
export const mountPoint = {
  id: 'wb-game-mount',
  selector: '#wb-game-mount',
  className: 'game-layer',
  description: '画布区域右上角的空浮层容器，游戏部件挂载于此',
} as const

export interface GameWidgetHandle {
  mounted: boolean
  host: HTMLElement | null
  unmount(): void
}

interface MountedGameWidget {
  host: HTMLElement
  app: VueApp
}

/** 同一时刻只允许一个游戏部件实例 */
let mountedGameWidget: MountedGameWidget | null = null

/**
 * V2 的写权限策略示例。
 *
 * V2 游戏开始后，只有当前画手（currentDrawerId）能在游戏轮次页（page.roundId 非空）上写；
 * 猜词者 / 观众只读。V1 直接返回 allowAllPolicy，保证白板行为不变。
 * 注意：Policy 只是客户端体验层守卫，最终权限仍由服务端校验。
 */
export function createGamePolicy(currentDrawerId?: string): Policy {
  return {
    canApply(_cmd, pageId) {
      if (!currentDrawerId || !pageId) return true
      // TODO(V2): 结合 kernel.doc.pages[pageId].roundId 判断是否游戏页，
      //           再与 currentDrawerId 比对；非画手返回 false。
      return allowAllPolicy.canApply(_cmd, pageId)
    },
  }
}

/**
 * 把游戏占位面板挂到约定挂载点。
 * @param kernel 白板内核（游戏面板按需读取，不修改文档）
 */
export function registerGameWidget(kernel: WhiteboardKernel): GameWidgetHandle {
  if (typeof document === 'undefined') {
    return { mounted: false, host: null, unmount: () => undefined }
  }

  const host = document.querySelector<HTMLElement>(mountPoint.selector)
  if (!host) {
    console.warn(`[game] 未找到挂载点 ${mountPoint.selector}，游戏部件跳过挂载`)
    return { mounted: false, host: null, unmount: () => undefined }
  }

  // 重复注册时先卸载旧实例，避免同一容器里出现两个 Vue 应用
  if (mountedGameWidget) {
    mountedGameWidget.app.unmount()
    mountedGameWidget = null
  }

  const app = createApp({
    name: 'GameWidgetRoot',
    render: () => h(GamePanel, { kernel }),
  })
  app.mount(host)
  mountedGameWidget = { host, app }
  host.dataset.gameMounted = '1'

  return {
    mounted: true,
    host,
    unmount(): void {
      if (!mountedGameWidget || mountedGameWidget.host !== host) return
      mountedGameWidget.app.unmount()
      mountedGameWidget = null
      delete host.dataset.gameMounted
    },
  }
}
