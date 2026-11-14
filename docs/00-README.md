# 00 · 契约文档索引

这套文档是**前后端并行开发的唯一依据**。改协议先改这里，再改代码。

| 文档 | 内容 | 谁必须读 |
| :--- | :--- | :--- |
| [01-数据模型](01-数据模型.md) | 文档 JSON、元素类型、MySQL 表、Redis key、持久化策略 | 后端全员、前端画布 |
| [02-通信协议](02-通信协议.md) | WS 消息表、board/game 命令、错误码、重连与限流 | 前后端全员 |
| [03-游戏状态机](03-游戏状态机.md) | 游戏会话状态、事件、守卫、与白板的映射 | 后端 + 前端游戏部件 |
| [04-身份作品库与版本](04-身份作品库与版本.md) | 注册登录、JWT、作品库、自动存稿、版本回滚、归档回收站 | 全员（V1.5 主线） |
| [05-接口示例](05-接口示例.md) | HTTP 接口的请求/响应样例、错误码、WS 鉴权方式 | 前后端联调必查 |
| [06-SRS修订清单](06-SRS修订清单.md) | 可直接粘进 `文档2` 的功能表、用例、非功能与验收改法 | 负责写文档的人 |
| [07-SprintB与前端改版](07-SprintB与前端改版.md) | 版本/归档/回收站、主界面信息架构、远端光标与设置抽屉 | 本轮前后端 |
| [08-交付说明与提交计划](08-交付说明与提交计划.md) | 交付物结构、验证证据、已知限制、建议的提交拆分 | 提交前必看 |

图（PlantUML 源文件放在 `docs/diagrams/`，用 IDEA 插件或 plantuml.com 导出 PNG）：

- `diagrams/game-state.puml` — 游戏会话状态机
- `diagrams/board-op-sequence.puml` — 一次笔划操作的完整时序

## 三条不可动摇的约定

1. **服务端全序**：每个 board 的所有操作由服务端串行分配 `seq`，客户端严格按 `seq` 应用。收敛性由定序保证，客户端不做冲突合并。
2. **笔划不可变**：`stroke` 一旦落定就只读；橡皮是删除操作，不是覆盖。
3. **白板不知道游戏**：游戏通过 `BoardPlugin` + 写权限策略钩子接入，卸载游戏后白板照常运行。

## 版本

| 项 | 值 | 说明 |
| :--- | :--- | :--- |
| `schemaVersion` | 1 | 文档 JSON 结构版本 |
| `protocolVersion` | 1 | WS 协议版本（首帧 `hello` 可协商，暂固定） |

## 重新出图

图源文件 `.puml` 与导出图 `.png` 放在同一目录，**改完 `.puml` 必须重新导出同名 PNG**（文档里引用的是 PNG）。

- 常规做法：IDEA 装 PlantUML 插件，右键 `.puml` → `Save as PNG`。
- 状态图文件头有 `!pragma layout smetana`，不装 Graphviz 也能排版。
- 若用命令行：`java -jar plantuml.jar -charset UTF-8 -tpng -o docs/diagrams docs/diagrams/*.puml`
  （Java 17 下若报 PNG 模块错误，加 `--add-exports java.desktop/com.sun.imageio.plugins.png=ALL-UNNAMED`）

