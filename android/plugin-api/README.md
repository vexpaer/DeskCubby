# DeskCubby Android Plugin API

这一目录是 Android 端的内部扩展架构，不是动态下载、安装第三方 APK 的插件市场。它的目标是让后续新增或大幅改造功能先依赖稳定 API，而不是直接耦合现有页面、Repository 或持久化细节。Plugin API v2 已成为 Android Agent 的业务能力边界；其他既有 Screen、ViewModel、导航和数据调用链没有迁移。

## 模块与数据流

```text
未来插件模块
  └─ Plugin.onLoad(PluginContext)
       ├─ DiaryAPI ── DiaryApiAdapter ── DiaryFileRepository
       ├─ VaultAPI ── VaultApiAdapter ── NotesRepository
       ├─ MediaAPI ── MediaApiAdapter ── DiaryFileRepository
       ├─ SyncAPI  ── SyncApiAdapter  ── AppCloudSyncService
       ├─ AIAPI    ── AiApiAdapter    ── OpenAI-compatible 模型边界
       ├─ DeskCubbyDataAPI ── DeskCubbyDataApiAdapter ── 稳定业务 API/Repository
       ├─ FileAPI  ── FileApiAdapter  ── SAF 授权的日记/笔记根
       ├─ AppAPI   ── AppApiAdapter   ── 非敏感设置白名单/应用状态
       ├─ UIAPI    ── PluginUiRegistry（当前导航尚不消费）
       └─ StorageAPI ── 插件 ID 隔离的应用私有 SharedPreferences

现有页面 ── 原 ViewModel ── 原 Repository/Service（保持不变）
Android Agent ── Agent Runtime / Tool Registry ── AI/Data/File/App API
```

- `plugin-api/core`：独立 Android library，保存 `Plugin`、`PluginContext`、`PluginManager`、十类 API 契约、跨边界 DTO 和 Compose UI contribution 类型；当前 `PLUGIN_API_VERSION` 为 2。
- `app/plugin/adapter`：把 API 契约委托给现有安全边界。插件拿到的日记/笔记标识仍是不透明 `content://` URI，不会转换为文件路径。
- `AppPluginContextFactory`：按插件创建上下文。适配器通过 `Provider` 延迟取得；没有生产插件时不会因这层架构提前创建业务 Repository。
- `PluginRuntime` 与 `PluginModule`：从 Hilt `Set<Plugin>` 注册插件并交给 `PluginManager`。当前生产集合为空。
- `AgentModule`：只把 `DeskCubbyDataAPI`、`FileAPI`、`AppAPI`、`AIAPI` 等契约注入 Agent 分层；Agent 工具不直接取得业务 Screen、ViewModel、Repository、DAO 或任意文件系统能力。
- `core/src/test/.../TestPlugin.kt`：只存在于测试源集，不进入 APK，也不注册 UI。

`VaultAPI` 在这里指 Obsidian 兼容的 Markdown/文件仓库，连接 `NotesRepository`；它不代表 DeskCubby 的加密收藏夹，插件 API 也不会取得收藏夹密码、明文或派生密钥。

## 生命周期

插件必须提供稳定的 `id`、显示名称 `name` 和自身 `version`。`PluginManager` 支持注册、加载、卸载、反向批量卸载和状态快照，并拒绝重复 ID 与非法状态迁移。加载失败时会调用插件清理、释放该插件的 UI 注册并把状态记为 `FAILED`；单个插件失败不会阻止批量加载其他插件。

Android 进程被系统直接终止时不会保证回调，因此 `onUnload` 用于应用内明确卸载和测试清理，不能被插件当作唯一的持久化提交时机。需要保存的状态应在操作成功时立即通过 `StorageAPI` 或对应业务 API 提交。

## 开发一个新插件功能

1. 新建 Android/Kotlin 模块，并依赖 `project(":plugin-api:core")`。业务代码只导入 `com.deskcubby.plugin.api.core` 及其 `api` 包。
2. 实现 `Plugin`。在 `onLoad(context)` 中保存 `PluginContext` 或注册所需 contribution；在 `onUnload()` 中停止插件自己的任务并释放主动持有的注册句柄。
3. 日记、Markdown、媒体、同步和 AI 操作分别通过 `context.diary`、`vault`、`media`、`sync`、`ai` 发起；通用结构化数据、SAF 范围文件和允许公开的应用状态分别通过 `context.data`、`files`、`app` 发起。不要直接注入 app 内 Repository，也不要自行解析 SAF URI、云凭据或 AI Key。
4. 插件状态只通过 `context.storage` 保存。该命名空间按插件 ID 隔离，不增加 Room 表、不属于 `AppSettings`，也不会改变 DeskCubby v30 JSON。
5. 用 `context.ui.registerPage/registerWidget/registerEntry` 声明 Compose 页面、组件和入口。当前 `PluginUiRegistry` 只负责安全注册与卸载清理，现有导航故意不读取它；首次正式交付插件 UI 时，应在统一宿主位置接入 registry，不能把旧页面迁入插件或悄悄改变现有导航。
6. 在插件模块的 Hilt module 中用 `@IntoSet` 把实现绑定为 `Plugin`。未绑定的开发中插件不会进入生产运行时。
7. 为生命周期失败、外部文件冲突、取消、超限输入和存储隔离补测试，再运行下方验证。

示意代码：

```kotlin
class ExamplePlugin @Inject constructor() : Plugin {
    override val id = "deskcubby.example"
    override val name = "Example"
    override val version = "1.0.0"

    override suspend fun onLoad(context: PluginContext) {
        val diaries = context.diary.list()
        context.storage.put("last_count", diaries.size.toString())
    }

    override suspend fun onUnload() = Unit
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ExamplePluginModule {
    @Binds
    @IntoSet
    abstract fun bindExamplePlugin(plugin: ExamplePlugin): Plugin
}
```

## 验证

```powershell
.\android\gradlew.bat --project-dir .\android :plugin-api:core:testDebugUnitTest --offline
.\android\gradlew.bat --project-dir .\android :app:compileDebugKotlin --offline
```

需要交付 APK 时，继续执行仓库根文档中的 Android JVM 测试、Android test 源码编译、`assembleDebug` 与 Lint。Agent 升级通过显式迁移把 Room 升至 v13、把 DeskCubby JSON 升至 v30；Markdown、SAF URI 与 `dc-media.json` v2 边界不变。
