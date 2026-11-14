/**
 * cursor.ts — 远端光标的视图模型（纯类型 + 兜底配色）
 *
 * 世界坐标由服务端 `cursor {x,y}` 提供（docs/07 §5）；颜色优先取
 * `members[].color`（服务端按 userId 哈希分配，稳定不变），缺失时用本地兜底色。
 */
import { CURSOR_PALETTE } from '../settings/settings'

/** 画布 overlay 渲染一条远端光标所需的全部信息 */
export interface RemoteCursorView {
  /** 成员 id（服务端 actor） */
  actor: string
  /** 昵称气泡文字 */
  name: string
  /** 世界坐标（与 canvas 视图无关，渲染时再做 world→screen 变换） */
  x: number
  y: number
  /** 本地收到该位置的时间戳，用于「2s 未更新自动淡出」 */
  at: number
  /** #RRGGBB；'auto' 时由设置层决定是否覆盖 */
  color: string
}

/** 服务端没给 color 时的兜底：按 actor 稳定散列到 12 色板 */
export function fallbackCursorColor(actor: string): string {
  let hash = 0
  for (let i = 0; i < actor.length; i += 1) {
    hash = (hash * 31 + actor.charCodeAt(i)) % 100000
  }
  return CURSOR_PALETTE[hash % CURSOR_PALETTE.length]
}
