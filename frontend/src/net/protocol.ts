/**
 * protocol.ts — WebSocket 消息类型：严格对应 docs/02-通信协议.md（protocolVersion = 1）
 *
 * 消息公共字段：t（类型）、cid（需要回执时必带）、ts（客户端时间戳，仅调试）。
 * 未知字段服务端会忽略；客户端遇到未知 S→C 类型也忽略（向后兼容，docs/02 §11）。
 */
import type { BoardCommand, Doc } from '../kernel/types'

/** 协议版本（docs/00 版本表） */
export const PROTOCOL_VERSION = 1

/** 命令通道：board / game 共用一条连接，靠 ch 区分 */
export type Channel = 'board' | 'game'

/** 房间角色 */
export type Role = 'owner' | 'editor' | 'viewer'

/** 游戏模式（V2 预留） */
export type GameMode = 'classic' | 'relay'

/** 成员信息（init / presence 的 me、members 元素） */
export interface Member {
  id: string
  name: string
  role: Role
  online: boolean
  /** docs/05 §3：init.me 增加 guest 字段；老服务端可缺省 */
  guest?: boolean
  /** docs/07 §3：服务端按 userId 哈希从 12 色板分配，稳定不变；老服务端可缺省 */
  color?: string
}

/**
 * game 命令（V2 预留，V1 服务端只回 NOT_IMPLEMENTED）。
 * 与 board 命令共用 op 通道。
 */
export type GameCommand =
  | { t: 'game.start'; mode: GameMode; rounds: number; seconds: number }
  | { t: 'game.guess'; text: string }
  | { t: 'game.skip' }
  | { t: 'game.next' }
  | { t: 'game.stop' }

/** 游戏状态快照（docs/02 §4 game.state 的字段） */
export interface GameSnapshot {
  state: string
  round: number
  drawerId: string | null
  endsAt: number | null
  mode: GameMode
}

// ---------------------------------------------------------------------------
// 客户端 → 服务端（docs/02 §3）
// ---------------------------------------------------------------------------

/** join：加入 / 重连；lastSeq 为已应用的最大序号，首次传 0 */
export interface JoinC2S {
  t: 'join'
  boardId: string
  lastSeq: number
  token?: string
  nickname?: string
  ts?: number
}

/** op：提交一个命令 */
export interface OpC2S {
  t: 'op'
  cid: string
  ch: Channel
  cmd: BoardCommand | GameCommand
  ts?: number
}

/** stroke.append：绘制中的增量点，高频（40–60ms 一批），不分配 seq、不落库 */
export interface StrokeAppendC2S {
  t: 'stroke.append'
  pageId: string
  strokeId: string
  tool: 'pen'
  color: string
  width: number
  /** 扁平数组 [x1,y1,x2,y2,...]；单帧 ≤ 512 点 */
  pts: number[]
  ts?: number
}

/** stroke.end：抬笔，服务端据此落定为一条 stroke.add；需要 ack，故带 cid */
export interface StrokeEndC2S {
  t: 'stroke.end'
  cid: string
  strokeId: string
  ts?: number
}

/** cursor：世界坐标光标，前端 50ms 节流 */
export interface CursorC2S {
  t: 'cursor'
  x: number
  y: number
  ts?: number
}

/** ping：心跳，客户端每 25s 一次 */
export interface PingC2S {
  t: 'ping'
  ts?: number
}

export type C2SMessage =
  | JoinC2S
  | OpC2S
  | StrokeAppendC2S
  | StrokeEndC2S
  | CursorC2S
  | PingC2S

// ---------------------------------------------------------------------------
// 服务端 → 客户端（docs/02 §4）
// ---------------------------------------------------------------------------

/** init：首次加入或全量重同步 */
export interface InitS2C {
  t: 'init'
  boardId: string
  seq: number
  doc: Doc
  me: Member
  members: Member[]
  game?: GameSnapshot
  /** docs/05 §3：最近一次快照落库时间，用于顶栏「已自动保存 HH:mm:ss」 */
  savedAt?: string | null
}

/** saved：快照落库后通知（docs/04 §9），前端只更新「已自动保存」时间 */
export interface SavedS2C {
  t: 'saved'
  seq: number
  at: string
}

/** op：已定序的广播（自己的命令也会收到） */
export interface OpS2C {
  t: 'op'
  seq: number
  actor: string
  ch: Channel
  cmd: BoardCommand | GameCommand
}

/** ack：命令已被接受并分配序号 */
export interface AckS2C {
  t: 'ack'
  cid: string
  seq: number
}

/** stroke.append：他人绘制中的增量 */
export interface StrokeAppendS2C {
  t: 'stroke.append'
  actor: string
  pageId: string
  strokeId: string
  tool: 'pen'
  color: string
  width: number
  pts: number[]
}

/** stroke.end：他人抬笔，随后会收到对应的 op:stroke.add */
export interface StrokeEndS2C {
  t: 'stroke.end'
  actor: string
  strokeId: string
  seq: number
}

/** cursor：他人光标 */
export interface CursorS2C {
  t: 'cursor'
  actor: string
  x: number
  y: number
}

/** presence：成员变化时全量下发，不做增量 diff */
export interface PresenceS2C {
  t: 'presence'
  members: Member[]
}

/**
 * board.reload：回滚 / 归档 / 回收站恢复后，服务端要求客户端丢弃未 ack 命令并全量重同步
 * （docs/07 §3；也可直接复用 init）。
 */
export interface BoardReloadS2C {
  t: 'board.reload'
  seq: number
  reason?: string
}

/** error：错误码见 docs/02 §7 */
export interface ErrorS2C {
  t: 'error'
  cid?: string
  code: ErrorCode
  msg: string
}

/** pong：心跳回执 */
export interface PongS2C {
  t: 'pong'
}

/** game.state：V2 预留 */
export interface GameStateS2C extends GameSnapshot {
  t: 'game.state'
}

/** game.word：V2 预留，只发给当前画手 */
export interface GameWordS2C {
  t: 'game.word'
  word: string
}

export type S2CMessage =
  | InitS2C
  | SavedS2C
  | OpS2C
  | AckS2C
  | StrokeAppendS2C
  | StrokeEndS2C
  | CursorS2C
  | PresenceS2C
  | BoardReloadS2C
  | ErrorS2C
  | PongS2C
  | GameStateS2C
  | GameWordS2C

/** 错误码（docs/02 §7） */
export type ErrorCode =
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'BAD_REQUEST'
  | 'NOT_FOUND'
  | 'SEQ_GAP'
  | 'RATE_LIMITED'
  | 'FRAME_TOO_LARGE'
  | 'ROOM_FULL'
  | 'INTERNAL'
  | 'NOT_IMPLEMENTED'
  /** docs/04 §9：拒绝写入时新增 */
  | 'AUTH_REQUIRED'
  | 'READ_ONLY'

/** 已知的服务端消息类型，用于解码时校验 */
const S2C_TYPES: ReadonlySet<string> = new Set([
  'init',
  'saved',
  'op',
  'ack',
  'stroke.append',
  'stroke.end',
  'cursor',
  'presence',
  'board.reload',
  'error',
  'pong',
  'game.state',
  'game.word',
])

/** 编码：统一 JSON 文本帧 */
export function encodeC2S(message: C2SMessage): string {
  return JSON.stringify(message)
}

/**
 * 解码服务端帧。
 * @returns 已知且结构可用的消息；无法识别（坏 JSON / 未知 t / 缺关键字段）时返回 null，
 *          由调用方记日志后忽略——协议只保证「新增字段/新增类型可被旧客户端忽略」。
 */
export function decodeS2C(raw: string): S2CMessage | null {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return null
  }
  if (typeof parsed !== 'object' || parsed === null) return null

  const record = parsed as Record<string, unknown>
  if (typeof record.t !== 'string' || !S2C_TYPES.has(record.t)) return null

  // 关键消息做最小字段校验，避免半截数据把内核带崩
  if (record.t === 'init') {
    if (typeof record.seq !== 'number') return null
    if (typeof record.doc !== 'object' || record.doc === null) return null
    if (typeof record.me !== 'object' || record.me === null) return null
    if (!Array.isArray(record.members)) return null
  } else if (record.t === 'saved') {
    if (typeof record.seq !== 'number' || typeof record.at !== 'string') return null
  } else if (record.t === 'op') {
    if (typeof record.seq !== 'number' || typeof record.actor !== 'string') return null
    if (typeof record.cmd !== 'object' || record.cmd === null) return null
  } else if (record.t === 'ack') {
    if (typeof record.cid !== 'string' || typeof record.seq !== 'number') return null
  } else if (record.t === 'board.reload') {
    if (typeof record.seq !== 'number') return null
  }

  return parsed as S2CMessage
}
