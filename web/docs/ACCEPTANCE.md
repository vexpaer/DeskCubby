# DeskCubby Web ↔ Android 对照验收清单

审查者（人或子代理）逐项核对 Web 实现是否复刻 Android 行为。参考：`README_for_ai.md` 对应章节、`android/app/src/main/java/com/deskcubby/app/` 源码、`overview.md`。
标记：✅ 一致 / ⚠️ 部分一致(说明差距) / ❌ 缺失。

## 0. Android 0.23.3–0.23.5 回归门

- [ ] 触屏/混合输入设备点按普通按钮后背景不会常驻，点其他按钮后旧按钮也不残留；分段按钮始终只有 `aria-checked=true` 的一项着色，键盘 `:focus-visible` 清晰可见
- [ ] 已安装旧 PWA 的浏览器重新打开（仍显示旧界面时再刷新一次）后会取得联网优先的新 index；新 Service Worker 接管时自动刷新并清理旧版本缓存，不会继续运行旧 CSS
- [ ] MATERIAL / LIQUID_GLASS / ORGANIC_FUTURE 切换后 `data-style` 使用 CSS kebab-case 值，三套表面与形状明显不同
- [ ] 首次语言页、登录页、About、manifest 与安装后的 PWA 图标均为新的 DeskCubby Logo
- [ ] 结构化首页写自然日期、日记编辑器写当前文档；跨日界线只改变“今日日记”的目标日期
- [ ] Agent 最多 5 个附件；任一暂存 ID 失效时整组拒绝且其余附件不被消费；附件消息和 Run 可经 v34 往返
- [ ] Agent SSE 使用 `started` / `tool_event`，持久状态只使用 RUNNING/SUCCEEDED/FAILED/CANCELED；关闭流不取消服务器 Run
- [ ] Agent 在发送前生成 UUID Run ID；首个 `started` 前点中止也能取消服务端任务；离开再进入会恢复 RUNNING、工具事件和待审批卡，完成后自动加载最终 assistant 消息
- [ ] WebDAV/S3 对 manifest 使用强条件版本；S3 非空 remotePath 是以 `/` 结尾的集合 URL
- [ ] 思想/诗词分类作为父依赖隐式同步；阅读偏好、进度、Agent 会话走 record-level adapters
- [ ] 旧的分类单选云配置在读取时迁移为小巧思/诗词正文选择，列表与编辑页都不再计算或显示隐式分类
- [ ] Usage 往返保留 platform/zoneId/OPEN|FINAL/collectedAt/tracking/backfill；逐日合并 FINAL 优先，其次较新 collectedAt，元数据独立 LWW
- [ ] 阅读背景/字号/间距/进度开关/章节模式跨设备同步；自定义正则和标题长度仅留在当前浏览器
- [ ] 开启“显示书架进度”后 TXT/PDF 卡片显示与指纹账本一致的百分比；CUSTOM 模式可用合法整行正则生成目录

## 1. 壳与导航
- [ ] NavItemId 19 项全部有页面且 route 名一致（home/desk/diary/notes/blog/thought/date_records/poetry_book/rss/ai_chat/vault/reader/games/statistics/usage_statistics/step_statistics/desktop_widgets/more/settings）
- [ ] 底部导航可见项 = navItems.visible + MORE(有收纳页时) + SETTINGS；桌面左栏为响应式适配
- [ ] 导航页(more)：列数设置生效、卡片底色/按钮底色自定义、描述显隐、排序
- [ ] 逐页教学蒙版：tutorialModeEnabled 开启时每页首次显示一次，确认后不再显示（存 settings.tutorialAcknowledgedPages）
- [ ] 三套视觉风格 MATERIAL/LIQUID_GLASS/ORGANIC_FUTURE + CUSTOM（8 角色浅深两套、圆角/边框/阴影/不透明度/间距/动效边界与 AppModels.kt 相同）
- [ ] 五语言 tr()：简中/繁中/英/韩/日，翻译表来自 AppTranslations.kt 自动转换
- [ ] 深浅色 SYSTEM/LIGHT/DARK；字号缩放 0.8–1.3；紧凑模式；全局背景图+透明度+模糊
- [ ] PWA manifest + SW；手机/平板/桌面响应式；safe-area

## 2. 认证与安全
- [ ] 默认无密码直接访问；设置内可开启访问密码
- [ ] 开启后所有页面/API/图片/附件/Agent 接口 401 拦截；登录页可解锁
- [ ] 密码仅存 PBKDF2 哈希；Session 为 HttpOnly Cookie
- [ ] 兼容 Caddy/Nginx 反代（X-Forwarded-*），设置主页不显示重复的公网部署横幅
- [ ] AI 配置详情按 Android 规则完整显示明文 API Key；WebDAV/S3 凭据任何 GET 均不回传，日志/错误/请求预览不得含 Key 或云凭据
- [ ] 错误响应不含正文/绝对路径/密钥

## 3. 首页模块（README §1）
- [ ] today/poem/calendar/weather/streak/month_diaries/total_words/recent_diary/recent_thought/date_records/quick_input/daily_records/meal_photos/random_diary/year_progress/website/notes/game_shortcuts/record_overview/cloud_sync_now/cloud_sync_force 全部实现或明确降级说明
- [ ] homeWidgets 顺序可编辑（布局更改模式）；homeWidgetBordersEnabled 生效
- [ ] 问候语模板 {name} 替换 userName；24 条默认双语问候
- [ ] meal_photos：六餐别按钮默认 emoji，可在主页设置切换图标/文字并自定义符号；上传→写入今日日记→成功后才恢复按钮、409 冲突处理
- [ ] quick_input 成功落盘后清空输入

## 4. Desk（README 桌面节）
- [ ] 日期标题、日记纸张对象（seed 旋转）、Idea/Photo 极简呈现、Today Traces 排版层级
- [ ] ✦ AI 浮层入口 → ai_chat；底部 + Quick Capture；空状态留白

## 5. 日记（README §2–3）
- [ ] 个人电脑模式可分别选择日记/媒体真实文件夹；仅直连 loopback 可修改，服务器/反代拒绝；切换不迁移旧内容且日记立即重建索引
- [ ] 列表月份分组、搜索、FAB 创建今日、条目菜单（打开/重命名/删除→回收站）
- [ ] 回收站列表/恢复/彻底删除
- [ ] 编辑器源码/预览双模式；保存 SHA-256 冲突 409 → 重新加载/覆盖/另存副本
- [ ] 未保存返回确认；底部工具栏插入图片/格式快捷键；字数统计
- [ ] dc-media.json v2 读写保真（未知字段保留、损坏不覆盖）

## 6. 吃历（README §4）
- [ ] 日期分组照片墙 TWO/THREE/SMART 行逻辑；caption/maxHeight 设置生效
- [ ] 热量详情对话框；筛选餐别；导出 PNG 长图；全屏缩放查看
- [ ] 热量估算：图片模型并行≤3 + 文本模型汇总、进度卡展开、kJ 写回 dc-media.json
- [ ] 滤镜页五滑杆实时预览、保存到设置、恢复默认

## 7. 笔记（README 笔记页面节）
- [ ] 文件夹树、新建/重命名/删除文件夹与笔记、自动保存+手动保存
- [ ] SHA-256 外部修改冲突三选一；![[Wiki]] 链接；媒体上传相对链接；搜索

## 8. 小巧思（README §6）
- [ ] 分类抽屉、一行/完整切换、置顶/高亮/移动分类/编辑/删除
- [ ] 回收站恢复/彻底删除；底部输入区高度限制；新条目精确定位
- [ ] 分类 CRUD + 颜色 + NOCASE 重名校验 + 排序

## 9. 浏览器（README §7）/ RSS（§10）
- [ ] 多标签、地址栏、收藏夹/历史、主页设置、桌面模式开关
- [ ] RSS 订阅管理、刷新、文章列表、摘要开关、点击进入浏览器阅读

## 10. 日期记录（§8）/ 诗词本（§9）
- [ ] 日期记录 CRUD、今天/N 天表述、emoji 图标
- [ ] 诗词分类筛选/颜色/CRUD/删除二选一、排序首位稳定 ID、预设导入（11 类 182 篇）
- [ ] 每日诗词轮换+去重+内置兜底

## 11. AI 聊天与 Agent（README §11）
- [ ] 会话列表/新建/删除；模型选择（aiConfigs）；SSE 流式回复+reasoning 折叠
- [ ] 附件≤5 图片/文档、文本提取；Markdown 渲染；字号/回复框宽度设置生效
- [ ] Agent 四方块：9 数据源授权、需要批准/全自动、工具执行过程展开、中止
- [ ] Mutation 审批弹窗（参数摘要/批准/拒绝）；Review 列表 before/after + 真实 Undo（内容不匹配拒绝）
- [ ] 页面重进恢复运行态/工具账本/待审批，结束后回载最终消息；首事件前中止不会留下后台 Run
- [ ] Token 统计（input/output/cached/cacheRate/reasoning，缓存率=cached/对应 input）；12 轮保护；未授权来源拒绝
- [ ] system prompt 包含 Android 十条 hard rules、已授权来源 metadata 目录、全局 agentPrompt 后接模型 systemPrompt；同步全局设置不清空本机 AI Key
- [ ] 热量估算配置（vision/text 提示词可编辑+恢复默认）

## 12. 收藏夹（§12）
- [ ] 未设密码 setup / 锁定 / 解锁三态；PBKDF2 120k + AES-GCM 与 Android 兼容
- [ ] 条目 CRUD/复制/排序/行高设置；改密整体重加密；锁定清密钥

## 13. 阅读（阅读页面节）
- [ ] 书架导入 TXT/PDF、重命名、删除
- [ ] TXT 分页阅读+进度保存（指纹 LWW）；PDF 连续滚动+缩放+搜索+目录
- [ ] 六种阅读背景、字号/行距/段距、书架进度开关和章节模式使用服务端权威偏好；浏览器缓存仅作离线/首次迁移
- [ ] 自定义整行正则、章节标题最长字符数为设备本地设置；无效正则禁止保存且 CUSTOM/SMART_AND_CUSTOM 目录有效
- [ ] engagement 时长记录

## 14. 小游戏（§13）
- [ ] 2048×3（动画速度设置、moveAttempts/effectiveMoves 分离、存档恢复）
- [ ] 贪吃蛇、俄罗斯方块、自定义扫雷（首击安全/旗子/计时）
- [ ] 蜘蛛纸牌单花色横屏、围棋 9/13/19（提子/禁自杀/简单劫/双停着结束、不判地域）
- [ ] 最高分/存档/特色统计持久化并进备份

## 15. 统计中心 / 使用时间 / 健康
- [ ] statshub 各卡片与图表；结构化记录统计（字段统计+派生指标）
- [ ] usage 导入 usage/v1 或 v20–v28 usageDevices、设备分组、7/30/90 天、总览/图表/App 列表
- [ ] usage 设备 ID 为规范 UUID；旧 Web ID 稳定迁移且碰撞时保留不同日期历史，FINAL 日不会被较新的 OPEN 日覆盖
- [ ] health 导入步数 JSON、三指标总览/图表/明细、状态说明卡在底部

## 16. 结构化记录（README 结构化记录节）
- [ ] `<!--dc:f_*-->` 注释写入/解析；日界线 05:00 默认；字段管理；重建索引
- [ ] `.deskcubby/settings.json|fields.json|records.json|statistics.json` 格式对齐

## 17. 小卡片设计器
- [ ] desktopWidgetConfigs CRUD/复制；1–6 格、颜色/透明度/文字缩放/圆角/内容类型/应用模块白名单/usage 范围 7/30/90
- [ ] legacy cloud_sync_now/force 归一 cloud_sync

## 18. 设置（README §17.1–17.17 全部小节逐项）
- [ ] 设置首页空搜索时严格只有五个一级分类；进入「子页面设置」后再显示主页、日记与媒体、浏览器、小巧思、收藏夹、诗词、RSS、AI、导航页、使用时间、健康；用户名/问候归主页、页面教学归关于、云同步归应用数据
- [ ] 每个子页本地草稿+右上角保存+dirty 返回确认+恢复默认只改草稿
- [ ] 所有字段名/枚举/上下限与 AppModels.kt 一致（抽查 10 项以上）
- [ ] 备份导出 v34 / 导入 v1–v34 预览+确认+事务替换+失败回滚
- [ ] 云同步 WebDAV/S3 配置脱敏、立即/强制上传/强制下载、结果计数持久化、撤回一次
- [ ] 删除云配置先删除凭据再保存设置；设置保存失败时凭据原样恢复，不留下“配置仍在但凭据丢失”状态
- [ ] Android manifest + immutable blobs + record-level adapters；manifest 发布与 blob 读取均执行条件版本校验
- [ ] 应用数据占用统计；自动备份

## 18.1 个人电脑安装
- [ ] `./web/install.sh` 用户级安装不复制仓库运行数据、不覆盖既有安装数据；依赖/构建失败时不切换 current 版本
- [ ] 安装后 `deskcubby` 默认在 `127.0.0.1:8787` 启动并打开浏览器；正常 INFO/HTTP 200 访问日志静默、错误仍输出；`--no-browser` 可用，重复运行可识别已启动实例

## 19. 数据保真（跨端迁移）
- [ ] SQLite 表名/列名 = Room Entities；时间 epoch millis
- [ ] v34 导出可在 Android 导入（人工抽查 JSON 结构 vs BackupJsonCodec.kt）
- [ ] dc-media.json / .deskcubby / reading progress 格式抽查
