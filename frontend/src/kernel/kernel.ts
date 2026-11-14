/**
 * kernel.ts — 白板内核：唯一持有文档、应用命令、广播事件
 *
 * 纯 TS 模块，不 import vue、不碰 DOM。Vue 只是订阅它的外壳：
 *   本地操作 kernel.dispatch(cmd)  →  先过 Policy，再改文档，再发网络（可选）
 *   远端操作 kernel.applyRemote(seq, cmd, actor)  →  按服务端全序重放
 */
import { Emitter, type Listener } from './bus'
import { applyCommand, createEmptyDoc, findStroke, normalizeDoc } from './doc'
import { userId as newUserId } from './ids'
import { allowAllPolicy, type Policy } from './policy'
import { pageIdOfCommand, type BoardCommand, type Doc } from './types'

/** 网络发送接口：由 App 注入 RoomClient；离线时不注入（命令只落本地） */
export interface KernelTransport {
  /** 把命令提交到服务端；失败请 reject，内核会退化为「仅本地」并广播 error */
  send(cmd: BoardCommand): Promise<void>
}

export type PageRefreshReason = 'local' | 'remote' | 'replace'

/** 他人绘制中的增量笔划（S→C stroke.append 的转发载荷） */
export interface StrokeProgressPayload {
  pageId: string
  strokeId: string
  author: string
  tool: 'pen'
  color: string
  width: number
  /** 本次增量点，扁平数组 [x1,y1,x2,y2,...] */
  points: number[]
}

export type KernelEvents = {
  /** 一条命令被应用（本地或远端） */
  op: { seq: number; actor: string; cmd: BoardCommand; local: boolean }
  /** 某页内容变化，渲染层据此重绘 */
  pagerefresh: { pageId: string | undefined; reason: PageRefreshReason }
  /** 他人绘制中的笔划增量；不进入文档，只用于实时预览 */
  'stroke-progress': StrokeProgressPayload
  /** 他人抬笔，服务端随后会广播对应的 stroke.add */
  'stroke-progress-end': { strokeId: string }
  /** 全量文档被替换（init / 重新同步） */
  doc: { doc: Doc }
  /** 非致命错误：发送失败、命令应用失败等 */
  error: { message: string; error?: unknown }
  /** 撤销栈变化 */
  history: { canUndo: boolean; size: number }
}

export interface KernelOptions {
  doc?: Doc
  boardId?: string
  /** 本机用户 id；连接成功后应由服务端 init.me.id 覆盖 */
  author?: string
  policy?: Policy
  /** 本地撤销栈深度，默认 50（需求约定） */
  undoLimit?: number
}

interface UndoEntry {
  pageId: string
  strokeId: string
}

interface DispatchOptions {
  /** 是否记入撤销栈，默认 true；撤销自身产生的 erase 不应再入栈 */
  recordHistory?: boolean
  /**
   * 只落本地、不走 transport。
   * 笔划用 stroke.append / stroke.end 通道提交（服务端据此生成 stroke.add），
   * 所以本地乐观应用 stroke.add 时不能再用 op 重复提交一次。
   */
  localOnly?: boolean
}

export class WhiteboardKernel {
  private docValue: Doc
  private authorValue: string
  private policyValue: Policy
  private transportValue: KernelTransport | null = null
  private readonly emitter = new Emitter<KernelEvents>()
  private readonly undoStack: UndoEntry[] = []
  private readonly undoLimit: number

  constructor(options: KernelOptions = {}) {
    const boardId = options.doc?.boardId ?? options.boardId ?? 'b_local'
    this.docValue = options.doc ?? createEmptyDoc(boardId)
    this.authorValue = options.author ?? newUserId()
    this.policyValue = options.policy ?? allowAllPolicy
    this.undoLimit = Math.max(1, options.undoLimit ?? 50)
  }

  get doc(): Doc {
    return this.docValue
  }

  get author(): string {
    return this.authorValue
  }

  get policy(): Policy {
    return this.policyValue
  }

  get boardId(): string {
    return this.docValue.boardId
  }

  /** 服务端分配的 userId 覆盖本地临时 id（影响 stroke.author 与撤销归属） */
  setAuthor(author: string): void {
    if (!author) return
    this.authorValue = author
  }

  /** 游戏部件 / 角色权限通过这里接管写权限 */
  setPolicy(policy: Policy): void {
    this.policyValue = policy
  }

  /** 注入 / 摘除网络发送；离线模式传 null */
  setTransport(transport: KernelTransport | null): void {
    this.transportValue = transport
  }

  get transport(): KernelTransport | null {
    return this.transportValue
  }

  on<K extends keyof KernelEvents>(type: K, listener: Listener<KernelEvents[K]>): () => void {
    return this.emitter.on(type, listener)
  }

  off<K extends keyof KernelEvents>(type: K, listener: Listener<KernelEvents[K]>): void {
    this.emitter.off(type, listener)
  }

  get undoSize(): number {
    return this.undoStack.length
  }

  /** 栈顶是否还有「仍然存在且属于自己的」笔划可撤 */
  get canUndo(): boolean {
    return this.findUndoEntry() !== null
  }

  /**
   * 本地提交一条命令。
   *
   * 顺序：Policy 校验 → 本地应用 → 广播事件 → 尝试网络发送。
   * @returns true 表示已交给网络（服务端是否接受以 ack/op 为准）；false 表示只落本地
   */
  async dispatch(cmd: BoardCommand, options: DispatchOptions = {}): Promise<boolean> {
    const pageId = pageIdOfCommand(cmd)
    if (!this.policyValue.canApply(cmd, pageId)) {
      throw new Error(`命令被写权限策略拒绝: ${cmd.t}`)
    }

    applyCommand(this.docValue, cmd)

    if (cmd.t === 'stroke.add' && options.recordHistory !== false) {
      this.pushUndo({ pageId: cmd.pageId, strokeId: cmd.stroke.id })
    }

    this.emitter.emit('op', {
      seq: this.docValue.seq,
      actor: this.authorValue,
      cmd,
      local: true,
    })
    this.emitter.emit('pagerefresh', { pageId, reason: 'local' })
    this.emitter.emit('history', { canUndo: this.canUndo, size: this.undoStack.length })

    const transport = this.transportValue
    if (!transport || options.localOnly) return false

    try {
      await transport.send(cmd)
      return true
    } catch (error) {
      this.emitter.emit('error', {
        message: `命令发送失败，仅保存在本地: ${error instanceof Error ? error.message : String(error)}`,
        error,
      })
      return false
    }
  }

  /**
   * 应用服务端定序后的远端命令。
   *
   * 自己命令的广播（已乐观应用过）也走这里：doc 层按 id 幂等，不会重复追加。
   * @param seq 服务端序号
   * @param cmd board 命令
   * @param actor 操作者 userId（可选，仅用于事件）
   */
  applyRemote(seq: number, cmd: BoardCommand, actor?: string): void {
    const pageId = pageIdOfCommand(cmd)
    try {
      applyCommand(this.docValue, cmd)
    } catch (error) {
      // 页面缺失等情况说明本地状态落后：不崩，交给上层触发重新 join
      this.emitter.emit('error', {
        message: `应用远端命令失败: ${error instanceof Error ? error.message : String(error)}`,
        error,
      })
    }

    if (Number.isFinite(seq) && seq > this.docValue.seq) {
      this.docValue.seq = seq
    }

    this.emitter.emit('op', {
      seq,
      actor: actor ?? 'remote',
      cmd,
      local: false,
    })
    this.emitter.emit('pagerefresh', { pageId, reason: 'remote' })
  }

  /**
   * 全量替换文档（收到 init / 重新同步时）。
   * 会清空撤销栈：旧文档的笔划已经不存在了。
   */
  replaceDoc(raw: unknown): void {
    this.docValue = normalizeDoc(raw, this.docValue.boardId)
    this.undoStack.length = 0
    this.emitter.emit('doc', { doc: this.docValue })
    this.emitter.emit('pagerefresh', {
      pageId: this.docValue.pages[0]?.id,
      reason: 'replace',
    })
    this.emitter.emit('history', { canUndo: false, size: 0 })
  }

  /** 撤销：只撤自己的笔划，做法是发 stroke.erase（笔划不可变） */
  async undo(): Promise<boolean> {
    const entry = this.findUndoEntry()
    if (!entry) return false

    // 先把自己这条出栈，撤销产生的 erase 不再入栈
    const index = this.undoStack.lastIndexOf(entry)
    if (index >= 0) this.undoStack.splice(index, 1)

    await this.dispatch(
      { t: 'stroke.erase', pageId: entry.pageId, ids: [entry.strokeId] },
      { recordHistory: false },
    )
    return true
  }

  /** 转发他人 stroke.append（不进入文档） */
  relayStrokeProgress(payload: StrokeProgressPayload): void {
    this.emitter.emit('stroke-progress', payload)
  }

  /** 转发他人 stroke.end（不进入文档） */
  relayStrokeProgressEnd(strokeId: string): void {
    this.emitter.emit('stroke-progress-end', { strokeId })
  }

  /** 入撤销栈，超出深度（默认 50）丢弃最旧的 */
  private pushUndo(entry: UndoEntry): void {
    this.undoStack.push(entry)
    while (this.undoStack.length > this.undoLimit) {
      this.undoStack.shift()
    }
  }

  /** 从栈顶找一个仍然存在、仍属于本机用户的笔划 */
  private findUndoEntry(): UndoEntry | null {
    for (let i = this.undoStack.length - 1; i >= 0; i -= 1) {
      const entry = this.undoStack[i]
      const stroke = findStroke(this.docValue, entry.pageId, entry.strokeId)
      if (stroke && stroke.author === this.authorValue) return entry
    }
    return null
  }
}

/** 便捷构造 */
export function createKernel(options: KernelOptions = {}): WhiteboardKernel {
  return new WhiteboardKernel(options)
}
