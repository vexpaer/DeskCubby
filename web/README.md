# DeskCubby Web

以 Android 端为唯一参考的完整 Web 复刻：React + TypeScript 前端、Python FastAPI 后端。
当前对齐目标为 Android 0.23.5（备份 JSON v34、dc-media.json v2、`.deskcubby` 结构化记录工作区、`reading/v1/progress.json`、Android manifest + immutable blobs + record-level 云同步等），目标是与 Android/Windows 端直接同步或迁移。

## 0.23.5 追平与对抗式修复

- 修复 Material、Liquid Glass、Organic Future 风格枚举到 CSS 属性的映射；三种风格现在会真实改变表面、圆角、边框和层次。普通按钮不再用可能被混合输入设备保留的 hover/active 背景，只有明确的语义选中项才常驻底色；Service Worker 对页面导航使用联网优先，新 Worker 接管旧安装时会清理旧版本缓存并刷新页面。
- PWA、登录、首次语言选择与 About 页统一使用仓库 `.github/logo.png` 派生的 192/512/maskable Logo；Service Worker 缓存版本随 0.23.5 更新。
- 首次进入支持五语言选择；导航标签、深色系统变化、横竖屏偏好、窄屏 More 页编辑/拖动/键盘排序及设置 dirty 返回拦截已补齐。
- Android 0.23.3 的日期语义已对齐：结构化首页按自然日期写入，日记编辑器写入当前文档，`todayDiarySwitchTime` 只影响“今日日记”的打开日期；设备本地设置不进入可同步设置。
- Android 0.23.4 的 Agent 已对齐：附件完整持久化并原子消费、客户端预生成稳定 Run ID、Android 状态值与 `started`/`tool_event` SSE、断流后后台继续、重进页面从 Run/工具账本恢复转圈与审批、完成后自动回载最终消息、无 tool-calling 模型普通聊天降级，以及 v34 Agent Run 导入恢复。服务进程重启时无法安全续跑的遗留 Run 会明确收敛为失败，避免重复执行 mutation。
- Android 0.23.5 云同步采用单一条件发布 manifest、不可变内容 blob 和记录级适配器；WebDAV 使用强 ETag，S3 使用条件对象版本，并同步阅读偏好、阅读进度和 Agent 会话等数据库记录。思想/诗词分类作为隐藏父依赖自动加入。
- Usage 保留 Android 的 platform、zoneId、OPEN/FINAL、采集时间与 tracking/backfill 水位，逐日合并遵循 FINAL 优先和 collectedAt LWW；全局设置记录同步保留本机 AI Key，删除云配置若设置写入失败会恢复凭据。
- Agent system prompt 包含 Android 的十条硬规则、仅元数据的数据源目录、全局 Agent 提示词及模型附加指令；Token 缓存率按 cached-input/对应-input 分母计算，Review 详情读取真实 `{run, toolEvents}` DTO。
- 阅读偏好由服务端保存可同步子集，浏览器本地只保留自定义章节正则与标题长度；支持六种阅读背景、ARGB 规范化、书架进度百分比、自定义整行章节规则和设置保存/取消草稿。
- 设置首页与 Android 一致只显示「外观与语言、子页面设置、应用数据、底部导航、关于」五个一级分类；主页、日记与媒体、浏览器、小巧思、收藏夹、诗词、RSS、AI、导航页、使用时间和健康均在「子页面设置」内二次进入，主页问候归主页、页面教学归关于、云同步归应用数据。
- 本机安装模式支持在「设置 → 子页面设置 → 日记与媒体 → 保存位置」选择真实日记/媒体文件夹。路径仅存当前电脑，不进入 v34 或云同步；切换时不移动、删除旧文件，日记目录会立即重新扫描。
- 主页饮食图片的六个餐别按钮默认显示 emoji；既有未修改的文字默认会一次性升级为 emoji，仍可在「设置 → 子页面设置 → 主页 → 饮食按钮」切换文字/图标并自定义符号。

## 运行

### 个人电脑安装（Linux）

需要 Python 3.11+、Node.js 18+ 与 npm。执行：

```bash
cd web
./install.sh
```

安装器默认把命令写到 `~/.local/bin/deskcubby`，程序和隔离 Python 环境写到 `~/.local/share/deskcubby/`，运行数据保存在 `~/.local/share/deskcubby/data/`。如果 `~/.local/bin` 尚未加入 `PATH`，安装器会打印需要加入 shell 配置的一行命令。

安装完成后直接运行：

```bash
deskcubby
```

命令默认只监听 `127.0.0.1:8787`，服务就绪后自动打开系统浏览器；正常启动和 HTTP 200 访问不会刷 Uvicorn INFO/访问日志，终端仅保留 DeskCubby 地址、数据目录和错误。按 `Ctrl+C` 停止，可用 `deskcubby --no-browser` 只启动服务；重新运行 `install.sh` 可更新程序且不会覆盖用户数据。

安装器会先使用当前系统的 pip 配置；若配置的镜像连接失败，会忽略该配置，依次尝试官方 PyPI 与阿里云 HTTPS 镜像，并在 pip 支持时启用断点续传。需要指定其他可信 HTTPS 镜像时，可设置 `DESKCUBBY_PIP_FALLBACK_INDEX_URL`。依赖未安装成功时不会切换当前已安装版本。

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
- Docker/NAS/VPS 不会开放服务器桌面文件夹选择器；日记和媒体默认位于挂载数据目录，也可由管理员设置 `DESKCUBBY_DIARY_DIR`、`DESKCUBBY_MEDIA_DIR`。只有 `install.sh` 安装后通过回环地址启动的个人电脑模式允许页面调用本机选择器。
- 反向代理（Caddy/Nginx）只需转发到 8787 并保留 `/api` 前缀；建议公网部署启用 HTTPS。

## 登录认证（可选）

- 默认本机/局域网直接访问，无需密码。
- 设置 → 应用数据 → 访问密码 中可开启；开启后所有页面、API、图片、附件与 Agent 接口都要求服务端会话认证（HttpOnly Cookie，服务端仅存 PBKDF2 哈希）。
- AI API Key 按 Android 产品规则在已打开的配置详情中完整明文显示并保存；WebDAV/S3 凭据仍只写入服务端且不会回传。公网或不受信局域网部署必须开启访问密码并使用 HTTPS。

## 目录结构

```text
web/
├─ backend/app/        # FastAPI：core(配置/DB/安全/受限HTTP) + routers + services
├─ frontend/src/       # React 页面、主题 token、i18n(tr 五语)、stores
├─ install.sh          # Linux 用户级安装器
├─ scripts/deskcubby   # 安装后的 8787 启动/浏览器打开命令
├─ docs/CONVENTIONS.md # 实现契约（API 总表、数据保真要求）
├─ Dockerfile / docker-compose.yml
└─ data/               # 运行时数据（git 忽略）：workspace/diary|media|notes|books、deskcubby.db、backups、private
```

详细 API 与数据结构约定见 [docs/CONVENTIONS.md](docs/CONVENTIONS.md)。
