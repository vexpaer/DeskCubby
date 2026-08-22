# DeskCubby 项目概览

## 1. 项目定位

DeskCubby 是一个本地优先、可高度定制的个人记录应用，仓库同时包含 Android 原生客户端和 Windows 桌面客户端。两个客户端都把 Markdown 日记与媒体目录视为用户数据的 source of truth；数据库保存结构化记录、设置和可重建索引，不替代真实文件。

Android 通过 Storage Access Framework（SAF）访问用户目录，并用 Room 保存小巧思、分类、浏览记录、日期记录、诗词、AI 对话等数据。Windows 通过受限 Tauri IPC 让 Rust 后端访问用户选择的普通目录，并用 SQLite 保存核心结构化数据；React 前端不获得任意文件系统权限。

两个客户端都提供 Material、Liquid Glass 和 Organic Future 三套预设视觉风格；Android 另有只映射到 Compose 主题角色的受控 Custom 风格。两端均支持简体中文/繁体中文/英文/韩语/日语、深浅色模式和字号缩放（Android 0.16.0 起支持五种语言；首次启动会先让用户选择语言）。当前版本为 Android **0.20.1**、Windows **0.8.0**。Android 当前应用备份格式为 v34（0.20.0 起：手动备份不再包含 AI API Key、SAF URI 与云凭据，新增 URI-free Agent 对话载荷）；Windows **0.8.0** 追赶 Android 数据类型进度：支持导入 Android v1–v33 并统一导出 v33（补齐 v30 Agent 来源授权/权限模式/模型工具能力、v31 AI 页面字号/回复框宽度/Agent 提示词/导航页列数与模块颜色、v32 桌面小卡片使用时间范围、v33 应用模块内容类型与云同步归一映射），Reader 内部状态 schema 升至 v5 并携带与 Android 一致的 0/5/…/95 页内偏移。注意：Windows 0.8.0 尚不支持导入 Android v34 JSON（需等待 Windows 侧跟进）。

## 2. Android 技术栈

| 层 | 技术 |
|---|---|
| UI | Kotlin、Jetpack Compose、Material 3、Navigation Compose |
| 状态 | ViewModel、StateFlow、Lifecycle Compose |
| 依赖注入 | Hilt |
| 结构化数据 | Room，数据库版本 13；手机使用时间、其他设备缓存、健康每日统计、小游戏特色累计统计、Agent 会话/运行/用量/Review 以 Room 为运行时权威 |
| 设置 | Preferences DataStore |
| 文件 | Storage Access Framework、DocumentFile |
| 后台任务 | WorkManager（逐任务持久 AI 队列、自动备份、可选云端周期同步、可选本机统计补采） |
| 系统统计 | UsageStatsManager（应用使用时间）、Health Connect（每日步数、距离与活动热量） |
| 图片 | Coil 3 |
| Markdown | CommonMark |
| 网络 | `HttpURLConnection`（普通请求）、OkHttp（仅 WebDAV `PROPFIND`）、`org.json`、XmlPullParser |
| 构建 | Gradle Kotlin DSL、JDK 17、compile/target SDK 36、min SDK 26 |

Android 工程位于仓库的 `android/` 目录，包含 application module `:app` 与内部扩展契约 module `:plugin-api:core`。后者只提供插件生命周期、API 契约和 Compose UI contribution 类型；现有业务仍全部留在 `:app`。

### Windows 技术栈

| 层 | 技术 |
|---|---|
| 桌面壳与 IPC | Tauri 2，版本化 command DTO，最小 capability 与 CSP |
| UI | React、TypeScript、Vite、React Router、Radix UI、Lucide |
| 状态 | Zustand |
| 编辑与预览 | CodeMirror 6、React Markdown（CommonMark） |
| 拖动排序 | dnd-kit |
| 后端 | Rust、Serde、SHA-256、`image`、`notify`、`reqwest`、Windows API、Tauri updater |
| 结构化数据 | `rusqlite` / SQLite；WAL、外键、事务迁移与 busy timeout |
| 构建 | pnpm、Cargo、固定 `stable-x86_64-pc-windows-msvc` 工具链 |

Windows 工程位于 `windows/`，产品标识符为 `com.deskcubby.windows`。前端只提交业务意图；目录选择、文件 I/O、数据库、HTTPS、备份影子和 DPAPI 均由 Rust 完成。

## 3. 目录结构

```text
DeskCubby/
├─ AGENTS.md                 # 后续 AI/开发者的仓库操作指南
├─ overview.md               # 本文件：架构、功能和历史概览
├─ README.md                 # 用户向功能、构建与发布说明
├─ README_for_ai.md           # 完整使用教学（About 页内联渲染 + Agent app_guide 源），镜像到 android assets
├─ LICENSE
├─ android/                  # 完整 Android Gradle 工程
│  ├─ app/
│  │  ├─ build.gradle.kts    # Android、依赖、签名与 APK 名称
│  │  ├─ schemas/            # Room 导出的历史 schema
│  │  └─ src/
│  │     ├─ main/
│  │     │  ├─ AndroidManifest.xml
│  │     │  └─ java/com/deskcubby/app/
│  │     │     ├─ agent/       # Agent Runtime、模型/上下文/权限/工具/Review 分层
│  │     │     ├─ data/
│  │     │     │  ├─ backup/    # JSON 导入导出与自动备份
│  │     │     │  ├─ local/     # Room entities、DAO、database、migrations
│  │     │     │  ├─ model/     # AppSettings、导航及跨层数据模型
│  │     │     │  ├─ preferences/ # DataStore 设置
│  │     │     │  ├─ repository/  # 日记、网络和各业务数据访问
│  │     │     │  ├─ statistics/  # 使用时间/步数模型、独立 JSON、采集与 Worker
│  │     │     │  └─ sync/        # WebDAV/S3 同步、冲突检测、凭据与后台任务
│  │     │     ├─ di/           # Hilt providers
│  │     │     └─ ui/
│  │     │        ├─ Navigation.kt
│  │     │        ├─ components/ # 跨页面 Compose 组件
│  │     │        ├─ theme/      # 三套主题、颜色、字体、tr()
│  │     │        └─ <feature>/  # Screen + ViewModel
│  │     ├─ test/                # JVM 单元测试
│  │     └─ androidTest/         # Room/Android/备份仪器测试
│  ├─ plugin-api/core/           # Plugin/PluginContext/PluginManager 与十类扩展 API 契约
│  ├─ gradle/wrapper/        # Gradle Wrapper
│  ├─ scripts/               # 发布签名等 Android 辅助脚本
│  ├─ release/               # 本地发布密钥目录；敏感文件不进 Git
│  ├─ build.gradle.kts
│  ├─ settings.gradle.kts
│  └─ gradlew / gradlew.bat
└─ windows/                  # Tauri 2 + React/TypeScript + Rust 桌面客户端
   ├─ src/                   # React 页面、组件、状态、IPC 类型与三套 CSS 主题
   ├─ src-tauri/
   │  ├─ capabilities/       # 最小 Tauri capability
   │  ├─ icons/              # Windows ICO 与打包图标
   │  ├─ src/                # Rust commands、SQLite、文件、媒体与备份
   │  ├─ Cargo.toml
   │  └─ tauri.conf.json
   ├─ package.json
   ├─ pnpm-lock.yaml
   └─ rust-toolchain.toml    # stable-x86_64-pc-windows-msvc
```

## 4. 总体数据流

### Android

```text
Compose Screen
    ↓ 用户事件 / collectAsStateWithLifecycle
Feature ViewModel
    ├────────────────────→ Repository
    └→ Agent Runtime ─→ Model Client / Context Provider / Tool Registry
                         ├→ Permission Manager ─→ 审批 UI
                         ├→ Tool Executor ─→ Review / Undo Store ─→ Room v13
                         └→ AI/Data/File/App API ─→ app Adapter ─→ 原 Repository/Service

Repository ─────────────→ Room DAO
    ├───────────────────→ DataStore
    ├───────────────────→ Room 统计表 / 应用私有阅读与娱乐 JSON
    ├───────────────────→ SAF 日记/笔记 Markdown / media files
    ├───────────────────→ UsageStatsManager / Health Connect（步数、距离、活动热量）
    ├───────────────────→ HTTPS API
    └───────────────────→ 可选 WebDAV / S3 兼容服务

未来 Plugin ──→ PluginContext v2 ──→ Diary/Vault/Media/Sync/AI/Data/File/App/UI/Storage API
                                         ↓
                                     app Adapter ──→ 原 Repository/Service
```

- `Navigation.kt` 在应用根部创建主要 ViewModel，因此从嵌套页面返回时可继续使用当前日记编辑状态等共享状态。
- Screen 只负责界面草稿和交互；持久化、并发控制、输入上限和失败恢复应放在 ViewModel/Repository。
- Android 结构化记录以 `.deskcubby/fields.json` / `records.json` 为共享模板来源；首页、记录页与系统桌面 Widget 使用同一套数据。Widget 渲染只做只读快照，点按后才执行初始化/旧模板迁移并通过 `StructuredRecordsRepository` 写入带稳定字段标记的 Markdown；工作区写入在 `DiaryFileRepository` 的共享 mutex 下原子化并广播失效事件，因此同根目录云同步和多个 ViewModel 不会靠各自缓存互相覆盖。
- 日记文件是正文 source of truth。`DiaryIndexEntity` 用于首页统计和列表加速，写入文件后应重新扫描更新。笔记库同样以用户选择目录中的真实 Markdown/文件夹为权威，但不进入日记索引，也不按日期分组。
- 云同步由设置页、应用首页“立即同步”与“强制上传/下载”两个模块、系统桌面同步组件或 `CloudSyncWorker` 触发，经 `data/sync/` 与同一串行 WorkManager 队列协调远端清单和冲突检测；本地日记与媒体仍只通过 `DiaryFileRepository` 访问 SAF。阅读进度使用独立 `reading/v1/progress.json`，经 `ReaderProgressJsonCodec` 与 `ReaderRepository` 的 URI-free 指纹账本合并，不进入日记/媒体文件清单。
- `plugin-api/core` 仍是既有非 AI 功能的旁路扩展层；生产 Hilt 插件集合为空，测试源集中的 `TestPlugin` 只验证生命周期。Android Agent 成为首个直接消费其稳定业务契约的生产子系统：`AgentModule` 绑定接口与 app adapter，Agent Runtime 不注入各业务 Screen、ViewModel、Repository、DAO 或底层存储。
- Agent 每轮只由 `AgentContextProvider` 取得已授权来源的类型、计数、分类、标题/日期范围等轻量 metadata 与工具说明；正文由模型通过分页、限量的 list/search/read 工具按需取得。`AgentPermissionManager` 在执行每个 mutation 前落实需要批准/全自动策略，`AgentReviewRepository` 在真实修改成功后保存 before/after、Undo token 与工具执行记录。

### Windows

```text
React Page / Zustand
    ↓ 版本化 Tauri IPC DTO
Rust command boundary
    ├────────→ SQLite (%LOCALAPPDATA%\com.deskcubby.windows\deskcubby.db)
    ├────────→ 用户选择的 Markdown / media / backup 目录
    ├────────→ DPAPI 加密的 v29 兼容影子与导入前恢复点
    ├────────→ DPAPI 加密的云凭据、usage/health 私有缓存与来源元数据
    ├────────→ 用户显式选择的 Android usage/health 兼容文件（只读）
    ├────────→ WebDAV / S3 兼容服务（HTTPS 默认、限额与冲突副本）
    ├────────→ 生产签名包中的 HTTPS updater manifest / 安装包
    └────────→ 今日诗词 / Hitokoto 诗词分类 / 古诗词·一言 HTTPS API（超时、响应上限、本地缓存与内置诗库回退）
```

- 前端没有任意路径读写权限；错误只返回稳定错误码和可展示信息，不回传日记正文、路径、凭据或备份中的秘密字段。
- 日记扫描只建立索引。读取时返回 `FileVersion { sha256, size, modifiedAt }`，保存前再次比较；冲突由界面让用户选择重新加载、明确覆盖或另存副本。外部删除时，重新加载会接受删除，明确覆盖可崩溃安全地重建同名文件，另存副本不依赖已删除的原文件。
- 安全写入采用临时文件写入、回读校验和提交；回收站、媒体元数据与自动备份同样避免把多步骤操作误报为成功。
- 本地媒体只可通过受限读取路径/协议访问；解析后的目标必须仍位于已选择的媒体根目录内，并拒绝 `..`、符号链接或 junction 越界。
- 收藏夹只在 Rust 边界解密；SQLite 保存 AES-256-GCM 密文、nonce、KDF 元数据和顺序，派生密钥仅保留在解锁进程内存中。改密使用 generation/revision 检查并在一个事务中整体替换，损坏或并发冲突时不部分提交。
- Windows 0.8.0 的使用时间页可显示 Android v33 `usageDevices`、兼容快照/只读链接及用户明确启用的按设备云对象；健康页只显示用户明确选择的兼容文件。二者都不调用 Windows 活动或健康采集 API，链接刷新从不写入源文件，失败保留上次有效快照。
- 云同步凭据使用当前 Windows 用户的 DPAPI 加密，前端只知道“是否已配置”。远端应用 JSON 只会校验并暂存，必须预览确认后才恢复；后台同步不得自动覆盖结构化数据。
- 生产更新配置只由受保护的 SignedRelease 注入。已配置包启动约 60 秒后进入自动检查调度；每次尝试都先把时间戳持久化到 SQLite，再越过网络边界，因此失败或重启也不会在 24 小时内重复自动检查。自动检查只展示提示；用户确认后才下载、验证 Tauri updater 签名、安装并重启。检入的基础配置没有公钥或端点，本地构建不会联网检查。

## 5. 功能与代码地图

| 功能 | UI / ViewModel | 数据与关键说明 |
|---|---|---|
| Kotlin Plugin API v2 | 当前无插件用户入口；`plugin-api/core`、`app/plugin/`、`di/PluginModule.kt`、`di/AgentModule.kt` | `PluginManager` 管理注册/加载/卸载；`PluginContext` 提供 Diary/Vault/Media/Sync/AI/DeskCubbyData/File/App/UI/Storage 十类契约。适配器委托现有安全边界；非 AI 页面调用链不迁移，Android Agent 通过接口注入消费 Data/File/App/AI，UI contribution 仍只注册、不接入导航 |
| 顶层导航与聚合页 | `ui/Navigation.kt`、`ui/components/PageTutorialOverlay.kt`、`ui/more/MoreHubScreen.kt` | `NavItemId` 定义主页面；默认开启的教学模式按稳定页面 ID 为主路由、嵌套路由、设置子页、阅读状态与具体小游戏各显示一次蒙版，确认只存设备本机；导航页按设置使用一列/两列/三列独立高度的瀑布流，平时卡片不显示手柄，长按模块进入布局更改模式后所有卡片出现四点手柄并可拖动排序，右上角对勾退出；每个模块可单独设置按钮底色、模块整体底色、名称和描述 |
| 首页 | `ui/home/HomeScreen.kt`、`HomeViewModel.kt`、`data/sync/AppCloudSyncService.kt` | 可配置模块、快速小巧思、饮食图片、日常记录；笔记入口、从八个小游戏/变体中自选的快捷入口和日记/小巧思/日期记录概览；24 条中性默认问候及可增删改的双语问候模板。饮食图片的交互忙碌状态只覆盖图片与今日日记的真实落盘，成功后立即恢复餐别按钮；可选 AI 热量估算与索引扫描继续后台执行，不再让页面持续转圈。首页的“立即同步”与“强制上传/下载”模块共享串行队列、进度、待确认 JSON 和持久化的最近完成结果；0.16.2 起完成后明确显示上传、下载与冲突文件数，进程重启后仍可见 |
| Desk | `ui/desk/DeskScreen.kt`、`DeskViewModel.kt`、`ui/desk/components/`、`ui/desk/model/` | 个人数字桌面，把今天留下的日记、小巧思、照片、事件和痕迹摊成一张编辑式「桌面」而非列表；只用现有 repository/DAO 聚合（`DiaryIndexDao`、`FlashThoughtDao`、`DateRecordDao`、`DiaryFileRepository.scanMealCalendar`/`load`），不新建数据系统。日期即页面标题，Diary 是带轻高度与微小旋转的「纸张」主对象，Idea/Photo 各自极简呈现，Today Traces 用排版而非卡片建立层级；右上角 ✦ 打开轻量 AI 浮层并转入既有 AI 页，底部低调 `+` 展开 Quick Capture 到既有创建流程；内容随当天数据动态改变信息层级，无数据时留白即空状态。对象旋转与选择用日期+内容 ID 生成可重现 seed，早晨/午后/傍晚/深夜仅做极轻微环境色倾移 |
| 日记与吃历 | `ui/diary/DiaryScreens.kt`、`DiaryViewModel.kt`、`data/repository/DefaultDiaryFolderSetup.kt`、`ui/components/MarkdownPreview.kt` | 未配置日记目录时可由用户在系统 SAF 选择器确认本机 Documents，一次创建并绑定 `Documents/deskcubby/diary` 与 `Documents/deskcubby/media`，也保留手动选择入口；`DiaryFileRepository` 负责 SAF、SHA 冲突、媒体、回收站、索引、预览照片地点和按日范围导出吃历 PNG 长图；Markdown 源码普通文字使用完整编辑宽度，删除/拖动控件仅在独占媒体行尾部局部覆盖；吃历用单次 SAF 子项元数据快照同时建立日记/媒体索引，返回嵌套页面时复用已加载结果，内部日记变更用进程内 revision 失效；共享预览保留 CommonMark 块级/行内格式并按 H1–H6 设置应用独立字号；热量估算仍按日期串行，但同一天最多 3 张图片并行识别，随后只调用一次文字模型统一计算并一次性保存；运行卡可展开并发图片与全日文字模型的用时、流式 reasoning/回复；`dc-media.json` v2 用输入上限、previous/pending 与回读校验保护更新 |
| 笔记 | `ui/notes/`、`data/repository/NotesRepository.kt`、`ui/components/MarkdownPreview.kt` | SAF 直接打开用户选择的 Obsidian 笔记库，文件夹优先、按名称顺序列出 Markdown；支持新建/重命名/删除文件夹和笔记、自动保存、SHA-256 外部修改冲突的加载/覆盖/另存副本；兼容标准 Markdown 图片与 `![[Wiki 嵌入]]`，每次上传媒体都由用户重新选择当前笔记库内的目标文件夹并写入可移植相对链接；不复用日记媒体目录、不建立日期索引 |
| 阅读 | `ui/reader/`、`data/repository/ReaderRepository.kt`、`data/sync/ReaderProgressJsonCodec.kt`、`data/statistics/EngagementTimeRepository.kt` | SAF 持久读取 TXT/PDF；增强视图使用 PDFium（`io.legere:pdfiumandroid:1.0.35`），通过 SAF 文件描述符直接打开且不复制原书，提供连续纵向按需渲染、缩放、页码、双色映射、文字搜索跳页与文本目录扫描，失败时安全切换系统 `PdfRenderer` 连续兼容视图。0.13.1 将 TXT、增强 PDF 与兼容 PDF 的本机恢复位置细化为页内 5%，约 600ms 防抖并在离开时检查点保存；0.13.2 让纯净模式控件悬浮覆盖固定正文平面，并把 engagement 私有文件升至 schema v2，为已有时长的书保留最后书名。0.15.0 把 PDF 双指缩放改为矩阵实时变换：手势期间内容围绕捏合中心实时跟随手指且不重新渲染，手势结束后才提交最终比例并重新渲染页面。0.16.0 让提交后按捏合中心锚定滚动位置，重载后的页面与松手时大小、位置一致；缩放滑杆按 1% 步进。0.16.3 用共享 `ReaderPdfContinuousViewport` 彻底替换增强/兼容视图各自的 nested-scroll 补丁：在方向锁定前读取原始 X 并独立结算横移，Y 继续交给 `LazyColumn` 以保留纵向惯性，单指斜向或画圈仍会同时移动两轴，任一轴到边界不吞另一轴；双指接管同一二维移动并增加围绕质心的即时缩放。专用 `ReaderPdfPageViewport` 继续以非约束测量让 100% 以上按目标像素宽度真实渲染，缩到视口内则居中并清除旧横向偏移。PDF 自动封面先取已验证缓存/提供方缩略图，缺失时再串行受限渲染第一页；封面文字可独立编辑、隐藏或恢复书名。Reader schema-v8 兼容 v1–v7；封面文字、更细偏移、书名和时长只留在 Android 私有状态，v33 与可选 `reading/v1/progress.json` 继续按原有页/段字段和完整文件 SHA-256+类型合并，不携带书名、URI、封面、正文或时长 |
| 吃历滤镜 | `ui/diary/filter/`、`data/model/MealPhotoFilterSettings.kt` | 统一亮度、对比度、饱和度、色温和色调；仅改变 Compose 显示，不改原图 |
| 结构化记录 | `ui/structuredrecords/`（原日常记录） | 在 Markdown 日记中嵌入 word / number / type / time / duration 五类字段，以稳定字段 ID 写入 HTML 注释，正文仍可读。首页和 Widget 明确写入设备真实本地日期；从日记编辑器进入则写入当前打开日记的日期，不受“今日日记切换时间”影响。写入、编辑、媒体删除和重命名后只更新对应 Markdown 的通用日记索引与结构化索引，不做全目录扫描；Room 投影仍可从 Markdown 重建。`.deskcubby/` 保存 fields/records/statistics 等工作区文件；设置页管理自动睡眠/醒来估算、字段与索引重建，统计页提供自动统计和派生指标 |
| 小巧思 | `ui/thought/` | `ThoughtRepository` + Room；分类、排序、回收站、页面恢复；首次进入全部/分类页自动定位到列表底部的新内容，新增条目继续精确滚动；顶栏可立即切换一行/完整显示 |
| 浏览器 | `ui/blog/` | `BrowserRepository` + WebView；多标签、收藏、历史、上传下载 |
| 日期记录 | `ui/date/` | `DateRecordRepository` + Room |
| 诗词本/每日诗词 | `ui/poetry/`、`PoetryBookRepository.kt`、`PoetryPresetCatalog.kt`、`PoetryRepository.kt`、`PoetryTypography.kt` | 自定义分类的筛选、颜色、增删改与单篇归类；排序以稳定 ID 处理第一个位置；分类删除可在“诗词归入未分类”和“连诗词删除”间选择；11 个离线预设共 182 篇古诗文；每日诗词轮换今日诗词、Hitokoto 诗词分类、古诗词·一言，按本地日期去重并以内置诗库持续兜底 |
| RSS | `ui/rss/`、`ui/blog/` | `RssRepository` 支持 RSS 2.0/Atom，仅 HTTPS；有效文章链接进入应用内多标签 WebView 阅读完整原文，文章列表缓存当前在内存中 |
| 收藏夹（隐私） | `ui/vault/`、`data/repository/VaultRepository.kt`、`data/vault/VaultCrypto.kt` | 新密码 1 个 Unicode 码点起且无最大长度；PBKDF2-SHA256 120k 次 + AES-GCM 密文存 Room，盐/校验值在独立 DataStore；卡片只在有备注时显示备注，长按进入复制/编辑/删除，四点手柄持久排序；v30 只备份密文、IV、盐、校验器及 generation，不备份密码或派生密钥 |
| 小游戏 | `ui/games/`、`games/`（纯 Kotlin 引擎） | 4×4/5×5/6×6 版 2048、贪吃蛇、俄罗斯方块、自定义扫雷、横屏单花色蜘蛛纸牌，以及 `GoGame.kt` / `GoGameScreen.kt` 的本地双人 9/13/19 路围棋；围棋实现提子、禁自杀、简单劫与连续两次停着结束，只统计提子而不自动判定地域胜负。0.12.0 让合法落子/停着发布独立 `snapshotCopy()` 以触发 Compose 重绘，并用统一棋盘几何把点击吸附到最近交叉点，消除圆形命中区间死区。2048 数字按位数/格宽单行缩放，`moveAttempts` 与 `effectiveMoves` 继续分开统计；旧 `losses` 不再写入或展示。既有七个游戏/变体的最高分、存档与特色战绩独立存 Room 并进入 v30；围棋存档、特色统计与主页围棋快捷入口只保存在 Android 本机并从 v30 投影排除；游玩时长另存排除备份的私有 JSON |
| 统计中心 | `ui/statshub/`、`data/statistics/GameStatisticsRepository.kt`、`AgentTokenStatisticsRepository.kt` | 汇总日记篇数、字数与连续记录，手机使用时间、Health Connect 健康、阅读/游戏时长、小游戏特色战绩和 Agent 用量；Agent 只累计 Provider 实际报告的模型调用、input/output/total/cached/reasoning Token，未报告调用单列并计算缓存率，不按文本估算 |
| 手机使用时间 | `ui/usage/`、`ui/statistics/`、`data/statistics/UsageStatisticsRepository.kt`、`UsageDeviceRepository.kt`、`UsageEventAggregator.kt` | 从 UsageEvents 重建本机历史并按本地午夜切分；Room v13 保存本机/外部设备规范历史并作为唯一运行时权威；稳定随机设备 ID 和可编辑设备名把历史按设备分组，页面可查看单设备或所有设备汇总；v30 与 `usage/v1/{deviceId}.json` 的外部 JSON 格式不变 |
| 健康 | `ui/steps/`、`ui/statistics/`、`data/statistics/StepStatisticsRepository.kt` | 经授权仅从 Health Connect 聚合每日步数、距离和活动热量，Room v13 保存每日历史；页面按指标切换总览、图表和明细；状态/授权说明卡位于页面内容最下方，不再申请活动识别或使用系统计步传感器 |
| 桌面小卡片与同步组件 | `ui/widgets/`、`widget/`、`DesktopWidgetInstanceStore.kt`、`DesktopWidgetUpdateWorker.kt`、`DesktopWidgetActionWorker.kt`、`DesktopWidgetInteractionActivity.kt` | 每个 App Widget ID 保存“绑定模板 ID + 最后有效完整快照”；音乐服务也优先读取该精确实例快照，切换模板后不会被旧模板回写覆盖。编辑器用下拉菜单选择“主页模块 / 应用模块 / 应用按钮”，主页与应用模块使用彼此独立的白名单。0.16.2 将音乐重写为占满卡片、无文字/自身背景的频谱、波形或曲线，并按实际小组件尺寸有界绘制；使用时间提供无背景/坐标/文字的纯色块、纯折线、纯柱状三种图表，范围为 7/30/90 天，采集成功后主动刷新。饮食图片只显示无底色、整体垂直居中的 emoji。快速输入按实例保存设备本机草稿，点纸飞机加入未分类，长按私有编辑器主操作可选已有分类；受 `RemoteViews` 限制，真实键盘输入仍由非导出 Activity 承载。2048 只保留上/下/左/右四个透明触控区，没有任何新局入口；渲染器也拒绝旧 `NEW` PendingIntent，避免启动器缓存旧视图时覆盖存档。`RemoteViews` 不提供任意四向滑动回调，因此系统桌面不能实现等同 Compose 页面的原生滑动。实例快照 schema 升至 2 并继续读取 schema 1；草稿与实例状态不进 Android 备份或设备迁移 |
| 更新检查与安装 | `data/repository/UpdateRepository.kt`、设置 About 页 | GitHub Releases latest API；检测到新版后下载精确匹配 APK 到私有缓存，限制 HTTPS 重定向/大小并校验包名、版本与当前签名，再交给系统安装器 |
| 取色器/缩放查看 | `ui/components/ColorPickerDialog.kt`、`ZoomableImageDialog.kt` | HSV 同行滑杆 + Compose 蜂窝色盘 + hex 取色（强制不透明）；吃历照片全屏缩放查看复用滤镜 |
| AI Agent | `ui/ai/`、`agent/`、`data/taskqueue/`、`data/repository/AiChatRepository.kt` | User→LLM→Tool Call→Execution→Result→LLM 循环由持久 Room 任务和每任务唯一 WorkRequest 驱动；Agent 与热量/图片 AI 可并行，页面切换、旋转或进程重建后从任务、工具事件与用量账本恢复状态。工具模型继续使用审批、Review/Undo、参数上限和 12 轮保护；旧 `supportsToolCalling=false` 配置退化为不调用工具的普通聊天，不解析文字冒充工具。四方块入口提供最多 5 个附件、8 类数据源授权和需要批准/全自动模式 |
| Agent Review / Undo | `ui/ai/AgentReview*`、`agent/AgentReviewRepository.kt`、Room `agent_*` 表 | 按 Run/会话记录全部工具事件和每项实际 mutation 的目标、摘要、before/after、状态与 Undo token；需要批准逐项弹窗，全自动仍完整记录。Undo 在当前内容仍匹配 Agent 结果时执行对应数据/SAF 文件/设置恢复，不以删除日志冒充恢复 |
| AI 密钥 | 设置页 | Key 是 `AiModelConfig` 的明文字段，随 DataStore 与 v30 JSON 备份保存；旧加密值仅做一次性迁移。`supportsToolCalling` 是显式能力字段，旧配置默认 false |
| 云端同步 | `ui/settings/`、`ui/home/HomeScreen.kt`、`data/sync/`、`data/model/CloudSyncModels.kt` | 设置页把立即同步与“撤回一次”组成等宽操作行：首页也显示最近一次完成的上传、下载与冲突文件数。`AppCloudSyncService` 将这些安全计数和完成时间持久化到排除备份的运行时状态，多服务结果聚合后写入，进程重启仍可恢复；撤回快照恢复被覆盖的本地日记并把本轮新建文件移入回收站，仅保留最近一次。既有日记/媒体/应用 JSON/usage/阅读同步边界不变；可选 Agent 会话 `agent/v1/chats.json` 继续以稳定 sync ID、LWW 和 tombstone 合并文字、冻结文档文字、图片占位、完成 Run 与 Provider 用量，不同步本机 URI/图片字节、Review/Undo 载荷或秘密 |
| 设置与数据占用 | `ui/settings/`、`data/repository/AppDataUsageRepository.kt` | 外观页新增 Custom：Material/Glass/Organic 基础渲染、浅/深八类颜色角色及有界圆角/边框/阴影/不透明度/间距/动效；只映射 Compose token，不执行 CSS/脚本/网络资源，低对比度保存时安全归一化。既有设置草稿、右上角保存、全局背景与占用统计边界不变 |
| 底栏音乐可视化 | `ui/components/MusicVisualizer.kt`、`MusicSpectrum.kt`、导航设置 | 用户显式开启并授予 `RECORD_AUDIO` 后，用 Android `Visualizer` 的全局音频会话实时绘制直方图、波形或平滑曲线；频谱按对数频带重采样，可自适应当前有效频段或手动设置 20–20,000 Hz 上下限，避免能量只挤在左侧；底栏不再绘制持久选中椭圆，可视化随生命周期、权限和系统动画状态停止捕获且不保存音频 |
| 备份 | `data/backup/` | 当前 Android JSON 格式 v33 并兼容 v1–v32；v31 已含 AI 页面字号/回复框宽度/Agent 提示词、导航页列数与模块按钮/卡片底色，v32 为桌面小卡片新增使用时间范围字段，v33 引入桌面小卡片「应用模块」内容类型并把旧独立云同步小组件模块（`cloud_sync_now`/`cloud_sync_force`）归一为合并的 `cloud_sync`。Agent 会话/附件/Run/Review/Undo、书架/书名/URI/封面/阅读偏好/时长、每个桌面实例快照及围棋数据仍排除。Windows 0.8.0 可导入 v1–v33 并统一导出 v33；Room 保持 v13，Markdown、媒体和其他既有格式不变 |
| 主题 | `ui/theme/`、`ui/components/AppBackground.kt` | `tr()`/`translate()` 提供五语（简体中文/繁体中文/英文/韩语/日语，未翻译文案回退简体中文，翻译表在 `AppTranslations.kt`）；三套预设风格继续使用主色+副色，Custom 经受控 `CustomThemeSettings` 生成 Material color scheme、shapes 与 visual token，并用全局动画比例驱动页面切换；SAF 全局背景与紧凑模式继续复用原边界 |

### Windows 功能与代码边界

| 功能 | 前端 | Rust / 持久化边界 |
|---|---|---|
| 应用壳与导航 | `windows/src/App.tsx`、`components/AppShell.tsx`、`components/desktopNavigationModel.ts`、i18n 与主题；左侧竖栏默认分记录、资料库、订阅与智能、娱乐与统计、工具，支持页面显隐/排序/换类、自定义分类和分类折叠，窄窗折叠为抽屉 | 导航配置与折叠状态保存在本机 WebView 偏好且严格归一化；`hidden` 内容有显式 CSS 规则，折叠时页面按钮同时退出布局与键盘顺序；中部导航独立滚动，底部设置固定。Tauri 只暴露列入 invoke handler/capability 的 command；Windows 不提供 Android 内置浏览器和桌面小卡片 |
| 首页 | 可排序/显隐模块、问候、快速输入、笔记与小游戏快捷入口、记录概览 | 聚合日记索引、SQLite 结构化数据、主页设置与每日诗词缓存 |
| 日记与编辑器 | 日记分组、CodeMirror、React Markdown、冲突对话框；预览保留相对引用图片并支持百分号编码的中文/空格文件名，空解析结果进入明确不可用状态 | 扫描/读写/重命名、SHA-256 `FileVersion`、安全提交、回收站；图片仍经媒体根协议与叶文件名校验，不开放任意路径 |
| 媒体与吃历 | 筛选、2/3/智能图片换行、非破坏滤镜、日期卡片单/双列、全屏查看；双列按日期中点分栏且窄窗回退单列 | 安全导入与压缩、EXIF 方向/GPS、`dc-media.json`、受限 PNG 导出；滤镜/布局由独立原子 IPC 持久写入受管设置，不改原图 |
| 日常记录 | 模板编辑、`xx` 选择替换、写入目标 | Windows 沿用原模板写入；Android 侧已升级为「结构化记录」（类型化字段、`.deskcubby` 索引与统计），Windows 暂未跟进 |
| 小巧思 | 分类、置顶/重点、拖动与回收站 | SQLite CRUD、持久顺序、软删除与分类文本导出 |
| 日期记录/诗词 | CRUD、分类/排序、离线诗词预设、每日诗词详情 | SQLite v6；Android 同源预设；每日诗词受限 HTTPS、缓存与内置回退 |
| 笔记 | `NotesPage.tsx`；目录浏览、Markdown 源码/预览、图片、重命名与冲突选择 | `notes.rs`；全部路径限制在用户选择根目录，拒绝遍历、保留名与符号链接/junction 越界，写入使用 SHA-256 版本检查 |
| 阅读 | `ReaderPage.tsx`、`readerTextScroll.tsx`、`readerPdfViewer.tsx`、`readerAppearance.ts`、`reader.css`；TXT 为段落流无限滚动（逻辑页仅作窗口化单元，大书不会一次渲染全部段落），单行工具栏、浮层目录、整本搜索、悬浮专注模式，以及背景/前景/对比度、TXT 字体/对齐/版心/缩进/字距/留白。PDF 用 React-PDF/PDF.js 原稿或阅读配色渲染，提供文字层/批注、目录、密码、旋转、连续虚拟/单页、缩放与页间距；未手动旋转时不覆盖页面自身 /Rotate（修复带旋转属性的 PDF 一页正一页反）；CMap/标准字体/ICC/WASM 离线打包 | `reader.rs`；Reader IPC v3 显式序列化 `assetUrl`，受限 UUID 本机书库、私有 schema-v5 偏好/进度（携带 0/5/…/95 页内偏移）和只读 PDF protocol（Range/CORS）；使用 Android 同构完整文件指纹，URI-free 位置接入 v33 和可选 `reading/v1/progress.json`，书架/路径/正文仍不迁移 |
| RSS | `RssPage.tsx`；订阅 CRUD、刷新与文章列表 | `rss.rs`；仅公网 HTTPS，限制 DNS/重定向/DOCTYPE/响应体/并发/总时长；文章不跨重启缓存 |
| AI 与吃历估算 | `AiPage.tsx`、`AiSettingsPage.tsx`、`MealPage.tsx`；模型配置、聊天/历史/上下文、图片估算 | `ai.rs`；OpenAI-compatible 非流式边界、HTTPS 默认、大小/超时/重定向限制，Key 不进日志；AI 会话为 Windows 本机数据，普通配置与明文 Key 按 v28 往返 |
| 收藏夹 | `windows/src/pages/VaultPage.tsx`；解锁、正文/备注 CRUD、拖动与键盘排序、复制/打开链接、改密/锁定 | `windows/src-tauri/src/vault.rs` + SQLite 私有表；PBKDF2-HMAC-SHA256 120k、AES-256-GCM、会话密钥 zeroize；Windows/Android Vault 均在兼容影子、v28、恢复点与云上传边界清除 |
| 小游戏/统计中心 | `GamesPage.tsx`、`StatsPage.tsx`；既有七个游戏/变体与本地双人 9/13/19 路围棋、键盘玩法、特色统计、记录概览；围棋实现提子、禁自杀、简单劫和连续两次停着结束，不自动判断地域胜负 | `games.rs` + SQLite v7；既有游戏存档/最高分/特色统计按 v28 结构验证，2048 `moveAttempts` 往返、旧 `losses` 只兼容。围棋存档、最高提子、特色统计与游玩时长使用独立私有表，从 v28、恢复点、自动备份和应用 JSON 云同步结构性排除 |
| 手机使用时间（只读） | `UsagePage.tsx`；快照/只读链接、设备/范围/应用筛选、总览与图表 | `usage.rs`；兼容 Android 多设备历史与专用云 usage 下载，DPAPI 私有缓存，链接刷新永不写源文件；不采集也不上传，明细不进 v28/恢复点/应用 JSON |
| 健康（只读） | `HealthPage.tsx`；步数/距离/活动热量、范围与图表 | `health.rs`；只读用户显式选择的兼容文件和 DPAPI 私有缓存；不采集、不上传、不进 v28/恢复点 |
| WebDAV/S3 云同步 | `windows/src/pages/CloudSyncPage.tsx`；配置、凭据操作、立即同步、需确认的强制上传/强制下载、状态、远端 JSON 预览 | `windows/src-tauri/src/cloud_sync/`；DPAPI 凭据、HTTPS 默认、SHA-256/manifest/强 ETag、冲突副本、传输限额；`reading/v1/progress.json` LWW 合并；强制模式不传播删除，仍绑定远端版本/本机扫描快照，强制下载只接受一个启用来源；总开关启用后启动延迟约 2 分钟并按间隔调度 |
| 设置 | `windows/src/pages/settings/`；Android 风格主页、搜索、子页面层级、本地草稿、右上角保存、恢复默认、离开确认；桌面导航页管理全部 18 个主页面和自定义分类 | 三套主题/背景、主页、日记标题字号、诗词/吃历、收藏夹、AI、桌面导航与应用数据设置集中持久化；Windows 路径不覆盖 Android URI |
| v29 备份 | 导入预览、确认与结果；支持 v1–v29 并统一导出 v29 | 64 MiB/字段限制、事务替换、恢复点、DPAPI 兼容影子、三文件轮换；严格保留 Custom 主题、合并最多 500 条 URI-free `readerProgress`；v29 的桌面卡片外观字段校验与旧版升级默认；`configs_managed` 控制非秘密云配置所有权，所有边界清除云凭据、两端 Vault、usage/health 明细和来源路径 |
| 关于/更新 | `windows/src/pages/AboutPage.tsx`；配置状态、检查、版本确认、下载/安装进度 | `windows/src-tauri/src/updater.rs` 与 `windows/scripts/`；SignedRelease 强制 Tauri updater 私钥与 `.sig` 验证，Authenticode 可选；tag workflow 创建五资产 Draft 并使用独立 `windows-stable` 通道 |

Windows 0.8.0 对接 Android v1–v33 数据格式（旧版在内存中安全升级为 v33 并统一导出 v33），不建立 Android 内置浏览器或桌面小卡片。两端都有本地双人围棋，但 Android 与 Windows 各自把棋局与战绩保存在本机并从各自应用备份投影排除；Android 的主页围棋快捷入口也只留在 Android 本机，Windows 围棋从小游戏页进入。Windows 另外用独立 SQLite 私有表把围棋排除在恢复点、自动备份和应用 JSON 云同步之外。手机使用时间与健康只读取用户带到 Windows 或通过专用只读云对象下载的数据，不采集、不上传；浏览器/小卡片和未来未知字段只在清理私有数据后的 DPAPI 兼容影子中往返，不由 Windows UI 执行。

## 6. 持久化边界

### SAF 文件

- 日记 Markdown、媒体文件、日记回收站和导出的备份文件。
- 媒体元数据以后写入 `dc-media.json`；若目录中只有旧版 `deskcubby-media.json`，先兼容读取，并在下一次元数据更新时写入短文件名而不删除旧文件。
- 自动备份以后写入 `dc.json`、`dc.pending.json`、`dc.previous.json`，手动导出默认名为 `DC-yyyy-MM-dd.json`；旧版 `DeskCubby*.json` 仍可导入和参与首次轮换。
- 用户选择的目录 URI 通过 DataStore 保存，但只有仍具备持久授权时才使用。
- 日记保存使用 SHA-256 检测外部编辑；冲突时暂停自动覆盖。删除媒体时只接受媒体根目录的直接子文件名，先写入并回读不含该媒体全部引用的 Markdown，再删除真实文件和侧车条目；删除失败则恢复原引用并回读确认。
- 云同步扫描、读取和下载写入同样通过 `DiaryFileRepository`；双向冲突保留确定命名的冲突副本，成功写入日记后再重建索引。

### Room

数据库 `deskcubby.db` 当前版本为 13，包含：

- `FlashThoughtEntity`（含 v7 新增的 `highlighted` 重点标记）
- `ThoughtCategoryEntity`
- `BrowserRecordEntity`
- `DiaryIndexEntity`
- `DateRecordEntity`
- `PoetryCategoryEntity`（v9 新增）
- `SavedPoemEntity`
- `AiConversationEntity`、`AiMessageEntity`（v13 加入稳定 sync ID，会话加入 deletion tombstone）
- `AiAttachmentEntity`（图片/文档引用与冻结的有界文档文字）
- `AgentRunEntity`（运行状态、来源/权限快照和 Provider 用量）
- `AgentToolEventEntity`（有界工具执行记录）
- `AgentMutationEntity`（Review before/after、状态与 Undo payload）
- `VaultItemEntity`（收藏夹密文，v7 新增；v8 增加 `sortOrder`）
- `GameStateEntity`（小游戏最高分与存档，v7 新增）
- `UsageHistoryEntity`、`UsageDayEntity`、`UsageAppDurationEntity`（本机使用时间）
- `UsageDeviceEntity`（其他设备使用时间缓存）
- `StepHistoryEntity`、`StepDayEntity`（Health Connect 每日统计）
- `LegacyStatisticsMigrationEntity`（旧 JSON 幂等迁移标记）
- `GameStatisticEntity`（小游戏特色累计统计，v12 新增）

历史迁移 1→2 至 12→13 全部保留在 `AppDatabase.kt`。5→6 新增 AI 会话和消息表；6→7 为 `flash_thoughts` 增加 `highlighted` 列并创建 `vault_items`、`game_states` 两表。7→8 为收藏夹增加持久顺序。8→9 新增诗词分类表并重建旧诗词外键；9→10 增加诗词排序；10→11 新建使用时间、健康和迁移标记表；11→12 新增 `game_statistics`；12→13 在不删除旧 AI 数据的情况下加入 sync ID/tombstone、附件、Agent Run/usage、工具事件和 mutation Review/Undo 表及索引。没有 destructive migration。

### DataStore

`AppSettings` 的绝大多数普通设置、主页/导航顺序与聚合页选择、主页小游戏快捷入口、笔记库 URI、H1–H6 Markdown 标题字号、全局背景 URI/可见度/模糊、教学模式及设备本机逐页确认、底栏音乐可视化、2048 动画速度、主页双语问候模板、桌面小卡片设计、诗词本字体 URI、排版与七言换行设置、吃历滤镜、云同步元数据、多行日常事件模板、RSS 订阅、Agent 八类数据源授权、需要批准/全自动模式和模型原生工具能力保存在 Preferences DataStore。按产品要求，S3 Access Key ID、Secret Access Key 与 Session Token 也以明文写入该应用私有 DataStore，以便再次编辑时完整回显；它们不会进入日志或 DeskCubby JSON 备份。默认日记目录的一键设置另保存用户确认的 Documents 父树授权，用它验证派生的 `diary` / `media` 子树；父树授权属于设备本地信息，不进入 v30。自定义字体、笔记库、全局背景与小卡片背景图片只在仍有 SAF 持久读取授权时启用；每个 App Widget ID 的“绑定模板 ID + 最后有效完整快照”另存于设备私有 SharedPreferences，并排除 JSON、系统备份与设备迁移。模板保存成功后按 ID 更新匹配实例；模板删除后实例保留最后快照，只有删除桌面实例才移除其记录。`SettingsRepository.decode()` 是旧值、损坏值和输入上限的集中归一化入口。

插件 `StorageAPI` 使用由插件 ID 派生的独立应用私有 SharedPreferences 文件，不写入 `AppSettings`、Room 或 DeskCubby v30 JSON。当前生产插件集合为空，因此升级本身不会创建插件数据。

### Room 统计与应用私有 JSON

- Room v13 的 usage/history/apps/device 与 step/history/day 表是手机使用时间、其他设备缓存和健康每日统计的唯一运行时权威；`game_statistics` 以 `(gameId, metricKey)` 为联合主键保存小游戏特色累计值。`usage-statistics.json`、`filesDir/usage-device-histories/` 与 `step-statistics.json` 只作为升级迁移源；每个有效文件事务化、幂等导入并记录 marker，损坏文件原样保留且不会阻断其他有效文件。
- 使用时间/健康 JSON codec 继续保留给 v30 备份、导入导出、按设备云同步与旧格式兼容，外部 JSON schema 不变。Room 同时保留 `OPEN`/`FINAL`、采集时间、包名与每日指标等原语义；没有 Health Connect 聚合值时保持空值，不伪造 0。
- `agent_runs` 累计每轮模型调用次数和 Provider 返回的 input/output/total/cached-input/reasoning Token；`AgentTokenStatisticsRepository` 将有报告和未报告调用分开汇总，缓存率只用真实输入/缓存字段计算，不做字符数估算。`agent_tool_events` 保存读/写/网络状态摘要，`agent_mutations` 保存真正修改的 Review/Undo 状态。
- `filesDir/reader/reader-state-v1.json` 内部升至 schema v8 并读取 v1–v7：在既有不透明书籍/封面 URI、类型、页/段位置与偏好、书籍总页数、更新时间、完整文件 SHA-256+类型指纹和有界进度账本上，v7 增加 0/5/…/95 的本机页内偏移，v8 增加最多 120 个 Unicode 码点的封面文字覆盖（`null` 跟随书名、空串明确隐藏）；旧状态使用对应默认。页内偏移与封面文字都不进入 v33 或 `reading/v1/progress.json`。无图片 TXT 默认封面直接绘制封面文字；PDF 先复用受限缓存或文档缩略图，缺失时串行、受限渲染第一页并回读校验缓存，原书与手动封面均不复制或改写。
- `filesDir/engagement/engagement-times-v1.json` 内部 schema v2 分别保存每本书与每个小游戏的累计前台毫秒数，并为每本已有阅读时长的书保存最后已知书名；schema-v1 时长无损读取，仍在书架中的旧书会补齐标题。阅读页和游戏页每 30 秒检查点写入，离开页面或 Activity 退到后台时结束当前会话；使用 `elapsedRealtime` 避免用户修改系统时间影响单次计时。书架移除不删除这里的书名或时长，0.13.2 升级前已经移除且旧文件没有标题的记录无法反推书名，会显示明确旧版占位。
- 当天统计记录是可刷新、可替换的 `OPEN` 状态；已经完整读取成功的过去日期标记为 `FINAL`，以后不再重复计算。读取失败、权限被拒或数据源不可用时不会把该日标记完成。
- 阅读/娱乐 JSON 通过 pending、回读解码与提交实现安全更新；Android 系统备份同时排除这些文件、旧统计迁移源和整个 `deskcubby.db`（含 WAL/SHM/journal）。显式 v30 只迁移阅读的 URI-free 指纹进度，不迁移书架、封面、偏好或时长；健康历史和阅读/游戏时长仍排除。
- `UsageDeviceRepository` 在独立 DataStore 中保存本机稳定随机 UUID 与可编辑设备名，其他设备规范历史保存在 Room。导入 v30 或云端对象时按设备 ID/日期合并：`FINAL` 优先于 `OPEN`，同状态取 `collectedAt` 更新的记录；本机随机 ID 不会被备份覆盖。
- Android 0.4.0 删除了旧的 SAF「导出给 Windows」规范 v4 界面。用户可在手机使用时间页切换本机、其他设备或所有设备；要跨手机同步，需在具体 WebDAV/S3 配置中明确勾选“多设备使用时间”。
- 两个统计功能默认关闭。UsageStats 使用 Android 的“使用情况访问权限”特殊授权页；健康统计只请求 Health Connect 的步数、距离、活动热量读取权限及可用的后台读取权限，不申请 `ACTIVITY_RECOGNITION`。启用任一统计后，WorkManager 每 6 小时尝试补采；关闭开关停止后续读取但保留已有本机历史。

### 敏感设置与瞬时状态

- AI API Key 按产品要求以明文属于 `AiModelConfig`，随 DataStore 及 DeskCubby v30 JSON 备份保存；备份文件不得放进公开或共享目录。模型请求只在 Authorization header 使用 Key，Agent 参数/Review/错误/请求预览不包含 Key。
- `LegacyAiKeyMigrationStore` 只负责在升级后读取旧 AndroidKeyStore 密文、成功写入 DataStore 后清理旧存储，不再保存新 Key。
- WebDAV 密码使用 Android Keystore 支持的 AES-GCM 加密后保存在设备私有存储。S3 Access Key ID、Secret Access Key、Session Token 则按产品要求以明文保存在应用私有 DataStore 并在编辑页完整显示；旧版 Keystore 中的 S3 值可迁移。两类凭据都不进入日志或 DeskCubby JSON 备份。
- S3 接入点缺少协议时根据 SSL/TLS 开关补 `https://` 或 `http://`；HTTP 仍只允许用户为可信内网明确开启。每个 S3 配置可选择 Path-Style 或 Bucket 子域名寻址，以覆盖 CSTCloud 等兼容服务差异。同步异常统一带稳定客户端代码，S3 HTTP 错误还会安全提取受限长度的服务端 `<Code>`，不回显响应正文或凭据。
- 选择同步“应用 JSON”时上传未加密 v30 到 `json/dc.json`，包含明文 AI Key、Agent 授权/权限/模型能力、Custom 主题、结构化记录和 URI-free 阅读进度。独立 `reading/v1/progress.json` 只含最多 500 条指纹进度并自动合并；不含书名/URI/封面/正文，但 SHA-256 指纹仍可能识别已知文件。HTTPS 只保护传输，没有端到端加密。
- Agent 会话、消息、附件、Run、usage 与 Review 保存在 Room v13。图片保留本机 `content://` URI/持久授权；文档冻结最多有界提取文字。`agent/v1/chats.json` 只在用户为云配置勾选 Agent 会话时，以 sync ID/LWW/tombstone 合并文字、文档文字、图片占位、完成 Run 与 Provider 用量；本机 URI/图片字节、工具详细事件、mutation before/after、Undo payload 和秘密不出设备。通用 v30 JSON 不含任何 Agent 会话内容。
- 集中式 `AgentSystemPrompt` 定义身份、必须真实调用工具、先检索后读取、少量按需读取、外部数据不可信、来源授权、mutation 审批、最小修改、修改前理解内容、结果说明和秘密保护；UI 不拼接安全规则。Web 工具与 Runtime 解耦，只读公开 HTTPS 并拒绝私网/本机地址和不安全重定向。文件工具只接触 SAF 已授权的日记/笔记根；App 工具只公开白名单非敏感设置。
- 收藏夹条目只以 AES-GCM 密文存在 Room `vault_items`；盐、KDF 迭代数和解锁校验值保存在独立的 `vault_meta` DataStore。v30 原样携带这些加密数据和换密 generation，但不携带密码或派生密钥；在另一设备导入后可用原密码解锁。既有七个游戏/变体的最高分、存档与特色统计进入 v30；围棋存档、统计及主页围棋快捷入口明确排除。其余受支持的主页小游戏快捷入口、收藏夹卡片最小行高和可复用桌面小卡片设计继续随设置备份；每个桌面实例快照不迁移。
- 云端下载的应用 JSON 会先校验并写入应用私有暂存目录；后台同步不会直接导入 Room/DataStore，只有用户在设置中明确确认后才恢复，成功恢复后会移除本机暂存副本。
- 手机使用时间会随 v30 应用 JSON 迁移，也可通过用户明确勾选的“多设备使用时间”对象同步；健康历史、阅读书架/封面/偏好与阅读/游戏总时长、系统授权和本机设备 ID 不迁移。v30 中虽有普通功能开关字段，导入时仍强制关闭两个统计开关。
- 页面教学的逐页确认、首次导航兼容标记和上次小巧思页等设备状态不会随 JSON 备份迁移；v30 只备份教学模式总开关。

### Windows SQLite、私有数据与用户目录

- SQLite 位于 `%LOCALAPPDATA%\com.deskcubby.windows\deskcubby.db`，当前 schema 为 v7，保存 Windows 设置、日记索引、小巧思/分类、日期记录、诗词分类与顺序、日常模板、小游戏存档/统计、AI 会话、云同步非秘密配置/状态，以及收藏夹的 KDF 元数据和 AES-256-GCM 密文。6→7 迁移只新增围棋专用的 state/statistics/engagement 私有表；连接启用 WAL、foreign keys、busy timeout，schema 只通过事务迁移升级。
- 收藏夹新密码至少 1 个 Unicode 字符。PBKDF2-HMAC-SHA256 120,000 次与 16 字节随机 salt 派生 AES-256 密钥，每条和校验器使用独立 12 字节 nonce；密码、明文与派生密钥不实现日志输出，解锁密钥只驻留内存并在释放时清零。改密只有在全部条目解密/重加密成功且 generation/revision 未变化时才单事务提交。
- usage 与 health 使用 Windows 私有的原子替换容器；源元数据和数据分别以 purpose-bound DPAPI 加密。一次性快照不再读取源文件；只读链接路径不经 IPC 返回，刷新时拒绝符号链接/junction，源缺失、损坏、超限或读取中变化均保留上次有效快照并标记状态。usage 可只读合并专用云对象，但 Windows 绝不上传；health 不进入任何云路径。
- 云端密码、S3 Access Key/Secret Key/Session Token 使用当前 Windows 用户 DPAPI 加密；配置列表和错误只返回是否存在凭据，不回传秘密、端点响应体或本机路径。
- 日记、媒体、笔记库和可选备份目录由用户分别选择。Windows 保存的是规范化本机路径；Android 的 `diaryTreeUri`、`mediaTreeUri`、`notesTreeUri` 和 `poetryFontUri` 不会被解析或写成 Windows 路径。
- 日记 Markdown 与媒体仍原地读写，不复制整个目录。日记回收站位于日记目录中的专用子目录，索引可由扫描重建。
- 媒体元数据优先写 `dc-media.json` v2，并兼容只读旧名 `deskcubby-media.json`。键统一为小写媒体文件名；Windows 可显示已有热量/地点并通过用户配置的 AI 做明确发起的估算，新图片可读取 EXIF GPS，但不做在线反向地理编码。
- 桌面导航配置与分类折叠状态保存在本机 WebView 偏好，不进入 Android 兼容 JSON；读取时归一化未知/重复/缺项页面与分类，保证设置入口和安全回退始终可达。折叠分类的页面容器同时从视觉布局和键盘顺序移除。吃历滤镜、图片换行、说明与日期卡片单/双列通过受限 IPC 原子保存到 Windows 受管设置，不改变媒体字节。
- Windows 阅读私有账本使用与 Android 相同的完整文件 SHA-256+类型指纹。书架路径、书名、封面、正文和阅读时长保持本机私有，最多 500 条 URI-free 位置可经 v29 或独立 `reading/v1/progress.json` 合并。

### Windows v29 兼容备份

- 接受 Android `version: 1`–`29`，在内存中安全升级旧版并统一导出 `version: 29`。输入最大 64 MiB，对数组数量、字符串长度、枚举、重复 ID、关联关系以及 v29 的 Custom 主题、阅读进度、桌面卡片外观字段、诗词分类、小游戏和设置结构执行严格校验；旧版导入时按 Android 0.13.0 语义补齐 `desktopWidgetConfigs` 的 `showName`/`backgroundOpacityPercent`/`showIcon`/`textAlignment`/`textScalePercent` 默认值，并把 `cloud_sync` 主页模块改写为 `cloud_sync_now`。
- 预览阶段只返回统计与警告，不修改状态。确认导入时先在应用私有目录生成恢复点，再事务替换 Windows 管理的设置、thoughts、categories、dateRecords、poetryCategories、poems 与小游戏数据；任何失败均回滚。真实日记、媒体、笔记和阅读正文不属于这一事务。
- 进入兼容影子前，递归清除云凭据/DPAPI 载荷、Windows 来源路径、Android 与 Windows Vault、`usageDevices`/健康明细等本机私有字段；剩余兼容数据由当前 Windows 用户的 DPAPI 加密。浏览器/桌面小卡片字段、AI Key 和未知字段仍可保留，但 Windows 不执行前两类模块。
- 导出覆盖 Windows 管理的普通设置和结构化记录。`cloud_sync_settings.configs_managed` 是 `cloudSyncConfigs` 的所有权门：初始为 `false` 时保留影子中的非秘密配置；用户在 Windows 新建、编辑、复制或删除配置后置为 `true`，后续才用 Windows 配置覆盖，并按相同 ID 保留未知兄弟字段。
- 私有字段清洗不受所有权门控影响，在兼容影子、手动导出、自动备份、恢复点和应用 JSON 云上传前都执行。usage 只进入 DPAPI 私有只读缓存，并可从专用云 usage 对象下载合并；Windows 不上传 usage。health 完全排除备份与云路径。
- Windows 目录设置不写入 Android URI 字段。AI API Key 按 Android v29 产品格式作为普通明文字段往返，因此导出文件和应用 JSON 云对象必须按敏感数据保存。
- 合法的 `CUSTOM/customTheme` 全字段进入 DPAPI 兼容影子；Windows 当前用 `baseStyle` 渲染，用户未主动改风格时重新导出仍保持 `CUSTOM`。根级 `readerProgress` 最多 500 条并与 Windows 私有账本按更新时间合并；2048 `moveAttempts` 正常往返，旧 `losses` 只读兼容且不再新写/展示。
- Windows 围棋不加入 v29 的七游戏白名单；其独立私有表不会被备份导入/导出、恢复点、自动备份或应用 JSON 云同步读取。导入 v29 只替换既有七个游戏/变体的数据，不触碰本机围棋棋局和统计。
- 手动导出和自动备份都生成 Android 可读取的 `dc.json`。选择备份目录后，应用启动约 30 秒执行首次检查，此后约每 5 分钟检查一次；内容未变化时跳过写入。需要提交时使用 `dc.pending.json` → 回读校验 → `dc.json`，同时保留 `dc.previous.json`，并通过互斥防止并发覆盖。

## 7. 导航与页面开关

主页面由 `NavItemId` 定义：

```text
home, desk, diary, blog, thought, date_records, poetry_book, reader,
rss, ai_chat, vault, games, usage_statistics, step_statistics,
desktop_widgets, more, settings
```

“导航/More”页用于收纳不适合全部挤在底栏中的主页面入口，笔记、阅读和统计默认收纳在这里；从旧版本升级时，统计页接管原手机使用时间/健康的默认收纳位置，但两个原页面仍可单独配置到底栏或导航页。用户在“设置 → 底部导航”控制底栏显示、改名、换图标、拖动排序、默认启动页及导航文字，并管理需要本机录音权限的底栏音乐可视化样式、自适应/手动频率范围；是否收纳到导航页、一列/两列/三列显示、每个模块的名称、按钮底色、模块整体底色、描述总开关和每页最多 160 字符的描述统一放在“设置 → 子页面设置 → 导航页”。聚合页使用 `LazyVerticalStaggeredGrid` 按设置列数独立高度连续排列；平时卡片不显示手柄，长按任一模块进入布局更改模式，所有卡片右上角出现四点手柄并可直接拖动排序，点右上角对勾退出布局模式；模块按钮底色与卡片底色支持每项自定义并随 v31 备份。`home`、`more` 和 `settings` 本身不能被放入聚合列表，设置入口不可隐藏；More 显示在底栏时，进入其聚合页面会保持 More 选中状态。进入日记/笔记编辑、阅读正文、任一小游戏、统计子页或热量估算进度页后底栏隐藏。

非主 tab 路由包括日记编辑、笔记编辑、主页小游戏直达、吃历、吃历滤镜设置、小巧思回收站、日常记录，以及直接打开导航、AI、诗词本设置的路由。系统小组件点击主页模块时可通过受限 route extra 打开对应主页面；应用只接受已知 `NavItemId`。设置子页打开时底栏会隐藏，防止绕过未保存提示。

Windows 使用独立的左侧竖栏，不复用 Android 的底部导航配置。0.7.0 默认分为记录、资料库、订阅与智能、娱乐与统计、工具五类，包含首页、日记、吃历、日常记录、笔记、小巧思、日期记录、诗词本、阅读、RSS、AI、收藏夹、小游戏、统计中心、手机使用时间、健康、更多和备份共 18 个主页面。设置始终固定在侧栏底部，中部页面区独立滚动；每类标题可折叠，折叠后其页面按钮确实隐藏并退出键盘顺序，折叠状态持久保存。宽屏可整体收起侧栏，窄窗口改为带焦点循环和 Escape 恢复的抽屉。Android 内置浏览器与桌面小卡片不提供入口；编辑器、回收站、阅读正文、图片查看、导入预览、云同步和设置子页作为嵌套路由或对话框打开。

Windows 设置采用接近 Android 的层级：设置主页为「外观与语言」「子页面设置」「应用数据」「桌面导航」「关于」；子页面设置覆盖主页、日记/媒体/标题字号、小巧思、诗词/吃历、收藏夹、AI、手机使用时间与健康的桌面边界说明；应用数据进入 v29/自动备份与 WebDAV/S3；关于进入检查更新。「桌面导航」允许全部 18 页逐项显隐、同类排序或移动分类，并支持分类中英文名、新建、排序和删除；删除分类时页面迁移到相邻分类，最后一个分类禁止删除。隐藏当前页会回退到首个可见页，全部隐藏仍可进入设置；隐藏页仍能从导航聚合页或相关业务入口打开。搜索结果可直接到目标子页，任一可编辑子页仍遵守本地草稿、右上角保存、恢复本页默认值和 dirty 离开确认。

## 8. 最近完成的功能

### Android 0.20.1 构建（启用 R8 代码压缩与资源收缩）

- 旧的「日常记录」升级为「结构化记录」：Markdown 日记仍可嵌入五类类型化字段值（今日一句话 word / 俯卧撑次数 number / 今天衣服颜色 type / 午饭时间 time / 午睡时长 duration），以稳定字段 ID 写入 HTML 注释；`.deskcubby/` 工作区（settings/fields/records/statistics.json）加可重建的 Room 索引让统计不再全量扫描 Markdown。日界线（默认 05:00）划分日记日，改动从下一日记日生效并保留历史。设置 → 子页面设置 → 日记与媒体 新增「结构化记录」子页（日界线、自动睡眠/醒来估算开关、字段管理与重建索引）；统计 → 结构化记录统计 提供字段自动统计与公式构造的派生指标（如 睡眠时长）。
- `android/app/build.gradle.kts` 的 `release` 构建类型开启 `isMinifyEnabled = true` 与 `isShrinkResources = true`（配合 `getDefaultProguardFile("proguard-android-optimize.txt")` 与 `proguard-rules.pro`）。发布产物从约 37.4 MB 降至约 22.6 MB（约 -39.7%），APK 签名仍为 v1/v2 有效。
- `android/app/proguard-rules.pro` 补充两条规则：`-keepclassmembers enum * { <fields>; ... }` 保住被持久化的枚举常量名（DataStore、v34 JSON 备份、记录同步 codec 与插件 API 都依赖 `Enum.valueOf`/`enumValues { it.name }` 跨进程字符串，R8 默认重命名枚举常量会破坏既有存档与插件 JSON）；`-keepattributes SourceFile, LineNumberTable` + `-renamesourcefileattribute` 保留发布版堆栈行号。
- Room、Hilt、Health Connect、PDFium、WorkManager、Webkit、OkHttp、Coil 均由各自自带 consumer rules 自动覆盖，无需额外 keep；应用内插件 API（`android/plugin-api`）是经 Hilt 多绑定的编译期库、无动态类加载，无需专门规则。单元测试（Release/Debug 各 508 项）与 lint（0 错误）全部通过。

### Android 0.20.1（首次启动语言选择与记录同步修复）

- 修复首次启动语言选择页从未真正显示的问题：`FirstLaunchLanguageScreen` 以前只定义未接线，现由 `DeskCubbyRoot` 在设置加载完成后按 `language_selected=false` 门控显示；已有用户与设置页语言切换不受影响。
- 修复 0.20.0 记录同步的两个发布前问题：`ReaderPreferencesRecordSyncAdapter` 此前未注册进同步注册表，默认内容选择下每轮同步都会在 `READER_PREFERENCES` 中止，现已接线并在选中内容类型无适配器时先失败关闭；`RecordSyncRemoteStore` 改为每轮每个内容类型只加载一次对象清单并复用，远端清单已证明一致的载荷跳过上传，新记录以 must-not-exist 语义写入，记录下载恢复真实对象身份（存储名/版本），不再构造清单存储必然拒绝的伪造身份。
- 应用升至 0.20.1（versionCode 45）；JSON 备份保持 v34、Room 保持 v13、Reader 保持 schema v8。

### Android 0.20.0（手动备份与云同步解耦为记录同步引擎）

- 云端同步从「整份应用 JSON 上传 + 待确认导入」改为 `RecordSyncEngine` 按内容类型逐对象同步：日记/笔记/媒体走文件同步，小巧思/日期记录/诗词/收藏/RSS/游戏/使用统计/阅读进度/阅读偏好/Agent 对话/Vault/通用设置等走 `records/…` 记录同步，配合 `RecordSyncAdapters`、`RepositoryRecordSyncAdapters`、`RecordSyncStateStore` 与每内容类型的状态码编解码；`AppCloudSyncService` 大幅精简为调度与进度/结果聚合，`DiaryCloudSyncLocalStore` 拆分为笔记（`NotesCloudSyncLocalStore`）与全局设置（`GlobalSettingsSyncCodec` + `SettingSyncScope`）等独立存储。`JSON_BACKUP` 从 `CloudSyncContent` 移除，旧备份中的该值不再加入运行中的同步配置；手动导出/导入与自动备份仍走 v34 应用 JSON，不再与云同步互相耦合。
- 备份语义升级：`BackupJsonCodec.FORMAT_VERSION` 33 → 34，新增 URI-free `agentChats`（Agent 会话/运行载荷，Base64，上限 64 MiB）随 v34 导出；手动备份投影 `sanitizedForManualBackup` 把 AI API Key、全局/卡片背景 URI、日记/媒体/笔记树 URI、诗词字体 URI 与 WebDAV/S3 凭据置空，`BackupSecurityTest` 断言这些秘密不再出现在 JSON 中，导入后为空、需在当前设备重新配置。
- 设置主页移除各条目的副标题描述（`SettingsMenuItem`/`OrganicSettingsMenuItem` 的 description 改为可空），改为更紧凑的图标 + 标题列表；同步说明文案相应精简。
- 应用升至 0.20.0（versionCode 44）；JSON 备份升至 v34、Room 保持 v13、Reader 保持 schema v8。

### Android 0.19.0（桌面小组件外观与尺寸一致性）

- `DesktopWidgetConfig` 新增 `cornerStyle`、`surfaceScalePercent` 与 `appIconScalePercent`：圆角/直角可选，卡片在启动器分配边界内按较短边进行 70%–100% 等比缩放，应用按钮图标以固定 48dp 为 100% 基线并支持 50%–150% 调整。DataStore、v33 JSON 可复用设计和本机实例快照均保留字段；旧数据安全使用默认值。
- `DesktopWidgetVisualPolicy` 统一所有普通卡片、应用模块与位图模块的实例尺寸解析，Launcher 未给出有效尺寸时全部按 72dp/格回退，不再混用 70dp 与 72dp。Android 12+ 使用 RemoteViews outline/margin API 应用圆角和统一留白。
- 桌面小组件编辑器的开关、按钮、对齐和边角选择定义稳定的单行/两行上限及最小高度，五种语言不再因自然换行改变相邻控件高度。饮食图片拍照成功路径会在成功 Toast 与 `finish()` 前主动关闭进度对话框。
- 应用升至 0.19.0（versionCode 42）；JSON 备份保持 v33、Room 保持 v13、Reader 保持 schema v8。

### Android 0.18.4（横屏子目录与 rail 坐标系统一）

- 根布局已通过 `Row` 把 84dp Navigation Rail 与页面内容分成独立列，因此阅读目录和小巧思分类抽屉必须使用页面本地坐标，不得再次增加 rail 宽度。0.18.4 删除两处重复 `offset(84.dp)`，并对 rail 右侧内容列启用 `clipToBounds()`：收起/拖动中的负向抽屉像素不能越界覆盖 rail；展开位置仍从 rail 右缘开始，不留下空隙。
- 竖屏 COMPACT 行为保持不变；应用升至 0.18.4（versionCode 41），JSON 备份保持 v33、Room 保持 v13、Reader 保持 schema v8。

### Android 0.18.0（横屏 Workspace 自适应布局）

- 为 Pad / 大屏设备新增一套横屏 Workspace 设计语言，竖屏（Portrait = Flow）保持原样不变：横屏不再把手机 UI 简单拉宽，而是用 Navigation Rail + Primary Workspace + 可选 Context Panel 的三区结构表达“选择 / 检视 / 比较 / 编辑 / 上下文”（Landscape = Workspace）。
- 新增统一的 adaptive 系统（`ui/components/AdaptiveLayout.kt`）：`WindowInfo`、`resolveLayoutMode`（COMPACT / MEDIUM / EXPANDED，由窗口宽高、方向与可用空间综合决定）、`LocalLayoutMode`、可复用的 `ContextPanel` 与左侧 `DeskCubbyNavigationRail`。方向（旋转行为）与 LayoutMode（UI 结构）解耦：Pad 竖屏也可能够宽，横屏手机也不一定适合三栏。
- 设置 → 外观新增「屏幕方向（自动 / 竖屏 / 横屏）」并标注“仅应用于此设备”。它是设备本地偏好（独立 DataStore 键），明确不进入 JSON 备份、云同步、Obsidian 同步或用户设置恢复，改完立即通过 `requestedOrientation` 生效、无需重启。
- EXPANDED 横屏下：底部导航替换为左侧 Navigation Rail（同一目的地集合、同样高亮，横屏不再同时显示底栏）；首页重排为 60/40 主次双栏 Workspace；日记编辑器正文限制在 650–850dp 阅读宽度并居中，右侧 Context Panel 显示日期/字数/图片数；AI 页右侧常驻 Context Panel（上下文来源、Agent 活动、审批模式与 Review 入口）。
- 竖屏路径（单面板、底部导航、原页面跳转、原输入体验与动画）未被改动；所有 Workspace 分支只读取既有 ViewModel / UiState / Repository，不新增 Landscape* 重复实现。应用升至 0.18.1（versionCode 38），JSON 备份保持 v33、Room 保持 v13、Reader 保持 schema v8。
- 0.18.1 修复横屏显示问题：底部/左侧导航由覆盖层改为真实 Row 分栏（各页面在导航栏右侧排版，不再被 84dp 遮挡）；左侧 Navigation Rail 处理 safeDrawing 安全区；小巧思抽屉锚定内容区不再与导航栏重叠；应用级「屏幕方向」锁在阅读页打开时自动挂起，避免与阅读页「阅读方向」偏好竞写 `requestedOrientation`。

### Android 0.17.0（桌面 Desk）

- 新增一级页面「Desk」——DeskCubby 的个人数字桌面。它不是 Dashboard、Bento Grid 或列表，而是把今天留下的日记、小巧思、照片、事件和痕迹散落在一张编辑式「桌面」上：日期即页面标题（极端字号差、右侧仅一枚 ✦），今日日记成为带轻高度与微小旋转的纸张主对象，小巧思与照片以极简纸条/照片形式呈现，Today Traces 用纯排版建立时间层级，内容多寡直接改变信息层级，无数据时留白本身就是空状态。
- Desk 只读取现有 repository/DAO（`DiaryIndexDao`、`FlashThoughtDao`、`DateRecordDao`、`DiaryFileRepository`），不新建数据系统；点击日记/照片进入既有 `DiaryEditor`，小巧思/事件/痕迹进入既有页面，右上角 ✦ 打开轻量 AI 浮层后转入既有 AI 页，底部低调 `+` 展开 Quick Capture 到既有创建流程。对象旋转与每日选择用日期+内容 ID 生成可重现 seed，同一天不跳变；早晨/午后/傍晚/深夜仅做极轻微环境色倾移，不引入大面积渐变或重拟物。
- 新增 `NavItemId.DESK`（默认收纳在导航页而非底栏，旧用户由 `normalizeNavItems` 自动补齐），纯逻辑测试 `DeskSelectionTest` 覆盖 seed 旋转、时间分季与 Markdown 摘要归一化；应用 JSON 保持 v33、Room 保持 v13、Reader 保持 schema v8，未改动任何既有页面、数据或迁移。

### Android 0.16.3（2026-08-14）

- PDF 增强与兼容视图改用同一套二维手势/页面视口：单指在原始指针层独立结算 X，Y 继续由 `LazyColumn` 处理以保留纵向惯性，斜向/画圈仍同时移动两轴，不再依赖会丢失 X 分量的纵向 nested-scroll；双指接管二维移动并围绕质心缩放。渲染宽度可真正超过父布局，100% 以上不再被压回视口；缩回视口内时页面居中且旧横向偏移归零。纯策略测试覆盖斜向移动、单轴到界不吞另一轴、缩放锚点、超宽放置和缩小居中。
- PDF 书架封面不再把文档提供方的缩略图能力标记当成唯一来源：没有缩略图时以 512 px 上限串行渲染第一页，归一为书本比例并写入回读校验的私有 PNG 缓存。封面编辑器新增最多 120 个 Unicode 码点的文字覆盖，空内容可明确隐藏、恢复后继续跟随文件书名；该字段只进入 Reader 私有 schema v8，不进入 v33 或阅读进度云对象。
- 2048 删除中心新局触控区，仅保留棋盘上、下、左、右四个透明触控区；渲染器在持久化前拒绝 `NEW` 及所有非方向事件，因此旧启动器缓存的中心 PendingIntent 也无法清空或覆盖已有存档。
- 应用升至 0.16.3（versionCode 35）；Reader 私有状态升至 schema v8 并继续读取 v1–v7，应用 JSON 保持 v33，Room 保持 v13。

### Android 0.16.2（2026-08-14）

- PDF 增强与兼容视图按实际内容宽度计算横向溢出，放大到 100% 以上时页面按目标像素宽度真正重渲染并可双指拖动，不再被父布局压回视口；缩回视口以内时居中并清除旧横向偏移。
- 小卡片设计器把“主页模块 / 应用模块 / 应用按钮”三枚拥挤选择按钮改为下拉菜单；主页模块与应用模块使用独立列表，切换类型时会归一到该类型的安全默认值。实例快照 schema 1→2 并继续兼容旧快照，使用时间范围统一为 7/30/90 天。
- 音乐可视化改用专用全幅 `RemoteViews` 布局和有界 Bitmap，只绘制频谱/波形/曲线，不绘制标题、文字或自身背景；服务持续观察风格与频率设置，并以桌面实例快照为权威，修复重新配置后旧模板继续覆盖的竞态。使用时间的三种面板分别重写为透明纯色块、纯折线和纯柱状；启动、手动刷新、周期采集成功后主动请求卡片更新。
- 饮食图片六项改为无底色纯 emoji，固定操作组在任意卡片高度中垂直居中。快速输入增加每实例设备本机草稿与透明纸飞机，单击发送到未分类；真实输入与长按选择已有分类由非导出的私有 Activity 完成，草稿明确排除 Android 备份/设备迁移。
- 2048 删除可见的上下左右和新游戏按钮；受 `RemoteViews` 不提供任意四向滑动回调的限制，棋盘用透明边缘触控区完成上下左右移动，中间透明区新开一局。
- 云同步完成时间以及上传、下载、冲突数量写入排除备份的运行时偏好；首页、桌面应用模块与设置页都读取同一持久结果。设置页第一行改为等宽“立即同步 / 撤回一次”，强制上传/下载仍在第二行。
- 应用升至 0.16.2（versionCode 34）；应用 JSON 保持 v33，Room 保持 v13。

### Android 0.16.1（2026-08-15）

- 修复 PDF 阅读双指缩放两个问题：增强视图（PDFium）漏算 `maxHorizontalPx`（横向最大偏移从未赋值，恒为 0），导致缩放后横向平移失效、页面固定靠左——现与兼容视图一致在 `BoxWithConstraints` 中计算并赋值；双指缩放手势提交的最终比例现在写回持久 `ReaderPreferences.pdfZoomPercent`（50–300），重新打开阅读器保持放大，不再回到默认 100%。
- 桌面小卡片引入「应用模块」内容类型，与「主页模块」「应用启动」并列：可直接在桌面游玩的游戏扩展到 2048 4×4/5×5/6×6 三种棋盘、贪吃蛇、俄罗斯方块、扫雷、蜘蛛纸牌、围棋；音乐可视化支持 2×1–4×1；阅读、使用时间总览/图表/应用排行（可设 3/7/30 天范围）；合并的云端同步面板显示「已完成 ↑上传 ↓下载 冲突数」并保留立即同步/撤回一次/强制上传下载。这些面板此前只渲染文本占位，本次真正接入 `DesktopWidgetGameRenderer`/`DesktopWidgetAppPanelRenderer`。
- 移除独立的「立即同步」「强制上传/下载」系统小组件（Manifest 取消注册、配置页入口删除、模块 ID 移除）；旧配置与 v32 备份中的 `cloud_sync_now`/`cloud_sync_force` 归一为合并的 `cloud_sync`。
- 配置页：不再显示顶部预览模块，背景预览并入「外观」分区；「显示内容」分区移到「外观」之前；移除大小设置与尺寸角标；说明文案更新。饮食图片 3×1/4×1 显示六个拍照按钮且点按直拍（不再弹拍照/上传选择）；日期记录 3×1/4×1 显示最近一条、3×2/4×2 显示两条；每日诗词 4×2 完整显示正文与按钮；快速输入 4×1 显示输入框与发送按钮，长按发送可先选小巧思分类；各模块显示阈值整体压缩（如诗词 ≥140×48、饮食 ≥150×48、日期记录一条 ≥140×56），更小空间也能显示内容。
- 设置 → 外观与语言：「软件语言」由五个分段按钮改为下拉菜单（原非中文语言全部显示为 “English” 且窄屏换行），选项显示各语言本名。
- Android 应用 JSON 备份升至 v33（继续导入 v1–v32）；应用升至 0.16.1（versionCode 33）；Room 保持 v13。

### Windows 0.8.0（2026-08-15）

- 备份格式追赶：`FORMAT_VERSION` 29 → 33，支持导入 Android v1–v33 并统一导出 v33。升级边界补齐 v30 Agent 来源授权/权限模式与 AI 配置 `includeToolCalling`、v31 AI 页面字号/回复框宽度/Agent 提示词/导航页列数与模块按钮/卡片底色、v32 桌面小卡片 `usageRangeDays`、v33 `APP_MODULE` 内容类型；旧独立云同步模块 `cloud_sync_now`/`cloud_sync_force` 归一为合并的 `cloud_sync`，桌面小卡片校验白名单同步扩展。未知字段、AI Key 与私有字段清洗语义不变；手机使用时间备份版本上限同步升至 33。前端与 Rust 错误文案、页面说明全部更新为 v33。
- Reader 进度格式对齐 Android schema v7：私有状态 schema 升至 v5，新增 `textPageOffsetPercent`（0/5/…/95 页内偏移，旧数据无损迁移为 0），保存进度时由前端按当前可见段落量化上报；备份 `readerProgress` 仍不含偏移，与 Android 一致。
- TXT 阅读改为**段落流无限滚动**：不再按逻辑页翻页，全书段落连续渲染，逻辑页仅作为虚拟化窗口单元（每页实测高度校准滚动位置），进度按精确段落保存与恢复，搜索/目录/工具栏跳转、复制当前页与进度滑杆全部适配。
- 修复 PDF 阅读"一页正一页反"：阅读器向 React-PDF 传 `rotate={0}` 会覆盖页面自身的 `/Rotate` 属性，导致带旋转属性的 PDF（如扫描件奇数页 0°、偶数页 180°）方向错乱；现仅在实际旋转视图时传 rotate，页面自身旋转始终生效；首页宽高比测量也不再覆盖 `/Rotate`。新增回归测试断言未旋转时不传 rotate。

### Android 0.15.0（2026-08-14）

- AI 设置页重构为独立子页结构：模型配置（接口、模型、API Key、工具能力）移到“AI 配置”子页；“AI 设置”页新增 AI 页面字体大小（12–28 sp）、AI 回复框宽度（280–1200 dp）与 Agent 提示词（可一键恢复默认）。Agent 提示词作为风格/任务偏好附加在严格内置规则之后，与每个配置的“附加模型指令”合并后随每次 Agent 运行发送。AI 聊天页的消息气泡按新字号渲染，Agent 回复渲染 CommonMark（标题、列表、代码、引用、链接，可选中、可点链接）。
- 修复 Agent 第二次对话（或任意工具轮）在成功调用工具后弹出“调用失败”且无回复的问题：工具轮次的请求 JSON 改为规范序列化——assistant 空内容不再发送 `content:null`（直接省略键），tool 消息不再携带非标准的 `name` 字段，兼容严格 OpenAI 兼容端点；新增 androidTest 覆盖两种序列化形态。
- 导航页设置新增一列/两列/三列布局选择；每个模块新增可设置名称（32 字符）、按钮底色与模块整体底色（HSV 色盘，可恢复默认）；导航页平时不显示四点按钮，长按任一模块进入布局更改模式后所有模块右上角出现四点手柄、可直接拖动排序，点右上角对勾退出布局模式。
- PDF 增强视图的双指缩放改为矩阵实时变换：手势期间内容围绕捏合中心实时跟随手指（`graphicsLayer` 缩放 + 变换原点），不触发逐页重渲染，不再出现加载圆圈；手势结束后提交最终比例并重新渲染页面。缩放范围仍受 50%–300% 基准限制，与持久基准缩放联动。
- Android 应用 JSON 备份升至 v31（继续导入 v1–v30）：新增 AI 页面字号、回复框宽度、Agent 提示词、导航页列数与模块按钮/卡片底色；旧版本导入时使用安全默认。应用升至 0.15.0（versionCode 31）；Windows 0.7.0 实现不变，仍只支持 v1–v29 JSON。

### Android 0.14.0（2026-08-13）

- 把 Android 单次 AI Chat 重构为真正的分层 Agent Runtime：`AgentModelClient`、Runtime、Context Provider、Tool Registry、Tool Executor、Permission Manager、Review Store 各自独立。循环支持一次多工具/重复工具、严格参数与 call ID 校验、模型/工具/网络失败回传、用户取消、每轮最多 16 个调用和 12 轮上限；不支持原生 tool calling 的配置明确失败关闭，不解析普通文字执行工具。
- Plugin API 升至 v2，新增 `DeskCubbyDataAPI`、`FileAPI`、`AppAPI`，`AIAPI` 增加原生 tools/tool_calls 与 Provider usage DTO。Agent 只依赖这些接口；通用数据 adapter 覆盖日记、小巧思、日期、日常事件、笔记、诗词、使用时间和统计，文件 adapter 始终限制在 SAF 已授权根，App adapter 只开放非敏感设置白名单。
- AI 输入区左下角改为四方块入口，提供最多 5 个图片/文档附件、八类持久上下文授权和持久权限模式。Context 每轮只给已授权来源的轻量 metadata，模型再分页 list/search/read；旧日记单日选择器取消，旧冻结上下文继续以不可信用户数据兼容。集中式 system prompt 明确检索优先、不得假装读/搜、外部内容不可信、来源/审批不可绕过、最小修改和秘密保护。
- mutation 在“需要批准”下逐项显示工具、目标、计划与 before/after；拒绝作为 tool result 继续循环。“全自动”只省略弹窗，不省略逐项 Review。独立 Agent Review 按 Run/会话展示真实修改和详细只读/网络记录；Undo 调用工具的补偿操作并在版本仍匹配时真正恢复数据、SAF 文件或设置，失败不删除日志或误报成功。
- 会话采用稳定同步 ID/LWW/tombstone，可由用户单独选择 `agent/v1/chats.json` 云同步；只同步文字、冻结文档文字、图片占位、完成 Run 与 Provider usage，不同步本机 URI/图片字节、Review/Undo 载荷或秘密。统计中心新增 Agent runs/calls、已报告/未报告调用、input/output/total/cached/reasoning Token 和真实缓存率，不估算 Provider 未返回的数据。
- Room 通过显式 12→13 migration 保留旧 AI 会话并新增附件、Run/usage、工具事件与 mutation Review 表；Android 应用 JSON 升至 v30 并继续导入 v1–v29，新增来源授权、权限模式和模型工具能力，聊天/Review 仍排除。应用升至 0.14.0（versionCode 30）；Windows 实现和 0.7.0 版本不变，仍只支持 v1–v29 JSON。
- JVM/Android instrumentation 测试覆盖单/多工具、失败、非法调用、上限、取消、上下文授权、mutation 批准/拒绝/全自动、Review/Undo、文件与设置恢复、旧 AI 迁移、附件/会话同步和 v30 备份兼容。

### Windows 0.7.0（2026-08-13）

- Windows PDF 阅读层从直接调用 PDF.js 单画布改为 MIT 许可的 React-PDF 10.4.1 + PDF.js 5.4.296，彻底移除会在 WebView2 把页面压成单一颜色的 `pageColors`。默认按原稿渲染，另提供显式「阅读配色」像素明暗映射；worker、CMap、标准字体、ICC 与 WASM 随前端离线打包，PDF 字节继续只经 UUID 受限只读协议按需读取，绝对路径不跨 IPC。
- PDF 新增可选中文字、批注链接、内嵌目录、整本文字层搜索、90° 旋转、仅会话密码输入、连续纵向/单页模式与页间距。连续模式使用当前页附近 ±3 页的虚拟画布和上下占位，页数再多也不同时渲染整本；当前可见页继续防抖写入原有指纹进度。
- TXT 段落显式继承 Reader 前景色，修复全局 `p` 次要文字颜色覆盖后与背景接近的问题；颜色设置加入即时预览、4.5:1 最低对比度兜底，以及首行缩进、字间距、页面留白。PDF 增加原稿/阅读配色、连续/单页和页间距设置。Reader IPC 升至 v3，私有状态升至 schema v4 并无损读取 v1–v3；SQLite 保持 v7，应用交换格式保持 v29。Windows 升至 0.7.0，Android 保持 0.13.2。

### Windows 0.6.1（2026-08-13）

- 修复 PDF 在进入 pdf.js 前被错误拒绝：Rust 标签枚举原先在线上输出 `asset_url`，TypeScript v1 协议却读取 `assetUrl`。Reader IPC 升至 v2 并显式固定、回归测试 `assetUrl`；前端仍只接受 UUID 形式的 `reader:` 或 `http://reader.localhost` 受限只读地址，绝对路径不跨 IPC，CSP 只允许该连接来源并把不再使用的 frame 来源关闭为 `'none'`。
- 阅读正文改成全高内容面，只保留书名、目录/搜索、翻页、复制或 PDF 临时缩放、设置和专注模式的一条紧凑工具栏。目录作为浮层显示，不再挤压正文；专注模式的半透明控制栏覆盖内容而不占高度，并加入 `Ctrl+F`、方向键/PageUp/PageDown/空格、Home/End 与 Escape 的阅读操作。
- 阅读设置新增列表/网格书架、进度和网格书名显隐、六种背景、自定义前景、衬线/无衬线/等宽、自然/两端对齐、520–1280 px 正文宽度和默认专注模式，同时保留字号、行段距、PDF 基准缩放与章节识别。Reader 私有状态升至 schema v3 并继续读取 v1/v2；书架路径、书名、正文、偏好与时长仍不进入 v29。Windows 升至 0.6.1，SQLite 保持 v7，交换格式保持 v29。

### Android 0.13.2（2026-08-12）

- 日记 Markdown 源码编辑器取消全局 88dp 右侧媒体留白，普通文本恢复完整书写宽度；删除与四点拖动控件改为仅在独占媒体行尾部使用不透明局部覆盖，既保留操作空间，也不再压缩其他文本行。
- 阅读纯净模式的书名、工具栏、搜索栏、页码和系统栏改为覆盖固定正文平面；屏幕中央显隐控件时 TXT/PDF 内容不重新测量或整体下移，允许页面顶部少量内容暂时被控件遮盖。非纯净模式仍使用常规安全区和顶栏内容间距。
- `engagement/engagement-times-v1.json` 内部升至 schema v2，继续无损读取 schema v1 的游戏/阅读时长，并为每本已有阅读时长的书有界保存最后已知标题。阅读会话检查点、Reader 初始化和书架移除前都会补齐可得标题；移除书架记录后统计仍显示书名和累计时长。升级前已经移除且 v1 已失去标题映射的记录使用明确旧版占位，不伪造名称。
- 应用升至 0.13.2（versionCode 29）；Reader 状态保持 schema v7、备份保持 v29、Room 保持 v12、Windows 保持 0.6.0。阅读标题/时长仍排除 v29、`reading/v1/progress.json` 和 Android 系统备份。

#### Android GitHub 发布授权记录（2026-08-12）

用户明确授权代理仅为本轮 Android 0.13.2 执行：把最终提交推送到远端 `main`，创建指向该提交的 `v0.13.2` tag，创建并公开对应 GitHub Release，并只上传唯一资产 `DeskCubby.apk`。该授权不包含强推、移动或删除任何既有 tag、覆盖或删除既有 Release/资产、导出或披露密钥，也不自动延伸到未来任意版本；出现远端状态不匹配时必须失败关闭并重新确认。

### Android 0.13.1（2026-08-12）

- TXT、PDFium 增强视图与系统 `PdfRenderer` 兼容视图都按首个可见页高度计算页内位置，向下量化到 0/5/…/95，滚动稳定约 600ms 后写入，离开阅读正文时再补一次最终检查点；重新打开后等待页面实测高度再按比例恢复，避免因不同尺寸直接复用像素偏移。
- Reader 私有状态升至 schema v7 并继续读取 v1–v6，旧数据页内偏移默认为 0；内容指纹变化时清零偏移，页码/目录/搜索显式跳转从目标页顶部开始。书架百分比把本机页内偏移纳入计算。
- v29 应用 JSON 与 `reading/v1/progress.json` 的版本、键集合和隐私边界保持不变；导出显式投影掉本机页内偏移，导入默认从记录页顶部恢复。应用升至 0.13.1（versionCode 28），备份保持 v29、Room 保持 v12、Windows 保持 0.6.0。

### Windows 0.6.0（2026-08-11）

- 修复桌面端无法显示 PDF 的问题：WebView2 不内置 PDF 渲染器，原 `<iframe>` 连续查看器改为 pdf.js（`pdfjs-dist`）应用内 canvas 渲染。数据仍通过受限 `http://reader.localhost/{bookId}.pdf` 只读协议按需获取（HTTP Range + CORS），前端不获得文件系统权限；`readerPdfViewer.tsx` 支持按容器宽度自适应与阅读设置中的 50%–300% 基准缩放、页码导航与书签恢复后的页码钳制，文档加载/渲染失败可重试。CSP 相应放开 `connect-src http://reader.localhost` 与 `worker-src 'self' blob:`（Vite dev 用 blob worker、生产为 self chunk）。PDF 阅读提示文案改为“应用内渲染”。
- 数据兼容追平 Android 0.13.0：备份边界从 Android v1–v28 升级为 v1–v29，统一导出 v29。旧版导入时在内存中安全升级：按 Android 0.13.0 语义为 `desktopWidgetConfigs` 每一项补齐 v29 外观字段默认值（`showName`/`backgroundOpacityPercent`/`showIcon`/`textAlignment`/`textScalePercent`），并把旧 `cloud_sync` 主页模块改写为 `cloud_sync_now`；`validate_desktop_widget_configs` 的合法 `homeModuleId` 扩展为 Android 同源的 21 项（新增 `notes`/`game_shortcuts`/`record_overview`/`cloud_sync_now`/`cloud_sync_force`），并校验 v29 五个外观字段的范围与枚举。导入/导出、恢复点、DPAPI 兼容影子、私有字段清洗与 `configs_managed` 所有权门不变。
- Windows 应用版本升级为 0.6.0；Android 保持 0.13.0，交换格式保持 v29。桌面导航仍为 18 个主页面，SQLite schema 保持 v7。

### Android 0.13.0（2026-08-10）

- PDF 增强视图改为 PDFium 1.0.35，经 SAF 文件描述符直接打开且不复制原书，支持连续按需渲染、缩放、页码、双色映射、内嵌文字搜索计数/跳页和目录扫描；不承诺高亮或文本选择。打开失败、页数异常、首个可见页渲染失败或首屏 30 秒超时自动回退系统 `PdfRenderer`。PdfiumAndroid 封装的 Apache-2.0 与 PDFium BSD 风格版权/免责声明随 `assets/pdfium_NOTICES.txt` 分发，并可从 About 打开。
- 桌面信息卡改为模板保存后实时传播到所有匹配实例，同时以 App Widget ID 保持不同模板、重新配置和最后快照彼此独立。应用快捷卡优先使用 launcher Activity/alias 图标、回退 application icon，以 48dp 居中显示；全部 21 个首页模块可选并在大尺寸复刻月历、多项记录、日常录入、随机日记、小游戏入口、完整云同步状态及各自操作，诗词、六餐图片和快速输入同样保留直接交互，1×1、1×2 等小尺寸退化为整卡跳转。
- 首页云同步拆成 `cloud_sync_now`“立即同步”和 `cloud_sync_force`“强制上传/下载”两张卡，共享串行队列与状态；旧 `cloud_sync` 和 v29 备份值在原位置展开，已移除模块的用户不被重新添加。新安装默认十个首页模块。
- 应用升级为 0.13.0（versionCode 27）；备份保持 v29、Reader schema 保持 v6、Room 保持 v12，Windows 保持 0.5.0。本轮按用户要求未启动 Android 模拟器，发布验证采用编译、自动化测试、Lint、签名 APK 与静态包检查。

#### Android GitHub 发布授权记录（2026-08-10）

用户明确授权代理仅为本轮 Android 0.13.0 执行：把最终提交推送到远端 `main`，创建指向该提交的 `v0.13.0` tag，创建并公开对应 GitHub Release，并只上传唯一资产 `DeskCubby.apk`。该授权不包含强推、移动或删除任何既有 tag、覆盖或删除既有 Release/资产、导出或披露密钥，也不自动延伸到未来任意版本；出现远端状态不匹配时必须失败关闭并重新确认。

### Android 0.12.0（2026-08-10）

- 围棋每次合法落子/停着改为发布独立棋局快照，修复可变对象引用未变化导致 Compose 不重绘棋子、手数与轮次；棋盘点击统一吸附最近交叉点，消除交叉点之间的命中死区，并补充引擎、输入几何和 Compose 语义回归测试。
- Reader 私有状态升至 schema v6 并继续读取 v1–v5；两列书架新增封面下方书名开关，无图片 TXT 把书名直接绘在默认封面。AndroidX PDF 不再把首屏前非致命 `RequestFailureEvent` 当作增强视图失败，文档打开/绑定异常和首屏 30 秒超时仍回退兼容视图。
- Android S3 删除故意不匹配 `If-Match` 的条件 GET 执行探针及对应阻断；实际 GET/PUT 保留最佳努力条件头与 409/412 冲突响应，manifest/payload SHA-256、内容寻址和缺少可信写入 ETag 时的同字节回读继续校验传输内容。服务忽略条件请求时不再声称具备原子并发保护；WebDAV 严格强 ETag 边界不变。
- 云同步加入真正的应用首页模块，显示队列、进度、上次完成和待确认 JSON，并提供立即同步及需确认的强制上传/单来源强制下载。新安装默认九个首页模块；旧用户把该模块追加一次，之后可照常移除、排序或隐藏标题。
- 桌面信息卡按 App Widget ID 保存完整实例快照，修复添加多个实例却显示同一卡片；配置改为必选并支持启动器重新配置。设计器新增名称/图标显隐、0%–100% 背景透明度、左/中/右文字对齐和 75%–150% 字号。
- 应用升级为 0.12.0（versionCode 26），备份升至 v29 并继续导入 v1–v28；v29 为桌面卡片增加五类外观字段。Windows 0.5.0 暂不能导入 v29。Room 保持 v12，Windows 源码与版本保持 0.5.0。

### Windows 0.5.0（2026-08-10）

- 云同步追平 Android 0.11.0 的手动强制模式：页面提供需要明确确认的“强制上传 / 强制下载”。强制上传可依次处理多个已启用配置；强制下载只接受恰好一个启用来源，并在读取同步内容或访问端点前验证数量。两者都保留独有项目、不传播删除；同路径覆盖仍分别绑定远端扫描版本和本机扫描 SHA-256，扫描后的并发修改会阻止覆盖或产生冲突副本，远端应用 JSON 仍只暂存待确认。
- 新增本地双人 9/13/19 路围棋，TypeScript 引擎实现提子、禁自杀、简单劫与双方连续两次停着结束；页面支持鼠标和键盘落子，显示双方提子和最后一手，不代替玩家判定地域胜负。SQLite 6→7 事务迁移新增围棋专用 state/statistics/engagement 表，最高提子及落子/提子/停着/完成棋局统计只保存在当前 Windows 用户本机，结构性排除 v28、恢复点、自动备份与应用 JSON 云同步。
- 修复左侧分组只改变无障碍树、页面按钮却仍占布局的问题：`hidden` 导航容器现在显式 `display: none`，折叠后页面按钮同时从视觉布局和 Tab 顺序移除，宽屏与窄窗抽屉共享同一行为。
- 修复日记 Markdown 图片永久停在“正在读取图片”的问题：React Markdown 对中文和空格文件名生成的百分号编码会继续交给受限 Rust 媒体解析；Rust 返回空结果或图片加载失败时切换到“图片不可用”，仍不开放媒体根之外的路径。
- Windows 应用版本升级为 0.5.0，SQLite schema 升至 v7；Android 保持 0.11.0，交换格式保持 v28，既有七游戏白名单及其往返语义不变。

### Android 0.11.0（2026-08-10）

- 日记未配置目录的空状态新增「一键设置默认目录」：先由用户在 Android SAF 系统选择器确认本机 Documents 的读写授权，再以不透明文档 URI 创建或复用 `Documents/deskcubby/diary` 与 `Documents/deskcubby/media`；创建、权限和子目录校验全部成功后才原子写入设置，原有手动选择路径继续保留。
- 修复两列书架进入时可能因封面触发崩溃的问题：不再为多个任意 PDF 在应用进程中同步打开 `PdfRenderer`，改为按卡片实测宽度严格限制解码/输出像素，复用受限缓存或文档提供方缩略图，捕获内存分配失败；无法取得安全封面时显示占位，SAF 手动封面仍可用。
- 增强 PDF 改为确认隔离服务实际存在、等 `PdfView` 附着后再绑定文档，首屏到达前不强制整视图硬件颜色层，并监听渲染请求失败；文档与首屏各有 30 秒上限。增强能力仍取决于 Android 版本、安装包中的服务和系统 PDF 扩展，失败会自动回退连续兼容视图，用户可从提示条重试，不把一次瞬时失败永久记住。
- 新增本地双人围棋，支持 9/13/19 路棋盘、提子、禁自杀、简单劫与双方连续两次停着结束；页面只记录双方提子，不代替玩家按数子或数目规则判断胜负。围棋暂停存档、最高提子与落子/提子/停着/完成局数统计保存在 Android 本机；为保持 Windows 0.4.0 的 v28 导入兼容，围棋数据与主页围棋快捷入口不进入 v28。
- 云同步页在「立即同步」下增加同高的「强制上传 / 强制下载」二合一按钮并在执行前明确确认。强制上传允许多个目标；强制下载只允许一个已启用来源，多来源会在内容扫描、凭据读取或网络请求前以 `SYNC_FORCE_DOWNLOAD_SOURCE_COUNT` 失败关闭。强制操作不传播删除；远端覆盖仍要求扫描版本匹配，本机覆盖仍要求本机快照匹配，之后发生的并发修改会阻止覆盖或留下冲突副本，云端应用 JSON 仍只暂存待手动确认导入。
- App Widget provider 补齐显式启用、图标、标签、provider 元数据和 Android 12+ 描述/预览，改善通用 Android 启动器的组件发现；系统小组件面板新增「立即同步」与左右分区的「强制上传 / 强制下载」，动作经私有广播和有联网约束的唯一 WorkManager 队列执行。S3 非标准 ETag 探针由条件 HEAD 改为与实际读取一致的有界条件 GET，避免只忽略 HEAD 条件的兼容服务被误判；验证失败仍停止同步。应用 versionCode 25，备份保持 v28、Reader 私有 schema 保持 v5、Room 保持 v12；Windows 源码与版本保持 0.4.0。

### Windows 0.4.0（2026-08-09）

- Windows JSON 边界追平 Android v28：接受 v1–v28 并统一导出 v28；严格验证并安全往返 Custom 主题、最多 500 条 URI-free `readerProgress` 与 2048 `moveAttempts`。Windows 当前以 Custom 的 `baseStyle` 渲染并保留其余参数；Vault、usage/health 明细、来源路径和云凭据继续在兼容影子、导出、恢复点、自动备份与应用 JSON 云上传前清洗。
- Windows 阅读使用与 Android 完全一致的完整文件 SHA-256+类型指纹，支持每个云配置可选的 `reading/v1/progress.json`。双向同步按指纹、类型和 `updatedAt` LWW 合并，同时间取更靠后位置；TXT 段落位置在本机分页中重新映射。对象不含路径、书名、封面、正文或时长，新配置默认启用、旧配置不静默追加。
- 左侧侧栏修复宽屏高度不足时无法滚到末尾的问题：中部页面区使用动态视口高度独立滚动，设置固定底部。全部 18 个主页面可在「设置 → 桌面导航」显隐、排序、换类；分类支持中英文名、新建/删除/排序，并可直接折叠。删除分类迁移其中页面而不删除页面，最后一类不可删除；隐藏当前页和全部隐藏都有安全路由回退。
- 日记 CommonMark 预览恢复相对饮食图片，仍经 Rust 媒体根协议和叶文件名校验。吃历把滤镜开关与五项参数、2/3/智能图片换行、说明和日期卡片单/双列原子持久保存；双列从日期中点分成左右列，每天及全部餐食不拆分，窄窗自动回退一列。
- Windows Logo 更换为用户提供的 512×512 透明像素画，生成 PNG/ICO/应用内资产时保持最近邻像素边缘和透明通道。2048 的 `moveAttempts` 统计总方向输入，旧 `losses` 仅兼容往返且不再新增或展示。
- `SignedRelease` 仍强制 Tauri updater 公私钥机制、HTTPS endpoint、非空私钥密码、安装包 `.sig` 和 Rust/Minisign 验证，但 Authenticode 改为可选。未配置 Windows 证书时正式包明确验证为 `NotSigned` 后继续，SmartScreen 可能显示“未知发布者”；未来配置 PFX 或自定义签名命令仍自动签名并验证证书链、可信时间戳与可选 subject。`windows-v0.4.0` Actions 创建只含 `DeskCubby-0.4.0-windows-x64-portable.exe`、`DeskCubby-0.4.0-windows-x64-setup.exe`、同 setup `.sig`、`SHA256SUMS.txt`、`latest.json` 的 Draft Release，人工发布后再提升到独立 `windows-stable` 通道。

### Android 0.10.0（2026-08-08）

- 修复增强 PDF 的全设备不可用路径：预热 `SandboxedPdfLoader` 隔离服务，在 `OnFirstContentLoadListener` 之外监听 `PdfView` 位图，文档/首屏分别以 15 秒为界，失败后自动切换连续兼容视图。兼容路径改为所有页面共享横向 `ScrollState`，避免只移动当前页。
- 阅读设置新增全屏纯净模式，隐藏书名、工具栏、页码和系统栏并由屏幕中央点击切换；TXT/PDF 共用可选背景与自定义前景色，PDF 用硬件/Compose 颜色矩阵实现不改原文件的双色显示。书架支持列表/两列封面、进度百分比、PDF 首页自动封面与 SAF 手动封面。
- 阅读私有 schema 升至 v5 并兼容 v1–v4；完整书籍字节+类型生成 SHA-256 指纹。Android JSON 升至 v28，以最多 500 条 URI-free 记录迁移进度；可选 `reading/v1/progress.json` 云对象自动合并同文件进度，书名、URI、封面、正文和累计时长继续排除。指纹存在已知文件识别风险，远端没有端到端加密。
- 2048 方块按位数/格宽单行缩放；新增包含未移动棋盘方向输入的总操作次数，同时保留有效移动，停止写入/展示失败次数但兼容旧字段。S3 只在条件语义探测、条件读取和内容哈希验证通过后兼容非标准 ETag，过期写入仍冲突；WebDAV 强 ETag 策略不放宽。
- 外观增加受控 Custom Compose 主题，开放浅/深色八类角色、基础渲染、圆角、边框、阴影、不透明度、间距和动效，不执行 CSS/脚本。App Widget 修复部分启动器的 receiver 分发与配置首帧，增加即时与 6 小时 WorkManager 保底、Android 12+ 预览和通用系统小组件面板手动添加说明。应用 versionCode 24，Room 保持 v12，Markdown 与 `dc-media.json` v2 不变；该 Android 发布完成时 Windows 尚为 0.3.0/v27，随后由 Windows 0.4.0 对接 v28。

### Android Kotlin Plugin API 架构（2026-08-08）

- 新增 `:plugin-api:core` Android library：定义 `Plugin`、`PluginContext`、`PluginManager`，以及 Diary/Vault（Markdown 笔记库）/Media/Sync/AI/UI/Storage 七类稳定接口与 DTO。
- `:app` 新增旁路 Adapter/Impl、Hilt 空插件集合和应用级 `PluginRuntime`。Diary/Vault/Media/Sync/AI 分别复用现有 Repository/Service 的 SAF、SHA-256、网络、凭据和限额边界；现有页面、ViewModel 与数据调用链没有迁移。
- UI contribution 具备页面、Widget、入口的 Compose 注册与按插件卸载清理，但当前导航不消费 registry，因而不增加任何可见页面或交互。插件存储按 ID 隔离；Plugin API 本身不改变 Room v12、Markdown、`dc-media.json` v2 或备份结构，v28 变化来自 Custom 主题与阅读进度。
- `TestPlugin` 仅位于 core 测试源集，验证注册、加载、卸载、重复 ID 防护以及加载失败清理，不进入 APK。

### Windows 0.3.0（2026-08-07）

- Windows 页面追平 Android 0.9.3：新增 Obsidian 风格笔记、TXT/PDF 阅读、RSS、AI 聊天与吃历热量估算、七个小游戏入口、统计中心和只读健康页；诗词分类/排序与离线预设、主页模块/问候/小游戏快捷入口和设置项同步扩展。Android 内置浏览器和桌面小卡片明确保持平台专属。
- 桌面壳改为分组左侧竖栏，宽屏可折叠、窄窗口切换抽屉；三套主题、中文/英文、系统缩放、键盘焦点与 dirty 设置离开确认继续覆盖新页面，Logo 改用 Android 同源图标。
- 备份边界升级为 Android v27：接受 v1–v27、最大 64 MiB，旧版安全升级后统一导出 v27；导入先预览确认并保留恢复点。兼容影子、导出、自动备份、恢复点与应用 JSON 云上传均清除两端 Vault、usage/health 明细、来源路径和云凭据，AI Key 按 Android 产品结构明文往返。
- 手机使用时间和健康均只显示、不采集。usage 可从用户选择的快照/只读链接或专用云对象下载合并到 DPAPI 私有缓存，Windows 永不上传；health 只接受用户选择的兼容文件，二者都不进入 Windows v27/恢复点/应用 JSON 云同步。
- WebDAV/S3 继续默认 HTTPS、条件请求、冲突副本与总量/时间限制；WebDAV 严格使用单个强 ETag，S3 只在条件探测与同字节回读绑定通过后兼容非标准或缺失 ETag。RSS/AI/每日诗词分别使用受限网络边界。正式发布始终强制验证 Tauri updater `.sig`；Authenticode 未配置时两个 PE 必须明确为 `NotSigned`，配置后则继续强制证书身份、证书链和可信时间戳。普通本地构建仍只能称为 updater 未配置的测试包。

### Android 0.9.3（2026-08-06）

- 修复 AndroidX `SandboxedPdfLoader` 的隔离服务未连接或 `PdfView` 首屏未回调时无限显示加载圈：文档打开和首屏内容分别设置 8 秒上限，异常或超时后把当前书会话切换到连续 `PdfRenderer` 兼容视图，并用中英文 Snackbar 告知用户；调用方协程取消继续向上传播。
- PDF 目录扫描推迟到首屏成功后；文本选择、搜索和提取目录按 Android 11+ 与 S SDK Extension 13+ 的真实平台能力门控，兼容视图仍保留真实页码、连续滚动和持久基准缩放。应用升级为 0.9.3（versionCode 23），备份仍为 v27、Room 仍为 v12；Windows 源码与版本未修改。

### Android 0.9.2（2026-08-05）

- 吃历扫描改为直接通过 `DocumentsContract` 对日记、媒体目录各查询一次子项名称、类型、修改时间与大小，非标准提供方继续安全回退 `DocumentFile`；媒体文件名索引和 `dc-media.json`/previous/pending/旧侧车查找复用同一份快照，不再为一次加载反复完整枚举媒体目录。
- 根级 `DiaryViewModel` 记录已成功加载的目录与仓库内容 revision：从吃历滤镜或热量进度页返回时直接显示现有结果，日记保存、重命名、删除/恢复、媒体引用删除、首页饮食上传或日常记录写入后再自动失效；外部编辑仍可用顶栏刷新强制重建。
- 热量队列保持不同日期串行，同一天改为最多 3 张图片受控并发识别；所有图片识别成功后只调用一次文字模型，结合当天备注和全部图片统一计算、识别同餐重复角度，再把按图片结果一次写入 `dc-media.json`。任一识别、统一计算或保存失败时当天不部分提交，后续日期继续。
- 默认热量文字提示词迁移到按天多图 JSON 契约，原有自定义提示词不覆盖；模型必须按 `photoIndex` 返回每张结果，重复角度可记为 0 kJ。应用升级为 0.9.2（versionCode 22），备份仍为 v27、Room 仍为 v12；Windows 源码与版本未修改。

### Android 0.9.1（2026-08-05）

- PDF 阅读改为连续纵向滚动，不再把每一页强制铺满一屏；Android 9+ 使用 AndroidX PDF 增强视图，支持双指缩放和 50%–300% 持久基准比例，系统 PDF 扩展支持的设备还提供文本选择复制、整书搜索高亮和逐页自动目录，Android 8 保留 `PdfRenderer` 连续兼容视图。
- TXT 正文支持长按选择复制与整书搜索；章节识别会归一化 BOM、零宽/全角空格及中文数字，把首页连续整合目录与后文同名正文标题去重并保留正文位置，不再只命中第一页目录。
- 贪吃蛇、俄罗斯方块、扫雷与蜘蛛纸牌的普通文字统一继承主题 `onSurface`，随亮/暗模式切换黑/白。主页小游戏模块可在七个游戏/变体中任意选择快捷入口，也可全部隐藏；该选择进入 v27 备份。
- 阅读私有状态升至 schema v4 并兼容 v1–v3；Android JSON 升至 v27 并继续导入 v1–v26。应用升级为 0.9.1（versionCode 21），Room 保持 v12；Windows 源码与版本未修改。

### Android 0.9.0（2026-08-05）

- 新增默认收纳在“导航”的笔记页面：直接浏览 SAF/Obsidian 笔记库中的真实文件夹和 Markdown 文件，支持文件夹/笔记新建、重命名、确认删除、自动保存、SHA-256 外部冲突的加载/覆盖/另存副本；标准图片与 `![[Wiki 嵌入]]` 共用安全预览。笔记媒体不复用日记目录，每次上传都重新选择当前笔记库内的目标文件夹，复制后回读 SHA-256 并插入相对链接。
- 日记和笔记改用共享 CommonMark 阅读组件，保留标题、粗体、斜体、列表、引用、代码和安全链接等格式；“设置 → 子页面设置 → 日记与媒体 → Markdown 阅读预览”可分别调整 H1–H6 的 12–48sp 字号。Android JSON 升至 v26，保存标题字号、笔记目录引用与新增主页模块，并继续导入 v1–v25；笔记/日记正文和媒体字节仍排除。
- TXT 章节识别扩展到更多中英文章节、卷/幕、罗马数字、序章/尾声、数字标题、Markdown 标题和括号标题；阅读设置可选“智能 / 自定义正则 / 智能 + 自定义”并调整标题长度上限，规则改变后立即重新派生目录。私有阅读状态升至 schema v3 并继续兼容 v1/v2。
- 热量进度页的运行卡可点开实时模型窗口，按图片/文字阶段显示单调时钟用时、可折叠 reasoning 与流式回复；不支持流式的兼容服务仍安全回退为普通 JSON。每日详情支持逐张图片重新估算并保留手工总量，无结果统一显示“估算失败”，整日重算仍原子更新并清除手工总量。
- 首页新增笔记入口、2048/贪吃蛇/扫雷快捷入口和记录概览；小巧思首次进入任一分类自动显示底部最新内容。玻璃面板统一传播主题前景色，2048 默认明暗色板跟随应用主题并保留手动切换。应用升级为 0.9.0（versionCode 20），Room 保持 v12；Windows 源码与版本未修改。

### Android 0.8.0（2026-08-04）

- 新增默认开启的逐页软件教学：根导航覆盖全部主页面与嵌套路由，设置、阅读和小游戏再上报内部逻辑页；每个稳定 page ID 首次进入显示一次不可误触关闭的双语蒙版。确认集合有 128 项上限并仅存设备本机；About 可关闭总开关或重置确认。
- “外观与语言”以单次 DataStore 事务保存主题、语言、字号、紧凑模式及 SAF 全局背景；图片先验证持久读取授权、128 MiB/32,768 像素边界，再用 Coil 在主题底色上应用 0–100% alpha 和 0–40dp blur。v25 备份新增 URI/参数与教学总开关，没有本机读取授权时恢复会丢弃 URI；图片字节与逐页确认不备份。
- TXT 阅读有界识别中英文常见章节标题，章节强制从稳定逻辑页开始；每约 1,800 字符分页并保护 UTF-16 代理对，侧栏目录与输入页码/进度滑块都按逻辑页跳转。阅读私有状态升至 schema v2，增加逻辑页进度和自定义不透明背景色，继续把 schema-v1 段落进度映射到对应首个逻辑页；PDF 同样支持页/进度跳转。
- 日常事件模板编辑与填写均改为多行；蜘蛛纸牌的重开只在确认对话框通过后才记录放弃、创建新局和清除旧存档。应用升级为 0.8.0（versionCode 19），Room 保持 v12；Windows 源码与版本未修改。

### Android 0.7.0（2026-08-04）

- 吃历热量估算改为根级按日期队列：顶部“估算所有”长按进入进度页，逐日逐图展示队列位置、图片模型识别、文字模型估算和保存阶段；离开吃历后任务继续，可从其他日期追加重算。每一天全部图片成功后才用一次侧车事务保存，失败保留具体阶段并继续下一天；日记/媒体目录变化时拒绝写回旧目录。
- 底栏移除每个已选导航项的持久椭圆底色。音乐频谱按对数频带重采样，新增自适应有效频段和 20–20,000 Hz 手动上下限；窄频段没有真实 FFT bin 时不借用范围外能量，权限从系统设置返回后立即重检。
- Room 升至 v12，新增联合主键 `game_statistics`。2048、贪吃蛇、俄罗斯方块、扫雷和蜘蛛纸牌分别记录符合玩法的终身累计指标；扫雷双击已揭开的数字会展开周围所有未插旗格，蜘蛛纸牌只有明确替换已操作牌局才记一次失败。
- 新增“统计”主页面，统一汇总日记、手机使用时间、健康、阅读时长、小游戏时长与特色战绩，可进入对应子页查看连续自然月、近七日、排行条和战绩指标；旧安装升级后默认用统计入口收纳手机使用时间与健康，原页面仍可单独放回底栏。
- Android JSON 升至 v24，加入音乐频率设置和小游戏特色统计；v1–v23 继续安全导入，旧备份保留本机统计，v24 按联合键取最大值幂等合并。应用升级为 0.7.0（versionCode 18）；Windows 源码与版本未修改。

### Android 0.6.6（2026-08-04）

- 修复 0.6.0 音乐可视化普通 `fillMaxSize()` 子项参与底栏测量、令 `Scaffold` 底栏占满页面高度的问题：覆盖层改为 `BoxScope.matchParentSize()`，组件自身不再施加尺寸，并增加 360×640 容器回归测试。捕获现在还受生命周期、录音权限和系统动画开关约束，静音不绘制且可靠释放 native `Visualizer`。
- 阅读状态改为异步、有界和可恢复提交，损坏/超限/提交失败时保留原文件并停止写入；阅读与蜘蛛纸牌在配置重建期间保留方向锁，真正退出后恢复系统方向。小游戏只累计实际游玩前台时间，修复暂停/恢复/旋转竞态；蜘蛛纸牌 v2 存档保留有界撤回历史。
- 吃历的每日总热量可打开完整详情：支持手工总量、按餐次编号的食物名称/分量/单位/kJ和仅详情显示的备注；重算保留备注并仅把它发送给文字模型。默认图片/文字提示词升级，自定义值原样保留；`dc-media.json` v2 加入严格输入上限、unknown 字段往返、previous/pending 与回读校验。
- 手机使用时间、其他设备缓存与健康每日统计从旧 JSON 安全迁入 Room v11，Room 成为唯一运行时权威，外部 JSON 格式继续用于迁移、v23、导入导出和云同步。应用数据页首次进入自动统计 Room 与各类目录占用；手动备份默认名改为 `DC-yyyy-MM-dd.json`。
- WebDAV 在普通 GET/PUT 缺验证头时增加有界、禁用外部实体的 `PROPFIND Depth: 0`，严格补取目标资源的单个合法强 ETag 并做条件确认；补充流量计入全局预算，仍无强验证器时失败关闭。应用升级为 0.6.6（versionCode 17），备份保持 v23；Windows 源码与版本未修改。

### Android 0.6.0（2026-08-03）

- 新增阅读主页面：SAF 导入 TXT/PDF，TXT 支持 UTF-8/UTF-16/GB18030 有界解码、五种背景、字号/行距/段距和方向锁定，PDF 使用 `PdfRenderer` 横向分页；书库、每书进度、全局显示偏好与阅读总时长保存在排除备份的私有 JSON。
- 底栏新增直方图、波形和平滑曲线音乐可视化。功能默认关闭，只有用户显式开启并授予 `RECORD_AUDIO` 后才连接 Android 全局音频会话；组件只在底栏可见时工作，不保存或上传音频。
- 小游戏新增 6–30 行/列与可调雷数的扫雷、强制横屏的单花色蜘蛛纸牌；2048 新增慢速/标准/快速三档动画速度。每个小游戏分别累计前台游玩总时长并每 30 秒写入排除备份的私有 JSON；游戏最高分和存档继续进入 Room/v23。
- 应用数据页新增安装包、数据库、偏好、阅读、娱乐计时、统计、缓存、外部目录和用户 SAF 目录的有界占用统计；收藏夹最低卡片高度压到 48dp。该版曾加入 WebDAV Last-Modified 回退，0.6.6 已收紧为仅接受普通响应或 `PROPFIND` 提供的强 ETag。
- 应用升级为 0.6.0（versionCode 16）；Android JSON 升级为 v23 并继续安全导入 v1–v22；Room 保持 v10。Windows 源码与版本未修改。

### Android 0.5.0（2026-08-03）

- 新增原生 Android App Widget“小卡片”：导航页可进入设计器，保存多套 1–6 格宽高、常用尺寸、背景/文字颜色、SAF 背景图、主页模块或其他应用启动按钮；支持应用内 pin 请求、系统小组件选择器、桌面缩放和中英文。设计随 v22 备份，背景图片文件与实例绑定不迁移。
- 吃历新增有界持久解析缓存，以日记 URI、名称、修改时间和大小验证条目；未变化的 Markdown 不再重复读取，手动刷新强制重建，修改时间不可靠的提供方继续逐次读取。批量热量估算直接更新当前状态，主页新照片按已知文件名写侧车元数据，移除两条全量扫描路径。
- 每日诗词从今日诗词单源扩展为今日诗词、Hitokoto 诗词分类和古诗词·一言轮换，请求统一限制 HTTPS、禁止重定向、设置超时与 64 KiB 响应上限；全部在线源失败或内容已看过时从 182 篇内置诗库继续选择，不再显示“暂无未看过的新诗词”。
- 健康恢复为 Health Connect 唯一数据源，删除 `ACTIVITY_RECOGNITION` 与 `TYPE_STEP_COUNTER` 采集路径，并把状态/权限说明模块移到页面最底部。云同步状态持久显示上次完成时间；Android 启动窗口/系统 splash 底色改为黑色。
- 应用升级为 0.5.0（versionCode 15）；Android JSON 升级为 v22 并继续安全导入 v1–v21；Room 保持 v10。Windows 源码与版本未修改。

### Android 0.4.2（2026-08-01）

- 修复 2048 手势被外层纵向滚动消费后只能左右移动的问题，改用起止坐标识别上、下、左、右，并把无限撤回按钮换成回转箭头。
- 修复诗词本更改第一条或把诗词移动到第一条时的排序错位，通过稳定条目 ID 和串行重排处理索引零；删除分类对话框提供“仅删除分类并归入未分类”和“分类及诗词一起删除”两个明确动作。
- “步数记录”升级为“健康”：Health Connect 同时只读聚合步数、距离与活动热量，三种指标复用总览/范围/图表/明细；私有统计 JSON 升级为 v3 并继续兼容 v1/v2，系统计步传感器仍只回退步数。
- 首页每日诗词按本地日期持久记录当天全部展示指纹，同一天启动不自动换诗，手动刷新最多进行五次有界请求且不会回退到当天已展示内容；无新诗时保留当前内容并提示。
- 日记源码与阅读预览新增媒体删除按钮和确认框；删除会清除当前日记中该文件的全部引用、媒体文件和侧车元数据，并继续执行 SHA-256 外部修改检测、写入回读校验和失败回滚。应用升级为 0.4.2（versionCode 14）；备份仍为 v21，Room 仍为 v10。

### Android 0.4.1（2026-07-31）

- 修复主页每日诗词连续刷新后在小范围内循环的问题：刷新请求有界，记录近期指纹并优先选择未出现的诗词；服务端随机池过小时安全回退，不会无限重试。诗词本新增右上角排序模式，四点手柄支持拖动和无障碍上移/下移，排序时显示标题开头的一行预览；七言自动换行只对检测到至少两句七言句式的诗词生效。
- WebDAV/S3 配置新增可保存的 User-Agent，并在每次云请求中使用；饮食图片主页改为单击拍照、长按选图并移除提示语；开启手机使用时间后，每天首次打开应用也会尝试一次统计采集。
- 2048 页面改为仅显示当前分数，去除 2048.org 文案；无限撤回改为右下角图表按钮，页面任意位置滑动均可操作；新增白天/黑夜与动画开关，棋盘放大并上下居中，数字字号按棋盘格大小适配。收藏夹卡片新增可设置的最小行高。
- Android JSON 升级为 v21，新增诗词持久排序、云同步 User-Agent 和收藏夹行高，继续安全导入 v1–v20；Room 升级为 v10 并显式迁移旧诗词顺序。应用 versionCode 为 13。

### Android 0.4.0（2026-07-30）

- Android JSON 升级为 v20：在 v19 基础上加入 Vault 的 AES-GCM 密文、IV、盐、迭代次数、加密密码校验值及换密 generation，加入 2048/贪吃蛇/俄罗斯方块的存档与最高分，并加入按设备分组的手机使用时间；单文件上限提高为 64 MiB，继续安全导入 v1–v19。旧备份没有新数据区所有权，导入时保留本机已有 Vault、游戏与使用时间。
- 手机使用时间加入稳定随机设备 ID、可编辑设备名、设备切换和“所有设备”汇总。WebDAV/S3 增加需用户明确勾选的“多设备使用时间”，使用 `usage/v1/{deviceId}.json` 每设备独立对象并按日期合并，避免 A/B 手机互相覆盖；Android 系统备份继续排除本机 ID 和统计文件，防止克隆设备身份。
- 删除 Android 旧的手动规范 v4 导出界面。Windows 0.2.0 暂不兼容 v20，也不能读取新的多设备历史；等待后续 Windows 版本升级，本次没有修改 Windows 代码。应用升级为 0.4.0（versionCode 12），Room 仍为 v9。

### Android 0.3.8（2026-07-30）

- 按 2048.org 的页面层级、固定配色和时序重做 4×4、5×5、6×6 三种 2048：移动 100 ms，新块/合并在移动后执行 200 ms appear/pop，计分飘字 600 ms；系统关闭动画时直接落到最终状态。单步撤回升级为随存档保存的完整历史栈，每次有效移动都可连续回退，直到本局开局状态。
- 修复 S3 兼容同步配置：接入点可由 SSL/TLS 开关自动补全协议，新增 Path-Style/Bucket 子域名寻址切换，覆盖 CSTCloud 等 S3 兼容端点；客户端、HTTP 和安全提取的 S3 服务端失败代码会显示在同步状态中。S3 用户名/Key 按产品要求改为应用私有 DataStore 明文并在编辑时完整回显，仍排除日志和 JSON 备份。
- 诗词本新增与小巧思相近的分类筛选、颜色、增删改和单篇归类。内置 11 个按初高中年级/教材册次组织的离线预设，共 182 篇古诗文；预设来源、固定版本与许可证记录在 assets 通知文件中，导入会原子创建/复用分类并跳过重复内容。
- “设置 → 应用数据”中把 JSON 导入导出模块移动到备份内容说明之前。Android 备份升级为 v19，加入诗词分类/关联和 S3 寻址元数据、继续支持 v1–v18；Room 升级为 v9 并显式迁移旧诗词。应用升级为 0.3.8（versionCode 11）。

### Windows 0.2.0（2026-07-29）

- 新增 Windows 收藏夹：PBKDF2-HMAC-SHA256 120k + AES-256-GCM，解锁密钥仅驻留内存；正文/备注 CRUD、安全 URL 打开、复制、拖动与键盘排序、主动锁定和全量原子改密均通过 Rust IPC 完成。Vault 私有表不被 v18 导入、恢复点或云端 JSON 覆盖。
- 新增 Android 手机使用时间的只读桌面展示。Android 端必须由用户在页面中显式通过 SAF 导出规范 v4；Windows 支持一次性快照与只读链接，使用 DPAPI 私有缓存和上次有效值回退，按范围/包名显示总计、日均、单日最高、近 7 日平均和日图表。Windows 不采集本机活动，v18/默认云同步继续排除统计明细。
- 新增 WebDAV/S3 兼容同步：多配置、DPAPI 凭据、HTTPS 默认与 HTTP 风险确认、日记/媒体/应用 JSON、仅上传/双向、SHA-256/manifest/条件请求、冲突副本及对象/总量/时间上限。总开关启用后启动约 2 分钟尝试，随后按数据库间隔调度（默认 6 小时）；下载的应用 JSON 永远先暂存预览，不自动恢复。
- Windows 设置改为 Android 风格的主页/子页层级与搜索；新增收藏夹、手机数据、应用数据/云同步、关于/检查更新入口，继续使用本地草稿、右上角保存、恢复默认和未保存离开确认。
- 接入 Tauri updater 与双重签名发布链：生产包默认启动约 60 秒后进入调度，自动尝试时间先持久化，跨失败/重启至少节流 24 小时且只提示；用户确认后才下载精确版本、验证 updater 签名、安装并重启。`SignedRelease` 同时验证 Authenticode 签名/时间戳和 Tauri `.sig`，CI 只创建待人工复核的 draft Release。
- 检入的基础 Tauri 配置故意不含 updater 公钥/端点，当前仓库和本机也没有生产 updater 私钥或 Authenticode 证书；因此本地 0.2.0 仍是未签名、更新未配置的测试构建。缺任一生产秘密时 `SignedRelease`/CI 失败关闭，不降级发布；0.1.0 用户需手动安装第一个 updater-enabled 版本。

### Windows 0.1.0 核心首版（2026-07-29）

- `windows/` 从预留目录升级为 Tauri 2 + React/TypeScript + Rust 工程，固定 MSVC Rust 工具链，使用最小 capability/CSP，并加入 Windows 10/11 x64 的原始 Release EXE 与 NSIS 配置。
- 实现桌面侧栏、宽屏分栏、中英文、系统明暗模式、字号缩放以及 Material、Liquid Glass、Organic Future 三套 CSS Token 主题。
- 打通首页、Markdown 日记/回收站、媒体/吃历、日常记录、小巧思、日期记录、诗词本/每日诗词、设置与备份的核心路径；日记正文与媒体仍原地保存在用户目录。
- Windows 数据库使用 WAL、外键、事务迁移与 busy timeout；文件保存使用 SHA-256 版本冲突、安全写入和受限媒体读取，结构化 IPC 错误不泄露正文、路径或凭据。
- 实现 Android v18 JSON 的严格预览、事务导入与双向导出；Windows 未实现数据及未知字段保存在 DPAPI 加密影子中并在导出时合并。首版不直接导入 v1–v17。
- 0.1.0 当时明确不包含浏览器、RSS、AI/热量估算、收藏夹、小游戏、云同步、自动更新、手机使用时间和步数检测；其中收藏夹、云同步、只读使用时间和 updater 基础设施于 Windows 0.2.0 加入。

### 仓库平台目录拆分（2026-07-29）

- 完整 Android Gradle 工程迁入 `android/`，包括 `app` module、Wrapper、构建配置、签名脚本和签名示例；仓库根目录继续保存 `README.md`、`README_for_ai.md`、`overview.md`、许可证等跨平台文件。构建继续兼容迁移前的根目录签名文件，签名脚本会拒绝在检测到旧长期密钥时生成替代密钥。
- 新建可被 Git 跟踪的 `windows/` 目录，为随后落地的 Windows 0.1.0 建立平台边界。此调整不改变 Android 应用版本、数据格式或用户交互。

### 0.3.7（2026-07-28）

- 修复部分厂商对多个按日查询重复返回同一个 UsageStats 汇总桶，导致微信等应用连续多天显示完全相同高值的问题。采集改为读取 `ACTIVITY_RESUMED` / `ACTIVITY_PAUSED` 以及熄屏、锁屏、关机事件，重建前台会话并按时区真实午夜（含 DST）切分；新前台应用会关闭缺失 pause 的陈旧会话，避免异常跨日累加。
- 手机使用时间私有 JSON schema 升为 v4。v1–v3 保留现有行但清空回填水位，首次刷新对 Android 仍保留事件且边界完整的日期执行权威替换，包括过去已标记 `FINAL` 的错误重复值；最老截断日及系统已清理事件的更早历史不猜测、不清零。当天没有完整事件覆盖时仅用当天单日查询回退。
- 4×4、5×5、6×6 三种 2048 新增单步撤回。每次有效移动保存移动前棋盘与分数，无效滑动不会清除撤回点；撤回状态进入游戏存档，重启继续后仍可使用。游戏结束对话框提供撤回继续，历史最高分保持单调不回退。
- 应用升级为 0.3.7（versionCode 10）；通用应用备份仍为 v18，Room 保持 v8。

### 0.3.6（2026-07-28）

- 诗词本排版设置新增“七言诗自动换行”，显示时按每七个正文字符连同紧随标点分行，不改写原诗内容；字段进入 DataStore 与 v18 备份，v17 及更早备份安全默认为关闭。
- 手机使用时间改用 `H`/`M` 紧凑单位，移除总览标题并加入单日最高与过去 7 天平均；图表移动到范围/类型按钮上方，柱高映射色阶。点选柱、折线点或方块时直接在图内悬浮显示日期和值，柱/线图的最高与最低刻度叠加在左上/左下，不占图宽；初始化阶段显示加载按钮。
- 步数采集在 Health Connect 未连接时回退到设备 `TYPE_STEP_COUNTER`。首次采样建立累计基线，此后只累加可信差额；重启、累计值回退和跨日时不猜测缺口。步数 JSON schema 升到 v2，保留 v1 导入。
- 2048 引擎参数化为 4×4、5×5、6×6 三个独立游戏与存档，旧 2048 存档继续归入 4×4；增强方块色差、滑动/合并旋转回弹与新块淡入，小游戏页面顶部安全区与其他主页面统一。
- 所有共用颜色选择页保留 HSV/hex 内容，压缩 H/S/V 标签到滑杆同行并新增 Compose 蜂窝色盘。设置子页统一加入“恢复本页默认值”草稿操作；应用数据入口简称“应用数据”，AI 聊天导航与空状态换用 Psychology 图标。
- 应用升级为 0.3.6（versionCode 9）；备份格式升级为 v18，Room 保持 v8。

### 0.3.5（2026-07-28）

- About 的应用图标选项新增用户提供的桌洞图标，并继续支持与“Desk Cubby/桌洞”名称自由组合；主页问候设置新增复制 `{name}` 按钮。
- 主页每日诗词详情改用诗词名称作为标题。诗词本移除卡片上的编辑/删除按钮，长按后显示两项操作；新增设置页，支持通过 SAF 导入字体、字号、行距、左右/居中对齐、出处与引号装饰，设置进入 DataStore 和 v17 备份。
- 手机使用时间通过 PackageManager 与可见启动器 Activity 解析真实应用标签和图标，避免直接显示 `aweme` 等内部包名。直方图、曲线和格子图改为同一行纯图标切换，三者都可点选具体日期查看当日值；使用时间 READY 状态移除“本机私有统计”说明模块。
- 收藏夹不再显示日期或无备注占位；有备注时才显示备注。卡片复制按钮替换为四点拖动手柄，顺序写入 Room；长按编辑界面新增复制并保留编辑/删除。Room 升级到 v8，7→8 显式迁移保留升级前显示顺序。
- 新生成的自动备份、手动备份、媒体元数据和云端应用 JSON 都使用 `dc` 短前缀；仍兼容旧长文件名且不删除旧文件。应用升级为 0.3.5（versionCode 8）。

### 0.3.4（2026-07-27）

- 修复 0.3.2 引入的手机使用时间空记录回归。旧实现用最长 366 天的请求读取 `INTERVAL_DAILY`，却把厂商返回的跨日聚合区间全部当作无效日丢弃，并错误推进了回填水位。
- 新采集路径先以 31 天短区间发现系统仍保留数据的范围，再对命中的日期逐个按本地自然日查询；日记录使用应用明确发出的查询边界，不再信任厂商可能扩大的 `firstTimeStamp/lastTimeStamp`。
- 手机使用历史 JSON schema 从 v2 升到 v3。导入 v1/v2 时保留所有已有日记录和 FINAL 状态，但清空旧回填水位，让升级后的首次刷新自动重新执行一次正确的有界历史发现。
- 应用版本升级为 0.3.4（versionCode 7）。

### 0.3.3（2026-07-27）

- 修复 0.3.2 升级后可能出现的启动闪退：根导航不再在首页启动时无条件创建收藏夹、手机使用时间、步数、RSS、AI、诗词本和小游戏 ViewModel，而是在进入对应页面时按需创建。
- 收藏夹元数据首次读取增加进程级失败边界；读取损坏或暂时不可用时保持锁定，不误判为首次设置、不覆盖原数据，也不会让后台协程异常终止应用。
- RSS 可信文章地址统一把 HTTPS scheme 规范化为小写，恢复视图模型边界测试。
- 应用版本升级为 0.3.3（versionCode 6）。

### 0.3.2（2026-07-27）

- 吃历顶栏新增“导出长图”：选择按天计算且首尾均包含的开始/结束日期后，通过系统文件选择器导出 PNG。导出沿用当前餐别筛选、滤镜、每行图片数量和说明开关；生成前限制像素与高度，写入后回读校验，范围过大时提示缩短日期。
- 日记阅读预览会从媒体目录的 `deskcubby-media.json` 批量读取元数据，在照片下显示地点；吃历放大查看继续显示说明、热量和地点。
- 三个小游戏在页面离开组合树或 Activity 进入 `ON_PAUSE` 时自动暂停并顺序保存；游戏颜色严格从当前主题 `primary` 和 `secondary`（设置中的主色和首个副色）插值得到。
- 小巧思页顶栏把“一行/完整”即时切换按钮放在分类菜单左侧，设置页中的同一偏好仍保留。主页内置 24 套更中性的双语问候并按日期稳定轮换；“设置 → 子页面设置 → 主页 → 主页问候”支持用户名、增加、修改和删除问候模板，动态高度顶栏完整换行显示。
- 收藏夹新设/修改密码统一按 Unicode 码点计数，允许 1 个到任意多个码点；旧密文继续兼容。条目不再设标题，改为必填正文和可选备注；右上角切换备注/日期，单击复制普通文字或在系统浏览器打开安全链接，长按编辑，并保留显式复制按钮。
- About 页支持在经典图标与新的魔法书图标之间即时切换，两个图标都兼容“Desk Cubby/桌洞”桌面名称。
- AI 聊天输入区把图片、日记上下文和小巧思上下文合并到输入框左侧 + 菜单，发送按钮放入输入框右侧。日记和小巧思可逐条预览选择，小巧思支持按分类原子地“导入整类/取消整类”；最多 50 项、单项 64 KiB、合计 256 KiB，超限拒绝而不截断。发送前冻结完整快照，作为不可信参考数据随会话保存和后续请求复用。
- 导航页设置移入“设置 → 子页面设置 → 导航页”，统一管理描述与收纳；底部导航页不再重复显示“导航页”勾选项。导航聚合页为左右列分别计算高度的双列瀑布流，每张卡片加入四点拖动手柄并支持 TalkBack 排序。
- 新增“手机使用时间”和“步数记录”主页面，默认收纳在导航页。两页均提供开始日期、已统计天数、总量、日均、7/30/90 天/全部范围，以及直方图、曲线和 GitHub 风格格子图。手机使用时间会回填 Android 仍可访问且日边界完整的历史，应用选择器显示系统名称和图标并按当前范围使用时长排序；步数从 Health Connect 读取。Health Connect 可用时打开管理入口；Android 13 及以下需要安装/更新时使用 Google Play 并提供 HTTPS 回退，完全不支持时不显示无效按钮。
- 统计开关分别位于“设置 → 子页面设置 → 手机使用时间/步数记录”。统计默认关闭且需系统授权；当天可刷新，完整日结的过去日期不再重复计算。历史分别写入 `usage-statistics.json` 与 `step-statistics.json`，不进入应用 JSON、云同步和 Android 自动备份。
- “设置 → 应用数据、备份与同步”合并原来的应用备份与云同步入口，并提供“查看整体 JSON”，以可滚动、可选择文本的对话框显示当前完整应用备份快照。
- 备份格式在 v15 基础上升级到 v16，加入可编辑主页问候并继续兼容旧版本；导入后手机使用与步数统计强制关闭，统计历史和权限从不进入备份。应用版本为 0.3.2。
- RSS 文章卡片可进入应用内多标签浏览器阅读完整原文；RSS 来源的文章导航保持 HTTPS 安全边界。诗词本编辑会按当前条目重读完整内容，只有来源、节选与缓存严格匹配时才自动展开，避免随机诗词误替换。
- 2048 新增移动、合并弹跳和新块出现动画，快速连续操作仍以逻辑最终态为准，并尊重系统动画时长设置。
- About 页检测到新版后显示“下载并安装”，安装包只写入私有缓存，并在交给系统安装器前校验包名、版本和签名。

### 2026-07 早期功能基线

- 新增“导航/More”主页面和 `showInMore` 配置。页面用自适应卡片展示调用方筛选后的主页面入口，保留自定义名称和图标；底栏显示与聚合页收纳可以分别配置，用于减少底栏拥挤。
- 新增可选云端同步。设置中可维护多个 WebDAV 或 S3 兼容服务，分别启停并选择日记、媒体、应用 JSON，以及“仅上传”或“双向”方向；支持手动立即同步，开启全局开关后还会注册联网约束的 6 小时周期任务。同步使用 SHA-256、内容寻址的不可变远端对象、manifest 的强 ETag 条件更新、传输与超时上限和冲突副本，禁止自动重定向，默认只允许 HTTPS；HTTP 必须由用户为可信本地服务明确开启。云端凭据仅在本机加密保存，不进入 JSON 备份。
- 云端同步中的日记和媒体继续遵守 SAF 边界，不把 `content://` 转换成文件路径。远端应用 JSON 只会校验并暂存，后台任务不会自动覆盖本机设置或 Room；用户必须在同步设置中明确确认恢复。
- 吃历刷新按钮左侧新增滤镜按钮：单击开关，长按进入滤镜设置。一个统一的非破坏 ColorMatrix 会应用到吃历所有照片，可实时预览并调整亮度、对比度、饱和度、色温和色调；原始媒体文件不会被重写。
- AI 聊天改为 Room 持久会话：从首条文字生成本地标题（纯图片会话使用图片标记），可查看历史、继续聊天、新建、改名和删除。聊天支持从系统文件选择器附带一张图片，并保留所需读取授权；请求仍使用 OpenAI-compatible 多模态消息结构。
- AI 页面在等待响应时显示“正在思考”，并可折叠展示服务端在 `reasoning_content`、`reasoning`、`analysis` 或 `<think>` 中实际返回的 reasoning；这不是应用或模型未返回的内部推理。最终回答与 reasoning 一起保存在本机会话中。
- 饮食分类固定为早餐、午餐、下午茶、晚餐、水果、夜宵，默认图标为 `🥪🍱🍹🍜🍊🍤`；吃历可设置图片最大高度和是否显示说明文字。
- 小巧思可选择重启后回到全部页面或上次分类，并支持单行/完整内容显示。
- Organic Future 设置菜单改为左右等高、相邻斜切的分体操作块；同一组件复用于日记月份等位置。
- 底部导航配置压缩为图标、名称、底栏开关和四点拖动同一行；手柄改善了父列表抢手势、取消复位和 TalkBack 排序。导航页收纳开关已统一移到导航页设置。
- 新增 RSS 订阅页面和设置，支持 RSS 2.0/Atom、订阅增删改启停和并发刷新；文章通过校验后可进入应用内浏览器阅读原文。
- AI 设置采用配置库：列表只显示类型符号、名称和文字/图片类型，点击或新增进入独立详情，长按可复制/删除。每套配置拥有独立 endpoint、model、系统提示词、HTTP 授权和明文 API Key；详情页可预览真实请求结构，预览使用内容占位符且不包含请求头或 Key。
- 首次启动增加页面管理说明，可直接跳到底部导航设置。
- 日记标题栏把“已保存/未保存”和“Markdown 源码/阅读预览”合并显示；原位置改为“日常记录”入口。
- 日常事件模板就是一整句文字；记录卡片会把原句预填进输入框，并给其中的 `xx` 加下划线。点击 `xx` 会自动选中两个字符以便直接替换，纸飞机把编辑后的整句原样写入日记；主页使用同一组件。旧单位模板读取时会迁移。
- 日常记录只有实际写入成功才提示成功，并在写入今日日记后刷新索引。
- 设置保存移动到右上角；子页返回时若有未保存草稿会弹窗确认。
- AI 聊天页单独选择使用的文字配置；日记设置单独选择热量估算使用的文字/图片配置，并保存两段提示词。热量流程为图片模型返回食物 JSON，再由文字模型返回 `energyKj`；结果写入媒体目录的 `deskcubby-media.json`，旧版 Markdown 标题中的 `-800kJ` 仅作只读兼容。
- 备份格式在此前 v12 纳入 AI 配置、明文 API Key、消费者模型选择和热量提示词；本轮升级到 v13，新增导航聚合选择、吃历滤镜和云同步非秘密元数据。v13 导入后云同步始终保持关闭，凭据需在本机复核或重新填写；v12 及更早版本仍可导入。设备瞬时状态和 AI 对话历史仍明确排除。
- 旧用户会一次性迁移得到日常记录主页模块，之后仍可自行移除。

### 2026-07-26 批量改进

- 主题统一：主色+副色对三套风格生效（Material/Glass 的 secondary/tertiary 由副色驱动，日记月份行在所有风格轮换副色）；主/副色、小巧思分类色、重点背景色都提供 HSV 取色器（`ui/components/ColorPickerDialog.kt`，强制不透明）。
- 外观新增紧凑模式（`LocalCompactMode`，主页与设置列表消费）；设置主页新增设置搜索（关键词直达子页）。
- 小巧思：长按菜单可标记“重点”，重点条目用可配置背景色渲染（Room v7 `highlighted` 列）；修复滑动误触发键盘开/关（移除 `imeNestedScroll`、列表滚动收起键盘的 NestedScrollConnection 与输入框滑动拦截，键盘只在点击时开、只从键盘收起）；输入框最大高度成为设置（96–400 dp）。
- 吃历：照片点击全屏放大（`ZoomableImageDialog`，沿用滤镜）；新增自动换行与每行数量 2/3/“2+3 自动”（`mealPhotoRowSizes()` 保证最后一行不留单个空位）。
- 日记：可选把未压缩原图另存系统相册（MediaStore，API≤28 需 WRITE_EXTERNAL_STORAGE，失败不影响 SAF 主写入）。
- 诗词：`PoetryRepository` 解析并缓存 jinrishici `origin.content` 全诗与朝代；主页点击弹全诗对话框，加入诗词本保存完整诗词。
- 设置新增 About 页：版本、GitHub Release 更新检查与校验后安装（`UpdateRepository`）、桌面名称“Desk Cubby/桌洞”切换（manifest 启动器别名 + `syncLauncherAlias`，MainActivity 无直接 launcher intent）。
- 新增收藏夹主页面（隐私）：密码 PBKDF2-SHA256(120k)+AES-GCM 加密条目存 `vault_items`，盐/校验值在独立 `vault_meta` DataStore，改密码全量重加密；密文与元数据不进 JSON 备份。
- 新增小游戏主页面：2048/贪吃蛇/俄罗斯方块纯 Kotlin 引擎（`games/`，可 JSON 序列化恢复），最高分与暂停存档存 `game_states`。
- 备份升级到 v14（新增设置字段与小巧思 highlighted，v13 及更早可导入）；Room 升级到 v7 并保留全部显式迁移。
- 媒体元数据 JSON：热量估算结果改为写入媒体目录 `deskcubby-media.json`（键=小写文件名；`setMealPhotoEnergy` 不再改写 Markdown 标题，旧 `-800kJ` 标题只读回退）。可选“记录照片拍摄地点”（`photoLocationEnabled`，v14 备份字段）：导入时读 EXIF GPS（MediaStore 照片用 `setRequireOriginal` + ACCESS_MEDIA_LOCATION 尽力而为）+ Geocoder 地名，写入同一 JSON；吃历放大查看显示热量与地点。
- 首次启动预设：默认主页模块精简为 今天/每日诗词/快速输入/饮食图片/年度进度；`decode()` 对无存量 homeWidgets 的全新安装跳过 widget 迁移，避免迁移把旧模块塞回预设。
- 小巧思：切换分类弹窗内可“新增分类”并自动套用（`createCategoryAndAssign`）；分类编辑对话框可导出该分类全部小巧思（ACTION_SEND 纯文本）；有机未来分类预设色板改为跨色相配色。
- 吃历顶栏新增餐别筛选（FilterAlt 图标，勾选对话框，会话内状态）；照片滤镜按钮图标改为 AutoFixHigh。
- About 页新增“应用教学”入口，内联渲染打包的 `README_for_ai.md`（完整教学，不再打开外部浏览器）。

本轮验证：101/101 JVM 测试通过（含游戏引擎、VaultCrypto、吃历排行算法）、Android 仪器测试源码编译通过（含 6→7 迁移测试源码）、`assembleDebug` 与 Lint（0 错误）通过。设备端仍需验证：系统相册写入、启动器别名切换、收藏夹实机解锁、三套主题下新 UI、6→7 迁移 connected test。

## 9. 构建与验证

```powershell
# 快速编译
.\android\gradlew.bat --project-dir .\android :app:compileDebugKotlin --offline

# JVM 测试
.\android\gradlew.bat --project-dir .\android :plugin-api:core:testDebugUnitTest --offline
.\android\gradlew.bat --project-dir .\android :app:testDebugUnitTest --offline

# 编译 Android 仪器测试源码
.\android\gradlew.bat --project-dir .\android :app:compileDebugAndroidTestKotlin --offline

# APK 与 Lint
.\android\gradlew.bat --project-dir .\android :app:assembleDebug :app:lintDebug --offline
```

输出位置：

- Debug APK：`android/app/build/outputs/apk/debug/DeskCubby.apk`
- Lint HTML：`android/app/build/reports/lint-results-debug.html`

Release 构建必须先配置长期签名；详见 `README.md`，不要提交 keystore 或密码。

Windows 验证与打包：

MSVC 构建前需有 Visual Studio 2022 Build Tools、WebView2 Runtime 和 Windows SDK 10.0.26100。仓库提供 `windows/scripts/install-windows-sdk-26100.ps1`，必须由用户在管理员 PowerShell 中运行，固定安装到 `E:\Windows Kits\10`；随后用 `-VerifyOnly` 检查 `rc.exe`、`signtool.exe`、`kernel32.lib`、Microsoft 签名与 `KitsRoot10`。只有只读校验成功，才可开始项目 MSVC 构建。

```powershell
cd .\windows
pnpm install --frozen-lockfile
pnpm lint
pnpm typecheck
pnpm test
cargo fmt --manifest-path .\src-tauri\Cargo.toml --check
cargo clippy --manifest-path .\src-tauri\Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path .\src-tauri\Cargo.toml
pnpm package:windows
pnpm package:portable
```

Windows 输出位置：

- 原始 Release EXE：`windows/src-tauri/target/release/deskcubby-windows.exe`（显式 target 构建时位于 `target/x86_64-pc-windows-msvc/release/`）
- NSIS：`windows/src-tauri/target/release/bundle/nsis/`
- 本地便携测试文件与 SHA-256：`windows/artifacts/DeskCubby-0.5.0-windows-x64-portable.exe` 与同名 `.sha256`

显式未签名测试构建使用：

```powershell
cd .\windows
.\scripts\build-release.ps1 -Mode AllowUnsignedTestBuild
```

正式 Tauri updater 签名构建使用（Authenticode 可选）：

```powershell
cd .\windows
.\scripts\build-release.ps1 -Mode SignedRelease -ReleaseTag windows-v0.5.0
```

生产模式必须取得 Tauri updater 私钥、非空私钥密码、内嵌公钥和 HTTPS endpoint，并生成 `DeskCubby-0.5.0-windows-x64-setup.exe.sig`、`latest.json` 与 `SHA256SUMS.txt`。未配置 Authenticode 时脚本验证 setup/portable 为 `NotSigned` 后继续；配置 PFX 或自定义签名命令时仍强制验证签名、证书链、可信时间戳和可选 signer subject。`.github/workflows/windows-release.yml` 对精确 `windows-vX.Y.Z` tag 执行同一 fail-closed updater 流程，创建只含五项预期资产的 draft Release；人工发布后才能把已验证 `latest.json` 提升到独立 `windows-stable` 通道。检入配置没有 updater 公钥/端点；普通 0.5.0 构建更新未配置，不得把它或 `AllowUnsignedTestBuild` 当作正式发布包。

### Windows updater 长期发布授权（2026-08-10）

用户已明确长期授权，且首套 DeskCubby 专用 Tauri updater 长期签名密钥已于 2026-08-10 生成：私钥与密码分别以当前 Windows 用户绑定的 DPAPI 密文备份在 `C:\Users\vexpaer\Documents\DeskCubby-release-keys`，GitHub `windows-production` environment 已配置 updater 所需三项 Secrets，并只允许 `windows-v*` tag 或 `main` 手动发布入口。后续 Windows Release 可复用同一套 updater 身份、按仓库 fail-closed 工作流创建 tag、核验并公开版本 Release，以及维护固定 `windows-stable` manifest 通道，无需为相同范围重复询问。密钥明文、密码和恢复材料不得进入仓库、日志、终端输出、Release 资产或应用数据。密钥轮换、删除恢复副本、导出明文、迁移到其他账号/仓库，或新增/更换 Authenticode 证书与签名服务不在此授权内，必须另行确认。

## 10. 已知边界

- Kotlin Plugin API 当前是应用内编译期扩展点，不会发现、下载或执行外部 APK/DEX。生产插件集合为空；UIAPI contribution 只进入内存 registry，首次正式插件页面仍需在统一 Compose 宿主接入，现有导航不会自动变化。

- Windows 0.8.0 可导入 Android v1–v33 并统一导出 v33，但不直接读取 Android Room 数据库；Android 内置浏览器和桌面小卡片保持平台专属。两端围棋棋局与战绩各自保存在本机并排除各自应用备份，Android 主页围棋快捷入口也不迁移；Windows 围棋还排除恢复点、自动备份和应用 JSON 云同步。
- Windows Vault、Android Vault `active`/`pending`/`items`、usage/health 明细、来源路径和云凭据都会在兼容影子、手动导出、自动备份、恢复点和应用 JSON 云上传边界清除。AI Key 按 Android v29 产品结构仍是明文字段。
- Windows 的业务联网路径包括每日诗词、用户配置的 WebDAV/S3，以及生产包内配置的 updater。每日诗词和 updater 只允许受限 HTTPS；云配置默认 HTTPS，HTTP 仅在用户为可信局域网显式确认后允许。
- Windows 媒体滤镜仅用于显示和吃历导出，不改写原图；EXIF 经纬度可以保存，但不进行在线反向地理编码。
- Windows 与 Obsidian 等外部编辑器同时修改或删除日记时会产生 SHA-256 冲突；只有用户明确选择覆盖才可忽略旧 `FileVersion`。外部删除后可接受删除、重建原文件或另存草稿副本，普通修改仍推荐重新加载或另存副本。
- 日记预览只覆盖基础 CommonMark；中文和空格媒体文件名可经百分号编码解析，无法解析或缺失的图片显示不可用状态。编辑器故意保留 Markdown 源码，避免破坏未知 Obsidian 语法。
- 媒体拖动只识别独占一行的 Markdown 图片。
- 天气模块仍是离线缓存占位。
- RSS 文章不做跨重启缓存；AI 会话可以跨重启保存，但不进入 DeskCubby JSON 备份。
- RSS 面向公网 HTTPS feed；如未来允许局域网订阅，需要重新评估 SSRF 与私网地址策略。
- AI 使用非流式 OpenAI-compatible chat/completions 接口；聊天图片和图片识别使用 `image_url` data URL。服务商及所选聊天模型需兼容这种多模态消息格式。思考面板只显示服务端明确返回或用 `<think>` 标记的内容。
- AI 上下文不是“只在本机分析”：只有用户点发送后，所选条目的冻结快照才会发往当前模型端点，并会随该会话后续请求复用。上下文条目最多 50 项、单项 64 KiB、总计 256 KiB，超限不会自动截断。
- 自动热量属于 AI 估算值，不是医学或营养测量；当前单张待识别图片限制 8 MiB，请优先开启饮食图片压缩。
- 吃历长图采用固定安全宽度并在分配 Bitmap 前限制高度和总像素；日期范围过大时需分成多次导出。缺失或损坏的照片会以占位块呈现，避免整次导出无提示失败。
- 手机使用时间依赖 Android UsageEvents 提供的应用前后台、熄屏和锁屏事件，按当前时区重建前台会话并切分自然日；统计口径和系统设置中的厂商统计可能不同。首次采集会尽力回填系统仍保留且事件流边界完整的历史，但系统已清理的数据或最老的截断日无法恢复；当天事件不可用时只回退查询当天汇总。
- Windows 手机使用时间只接受用户显式选择的兼容文件或从已启用的专用云 usage 对象下载多设备历史。快照不会随原文件变化；只读链接仅在用户手动刷新时重读。Windows 不采集、不上传，失败保留上次有效快照；不可信应用 label/icon 会回退为包名和通用图标。
- 健康统计只读取 Health Connect 中已有的步数、距离和活动热量聚合值；未安装、未授权、不可用或读取失败时不会改用系统计步传感器，也不会写入伪造的 0。
- WebDAV 远端目录需预先存在。普通 GET/PUT 没有验证头时，Android 会用最大 64 KiB 的 `PROPFIND Depth: 0` 补取目标资源属性，并只接受单个合法强 ETag 后做严格条件确认。Android 0.12.0 的 S3 不再执行故意不匹配 `If-Match` 的条件 GET 探针，也不因非标准/缺失 ETag 或忽略条件 GET 产生探针型 `SYNC_REMOTE_VALIDATION`；实际读写仍最佳努力发送条件头并处理 409/412，manifest/payload SHA-256、内容寻址和必要的写后同字节回读仍校验内容。兼容服务忽略条件写时无法保证原子并发保护；普通与强制同步都不会传播删除。
- 云端同步默认限制单对象 64 MiB、单次传输 512 MiB、10,000 个对象和 10 分钟总时长；它不是不受限制的整盘镜像工具。
- 云端应用 JSON 的下载只产生待用户确认的暂存副本；恢复 JSON 不会替换日记正文、媒体文件或 AI 对话历史。
- WebDAV/S3 自动调度仅在总开关与至少一个可用配置开启后运行；启动首轮约延迟 2 分钟，后续按 15–10,080 分钟的已保存间隔（默认 360 分钟）。取消或失败不能把不完整传输误报成功。
- 只有带生产公钥和 HTTPS endpoint 的 updater-enabled 包才自动检查；首轮约延迟 60 秒，尝试时间在联网前持久化，之后跨失败与应用重启至少间隔 24 小时且只提示。0.1.0 没有 updater 插件，必须由用户手动安装第一个启用版本。Tauri updater 公钥/私钥/密码、HTTPS endpoint 或 `.sig` 缺失时 SignedRelease 必须失败；Authenticode 未配置不再阻断正式发布，但 SmartScreen 可能显示“未知发布者”。
- SAF 文档提供方能力不一致，任何重命名/移动/覆盖流程都必须保守处理失败。
