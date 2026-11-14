# Henu-SE-Project-Group9
河南大学软件工程小组作业

**在线协同白板 + 你画我猜部件**：白板是平台，游戏是通过插件接缝挂上去的部件。

## 目录结构

| 目录 | 内容 |
| :--- | :--- |
| [`docs/`](docs/00-README.md) | **契约文档 + 技术栈说明**（改协议先改这里） |
| [`backend/`](backend) | Spring Boot 3.5.16 + 原生 WebSocket + JPA（H2 开发 / MySQL 生产） |
| [`frontend/`](frontend) | Vue 3.5 + TypeScript + Vite 8，画布内核为**框架无关的纯 TS** |
| [`scripts/`](scripts/smoke-ws.mjs) | 端到端联调脚本（REST / WS / 版本归档） |
| [`deploy/`](deploy/README.md) | 部署配置与**运维手册**（部署者使用） |

## 快速开始

前置：JDK 17、Node 20+。

> 前端锁定 **Vite 8**（`vite: ^8.0.0`）。Vite 7 依赖 esbuild 子进程，在受限沙箱/CI 里会 `EPERM`，**不要降级**。

```powershell
# 终端 1：后端（自带 Maven Wrapper，无需安装 Maven）
cd backend
.\mvnw.cmd spring-boot:run          # 监听 8080，WS 路径 /ws

# 终端 2：前端
cd frontend
npm install
npm run dev                          # 监听 5173，已代理 /api 与 /ws 到 8080
```

浏览器打开 <http://localhost:5173>，**再开一个窗口访问同一个 `?board=` 即同房间**，两边画画应该实时可见。

## 页面结构

| 路由 | 页面 | 说明 |
| :--- | :--- | :--- |
| `/login` | 登录 / 注册 | 可游客进入 |
| `/home` | 主界面 | 三个入口：画画 / 联机 / 你画我猜（V2） |
| `/draw` | 画画 | 作品网格 + 全部 / 已归档 / 回收站 + 版本历史抽屉 |
| `/collab` | 联机 | 选板建房（归档板"复制后联机"）+ 新建 + 邀请链接 |
| `/board/:id` | 画布 | 协同编辑、远端光标、实时保存、缩略图 |
| `/guess` | 你画我猜 | 占位，V2 实现 |

右上角齿轮打开**设置抽屉**：远端光标颜色 / 透明度 / 大小 / 昵称开关 / 深色模式（只影响本机，刷新保持）。

## 联调自检

后端启动后，在仓库根目录执行：

```powershell
node scripts/smoke-api.mjs        # 身份 + 作品库 REST（18 项）
node scripts/smoke-ws.mjs         # 协同 + 断线补发 + 光标配色（11 项）
node scripts/smoke-versions.mjs   # 版本回滚 + 归档只读 + 回收站 + 复制（21 项）
```

## 当前进度

| 模块 | 状态 |
| :--- | :--- |
| 契约（数据模型 / 协议 / 状态机 / 版本归档） | ✅ |
| 后端：房间、定序、重连补发、权限、用户体系、作品库 | ✅ |
| 后端：版本历史与回滚、归档/回收站、复制、只读强制 | ✅ |
| 前端：主界面 / 作品库 / 联机 / 画布 / 远端光标 / 设置抽屉 | ✅ |
| 你画我猜部件 | 🚧 入口已留，状态机见 [docs/03](docs/03-游戏状态机.md) |
| 部署 | 📄 见 [`deploy/README.md`](deploy/README.md) |

## 约定

- 画布坐标一律**世界坐标**；屏幕坐标只存在于渲染层。
- 收敛性由**服务端全序（seq）**保证，客户端不做冲突合并（详见 [docs/01](docs/01-数据模型.md) §4）。
- 白板内核不知道游戏的存在；游戏通过 `BoardPlugin` + 写权限策略接入。
