# 部署手册

> 目标：把「前端静态 + 后端 jar + MySQL」这套东西放到一台 Linux 服务器上，公网能访问、别人能扫码一起画。

## 0. 现在能部署吗？

| 条件 | 状态 |
| :--- | :--- |
| 前端可构建 | ✅ `npm run build` 通过 |
| 后端可打包 | ✅ `mvn package` 通过 |
| 用户/作品库接口（Sprint A） | 🚧 **等联调通过再上**，否则线上登录不可用 |
| MySQL profile | ✅ `application-mysql.yml` 已存在 |
| 数据库迁移 | ✅ Flyway 启动自动建表 |

**结论**：环境可以先准备；正式部署等 Sprint A 联调完（当天）。

## 1. 选 Nginx 还是别的

| 方案 | 适合 | 说明 |
| :--- | :--- | :--- |
| **Nginx**（本手册默认） | 课设答辩、要写进文档 | 资料多，但 WebSocket 必须手写 `Upgrade` 头 + 长超时 |
| Caddy | 想要自动 HTTPS、少写配置 | 两行搞定反代和证书，`reverse_proxy` 自动处理 WS |
| 只跑 `java -jar` + `vite preview` | 临时演示 | 不能上公网，前端端口和后端端口分离会有跨域 |

> 你们 SRS 里写的是 Nginx，就按 Nginx 来，答辩时也更好讲。

## 2. 先在本地跑通（部署前必做）

```powershell
# 终端 1：后端
cd backend
.\mvnw.cmd spring-boot:run

# 终端 2：前端
cd frontend
$env:npm_config_cache = "D:\Henu-SE-Project\.dsh-tmp\npm-cache"
npm.cmd run dev

# 终端 3：自检
node scripts/smoke-api.mjs
node scripts/smoke-ws.mjs
```

三个都绿了再上服务器，否则线上排查成本翻倍。

## 3. 方案 A：裸机部署（推荐，最直观）

> 适用于 Ubuntu 22.04 / 24.04，2C2G 学生机即可。

### 3.0 阿里云 Ubuntu 特别注意

| 项 | 做法 |
| :--- | :--- |
| **安全组** | 控制台 → 实例 → 安全组 → 入方向放行 `22`、`80`（以后加 `443`）。**不放行 80，浏览器就是"无法访问"** |
| **系统防火墙** | Ubuntu 默认 `ufw` 是关闭的；若你开过：`sudo ufw allow 22,80/tcp` |
| **apt 源** | 阿里云镜像已内置，直接 `apt update` 即可 |
| **公网 IP** | 用弹性公网 IP；重启实例 IP 可能变，绑定后固定 |
| **未备案** | 用 `http://公网IP` 访问完全没问题；**只有"域名指向大陆服务器"才需要备案** |
| **备案后** | 域名 A 记录 → 备案通过 → 再加 443 + 证书（§5.2） |

### 3.1 服务器准备

```bash
# 安全组放行 22 / 80（443 以后再放）
sudo apt update
sudo apt install -y openjdk-17-jre-headless mysql-server nginx

# 2G 内存机器建议加 1G swap，防止 MySQL + JVM 触发 OOM
sudo fallocate -l 1G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

### 3.2 建数据库

```bash
sudo mysql -e "CREATE DATABASE wb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
sudo mysql -e "CREATE USER 'wb'@'localhost' IDENTIFIED BY '换成强密码';"
sudo mysql -e "GRANT ALL ON wb.* TO 'wb'@'localhost'; FLUSH PRIVILEGES;"
```

### 3.3 建运行用户与目录

```bash
sudo useradd -r -m -d /opt/whiteboard wb
sudo mkdir -p /opt/whiteboard /var/www/whiteboard /etc/whiteboard
sudo chown -R wb:wb /opt/whiteboard
```

### 3.4 在本地构建并上传

```powershell
# 后端
cd backend
.\mvnw.cmd -DskipTests package
scp target\whiteboard-backend-*.jar root@服务器IP:/opt/whiteboard/app.jar

# 前端
cd ..\frontend
npm.cmd run build
tar -czf dist.tgz -C dist .
scp dist.tgz root@服务器IP:/tmp/
```

```bash
# 服务器上解压
sudo rm -rf /var/www/whiteboard/*
sudo tar -xzf /tmp/dist.tgz -C /var/www/whiteboard
sudo chown -R www-data:www-data /var/www/whiteboard
```

### 3.5 环境变量

```bash
sudo cp deploy/env.example /etc/whiteboard/wb.env
sudo nano /etc/whiteboard/wb.env          # 改 JWT 密钥和数据库密码
sudo chmod 600 /etc/whiteboard/wb.env
sudo chown wb:wb /etc/whiteboard/wb.env
```

JWT 密钥生成：`openssl rand -base64 48`

### 3.6 后端托管（systemd）

```bash
sudo cp deploy/systemd/whiteboard.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now whiteboard
sudo systemctl status whiteboard
sudo journalctl -u whiteboard -f
```

看到 `Started WhiteboardApplication` 即成功。首次启动 Flyway 会自动建表。

### 3.7 Nginx

```bash
sudo cp deploy/nginx/bare-metal.conf /etc/nginx/conf.d/whiteboard.conf
sudo rm -f /etc/nginx/sites-enabled/default     # 避免默认站点抢占
sudo nginx -t && sudo systemctl reload nginx
```

### 3.8 验证

```bash
curl -s http://127.0.0.1:8080/actuator/health      # 后端存活
curl -I http://服务器IP/                            # 前端页面
```

本地再跑一次联调脚本，直指服务器：

```powershell
node scripts/smoke-api.mjs http://服务器IP
node scripts/smoke-ws.mjs ws://服务器IP/ws
```

## 4. 方案 B：Docker Compose（一台机器一把梭）

适合不想手动装 MySQL/JDK 的情况。**先确认服务器能跑 Docker**（国内机器拉镜像可能需要镜像加速）。

```bash
# 本地先构建前端，容器直接挂载 dist
cd frontend && npm run build && cd ..

cd deploy
cp env.example .env
nano .env                                  # 改密码 + JWT 密钥
docker compose up -d --build
docker compose logs -f app
```

访问 `http://服务器IP/`。

第一次拉 `mysql:8.0` 和 `maven:3.9-eclipse-temurin-17` 比较慢（约 1–2 GB），建议在本地先 `docker pull` 再 `docker save` 传过去，或配置镜像加速。

## 5. 域名与 HTTPS

### 5.1 要不要域名

| 情况 | 做法 |
| :--- | :--- |
| 只有 IP | 直接用 `http://IP`，WS 用 `ws://`，**前后端同源**，不会出混合内容问题 |
| 有域名 + 国内服务器 | **必须 ICP 备案**（7–20 个工作日），否则 80/443 会被拦 |
| 有域名 + 香港/海外 | 免备案，但延迟高，演示时注意 |

课设若时间紧，**建议直接用 IP + HTTP**，把精力放在功能上。

### 5.2 上 HTTPS 之后必须注意

前端已经适配：页面是 `https` 时自动用 `wss://`（见 `frontend/src/net/client.ts:712`）。但 Nginx 侧要确保：

- `location /ws` 有 `Upgrade` / `Connection` 头（本仓库配置已含）
- `proxy_read_timeout` 至少几十分钟（默认 60s 会掐断连接）

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d your.domain.com
```

## 6. 更新与回滚

```bash
# 更新
scp app.jar root@server:/opt/whiteboard/app.jar.new
sudo systemctl stop whiteboard
sudo mv /opt/whiteboard/app.jar /opt/whiteboard/app.jar.bak
sudo mv /opt/whiteboard/app.jar.new /opt/whiteboard/app.jar
sudo systemctl start whiteboard

# 回滚
sudo systemctl stop whiteboard
sudo mv /opt/whiteboard/app.jar.bak /opt/whiteboard/app.jar
sudo systemctl start whiteboard
```

前端更新：重新 `npm run build` → 覆盖 `/var/www/whiteboard`（无需重启 Nginx）。

## 7. 备份

```bash
# /opt/backup/wb-backup.sh
#!/usr/bin/env bash
set -euo pipefail
ts=$(date +%F-%H%M)
mysqldump -u wb -p"$WB_DB_PASSWORD" --single-transaction --routines wb | gzip > /opt/backup/wb-$ts.sql.gz
find /opt/backup -name 'wb-*.sql.gz' -mtime +7 -delete
```

```bash
sudo crontab -e
0 3 * * * /opt/backup/wb-backup.sh
```

演示前一天手动备份一次，答辩前一小时再备一次。

## 8. 排障速查

| 现象 | 原因 | 处理 |
| :--- | :--- | :--- |
| 502 Bad Gateway | 后端没起 / 端口不对 | `systemctl status whiteboard`、`curl 127.0.0.1:8080/actuator/health` |
| 页面能开，协作连不上 | Nginx 缺 WS 头或超时太短 | 检查 `location /ws` 的 `Upgrade`、`Connection`、`proxy_read_timeout` |
| 一直重连 | 后端拒绝 token / 路由错 | 浏览器 F12 → Network → WS，看 `error.code` |
| 刷新 404 | Nginx 缺 SPA 回退 | `try_files $uri $uri/ /index.html` |
| 数据库连不上 | 密码/编码/时区 | 确认 URL 带 `serverTimezone=Asia/Shanghai&useUnicode=true` |
| 内存打满 | MySQL + JVM 争抢 | `-Xmx512m`、`innodb_buffer_pool_size=128M`、加 swap |
| 时间差 8 小时 | 时区没设 | 容器/主机都设 `TZ=Asia/Shanghai` |
| 启动报 `Lock file exists: ...wb.lock.db` | 上次是**强制结束**的（kill -9 / 直接关窗口），H2 没来得及清理 | 正常停用 `systemctl stop`（SIGTERM 会清理）；已残留就删锁：`rm -f data/wb.lock.db` 再启动 |
| 日志出现 `H2 文件 ... NIO 判定为 false ... 数据库只读` | 当前运行在受限沙箱里（Java 的 `Files.isWritable` 被拦截，对**所有**文件返回 false） | 在你自己的终端/服务器终端跑即可；服务器用 MySQL 时完全不会出现 |

## 9. 没有服务器怎么办

| 方案 | 成本 | 限制 |
| :--- | :--- | :--- |
| 阿里云/腾讯云学生机 2C2G | 百元/年量级 | 大陆节点要备案；够用 |
| 学校实验室服务器 | 免费 | 可能要内网穿透 |
| Cloudflare Tunnel（本机暴露） | 免费 | 电脑必须开着，适合演示兜底 |
| Render / Railway / Fly.io | 免费额度 | 有休眠、WS 时长限制，国内访问慢 |

**答辩兜底方案**：本地起服务 + Cloudflare Tunnel，提前 30 分钟开好；再录一段屏，防止现场网络抽风。
