/**
 * doc.ts — 文档数据结构操作：命令应用 / 页面查找 / 全量文档兜底归一化
 *
 * 纯函数、纯 TS：不 import vue、不碰 DOM。
 * 决策：applyCommand 采用「原地修改」——调用方共享同一个 Doc 引用，
 * 这样 kernel 不必替换对象，渲染层每帧直接读最新结构即可。
 */
import { pageId as newPageId } from './ids'
import {
  SCHEMA_VERSION,
  type BoardCommand,
  type Doc,
  type Element,
  type ElementKind,
  type Page,
  type Shape,
  type Stroke,
  type Text,
} from './types'

/** 新建一个空文档（至少 1 页，见 docs/01 §3） */
export function createEmptyDoc(boardId: string): Doc {
  return {
    schemaVersion: SCHEMA_VERSION,
    boardId,
    seq: 0,
    pages: [createPage('第 1 页')],
  }
}

/** 新建一页 */
export function createPage(name: string): Page {
  return {
    id: newPageId(),
    name,
    roundId: null,
    elements: [],
  }
}

export function findPage(doc: Doc, pageId: string): Page | undefined {
  return doc.pages.find((page) => page.id === pageId)
}

export function findElement(page: Page, elementId: string): Element | undefined {
  return page.elements.find((element) => element.id === elementId)
}

/** 找到当前页里指定 id 的笔划（撤销栈用） */
export function findStroke(doc: Doc, pageId: string, strokeId: string): Stroke | undefined {
  const page = findPage(doc, pageId)
  if (!page) return undefined
  const element = findElement(page, strokeId)
  return element && element.kind === 'stroke' ? element : undefined
}

function requirePage(doc: Doc, pageId: string): Page {
  const page = findPage(doc, pageId)
  if (!page) {
    throw new Error(`页面不存在: ${pageId}`)
  }
  return page
}

/**
 * 应用一条 board 命令（原地修改 doc）。
 *
 * - 未知命令一律抛错：协议漂移要尽早暴露，而不是静默忽略。
 * - 页面不存在也抛错：调用方（kernel）负责捕获并提示重同步。
 * - 按 id 幂等：本地乐观应用后还会收到服务端广播的同一命令，不能重复追加。
 */
export function applyCommand(doc: Doc, cmd: BoardCommand): void {
  switch (cmd.t) {
    case 'stroke.add': {
      const page = requirePage(doc, cmd.pageId)
      if (page.elements.some((element) => element.id === cmd.stroke.id)) return
      page.elements.push(cmd.stroke)
      return
    }

    case 'stroke.erase': {
      // 橡皮 = 删除操作，不是白色覆盖（docs/01 §1）；只删笔划
      const page = requirePage(doc, cmd.pageId)
      const ids = new Set(cmd.ids)
      page.elements = page.elements.filter((element) => !(element.kind === 'stroke' && ids.has(element.id)))
      return
    }

    case 'object.add': {
      const page = requirePage(doc, cmd.pageId)
      if (page.elements.some((element) => element.id === cmd.object.id)) return
      page.elements.push(cmd.object)
      return
    }

    case 'object.patch': {
      const page = requirePage(doc, cmd.pageId)
      const element = findElement(page, cmd.id)
      // 先删后改：对已删元素的 patch 直接丢弃（docs/01 §4）
      if (!element) return
      // 笔划不可变：patch 只作用于图形 / 文本
      if (element.kind === 'stroke') return
      Object.assign(element, cmd.patch)
      return
    }

    case 'object.remove': {
      const page = requirePage(doc, cmd.pageId)
      const ids = new Set(cmd.ids)
      // 只删图形 / 文本；笔划的删除走 stroke.erase
      page.elements = page.elements.filter((element) => !(element.kind !== 'stroke' && ids.has(element.id)))
      return
    }

    case 'page.add': {
      const page = normalizePage(cmd.page)
      if (doc.pages.some((existing) => existing.id === page.id)) return
      doc.pages.push(page)
      return
    }

    case 'page.rename': {
      const page = requirePage(doc, cmd.pageId)
      const name = typeof cmd.name === 'string' ? cmd.name.trim() : ''
      if (!name) return
      page.name = name.slice(0, 50)
      return
    }

    case 'page.clear': {
      const page = requirePage(doc, cmd.pageId)
      page.elements = []
      return
    }

    case 'board.reset': {
      // 清空所有页，只保留 1 页（docs/02 §5）
      const first = doc.pages[0]
      if (!first) {
        doc.pages = [createPage('第 1 页')]
        return
      }
      first.elements = []
      first.roundId = null
      doc.pages = [first]
      return
    }

    default: {
      // 编译期穷尽性检查 + 运行期协议漂移兜底
      const unknown = cmd as { t?: unknown }
      throw new Error(`未知的 board 命令: ${String(unknown.t)}`)
    }
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function asNumber(value: unknown, fallback = 0): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

function asString(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback
}

const ELEMENT_KINDS: readonly ElementKind[] = ['stroke', 'rect', 'ellipse', 'arrow', 'line', 'text']

/** 归一化单个元素；结构非法返回 null（脏数据丢弃，不让渲染层崩） */
function normalizeElement(raw: unknown): Element | null {
  if (!isRecord(raw)) return null
  if (typeof raw.id !== 'string' || raw.id.length === 0) return null
  if (typeof raw.kind !== 'string' || !ELEMENT_KINDS.includes(raw.kind as ElementKind)) return null

  const base = {
    id: raw.id,
    author: asString(raw.author, 'unknown'),
    createdAt: asNumber(raw.createdAt, Date.now()),
  }

  if (raw.kind === 'stroke') {
    const points = Array.isArray(raw.points)
      ? raw.points.filter((value): value is number => typeof value === 'number' && Number.isFinite(value))
      : []
    if (points.length < 2) return null
    return {
      ...base,
      kind: 'stroke',
      tool: 'pen',
      color: asString(raw.color, '#111111'),
      width: Math.min(64, Math.max(1, asNumber(raw.width, 4))),
      points,
    } satisfies Stroke
  }

  if (raw.kind === 'text') {
    return {
      ...base,
      kind: 'text',
      x: asNumber(raw.x),
      y: asNumber(raw.y),
      text: asString(raw.text).slice(0, 500),
      fontSize: Math.min(96, Math.max(16, asNumber(raw.fontSize, 24))),
      color: asString(raw.color, '#111111'),
    } satisfies Text
  }

  return {
    ...base,
    kind: raw.kind as Shape['kind'],
    x: asNumber(raw.x),
    y: asNumber(raw.y),
    w: asNumber(raw.w),
    h: asNumber(raw.h),
    rotation: asNumber(raw.rotation),
    fill: typeof raw.fill === 'string' ? raw.fill : null,
    stroke: asString(raw.stroke, '#111111'),
    strokeWidth: Math.min(64, Math.max(1, asNumber(raw.strokeWidth, 2))),
  } satisfies Shape
}

/** 归一化一页；page.add 命令与 init 文档都走这里，保证渲染层拿到完整结构 */
export function normalizePage(raw: unknown): Page {
  const record = isRecord(raw) ? raw : {}
  const id = typeof record.id === 'string' && record.id.length > 0 ? record.id : newPageId()
  const elements = Array.isArray(record.elements)
    ? record.elements.map(normalizeElement).filter((element): element is Element => element !== null)
    : []
  return {
    id,
    name: asString(record.name, '未命名页').slice(0, 50),
    roundId: typeof record.roundId === 'string' ? record.roundId : null,
    elements,
  }
}

/**
 * 把服务端 init 下发的文档归一化成可信 Doc。
 * 服务端字段缺失 / schemaVersion 不一致时不崩，降级成可用的最小文档。
 */
export function normalizeDoc(raw: unknown, fallbackBoardId: string): Doc {
  const record = isRecord(raw) ? raw : {}
  const pages = Array.isArray(record.pages)
    ? record.pages.map(normalizePage).filter((page) => page.id.length > 0)
    : []
  if (pages.length === 0) {
    pages.push(createPage('第 1 页'))
  }
  return {
    schemaVersion: asNumber(record.schemaVersion, SCHEMA_VERSION),
    boardId: asString(record.boardId, fallbackBoardId) || fallbackBoardId,
    seq: asNumber(record.seq, 0),
    pages,
  }
}
