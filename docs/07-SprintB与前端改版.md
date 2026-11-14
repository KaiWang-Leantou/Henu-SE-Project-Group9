# 07 · Sprint B 契约补充（版本 / 归档 / 信息架构 / 光标设置）

> 在 `docs/04` 基础上的增量。**后端按 §1–§3 实现，前端按 §4–§6 实现，两边以此为准。**

## 1. 版本历史

### 1.1 表（Flyway `V3__board_version.sql`）

```sql
CREATE TABLE wb_board_version (
  id         VARCHAR(32) NOT NULL PRIMARY KEY,
  board_id   VARCHAR(32) NOT NULL,
  version_no INT         NOT NULL,
  kind       VARCHAR(16) NOT NULL,        -- AUTO / MANUAL / PRE_RESTORE
  label      VARCHAR(100) NULL,
  doc_json   MEDIUMTEXT  NOT NULL,
  doc_seq    BIGINT      NOT NULL,
  thumbnail  MEDIUMTEXT  NULL,
  author_id  VARCHAR(32) NULL,
  created_at TIMESTAMP(3) NOT NULL,
  CONSTRAINT uk_board_version UNIQUE (board_id, version_no)
);
CREATE INDEX idx_board_version_time ON wb_board_version (board_id, created_at);
```

### 1.2 生成规则

| 类型 | 触发 | 保留 |
| :--- | :--- | :--- |
| `AUTO` | 每 5 分钟且期间有 op，或累计 200 op | 最近 30 个 + 每天首个，30 天 |
| `MANUAL` | 用户点"存为版本" | 永久，单板 ≤100 |
| `PRE_RESTORE` | 每次回滚前 | 30 天 |

GC：每日 03:00 扫描；单板版本总数 >200 时按"最旧 AUTO → 最旧 PRE_RESTORE"删除；`MANUAL` 永不自动删。

### 1.3 接口

| 方法 | 路径 | 说明 |
| :--- | :--- | :--- |
| GET | `/api/boards/{id}/versions?page=&size=` | 时间轴，倒序；字段 `id,versionNo,kind,label,docSeq,author,createdAt,thumbnail` |
| POST | `/api/boards/{id}/versions` | 手动存档 `{label?, thumbnail?}` |
| POST | `/api/boards/{id}/versions/{versionId}/restore` | 回滚 |

### 1.4 回滚语义（不可简化）

```
① 当前 doc 存为 PRE_RESTORE
② 目标版本 doc 覆盖当前 doc，seq = max(当前seq, 目标docSeq) + 1
③ 落一条 op {t:"board.restore", versionId, versionNo}
④ 更新 wb_board.doc_json/doc_seq/last_saved_at
⑤ 向房间广播 init（全量重同步）
```

权限：`owner` 才能回滚；`editor+` 可手动存档。

## 2. 生命周期（归档 / 回收站）

| 方法 | 路径 | 权限 | 说明 |
| :--- | :--- | :--- | :--- |
| PATCH | `/api/boards/{id}` | owner | `{name?}` 或 `{status:"archived"\|"active"}` |
| DELETE | `/api/boards/{id}` | owner | 软删除 → `TRASHED`，写 `trashed_at` |
| POST | `/api/boards/{id}/trash/restore` | owner | 回收站恢复 → `ACTIVE` |
| POST | `/api/boards/{id}/duplicate` | 登录用户 | 复制成新板（`ACTIVE`，owner=自己），用于"从归档作品继续联机" |

**只读强制**：
- `ARCHIVED` / `TRASHED` 的板：WS 所有写命令 → `error.code = READ_ONLY`；REST 改名/缩略图/存档 → 403。
- 列表：`scope=mine|joined|all` + `status=active|archived|trashed`；**不传 status 时不返回 TRASHED**。

## 3. WS 增量

| 方向 | 消息 | 说明 |
| :--- | :--- | :--- |
| S→C | `init.members[]` 增加 `color` | 服务端按 `userId` 哈希从 12 色调色板分配，稳定不变 |
| S→C | `presence.members[]` 同上 | |
| S→C | `board.reload {seq, reason}` | 回滚/归档后触发，客户端丢弃未 ack 命令并重同步 |

`cursor` 消息保持不变（世界坐标 `x,y`），颜色由 `members[].color` 提供。

## 4. 前端信息架构（本次改版的核心）

```
/             → /home
/login        → 登录/注册（保留）
/home         → 主界面：三张入口卡
                 ├─ 画画（作品 + 历史归档）
                 ├─ 联机（选板建房 / 新建）
                 └─ 你画我猜（占位，V2）
/draw         → 画画：我的作品网格 + [全部 | 已归档 | 回收站] + 版本/归档操作
/board/:id    → 画布（从 /draw、/collab 进入）
/collab       → 联机：选一块板（active/archived/他人公开）+ 新建 → 进房间
```

**移除**：画布页右侧常驻的"你画我猜"浮层（`widgets/game/GamePanel` 不再挂在 BoardView）。
游戏入口只在 `/home`，V2 前进去显示"敬请期待"。

### 4.1 `/home`
- 顶部：昵称、退出、设置入口。
- 三张卡片：图标 + 标题 + 一句说明 + 右侧最近作品缩略图。

### 4.2 `/draw`
- 分段控件：`全部 / 已归档 / 回收站`。
- 卡片操作菜单：打开、改名、存为版本、归档/取消归档、删除（进回收站）、从回收站恢复、回滚历史。
- 点卡片右上"历史"打开**版本抽屉**：时间轴（AUTO/MANUAL/PRE_RESTORE 标签）、缩略图、恢复按钮（二次确认）。

### 4.3 `/collab`
- 列表：我参与/我的 active 板 + "他人公开板"（可选）。
- 每张卡：`进入房间`、`复制后联机`（archived 时显示）。
- `新建白板` → 创建后直接进 `/board/:id`，并把链接展示为"邀请链接（复制）"。

## 5. 远端光标

- 画布顶层渲染每个在线成员的 `cursor{x,y}`：小三角/圆点 + 昵称气泡。
- 颜色：`settings.cursorColor = 'auto' | '#RRGGBB'`；`auto` 用 `members[].color`。
- 透明度：`settings.cursorOpacity = 0.2 ~ 1.0`，默认 `0.85`。
- 尺寸：`small | medium | large`（12/16/20px），默认 medium。
- 显示昵称：`settings.showCursorName`，默认 true。
- 光标 2s 未更新自动淡出（对方停止移动时）。

## 6. 设置抽屉（侧边栏）

从 `/home`、`/draw`、`/collab` 右上角齿轮打开，右侧滑出：

| 设置项 | 取值 | 存储 |
| :--- | :--- | :--- |
| 光标颜色 | 自动 / 12 色板 | localStorage `wb.settings.cursorColor` |
| 光标透明度 | 0.2–1.0 滑杆 | `wb.settings.cursorOpacity` |
| 光标大小 | 小/中/大 | `wb.settings.cursorSize` |
| 显示昵称 | 开/关 | `wb.settings.showCursorName` |
| 深色模式 | 开/关（可选） | `wb.settings.theme` |

设置只影响**本机视图**，不同步到服务器。

## 7. 验收清单

- [ ] 登录后落到 `/home`，三个入口可达
- [ ] `/draw` 能切换 全部/已归档/回收站；归档后卡片移入"已归档"且只读
- [ ] 版本抽屉能列出 AUTO+MANUAL；手动存档后立刻出现；回滚后画布内容变化且**历史不丢**
- [ ] `/collab` 选一块 active 板进房间；选归档板提示"复制后联机"，复制成功
- [ ] 两个浏览器进同一房间，能看到对方光标与昵称
- [ ] 设置里改颜色/透明度/大小，对方光标即时变化；刷新后设置仍在
- [ ] 画布页不再有你画我猜浮层
- [ ] 归档板上发起写入 → 服务端返回 `READ_ONLY`
