# DeskCubby Web — 实现约定（所有贡献者必读）

本文件是 `web/` 目录的唯一契约。Android 端（`android/app/src/main/java/com/deskcubby/app/`）是**唯一行为参考**；`README_for_ai.md` 是逐页面交互教学。不得擅自删减、简化或重新设计功能。

## 1. 技术栈与命令

- 后端：Python FastAPI + sqlite3(stdlib, WAL) + httpx + cryptography + Pillow。入口 `web/backend/app/main.py`，uvicorn 运行。
- 前端：React 18 + TypeScript + Vite + react-router-dom v6 + zustand + lucide-react + react-markdown。入口 `web/frontend/src/main.tsx`。
- 开发：`cd web/backend && uvicorn app.main:app --reload --port 8787`；`cd web/frontend && npm install && npm run dev`（dev 代理 `/api` 到 8787）。
- 校验：后端 `python3 -m compileall app` 与 pytest；前端 `npm run typecheck && npm run build`。

## 2. 数据目录布局（默认 `<repo>/web/data`，env `DESKCUBBY_DATA_DIR` 可覆盖）

```text
data/
├─ deskcubby.db            # SQLite（表名/列名镜像 Room 实体）
├─ workspace/
│  ├─ diary/               # Markdown 日记（yyyy-MM-dd.md），source of truth
│  │  └─ .trash/           # 日记回收站
│  ├─ media/               # 图片媒体 + dc-media.json (v2)
│  │  └─ .trash/
│  ├─ notes/               # Obsidian 兼容笔记库
│  ├─ books/               # 阅读的 TXT/PDF 私有存储
│  └─ .deskcubby/          # 结构化记录工作区: settings.json fields.json records.json statistics.json
├─ backups/                # 自动备份 JSON (v34)
├─ private/                # 服务端私有: reading/v1/progress.json、engagement、usage 导入缓存等
└─ uploads/                # Agent/AI 附件暂存
```

## 3. 数据保真要求（最高优先级）

1. **设置**：服务端以 DataStore 同构的键值保存（snake_case 键名 = Android `SettingsRepository.Keys`）。对外 API 用 camelCase 的 AppSettings 形状。所有字段名、枚举值、默认值、上下限必须与 `AppModels.kt` / `SettingsRepository.kt` 一致。
2. **Room → SQLite**：表名、列名与 `Entities.kt` 完全一致（flash_thoughts、thought_categories、browser_records、diary_index、date_records、poetry_categories、saved_poems、ai_conversations、ai_messages、ai_attachments、agent_runs、agent_tool_events、agent_mutations、vault_items、game_states、game_statistics、structured_record_files、structured_record_occurrences）。时间一律 epoch millis 整数。
3. **dc-media.json v2**：键为小写媒体文件名；格式与 `MediaMetaJsonCodec.kt` 一致；读改写加锁、输入上限、回读校验、保留未知字段。
4. **备份 JSON v34**：导入支持 v1–v34，导出固定 v34；形状以 `BackupJsonCodec.kt` 为准。
5. **结构化记录**：`<!--dc:f_<id>-->value<!--dc:/f_<id>-->` 写入 Markdown HTML 注释；`.deskcubby/*.json` 格式与 Android 一致；日界线默认 05:00。
6. **阅读进度**：`private/reading/v1/progress.json`，URI-free 指纹账本（完整文件 SHA-256+类型+页/段位置）。
7. API Key 等敏感配置只存服务端；任何 GET 响应不得返回 apiKey 明文（写入用专用端点，读取返回空串）。

## 4. 后端模式

- 路由文件放 `app/routers/<domain>.py`，用 `APIRouter(prefix="/api/<domain>", tags=[...])`；在 `main.py` 注册（骨架已留好 import 行，按字母序补）。
- DB 访问：`from ..core.db import get_db`（FastAPI 依赖，返回 sqlite3.Connection，row_factory=Row）。写操作用 `with con:` 事务。禁止 fallbackToDestructive 类行为——schema 由 `core/db.py` 的 `SCHEMA_SQL` + 显式迁移管理。
- 文件操作统一走 `core/fs.py` 的路径安全函数（拒绝 `..`、绝对路径、越出根目录）；日记/媒体写入用“临时文件→回读 SHA-256 校验→提交”流程（`fs.safe_write`）。
- 统一错误：`core/errors.py` 的 `ApiError(status, code, message)`；处理器转 `{ "error": { code, message } }`。错误信息不得包含正文、绝对路径、密钥。
- 外部网络（RSS、AI、每日诗词、WebDAV）：仅经 `core/http.py` 的受限客户端（超时、重定向限制、响应体上限、HTTPS 默认）。
- 认证：可选密码。开启后除 `/api/auth/*`、静态资源外的所有端点需会话 Cookie（HttpOnly）。依赖注入 `user=Depends(require_auth)`；中间件已整体拦截，路由无需重复处理。
- 后台任务：`services/background.py` 中 asyncio 任务（自动备份、RSS 刷新、热量估算队列）。启动时创建，关闭时取消。

### API 总表（camelCase JSON；未列明的 CRUD 均按 REST 惯例）

```text
GET  /api/system/info                     # 版本、部署提示(是否检测到反代/https/公网)、数据目录占用统计
GET/PUT /api/settings                     # AppSettings 全量(camelCase)；PUT 为合并更新；敏感字段见 §3.7
POST /api/settings/background-image       # multipart 上传全局背景 -> {uri}
GET  /api/auth/status                     # {enabled, authenticated, deployment:{behindProxy,scheme,suggestPassword,suggestHttps}}
POST /api/auth/set-password|login|logout|change-password|disable
# 日记 diary
GET  /api/diary/documents?query=&month=   # DiaryDocument[]（来自 diary_index）
GET  /api/diary/document?name=            # DiaryEditorDocument{content, sha256,...}
POST /api/diary/documents                 # {dateIso?, name?, template?} 创建
PUT  /api/diary/document                  # {name, content, previousSha256?} -> 200 或 409{currentSha256,content,lastModified}
DELETE /api/diary/document?name=          # 移入回收站
GET  /api/diary/trash                     # DiaryTrashItem[]
POST /api/diary/trash/restore{name=} DELETE /api/diary/trash/item?name=
GET  /api/diary/recent?limit= GET /api/diary/random GET /api/diary/stats  # 总篇数/字数/连续天数/本月篇数
GET  /api/diary/export/meal-calendar.png?start=&end=&options...
# 结构化记录 structured
GET  /api/structured/config               # .deskcubby/settings.json+fields.json 合并视图
PUT  /api/structured/day-boundary {hours} PUT /api/structured/fields {fields[]}
GET  /api/structured/records?fromDay=&toDay= POST /api/structured/records {journalDay, fieldId, rawValue}
GET  /api/structured/statistics           # 字段统计+派生指标
POST /api/structured/reindex
# 吃历/媒体 meals & media
GET  /api/meals/calendar?from=&to=&categories=   # 按日期分组照片+kJ（MealCategory.sortOrder）
GET  /api/meals/photo-meta?file=          # dc-media.json 单条
PUT  /api/meals/photo-meta                # {fileName, energyKj?, note?, location?...}
POST /api/media/upload?category=          # multipart -> ImportedMedia{fileName, markdown}
GET  /api/media/file?path=rel&size=thumb  # 受限图片读取（需认证）
GET  /api/media/thumbs?paths=a,b
# 笔记 notes
GET  /api/notes/tree                      # 文件夹+Markdown 树
GET  /api/notes/file?path= PUT /api/notes/file {path, content, previousSha256}
POST /api/notes/folder {parent,name} POST /api/notes/file-create {parent,name}
POST /api/notes/rename {path,newName} DELETE /api/notes/node?path=
POST /api/notes/media-upload              # multipart+targetFolder -> 相对链接 markdown
GET  /api/notes/search?q=
# 小巧思 thoughts
GET/POST /api/thoughts  PUT/DELETE /api/thoughts/{id}   # 回收站 deletedAt 过滤参数 ?trash=1
POST /api/thoughts/{id}/restore  DELETE /api/thoughts/{id}/permanent
POST /api/thoughts/{id}/pin|highlight|move {value|categoryId}
POST /api/thoughts/reorder [{id,sortOrder}]
GET/POST/PUT/DELETE /api/thought-categories (+reorder)
# 日期记录 dates、诗词 poetry、浏览器记录 browser
GET/POST /api/date-records  PUT/DELETE /api/date-records/{id}
GET/POST /api/poetry/poems PUT/DELETE /api/poetry/poems/{id} POST /api/poetry/poems/reorder
GET/POST/PUT/DELETE /api/poetry/categories  POST /api/poetry/categories/{id}/poems-move
GET  /api/poetry/presets  POST /api/poetry/presets/import {presetId}
GET  /api/poetry/daily                    # 今日一首（轮换+去重+内置库兜底）
GET/POST /api/browser/records             # browser_records 历史/收藏 favorite 参数
# RSS
GET  /api/rss/feeds                       # 设置中的订阅源
POST /api/rss/refresh                     # 服务端抓取全部启用源 -> items（内存缓存+磁盘缓存）
GET  /api/rss/items?feedId=
# AI / Agent
GET  /api/ai/conversations  POST /api/ai/conversations {title,modelConfigId}
GET  /api/ai/conversations/{id}/messages  DELETE /api/ai/conversations/{id}
POST /api/ai/chat                         # SSE 流式；body{conversationId?, content, attachmentIds[], configId?}
POST /api/ai/attachments                  # multipart -> {id, displayName, mimeType, sizeBytes, kind, extractedText?}
GET  /api/agent/runs?conversationId=      GET /api/agent/runs/{runId}  # 含 tool events
GET  /api/agent/mutations?runId=          POST /api/agent/mutations/{id}/undo
GET  /api/agent/pending-approvals         POST /api/agent/approvals/{toolCallId} {approve}
POST /api/agent/run                       # SSE；body{conversationId?, content, configId?, sourceAuthorizations{}, permissionMode?}
POST /api/agent/cancel/{runId}
GET  /api/agent/token-stats               POST /api/calorie/estimate {dateIso}  GET /api/calorie/status?dateIso=
# 收藏夹 vault（服务端加密）
GET  /api/vault/status                    # {hasPassword, unlocked}
POST /api/vault/setup|unlock|lock|change-password {password,...}
GET/POST /api/vault/items  PUT/DELETE /api/vault/items/{id}  POST /api/vault/items/reorder
# 阅读 reader
GET  /api/reader/books  POST /api/reader/books (multipart txt/pdf)
DELETE /api/reader/books/{id}  GET /api/reader/books/{id}/content?format=
GET/PUT /api/reader/progress              # reading/v1/progress.json 形状
GET  /api/reader/engagement  POST /api/reader/engagement {bookId, seconds}
# 游戏 games / 统计 statshub / 使用 usage / 健康 health
GET/PUT /api/games/states/{gameId}        # {highScore, saveJson}
GET  /api/games/statistics  POST /api/games/statistics {gameId, metricKey, value(add)}
GET  /api/statshub/overview               # 聚合各模块摘要
GET/POST /api/usage/import                # 上传 Android usage/v1 或 v20-v28 usageDevices JSON
GET  /api/usage/overview?days=&deviceId=  GET  /api/usage/devices
GET/POST /api/health/import  GET /api/health/overview?days=
# 云同步 cloudsync（WebDAV/S3）
GET  /api/cloudsync/status                # 配置脱敏+最近结果
POST /api/cloudsync/configs  PUT/DELETE /api/cloudsync/configs/{id}
POST /api/cloudsync/sync {configId, mode: now|force_upload|force_download}
POST /api/cloudsync/undo                  # 撤回一次
# 备份 backup
GET  /api/backup/export?includeSecrets=0  # v34 JSON 下载
POST /api/backup/import                   # multipart -> 预览
POST /api/backup/import/commit            # 确认恢复（单事务替换+回滚保护）
GET  /api/backup/auto  PUT /api/backup/auto {enabled, dirUri, keepCount}  POST /api/backup/auto/run
# 桌面小组件设计 widgets
GET/PUT /api/widgets/configs              # desktopWidgetConfigs 存于 settings，独立便捷端点
```

## 5. 前端模式

- 页面放 `src/pages/<feature>/XxxPage.tsx`；在 `src/App.tsx` 路由表注册（route 名 = NavItemId.route，如 `/home`、`/thought`…）。骨架已含懒加载占位，实现者替换对应文件即可，不要改别人的页面。
- 文案一律 `tr("中文", "English")`（`src/i18n/tr.ts`）；五语翻译表 `src/i18n/translations.json` 已从 Android `AppTranslations.kt` 自动转换，直接受益；新增文案先写双语，能对上 Android 原文案就逐字使用。
- 颜色只消费 CSS 变量 token（`src/theme/tokens.css`）：`--dc-primary, --dc-on-primary, --dc-secondary*, --dc-surface, --dc-surface-container, --dc-surface-variant, --dc-on-surface, --dc-on-surface-variant, --dc-outline, --dc-background, --dc-on-background, --dc-error, --dc-radius, --dc-spacing, --dc-font-scale, --dc-motion-scale`。三风格差异由 `[data-style="material|liquid-glass|organic-future"]` 选择器表达；普通组件禁止硬编码颜色。
- 顶栏用共享 `<TopBar title actions/>`；对话框用共享 `ConfirmDialog`/自建受控 dialog；底部导航栏与「导航」聚合页由 shell 提供（`NavItemId` 驱动，与 Android 默认显隐一致）。
- 设置子页模式：本地草稿 state + 右上角保存按钮 + dirty 离开确认（shell 已提供 `useDirtyGuard`）。
- 响应式：≥1024px 双栏/侧栏布局可用 CSS grid；600–1024 平板；<600 手机单列。底栏导航 <768 显示。PWA manifest/SW 已就位。
- API 访问统一 `src/api/client.ts`（fetch 封装，401 时跳登录）；类型定义在各域 `src/api/types.ts`。

## 6. 文件所有权（避免冲突）

- core/shell（main.tsx、App.tsx、theme、i18n、stores、components/、api/client.ts）：仅 scaffold 作者改。
- 各 feature 子目录内文件归对应实现者；跨域复用组件放 `components/` 时须为新增文件。
