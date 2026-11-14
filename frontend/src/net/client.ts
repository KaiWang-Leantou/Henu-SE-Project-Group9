/**
 * client.ts — WebSocket 房间客户端
 *
 * 职责：连接 / 指数退避重连、join{lastSeq} 补序、cid 未确认队列、断线重发、
 *       25s 心跳、cursor 50ms 节流、seq 跳变检测（不连续就重新 join）。
 *
 * 纯 TS：不 import vue。连不上时 connect() 抛可捕获错误，UI 据此进入离线模式。
 */
import { Emitter, type Listener } from '../kernel/bus'
import { clientCmdId } from '../kernel/ids'
import type { BoardCommand } from '../kernel/types'
import {
  decodeS2C,
  encodeC2S,
  type BoardReloadS2C,
  type C2SMessage,
  type CursorC2S,
  type CursorS2C,
  type ErrorCode,
  type GameCommand,
  type InitS2C,
  type Member,
  type OpC2S,
  type OpS2C,
  type SavedS2C,
  type StrokeAppendC2S,
  type StrokeAppendS2C,
  type StrokeEndC2S,
  type StrokeEndS2C,
} from './protocol'

export type RoomStatus = 'idle' | 'connecting' | 'online' | 'reconnecting' | 'offline' | 'closed'

export interface RoomStatusPayload {
  status: RoomStatus
  /** 当前是第几次重连尝试（0 = 未在重连） */
  attempt: number
  detail?: string
}

export type RoomEvents = {
  status: RoomStatusPayload
  init: InitS2C
  /** 服务端快照落库通知（docs/04 §9） */
  saved: SavedS2C
  /** 回滚 / 归档后退弃本地未 ack 命令并重同步（docs/07 §3） */
  'board-reload': BoardReloadS2C
  op: OpS2C
  ack: { cid: string; seq: number }
  presence: Member[]
  'stroke-append': StrokeAppendS2C
  'stroke-end': StrokeEndS2C
  cursor: CursorS2C
  error: { code?: ErrorCode; msg: string; cid?: string }
  'seq-gap': { expected: number; received: number }
  log: { level: 'info' | 'warn' | 'error'; message: string }
}

export interface RoomClientOptions {
  /** 完整 WS 地址；缺省用 location 推导 ws(s)://host/ws（Vite dev 代理到 8080） */
  url?: string
  /** 重连退避基数，默认 1000ms */
  reconnectBaseMs?: number
  /** 重连退避上限，默认 15000ms */
  reconnectMaxMs?: number
  /** 心跳间隔，默认 25000ms */
  pingIntervalMs?: number
  /** 光标节流，默认 50ms */
  cursorThrottleMs?: number
  /** join 后等待首帧的超时，默认 8000ms */
  joinTimeoutMs?: number
  /** stroke.append 单帧点数上限，默认 512（docs/02 §3） */
  maxBatchPoints?: number
}

interface ResolvedOptions {
  url?: string
  reconnectBaseMs: number
  reconnectMaxMs: number
  pingIntervalMs: number
  cursorThrottleMs: number
  joinTimeoutMs: number
  maxBatchPoints: number
}

interface PendingCommand {
  cid: string
  message: OpC2S | StrokeEndC2S
  sentAt: number
}

const DEFAULT_OPTIONS: ResolvedOptions = {
  reconnectBaseMs: 1000,
  reconnectMaxMs: 15000,
  pingIntervalMs: 25000,
  cursorThrottleMs: 50,
  joinTimeoutMs: 8000,
  maxBatchPoints: 512,
}

/** cid 去重窗口（docs/02 §8：服务端 5 分钟） */
const PENDING_TTL_MS = 5 * 60 * 1000
/** seq 不连续时重新 join 的最小间隔，避免抖动风暴 */
const RESYNC_COOLDOWN_MS = 500

export class RoomClient {
  private readonly config: ResolvedOptions
  private readonly emitter = new Emitter<RoomEvents>()

  private socket: WebSocket | null = null
  private boardIdValue = ''
  private nicknameValue = ''
  /** 登录态 token：同时放进 WS query 与 join.token（docs/04 §3.3） */
  private tokenValue = ''
  private statusValue: RoomStatus = 'idle'
  private lastSeqValue = 0
  private meValue: Member | null = null
  private membersValue: Member[] = []
  private readonly pending = new Map<string, PendingCommand>()

  private reconnectAttempt = 0
  private reconnectTimer: number | null = null
  private pingTimer: number | null = null
  private joinTimer: number | null = null

  private cursorTimer: number | null = null
  private cursorPending = false
  private cursorX = 0
  private cursorY = 0
  private cursorLastSentAt = 0

  private manualClose = false
  private joinedOnce = false
  private ready = false
  private disposed = false
  private lastSocketError = ''
  private lastResyncAt = 0

  private firstAttempt: { resolve: () => void; reject: (error: Error) => void } | null = null

  constructor(options: RoomClientOptions = {}) {
    this.config = { ...DEFAULT_OPTIONS, ...options }
  }

  // -------------------------------------------------------------------------
  // 只读状态
  // -------------------------------------------------------------------------

  get status(): RoomStatus {
    return this.statusValue
  }

  get boardId(): string {
    return this.boardIdValue
  }

  get nickname(): string {
    return this.nicknameValue
  }

  /** 当前连接使用的 token（空串 = 匿名） */
  get token(): string {
    return this.tokenValue
  }

  /** 已应用的最大服务端序号，重连时作为 join.lastSeq */
  get lastSeq(): number {
    return this.lastSeqValue
  }

  get me(): Member | null {
    return this.meValue
  }

  get members(): Member[] {
    return this.membersValue
  }

  get pendingCount(): number {
    return this.pending.size
  }

  get isOpen(): boolean {
    return this.socket !== null && this.socket.readyState === WebSocket.OPEN
  }

  /** 已 join 且连接可用，才允许发 op */
  get isReady(): boolean {
    return this.ready && this.isOpen
  }

  on<K extends keyof RoomEvents>(type: K, listener: Listener<RoomEvents[K]>): () => void {
    return this.emitter.on(type, listener)
  }

  off<K extends keyof RoomEvents>(type: K, listener: Listener<RoomEvents[K]>): void {
    this.emitter.off(type, listener)
  }

  // -------------------------------------------------------------------------
  // 连接生命周期
  // -------------------------------------------------------------------------

  /**
   * 连接房间。首次连接失败会 reject（UI 进入离线模式），且不会自动重试；
   * 只有在「曾经成功 join 过」之后掉线，才会指数退避自动重连。
   *
   * @param token 登录态 JWT；会同时用于 ws URL query 与 join.token（docs/05 §3）
   */
  connect(boardId: string, nickname: string, token = ''): Promise<void> {
    if (this.disposed) {
      return Promise.reject(new Error('客户端已销毁'))
    }
    if (typeof WebSocket === 'undefined') {
      return Promise.reject(new Error('当前环境不支持 WebSocket'))
    }

    // 取消上一次未完成的连接
    if (this.firstAttempt) {
      const previous = this.firstAttempt
      this.firstAttempt = null
      previous.reject(new Error('连接被新的连接请求取代'))
    }

    this.teardownSocket()
    this.clearReconnectTimer()
    this.stopCursorTimer()
    this.manualClose = false

    if (this.boardIdValue !== boardId) {
      // 换房间：旧序号没有意义
      this.lastSeqValue = 0
    }
    this.boardIdValue = boardId
    this.nicknameValue = nickname
    this.tokenValue = token
    this.reconnectAttempt = 0

    return new Promise<void>((resolve, reject) => {
      this.firstAttempt = { resolve, reject }
      this.openSocket()
    })
  }

  /** 主动断开（不再重连） */
  disconnect(): void {
    this.manualClose = true
    this.reconnectAttempt = 0
    this.clearReconnectTimer()
    this.stopCursorTimer()
    this.teardownSocket()
    this.pending.clear()

    const attempt = this.firstAttempt
    this.firstAttempt = null
    attempt?.reject(new Error('连接已被取消'))

    this.setStatus('closed', '已断开连接')
  }

  /** 卸载时调用：断开并清空所有监听 */
  dispose(): void {
    this.disconnect()
    this.disposed = true
    this.emitter.clear()
  }

  // -------------------------------------------------------------------------
  // 发送
  // -------------------------------------------------------------------------

  /** 提交一条 board / game 命令；返回 cid（服务端 ack 会带回） */
  sendOp(channel: 'board' | 'game', cmd: BoardCommand | GameCommand): string {
    if (!this.isReady) {
      throw new Error('尚未连接房间，命令未发送')
    }
    const cid = clientCmdId()
    const message: OpC2S = { t: 'op', cid, ch: channel, cmd, ts: Date.now() }
    this.pending.set(cid, { cid, message, sentAt: Date.now() })
    if (!this.sendRaw(message)) {
      this.pending.delete(cid)
      throw new Error('WebSocket 发送失败')
    }
    return cid
  }

  /** 抬笔；需要 ack，进入未确认队列，断线后会重发 */
  sendStrokeEnd(strokeId: string): string {
    if (!this.isReady) {
      throw new Error('尚未连接房间，stroke.end 未发送')
    }
    const cid = clientCmdId()
    const message: StrokeEndC2S = { t: 'stroke.end', cid, strokeId, ts: Date.now() }
    this.pending.set(cid, { cid, message, sentAt: Date.now() })
    if (!this.sendRaw(message)) {
      this.pending.delete(cid)
      throw new Error('WebSocket 发送失败')
    }
    return cid
  }

  /** 绘制增量；不需要回执，不分配 seq，单帧按 512 点拆包 */
  sendStrokeAppend(
    pageId: string,
    strokeId: string,
    tool: 'pen',
    color: string,
    width: number,
    pts: number[],
  ): void {
    if (!this.isReady || pts.length === 0) return
    const chunkSize = this.config.maxBatchPoints * 2
    for (let offset = 0; offset < pts.length; offset += chunkSize) {
      const message: StrokeAppendC2S = {
        t: 'stroke.append',
        pageId,
        strokeId,
        tool,
        color,
        width,
        pts: pts.slice(offset, offset + chunkSize),
        ts: Date.now(),
      }
      this.sendRaw(message)
    }
  }

  /** 光标：50ms 节流（尾调用合并，保证最后位置一定发出去） */
  sendCursor(x: number, y: number): void {
    this.cursorX = x
    this.cursorY = y
    this.cursorPending = true
    if (this.cursorTimer !== null) return

    const elapsed = Date.now() - this.cursorLastSentAt
    const wait = elapsed >= this.config.cursorThrottleMs ? 0 : this.config.cursorThrottleMs - elapsed

    this.cursorTimer = window.setTimeout(() => {
      this.cursorTimer = null
      if (!this.cursorPending) return
      this.cursorPending = false
      this.cursorLastSentAt = Date.now()
      if (!this.isReady) return
      const message: CursorC2S = { t: 'cursor', x: this.cursorX, y: this.cursorY, ts: Date.now() }
      this.sendRaw(message)
    }, wait)
  }

  private sendRaw(message: C2SMessage): boolean {
    const socket = this.socket
    if (!socket || socket.readyState !== WebSocket.OPEN) return false
    try {
      socket.send(encodeC2S(message))
      return true
    } catch (error) {
      this.log('warn', `发送失败: ${error instanceof Error ? error.message : String(error)}`)
      return false
    }
  }

  // -------------------------------------------------------------------------
  // socket 事件
  // -------------------------------------------------------------------------

  private openSocket(): void {
    this.teardownSocket()

    let url: string
    try {
      url = this.resolveUrl()
    } catch (error) {
      this.failAttempt(error instanceof Error ? error : new Error(String(error)))
      return
    }

    if (this.statusValue !== 'reconnecting') {
      this.setStatus('connecting')
    }

    let socket: WebSocket
    try {
      socket = new WebSocket(url)
    } catch (error) {
      this.failAttempt(
        new Error(`无法建立 WebSocket 连接: ${error instanceof Error ? error.message : String(error)}`),
      )
      return
    }

    this.socket = socket
    socket.onopen = () => this.handleOpen(socket)
    socket.onmessage = (event: MessageEvent) => this.handleRawMessage(socket, event.data)
    socket.onerror = () => {
      this.lastSocketError = 'WebSocket 连接错误（服务器未启动或代理不可达）'
    }
    socket.onclose = (event: CloseEvent) => this.handleClose(socket, event.code, event.reason)

    // 兜底：连接一直挂起（既不 open 也不 close）时也要能超时进入离线模式
    this.startJoinTimer()
  }

  private handleOpen(socket: WebSocket): void {
    if (socket !== this.socket) return
    this.lastSocketError = ''

    // 握手后必须先发 join（docs/02 §1）；token 同时作为首帧兜底（docs/05 §3）
    this.sendRaw({
      t: 'join',
      boardId: this.boardIdValue,
      lastSeq: this.lastSeqValue,
      nickname: this.nicknameValue,
      token: this.tokenValue || undefined,
      ts: Date.now(),
    })
    this.startJoinTimer()
    this.startPingTimer()

    // 重连后先 join，再重发未确认命令（服务端按 cid 幂等去重）
    this.resendPending()
  }

  private handleRawMessage(socket: WebSocket, data: unknown): void {
    if (socket !== this.socket) return
    if (typeof data !== 'string') {
      this.log('warn', '收到非文本帧，已忽略（协议只使用 JSON 文本帧）')
      return
    }

    const message = decodeS2C(data)
    if (!message) {
      this.log('warn', `无法识别的服务端消息，已忽略: ${data.slice(0, 120)}`)
      return
    }

    switch (message.t) {
      case 'init':
        this.handleInit(message)
        return
      case 'saved':
        this.emitter.emit('saved', message)
        return
      case 'board.reload':
        this.handleBoardReload(message)
        return
      case 'op':
        this.handleOp(message)
        return
      case 'ack':
        this.pending.delete(message.cid)
        this.emitter.emit('ack', { cid: message.cid, seq: message.seq })
        return
      case 'stroke.append':
        this.emitter.emit('stroke-append', message)
        return
      case 'stroke.end':
        this.emitter.emit('stroke-end', message)
        return
      case 'cursor':
        this.emitter.emit('cursor', message)
        return
      case 'presence':
        this.membersValue = message.members
        this.emitter.emit('presence', message.members)
        return
      case 'error':
        this.handleServerError(message.code, message.msg, message.cid)
        return
      case 'pong':
        return
      case 'game.state':
      case 'game.word':
        // V2 预留：V1 不消费
        return
      default:
        return
    }
  }

  private handleInit(message: InitS2C): void {
    this.lastSeqValue = message.seq
    this.meValue = message.me
    this.membersValue = message.members
    this.markOnline()
    this.emitter.emit('init', message)
  }

  /**
   * board.reload：丢弃未 ack 命令，从 seq=0 重新 join 拿全量 init。
   * 回滚 / 归档后本地增量已不可信，必须整份重同步（docs/07 §1.4 / §3）。
   */
  private handleBoardReload(message: BoardReloadS2C): void {
    this.pending.clear()
    this.lastSeqValue = 0
    this.emitter.emit('board-reload', message)
    this.requestResync()
  }

  private handleOp(message: OpS2C): void {
    if (!this.joinedOnce) {
      // 首连必须先收到 init 才能建立完整文档
      this.log('warn', '在 init 之前收到 op，已忽略')
      return
    }

    if (message.seq <= this.lastSeqValue) {
      // 重复补发 / 自己的乐观操作与广播重叠：跳过，内核已按 id 幂等
      return
    }

    if (message.seq !== this.lastSeqValue + 1) {
      this.emitter.emit('seq-gap', { expected: this.lastSeqValue + 1, received: message.seq })
      this.requestResync()
      // 不应用乱序 op：等待 join 后的连续补发
      return
    }

    this.lastSeqValue = message.seq
    this.markOnline()
    this.emitter.emit('op', message)
  }

  private handleServerError(code: ErrorCode, msg: string, cid?: string): void {
    this.emitter.emit('error', { code, msg, cid })
    if (code === 'SEQ_GAP') {
      this.requestResync()
    }
    if (code === 'FRAME_TOO_LARGE') {
      this.log('warn', '帧过大：stroke.append 已按 512 点拆包，请检查服务端 256KB 限制')
    }
  }

  private handleClose(socket: WebSocket, code: number, reason: string): void {
    if (socket !== this.socket) return
    this.socket = null
    this.ready = false
    this.clearJoinTimer()
    this.stopPingTimer()
    this.stopCursorTimer()

    if (this.manualClose || this.disposed) {
      this.setStatus('closed', '已断开连接')
      return
    }

    if (!this.joinedOnce) {
      // 首次连接失败：抛出可捕获错误，由 UI 切离线模式，不自动重试
      const error = new Error(
        this.lastSocketError ||
          `无法连接协作服务器（code ${code}${reason ? `, ${reason}` : ''}），已切换到离线模式`,
      )
      const attempt = this.firstAttempt
      this.firstAttempt = null
      this.setStatus('offline', error.message)
      attempt?.reject(error)
      return
    }

    this.scheduleReconnect()
  }

  private markOnline(): void {
    this.ready = true
    this.joinedOnce = true
    this.reconnectAttempt = 0
    this.clearJoinTimer()
    if (this.statusValue !== 'online') {
      this.setStatus('online')
    }
    const attempt = this.firstAttempt
    this.firstAttempt = null
    attempt?.resolve()
  }

  private requestResync(): void {
    const now = Date.now()
    if (now - this.lastResyncAt < RESYNC_COOLDOWN_MS) return
    this.lastResyncAt = now
    if (!this.isOpen) return
    this.log('warn', `seq 不连续，重新 join（lastSeq=${this.lastSeqValue}）`)
    this.sendRaw({
      t: 'join',
      boardId: this.boardIdValue,
      lastSeq: this.lastSeqValue,
      nickname: this.nicknameValue,
      token: this.tokenValue || undefined,
      ts: Date.now(),
    })
    this.startJoinTimer()
  }

  private scheduleReconnect(): void {
    if (this.manualClose || this.disposed) return
    const attempt = this.reconnectAttempt + 1
    this.reconnectAttempt = attempt
    const delay = Math.min(
      this.config.reconnectBaseMs * 2 ** (attempt - 1),
      this.config.reconnectMaxMs,
    )
    this.setStatus('reconnecting', `连接已断开，${Math.round(delay / 1000)}s 后第 ${attempt} 次重连`)

    this.clearReconnectTimer()
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      this.openSocket()
    }, delay)
  }

  private failAttempt(error: Error): void {
    this.ready = false
    if (this.joinedOnce) {
      this.scheduleReconnect()
      return
    }
    const attempt = this.firstAttempt
    this.firstAttempt = null
    this.setStatus('offline', error.message)
    attempt?.reject(error)
  }

  private resendPending(): void {
    if (this.pending.size === 0) return
    this.log('info', `重发 ${this.pending.size} 条未确认命令`)
    for (const entry of this.pending.values()) {
      entry.sentAt = Date.now()
      this.sendRaw(entry.message)
    }
  }

  private prunePending(): void {
    const cutoff = Date.now() - PENDING_TTL_MS
    for (const [cid, entry] of this.pending) {
      if (entry.sentAt < cutoff) this.pending.delete(cid)
    }
  }

  // -------------------------------------------------------------------------
  // 定时器 / 清理
  // -------------------------------------------------------------------------

  private startJoinTimer(): void {
    this.clearJoinTimer()
    this.joinTimer = window.setTimeout(() => {
      this.joinTimer = null
      if (this.ready) return
      this.lastSocketError = '加入房间超时（未收到 init）'
      const socket = this.socket
      this.socket = null
      if (socket) {
        socket.onclose = null
        try {
          socket.close()
        } catch {
          // 忽略
        }
      }
      if (this.joinedOnce) {
        this.scheduleReconnect()
      } else {
        this.failAttempt(new Error(this.lastSocketError))
      }
    }, this.config.joinTimeoutMs)
  }

  private clearJoinTimer(): void {
    if (this.joinTimer !== null) {
      window.clearTimeout(this.joinTimer)
      this.joinTimer = null
    }
  }

  private startPingTimer(): void {
    this.stopPingTimer()
    this.pingTimer = window.setInterval(() => {
      if (!this.isOpen) return
      this.sendRaw({ t: 'ping', ts: Date.now() })
      this.prunePending()
    }, this.config.pingIntervalMs)
  }

  private stopPingTimer(): void {
    if (this.pingTimer !== null) {
      window.clearInterval(this.pingTimer)
      this.pingTimer = null
    }
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
  }

  private stopCursorTimer(): void {
    if (this.cursorTimer !== null) {
      window.clearTimeout(this.cursorTimer)
      this.cursorTimer = null
    }
    this.cursorPending = false
  }

  /** 关闭当前 socket 并解绑事件（不会触发重连） */
  private teardownSocket(): void {
    const socket = this.socket
    this.socket = null
    this.ready = false
    this.clearJoinTimer()
    this.stopPingTimer()
    if (!socket) return
    socket.onopen = null
    socket.onmessage = null
    socket.onclose = null
    socket.onerror = null
    try {
      if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
        socket.close()
      }
    } catch {
      // 忽略
    }
  }

  private resolveUrl(): string {
    let base = this.config.url
    if (!base) {
      if (typeof window === 'undefined' || !window.location) {
        throw new Error('无法推导 WebSocket 地址，请通过 RoomClientOptions.url 显式传入')
      }
      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      base = `${protocol}//${window.location.host}/ws`
    }
    if (!this.tokenValue) return base
    // docs/05 §3：ws://host/ws?token=<JWT>
    const separator = base.includes('?') ? '&' : '?'
    return `${base}${separator}token=${encodeURIComponent(this.tokenValue)}`
  }

  private setStatus(status: RoomStatus, detail?: string): void {
    this.statusValue = status
    this.emitter.emit('status', { status, attempt: this.reconnectAttempt, detail })
  }

  private log(level: 'info' | 'warn' | 'error', message: string): void {
    this.emitter.emit('log', { level, message })
  }
}
