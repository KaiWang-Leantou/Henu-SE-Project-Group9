/**
 * policy.ts — 写权限策略接缝
 *
 * 白板不知道游戏（docs/00 三条约定之三）：游戏部件通过实现 Policy 来收紧/放开写权限，
 * 例如「只有当前画手能画」「猜词者只读」「游戏结束后锁定本页」。
 *
 * 注意：
 * 1. Policy 只在客户端生效，是「体验层守卫」，服务端仍会做最终权限校验（docs/02 §5）；
 * 2. 被拒绝的命令不应本地应用，也不应发网络——kernel.dispatch 会在应用前调用 canApply；
 * 3. V1 没有游戏，默认 allowAllPolicy，卸载游戏后行为不变。
 */
import type { BoardCommand } from './types'

export interface Policy {
  /**
   * 是否允许应用这条命令。
   * @param cmd 待应用的 board 命令
   * @param pageId 命令影响到的页面（board.reset 没有页面，传 undefined）
   */
  canApply(cmd: BoardCommand, pageId?: string): boolean
}

/** 默认策略：本机用户拥有全部权限（服务端仍会校验） */
export const allowAllPolicy: Policy = {
  canApply: () => true,
}

/** 只读策略：viewer 角色或游戏旁观者使用 */
export const readonlyPolicy: Policy = {
  canApply: () => false,
}

/** 组合多个策略：全部允许才允许（用于「游戏策略 + 房间角色」叠加） */
export function combinePolicies(...policies: Policy[]): Policy {
  if (policies.length === 0) return allowAllPolicy
  return {
    canApply(cmd, pageId) {
      return policies.every((policy) => policy.canApply(cmd, pageId))
    },
  }
}
