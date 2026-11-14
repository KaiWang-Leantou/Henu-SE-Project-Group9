/**
 * types.ts — 契约数据类型：严格对应 docs/01-数据模型.md，不增删字段
 *
 * 约定：所有几何数据都是「世界坐标」；屏幕坐标只允许出现在 canvas 渲染层。
 */

/** 文档结构版本，见 docs/01 §3；当前固定为 1 */
export const SCHEMA_VERSION = 1

/** 元素种类，见 docs/01 §3 Element 公共字段 */
export type ElementKind = 'stroke' | 'rect' | 'ellipse' | 'arrow' | 'line' | 'text'

/** 笔划工具：V1 只有笔；橡皮是删除操作，不产生 stroke（docs/01 §1） */
export type StrokeTool = 'pen'

export interface ElementBase {
  kind: ElementKind
  /** 全局唯一：s_ 笔划，o_ 图形/文本 */
  id: string
  /** 创建者 userId */
  author: string
  /** 创建时间，epoch ms */
  createdAt: number
}

/** 笔划：落定后不可变（docs/01 §1） */
export interface Stroke extends ElementBase {
  kind: 'stroke'
  tool: StrokeTool
  /** #RRGGBB */
  color: string
  /** 世界坐标下的线宽，1–64 */
  width: number
  /** 扁平数组 [x1,y1,x2,y2,...]，世界坐标；单笔上限 4000 点 */
  points: number[]
}

/** 图形：矩形 / 圆 / 箭头 / 直线，可改 */
export interface Shape extends ElementBase {
  kind: 'rect' | 'ellipse' | 'arrow' | 'line'
  /** 世界坐标包围盒；允许负 w/h 表示反向拖拽 */
  x: number
  y: number
  w: number
  h: number
  /** 弧度，默认 0 */
  rotation: number
  /** null 表示不填充 */
  fill: string | null
  /** 描边色 */
  stroke: string
  /** 描边宽 */
  strokeWidth: number
}

/** 文本：可改 */
export interface Text extends ElementBase {
  kind: 'text'
  /** 左上角世界坐标 */
  x: number
  y: number
  /** 内容，≤ 500 字符 */
  text: string
  /** 16–96 */
  fontSize: number
  /** #RRGGBB */
  color: string
}

export type Element = Stroke | Shape | Text

/** 页：一页一画布；游戏里一页 = 一轮 */
export interface Page {
  /** p_ + 随机 */
  id: string
  /** 显示名；游戏页写「第 N 轮」 */
  name: string
  /** 游戏轮次 ID；普通白板页为 null */
  roundId: string | null
  /** 按绘制顺序；绘制顺序即图层顺序 */
  elements: Element[]
}

/** 整块白板文档，序列化后存 wb_board.doc_json，并通过 init 全量下发 */
export interface Doc {
  /** 结构版本，当前 1 */
  schemaVersion: number
  /** 与 DB 主键一致 */
  boardId: string
  /** 文档已应用到的最新服务端序号 */
  seq: number
  /** 有序，至少 1 页 */
  pages: Page[]
}

/**
 * object.patch 的补丁字段：只允许改这些可编辑字段。
 * 不允许改 kind / id / author / createdAt，也不允许改笔划（笔划不可变）。
 */
export interface ElementPatch {
  x?: number
  y?: number
  w?: number
  h?: number
  rotation?: number
  fill?: string | null
  stroke?: string
  strokeWidth?: number
  text?: string
  fontSize?: number
  color?: string
}

/**
 * board 命令联合类型，逐条对应 docs/02 §5 的命令表。
 * 未知命令会被 doc.applyCommand 抛错，用于尽早发现协议漂移。
 */
export type BoardCommand =
  | { t: 'stroke.add'; pageId: string; stroke: Stroke }
  | { t: 'stroke.erase'; pageId: string; ids: string[] }
  | { t: 'object.add'; pageId: string; object: Element }
  | { t: 'object.patch'; pageId: string; id: string; patch: ElementPatch }
  | { t: 'object.remove'; pageId: string; ids: string[] }
  | { t: 'page.add'; page: Page }
  | { t: 'page.rename'; pageId: string; name: string }
  | { t: 'page.clear'; pageId: string }
  | { t: 'board.reset' }

/** 命令类型名的常量表，便于日志 / 策略判断 */
export const BOARD_COMMAND_TYPES = [
  'stroke.add',
  'stroke.erase',
  'object.add',
  'object.patch',
  'object.remove',
  'page.add',
  'page.rename',
  'page.clear',
  'board.reset',
] as const

export type BoardCommandType = (typeof BOARD_COMMAND_TYPES)[number]

export function isStroke(element: Element): element is Stroke {
  return element.kind === 'stroke'
}

export function isShape(element: Element): element is Shape {
  return element.kind === 'rect' || element.kind === 'ellipse' || element.kind === 'arrow' || element.kind === 'line'
}

export function isText(element: Element): element is Text {
  return element.kind === 'text'
}

/** 命令影响到的页面 id（board.reset / page.add 用命令自带信息推导） */
export function pageIdOfCommand(cmd: BoardCommand): string | undefined {
  switch (cmd.t) {
    case 'page.add':
      return cmd.page.id
    case 'board.reset':
      return undefined
    default:
      return cmd.pageId
  }
}
