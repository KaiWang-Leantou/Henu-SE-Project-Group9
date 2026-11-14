/**
 * bus.ts — 极简类型安全事件总线
 *
 * kernel 与 net 都用它对外广播事件，Vue 只订阅、不反向依赖，保证内核与框架解耦。
 */

export type Listener<T> = (payload: T) => void

export class Emitter<Events extends object> {
  private readonly listeners = new Map<keyof Events, Set<Listener<unknown>>>()

  /** 订阅事件，返回取消订阅函数 */
  on<K extends keyof Events>(type: K, listener: Listener<Events[K]>): () => void {
    let group = this.listeners.get(type)
    if (!group) {
      group = new Set<Listener<unknown>>()
      this.listeners.set(type, group)
    }
    group.add(listener as unknown as Listener<unknown>)
    return () => this.off(type, listener)
  }

  /** 只触发一次 */
  once<K extends keyof Events>(type: K, listener: Listener<Events[K]>): () => void {
    const wrapped: Listener<Events[K]> = (payload) => {
      this.off(type, wrapped)
      listener(payload)
    }
    return this.on(type, wrapped)
  }

  off<K extends keyof Events>(type: K, listener: Listener<Events[K]>): void {
    const group = this.listeners.get(type)
    if (!group) return
    group.delete(listener as unknown as Listener<unknown>)
    if (group.size === 0) this.listeners.delete(type)
  }

  /** 广播；遍历副本，允许监听器在回调里取消订阅 */
  emit<K extends keyof Events>(type: K, payload: Events[K]): void {
    const group = this.listeners.get(type)
    if (!group || group.size === 0) return
    for (const listener of [...group]) {
      ;(listener as Listener<Events[K]>)(payload)
    }
  }

  listenerCount<K extends keyof Events>(type: K): number {
    return this.listeners.get(type)?.size ?? 0
  }

  /** 清空某个事件或全部事件 */
  clear(type?: keyof Events): void {
    if (type === undefined) {
      this.listeners.clear()
      return
    }
    this.listeners.delete(type)
  }
}
