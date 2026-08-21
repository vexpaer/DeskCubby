# DeskCubby agent guide

本文件适用于整个仓库。开始修改前先阅读 [overview.md](overview.md)；它记录项目结构、数据流和最近完成的功能。[README_for_ai.md](README_for_ai.md) 是完整使用教学（应用的 About 页内直接渲染它，也是 Agent 的只读 `app_guide` 数据源），逐页面记录每个按钮和手势。它镜像一个字节相同的副本到 `android/app/src/main/assets/README_for_ai.md`，两处必须保持一致。

## 项目原则

- DeskCubby 是本地优先的跨平台应用。Android 技术栈为 Kotlin、Jetpack Compose、Hilt、Room、DataStore 和 Storage Access Framework（SAF）；Windows 技术栈为 Tauri 2、React/TypeScript、Rust、SQLite 和普通用户选择目录。
- 日记正文和媒体是用户选择目录中的真实文件。Room 中的日记数据只是可重建索引，不是正文的最终来源。
- 不要把 `content://` URI 转换成文件系统路径，也不要假设文档提供方支持原子重命名、随机访问或普通 `File` API。
- Windows 前端不得获得任意文件系统权限。所有文件、数据库、网络、DPAPI 和敏感数据操作都留在 Rust command 边界；路径必须限制在用户明确选择的根目录内。
- 保留用户已有数据。禁止用 destructive migration、清库、覆盖目录等方式规避兼容问题。
- 两个平台的 UI 都支持中文和英文、Material、Liquid Glass、Organic Future 三种风格；Android 还需处理 RTL/系统安全区域，Windows 需处理宽屏、窄窗口、键盘和系统缩放。

## Git 分支与提交

- 除非用户明确要求创建分支或 Pull Request，后续任务默认直接在 `main` 分支修改、验证和提交，不要自行创建 `codex/*`、feature 或临时开发分支。
- 开始修改前先确认当前分支和工作树状态；如果不在 `main`，在不丢失现有改动的前提下安全切回 `main`。需要同步远端时只允许快进更新，禁止用 reset、强制切换或强推覆盖已有提交。
- 完成验证后把应跟踪的改动提交到本地 `main`。只有用户明确要求推送或发布时才写入远端；推送后核对远端 `main` 指向预期提交。
- 已合并的历史功能分支不要再次合并。若分支与 `main` 的提交图不同但内容可能相同，先检查祖先关系和 tree/diff，再决定是否需要操作，避免制造重复提交。

## 首要代码入口

- 应用入口与依赖注入：
  - `android/app/src/main/java/com/deskcubby/app/MainActivity.kt`
  - `android/app/src/main/java/com/deskcubby/app/DeskCubbyApplication.kt`
  - `android/app/src/main/java/com/deskcubby/app/di/AppModule.kt`
- 导航与顶层 ViewModel：`android/app/src/main/java/com/deskcubby/app/ui/Navigation.kt`
- 设置模型与持久化：
  - `data/model/AppModels.kt`
  - `data/preferences/SettingsRepository.kt`
  - `ui/settings/SettingsViewModel.kt`
  - `ui/settings/SettingsScreen.kt`
- Room：`data/local/Entities.kt`、`Daos.kt`、`AppDatabase.kt`
- 日记文件核心：`data/repository/DiaryFileRepository.kt`
- JSON 备份：`data/backup/BackupJsonCodec.kt`、`AppBackupRepository.kt`
- 主题：`ui/theme/Theme.kt`、`OrganicFutureTheme.kt`

Windows 首要入口：

- React 入口、路由与应用壳：`windows/src/main.tsx`、`windows/src/App.tsx`
- 页面、共享组件、状态和 IPC：`windows/src/`
- Tauri 入口与 command 注册：`windows/src-tauri/src/main.rs`、`windows/src-tauri/src/lib.rs`
- SQLite、v28 备份、日记/媒体、Vault、阅读进度、使用时间、云同步与 updater：`windows/src-tauri/src/`
- 权限和打包：`windows/src-tauri/capabilities/`、`windows/src-tauri/tauri.conf.json`
- 前端与 Rust 依赖：`windows/package.json`、`windows/src-tauri/Cargo.toml`

功能对应目录见 `overview.md` 的“功能与代码地图”。

## 修改规则

### 设置和备份

`AppSettings`、DataStore 和 JSON 备份必须保持同步。新增一个可配置字段时，至少检查：

1. `AppModels.kt` 中的默认值。
2. `SettingsRepository.Keys`、`decode()`、写入方法及 `restoreFromBackup()`。
3. `BackupJsonCodec` 的编码、按版本解码、输入上限和测试。
4. 设置页面的草稿、dirty 判断、右上角保存和未保存返回提示。
5. 旧版本/旧用户是否需要一次性迁移。

当前 Android 备份格式是 v28。改变 JSON 结构时递增版本，并继续支持旧版本导入。设备瞬时状态通常不备份，例如逐页教学确认、首次导航提示和上次停留的小巧思页。v28 会备份全局背景 URI/参数、Custom 主题、教学总开关、收藏夹密文/元数据、小游戏存档与特色统计、音乐可视化频率设置、按设备分组的手机使用时间，以及不含 URI/书名/封面/正文的阅读指纹进度；但不包含背景图片文件、密码/派生密钥、阅读书架与偏好、阅读与游戏累计时长、健康历史或系统授权。

根据产品要求，AI API Key 是 `AiModelConfig` 的普通明文字段，随 `AppSettings` 写入 DataStore，也随 v12 JSON 备份导入导出；配置详情必须正常显示完整 Key，不要加密、遮罩或另建秘密存储。`LegacyAiKeyMigrationStore` 只用于把旧版 AndroidKeyStore 密文一次性迁移到普通配置，禁止用它保存新 Key。即使 Key 明文持久化，也不得写入日志、异常文本或请求 JSON 预览；网络请求只在 Authorization 请求头中使用它。

Windows 0.4.0 支持导入 Android v1–v28，并统一导出 v28。导入必须执行 64 MiB、数量、长度、枚举、重复 ID 和关联关系校验，先返回预览，用户确认后才在单事务中替换核心数据；失败完整回滚，并保留导入前恢复点。v28 的 URI-free `readerProgress` 按完整文件指纹和 LWW 合并；v1–v27 导入不得触碰本机阅读进度。保存 DPAPI 加密兼容影子前，以及每次导出前，都必须无条件清除已知的本机私有字段，包括云凭据、Vault 密文/元数据、使用时间/步数/健康明细和阅读路径/标题/封面；未知安全字段、浏览器/RSS/AI 配置和 AI Key 仍无损保留。

Windows 导出始终覆盖自己管理的设置、thoughts、categories、dateRecords 和 poems。`cloud_sync_settings.configs_managed` 是 `cloudSyncConfigs` 的所有权门：默认 `false`，此时导出完整保留 Android 影子中的非秘密云配置；用户在 Windows 新建、编辑、复制或删除云配置后置为 `true`，后续导出才用 Windows 的非秘密配置覆盖该字段并保留同 ID 的未知兄弟字段。私有字段清洗不得受这个门控影响。Windows 本机目录绝不写入 Android 的 `diaryTreeUri`、`mediaTreeUri` 或 `poetryFontUri`。

### Room

- 当前 Android 数据库版本是 12，schema 位于 `android/app/schemas`。
- 修改实体后必须提高版本、提供显式 `Migration`、注册到 `AppModule`，并更新/增加仪器测试。
- 不要启用 `fallbackToDestructiveMigration()`。
- 小巧思、分类、浏览记录、日期记录和诗词是 Room 数据；日记索引可以通过扫描文件重建。

Windows SQLite 位于 `%LOCALAPPDATA%\com.deskcubby.windows\deskcubby.db`。必须开启 WAL、foreign keys 和 busy timeout；所有 schema 变化都需要显式事务迁移与回滚测试，禁止删除数据库规避兼容问题。

### 日记、媒体与日常记录

- 所有 SAF I/O 放在 `DiaryFileRepository`，在 `Dispatchers.IO` 上执行。
- 写入前考虑外部修改，使用 SHA-256 冲突检测；失败时不能误报成功或无条件覆盖其他应用的内容。
- 多步骤文件操作优先采用“写入 → 回读校验 → 再删除/提交”，并保留可恢复路径。
- 日常记录只有真正落盘成功后才能清空输入或显示成功；写入后需要刷新日记索引。
- 吃历分类顺序由 `MealCategory.sortOrder` 决定，不要依赖 Markdown 图片出现顺序。
- 热量、逐项食物明细、日期手工总量/备注与照片拍摄地点记录在媒体目录的 `dc-media.json` v2（键为小写媒体文件名），兼容旧结构及旧名 `deskcubby-media.json`；不要再改写 Markdown 图片标题，旧标题中的 `-800kJ` 只作为只读回退解析。该 JSON 的读改写走 `DiaryFileRepository` 的 mediaMutex，执行输入上限、previous/pending、回读校验并保留未知字段；损坏或超限文件不得被当作空数据覆盖。

Windows 日记也必须使用 SHA-256 `FileVersion` 检测外部修改，并让用户选择重新加载、明确覆盖或另存副本。文件写入、回收站、媒体元数据与自动备份优先采用“临时写入 → 回读验证 → 提交/删除”的崩溃安全流程。不得跟随符号链接或 junction 越出已选择根目录；不得接受 `..`、绝对路径或保留设备名作为媒体文件名。

Windows Vault 的密码、明文与派生密钥只在 Rust 边界处理。SQLite 只保存 PBKDF2-HMAC-SHA256 120k + AES-256-GCM 的密文、nonce、盐、校验器、generation/revision 和顺序；解锁密钥仅驻留会话内存并在释放时清零。改密必须先完整解密/重加密全部条目，再用 generation/revision 比较并在一个事务中原子替换。Vault 不进入 v28、自动备份、恢复点或云同步。

Windows 手机使用时间只接受用户显式选择的 Android 规范 v4、usage/v1 设备对象或含 `usageDevices` 的 v20–v28 文件。快照与只读链接的缓存、来源元数据使用 purpose-bound DPAPI 私有容器；链接刷新不得写入、改名或删除源文件，也不得调用 Windows 活动统计 API。源缺失、损坏、超限、重解析点或读取中变化时保留上次有效快照。统计明细和来源路径不进入 v28、自动备份或上传云同步。

Android 0.4.0 起已移除「导出给 Windows」v4 界面；规范 v4 codec 仅为旧文件兼容保留，不得重新暴露为运行时权威。Android 0.10.0 当前以 Room v12 作为手机使用时间、其他设备缓存、健康每日统计和小游戏特色统计的唯一运行时权威；旧 `usage-statistics.json`、设备缓存 JSON 和 `step-statistics.json` 仅作事务化、幂等迁移源，JSON codec 继续用于 v28、导入导出和云同步且外部格式保持兼容。

### 网络与秘密

- 普通网络请求继续使用 `HttpURLConnection`；仅 WebDAV `PROPFIND` 因 Android 的方法限制使用无重定向、无重试且有界的 OkHttp 客户端。没有 Retrofit，也不要把 OkHttp 扩散到其他网络边界。
- RSS 默认只允许 HTTPS，限制重定向、响应体大小、XML DOCTYPE 和并发数。
- AI 默认要求 HTTPS；HTTP 仅在用户明确允许时用于可信本地服务。重定向不得跨主机或从 HTTPS 降级。
- 网络读取必须设置连接/读取超时和响应体上限，并正确传播协程取消。
- 不要把 API key、Authorization header 或完整私人内容打印到日志。

Windows 0.4.0 的业务联网边界包括每日诗词、RSS、用户配置的 AI、WebDAV/S3 和生产包 updater。每日诗词、RSS 与 updater 只允许受限 HTTPS；AI 默认 HTTPS，HTTP 仅允许用户明确确认的可信本地端点；云端点默认 HTTPS，HTTP 仅在用户为可信局域网显式确认后允许。所有请求都要限制重定向、超时、响应/对象大小、数量与总运行时间，并正确传播取消；错误不得携带端点响应体、路径、正文或凭据。

WebDAV 密码及 S3 Access Key、Secret Key、Session Token 必须由当前 Windows 用户的 DPAPI 加密，前端只能看到“是否已配置”。远端应用 JSON 只能暂存并走 v28 预览/确认恢复，不得由后台自动覆盖结构化数据。云同步配置写回 v28 时只包含非秘密元数据；已知秘密字段在入影子前和导出前都必须清洗。阅读进度使用唯一 `reading/v1/progress.json`，只含指纹、类型、位置、页数和时间戳。

Updater 只在有效配置同时含非空公钥与 HTTPS endpoint 时启用；检入的基础配置必须保持离线。自动检查启动约 60 秒后首次尝试，尝试时间先持久化，再越过网络边界，跨重启至少间隔 24 小时；自动检查只提示，下载/验证/安装必须由用户确认。`SignedRelease` 始终必须验证 Tauri updater `.sig`，任何 updater 私钥、私钥密码、端点、公钥或签名缺失都应失败关闭。Windows Authenticode 是可选的：未配置证书或 `signCommand` 时，正式包必须明确验证为 `NotSigned`，并说明 SmartScreen 可能显示“未知发布者”；一旦配置 Authenticode，则仍必须验证签名身份、证书链和可信时间戳。

### Compose UI

- 文案使用 `tr("中文", "English")`；不要只补一种语言。
- 使用 `MaterialTheme` 和现有视觉 token，避免为普通页面硬编码颜色。
- Organic Future 的相邻斜切双块使用 `ui/components/OrganicSplitActionRow.kt`，不要复制一套近似 Shape。
- 排序手柄使用 `ui/components/FourDotDragHandle.kt`，同时考虑触摸冲突、取消状态和 TalkBack 操作。
- 页面需处理 `safeDrawing`、navigation bars、IME，以及返回键行为。
- 设置子页采用本地草稿；保存按钮在右上角，未保存返回必须显示确认框。不要让底部导航绕过 dirty 检查。
- 主页面从 `NavItemId` 驱动。新增页面时同步更新 `NavItemId`、`Navigation.kt`、`iconFor()`、导航图标选择器、默认可见性和首次提示文案。

### Windows React UI

- 页面文案必须同时提供中文和英文；颜色只消费 Material、Liquid Glass、Organic Future 三套 CSS token，不在普通组件硬编码主题色。
- 使用桌面侧栏与宽屏分栏，窄窗口可收起侧栏；18 个支持页面可在“设置 → 桌面导航”中显隐、排序、移动或归入自定义分类，分类可折叠。设置入口必须始终固定可达，隐藏页仍可从导航聚合页或相关业务入口打开。
- 设置子页使用本地草稿，保存位于右上角；恢复默认只改草稿，离开 dirty 页面必须确认。
- 键盘操作、焦点可见性、Radix 对话框语义、拖动排序的键盘替代操作、加载/错误/空状态都要测试。
- TypeScript 与 Rust 的 IPC DTO 必须版本化并集中定义。Rust 错误只返回稳定代码与安全消息，禁止携带正文、绝对路径、备份原文、API Key 或其他兼容影子字段。

## 常用验证命令

Windows PowerShell：

```powershell
.\android\gradlew.bat --project-dir .\android :app:compileDebugKotlin --offline
.\android\gradlew.bat --project-dir .\android :app:testDebugUnitTest --offline
.\android\gradlew.bat --project-dir .\android :app:compileDebugAndroidTestKotlin --offline
.\android\gradlew.bat --project-dir .\android :app:assembleDebug :app:lintDebug --offline
```

Debug APK 输出到：

```text
android/app/build/outputs/apk/debug/DeskCubby.apk
```

有设备/模拟器时再运行 connected Android tests。涉及 Room、SAF、备份或导航的改动，不能只依赖 Kotlin 编译。

Windows PowerShell：

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

需要显式生成未签名本地测试包时运行（禁止发布）：

```powershell
cd .\windows
.\scripts\build-release.ps1 -Mode AllowUnsignedTestBuild
```

只有在受保护发布环境已经提供 Tauri updater 私钥、非空密码、公钥和 HTTPS endpoint 时，才运行；Windows Authenticode 证书可选：

```powershell
cd .\windows
.\scripts\build-release.ps1 -Mode SignedRelease -ReleaseTag windows-v0.4.0
```

普通 Windows 输出通常位于：

```text
windows/src-tauri/target/release/deskcubby-windows.exe
windows/src-tauri/target/release/bundle/nsis/
windows/artifacts/DeskCubby-0.4.0-windows-x64-portable.exe
```

成功的 `SignedRelease` 还应生成：

```text
windows/artifacts/DeskCubby-0.4.0-windows-x64-setup.exe
windows/artifacts/DeskCubby-0.4.0-windows-x64-setup.exe.sig
windows/artifacts/latest.json
windows/artifacts/SHA256SUMS.txt
```

普通本地构建和 `AllowUnsignedTestBuild` 仍是 updater 未配置的测试产物。只有实际成功运行 fail-closed `SignedRelease` 并验证 Tauri `.sig` 和清单后，才可称为正式发布包。若本次未配置 Authenticode，必须明确说明 PE 为 `NotSigned` 且 SmartScreen 可能显示“未知发布者”；若配置了 Authenticode，则还必须验证证书身份、证书链和可信时间戳。

## 测试放置

- 纯函数、格式化、归一化、迁移逻辑：`android/app/src/test`。
- Room DAO、Android API、完整 JSON codec：`android/app/src/androidTest`。
- 新增边界时优先测试：旧数据迁移、超长/恶意输入、重复点击、取消、外部文件冲突和失败后恢复。
- Windows 前端组件、状态与 mocked IPC：`windows/src/**/*.test.ts(x)`。
- Windows Rust 数据库、v28 codec、路径/文件、媒体、Vault、reader、usage、cloud 与 updater 边界：对应 `windows/src-tauri/src/` 模块内的 `#[cfg(test)]` 或 `windows/src-tauri/tests/`。
- Windows 新增边界优先测试：事务回滚、未知字段/AI Key 无损透传、入影子/导出双重私有字段清洗、`configs_managed` 所有权切换、恶意 JSON、路径穿越、Windows 保留文件名、外部编辑冲突、崩溃安全写入、回收站碰撞、媒体元数据并发、吃历导出像素上限、Vault 损坏/并发改密、v4 只读源变化、DPAPI 绑定、云条件请求/冲突/取消，以及 updater 未配置离线、持久 24 小时节流和签名失败关闭。

## 不要修改或提交

- `android/app/build/`、构建缓存和其他生成文件。
- `windows/node_modules/`、`windows/dist/`、`windows/coverage/`、`windows/artifacts/`、`windows/src-tauri/target/`、Tauri 生成目录和根 `.corepack-cache/`；必须提交 `pnpm-lock.yaml`、`Cargo.lock`、源码、图标、capability 和打包配置。
- `android/release/DeskCubby-release.jks`、`android/keystore.properties`、迁移前根目录中的同名本机签名文件或任何密钥。
- 与当前任务无关的用户改动。工作树可能本来就不干净，先查看 `git status` 和目标文件 diff。

## 完成任务前检查

- 需求是否在真实导航路径中可达，而不只是创建了孤立 Screen。
- 新设置是否持久化、可备份且能从旧版本安全恢复。
- 成功提示是否发生在持久化成功之后。
- 中文、英文、三套主题、窄屏、IME、系统返回和无障碍是否合理。
- 至少运行相关单元测试及 `assembleDebug`；大范围改动再运行 Lint 和 Android test 编译。
- Windows 功能是否只通过受限 IPC 到达 Rust；所有本机路径是否来自用户选择且经过根目录约束，错误中是否没有正文、路径或兼容影子内容。
- Windows v28 导入是否先预览再事务提交，旧版本导入是否保持兼容，未知安全字段是否仍能往返；Android URI 是否保持不透明且 Windows 路径没有写回备份。
- Windows 兼容影子是否在保存前清理已知私有字段，所有导出路径是否再次清理；`configs_managed = false` 时是否原样保留 Android 非秘密云配置，变为 `true` 后是否只覆盖 Windows 管理的非秘密字段。
- Vault 是否始终排除 v28/恢复点/备份/云同步；usage 是否只读 Android v4、usage/v1 或 v20–v28 投影且不采集 Windows；云凭据是否只以 DPAPI 密文存在并不回传前端。
- Updater 未配置构建是否完全离线；自动检查尝试时间是否持久化并跨重启至少节流 24 小时；生产签名缺失时构建是否失败关闭。
- Windows 设置 dirty、三主题、中英文、窄窗口、键盘操作与 mocked IPC 是否覆盖；至少运行 lint、typecheck、前端测试、Rust fmt/clippy/test 和与改动风险相称的 Tauri 构建。
- 功能或交互发生变化时，必须同步更新三份文档：`overview.md`（结构、数据流、"最近完成的功能"）、`README_for_ai.md`（完整教学——新增/修改的页面、按钮、手势、设置项都要反映，路径写完整如"设置 → 子页面设置 → 日记与媒体"；它被应用内 About 页直接渲染、也是 Agent 的 `app_guide` 只读数据源，过时会直接误导用户与 AI）、`README.md`（功能清单与版本号相关段落）。改 README_for_ai.md 时同步复制到 `android/app/src/main/assets/README_for_ai.md`，两处必须保持字节一致。
- 交付时说明实际运行过的验证，以及 APK/EXE/NSIS/报告路径；不要声称未实际运行的测试、安装、便携启动或设备检查已经执行。

## Android GitHub Release 手动交接

- Android 版本需要发布 GitHub Release 时，代理必须完成实现、三份文档同步、版本号更新、相关验证、签名 release APK 生成与校验，并把所有应跟踪的改动提交到 `main`；默认停在“用户可以双击批处理直接发布”的状态。
- 根目录本机批处理固定为 `publish-android-release.bat`，发布说明固定为 `.android-release-notes-v<version>.md`，两者都由 `.gitignore` 忽略并禁止提交。每次发布都要更新其中的仓库、分支、版本、tag、versionCode、最终提交 SHA、APK 路径、APK 大小、SHA-256、Android SDK 校验工具路径、标题和发布说明；不得写入 token、密码、签名口令或其他秘密。
- 批处理必须先失败关闭地检查：当前分支和提交、工作树干净、origin 仓库、源码版本、APK 包名/versionCode/versionName、APK 大小与 SHA-256、APK 签名、`gh` 登录、远端仓库，以及同名 tag/Release 的精确状态。检查通过后才允许 `git push`，并确认远端 `main` 指向预期提交。同版本草稿可以续跑，但已存在的 tag、已发布 Release 或不匹配资产不得被移动或覆盖。
- 所有决定发布状态的 GitHub 读取都必须有限次数重试，并区分明确的 `404`/`release not found` 与超时、`EOF`、空输出等临时查询失败；临时失败不得被推断成“tag/Release/资产不存在”。草稿 Release 需要通过 `gh release view` 查询，因为 REST 的按 tag 查询可能看不到尚未发布的草稿。
- Release 先创建为草稿，再单独上传唯一的 `DeskCubby.apk`；公开前核对资产名、字节数和 GitHub 返回的 SHA-256 digest。若网络命令返回失败，先重新读取实际远端状态：正确草稿和已上传资产应安全续跑，零资产草稿可继续上传，其他状态停止且不自动删除、覆盖或替换已有 tag、Release 或资产。
- 交付前运行 `publish-android-release.bat --check` 完成只读预检（脚本内部也兼容 `DESKCUBBY_RELEASE_DRY_RUN=1`）；不得为测试批处理而实际 push 或创建 Release。确认 `git status` 干净、签名 APK 仍存在，并把双击文件的绝对路径交给用户。
- 除非用户再次明确要求代理直接发布，否则代理不得执行 `publish-android-release.bat` 的真实发布路径，也不得自行运行 `git push`、创建 tag 或调用 `gh release create/edit/upload`；这些外部写操作由用户双击批处理触发。
