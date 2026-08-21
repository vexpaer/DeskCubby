# DeskCubby「结构化记录」系统升级

> **系统提示词 + 产品 / 数据 / Markdown / 统计设计文档**  
> 目标：将现有「日常事件」升级为可长期积累、可统计、可计算、可被 Agent 使用，同时仍保持普通 Markdown / Obsidian 可读的「结构化记录」系统。

---

# Part A：给 Agent 的实现提示词

你正在修改 **DeskCubby**。

本次任务不是在现有「日常事件」上零散增加几个 placeholder，而是将其正式升级并更名为：

> **结构化记录（Structured Records）**

结构化记录是 DeskCubby 日记体系的核心能力之一：用户仍然是在写正常 Markdown 日记，但可以在日记中嵌入带稳定字段 ID 的结构化值；DeskCubby 在本地建立索引，并在「统计 → 结构化记录统计」中对这些字段进行趋势、聚合以及用户自定义派生计算。

实现时必须同时保证：

1. **Markdown 是用户真正拥有的数据。**
2. Obsidian、Typora、VS Code、GitHub、普通文本编辑器仍能正常阅读日记。
3. DeskCubby 私有语义不能污染正常阅读体验。
4. 统计不能每次全量扫描全部 Markdown，应使用可重建的本地增量索引。
5. 字段使用稳定 ID，不以显示名称作为身份。
6. “自然日期”与“Journal Day / 日记日”分离；默认日界线为 `05:00`。
7. 所有跨午夜时间计算必须基于统一时间语义，而不是单独为睡眠写特殊 hack。
8. 自定义统计采用结构化表达式 / AST，不执行用户输入的任意代码或 `eval`。
9. 字段类型是强语义类型：不仅决定输入控件，还必须决定校验、归一化、可用聚合、可用图表和公式运算。
10. 字段类型与数据来源分离：`time` 仍然是 `time`，无论它来自手动填写、系统自动采集还是派生计算。
11. 睡觉/起床自动记录仅基于手机系统的首次/最后一次使用、解锁/锁屏等行为估算，不接入 Health Connect。
12. 旧数据优先兼容，不做无必要的破坏性迁移。

---

## 1. 全局更名

将用户可见的现有：

```text
日常事件
```

统一升级为：

```text
结构化记录
```

包括但不限于：

- 页面标题
- 设置项
- 添加按钮
- 模板管理
- 空状态
- 搜索/筛选
- Agent 工具描述
- 统计入口
- 教程文案
- 导入导出文案

代码内部旧类名可以分阶段迁移，但新建的数据模型、接口、UI、测试优先使用 `StructuredRecord*` 命名，不继续扩大 `DailyEvent*` 技术债。

---

## 2. 核心数据层级

整个系统必须明确区分四层：

```text
结构化记录模板 / 字段定义
        ↓
用户填写
        ↓
正常 Markdown + 隐藏字段 ID
        ↓
本地 Structured Record Index
        ↓
统计 / 派生指标 / Agent
```

其中：

- **Markdown**：原始记录，是 Source of Truth。
- **`.deskcubby/*.json`**：字段、模板、统计公式、Journal Day 规则等“解释 Markdown 所需的工作区语义”。
- **本地 Room / SQLite index**：性能缓存，可删除、可全量重建，不作为唯一数据源。
- **UI/统计/Agent**：消费索引，不应在正常使用时反复全量扫 Markdown。

---

## 3. `.deskcubby` 工作区目录

用户选择的**日记文件夹根目录**下默认建立：

```text
Journal/
├─ 2026-08-17.md
├─ 2026-08-18.md
├─ 2026-08-19.md
└─ .deskcubby/
   ├─ settings.json
   ├─ fields.json
   ├─ records.json
   └─ statistics.json
```

职责：

### `settings.json`

保存与这套日记数据绑定、需要跨设备保持一致的规则，例如：

- schema / protocol version
- Journal Day Boundary
- 未来其他需要跟随整个日记工作区同步的结构化记录规则

### `fields.json`

保存所有结构化字段定义：

- stable field ID
- 显示名称
- 类型
- 数据来源（manual/system/derived；agent 预留）
- 单位
- type 枚举选项
- archived 状态
- 可选统计显示配置

### `records.json`

保存结构化记录模板定义以及模板中引用的字段 ID。

### `statistics.json`

保存用户在「结构化记录统计」中创建的：

- 自定义统计卡片
- 派生指标
- 计算表达式
- 图表配置
- 默认聚合方式

不要把以下设备专属配置迁入 `.deskcubby`：

- 深色模式
- UI 主题
- 横竖屏偏好
- 窗口尺寸
- 本地权限状态
- 本地缓存状态
- API Key / token / 密钥

Android 当前已有的 DataStore 设置体系继续存在；`.deskcubby` 只负责**日记工作区级语义**。

特别注意：虽然“自动记录睡觉/起床时间”的 UI 位于「日记设置 → 结构化记录」，但它是**设备能力开关**，应保存在本地 DataStore，不写入 `.deskcubby/settings.json`。`.deskcubby` 只同步最终字段定义与已经落入 Markdown 的结构化结果。

---

## 4. `settings.json` 与 Journal Day

第一版建议至少保存当前 Boundary，并为 Boundary 修改保留可重建的历史解释：

```json
{
  "schemaVersion": 1,
  "markdownProtocolVersion": 1,
  "dayBoundary": "05:00",
  "dayBoundaryHistory": [
    { "effectiveFromJournalDay": "2026-08-18", "value": "05:00" }
  ]
}
```

如果实现阶段希望 V1 UI 仍只暴露一个 `dayBoundary`，也必须在数据层保留等价的 Boundary history / effective rule；否则用户以后修改 Boundary 后，仅凭“日记文件日期 + HH:mm + 当前 Boundary”无法无歧义恢复旧记录当时的真实 Calendar Date。

要求：

- 默认 `dayBoundary = "05:00"`。
- 使用本地时间 `HH:mm`，24 小时制。
- 文件不存在时自动使用默认值，并在合适时机安全创建。
- 非法值安全回退到 `05:00`。
- 写入采用原子写入 / 临时文件替换。

统一实现：

```text
resolveJournalDay(timestamp, effectiveDayBoundary, timezone) -> LocalDate
getEffectiveDayBoundary(journalDay / timestamp) -> HH:mm
```

规则：

```text
localTime < dayBoundary  → Journal Day = Calendar Date - 1 day
localTime >= dayBoundary → Journal Day = Calendar Date
```

例如边界 `05:00`：

```text
2026-08-19 02:37 → Journal Day 2026-08-18
2026-08-19 04:59 → Journal Day 2026-08-18
2026-08-19 05:00 → Journal Day 2026-08-19
2026-08-19 18:20 → Journal Day 2026-08-19
```

**所有“今天”语义必须统一调用这个 resolver**，包括：

- 「进入今日日记」
- 首页“今天”
- 新结构化记录插入目标日记
- 今日统计
- 今日热量 / 今日运动等按日功能
- 连续记录天数
- 日历默认高亮
- Widget
- 后台任务
- Agent 对“今天 / 昨天”的解释

禁止各页面自行实现不同版本的凌晨判断。

---

## 5. 字段系统：强类型，而不是五种不同输入框

V1 正式支持：

```text
word
number
type
time
duration
```

这五种类型必须具有**独特的数据与统计语义**。选择类型后，DeskCubby 就应天然知道：

- 值是否合法、如何标准化；
- 在索引中如何存储；
- 能做哪些聚合；
- 能画哪些默认图；
- 可以参与哪些公式；
- Agent 查询时应该把它当作文本、数字、类别、时刻还是时长。

不能把它们实现成“底层全部都是 string，只是输入控件不同”。

### `word`

自由文本。

预设示例：

```text
今日一句话 [word]
```

适合：

- 今天值得记住的事
- 今天看的电影
- 梦境关键词
- 地点
- 备注

统计语义：

- 时间线
- 搜索
- 非空记录次数
- 未来可交给 Agent 做文本分析

不默认提供 sum / average 等数值聚合。

### `number`

真正的数值字段，解析后必须保存 numeric normalized value。

预设示例：

```text
俯卧撑次数 [number]，单位：次
```

适合：

- 俯卧撑
- 体重
- 跑步距离
- 喝水量
- 花费

统计语义：

- sum / average / min / max / latest / count
- 折线图 / 柱状图
- 可参与四则运算
- 支持单位

### `type`

离散类别字段，不是普通文本。

预设示例：

```text
今天衣服颜色 [type]
```

例如：

```text
黑色
白色
蓝色
灰色
```

适合：

- 衣服颜色
- 心情
- 天气状态
- 工作地点

字段可以预设 options，并可配置：

```text
allowCustomOption = true / false
```

统计语义：

- 分类计数
- 占比
- 排名
- 日历分布
- 分类随时间变化
- 众数 / 最常见值

DeskCubby 应尽量复用历史 option，避免“黑色 / 黑 / 黑色衣服”被无意拆成多个类别。

### `time`

一天中的**时刻**，不是时长。内部标准化为本地 `HH:mm` 对应的分钟数/秒数，并在需要时结合 Journal Day 恢复实际日期时间。

手动预设示例必须使用：

```text
午饭时间 [time]
```

手动 `time` 字段必须保留旧「日常事件」升级构想里的低摩擦优势：

- 打开/插入该结构化记录时，`[time]` **默认自动填入当前本地时间**，例如 `21:47`；
- 用户可以不修改，直接一键保存；
- 保存前允许手动修改为其他时间；
- 若快捷记录支持“无需进入编辑器直接记录”，则 `[time]` 直接解析为触发瞬间的当前时间；
- 最终 Markdown 仍只写正常 `HH:mm`，绝不写私有时间格式。

因此 `午饭时间 [time]` 的典型体验应该是：中午点一下“午饭时间”即可记录当下时间，而不是还要求用户重新选择小时和分钟。

不要把“起床时间”作为 `time` 类型的手动示例，因为起床/睡觉在本系统中另有可选的系统自动采集能力。

其他适合场景：

- 晚饭时间
- 出门时间
- 到家时间
- 开始学习时间

统计语义：

- 每日时刻折线图
- 平均时刻
- 最早 / 最晚
- first / last
- 两个 `time` / datetime-like 值可通过 `timeDiff` 得到 `duration`
- 跨午夜绘图使用统一 Day Boundary unwrap

### `duration`

持续时长。内部建议统一存储整数秒或毫秒，显示时格式化为人类可读时长。

预设示例：

```text
午睡时长 [duration]
```

适合：

- 午睡时长
- 学习时长
- 运动时长
- 通勤时长

统计语义：

- sum / average / min / max
- 折线图
- 日/周/月总时长
- 可与其他 duration 做加减

### 类型与来源必须正交

增加字段来源概念，但 V1 必须区分 **原始字段（Field）** 与 **派生指标（Metric）**，不要把两者混成一种对象。

原始 Field 的来源：

```text
manual   用户主动填写
system   DeskCubby 根据系统事件自动生成
agent    预留：由 Agent 创建/建议原始值（V1 可不开放）
```

派生计算属于 `MetricDefinition`，保存在 `statistics.json`，不是每天重复写入 Markdown 的原始 Field。

例如：

```text
午饭时间 = Field(time, manual)
睡觉时间 = Field(time, system)
起床时间 = Field(time, system)
午睡时长 = Field(duration, manual)
睡眠时长 = Metric(resultType=duration, derived)
```

因此“字段 ≠ 统计指标”：

- `Field.type` 决定原始值的校验、归一化和可用统计；
- `Field.source` 说明原始值怎么获得；
- `Metric.resultType` 决定计算结果是 number/time/duration 等什么语义；
- Metric 默认不写回 Markdown，只在需要时计算/缓存。

如果未来确实需要“把派生结果物化回 Markdown”，再单独设计 `materialized derived field` 协议，不要在 V1 模糊处理。

---

## 5.1 内置结构化记录示例

首次进入结构化记录、且用户没有可用模板时，提供一组**可编辑、可删除的起步示例**。每个 V1 类型至少一个：

| 类型 | 默认示例 | 典型输入 | 默认统计优势 |
| --- | --- | --- | --- |
| `word` | 今日一句话 | `今天终于把功能做完了` | 时间线、搜索、记录次数 |
| `number` | 俯卧撑次数 | `30 次` | 趋势、总和、平均、最大最小 |
| `type` | 今天衣服颜色 | `黑色` | 分类次数、占比、日历分布 |
| `time` | 午饭时间 | `12:36` | 时间趋势、平均时刻、最早最晚 |
| `duration` | 午睡时长 | `00:42` | 总时长、平均、趋势 |

这些只是示例模板，不是特殊硬编码业务。用户可以：

- 删除；
- 改名；
- 修改单位/选项；
- 复制；
- archive；
- 新建自己的模板。

升级旧用户时：

- 优先迁移现有模板；
- 不重复塞入同名示例；
- 只有在模板为空或用户主动“添加示例”时才补充示例库。

---

## 5.2 系统来源：自动估算睡觉 / 起床时间

睡觉时间与起床时间不作为手动 `time` 示例，而是结构化记录系统的一项**可选 system source**。

明确要求：

> **不要使用 Health Connect。**

仅基于手机本身的系统交互事件，例如：

- 解锁 / USER PRESENT；
- 锁屏 / SCREEN OFF；
- 屏幕开启；
- DeskCubby 已有的手机使用记录能力能够可靠提供的首次/最后一次设备交互时间。

目标语义：

```text
起床时间：一个 Journal Day 中符合规则的第一次手机解锁/开始使用时间
睡觉时间：该 Journal Day 结束前最后一次手机锁屏/停止使用时间
```

这只是基于手机使用行为的**自动估算**，不是医学意义上的真实睡眠检测。UI 在说明中应明确这一点，但字段仍可显示为“睡觉时间 / 起床时间”，并带“自动估算”来源标记。

推荐内置字段：

```json
{
  "id": "f_system_sleep_time",
  "name": "睡觉时间",
  "type": "time",
  "source": "system",
  "collector": "last_phone_lock",
  "archived": false
}
```

```json
{
  "id": "f_system_wake_time",
  "name": "起床时间",
  "type": "time",
  "source": "system",
  "collector": "first_phone_unlock",
  "archived": false
}
```

自动记录关闭时，不采集/不生成新的睡觉与起床结构化值；已经生成的历史 Markdown 与统计不得删除。

系统字段最终落入 Markdown 时必须遵循“**同一 Journal Day 的同一系统字段更新已有值，而不是反复追加重复值**”原则：

- 若当天尚无 `f_system_wake_time` / `f_system_sleep_time` marker，则通过统一的结构化记录插入器写入目标日记；
- 若已有对应系统 marker，则更新其可见值；
- 更新不得破坏字段周围普通 Markdown 文本；
- 如果用户主动删除了系统 marker，在下一次尚未结算完成前可重新生成；已经结算且用户之后明确删除的历史值，不应后台无限“复活”，具体以索引中的用户删除/结算状态避免写回循环；
- 原始 lock/unlock 候选事件永远不逐条写入 Markdown。

V1 不要求新增专用可见“系统记录区”标题；优先复用现有结构化记录插入位置/规则，避免为了自动采集给用户日记强塞新的固定版式。

### 自动结算而不是每次锁屏都写日记

不能在每次屏幕开关时往 Markdown 塞一条记录。

例如：

```text
23:40 锁屏
00:20 解锁
00:35 锁屏
```

23:40 当时并不能确定是这一夜最后一次使用。正确流程是：

```text
持续收集必要的系统交互候选事件
→ 当前 Journal Day 临近/到达结算条件
→ 回看该 Journal Day 的候选事件
→ 得到最终 first unlock / last lock
→ 只把最终的起床/睡觉时间作为结构化值写入/更新 Markdown 与 index
```

在结算前 UI 可以显示“暂定值”，后续若又发生交互则自动更新。

原始系统事件只存本地必要缓存/索引，不逐条污染 Markdown；最终结构化结果才进入用户日记，使其能够随 Markdown 跨设备保留。

睡眠时长仍然推荐作为 `derived duration`：

```text
睡眠时长(D) = 起床时间(D) - 睡觉时间(D-1 Journal Day)
```

这使用通用派生指标系统实现，不写死睡眠专属计算器。

---

## 6. 字段 ID 是永久身份

**字段名称绝对不能作为主键。**

例如：

```json
{
  "id": "f_7e82ab",
  "name": "俯卧撑",
  "type": "number",
  "unit": "次"
}
```

以后用户改名：

```text
俯卧撑 → 标准俯卧撑
```

仍然保持：

```text
id = f_7e82ab
```

因此历史统计连续，不拆成两个字段。

字段删除默认执行 **archive**，而不是直接硬删除定义。

对于已经出现在历史 Markdown 中的字段 ID，除非用户显式执行高级清理，否则应保留最低限度的定义，使旧记录仍能解释。

---

## 7. `fields.json`

示例：

```json
{
  "schemaVersion": 1,
  "fields": [
    {
      "id": "f_lunch_time",
      "name": "午饭时间",
      "type": "time",
      "source": "manual",
      "archived": false
    },
    {
      "id": "f_system_sleep_time",
      "name": "睡觉时间",
      "type": "time",
      "source": "system",
      "collector": "last_phone_lock",
      "archived": false
    },
    {
      "id": "f_system_wake_time",
      "name": "起床时间",
      "type": "time",
      "source": "system",
      "collector": "first_phone_unlock",
      "archived": false
    },
    {
      "id": "f_pushups",
      "name": "俯卧撑",
      "type": "number",
      "source": "manual",
      "unit": "次",
      "archived": false
    },
    {
      "id": "f_top",
      "name": "上衣",
      "type": "type",
      "source": "manual",
      "options": ["黑色卫衣", "白色衬衫"],
      "allowCustomOption": true,
      "archived": false
    }
  ]
}
```

JSON 可在未来增加属性，但读取端必须忽略未知字段，保证向前兼容。

---

## 8. 模板编辑语义

对用户保留简单、直观的 placeholder 心智模型。

将旧的 `xx` placeholder 升级为：

```text
[word]
```

支持：

```text
[word]
[number]
[type]
[time]
[duration]
```

创建字段时允许命名：

```text
[number](俯卧撑)
[type](上衣)
[time](午饭时间)
[word](电影)
```

**这些是“结构化记录模板 DSL”，不是最终写入日记 Markdown 的格式。**

因此 `[value](field)` 与标准 Markdown link 的冲突不进入最终 `.md` 文件。

UI 不应要求普通用户手写 DSL。推荐：

```text
添加字段
├─ 文字
├─ 数字
├─ 分类
├─ 时间
└─ 时长
```

选择后填写字段名、单位、选项等，系统自动创建/复用 field ID。

模板内部保存时优先直接引用 `fieldId`，不要每次靠名字重新匹配。

---

## 9. `records.json`

结构化记录模板示例：

```json
{
  "schemaVersion": 1,
  "records": [
    {
      "id": "r_lunch",
      "name": "午饭时间",
      "archived": false,
      "segments": [
        { "kind": "text", "value": "午饭：" },
        { "kind": "field", "fieldId": "f_lunch_time" }
      ]
    },
    {
      "id": "r_pushups",
      "name": "俯卧撑",
      "archived": false,
      "segments": [
        { "kind": "text", "value": "做了 " },
        { "kind": "field", "fieldId": "f_pushups" },
        { "kind": "text", "value": " 个俯卧撑" }
      ]
    }
  ]
}
```

不要求第一版必须立刻采用 `segments` 这一精确结构，但最终模型必须避免仅保存难以演进的裸模板字符串。

---

## 10. 最终 Markdown 协议

原则：

> 用户打开源文件时看到的是正常日记；DeskCubby 需要的字段语义隐藏在 HTML comment 中。

字段类型、名称、单位等**不要重复写入 Markdown**，Markdown 仅写 stable field ID。

推荐 V1 使用成对边界标记，确保 `word` / `type` 等任意文本也可以无歧义恢复：

```markdown
今天做了 <!--dc:f_pushups-->20<!--dc:/f_pushups--> 个俯卧撑。
```

```markdown
午饭：<!--dc:f_lunch_time-->12:36<!--dc:/f_lunch_time-->
```

```markdown
上衣：<!--dc:f_top-->黑色超轻羽绒服<!--dc:/f_top-->
```

也可以在模板视觉设计需要时保留普通字符，例如：

```markdown
午饭：[<!--dc:f_lunch_time-->12:36<!--dc:/f_lunch_time-->]
```

在 Obsidian 等支持 HTML comment 的 Markdown 阅读器中，用户看到：

```text
午饭：[12:36]
```

要求：

- comment 中只保存协议标记 + field ID。
- 不保存字段名。
- 不保存类型。
- 不保存单位。
- 不复制 value 到 comment。
- value 永远以正常文本形式存在于 Markdown 正文。
- parser 必须验证 start/end field ID 一致。
- 不允许嵌套同一字段标记。
- 遇到损坏标记时尽量保留用户正文，不得为了“修复结构化数据”删除普通文本。

如果实现阶段认为成对 comment 对现有 Markdown parser 兼容性存在明确问题，可以采用等价、仍然保持普通 Markdown 可读且可无歧义恢复 value 的协议；但禁止回退到标准 Markdown `[value](field)` link 形式。

---

## 11. `time` 的 Markdown 与真实日期

绝对不要为了 Journal Day 把时间写成私有格式。

例如：

- Calendar Date：`2026-08-19`
- 真实时间：`02:37`
- Boundary：`05:00`
- Journal Day：`2026-08-18`

该记录写入：

```text
2026-08-18.md
```

正文仍然是：

```markdown
睡觉：[<!--dc:f_system_sleep_time-->02:37<!--dc:/f_system_sleep_time-->]
```

Obsidian 正常看到：

```text
睡觉：[02:37]
```

禁止写：

```text
26:37
02:37(+1)
2026-08-18 02:37
```

DeskCubby 内部需要把 Journal Day + time 恢复为可计算时间点时，统一使用该记录对应的**有效 Boundary**，而不是永远使用今天最新的设置：

```text
effectiveBoundary = getEffectiveDayBoundary(journalDay)
resolveFieldDateTime(journalDay, timeValue, effectiveBoundary)
```

规则：

```text
if timeValue < dayBoundary:
    actualDate = journalDay + 1 day
else:
    actualDate = journalDay

return actualDate + timeValue
```

例如：

```text
journalDay = 2026-08-18
time = 02:37
boundary = 05:00

→ actualDateTime = 2026-08-19 02:37
```

这套规则必须被：

- time 折线图
- 时间差计算
- 自定义统计
- Agent 数据工具

共同复用。

---

## 12. 历史数据与 Boundary 修改

修改 `dayBoundary` 后：

> **禁止自动移动、重写或重归档已有 Markdown。**

已有文件归属优先视为历史事实。

新的 Boundary 作用于：

- 当前/未来 Journal Day 的解析；
- 「进入今日日记」；
- 新记录写入目标；
- 新建日记；
- 新记录的时间解释；
- 当前时间图的展示边界。

同时必须把 Boundary 变更记录为新的 effective rule。旧记录在恢复实际 datetime、计算 `timeDiff` 时继续使用**该记录所属历史阶段的 Boundary**，不能拿最新 Boundary 重新解释，否则例如旧 `04:30` 记录可能被错误地从“次日凌晨”解释成“当日清晨”。

为避免“同一个 Journal Day 前半段按旧 Boundary、后半段按新 Boundary”导致 Markdown 无法仅凭 field ID 无歧义重建，V1 最简单可靠的规则是：**Boundary 修改从下一个 Journal Day 生效**。设置页可以明确提示“新的日界线从下一个日记日开始使用”。如果以后要支持立即生效，必须升级协议以保存足够的 per-occurrence 时间语义，不能只改一个全局字符串。

这也是为什么仅仅保存一个可变的 `dayBoundary` 字符串并不足以同时满足“可改 Boundary”和“Markdown metadata 只写 field ID”。Boundary history 是保持两者兼容的最低成本方案。

如果未来实现“按新日界线重新整理历史”，必须是：

- 显式操作
- 有预览
- 有 diff
- 可取消
- 可撤销

---

## 13. 本地结构化记录索引

**统计页面禁止每次全量扫描整个日记目录。**

实现本地 Room / SQLite 索引，例如：

```text
StructuredRecordOccurrence
- id
- journalDay
- sourceFile
- sourceFileModifiedAt / hash
- fieldId
- rawValue
- normalizedValue
- valueType
- orderInFile
- parsedAt
```

可根据现有数据库架构拆分为 `record` + `field_value` 表。

索引原则：

```text
Markdown + .deskcubby JSON = 可恢复源
Local DB = Derived Index / Cache
```

必须支持：

### 首次建立

```text
扫描日记 Markdown
→ 解析 dc field markers
→ 建立 index
```

### 平时增量更新

```text
新增结构化记录
→ 写 Markdown
→ 同时更新 index
```

外部编辑 Markdown 时：

```text
检测 mtime / size / hash 变化
→ 只重新解析变化文件
```

### 手动重建

提供：

```text
重建结构化记录索引
```

删除本地索引后，必须能够完全从：

```text
Markdown + fields.json + settings.json
```

恢复原始字段记录。

`statistics.json` 和 `records.json` 属于用户定义，也应保留，不属于可丢弃 cache。

---

## 13.1 日记设置页面重构

在现有「设置 → 日记设置」下新增两个明确的设置子页面：

```text
日记设置
├─ 吃历
└─ 结构化记录
```

不要把所有日记相关选项继续堆在一个长页面。

### 「吃历」设置子页面

承接所有只属于吃历 / 餐食日历的设置。具体已有选项沿用现有功能，不在本任务中无意义重构其数据模型。后续新增加的吃历专属设置也统一进入此页。

### 「结构化记录」设置子页面

至少包含：

```text
结构化记录
├─ 日界线 / 一天开始时间：05:00
├─ 自动记录睡觉/起床时间：开关
├─ 字段管理
├─ 模板管理
├─ 示例记录 / 添加预设示例
└─ 重建结构化记录索引
```

其中：

#### 自动记录睡觉/起床时间

使用一个主开关：

```text
自动记录睡觉/起床时间  [ Off / On ]
```

说明文案应明确：

```text
根据手机每天第一次/最后一次使用、解锁与锁屏时间自动估算，不使用 Health Connect。
```

建议默认关闭，首次开启时再请求实现该系统采集所必需的权限/后台能力，并清楚解释用途。

**这个开关是设备本地设置。** UI 虽位于「日记设置 → 结构化记录」，但状态应继续存入 Android DataStore / 对应平台本地设置，不写入 `.deskcubby/settings.json`、不跨设备同步。原因是不同手机拥有不同的系统交互事件，若同步开关会导致多个设备同时向同一工作区采集并竞争写入。

跨设备同步的是：

- 系统字段定义；
- 已结算后写入 Markdown 的最终睡觉/起床结构化值；
- 派生统计定义。

不跨设备同步的是：

- 是否在当前设备启用自动采集；
- 原始 lock/unlock 候选事件；
- 当前设备的采集权限与后台状态。

开启后：

- 创建或启用系统字段“睡觉时间”“起床时间”；
- 自动生成/更新最终结构化值；
- 统计页可以直接使用这两个 `time` 字段；
- 用户可进一步创建“睡眠时长”等 derived metric；
- 关闭开关只停止未来自动采集，不删除历史记录。

日界线的修改也放在这一子页面，因为它直接影响结构化记录的 Journal Day、跨日时间统计和「进入今日日记」。

---

## 14. 新增统计子页面：结构化记录统计

在现有：

```text
统计
```

下面新增独立子页面：

> **结构化记录统计**

它不是固定写死几个图，而是一个用户可配置的个人数据分析页面。

页面包含两类内容：

```text
A. 字段统计（自动）
B. 自定义统计 / 派生指标（用户创建）
```

---

## 15. 字段统计（自动）

DeskCubby 根据 field type 自动给出合理的默认统计。

### `number`

默认支持：

- 日/周/月折线图
- 柱状图
- sum
- average
- min
- max
- latest
- count

### `time`

默认支持：

- 每日时间折线图
- 平均时刻
- 最早 / 最晚
- 趋势

时间折线图必须按 `dayBoundary` unwrap，避免：

```text
23:40 → 00:20
```

画成 23 → 0 的巨大断崖。

内部可转换：

```text
23:40 → 23:40
00:20 → 24:20
01:10 → 25:10
```

但坐标标签仍显示正常：

```text
23:40
00:20
01:10
```

绝不向用户显示 `24:20` / `25:10`。

### `duration`

默认支持：

- 折线图
- 日/周/月总时长
- 平均时长
- 最长 / 最短

### `type`

默认支持：

- 次数排行
- 占比
- 日历分布
- 时间趋势
- 最常见值

### `word`

默认支持：

- 时间线
- 搜索
- 出现次数

V1 不强行对自由文本做 NLP 分类；Agent 可作为后续高级分析入口。

---

## 16. 自定义统计 / 派生指标

这是「结构化记录统计」的核心能力。

用户可以自己设计：

> **“我到底想统计什么？”**

一个统计指标不一定对应一个原始字段，可以由多个字段、不同 Journal Day 的字段计算得到。

例如：

```text
睡眠时长 = 起床时间 - 前一天的睡觉时间
```

用户应能够在 UI 中构造这个指标，而不是需要写代码。

---

## 17. 睡眠时长示例

字段：

```text
f_system_sleep_time = 睡觉时间 / time
f_system_wake_time  = 起床时间 / time
```

假设：

```text
2026-08-18.md
睡觉：00:37

2026-08-19.md
起床：08:12
```

Boundary：

```text
05:00
```

则：

```text
2026-08-18 + 00:37
→ actual = 2026-08-19 00:37

2026-08-19 + 08:12
→ actual = 2026-08-19 08:12
```

用户定义：

```text
睡眠时长(D)
= 起床时间(D)
- 睡觉时间(D - 1 Journal Day)
```

结果：

```text
07:35
```

图表的日期可以记在：

```text
2026-08-19
```

即“起床后的这一天”。

这个逻辑不是睡眠专属规则，而是通用：

```text
FieldRef + JournalDayOffset + TimeDiff
```

---

## 18. 自定义统计 UI

不要把普通用户直接扔进代码编辑器。

推荐创建流程：

```text
新建统计
↓
名称：睡眠时长
↓
结果类型：时长
↓
计算方式：时间差
↓
结束值：起床时间 / 当天 / 最后一次
↓
开始值：睡觉时间 / 前一天 / 最后一次
↓
显示：折线图
↓
保存
```

视觉上可以做成类似公式积木 / 条件构建器：

```text
[ 起床时间 ] [ 当天 ]
        −
[ 睡觉时间 ] [ 前一天 ]
        ↓
[ 睡眠时长 ]
```

用户无需知道 `dayOffset = -1`，UI 显示：

```text
当天
前一天
后一天
```

高级模式再允许输入更复杂表达式。

---

## 19. 统计表达式模型

禁止使用任意 JavaScript/Kotlin/Python `eval`。

使用结构化 AST。

睡眠时长示例：

```json
{
  "id": "m_sleep_duration",
  "name": "睡眠时长",
  "resultType": "duration",
  "expression": {
    "op": "timeDiff",
    "end": {
      "op": "fieldRef",
      "fieldId": "f_system_wake_time",
      "dayOffset": 0,
      "selector": "last"
    },
    "start": {
      "op": "fieldRef",
      "fieldId": "f_system_sleep_time",
      "dayOffset": -1,
      "selector": "last"
    }
  },
  "display": {
    "chart": "line",
    "period": "day"
  }
}
```

---

## 20. V1 支持的表达式能力

至少支持：

### `fieldRef`

```text
字段 + Journal Day offset + 同日多值选择策略
```

### `add`

数字/时长加法。

### `subtract`

数字/时长减法。

### `multiply`

数字乘法。

### `divide`

数字除法，必须处理除零。

### `timeDiff`

两个 time / datetime-like FieldRef 之间计算 duration。

### `constant`

常数。

建议 V1 AST 留出未来扩展：

```text
if
min
max
abs
round
count
coalesce
```

但不要为了未来一次性做过度复杂的公式语言。

---

## 21. 同一天同字段多次记录

这是必须定义的语义。

例如一天记录了三次体重，或三次“出门时间”。

`fieldRef` 支持 selector：

```text
first
last
min
max
sum
average
count
```

不同类型限制合理 selector：

- `time`：first / last / min / max
- `number`：first / last / min / max / sum / average / count
- `duration`：first / last / min / max / sum / average / count
- `type`：first / last / count；其他类别聚合放到图表层
- `word`：first / last / count

UI 默认：

```text
time     → last
number   → last
 duration → last
type     → last
word     → last
```

但统计创建时可修改。

---

## 22. `statistics.json`

示例：

```json
{
  "schemaVersion": 1,
  "metrics": [
    {
      "id": "m_sleep_duration",
      "name": "睡眠时长",
      "resultType": "duration",
      "expression": {
        "op": "timeDiff",
        "end": {
          "op": "fieldRef",
          "fieldId": "f_system_wake_time",
          "dayOffset": 0,
          "selector": "last"
        },
        "start": {
          "op": "fieldRef",
          "fieldId": "f_system_sleep_time",
          "dayOffset": -1,
          "selector": "last"
        }
      },
      "display": {
        "chart": "line",
        "period": "day"
      },
      "archived": false
    }
  ]
}
```

统计配置随日记目录同步；计算结果默认不需要写回 Markdown，也不需要永久写进 `statistics.json`。

派生结果可以缓存到本地 DB，加速显示，但必须可重新计算。

---

## 23. 更多自定义统计示例

### 每日力量训练量

```text
俯卧撑 + 深蹲 + 引体向上
```

### 跑步平均配速

如果未来有：

```text
跑步时长 / 跑步距离
```

可以得到派生速度/配速。

### 与昨天相比的体重变化

```text
体重(D) - 体重(D-1)
```

### 今日总饮水

```text
SUM(喝水量, D)
```

本质上由同日 selector = `sum` 实现。

### 起床时间趋势

直接对 `f_wake_time` 做 time field 折线图，不需要派生指标。

---

## 24. 缺失值规则

统计系统不得因为某一天缺字段而产生错误数据。

默认：

```text
任一必需输入缺失 → 该 Journal Day 结果 = null
```

图表：

- 默认显示断点，不自动当作 0。
- 用户可在未来高级设置选择忽略 / 前值填充等策略。

例如：

```text
有起床时间，但前一天没有睡觉时间
```

则睡眠时长：

```text
null
```

不是：

```text
0 小时
```

---

## 25. 单位系统

`number` 字段允许单位：

```text
次
kg
km
mL
元
kcal
```

单位属于 field definition，不需要每次重复写进 HTML comment。

普通 Markdown 模板仍可以把单位作为可见文本写出来：

```markdown
跑了 <!--dc:f_run_distance-->5.2<!--dc:/f_run_distance--> km
```

V1 不需要构建完整物理量单位换算引擎，但自定义计算必须检查明显不合理的组合：

```text
kg + km → 默认不允许
```

同单位数值可以相加减。

乘除的复杂单位推导可以后续扩展。

---

## 26. 时间图与 Journal Day Boundary

时间折线图的 Y 轴不是简单 `00:00 → 23:59`。

Boundary = `05:00` 时，可以内部使用：

```text
05:00 = 5:00
...
23:59 = 23:59
00:00 = 24:00
...
04:59 = 28:59
```

这只用于排序/绘图。

用户看到的 tick label 必须重新格式化为：

```text
23:00
00:00
01:00
02:00
```

而不是：

```text
23:00
24:00
25:00
26:00
```

---

## 27. 「进入今日日记」

它必须使用 Journal Day，而不是系统 Calendar Date。

例如：

```text
当前：2026-08-19 02:47
Boundary：05:00
```

点击：

```text
进入今日日记
```

必须进入：

```text
2026-08-18.md
```

05:00 后再进入：

```text
2026-08-19.md
```

这个规则同时用于从结构化记录快捷入口创建记录时选择目标日记。

---

## 28. Obsidian / 标准 Markdown 兼容原则

必须满足：

### 原始 `.md` 可读

即使完全不用 DeskCubby，用户也能读懂：

```markdown
## 今天

做了 20 个俯卧撑。
睡觉：[00:37]
穿了黑色卫衣。
```

HTML comment 只是隐藏 metadata。

### 不使用 `[value](field)`

因为标准 Markdown 会把它解释为 link。

### 不用奇怪的跨日时间

禁止：

```text
26:37
```

### `.deskcubby` 缺失不能让日记无法打开

如果用户只复制 Markdown 文件，没有复制 `.deskcubby`：

- 日记依旧完全可读。
- DeskCubby 允许将未知 field ID 显示为“未知字段”。
- 不得删除对应正文。

---

## 29. 迁移旧「日常事件」

迁移应尽量无损。

### UI 名称

```text
日常事件 → 结构化记录
```

### `xx`

旧模板中的：

```text
xx
```

迁移为 `word` placeholder 语义。

如果无法可靠自动命名字段：

- 生成稳定 field ID
- 默认显示名可使用“文字”“文字 2”等
- 允许用户之后改名

### 已经写入历史 Markdown 的旧普通文本

不要进行激进自动识别。

除非现有旧格式有 100% 可逆、明确的 pattern，否则：

> 历史普通文本继续作为普通文本存在。

新系统从升级后的结构化记录开始积累结构化字段。

如果未来提供“从历史 Markdown 提取结构化记录”，应作为独立导入工具，有预览和确认。

---

## 30. Agent 集成

Agent 不应该通过全量读取日记来统计结构化记录。

提供结构化工具，例如：

```text
listStructuredFields()
getStructuredFieldValues(fieldId, range)
getStructuredFieldStats(fieldId, range, aggregation)
listStructuredMetrics()
getStructuredMetric(metricId, range)
```

以及未来：

```text
createStructuredMetric(...)
updateStructuredMetric(...)
```

Agent 对用户说：

```text
“分析我最近一个月睡眠”
```

优先：

```text
读取字段/指标定义
→ 查询本地 index
→ 计算/读取睡眠时长 metric
```

而不是：

```text
一次性把一个月所有日记全文塞进上下文
```

---

## 31. 数据写入一致性

新增一条结构化记录时：

```text
1. 计算 Journal Day
2. 确定目标 Markdown
3. 根据模板获取用户输入
4. 生成可读 Markdown + dc field marker
5. 原子/安全地写入 Markdown
6. 成功后更新本地 index
```

如果 DB index 更新失败：

- Markdown 已写入的数据不能丢。
- 标记 index dirty。
- 后续重新解析该文件恢复索引。

如果 Markdown 写入失败：

- 不得只在 index 中留下不存在于 Markdown 的“幽灵记录”。

Markdown 永远优先。

---

## 32. 解析与性能

禁止在以下行为中每次全量扫全部 Markdown：

- 打开统计页
- 打开某字段图表
- Agent 查询
- 首页展示今日统计

正常查询必须走 index。

允许全量扫描的场景：

- 首次升级建立索引
- 用户手动点击“重建结构化记录索引”
- 数据库丢失/损坏后的恢复

外部编辑检测优先增量：

```text
path + mtime + size + hash（按需要）
```

只重新解析发生变化的 Markdown。

---

## 33. UI 建议

### 结构化记录主页

保留“快捷记录”的低摩擦体验：

```text
结构化记录

[ 今日一句话 ]
[ 俯卧撑次数 ]
[ 今天衣服颜色 ]
[ 午饭时间 ]
[ 午睡时长 ]
[ + 新建 ]

若开启系统自动记录，可另外显示非操作型状态卡：

[ 起床时间 · 自动 ]
[ 睡觉时间 · 暂定/已结算 ]
```

点击后只填写必要字段，不让用户面对 JSON / field ID / HTML comment。

### 字段管理

增加：

```text
结构化记录 → 字段管理
```

展示：

```text
字段名
类型
单位
使用模板数
历史记录数量
```

支持重命名和 archive。

### 统计入口

```text
统计
├─ 原有统计页面……
└─ 结构化记录统计
```

结构化记录统计顶部建议：

```text
[ + 添加统计 ]    [ 管理字段 ]
```

下面以可排序卡片展示：

```text
睡眠时长
起床时间
俯卧撑
体重
上衣分布
……
```

---

## 34. V1 范围控制

V1 必须完成：

- 「日常事件」→「结构化记录」更名
- word / number / type / time / duration 强类型语义
- 每种类型至少一个可编辑/可删除的预设示例
- 原始字段 `source = manual / system` 分层（agent 预留）；派生计算使用独立 MetricDefinition
- stable field ID
- `.deskcubby/settings.json`
- `.deskcubby/fields.json`
- 结构化记录模板持久化
- Markdown hidden field marker
- 默认 `dayBoundary = 05:00`
- 「进入今日日记」接入 Journal Day
- 日记设置下新增「吃历」「结构化记录」两个设置子页面
- 「结构化记录」设置内提供“自动记录睡觉/起床时间”开关
- 自动睡觉/起床仅使用手机首次/最后一次使用、解锁/锁屏事件，不接 Health Connect
- 本地增量 index
- 字段自动统计
- 「统计 → 结构化记录统计」
- 用户自定义 metric
- `fieldRef + dayOffset`
- 数值四则运算的安全子集
- `timeDiff`
- 睡眠时长示例可真正创建并正确计算
- index rebuild
- 迁移现有 `xx` placeholder

V1 不要求：

- event ID / record occurrence ID 写入 Markdown
- 完整 Excel 级公式语言
- 任意脚本
- AI 自动生成所有统计
- 复杂单位代数
- NLP 分析 word 字段
- 自动按新 Boundary 重排所有历史 Markdown

**Event ID 可以后续协议版本再增加，不要为了它阻塞 V1。**

---

## 35. 测试要求

至少覆盖：

### Journal Day

```text
Boundary 05:00
04:59 → 前一天
05:00 → 当天
```

### Markdown

```text
field marker round-trip
中文
emoji
多字 word/type
空格
标点
多字段同一行
同字段多次出现
损坏的 end marker
未知 field ID
```

### 字段

```text
字段重命名后历史统计不断
archive 后历史可读
unit 保留
```

### 增量索引

```text
新增记录立即查询可见
外部修改 1 个 md 只重建该文件
删除 md 后 index 对应记录删除
全量 rebuild 与增量结果一致
```

### 时间

```text
手动 [time] 打开/插入时默认填当前本地时间，且保存前可编辑
快捷直接记录 [time] → 使用触发瞬间当前时间
23:40 / 00:20 折线连续
journalDay + 00:37 + boundary 05:00 → 次日 00:37
修改 Boundary 后，旧记录仍按旧 effective Boundary 恢复真实 datetime
```

### 自定义 metric

重点验收：

```text
起床时间(D) - 睡觉时间(D-1)
```

例如：

```text
D-1 入睡 = 00:37
D 起床   = 08:12
Boundary = 05:00
```

结果必须是：

```text
07:35
```

而不是负数、31:35 或 0。

### 缺失值

```text
缺少 start 或 end → null
```

不得自动当 0。

### 字段强类型

```text
number 可直接求 sum / average
type 可直接分类计数与占比
time 可做平均时刻与 timeDiff
duration 可求总时长
word 不得误参与数值运算
```

### 预设示例

至少存在且类型正确：

```text
今日一句话 → word
俯卧撑次数 → number
今天衣服颜色 → type
午饭时间 → time
午睡时长 → duration
```

### 自动睡觉/起床

```text
自动记录关闭 → 不生成新系统字段值
自动记录开启 → 使用系统手机交互事件
一天内多次锁屏/解锁 → 不逐次写 Markdown，只更新候选并最终结算
关闭开关 → 历史记录保留
不得依赖 Health Connect
```

### 设置导航

```text
日记设置 → 吃历
日记设置 → 结构化记录
结构化记录设置 → 自动记录睡觉/起床时间
```

---

## 36. Definition of Done

本任务只有同时满足以下条件才算完成：

1. 用户界面已使用「结构化记录」命名。
2. 旧 `xx` 能迁移到 word 语义。
3. 用户能创建 number/type/time/word/duration 字段，并且它们拥有各自明确的校验、统计、图表和公式语义。
4. 默认示例至少覆盖：今日一句话 / 俯卧撑次数 / 今天衣服颜色 / 午饭时间 / 午睡时长。
5. 睡觉/起床不作为手动 time 示例，而是可选 system source。
6. 日记设置下存在「吃历」「结构化记录」两个设置子页面。
7. 「结构化记录」设置中存在“自动记录睡觉/起床时间”开关，且不使用 Health Connect。
8. 自动记录开关是设备本地状态，不同步到 `.deskcubby`；最终字段值才随 Markdown 同步。
9. 自动睡觉/起床只写最终/结算后的结构化值，不把每次锁屏解锁都写进 Markdown。
10. 字段拥有不可因重命名而变化的 stable ID。
11. 结构化值写进 Markdown 后，在 Obsidian 中仍是正常人类文本。
12. Markdown 私有 metadata 只引用 field ID，不重复存 type/name/unit/value。
13. `.deskcubby` 默认位于日记根目录。
14. `dayBoundary` 默认 05:00。
15. 凌晨 05:00 前「进入今日日记」进入前一个 Journal Day。
16. 统计查询正常情况下不全量扫描 Markdown。
17. 本地 index 可从 Markdown 重建。
18. 统计页面存在「结构化记录统计」子页面。
19. 用户能自由选择原始字段生成图表。
20. 用户能建立派生指标。
21. “当天起床时间 - 前一天睡觉时间 = 睡眠时长”可以通过通用公式系统实现，而不是硬编码睡眠特例。
22. 改字段名不破坏历史。
23. 改 Boundary 不静默搬迁历史 Markdown。
24. 原有 Android DataStore 设备设置不被粗暴迁入 `.deskcubby`。
25. 对解析失败、未知字段、缺失配置有安全降级。
26. 手动 `time` 字段默认自动填入当前时间，并允许用户在保存前修改；快捷直接记录可一键落当前时间。
27. Boundary 修改后有 effective history，历史 `time` 不被最新 Boundary 重新错误解释。
28. Field 与 Derived Metric 的数据模型边界清晰，睡眠时长默认是 Metric，不伪装成每天写回 Markdown 的原始字段。
29. 系统睡觉/起床字段更新同日已有 marker，不因重复结算不断追加重复记录。
30. 关键单元测试 / 集成测试通过。

---

# Part B：设计文档

## 1. 产品定位

「结构化记录」应该被理解为：

> **嵌在自然语言日记里的轻量个人数据库。**

它既不是传统打卡 App，也不是把日记变成数据库表格。

用户仍然可以写：

```markdown
晚上状态不错，做了 20 个俯卧撑，后来和朋友出去吃饭，00:37 才睡。
```

DeskCubby 只是让其中一部分值拥有机器可理解的身份：

```text
20    → f_pushups
00:37 → f_system_sleep_time
```

从而让同一份 Markdown 同时拥有：

```text
人类可读性
+
统计能力
+
跨设备可解释性
+
Agent 可查询性
```

---

## 2. 字段类型本身就是统计契约

结构化记录的五个 V1 类型不是五套外观，而是五种不同的数据语义：

```text
word      → 文本
number    → 数值
type      → 离散类别
time      → 一天中的时刻
duration  → 持续时长
```

因此“用户创建字段”这一刻，统计系统已经知道默认能力。例如：

```text
今天衣服颜色 / type
→ 自动出现分类次数、占比、最常见颜色、日历分布

俯卧撑次数 / number
→ 自动出现趋势、总和、平均、最大最小

午饭时间 / time
→ 手动记录时默认直接带入当前时间；自动出现每日时间折线、平均午饭时间、最早最晚

午睡时长 / duration
→ 自动出现平均午睡、总时长、趋势
```

所以用户不需要先“教统计页这是数字还是种类”；字段定义本身已经提供这个信息。

默认起步示例固定覆盖五种类型：

```text
word      今日一句话
number    俯卧撑次数
type      今天衣服颜色
time      午饭时间
duration  午睡时长
```

“睡觉时间 / 起床时间”不占用 `time` 的手动示例位置，因为它们属于可选的系统来源。

---

## 3. 字段类型与原始数据来源是两条轴

一个字段的 `type` 回答：

> 这个值是什么？

一个原始字段的 `source` 回答：

> 这个原始值怎么来的？

例如：

```text
午饭时间       = Field(time, manual)
睡觉时间       = Field(time, system)
起床时间       = Field(time, system)
午睡时长       = Field(duration, manual)
睡眠时长       = Metric(duration, derived)
```

这里必须坚持“字段 ≠ 统计指标”：睡眠时长默认是 `statistics.json` 中的派生 Metric，而不是一个每天写回 Markdown 的 Field。

这使统计引擎保持通用：无论睡觉时间是系统自动得到还是未来用户手动覆盖，它本质上仍是 `time`，仍然拥有相同的时间统计能力。

自动睡觉/起床只使用手机首次/最后一次使用、解锁/锁屏等系统行为估算，**不使用 Health Connect**。原始系统事件留在设备本地，最终结算值才写进 Markdown。

自动采集开关也必须是**设备本地设置**。它虽然出现在「日记设置 → 结构化记录」里，但不随 `.deskcubby` 同步，避免多个手机对同一日记工作区同时采集并发生冲突。

---

## 4. 日记设置的信息架构

日记设置进一步拆成两个业务子页面：

```text
设置
└─ 日记设置
   ├─ 吃历
   └─ 结构化记录
```

其中「结构化记录」承担结构化记录自己的所有配置入口：

```text
一天开始时间 / Day Boundary
自动记录睡觉/起床时间
字段管理
模板管理
预设示例
重建结构化记录索引
```

「吃历」则承接已有及未来只与餐食日历有关的设置。这样“日记设置”作为一级概念保持稳定，但业务设置不会继续堆成一个大页面。

---

## 5. 为什么不直接每次扫 Markdown

Markdown 规模小时，全量扫描很快；但长期使用后可能出现：

- 数千篇日记
- 大量小文件
- Android SAF 文件访问
- 网络/同步目录
- 多种 Markdown 内容

瓶颈通常不是一个正则本身，而是：

```text
枚举文件
→ 打开大量文件
→ 读取
→ 解析
→ 聚合
```

因此采用：

```text
首次/恢复：全量扫描
日常使用：增量解析 + DB index
统计查询：直接查 index
```

这样 10 年数据和 1 个月数据在“打开统计页”的体验上不会线性恶化。

---

## 6. 为什么 field definition 独立于 Markdown

如果写成：

```text
20(number, 俯卧撑, 次)
```

每次都重复 schema，会产生：

- Markdown 污染
- 字段改名需要批量改历史
- 单位变更复杂
- 解析协议冗余

所以 Markdown 只保留：

```text
value + fieldId
```

而：

```text
fieldId → name/type/unit/options
```

统一在 `fields.json` 中解析。

这相当于：

```text
Markdown = 数据行
fields.json = schema registry
```

---

## 7. 为什么自定义统计独立于字段

字段是“我记录了什么”；统计指标是“我想从这些记录中知道什么”。

二者不能绑定死。

例如原始字段只有：

```text
睡觉时间
time

起床时间
time
```

用户想知道的是：

```text
睡眠时长
```

这个值不一定需要每天重复手填，它完全可以计算得到。

因此系统形成：

```text
Raw Fields
   ↓
Derived Metrics
   ↓
Charts / Cards / Agent
```

这是「结构化记录统计」最重要的设计。

---

## 8. Journal Day 是附加时间语义，不是系统主体

Journal Day 的作用是解决：

> 人的“一天”经常不是午夜 00:00 切换。

例如凌晨 02:00 入睡，用户通常仍然把它视为“昨天最后发生的事情”。

默认 Boundary = `05:00`，使：

```text
8/19 02:00
```

写进：

```text
8/18.md
```

但真实 Markdown 时间仍是：

```text
02:00
```

所以 Journal Day 只是结构化记录和日记系统共享的一项底层时间规则，不应喧宾夺主。

---

## 9. 推荐最终结构

```text
                         .deskcubby/
                  ┌─────────┼──────────┐
                  │         │          │
              fields    records   statistics
                  │         │          │
                  └────┬────┘          │
                       ▼               │
               Structured Record       │
                 ▲      ▲              │
                 │      │              │
              manual  system           │
                 │   phone events      │
                 └──┬───┘              │
                    ▼                   │
                   Markdown            │
                       │               │
                       ▼               │
               Incremental Parser      │
                       │               │
                       ▼               │
                Local Room Index ◄─────┘
                       │
              ┌────────┼─────────┐
              ▼        ▼         ▼
            Charts   Metrics    Agent

settings.json
     │
     └─ dayBoundary = 05:00
          ├─ Journal Day
          ├─ 进入今日日记
          ├─ 新记录归属
          ├─ time 图表
          └─ timeDiff
```

---

## 10. 后续可自然扩展的能力

本设计以后可以无痛扩展：

```text
rating
boolean/check
date
location
money
multi-type
```

也可以增加：

- Event / occurrence ID
- 结构化记录之间的关系
- 条件公式
- 指标之间互相引用
- 统计 Dashboard
- Agent 自动建议 metric
- 多字段相关性
- “睡眠时长 vs 第二天心情”分析
- “晚睡时第二天运动量是否下降”分析

关键是 V1 先把：

```text
stable field ID
强类型 field semantics
type/source 分离
Markdown protocol
incremental index
Journal Day
Derived Metric AST
```

这些底座做对。

---

# 一句话架构原则

> **DeskCubby 结构化记录 = 强类型字段定义“这个值是什么” + source 定义“这个值怎么来” + 正常 Markdown 保存用户最终数据 + `.deskcubby` 保存 schema 与统计定义 + stable field ID 连接二者 + 本地增量索引负责性能 + Journal Day 负责人的一天 + Derived Metrics 把“记录什么”升级为“我能从生活数据中算出什么”。**
