# DeskCubby

DeskCubby 是一个本地优先的 Android 原生个人记录应用。日记正文始终保存在用户通过 Storage Access Framework 授权的 Markdown 文件中；Room 仅保存可重建索引、闪思、浏览器记录等结构化数据。

## 当前可用功能

- 五栏 Compose 导航：首页、日记、博客、闪思、设置。
- Material 3 与 Liquid Glass 两套视觉主题，支持浅色、深色和跟随系统。
- 可配置导航名称、图标、显示状态、顺序和默认启动页；设置入口不可隐藏。
- SAF 日记/媒体目录长期授权，不把 `content://` URI 转换为文件路径。
- Markdown 平铺扫描、虚拟月份分组、今日日记、模板和日期/文件名格式。
- Markdown 源码编辑、基础阅读预览、自动保存、手动保存、撤销/重做。
- SHA-256 外部修改检测；冲突时暂停自动保存并提供重新加载或强制覆盖。
- 普通图片上传，以及早餐/午餐/晚餐/水果/夜宵分类上传和递增编号。
- 图片说明编辑、显示尺寸限制；阅读预览中长按并上下拖动独占段落图片块。
- 日记软删除、恢复和永久删除。软删除通过 SAF 文档重命名保存原内容。
- 闪思创建/更新、置顶、复制、软删除、回收站及可持久化分界线。
- WebView 地址栏、前进/后退、刷新、主页、页内查找、收藏、历史、上传、下载、外部打开和页面恢复。
- 可添加、移除和排序的首页组件：日历、日期、统计、最近内容、快速输入、年度进度、网站入口等。

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
- 图片拖动只针对独占段落的 Markdown 图片块。
- 两个独立 SAF 目录之间无法可靠计算真实相对路径，因此设置中提供“Markdown 媒体路径前缀”，默认值为 `../Attachments`。
- 天气组件目前只显示离线缓存占位；每日诗词使用内置离线内容。在线数据源与缓存更新计划放在下一阶段。
- 文件版本历史和更完整的冲突“另存副本”流程尚未加入。
- 部分云盘文档提供方可能拒绝重命名；这时软删除会失败并保留原文件，不会直接永久删除。

## 开源许可

DeskCubby 使用 [MIT License](LICENSE) 开源。
