# DeskCubby

DeskCubby 是一个本地优先的 Android 原生个人记录应用。日记正文始终保存在用户通过 Storage Access Framework 授权的 Markdown 文件中；Room 仅保存可重建索引、小巧思、浏览器记录等结构化数据。

## 当前功能

### 界面与导航

- Jetpack Compose 界面，提供 Material、Liquid Glass 和 Organic Future 三套视觉风格，支持明暗模式、中英文和 RTL/系统安全区域。
- Organic Future 支持一个主颜色和 2–5 个副颜色；应用字号可在 80%–130% 间调整。
- 首页组件可增删、排序和单独控制标题显示，并提供小巧思、日常记录、饮食图片等快捷模块。
- 底部导航可修改名称、图标、显示状态、顺序、默认启动页和标签显示方式；设置入口不可隐藏。
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
- AI 聊天使用 OpenAI-compatible `chat/completions` 非流式接口，对话仅保留在本次应用运行期间。

> [!WARNING]
> 按当前产品设计，AI API Key 会以**明文**随 AI 配置写入应用设置，也会进入 DeskCubby v12 JSON/自动备份。请勿把备份文件、应用数据目录或含 Key 的截图放入公开或共享位置。

### 备份

- 支持选择自动备份目录、立即保存、手动导入和导出单个 JSON 文件。
- 当前备份格式为 v12，包含普通应用设置、AI 配置及明文 API Key、小巧思与分类、浏览器收藏、日期记录和诗词本。
- 日记正文和媒体文件不进入 JSON 备份；跨设备恢复后需要重新授予日记与媒体目录访问权限。
- 导入 v11 及更早备份时，仅为配置 ID 与 API 地址都一致的 AI 配置保留本机已有 Key。

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
- AI 服务商需要兼容非流式 OpenAI `chat/completions` 消息格式；图片识别通过 `image_url` data URL 发送，单张待识别图片限制为 8 MiB。
- AI 热量结果仅为估算值，不应视为医学或营养测量结果。
- 文件版本历史和更完整的冲突“另存副本”流程尚未加入。
- 部分云盘文档提供方可能拒绝重命名；这时软删除会失败并保留原文件，不会直接永久删除。

## 开源许可

DeskCubby 使用 [MIT License](LICENSE) 开源。
