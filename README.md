# DeskCubby

DeskCubby 是一个本地优先的 Android 原生个人记录应用。日记正文始终保存在用户通过 Storage Access Framework 授权的 Markdown 文件中；Room 仅保存可重建索引、小巧思、浏览器记录等结构化数据。

## 当前功能

- Jetpack Compose 界面，提供 Material、Liquid Glass 和 Organic Future 三套视觉风格。
- Organic Future 支持一个主颜色和 2–5 个副颜色、斜切圆角图标控件以及明暗模式自适应；应用字号可在 80%–130% 间调整。
- 首页组件可添加、移除、显示或隐藏标题并通过四点柄排序；主页专属选项集中在“设置 → 子页面设置 → 主页”。
- 可配置底部导航的名称、图标、显示状态、顺序、默认启动页和标签显示方式；设置入口不可隐藏。
- SAF 日记与媒体目录长期授权，不把 `content://` URI 转换为文件路径。
- Markdown 日记扫描、月份分组、今日日记、模板、日期/文件名格式、源码编辑、阅读预览和自动保存。
- 图片上传支持早餐、午餐、晚餐、水果和夜宵分类；“吃历”按日期展示横向饮食照片墙。
- Markdown 源码中的独立媒体行可拖动排序，拖动时整行半透明跟随并实时显示插入位置。
- SHA-256 外部修改检测；冲突时暂停自动保存并提供重新加载或强制覆盖。
- 日记软删除、恢复和永久删除；软删除先复制到独立回收站并校验，再删除原文件。
- 小巧思支持创建、更新、分类、置顶、拖动排序、复制、分享、软删除和回收站；发送后自动收起键盘。
- 多标签 WebView 浏览器，支持地址栏横滑切换、前进/后退、刷新、主页、页内查找、收藏、历史、上传、下载和外部打开。
- 日期记录、诗词本和每日诗词；每日诗词断网时会显示最近缓存或内置内容。
- 应用数据支持自动备份、手动导入和导出；主题颜色和字号会随备份保存。
- 应用界面支持中文与英文切换。

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
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleDebug
apksigner verify --verbose app\build\outputs\apk\release\DeskCubby.apk
```

## 使用边界

- 编辑器采用“源码编辑 + 阅读预览”，不会把 CommonMark AST 重新序列化，因此能保留未知的 Obsidian 语法；预览只渲染基础 CommonMark。
- 源码中的媒体拖动只识别独占一行的 Markdown 图片语法。
- 图片 Markdown 链接只写媒体文件名；建议在 Obsidian 中把 DeskCubby 的媒体目录配置为附件目录。
- 天气组件目前只显示离线缓存占位；每日诗词会优先请求在线 API，失败时回退到最近缓存或内置内容。
- 文件版本历史和更完整的冲突“另存副本”流程尚未加入。
- 部分云盘文档提供方可能拒绝重命名；这时软删除会失败并保留原文件，不会直接永久删除。

## 开源许可

DeskCubby 使用 [MIT License](LICENSE) 开源。
