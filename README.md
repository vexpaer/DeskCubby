# DeskCubby

[前往 GitHub Releases 下载最新版](https://github.com/vexpaer/DeskCubby/releases)

DeskCubby 是一个本地优先的跨平台个人记录应用。Android 端使用 Kotlin/Jetpack Compose，Windows 端使用 Tauri 2 + React/TypeScript + Rust；两个客户端都把 Markdown 日记和媒体保存在用户自己选择的目录中，应用数据库只保存结构化记录、设置与可重建索引。

当前版本：Android **0.4.0**；Windows **0.2.0**。

仓库按平台拆分：完整 Android 工程位于 `android/`，Windows 工程位于 `windows/`；`README.md`、`TUTORIAL.md`、`overview.md`、许可证等项目级文档继续保留在仓库根目录。

## Windows 0.2.0

Windows 客户端支持 Windows 10/11 x64，采用适合桌面宽屏的侧栏和分栏布局，并保留中文/英文、跟随系统明暗模式、字号缩放以及 Material、Liquid Glass、Organic Future 三套主题。

0.2.0 在 0.1.0 记录核心上加入 Windows 本机收藏夹、WebDAV/S3 云同步、Android 手机使用时间只读展示和签名更新基础设施。已实现范围：

- 首页：日历、今天、每日诗词、最近日记/小巧思、快速输入、日常记录、饮食图片、月度统计、日记字数、年度进度和随机旧日记；不会显示尚未实现功能的入口。
- 日记：选择并原地使用已有日记目录，扫描 Markdown、按月份分组、创建/重命名、CodeMirror 源码编辑、CommonMark 预览、外部修改冲突处理、软删除、恢复和永久删除。日记正文仍以真实文件为准。
- 媒体与吃历：选择已有媒体目录，安全导入图片并写相对 Markdown 文件名；兼容 `dc-media.json` 与旧名 `deskcubby-media.json`，显示已有热量/地点但不调用 AI；支持餐别/日期筛选、每行 2 张、3 张或智能换行、非破坏滤镜、缺图占位、全屏查看和带像素上限的 PNG 长图导出。
- 日常记录：模板增删改、`xx` 快速替换，并可追加到当前日记或今日日记；只有文件写入成功后才清空输入并提示成功。
- 小巧思：分类、颜色、置顶、重点、拖动排序、软删除、恢复、永久删除以及按分类导出文本。
- 日期记录、诗词本和每日诗词：完整增删改；每日诗词只访问当前 HTTPS 接口，设置超时与响应上限，失败时使用本地缓存或内置回退。
- 收藏夹：首次设置密码后，以 PBKDF2-HMAC-SHA256（120,000 次）派生 AES-256-GCM 密钥；正文和备注只以密文保存在 Windows SQLite，派生密钥只在解锁会话内存中存在。支持复制普通正文、打开经过 Rust 校验的 HTTP(S) 链接、编辑、永久删除、拖动/键盘排序、主动锁定和原子改密。收藏夹密文不进入 v18、自动备份或云同步。
- 手机使用时间：Windows 0.2.0 **只显示、不采集**，但它只认识旧版 Android 手动导出的规范 v4 文件。Android 0.4.0 已移除该手动导出入口并改用 v20/按设备云同步，因此此 Windows 页面暂不能接收 Android 0.4.0 的新数据；需等待后续 Windows 版本兼容 v20。
- WebDAV/S3：可维护多个服务，选择日记、媒体、应用 JSON以及仅上传/双向同步；凭据用当前 Windows 用户的 DPAPI 加密，不回传前端、不进 v18。默认只允许 HTTPS，HTTP 必须显式确认仅用于可信局域网；双方变化时保留冲突副本，远端 JSON 下载只暂存，预览确认后才恢复。除手动「立即同步」外，保存并开启总开关后会在启动约 2 分钟后尝试一次，之后按设置间隔执行（默认 6 小时）；没有启用配置或生产凭据时不会传输。
- 设置与备份：设置主页改为接近 Android 的「外观与语言 / 子页面设置 / 应用数据 / 桌面导航 / 关于」层级，支持搜索；子页使用本地草稿、右上角保存、恢复本页默认值和未保存离开确认。支持 Android v18 JSON 的预览导入、手动导入/导出与 `pending/current/previous` 自动备份轮换。选定备份目录后，应用启动约 30 秒进行首次检查，此后约每 5 分钟检查一次；内容未变化时不会重复轮换。
- 关于与更新：Updater-enabled 发布包可在「设置 → 关于 → 检查更新」读取 HTTPS `latest.json`；默认在启动约 60 秒后检查，此后最多每 24 小时检查一次，只提示而不静默下载或安装。用户确认后才下载精确版本、验证 Tauri updater 签名、安装并重启。生产发布链要求安装包同时具有 Authenticode 签名/时间戳和 Tauri updater 签名，缺任一项即失败；没有生产更新源和公钥的本地构建不会发起检查。

Windows 数据库位于 `%LOCALAPPDATA%\com.deskcubby.windows\deskcubby.db`，启用 WAL、外键、事务迁移与 busy timeout。日记和媒体目录不会被整份复制进应用私有目录；保存前使用 SHA-256 文件版本检测外部修改，冲突时提供“重新加载、覆盖、另存副本”。若文件被外部删除，“重新加载”会接受删除并关闭当前编辑，“覆盖”可安全重建同名文件，“另存副本”则把草稿保存为新文件。

### Windows v18 数据兼容（与 Android v20 的限制）

- Windows 0.2.0 只直接导入/导出 Android v18 JSON。Android 0.4.0 当前导出 v20，并加入 Vault 密文、小游戏存档和多设备使用时间；Windows 0.2.0 **不能直接导入 v20**，本次 Android 更新不为旧 Windows 增加兼容层。
- 导入前显示数量统计并要求确认；确认后在单个数据库事务中替换 Windows 管理的设置、小巧思、分类、日期记录和诗词，失败则完整回滚，并保留导入前恢复点。
- Android 的 `diaryTreeUri`、`mediaTreeUri`、`poetryFontUri` 等 `content://` URI 只作为不透明兼容字段保留。Windows 目录单独保存，绝不会把 URI 转换为文件路径。
- Windows 不展示的浏览器收藏、RSS、AI/API Key 和未知字段会保存在由 Windows DPAPI 加密的兼容影子中，随后导出时原样合并，避免往返一次便丢失 Android 数据。导入后的云同步非秘密元数据先继续由兼容影子持有；只有用户首次在 Windows 新建、编辑、复制或删除云配置后，Windows 才按 v18 结构覆盖该字段。WebDAV/S3 凭据、收藏夹密文和手机使用时间明细会在进入影子前及每次导出前清除。
- v18 JSON 最大 10 MiB，并执行数量、长度、枚举、重复 ID 与关联关系校验。导出的 JSON 与 Android 一样是普通数据文件；AI Key 等 Android 原字段仍可能以明文出现在导出结果中，请妥善保存。

### Windows 0.2.0 明确不包含

Windows 0.2.0 仍不实现浏览器、RSS、AI 聊天或热量估算、小游戏和步数。手机使用时间只保留旧 Android v4 文件的只读展示，不采集 Windows 或手机数据；它尚不理解 Android v20 的按设备历史。浏览器/RSS/AI 等 Android 数据继续在兼容影子中无损保留，不由 Windows 界面执行。

## Android 0.4.0 当前功能

### 界面与导航

- Jetpack Compose 界面，提供 Material、Liquid Glass 和 Organic Future 三套视觉风格，支持明暗模式、中英文和 RTL/系统安全区域。
- 三套风格共享一个主颜色和 2–5 个副颜色：主色驱动各风格的 primary，副色进入 secondary/tertiary 并在日记月份行、设置菜单等位置轮换显示；应用字号可在 80%–130% 间调整。
- 主题主/副颜色、小巧思分类颜色和重点背景色都提供 HSV 取色器、紧凑的同行滑杆标签、蜂窝色盘，也支持直接输入 #RRGGBB。
- 外观设置提供紧凑模式，收窄主页与设置列表的间距；设置主页顶部提供设置搜索，可按名称或关键词直达对应设置页。
- 首页组件可增删、排序和单独控制标题显示，并提供小巧思、日常记录、饮食图片等快捷模块；左上角默认提供 24 条简短中性问候，按日期稳定轮换并完整显示。“设置 → 子页面设置 → 主页 → 主页问候”可修改用户名、复制 `{name}` 占位符，以及增加、修改或删除中英文问候语。
- 底部导航可修改名称、图标、显示状态、顺序、默认启动页和标签显示方式；设置入口不可隐藏。
- “导航/More”聚合页可收纳不想直接放在底栏的其他主页面。底栏可见性在“设置 → 底部导航”管理，导航页收纳与描述统一在“设置 → 子页面设置 → 导航页”管理，避免重复开关。聚合页使用左右列分别按真实卡片高度排列的双列瀑布流，并可用卡片上的四点手柄直接拖动排序。
- 首次启动会提示应用包含多个可手动开关的页面，并可直接进入底部导航设置。
- 首次启动的预设刻意精简：底栏只有主页、日记、小巧思、导航和设置；主页只有“今天、每日诗词、快速输入、饮食图片、年度进度”五个模块，其余模块和页面都可在设置中随时添加。
- 设置子页使用右上角保存，并可用重置按钮把本页全部草稿恢复为默认值后再保存；存在未保存修改时返回会先弹出确认提示。

### 日记、媒体与日常记录

- 通过 Storage Access Framework 持久授权日记和媒体目录，不把 `content://` URI 转换成文件路径。
- 支持 Markdown 日记扫描、月份分组、今日日记、模板、文件名格式、源码编辑、阅读预览和自动保存。
- Markdown 中独占一行的媒体可拖动排序；SHA-256 用于检测外部修改，发生冲突时暂停覆盖并提供重新加载或强制保存。
- 日记设置可选择在导入图片时把未压缩的原图另存到系统相册的 DeskCubby 相簿（API 29+ 无需权限；API 26–28 依赖安装时授予的存储权限，失败不影响日记内的保存）。
- 日记支持软删除、恢复和永久删除；软删除会先复制到独立回收站并回读校验。
- 日常记录使用整句模板，`xx` 代表可快速选中替换的内容；编辑后的整句话可直接追加到当前日记或今日日记。
- 日记编辑页和首页都可快速打开、填写并发送日常记录。

### 饮食记录与 AI 热量估算

- 饮食分类固定为早餐、午餐、下午茶、晚餐、水果、夜宵，默认图标为 `🥪 🍱 🍹 🍜 🍊 🍤`。
- “吃历”按日期展示饮食照片，可设置图片高度上限和是否显示文字说明；点击照片可全屏放大查看（双指缩放、双击切换倍率），放大时显示热量与拍摄地点（如有）。
- 吃历可按包含首尾日期的日范围导出 PNG 长图。导出沿用当前餐别筛选、滤镜、每行图片数量与说明显示设置，生成前检查高度/像素上限，写入后回读校验。
- 吃历支持图片自动换行：可选每行 2 张、3 张或“2+3 自动”——自动模式混合每行 2/3 张，保证最后一行不留空位（4=2+2、5=3+2、7=3+2+2）。
- 吃历右上角提供餐别筛选（漏斗图标）：勾选想显示的餐别（如只勾早餐则只显示早餐照片），筛选为会话内状态。
- 照片滤镜按钮（魔棒图标）：单击开关，长按进入设置，可调整亮度、对比度、饱和度、色温和色调。滤镜只影响应用内显示，不会改写原始图片。
- 配置文字模型与图片识别模型后，可开启上传图片自动估算热量：图片模型先返回食物 JSON，再由文字模型计算总能量。
- 热量结果记录在媒体目录的 `dc-media.json`（兼容读取旧名 `deskcubby-media.json`，不再改写图片 Markdown 标题；旧版写在标题里的 `午餐-800kJ` 仍可读取）；吃历可汇总每日热量，也可批量计算未处理图片或重新计算指定日期。
- 日记设置可开启“记录照片拍摄地点”：导入图片时读取 EXIF 位置（相册图片可能需要授予媒体位置权限），经系统地理编码后与热量一起写入 `dc-media.json`。
- 日记阅读预览会在照片下方显示 `dc-media.json` 中已有的拍摄地点，不只在吃历放大查看器中显示。

### 小巧思、浏览器与其他页面

- 小巧思支持创建、更新、分类、置顶、拖动排序、复制、分享、软删除和回收站。
- 长按小巧思可标记/取消“重点”，重点条目以可自定义的背景色显示；分类颜色支持预设色板与自定义取色，有机未来的预设色板覆盖多个色相而非单一绿色系。
- 长按小巧思的“切换分类”弹窗内可直接新增分类，新分类会立即套用到该条小巧思；编辑分类时可一键导出该分类的全部小巧思（系统分享为纯文本）。
- 输入框只在点击时弹出键盘、只从键盘自身收起，列表滚动不再影响键盘；输入框最大高度可在小巧思设置中调整（超出后内部滚动）。
- 可选择小巧思单行/完整显示，以及重启后回到全部页面或上次停留的分类；小巧思页右上角、分类按钮左侧可随时即时切换“一行/完整”。
- 多标签 WebView 浏览器支持地址栏横滑切换、前进/后退、刷新、主页、页内查找、收藏、历史、上传、下载和外部打开。
- 日期记录、诗词本和每日诗词；每日诗词在线请求失败时使用最近缓存或内置内容。主页诗词详情以诗词名称为标题。
- 诗词本支持“全部 / 未分类 / 自定义分类”筛选、分类颜色、增删改、单篇改分类；“添加分类”可选择 11 个按初高中年级/教材册次组织的离线预设，共 182 篇古诗文。重复导入会在单事务中跳过已有内容。点击主页诗词模块可查看整首诗并收藏；卡片长按进入编辑、改分类或删除。诗词本设置仍支持本机字体、字号、行距、对齐、出处、引号装饰和七言自动换行。
- RSS 页面支持 RSS 2.0 与 Atom 订阅的增删改、启停和刷新；点击有效的 HTTPS 文章会进入应用内多标签浏览器阅读全文，仍可从浏览器菜单交给系统浏览器。
- 收藏夹页面提供密码保护的私密文本收藏：首次使用设置密码，条目用密码派生密钥（PBKDF2 + AES-GCM）加密后保存在 Room。新密码允许 1 个到任意多个 Unicode 码点；密码不可找回。v20 会原样备份 AES-GCM 密文、IV、盐、迭代次数和加密校验值，但绝不写入密码、明文或派生密钥，因此换设备后输入原密码即可解锁。卡片不显示日期；有备注时直接显示备注，无备注时省略该行。普通正文单击复制，安全 HTTP(S) 链接单击用系统浏览器打开，长按进入含复制/编辑/删除的界面，右侧四点手柄可拖动排序。
- 小游戏页面内置相互独立存档的 4×4、5×5、6×6 三种 2048，以及贪吃蛇和俄罗斯方块，各自记录最高分。2048 页面按 [2048.org](https://www.2048.org/) 的布局、固定色板和时序重做：移动 100 ms，新块/合并在移动后用 200 ms appear/pop，计分飘字 600 ms；系统关闭动画时直接显示结果。每个有效移动都会加入随存档保存的完整撤回栈，可连续撤回直到本局开局状态。

### 手机使用时间与步数

- 新增“手机使用时间”主页面，经 Android“使用情况访问权限”读取应用进入/退出前台及熄屏、锁屏事件，在本地午夜切分真实使用区间；每次刷新会补采系统仍可访问且事件边界完整的历史，并日结总时长及每个应用的时长。0.3.7 会重建仍可验证的近期历史，修正部分厂商把同一按日汇总重复返回而造成连续多天数值完全相同的问题。应用筛选优先读取系统应用标签和图标（例如把 `aweme` 显示为抖音），并按当前范围用时降序排列。
- “步数记录”优先从已授权的 Health Connect 只读聚合每日步数；没有连接 Health Connect 时可请求活动识别权限，改用手机的 `TYPE_STEP_COUNTER` 系统计步传感器。传感器首次采样只建立基线，此后按累计值差额记录，跨重启或跨日不会猜测缺失步数；设备不支持、没有数据、未授权或读取失败时不会伪造 0 步。
- 两页都提供开始日期、已统计天数、总计、日均以及 7/30/90 天或全部范围。三种统计图以同一行的纯图标切换，点击柱、折线点或方块会在该点上方悬浮显示日期和值，不会弹出页面；柱状图按高低渐变，柱状图和曲线在图内左上/左下叠加最高/最低纵轴标注。
- 手机使用时间使用 `H`/`M` 紧凑显示时长，不再显示“总览”标题；总览卡新增单日最高和过去 7 天平均。统计图位于范围与图表类型按钮上方，加载权限与历史期间显示加载按钮，不再短暂闪现“统计未开启”；“本机私有统计”说明卡已移除。
- 每次安装会生成不依赖硬件标识的随机稳定设备 ID，并使用可编辑的设备名称。手机使用时间页可选择“所有设备”或任一设备；本机只采集自己的系统数据，其他设备历史来自 v20 导入或云同步。“所有设备”按日期和应用相加，并显示短设备 ID 以区分同名设备。
- Android 0.4.0 已删除旧的「导出给 Windows」规范 v4 手动导出界面。云配置中勾选“多设备使用时间”后，每台设备会使用 `usage/v1/{deviceId}.json` 独立对象双向同步，并按同一设备/日期合并；FINAL 日优先于 OPEN 日，同状态取较新的采集结果，避免 A、B 手机覆盖彼此。
- 两个功能默认关闭，开关分别位于“设置 → 子页面设置 → 手机使用时间”和“设置 → 子页面设置 → 步数记录”。开启后仍需进入对应系统授权页；启用任一统计时，WorkManager 每 6 小时尝试补采。
- 当天记录保持可刷新；过去日期只有完整读取成功才会日结，日结后不再重复计算。关闭开关停止后续采集但保留本机历史。
- 本机历史仍保存在应用私有且排除 Android 系统备份的 `usage-statistics.json`；其他设备缓存、随机设备 ID 和步数历史也排除系统备份/设备迁移，防止克隆设备身份。手机使用时间会进入 v20 JSON，并可通过用户明确勾选的 WebDAV/S3“多设备使用时间”同步；步数历史和系统授权仍不进入 JSON 或云同步。

### AI 配置与聊天

- AI 配置库支持多套文字/图片模型；每套配置包含名称、类型、API 地址、模型名称、API Key、温度、系统提示词和 HTTP 许可。
- 配置列表点击进入详情，长按可复制或删除；AI 聊天和日记热量估算可分别选择要使用的配置。
- 配置详情可预览实际请求 JSON 结构。文字消息、图片提示词和图片数据以占位符显示；API Key 位于 Authorization 请求头，不属于 JSON 预览。
- AI 聊天使用 OpenAI-compatible `chat/completions` 非流式接口；会话和消息保存在 Room，可查看历史、继续聊天、新建、改名和删除，并根据首条消息在本地自动生成标题。
- 聊天可通过系统文件选择器附带一张图片。服务商和所选模型需要兼容 `image_url` data URL 形式的多模态消息。
- 聊天输入框左侧使用一个“+”菜单统一提供图片、日记上下文和小巧思上下文，发送按钮位于输入框内部。日记可逐项选择；小巧思既可逐条选择，也可一键导入或取消整个分类。日期记录和诗词旧上下文快照继续兼容但不再占用新建入口。每次最多 50 项、单项 64 KiB、总计 256 KiB；超限会原子拒绝而不会改变原选择或静默截断。
- 所选内容在用户点发送前才读取并冻结，快照仅包含来源、标题、日期/署名和正文，不发送 Room ID、`content://` URI、文件哈希或凭据。快照作为不可信参考数据随会话保存在本机，并随该会话后续请求继续发往所选模型服务。
- 等待回复时会显示“正在思考”；若服务端明确返回 `reasoning_content`、`reasoning`、`analysis` 或 `<think>` 内容，可在折叠面板中查看并随会话保存。应用不会展示模型未返回的内部推理。

> [!WARNING]
> 按当前产品设计，AI API Key 会以**明文**随 AI 配置写入应用设置，也会进入 DeskCubby v20 JSON/自动备份。v20 还包含多设备使用时间、小游戏存档和 Vault 密文/校验元数据；请勿把备份文件、应用数据目录、同步到云端的应用 JSON 或含 Key 的截图放入公开或共享位置。

### 关于与更新

- 设置 → 关于：显示版本号、GitHub 仓库入口，并可从 GitHub Release 手动检查更新。检测到含可信 DeskCubby APK 的新版本后显示“下载并安装”按钮；应用会下载到私有缓存，校验包名、版本和签名，再处理“允许安装未知应用”授权并调起系统安装器。
- 关于页可把桌面显示名称切换为 “Desk Cubby” 或 “桌洞”（通过启动器别名实现，部分启动器需要片刻刷新）。
- 关于页可在经典图标、魔法书图标与用户提供的桌洞图标之间即时切换；图标与中英文桌面名称可自由组合。
- 关于页提供“应用教学”入口，跳转到仓库内的 [TUTORIAL.md](TUTORIAL.md)，逐页面说明每个按钮和手势的用法。

### 应用数据

- 设置主页使用简短入口“设置 → 应用数据”；其中统一提供本机 JSON 备份，并可从“云端同步”卡片进入 WebDAV/S3 配置。
- 设置中可维护多个 WebDAV 或 S3 兼容服务配置，分别启停，并选择日记、媒体、应用 JSON、多设备使用时间以及“仅上传”或“双向”同步。
- 支持手动立即同步；开启全局开关后会注册需要联网的 6 小时周期任务。同步日记和媒体时仍只通过 SAF 访问用户授权目录。
- 同步使用 SHA-256、远端 manifest、强 ETag 条件请求和冲突副本保护并发修改。当前不会传播删除：某一侧缺失的文件会按同步方向重新上传、下载或跳过。
- S3 接入点可省略协议：SSL/TLS 默认开启并自动补 `https://`，仅可信内网可关闭后使用 HTTP。S3 支持默认开启的 Path-Style（`/Bucket/目录`）与 Bucket 子域名寻址，可用于 CSTCloud 等兼容端点；失败状态会显示稳定的客户端代码、HTTP 状态和安全提取的 S3 服务代码。
- WebDAV 密码仍由 Android Keystore 加密。按产品要求，S3 Access Key ID、Secret Access Key、Session Token 以明文写入应用私有 DataStore，编辑配置时完整回显；旧版 Keystore 中的 S3 值会在首次编辑保存时迁移。S3 凭据仍不进入日志或 DeskCubby JSON 备份。
- 选择“应用 JSON”会把 v20 备份以 `json/dc.json` 上传到所选服务，其中包含明文 AI API Key、多设备使用时间、小游戏存档和 Vault 密文/校验元数据。另选“多设备使用时间”会使用每设备独立对象自动合并，无需恢复整份应用 JSON。HTTPS 保护传输过程，但不提供远端对象的端到端加密。
- 从云端下载的应用 JSON 只会校验并暂存在应用私有目录；后台任务不会直接覆盖本机设置或 Room，必须由用户在同步设置中确认后才会恢复。

### 备份

- 支持选择自动备份目录、立即保存、手动导入和导出单个 JSON 文件；自动备份使用 `dc.json`，手动导出默认使用 `dc-backup-日期.json`，并继续识别旧版 `DeskCubby*.json`。“设置 → 应用数据 → 查看整体 JSON”可查看当前完整备份快照。
- 当前备份格式为 v20，最大 64 MiB。在 v19 内容基础上新增：Vault 的 AES-GCM 密文、IV、盐、迭代次数、加密密码校验值及换密 generation；2048（含无限撤回栈）、贪吃蛇、俄罗斯方块的存档和最高分；按稳定随机设备 ID 分组的手机使用时间历史。
- Vault 密码/明文/派生密钥、WebDAV 密码、S3 用户名/Key/Session Token、AI 对话历史及冻结上下文、日记正文、媒体文件、步数历史和系统权限不进入 JSON。v20 导入后 Vault 保持锁定；小游戏同 ID 最高分取较大值，较新存档胜出；使用时间按设备和日期合并。本机设备 ID 不会被导入文件覆盖。
- v20 仍包含两个统计功能的普通开关字段，但导入时会强制关闭手机使用时间和步数统计，云同步也保持关闭。Android 继续支持 v1–v19；旧备份不拥有 Vault、小游戏或使用时间字段，因此导入旧版时保留本机这些数据。
- 导入 v11 及更早备份时，仅为配置 ID 与 API 地址都一致的 AI 配置保留本机已有 Key。

### 本地数据库

- Room 数据库当前版本为 9，保留 1→2 至 8→9 的全部显式迁移，不使用 destructive migration；8→9 原样迁移旧诗词并增加诗词分类外键。
- v6 新增 AI 会话和消息表；删除会话会级联删除其消息。AI 历史保存在本机，但不属于 JSON 备份。
- v7 为小巧思增加重点标记列，并新增收藏夹密文表（vault_items）与小游戏存档表（game_states）；v20 开始把后两者的密文/存档纳入显式 JSON 备份，但数据库结构不变。
- v8 为收藏夹条目增加持久排序字段，升级时保留原显示顺序。

## Android 构建环境

- Android SDK 36
- JDK 17 或更高版本（可以使用 Android Studio 自带的 JDK）

使用 Android Studio 时请打开仓库内的 `android/` 目录。项目 Wrapper 在未设置 `GRADLE_USER_HOME` 时，会默认把 Gradle 分发和依赖缓存放在 `android/.gradle-user-home`，避免占用 C 盘。

以下命令均从仓库根目录运行。

### Debug APK

```powershell
.\android\gradlew.bat --project-dir .\android :app:assembleDebug
```

输出：`android/app/build/outputs/apk/debug/DeskCubby.apk`

### Release 签名

首次打包前运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\android\scripts\generate-release-keystore.ps1
```

脚本会生成：

- `android/release/DeskCubby-release.jks`：应用的长期 Release 签名密钥。
- `android/keystore.properties`：本机签名参数和随机强密码。

这两个文件均已被 `.gitignore` 排除，不会进入 Git。请把它们一起加密备份到可靠位置；Android 后续版本必须继续使用同一把签名密钥，丢失或更换密钥会导致已安装版本无法正常升级。

为避免目录迁移破坏已有发布能力，构建仍兼容迁移前保存在仓库根目录的 `keystore.properties` 与其相对路径；新配置统一放在 `android/` 内即可。如果签名脚本检测到根目录已有旧签名文件，它会拒绝生成替代密钥。不要为了整理目录复制、替换或重新生成已有长期签名密钥。

然后构建已签名 Release APK：

```powershell
.\android\gradlew.bat --project-dir .\android :app:assembleRelease
```

输出：`android/app/build/outputs/apk/release/DeskCubby.apk`

如需手动配置，可复制 `android/keystore.properties.example` 为 `android/keystore.properties`。CI 也可以改用以下环境变量，避免把密码写入文件：

- `DESKCUBBY_RELEASE_STORE_FILE`
- `DESKCUBBY_RELEASE_STORE_PASSWORD`
- `DESKCUBBY_RELEASE_KEY_ALIAS`
- `DESKCUBBY_RELEASE_KEY_PASSWORD`

Release 任务在签名配置缺失或不完整时会直接失败，不会误生成未签名安装包。

### 验证

```powershell
.\android\gradlew.bat --project-dir .\android :app:compileDebugKotlin --offline
.\android\gradlew.bat --project-dir .\android :app:testDebugUnitTest --offline
.\android\gradlew.bat --project-dir .\android :app:compileDebugAndroidTestKotlin --offline
.\android\gradlew.bat --project-dir .\android :app:assembleDebug :app:lintDebug --offline
```

0.4.0（2026-07-30）把 Android 应用备份升级为 v20：原样保存 Vault 密文/盐/校验元数据，加入五种小游戏的存档与最高分，并加入按稳定随机设备 ID 分组的手机使用时间。手机使用时间页新增设备切换、设备名编辑和“所有设备”汇总；WebDAV/S3 新增每设备独立的使用时间同步对象并按日期安全合并。旧的手动规范 v4 导出界面已删除；Android 继续导入 v1–v19，但 Windows 0.2.0 暂不兼容 v20。应用 versionCode 为 12；Room 仍为 v9。

0.3.8（2026-07-30）按 2048.org 重做三种 2048 的界面和动画并加入无限撤回；修复 Android S3 的协议、SSL/TLS、Path-Style/虚拟主机寻址、CSTCloud 兼容、凭据回显和错误代码；诗词本加入分类及 11 类 182 篇初高中古诗文预设；调换应用数据页的 JSON 与备份说明顺序。应用 versionCode 为 11；备份升级为 v19，Room 升级为 v9。

0.3.7（2026-07-28）改用前后台事件重建手机使用时间并按本地午夜切分，升级后会修正系统仍保留事件范围内的重复日高值；4×4/5×5/6×6 三种 2048 均新增可随存档保留的单步撤回，游戏结束时也可撤回继续。应用 versionCode 为 10；应用备份仍为 v18，Room 仍为 v8。

0.3.6（2026-07-28）新增七言诗自动换行、步数传感器回退、设置页一键重置和蜂窝色盘；重做手机使用时间总览、加载状态与图内交互提示；2048 拆分为 4×4/5×5/6×6 并增强色阶和动画；设置入口缩短为“应用数据”，AI 聊天更换图标。备份格式升级为 v18，应用 versionCode 为 9。

0.3.5（2026-07-28）新增桌洞启动图标、诗词本排版设置与长按操作；修正使用时间应用名称/图标解析并让三种统计图可点选日期；收藏夹改为备注紧凑显示、长按复制/编辑/删除和拖动排序；未来保存的 JSON 文件名统一缩短为 `dc` 前缀并兼容旧名。备份格式升级为 v17，Room 升级为 v8。

本次发布产物：

- Release APK：`android/app/build/outputs/apk/release/DeskCubby.apk`

如需验证已签名的 Release APK：

```powershell
apksigner verify --verbose android\app\build\outputs\apk\release\DeskCubby.apk
```

## Windows 构建环境

Windows 客户端需要 Windows 10/11 x64、Node.js 20+、pnpm、Rust，以及带“使用 C++ 的桌面开发”工作负载的 Visual Studio 2022 Build Tools 和 WebView2 Runtime。仓库的 `windows/rust-toolchain.toml` 固定使用项目级 `stable-x86_64-pc-windows-msvc`，不会依赖机器默认的 GNU target。

以下命令从仓库根目录运行：

```powershell
cd .\windows
pnpm install --frozen-lockfile

# 浏览器中的 Vite 开发界面
pnpm dev

# 带 Rust 后端的桌面开发模式
pnpm tauri dev
```

提交 Windows 改动前执行：

```powershell
cd .\windows
pnpm lint
pnpm typecheck
pnpm test
cargo fmt --manifest-path .\src-tauri\Cargo.toml --check
cargo clippy --manifest-path .\src-tauri\Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path .\src-tauri\Cargo.toml
```

普通本地构建可生成 NSIS，并从同一次 Release 构建复制便携 EXE、生成 SHA-256：

```powershell
cd .\windows
pnpm package:windows
pnpm package:portable
```

典型输出位置：

- 原始 Release EXE：`windows/src-tauri/target/release/deskcubby-windows.exe`（显式 target 构建时位于 `target/x86_64-pc-windows-msvc/release/`）
- NSIS 安装包：`windows/src-tauri/target/release/bundle/nsis/`
- 便携测试文件与校验值：`windows/artifacts/DeskCubby-0.2.0-windows-x64-portable.exe` 和同名 `.sha256`

也可显式构建一个仅供本地测试、禁止发布的未签名包：

```powershell
cd .\windows
.\scripts\build-release.ps1 -Mode AllowUnsignedTestBuild
```

生产发布必须使用受保护环境中的长期 Tauri updater 私钥和一套 Authenticode 身份（PFX 或硬件/云签名命令）：

```powershell
cd .\windows
.\scripts\build-release.ps1 -Mode SignedRelease -ReleaseTag windows-v0.2.0
```

成功的 `SignedRelease` 会产生：

- `windows/artifacts/DeskCubby-0.2.0-windows-x64-setup.exe`
- `windows/artifacts/DeskCubby-0.2.0-windows-x64-portable.exe`
- 安装包的 `.sig`、`latest.json` 与 `SHA256SUMS.txt`

这是两套互不替代的签名：Authenticode 签名和可信时间戳用于 Windows 发布者身份/SmartScreen；Tauri updater 的 minisign 签名用于应用内下载验证。`.github/workflows/windows-release.yml` 在 `windows-v*` tag 上执行 `SignedRelease`，校验签名与清单后只创建 **draft** GitHub Release，必须人工复核后才能发布；复核通过后再把签名清单提升到独立的 `windows-stable` 通道，避免与同仓库 Android Release 争用全局 `latest`。

当前仓库及这台开发机**没有**生产 updater 私钥与 Authenticode 证书，检入的基础配置也故意不含 updater 公钥或端点。因此普通本地 0.2.0 构建仍是未签名、更新未配置的测试产物，可能显示“未知发布者”；它不会自动检查更新。`SignedRelease` 或 CI 缺少任一生产秘密、证书、时间戳、安装包 `.sig` 时会直接失败，绝不会降级发布未签名包。Windows 0.1.0 没有 updater 插件，已有用户必须手动安装首个经过双重签名且启用 updater 的 0.2.0（或后续）版本，之后才可使用应用内更新。

## 使用边界

- Windows 0.2.0 与 Android 旧版共用 v18 结构化 JSON，但不会直接打开或共享 Android Room 数据库。Android 0.4.0 当前导出 v20，Windows 0.2.0 尚不能直接导入；日记和媒体真实文件仍应通过用户选择的普通目录互操作。
- Windows 只保证直接导入 v18。Windows 重新导出会合并 DPAPI 加密影子中的未实现模块与未知字段，但这一机制不是编辑这些字段的界面。
- Windows 0.2.0 没有浏览器、RSS、AI/热量估算、小游戏或步数；其手机使用时间页只读取旧 Android v4 文件，不采集 Windows 使用时间，也尚未兼容 Android 0.4.0 的 v20/按设备云同步。
- 编辑器采用“源码编辑 + 阅读预览”，不会把 CommonMark AST 重新序列化，因此能保留未知的 Obsidian 语法；预览只渲染基础 CommonMark。
- 源码中的媒体拖动只识别独占一行的 Markdown 图片语法。
- 图片 Markdown 链接只写媒体文件名；建议在 Obsidian 中把 DeskCubby 的媒体目录配置为附件目录。
- 天气组件目前只显示离线缓存占位；每日诗词会优先请求在线 API，失败时回退到最近缓存或内置内容。
- RSS 面向公网 HTTPS Feed；文章列表当前不跨应用重启保存。
- AI 服务商需要兼容非流式 OpenAI `chat/completions` 消息格式；聊天图片和图片识别通过 `image_url` data URL 发送，单张图片限制为 8 MiB。思考面板只显示服务端实际返回的 reasoning。
- 导入 AI 上下文后，只有在用户发送消息时冻结内容才会离开设备并发往当前模型服务；该快照会留在本机会话中并随之后的会话请求继续发送。需要停止复用时请新建对话。
- AI 热量结果仅为估算值，不应视为医学或营养测量结果。
- 手机使用时间由 Android UsageEvents 的前后台、熄屏和锁屏事件重建，按当前时区的本地午夜切分，口径仍可能与设备厂商的“屏幕时间”不同；应用会补采系统仍保留且事件流完整覆盖的自然日，但 Android/厂商已经清理的数据或最老的截断日无法恢复。若当天事件不可用，应用只对当天使用单日汇总回退，不再把一个厂商汇总复制到多个历史日期。
- 步数页优先读取 Health Connect 已有数据；未连接时只能在具备 `TYPE_STEP_COUNTER` 的手机上从首次采样开始按差额记录，不能还原授权前、应用未采样期间、跨重启或跨日的历史。Android 13 及以下需要安装/更新 Health Connect 时会优先打开 Google Play，并在商店不可用时回退到 HTTPS 页面。
- 吃历长图受 Android Bitmap 内存、图像高度和总像素安全上限约束；时间范围过大时需缩短范围并分批导出。
- WebDAV 远端目录需要预先存在，WebDAV/S3 服务必须可靠支持强 ETag 与条件 GET/PUT；不满足这些条件时同步会安全失败。
- 云端同步默认限制单对象 64 MiB、单次传输 512 MiB、10,000 个对象和 10 分钟总时长，不适合作为不受限制的整盘镜像工具。
- 云端应用 JSON 的下载只产生待确认的暂存副本；恢复 JSON 不会替换日记正文、媒体文件或 AI 对话历史。
- Windows 收藏夹只属于当前 Windows 数据库，不进 v18、云同步或恢复点；密码无法找回。Windows 手机使用时间的快照/只读链接也不进 v18 或默认云同步。
- 自动更新只在生产发布包内嵌可信 HTTPS 更新源和 updater 公钥后生效；自动检查只提示，下载安装始终要求用户确认。本地未配置构建必须显示“更新未配置”。
- 文件版本历史和更完整的冲突“另存副本”流程尚未加入。
- 部分云盘文档提供方可能拒绝重命名；这时软删除会失败并保留原文件，不会直接永久删除。

## 开源许可

DeskCubby 代码使用 [MIT License](LICENSE) 开源。Android 内置诗词预设的来源与单独许可见
[`poetry_presets_NOTICES.txt`](android/app/src/main/assets/poetry_presets_NOTICES.txt)；其中由初中数据
派生的预设编排按 CC BY-SA 4.0 提供，不属于仓库代码的 MIT 授权范围。
