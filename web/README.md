# DeskCubby Web

以 Android 端为唯一参考的完整 Web 复刻：React + TypeScript 前端、Python FastAPI 后端。
功能范围与数据格式对齐 Android 0.20.1（备份 JSON v34、dc-media.json v2、`.deskcubby` 结构化记录工作区、`reading/v1/progress.json` 等），目标是未来可与 Android/Windows 端直接同步或迁移。

## 运行

### 本机开发

```bash
# 后端（默认数据目录 web/data，可用 DESKCUBBY_DATA_DIR 覆盖）
cd web/backend
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8787

# 前端（dev 代理 /api -> 127.0.0.1:8787）
cd web/frontend
npm install
npm run dev
```

生产模式：`npm run build` 后由 FastAPI 直接托管 `frontend/dist`，访问 http://127.0.0.1:8787 即可。

### Docker / NAS / VPS

```bash
cd web
docker compose up -d --build   # 一条命令启动；数据全部落在 ./data
```

- 数据、SQLite 数据库、图片附件、备份均通过挂载目录 `./data` 持久化，备份 = 复制该目录。
- 反向代理（Caddy/Nginx）只需转发到 8787 并保留 `/api` 前缀；建议公网部署启用 HTTPS。

## 登录认证（可选）

- 默认本机/局域网直接访问，无需密码。
- 设置 → 应用数据 → 访问密码 中可开启；开启后所有页面、API、图片、附件与 Agent 接口都要求服务端会话认证（HttpOnly Cookie，服务端仅存 PBKDF2 哈希）。
- 检测到公网部署时应用内会提示开启密码与 HTTPS。
- 无论是否开启密码，AI API Key 等敏感配置只保存在服务端，任何接口都不会把明文返回给浏览器。

## 目录结构

```text
web/
├─ backend/app/        # FastAPI：core(配置/DB/安全/受限HTTP) + routers + services
├─ frontend/src/       # React 页面、主题 token、i18n(tr 五语)、stores
├─ docs/CONVENTIONS.md # 实现契约（API 总表、数据保真要求）
├─ Dockerfile / docker-compose.yml
└─ data/               # 运行时数据（git 忽略）：workspace/diary|media|notes|books、deskcubby.db、backups、private
```

详细 API 与数据结构约定见 [docs/CONVENTIONS.md](docs/CONVENTIONS.md)。
