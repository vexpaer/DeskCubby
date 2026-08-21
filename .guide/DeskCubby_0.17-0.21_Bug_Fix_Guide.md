# DeskCubby Android 0.17–0.21 Bug Fix Guide

> 目标：系统修复 DeskCubby Android 自 0.17.0 至当前 0.22.0 引入或遗留的高置信 bug。
>
> 本轮以 **稳定性、数据完整性、后台任务可靠性、用户可感知行为正确性** 为最高优先级。
>
> **不要新增新功能，不要进行无关 UI 重构，不要为了“看起来修好了”绕过根因。**
>
> 当前先不要求新建完整 regression suite，但所有修改必须完成现有测试、编译、Release 构建和关键路径人工/自动验证。

## 修复完成后交付debug apk

---

## 0. 总体要求

修复顺序：

1. P0：数据丢失、AI 全部卡死、同步静默失效、备份不完整
2. P1：后台任务无法停止、照片流程卡死、导出假成功、凭据安全
3. P2：Desk / 横屏 / 导航 / UI wiring 等行为错误

必须遵守：

- 不允许通过“加更长 timeout”“忽略异常”“自动清空数据库”等方式掩盖问题。
- AI 后台运行设计保留，**禁止退回 ViewModel coroutine**。
- 云同步继续保留 RecordSync 架构，禁止回退成整包 JSON 同步。
- 照片保存必须保证：
  - 图片文件持久化成功；
  - Markdown 引用持久化成功；
  - 这两步成功后 UI 就应该认为“添加完成”；
  - AI、全量扫描、Geocoder、额外 metadata 等不得继续阻塞前台。
- 数据导出必须做到“失败即失败”，禁止生成空文件后仍提示成功。
- 对所有数据迁移与恢复逻辑，必须兼容现有用户数据。
- 对现有已经卡死的 AI task，要有自动恢复方案。
- 不要删除用户已有数据来“修复”。

完成后至少运行：

```bash
./gradlew test
./gradlew lint
./gradlew assembleRelease
```

如果项目已有更完整的 Android 验证命令，以项目现有命令为准补充执行。

---

# P0-1：AI Task Queue 存在永久卡死

## 现象

用户会看到：

- “正在准备模型请求”
- “Agent 正在规划下一步”

长时间不动。

一旦第一次出现 zombie task，后续多个 AI 功能都可能一起失效，包括：

- Agent Chat
- 图片热量识别
- 单日热量统计
- 其他统一接入 AiTaskQueue 的模型任务

## 根因 A：RUNNING task 无法恢复

当前 Application/Queue 启动逻辑会发现数据库存在 `RUNNING` task 后重新 schedule worker。

但真正的 claim 逻辑只领取：

```text
QUEUED
```

并不会重新处理遗留：

```text
RUNNING
```

因此：

```text
QUEUED
  ↓
RUNNING
  ↓
进程 / Worker 被杀
  ↓
数据库仍然 RUNNING
  ↓
App 重启
  ↓
Worker 只找 QUEUED
  ↓
该任务永久僵死
```

## 根因 B：claim 后可能取错任务

当前逻辑大致是：

```text
claimOldest(QUEUED -> RUNNING)
peekOldest(RUNNING)
```

第二步不是精确返回“刚刚 claim 的 task”，而是重新查数据库里最老的 RUNNING。

如果已经存在一个历史 zombie RUNNING：

```text
#5 RUNNING   <- zombie
#6 QUEUED
```

领取 #6 后：

```text
#5 RUNNING
#6 RUNNING
```

然后 `peekOldest(RUNNING)` 可能再次得到 #5。

结果 #6 也被遗留成 RUNNING。

## 修复要求

### 1. 原子 claim

`claimNext()` 必须原子化，并且**精确返回这次从 QUEUED 改为 RUNNING 的同一条 task**。

可以采用以下任一可靠方式：

- transaction 中先取 ID，再 conditional update，再按 ID read；
- SQLite `UPDATE ... RETURNING`（若 Room/SQLite 版本支持）；
- 使用 lease owner / worker ID；
- 其他同等可靠的 compare-and-set 方案。

禁止：

```text
claim 一个 task
↓
重新查“最老的 RUNNING”
```

### 2. RUNNING 恢复机制

必须定义 interrupted task 的恢复规则。

推荐：

```text
QUEUED
  ↓
RUNNING + leaseStartedAt
```

如果：

- 进程启动时发现历史 RUNNING；
- lease 超时；
- 对应 Worker 已不存在；
- Worker 被系统中止；

则：

```text
RUNNING -> QUEUED
```

重新执行。

也可以使用可靠 lease/heartbeat 机制，但最终要求是：

> 任意非正常退出后，任务不能永久停在 RUNNING。

### 3. 修复已有用户数据库

不能只修新任务。

App 升级启动时需要处理当前用户数据库中已经存在的 stale RUNNING task。

至少做到：

- 判断是否属于历史遗留；
- 安全重新排队；
- 不重复执行已经成功完成的 side effect；
- 不直接删掉用户任务。

### 4. 状态转换统一

至少保证：

```text
QUEUED
  -> RUNNING
  -> SUCCEEDED

QUEUED
  -> RUNNING
  -> FAILED

QUEUED
  -> CANCELED

RUNNING 被系统中断
  -> QUEUED / 可恢复状态
```

任何情况下均不得永久停留 RUNNING。

## 验收

- 正常 AI 请求能完成。
- 运行中 force-stop / kill process，重新打开 App 后任务能够恢复。
- 数据库里手动制造旧 RUNNING 后，新任务仍可正常运行。
- 旧 zombie 不会污染后续任务。
- 多个 AI task 连续排队能够全部按正确顺序完成。

---

# P0-2：“停止 Agent”目前不是真正停止

## 现象

用户点击“停止 Agent”后：

```text
UI：Agent 已中止
```

但后台实际任务可能仍继续：

- 请求模型；
- 调工具；
- 修改数据；
- 写 assistant 回复。

## 根因

前台停止逻辑主要停止 ViewModel 中的等待 job。

而 `AiTaskQueue.cancelTask()` 对已经是：

```text
RUNNING
```

的 task 可能直接拒绝取消。

所以：

```text
UI stopped != backend stopped
```

## 修复要求

必须实现真正的 task cancellation。

### 状态层

允许：

```text
QUEUED -> CANCELED
RUNNING -> CANCEL_REQUESTED / CANCELED
```

如果不增加 `CANCEL_REQUESTED`，也必须有等价可靠机制。

### Worker / Agent loop

每个长步骤前后检查 cancellation：

- 模型请求前；
- 模型请求后；
- tool call 前；
- tool call 后；
- 下一轮 agent loop 前；
- 写数据前。

如果底层 HTTP client 支持 cancel，应同步取消网络调用。

### Side effect

一旦用户已经点击 Stop：

- 后续不得再执行新的写操作；
- 不得继续追加新的 assistant message；
- 不得继续执行下一轮工具；
- UI 与数据库状态必须一致。

## 验收

在 Agent：

- 正在请求模型；
- 正在等待下一轮；
- 正准备 tool call；

三个阶段分别点击 Stop，后台均能真正结束。

---

# P0-3：Agent WAITING_APPROVAL 未真正持久化

## 现象

Agent 进入“需要用户批准”的状态时，如果：

- 切后台；
- 系统杀进程；
- App 崩溃；
- Worker 被重启；

审批上下文可能丢失，任务留下 zombie RUNNING。

## 根因

数据库虽然已经定义：

```text
WAITING_APPROVAL
```

并存在类似 `markWaitingApproval()` 的 DAO 能力，

但实际审批流程主要依赖：

- `CompletableDeferred`
- `MutableStateFlow`
- ViewModel / 进程内内存

没有真正让数据库状态成为 source of truth。

## 修复要求

等待批准时必须：

```text
RUNNING
  -> WAITING_APPROVAL
```

并持久化：

- taskId
- approval request
- tool/action
- arguments
- 用户可阅读的修改摘要
- 创建时间
- 必要上下文

用户批准后：

```text
WAITING_APPROVAL
  -> QUEUED / RUNNING
```

用户拒绝：

```text
WAITING_APPROVAL
  -> Agent 获得 rejection result
  -> 继续或结束
```

App 重启后：

- 可以重新显示审批 UI；
- 不得丢失审批；
- 不得留下无主 RUNNING。

---

# P0-4：RecordSync 对部分设置修改无法检测

## 涉及

重点检查：

- Global Settings
- Reader Preferences
- RSS Subscription
- 其他 revision / updatedAt 被固定值代替的 RecordSyncAdapter

## 现象

第一次同步正常。

修改后第二次同步可能静默认为：

```text
没有变化
```

于是永远不上传新值。

## 根因

RecordSyncEngine 主要依赖类似：

```text
old.revision != local.revision
```

判断本地是否变化。

但部分 adapter 的 `LocalRecordRef` revision / modifiedAt 被固定：

```text
1L
0L
```

例如类似：

```text
global-settings      revision = 1
reader-preferences   revision = 1
rss subscription     revision = 1
```

于是：

```text
第一次同步 revision = 1
↓
用户修改
↓
revision 仍然 = 1
↓
RecordSync 判断 unchanged
↓
甚至不重新 hash payload
```

## 修复要求

所有 adapter 必须具备可靠变化检测。

优先选择：

### 方案 A：真实 revision / updatedAt

每次本地数据发生变化：

```text
revision++
updatedAt = now
```

### 方案 B：payload hash

对于 aggregate configuration：

```text
settings
reader preferences
```

可以使用规范序列化后的 payload hash 作为变化依据。

要求：

- 对 key 顺序 / 空字段保持 deterministic；
- 不能每次同步无变化却生成不同 hash。

## 需要全仓扫描

不要只修已经点名的三个 adapter。

搜索全部：

```text
LocalRecordRef
revision =
modifiedAt =
updatedAt =
1L
0L
```

确认所有 RecordSyncAdapter 均拥有正确 revision semantics。

## 验收

至少验证：

```text
设备 A 第一次同步
修改 Global Settings
再次同步
设备 B 得到新值
```

Reader Preferences、RSS 同样执行。

---

# P0-5：ZIP Export 丢失媒体元数据

## 现象

0.21 新增 ZIP 导出后：

图片本身可以进入 ZIP，

但媒体 sidecar 可能被主动排除，例如：

```text
dc-media.json
```

而这些 sidecar 保存：

- energyKj
- foods
- latitude
- longitude
- place
- meal metadata
- 其他图片附加信息

与此同时结构化：

```text
data/data.json
```

并没有完整接管上述内容。

因此 ZIP 看起来“导出成功”，实际上丢失部分重要数据。

## 修复要求

必须保证 ZIP 是完整可迁移数据。

二选一：

### 方案 A

直接保留原始 sidecar：

```text
media/
  image.jpg
  dc-media.json
```

### 方案 B

正式迁移成：

```text
data/media-metadata.json
```

并确保所有字段无损保存。

无论采用哪种方案：

- 热量不能丢；
- food list 不能丢；
- GPS / place 不能丢；
- meal history metadata 不能丢；
- 必须可以从 ZIP 恢复出语义等价数据。

---

# P1-1：0.19.1 的“正在添加照片”没有真正修完

## 用户现象

拍照后：

```text
正在添加照片...
```

仍可能持续很久。

0.19.1 虽然把 AI 热量估算挪到后台，但卡顿仍存在。

---

## 问题 A：Desktop Widget 路径仍等待 scan()

桌面小组件自己的照片添加 Activity 没有完全复用主页修复。

当前流程可能类似：

```text
显示“正在添加照片”
↓
appendImageToToday()
↓
enqueue AI
↓
diaryFileRepository.scan()
↓
全部完成
↓
dismiss loading
```

`scan()` 是全量日记目录扫描。

当：

- 日记数量大；
- SAF 慢；
- 文件 Provider 慢；

时 UI 会持续显示 loading。

## 修复要求

照片 durable write 完成后：

```text
image write
+
markdown write
+
必要 read-back
```

立即：

```text
dismiss “正在添加照片”
```

然后再后台：

```text
AI
scan/index refresh
metadata enrich
```

Desktop Widget 与主页必须复用同一个 durable photo pipeline，避免两套逻辑以后再次分叉。

---

## 问题 B：appendImageToToday 临界区仍然过宽

当前 photo import 流程可能同步执行：

- 图片压缩
- SAF write
- 原图复制到 Gallery
- EXIF GPS
- Reverse Geocoder
- media metadata
- Markdown
- 其他附加操作

尤其同步 Geocoder：

```text
Geocoder.getFromLocation(...)
```

可能非常慢，而且不是“照片已经保存”的必要条件。

## 正确边界

前台必须只等待：

```text
1. DeskCubby media 文件真正持久化
2. Markdown 图片引用真正写入
3. 必要 read-back / fsync 成功
```

随后 UI 立即恢复。

以下全部后台：

- AI
- Reverse Geocoder
- EXIF enrichment（若实现允许，可在 durable write 后处理）
- Gallery copy
- index scan
- calorie calculation
- 非关键 metadata enrichment

### 注意

如果某 metadata 与写入强一致性有关，允许先写 placeholder，然后后台补齐。

不能为了后台化而丢数据。

## 验收

人工构造：

- scan 永不返回；
- Geocoder 极慢；
- AI 永不返回；

用户仍然应在图片 + Markdown 完成后立刻退出“正在添加照片”。

---

# P1-2：ZIP Export 存在“假成功 / 0B 文件”

## 现象

如果源文件：

```text
openInputStream(uri) == null
```

或者读取中失败，

当前流程可能：

- 已创建 ZIP entry；
- entry 最终 0 bytes；
- exported count 仍然 +1；
- 最终 UI 仍提示成功。

同样：

```text
openOutputStream(downloadUri)
```

如果返回 null，也可能继续完成状态。

## 修复要求

### 读取

任何被用户明确选择导出的文件，如果读取失败：

- 不允许静默跳过；
- 不允许计入成功数；
- 必须在最终结果中明确失败。

可以：

- 整体 export fail；
- 或导出部分成功 + 明确列出 failed files。

但不能“假成功”。

### 写入

写入 Downloads 后必须：

1. 确认 output stream 非 null；
2. flush/close 成功；
3. reopen；
4. 校验 size；
5. 最好计算 SHA-256；
6. 与临时 ZIP hash / size 对比；
7. 最终才标记成功。

## 验收

人为让一个 source URI 无法读：

```text
Export != Success
```

人为让目标无法写：

```text
Export != Success
```

ZIP 内不能存在“因为读取失败产生的无意义 0B entry”。

---

# P1-3：Android 系统备份可能包含敏感凭据

## 背景

手动 DeskCubby JSON backup 已经有意识排除：

- AI API Key
- S3 credential
- 其他敏感连接配置

这是正确的。

但 Android Manifest 当前允许：

```text
allowBackup = true
```

Settings DataStore 本身又保存：

- AI apiKey
- S3 accessKey
- S3 secretKey
- S3 sessionToken
- 可能还有其他 secret

系统 backup exclusion 没有完整覆盖 settings DataStore。

## 风险

即使 DeskCubby 手动 backup 不包含凭据，

Android Auto Backup 仍可能把：

```text
datastore/deskcubby_settings.preferences_pb
```

一起备份。

## 修复建议

优先推荐：

### 长期方案

把 secret 全部迁入 Android Keystore-backed secret storage：

- API Key
- S3 Secret
- token

普通 AppSettings 只保存：

```text
secret reference / hasCredential
```

### 最低要求

如果本轮暂不迁移：

- 明确从 Android backup rules 排除包含凭据的 DataStore；
- 检查 `data_extraction_rules.xml`
- 检查旧版 backup rules
- 同时检查 device-to-device transfer 规则。

## 迁移要求

升级后不能让已有用户 key 直接消失。

如果迁 Keystore：

```text
旧 DataStore secret
↓
第一次启动安全迁移
↓
写 SecretStore
↓
确认成功
↓
删除旧明文字段
```

---

# P1-4：AI Task payload 不应复制整份带 Key 的 Settings

## 问题

0.21 为了后台 AI 任务恢复，部分 task payload 可能通过 AppSettingsCodec 把整份 settings JSON 塞进：

```text
ai_task_queue.payloadJson
```

其中可能包含：

```text
apiKey
```

这样会：

- 在 task queue 数据库再复制一份 secret；
- task 完成后仍可能残留；
- key rotation 后旧 key 仍留在历史 task。

## 修复要求

AI task payload 不应该保存整份 Settings。

只保存运行任务真正必要的配置，例如：

```text
providerId
model
baseUrl
temperature
feature flags
```

secret 在 Worker 真正执行时从安全 SecretStore / current settings 获取。

如必须保证任务使用 enqueue 时的 provider 配置，也只 snapshot 非 secret 字段。

禁止：

```text
task payload = complete AppSettings including apiKey
```

完成/失败/取消后的历史 task 还需要有明确清理策略。

---

# P2-1：Desk “总结今天”没有把 Prompt 传给 AI

## 现象

Desk 提供：

```text
总结一下我今天的状态
```

点击后：

```text
onOpenChat(prompt)
```

但 Navigation wiring 只执行：

```text
navigate(AI_CHAT)
```

prompt 参数被丢弃。

结果：

```text
用户点击总结今天
↓
只打开一个空 AI 页面
```

## 修复要求

提供明确 navigation / shared state：

```text
AI_CHAT?initialPrompt=...
```

或：

- SavedStateHandle
- shared navigation state
- explicit ViewModel action

进入 AI Chat 后：

- prompt 必须真正出现在输入框或直接作为用户消息发送；
- 不得因为旋转 / recomposition 重复发送。

---

# P2-2：Desk 快速“照片”入口实际没有打开照片流程

## 现象

Desk：

```text
+ -> 照片
```

当前可能只是：

```text
onOpenTodayDiary()
```

并没有真正：

- 拍照；
- 选图片；
- 进入 meal/photo flow。

## 修复要求

Desk photo action 应复用统一的图片入口。

不要再写第三套 photo logic。

推荐：

```text
PhotoCaptureCoordinator / shared navigation action
```

统一由：

- Home
- Desk
- Desktop Widget

调用同一业务层。

---

# P2-3：Desk “+N traces” 跳转目标错误

## 现象

Desk 的 Today Traces 可能包含：

- 日记
- 图片
- 小巧思
- 事件
- 其他 trace

但“查看更多”当前可能固定跳到：

```text
THOUGHT
```

## 修复要求

根据设计重新定义。

推荐两种：

### 方案 A

打开专门的 Today Traces 页面。

### 方案 B

展开 Desk 内完整 traces。

不要固定跳“小巧思”。

---

# P2-4：Desk stagger animation 实际可能没有 stagger

## 问题

动画实现可能类似：

```text
Animatable(0 -> 1)
```

但 produceState 只在 `animateTo()` 完成后才把最终 value 写回 Compose state。

导致 UI 实际看到：

```text
0
...
1
```

于是：

```text
> 0.4
> 0.6
> 0.75
> 0.85
```

几乎同一帧触发。

## 修复要求

直接使用 Compose 可观察的 Animatable state：

```text
val progress = remember { Animatable(0f) }
LaunchedEffect { progress.animateTo(1f, ...) }
```

UI 直接读取：

```text
progress.value
```

或者为每个模块使用明确 delay。

---

# P2-5：竖屏 Tablet 仍可能出现左侧 Navigation Rail

## 产品规则

DeskCubby 既定导航规则：

```text
竖屏 -> 底部 Navigation Bar
横屏 -> 左侧 Navigation Rail
```

导航位置只由：

```text
orientation
```

决定。

内容布局可以继续根据 width：

```text
COMPACT / MEDIUM / EXPANDED
```

决定。

## 当前问题

当前 navigation 可能绑定：

```text
layoutMode != COMPACT
```

所以：

```text
Tablet portrait
width >= 600dp
↓
MEDIUM
↓
left rail
```

违反产品规则。

## 修复要求

彻底拆分两个概念：

### Navigation Placement

```text
portrait  -> bottom
landscape -> left
```

### Content Layout

继续用：

```text
window size / available width
```

决定：

- 单栏
- 双栏
- expanded workspace

例如允许：

```text
竖屏 Pad
= 底部导航 + 双栏内容
```

这是正确行为。

---

# P2-6：RecordSync identical payload 可能导致两条本地记录碰撞

## 背景

首次给旧记录生成稳定 ID 时，当前可能采用：

```text
seedRecordId(payloadSha256)
```

这样可以让“两个设备上本来就是同一个旧内容”的记录自动 merge。

但存在碰撞语义问题：

同一设备真实存在两条不同记录：

```text
record A payload = "hello"
record B payload = "hello"
```

二者 hash 相同：

```text
seed-abc
seed-abc
```

然后 map：

```text
localRefById[id] = ref
```

后一条覆盖前一条。

结果另一设备同步时可能最多得到一条。

## 修复要求

长期稳定 ID 应独立于 payload。

推荐：

```text
UUID
```

对于升级旧数据：

```text
local persistent key -> generated UUID
```

第一次 migration 建立映射，以后永久沿用。

如果仍希望“跨设备相同旧内容自动 dedupe”，应把：

```text
dedupe heuristic
```

与：

```text
record identity
```

分开。

不要使用 payload hash 同时承担两种职责。

---

# 3. 全局代码审查要求

除了上述明确问题，还需要顺手扫描同类问题。

---

## 3.1 后台任务状态机

搜索：

```text
RUNNING
QUEUED
WAITING_APPROVAL
CANCELED
WorkManager
APPEND_OR_REPLACE
unique work
```

检查：

- 有没有状态无法退出；
- 有没有 UI 与 DB 状态不一致；
- 有没有 Worker retry 导致重复 side effect；
- 有没有旧任务阻塞新任务；
- cancellation 是否贯穿整个调用链；
- 是否存在并发 worker 同时 claim 同一 task。

---

## 3.2 照片 / 文件 UI 临界区

搜索：

```text
appendImageToToday
persistMealPhoto
mealUploadInProgress
scan(
Geocoder
Exif
MediaStore
```

要求：

> UI busy 只保护 durable write，不保护后台 enrichment。

检查 Home、Desk、Widget 三条入口是否一致。

---

## 3.3 RecordSync

扫描所有 adapter：

```text
LocalRecordRef
revision
updatedAt
modifiedAt
payloadSha256
seedRecordId
```

重点找：

- revision 固定值；
- modifiedAt 永远 0；
- payload hash 不稳定；
- ID 与内容耦合；
- applyRemote 后 revision 没更新；
- merge 后产生无限 ping-pong；
- adapter 没注册。

---

## 3.4 Backup / Export

搜索：

```text
ZipOutputStream
openInputStream
openOutputStream
IS_PENDING
backup
export
sidecar
```

要求：

- 所有重要数据进入导出；
- 不允许 silent skip；
- 不允许 0B 假成功；
- 输出文件可 read-back；
- 敏感 secret 不进入普通用户备份。

---

## 3.5 Navigation / Desk

扫描：

```text
onOpenAi
onOpenChat
onOpenTraces
onSelectPhoto
LayoutMode
showBottomBar
showWorkspaceRail
orientation
```

确保：

- callback 参数真正被消费；
- placeholder action 已移除；
- navigation placement 不再错误依赖 width class。

---

# 4. 不要这么修

以下修法禁止：

## AI 卡死

禁止：

```text
把 timeout 从 30min 改成 60min
```

这不是修复。

禁止：

```text
启动时直接 deleteAll RUNNING
```

可能丢任务。

禁止：

```text
把 AI 放回 ViewModel coroutine
```

会失去后台持续运行。

---

## 照片卡死

禁止：

```text
延迟 2 秒后强制隐藏 loading
```

必须根据 durable write 成功判断。

禁止：

```text
隐藏 loading，但 scan 继续持有同一个 critical lock
```

会产生其他隐性问题。

---

## RecordSync

禁止：

```text
每次都强制上传所有 records
```

虽然能绕过 revision bug，但会毁掉同步性能和冲突语义。

---

## ZIP

禁止：

```text
catch Exception {}
```

备份失败必须显式可见。

---

# 5. 推荐实施顺序

## 第一批：AI 救火

- [ ] 原子 claim
- [ ] stale RUNNING recovery
- [ ] 修已有 zombie task
- [ ] RUNNING cancellation
- [ ] WAITING_APPROVAL persistence
- [ ] 移除 task payload 中 secret snapshot

完成后先验证所有 AI 功能。

---

## 第二批：数据安全

- [ ] RecordSync revision
- [ ] ZIP media metadata
- [ ] ZIP read/write verification
- [ ] Android backup secret exclusion / Keystore migration

---

## 第三批：0.19.1 照片流程

- [ ] Home photo pipeline
- [ ] Desktop Widget photo pipeline
- [ ] Desk photo pipeline
- [ ] durable-write boundary
- [ ] scan 后台化
- [ ] Geocoder 后台化
- [ ] AI 后台化确认

---

## 第四批：Desk / Tablet

- [ ] Desk initial AI prompt
- [ ] Desk photo action
- [ ] Traces action
- [ ] stagger animation
- [ ] portrait bottom nav / landscape left rail

---

## 第五批：Record ID 长期稳定性

- [ ] identical payload collision
- [ ] stable UUID migration
- [ ] dedupe 与 identity 分离

---

# 6. 最终验收清单

完成全部修复后，必须验证以下真实用户流程。

## AI

- [ ] Agent 正常对话
- [ ] Agent 调 tool
- [ ] Agent 等待批准
- [ ] 等待批准时杀 App，重启后可继续
- [ ] Agent 运行中点击 Stop，后台真的停止
- [ ] 模型请求过程中切后台
- [ ] 系统杀进程后任务能恢复
- [ ] 一个历史 zombie task 不会阻塞新 AI
- [ ] 图片 AI / Agent 可以连续排队运行

## 照片

- [ ] Home 拍照
- [ ] Desktop Widget 拍照
- [ ] Desk 拍照
- [ ] 图片写完后 loading 立即结束
- [ ] AI 很慢不影响 loading
- [ ] scan 很慢不影响 loading
- [ ] Geocoder 很慢不影响 loading
- [ ] Markdown 引用实际存在
- [ ] App 重启后图片仍存在

## Sync

- [ ] 修改 settings 后第二次 sync 有效
- [ ] 修改 reader preferences 后有效
- [ ] 修改 RSS 后有效
- [ ] 两设备双向同步
- [ ] 冲突行为符合预期
- [ ] 相同 payload 的两条独立记录不丢

## Export

- [ ] ZIP 中包含所有用户选择的数据
- [ ] media metadata 完整
- [ ] 故意制造 unreadable source 时不假成功
- [ ] Downloads 写失败时不假成功
- [ ] 导出后 reopen 成功
- [ ] ZIP size/hash 正常
- [ ] 不包含不该导出的 secret

## UI / Navigation

- [ ] 手机竖屏 -> bottom nav
- [ ] Pad 竖屏 -> bottom nav
- [ ] 手机横屏 -> left rail
- [ ] Pad 横屏 -> left rail
- [ ] Desk “总结今天”真正进入 AI prompt
- [ ] Desk “照片”真正进入 photo flow
- [ ] Traces 行为正确

---

# 7. 完成标准

本任务只有同时满足以下条件才算完成：

1. 上述 P0/P1/P2 全部逐项处理；
2. 不能只修 UI 表象；
3. 现有用户数据可以安全升级；
4. 不新增明显架构分叉；
5. Home / Desk / Widget 能共享的逻辑必须共享；
6. `./gradlew test` 通过；
7. `./gradlew lint` 无新增 blocker；
8. `./gradlew assembleRelease` 成功；
9. 对 AI、照片、同步、导出四条关键路径做最终验证；
10. 最终给出修改报告，格式至少包含：

```text
Fixed
- bug
- root cause
- changed files
- verification

Remaining risks
- ...

Build/Test
- ...
```

如果发现本文某条 bug 在当前最新代码中已经被其他提交解决，不要再次重写；先验证确实已解决，然后在最终报告里标记：

```text
Already fixed / verified
```

如果发现新的同类高置信 bug，可以一并修复，但必须在最终报告中单独列出：

```text
Additional bugs found
```

---

# 8. 本轮核心原则

DeskCubby 最近的问题主要来自：

```text
功能迭代很快
+
Home / Desk / Widget 多入口存在重复实现
+
后台任务跨进程生命周期复杂
+
RecordSync / Export 属于数据基础设施
```

因此本轮修复重点不是“哪里报错改哪里”，而是统一几个基础规则：

```text
AI task
= persistent + recoverable + cancellable

Photo
= durable write first + enrichment async

Sync
= stable identity + real revision

Export
= complete + verifiable + never silent fail

Navigation
= orientation controls nav placement
  width controls content layout
```

按这些规则修完，后续继续开发新功能时才不会反复把旧 bug 带回来。
