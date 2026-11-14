/**
 * useToast.ts — 轻量 toast 状态（无需 store / 组件库）
 *
 * 每个页面各自持有一个实例；渲染统一交给 components/AppToast.vue。
 */
import { onBeforeUnmount, ref, type Ref } from 'vue'

export type ToastKind = 'info' | 'warn' | 'error'

export interface ToastState {
  kind: ToastKind
  text: string
}

export interface ToastApi {
  toast: Ref<ToastState | null>
  /** 展示一条提示，duration 毫秒后自动消失 */
  showToast: (text: string, kind?: ToastKind) => void
  clearToast: () => void
}

export function useToast(duration = 4000): ToastApi {
  const toast = ref<ToastState | null>(null)
  let timer: number | null = null

  function clearToast(): void {
    if (timer !== null) {
      window.clearTimeout(timer)
      timer = null
    }
    toast.value = null
  }

  function showToast(text: string, kind: ToastKind = 'info'): void {
    if (!text) return
    if (timer !== null) window.clearTimeout(timer)
    toast.value = { kind, text }
    timer = window.setTimeout(() => {
      toast.value = null
      timer = null
    }, duration)
  }

  onBeforeUnmount(clearToast)

  return { toast, showToast, clearToast }
}
