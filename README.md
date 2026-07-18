# DeskCubby

DeskCubby 是一个本地优先的 Android 原生个人记录应用。日记正文始终保存在用户通过 Storage Access Framework 授权的 Markdown 文件中；Room 仅保存可重建索引、小巧思、浏览器记录等结构化数据。

## 当前可用功能

- 五栏 Compose 导航：首页、日记、浏览器、小巧思、设置。
- Material 3 与 Liquid Glass 两套视觉主题，支持自定义主题色、浅色、深色和跟随系统。
- 应用界面支持中文与英文切换。
- 可配置导航名称、图标、显示状态、顺序和默认启动页；设置入口不可隐藏。
- SAF 日记/媒体目录长期授权，不把 `content://` URI 转换为文件路径。
- Markdown 平铺扫描、虚拟月份分组、今日日记、模板和日期/文件名格式。
- Markdown 源码编辑、基础阅读预览、自动保存和手动保存；媒体行可通过四点柄调整位置。
- SHA-256 外部修改检测；冲突时暂停自动保存并提供重新加载或强制覆盖。
- 普通图片上传，以及早餐/午餐/晚餐/水果/夜宵分类上传和递增编号。
- 图片说明编辑与显示尺寸限制；阅读预览中的图片按文章顺序正常显示。
- 日记软删除、恢复和永久删除。删除时先复制到日记目录内的独立回收站并校验，再删除原文件。
- 小巧思创建/更新、拖动排序、置顶、复制、软删除、回收站及可持久化分界线。
- 多标签 WebView 浏览器，支持横滑地址栏切换网页，以及前进/后退、刷新、主页、页内查找、收藏、历史、上传、下载和外部打开。
- 可添加、移除并通过四点柄排序的首页组件：日历、日期、统计、每日诗词、最近内容、快速输入、年度进度、网站入口等。
- 每日诗词使用“今日诗词”客户端 API，Token 与最近结果保存在应用本地，断网时显示缓存。

## 构建

环境要求：Android SDK 36、JDK 17 或更高版本。可以使用 Android Studio 自带的 JDK。

```powershell
.\gradlew.bat :app:assembleDebug
```

项目 Wrapper 在未设置 `GRADLE_USER_HOME` 时，会默认把 Gradle 分发和依赖缓存放在项目根目录的 `.gradle-user-home`，避免占用 C 盘。

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`

验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline
```

## 首版边界

- 编辑器采用“源码编辑 + 阅读预览”，不会把 CommonMark AST 重新序列化，因此能保留未知的 Obsidian 语法；预览只渲染基础 CommonMark。
- 源码中的媒体拖动只识别独占一行的 Markdown 图片语法。
- 图片 Markdown 链接只写媒体文件名；建议在 Obsidian 中把 DeskCubby 的媒体目录配置为附件目录。
- 天气组件目前只显示离线缓存占位；每日诗词会优先请求在线 API，失败时回退到最近缓存或内置内容。
- 文件版本历史和更完整的冲突“另存副本”流程尚未加入。
- 部分云盘文档提供方可能拒绝重命名；这时软删除会失败并保留原文件，不会直接永久删除。

## 开源许可

DeskCubby 使用 [MIT License](LICENSE) 开源。
