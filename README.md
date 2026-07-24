# DeskCubby

DeskCubby 是一个本地优先的 Android 原生个人记录应用。日记正文始终保存在用户通过 Storage Access Framework 授权的 Markdown 文件中；Room 保存可重建索引、小巧思、浏览器记录、AI 会话等结构化数据。可选云端同步不会改变本地文件的 source of truth 地位。

## 当前功能

### 界面与导航

- Jetpack Compose 界面，提供 Material、Liquid Glass 和 Organic Future 三套视觉风格，支持明暗模式、中英文和 RTL/系统安全区域。
- Organic Future 支持一个主颜色和 2–5 个副颜色；应用字号可在 80%–130% 间调整。
- 首页组件可增删、排序和单独控制标题显示，并提供小巧思、日常记录、饮食图片等快捷模块。
- 底部导航可修改名称、图标、显示状态、顺序、默认启动页和标签显示方式；设置入口不可隐藏。
- “导航/More”聚合页可收纳不想直接放在底栏的其他主页面。每个适用页面可分别选择“显示在底栏”和“放入导航页”，减少底栏拥挤。
- 首次启动会提示应用包含多个可手动开关的页面，并可直接进入底部导航设置。
- 设置子页使用右上角保存；存在未保存修改时返回会先弹出确认提示。

### 日记、媒体与日常记录

- 通过 Storage Access Framework 持久授权日记和媒体目录，不把 `content://` URI 转换成文件路径。
- 支持 Markdown 日记扫描、月份分组、今日日记、模板、文件名格式、源码编辑、阅读预览和自动保存。
- Markdown 中独占一行的媒体可拖动排序；SHA-256 用于检测外部修改，发生冲突时暂停覆盖并提供重新加载或强制保存。
- 日记支持软删除、恢复和永久删除；软删除会先复制到独立回收站并回读校验。
- 日常记录使用整句模板，`xx` 代表可快速选中替换的内容；编辑后的整句话可直接追加到当前日记或今日日记。
- 日记编辑页和首页都可快速打开、填写并发送日常记录。

### 饮食记录与 AI 热量估算

- 饮食分类固定为早餐、午餐、下午茶、晚餐、水果、夜宵，默认图标为 `🥪 🍱 🍹 🍜 🍊 🍤`。
- “吃历”按日期展示饮食照片，可设置图片高度上限和是否显示文字说明。
- 吃历刷新按钮左侧提供统一照片滤镜：单击开关，长按进入设置，可调整亮度、对比度、饱和度、色温和色调。滤镜只影响应用内显示，不会改写原始图片。
- 配置文字模型与图片识别模型后，可开启上传图片自动估算热量：图片模型先返回食物 JSON，再由文字模型计算总能量。
- 估算结果写入图片标题，例如 `午餐-800kJ`；吃历可汇总每日热量，也可批量计算未处理图片或重新计算指定日期。

### 小巧思、浏览器与其他页面

- 小巧思支持创建、更新、分类、置顶、拖动排序、复制、分享、软删除和回收站。
- 可选择小巧思单行/完整显示，以及重启后回到全部页面或上次停留的分类。
- 多标签 WebView 浏览器支持地址栏横滑切换、前进/后退、刷新、主页、页内查找、收藏、历史、上传、下载和外部打开。
- 日期记录、诗词本和每日诗词；每日诗词在线请求失败时使用最近缓存或内置内容。
- RSS 页面支持 RSS 2.0 与 Atom 订阅的增删改、启停、刷新和文章打开。

### AI 配置与聊天

- AI 配置库支持多套文字/图片模型；每套配置包含名称、类型、API 地址、模型名称、API Key、温度、系统提示词和 HTTP 许可。
- 配置列表点击进入详情，长按可复制或删除；AI 聊天和日记热量估算可分别选择要使用的配置。
- 配置详情可预览实际请求 JSON 结构。文字消息、图片提示词和图片数据以占位符显示；API Key 位于 Authorization 请求头，不属于 JSON 预览。
- AI 聊天使用 OpenAI-compatible `chat/completions` 非流式接口；会话和消息保存在 Room，可查看历史、继续聊天、新建、改名和删除，并根据首条消息在本地自动生成标题。
- 聊天可通过系统文件选择器附带一张图片。服务商和所选模型需要兼容 `image_url` data URL 形式的多模态消息。
- 等待回复时会显示“正在思考”；若服务端明确返回 `reasoning_content`、`reasoning`、`analysis` 或 `<think>` 内容，可在折叠面板中查看并随会话保存。应用不会展示模型未返回的内部推理。

> [!WARNING]
> 按当前产品设计，AI API Key 会以**明文**随 AI 配置写入应用设置，也会进入 DeskCubby v13 JSON/自动备份。请勿把备份文件、应用数据目录、同步到云端的应用 JSON 或含 Key 的截图放入公开或共享位置。

### 云端同步

- 设置中可维护多个 WebDAV 或 S3 兼容服务配置，分别启停，并选择日记、媒体、应用 JSON 以及“仅上传”或“双向”同步。
- 支持手动立即同步；开启全局开关后会注册需要联网的 6 小时周期任务。同步日记和媒体时仍只通过 SAF 访问用户授权目录。
- 同步使用 SHA-256、远端 manifest、强 ETag 条件请求和冲突副本保护并发修改。当前不会传播删除：某一侧缺失的文件会按同步方向重新上传、下载或跳过。
- 默认只允许 HTTPS；HTTP 仅能由用户为可信本地服务明确开启。WebDAV 密码及 S3 Access Key、Secret Key、Session Token 使用 Android Keystore 支持的 AES-GCM 加密后保存在设备私有存储，不进入日志、DataStore 或 DeskCubby JSON 备份。
- 选择“应用 JSON”会把未加密的 v13 备份上传到所选服务，其中包含明文 AI API Key。HTTPS 保护传输过程，但不提供远端对象的端到端加密。
- 从云端下载的应用 JSON 只会校验并暂存在应用私有目录；后台任务不会直接覆盖本机设置或 Room，必须由用户在同步设置中确认后才会恢复。

### 备份

- 支持选择自动备份目录、立即保存、手动导入和导出单个 JSON 文件。
- 当前备份格式为 v13，包含普通应用设置、导航聚合选择、吃历滤镜、云同步非秘密元数据、AI 配置及明文 API Key、小巧思与分类、浏览器收藏、日期记录和诗词本。
- WebDAV/S3 凭据、AI 对话历史、日记正文和媒体文件不进入 JSON 备份；跨设备恢复后需要重新授予日记与媒体目录访问权限，并复核或重新填写云端凭据。
- 导入 v13 备份后云同步始终保持关闭，避免在未复核目标和凭据前自动传输；v12 及更早版本仍受支持。
- 导入 v11 及更早备份时，仅为配置 ID 与 API 地址都一致的 AI 配置保留本机已有 Key。

### 本地数据库

- Room 数据库当前版本为 6，保留 1→2、2→3、3→4、4→5 和 5→6 的显式迁移，不使用 destructive migration。
- v6 新增 AI 会话和消息表；删除会话会级联删除其消息。AI 历史保存在本机，但不属于 v13 JSON 备份。

## 构建环境

- Android SDK 36
- JDK 17 或更高版本（可以使用 Android Studio 自带的 JDK）

项目 Wrapper 在未设置 `GRADLE_USER_HOME` 时，会默认把 Gradle 分发和依赖缓存放在项目根目录的 `.gradle-user-home`，避免占用 C 盘。

### Debug APK

```powershell
.\gradlew.bat :app:assembleDebug
```

输出：`app/build/outputs/apk/debug/DeskCubby.apk`

### Release 签名

首次打包前运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\generate-release-keystore.ps1
```

脚本会生成：

- `release/DeskCubby-release.jks`：应用的长期 Release 签名密钥。
- `keystore.properties`：本机签名参数和随机强密码。

这两个文件均已被 `.gitignore` 排除，不会进入 Git。请把它们一起加密备份到可靠位置；Android 后续版本必须继续使用同一把签名密钥，丢失或更换密钥会导致已安装版本无法正常升级。

然后构建已签名 Release APK：

```powershell
.\gradlew.bat :app:assembleRelease
```

输出：`app/build/outputs/apk/release/DeskCubby.apk`

如需手动配置，可复制 `keystore.properties.example` 为 `keystore.properties`。CI 也可以改用以下环境变量，避免把密码写入文件：

- `DESKCUBBY_RELEASE_STORE_FILE`
- `DESKCUBBY_RELEASE_STORE_PASSWORD`
- `DESKCUBBY_RELEASE_KEY_ALIAS`
- `DESKCUBBY_RELEASE_KEY_PASSWORD`

Release 任务在签名配置缺失或不完整时会直接失败，不会误生成未签名安装包。

### 验证

```powershell
.\gradlew.bat :app:compileDebugKotlin --offline
.\gradlew.bat :app:testDebugUnitTest --offline
.\gradlew.bat :app:compileDebugAndroidTestKotlin --offline
.\gradlew.bat :app:assembleDebug :app:lintDebug --offline
```

本轮功能合并后已完成 78 个 JVM 测试（78/78 通过）、Android 仪器测试源码编译、Debug APK 构建和 Lint。当前没有连接设备或模拟器，因此尚未执行 connected Android tests；WebDAV/S3 实服、SAF 提供方差异、持久图片授权和三套主题交互仍建议在设备上验证。

构建产物与报告：

- Debug APK：`app/build/outputs/apk/debug/DeskCubby.apk`
- Lint HTML：`app/build/reports/lint-results-debug.html`

如需验证已签名的 Release APK：

```powershell
apksigner verify --verbose app\build\outputs\apk\release\DeskCubby.apk
```

## 使用边界

- 编辑器采用“源码编辑 + 阅读预览”，不会把 CommonMark AST 重新序列化，因此能保留未知的 Obsidian 语法；预览只渲染基础 CommonMark。
- 源码中的媒体拖动只识别独占一行的 Markdown 图片语法。
- 图片 Markdown 链接只写媒体文件名；建议在 Obsidian 中把 DeskCubby 的媒体目录配置为附件目录。
- 天气组件目前只显示离线缓存占位；每日诗词会优先请求在线 API，失败时回退到最近缓存或内置内容。
- RSS 面向公网 HTTPS Feed；文章列表当前不跨应用重启保存。
- AI 服务商需要兼容非流式 OpenAI `chat/completions` 消息格式；聊天图片和图片识别通过 `image_url` data URL 发送，单张图片限制为 8 MiB。思考面板只显示服务端实际返回的 reasoning。
- AI 热量结果仅为估算值，不应视为医学或营养测量结果。
- WebDAV 远端目录需要预先存在，WebDAV/S3 服务必须可靠支持强 ETag 与条件 GET/PUT；不满足这些条件时同步会安全失败。
- 云端同步默认限制单对象 64 MiB、单次传输 512 MiB、10,000 个对象和 10 分钟总时长，不适合作为不受限制的整盘镜像工具。
- 云端应用 JSON 的下载只产生待确认的暂存副本；恢复 JSON 不会替换日记正文、媒体文件或 AI 对话历史。
- 文件版本历史和更完整的冲突“另存副本”流程尚未加入。
- 部分云盘文档提供方可能拒绝重命名；这时软删除会失败并保留原文件，不会直接永久删除。

## 开源许可

DeskCubby 使用 [MIT License](LICENSE) 开源。
