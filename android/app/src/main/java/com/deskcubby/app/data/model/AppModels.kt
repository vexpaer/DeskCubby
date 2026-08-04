package com.deskcubby.app.data.model

enum class VisualStyle { MATERIAL, LIQUID_GLASS, ORGANIC_FUTURE }

enum class DarkMode { SYSTEM, LIGHT, DARK }

enum class BrowserTheme { SYSTEM, LIGHT, DARK }

enum class AppLanguage { CHINESE, ENGLISH }

enum class MusicVisualizerStyle { BARS, WAVEFORM, CURVE }

enum class Game2048AnimationSpeed { SLOW, NORMAL, FAST }

enum class LauncherIcon { CURRENT, MAGIC_BOOK, DESK_CUBBY }

enum class ThoughtReopenMode { LAST_VISITED, ALL }

enum class ThoughtDisplayMode { SINGLE_LINE, FULL }

enum class MealPhotosPerRow { TWO, THREE, SMART }

enum class PoetryTextAlignment { START, CENTER }

enum class DesktopWidgetContentType { HOME_MODULE, APP_SHORTCUT }

val DESKTOP_WIDGET_HOME_MODULE_IDS: List<String> = listOf(
    "calendar",
    "weather",
    "poem",
    "today",
    "date_records",
    "streak",
    "month_diaries",
    "total_words",
    "recent_diary",
    "recent_thought",
    "quick_input",
    "daily_records",
    "meal_photos",
    "random_diary",
    "year_progress",
    "website",
)

const val MIN_DESKTOP_WIDGET_CELLS: Int = 1
const val MAX_DESKTOP_WIDGET_CELLS: Int = 6

/** A reusable design that can be bound to one or more launcher App Widget instances. */
data class DesktopWidgetConfig(
    val id: String,
    val name: String,
    val widthCells: Int = 2,
    val heightCells: Int = 2,
    val backgroundColorArgb: Int = 0xFF263238.toInt(),
    val textColorArgb: Int = 0xFFFFFFFF.toInt(),
    val backgroundImageUri: String? = null,
    val contentType: DesktopWidgetContentType = DesktopWidgetContentType.HOME_MODULE,
    val homeModuleId: String = "today",
    val appPackageName: String? = null,
    val appLabel: String? = null,
)

val DEFAULT_DESKTOP_WIDGET_CONFIGS: List<DesktopWidgetConfig> = listOf(
    DesktopWidgetConfig(
        id = "default-today",
        name = "今天 / Today",
    ),
)

/**
 * Row sizes for wrapped meal-calendar photos. SMART mixes rows of 3 and 2 so the
 * last row is never left with a single dangling photo (4=2+2, 5=3+2, 7=3+2+2).
 */
fun mealPhotoRowSizes(count: Int, mode: MealPhotosPerRow): List<Int> {
    if (count <= 0) return emptyList()
    return when (mode) {
        MealPhotosPerRow.TWO -> List(count / 2) { 2 } + if (count % 2 == 1) listOf(1) else emptyList()
        MealPhotosPerRow.THREE -> List(count / 3) { 3 } + if (count % 3 != 0) listOf(count % 3) else emptyList()
        MealPhotosPerRow.SMART -> when {
            count == 1 -> listOf(1)
            count % 3 == 0 -> List(count / 3) { 3 }
            count % 3 == 1 -> List((count - 4) / 3) { 3 } + listOf(2, 2)
            else -> List(count / 3) { 3 } + listOf(2)
        }
    }
}

enum class AiModelType { TEXT, IMAGE }

data class AiModelConfig(
    val id: String,
    val name: String,
    val type: AiModelType,
    val endpointUrl: String,
    val model: String,
    val enabled: Boolean = true,
    val allowInsecureHttp: Boolean = false,
    val temperature: Float = 0.7f,
    val systemPrompt: String = "",
    /** Plain-text API key persisted together with the rest of this configuration. */
    val apiKey: String = "",
)

enum class MealCategory(
    val key: String,
    val chineseLabel: String,
    val englishLabel: String,
    val defaultIcon: String,
    val sortOrder: Int,
) {
    BREAKFAST("breakfast", "早餐", "Breakfast", "🥪", 0),
    LUNCH("lunch", "午餐", "Lunch", "🍱", 1),
    AFTERNOON_TEA("afternoon_tea", "下午茶", "Afternoon tea", "🍹", 2),
    DINNER("dinner", "晚餐", "Dinner", "🍜", 3),
    FRUIT("fruit", "水果", "Fruit", "🍊", 4),
    LATE_SNACK("late_snack", "夜宵", "Late snack", "🍤", 5),
}

data class DailyEventTemplate(
    val id: String,
    val text: String,
    val firstUnit: String = "",
    val secondUnit: String = "",
)

data class RssSubscription(
    val id: String,
    val title: String,
    val url: String,
    val enabled: Boolean = true,
)

val DEFAULT_MEAL_BUTTON_ICONS: List<String> = MealCategory.entries.map(MealCategory::defaultIcon)

const val DEFAULT_CALORIE_VISION_PROMPT: String = """你是谨慎的餐食视觉记录助手。识别图片中所有可食用食物和饮料，按主食、蛋白质、蔬菜、水果、酱汁/油和饮料等实际组成拆分；估计可食用部分的数值分量与单位，餐具和装饰不要算作食物，同一食物不要重复列出。只返回 JSON，不要 Markdown：{"foods":[{"name":"食物名称","amount":"估计数值或范围","unit":"g、ml、个或份","confidence":0.0}],"sceneNotes":"烹饪方式、遮挡和份量不确定性"}。看不清时给出保守的合理范围并降低 confidence，不要虚构无法从图片推断的品牌或配方。"""
const val DEFAULT_CALORIE_TEXT_PROMPT: String = """你是谨慎的营养能量估算助手。根据随后 JSON 中的 recognizedFoods、visionNotes 和可选 userNote，结合可食用分量、常见烹饪方式、可见油脂/酱汁与饮料估算能量；用户备注可用于判断多人分享、同一餐多角度拍摄、剩余比例或实际分量。避免重复计算，并在证据不足时采用中性的合理估值。只返回 JSON，不要 Markdown：{"energyKj":整数,"foods":[{"name":"食物名称","amount":"分量","unit":"单位","energyKj":整数}]}。所有能量使用千焦(kJ)，保留每种食物，各项之和应与总能量在合理舍入范围内一致。"""

const val DEFAULT_THEME_COLOR_ARGB: Int = 0xFF42664D.toInt()
val DEFAULT_THEME_SECONDARY_COLORS_ARGB: List<Int> = listOf(
    0xFFC96F4A.toInt(),
    0xFFD4A72C.toInt(),
    0xFF527F91.toInt(),
)
const val MIN_THEME_SECONDARY_COLOR_COUNT: Int = 2
const val MAX_THEME_SECONDARY_COLOR_COUNT: Int = 5
const val MIN_APP_FONT_SCALE: Float = 0.8f
const val MAX_APP_FONT_SCALE: Float = 1.3f
const val DEFAULT_THOUGHT_HIGHLIGHT_COLOR_ARGB: Int = 0xFFF6E3A1.toInt()
const val MIN_THOUGHT_EDITOR_MAX_HEIGHT_DP: Int = 96
const val MAX_THOUGHT_EDITOR_MAX_HEIGHT_DP: Int = 400
const val DEFAULT_THOUGHT_EDITOR_MAX_HEIGHT_DP: Int = 168
const val MIN_VAULT_ROW_HEIGHT_DP: Int = 48
const val MAX_VAULT_ROW_HEIGHT_DP: Int = 120
const val DEFAULT_VAULT_ROW_HEIGHT_DP: Int = 56
const val MIN_POETRY_FONT_SIZE_SP: Float = 14f
const val MAX_POETRY_FONT_SIZE_SP: Float = 36f
const val MIN_POETRY_LINE_SPACING: Float = 1f
const val MAX_POETRY_LINE_SPACING: Float = 2f

enum class NavItemId(
    val route: String,
    val defaultLabel: String,
    val englishLabel: String,
    val defaultIcon: String,
    val defaultDescription: String,
    val englishDescription: String,
    val defaultVisible: Boolean = true,
    val defaultShowInMore: Boolean = false,
) {
    HOME("home", "首页", "Home", "home", "今日概览与快捷记录", "Overview and quick capture"),
    DIARY("diary", "日记", "Diary", "book", "浏览、编辑日记与吃历", "Diaries and meal calendar"),
    BLOG(
        "blog",
        "浏览器",
        "Browser",
        "language",
        "在应用内浏览网页",
        "Browse the web in the app",
        defaultVisible = false,
        defaultShowInMore = true,
    ),
    THOUGHT(
        "thought",
        "小巧思",
        "Thoughts",
        "bolt",
        "记录与整理瞬间想法",
        "Capture and organize thoughts",
    ),
    DATE(
        "date_records",
        "日期记录",
        "Dates",
        "event",
        "追踪纪念日与目标日期",
        "Track occasions and target dates",
        defaultVisible = false,
        defaultShowInMore = true,
    ),
    POETRY(
        "poetry_book",
        "诗词本",
        "Poetry book",
        "poetry",
        "收藏喜欢的诗词",
        "Keep your favorite poems",
        defaultVisible = false,
        defaultShowInMore = true,
    ),
    RSS(
        "rss",
        "RSS 订阅",
        "RSS",
        "rss",
        "阅读订阅源的最新文章",
        "Read the latest from your feeds",
        defaultVisible = false,
        defaultShowInMore = true,
    ),
    AI_CHAT(
        "ai_chat",
        "AI 聊天",
        "AI chat",
        "ai",
        "选择本机记录作为上下文并与模型分析",
        "Analyze selected local records with AI",
        defaultVisible = false,
        defaultShowInMore = true,
    ),
    VAULT(
        "vault",
        "收藏夹",
        "Vault",
        "lock",
        "密码保护的私密收藏",
        "Password-protected private notes",
        defaultVisible = false,
        defaultShowInMore = true,
    ),
    READER(
        "reader",
        "阅读",
        "Reader",
        "reader",
        "导入并阅读 TXT/PDF 小说",
        "Import and read TXT/PDF books",
        defaultVisible = false,
        defaultShowInMore = true,
    ),
    GAMES(
        "games",
        "小游戏",
        "Games",
        "game",
        "2048、贪吃蛇、俄罗斯方块、扫雷与蜘蛛纸牌",
        "2048, Snake, Tetris, Minesweeper, and Spider Solitaire",
        defaultVisible = false,
        defaultShowInMore = true,
    ),
    USAGE(
        "usage_statistics",
        "手机使用时间",
        "Screen time",
        "usage",
        "按天查看各应用的使用时长",
        "Daily usage time by app",
        defaultVisible = false,
        defaultShowInMore = true,
    ),
    STEPS(
        "step_statistics",
        "健康",
        "Health",
        "steps",
        "读取并可视化每日步数、距离和活动热量",
        "Chart daily steps, distance, and active calories",
        defaultVisible = false,
        defaultShowInMore = true,
    ),
    WIDGETS(
        "desktop_widgets",
        "小卡片",
        "Widgets",
        "widgets",
        "设计并添加可缩放的桌面小卡片",
        "Design and add resizable home-screen widgets",
        defaultVisible = false,
        defaultShowInMore = true,
    ),
    MORE(
        "more",
        "导航",
        "More",
        "apps",
        "打开收纳的页面",
        "Open collected pages",
        defaultVisible = false,
    ),
    SETTINGS(
        "settings",
        "设置",
        "Settings",
        "settings",
        "调整应用与页面设置",
        "Adjust app and page settings",
    ),
}

data class NavItemConfig(
    val id: NavItemId,
    val label: String = id.defaultLabel,
    val iconKey: String = id.defaultIcon,
    val visible: Boolean = id.defaultVisible,
    val showInMore: Boolean = id.defaultShowInMore,
    val moreDescription: String = id.defaultDescription,
)

val MORE_PAGE_ORDERABLE_IDS: List<NavItemId> = NavItemId.entries.filter { id ->
    id != NavItemId.HOME && id != NavItemId.MORE && id != NavItemId.SETTINGS
}

/**
 * Keeps a user-defined More-page order complete and forward-compatible.
 *
 * Missing items first inherit their relative order from [navItems], which is the migration path
 * for v15 backups and DataStore values created before More had an independent order.
 */
fun normalizeMorePageOrder(
    order: Iterable<NavItemId>,
    navItems: List<NavItemConfig>,
): List<NavItemId> = buildList {
    val eligible = MORE_PAGE_ORDERABLE_IDS.toSet()
    fun appendIfEligible(id: NavItemId) {
        if (id in eligible && id !in this) add(id)
    }
    order.forEach(::appendIfEligible)
    navItems.forEach { appendIfEligible(it.id) }
    MORE_PAGE_ORDERABLE_IDS.forEach(::appendIfEligible)
}

data class HomeGreetingTemplate(
    val chinese: String,
    val english: String,
)

val DEFAULT_HOME_GREETINGS: List<HomeGreetingTemplate> = listOf(
    HomeGreetingTemplate("今天从这里开始", "Start here today"),
    HomeGreetingTemplate("看看今天的安排", "Check today's plan"),
    HomeGreetingTemplate("有想法就记下来", "Write down what's on your mind"),
    HomeGreetingTemplate("先完成一件小事", "Start with one small task"),
    HomeGreetingTemplate("今天想写点什么？", "What would you like to write today?"),
    HomeGreetingTemplate("看看最近的记录", "Review your recent notes"),
    HomeGreetingTemplate("先处理重要的事", "Start with what matters"),
    HomeGreetingTemplate("打开日历看看", "Take a look at the calendar"),
    HomeGreetingTemplate("记录一下当前状态", "Record where things stand"),
    HomeGreetingTemplate("今天的进度怎么样？", "How is today going?"),
    HomeGreetingTemplate("先快速记一条", "Add a quick note"),
    HomeGreetingTemplate("看看时间都去哪了", "See where the time went"),
    HomeGreetingTemplate("今天走了多少步？", "How many steps today?"),
    HomeGreetingTemplate("查看新的订阅", "Check the latest feeds"),
    HomeGreetingTemplate("整理一下当前思路", "Organize your current thoughts"),
    HomeGreetingTemplate("从最简单的事开始", "Begin with the simplest thing"),
    HomeGreetingTemplate("该记录今天了", "Time to record today"),
    HomeGreetingTemplate("翻翻过去写的内容", "Browse something you wrote before"),
    HomeGreetingTemplate("现在要做什么？", "What comes next?"),
    HomeGreetingTemplate("先看一眼今日数据", "Check today's numbers"),
    HomeGreetingTemplate("把刚才的想法留下", "Keep that thought before it slips away"),
    HomeGreetingTemplate("今天也按计划推进", "Keep today's plan moving"),
    HomeGreetingTemplate("检查一下重要日期", "Check the important dates"),
    HomeGreetingTemplate("{name}，欢迎回来", "Welcome back, {name}"),
)

data class AppSettings(
    val visualStyle: VisualStyle = VisualStyle.MATERIAL,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val appLanguage: AppLanguage = AppLanguage.CHINESE,
    val userName: String = "",
    val homeGreetings: List<HomeGreetingTemplate> = DEFAULT_HOME_GREETINGS,
    val themeColorArgb: Int = DEFAULT_THEME_COLOR_ARGB,
    val themeSecondaryColorsArgb: List<Int> = DEFAULT_THEME_SECONDARY_COLORS_ARGB,
    val fontScale: Float = 1f,
    val compactMode: Boolean = false,
    val useChineseLauncherName: Boolean = false,
    val launcherIcon: LauncherIcon = LauncherIcon.CURRENT,
    val backupTreeUri: String? = null,
    val cloudSyncEnabled: Boolean = false,
    val cloudSyncConfigs: List<CloudSyncConfig> = emptyList(),
    val diaryTreeUri: String? = null,
    val mediaTreeUri: String? = null,
    val fileNamePattern: String = "yyyy-MM-dd",
    val markdownTemplate: String = "# {title}\n\n",
    val imageNamePattern: String = "{date}_{category}_{seq}",
    val imageMaxWidthDp: Int = 720,
    val imageMaxHeightDp: Int = 640,
    val mealImageCompressionEnabled: Boolean = true,
    val mealImageCompressionQuality: Int = 80,
    val saveOriginalToGallery: Boolean = false,
    val photoLocationEnabled: Boolean = false,
    val browserHomeUrl: String = "https://www.google.com",
    val lastBrowserUrl: String? = null,
    val browserTheme: BrowserTheme = BrowserTheme.SYSTEM,
    val browserDesktopMode: Boolean = false,
    val thoughtSplitRatio: Float = 0.58f,
    val thoughtRowHeightDp: Int = 56,
    val thoughtReopenMode: ThoughtReopenMode = ThoughtReopenMode.ALL,
    val lastThoughtPageKey: String = "all",
    val thoughtDisplayMode: ThoughtDisplayMode = ThoughtDisplayMode.SINGLE_LINE,
    val thoughtHighlightColorArgb: Int = DEFAULT_THOUGHT_HIGHLIGHT_COLOR_ARGB,
    val thoughtEditorMaxHeightDp: Int = DEFAULT_THOUGHT_EDITOR_MAX_HEIGHT_DP,
    val vaultRowHeightDp: Int = DEFAULT_VAULT_ROW_HEIGHT_DP,
    val poetryFontUri: String? = null,
    val poetryFontSizeSp: Float = 18f,
    val poetryLineSpacing: Float = 1.45f,
    val poetryTextAlignment: PoetryTextAlignment = PoetryTextAlignment.START,
    val poetryShowSource: Boolean = true,
    val poetryShowQuoteMark: Boolean = true,
    val poetrySevenCharacterWrapEnabled: Boolean = false,
    val mealCalendarImageMaxHeightDp: Int = 124,
    val mealCalendarShowCaptions: Boolean = true,
    val mealCalendarWrapEnabled: Boolean = false,
    val mealCalendarPhotosPerRow: MealPhotosPerRow = MealPhotosPerRow.SMART,
    val mealPhotoFilter: MealPhotoFilterSettings = MealPhotoFilterSettings(),
    val mealButtonsUseIcons: Boolean = false,
    val mealButtonIcons: List<String> = DEFAULT_MEAL_BUTTON_ICONS,
    val dailyEventTemplates: List<DailyEventTemplate> = emptyList(),
    val rssSubscriptions: List<RssSubscription> = emptyList(),
    val rssMaxItemsPerFeed: Int = 50,
    val rssShowSummaries: Boolean = true,
    val aiEndpointUrl: String = "https://api.openai.com/v1/chat/completions",
    val aiModel: String = "",
    val aiSystemPrompt: String = "你是一个有帮助的助手。",
    val aiTemperature: Float = 0.7f,
    val aiAllowInsecureHttp: Boolean = false,
    val aiConfigs: List<AiModelConfig> = emptyList(),
    val aiChatConfigId: String? = null,
    val calorieEstimationEnabled: Boolean = false,
    val calorieTextConfigId: String? = null,
    val calorieImageConfigId: String? = null,
    val calorieVisionPrompt: String = DEFAULT_CALORIE_VISION_PROMPT,
    val calorieTextPrompt: String = DEFAULT_CALORIE_TEXT_PROMPT,
    val usageTrackingEnabled: Boolean = false,
    val stepTrackingEnabled: Boolean = false,
    val navigationIntroAcknowledged: Boolean = false,
    val navItems: List<NavItemConfig> = NavItemId.entries.map { id ->
        NavItemConfig(id = id, visible = id.defaultVisible || id == NavItemId.MORE)
    },
    val morePageOrder: List<NavItemId> = normalizeMorePageOrder(emptyList(), navItems),
    val defaultPage: NavItemId = NavItemId.HOME,
    val bottomNavShowLabels: Boolean = true,
    val musicVisualizerEnabled: Boolean = false,
    val musicVisualizerStyle: MusicVisualizerStyle = MusicVisualizerStyle.BARS,
    val game2048AnimationSpeed: Game2048AnimationSpeed = Game2048AnimationSpeed.NORMAL,
    val morePageShowDescriptions: Boolean = true,
    val homeWidgetBordersEnabled: Boolean = true,
    // First-launch preset: a deliberately small home page. Everything else stays
    // available in the settings catalog.
    val homeWidgets: List<String> = listOf(
        "today",
        "poem",
        "quick_input",
        "meal_photos",
        "year_progress",
    ),
    val homeWidgetTitles: List<String> = listOf(
        "calendar",
        "weather",
        "poem",
        "today",
        "streak",
        "month_diaries",
        "total_words",
        "recent_diary",
        "recent_thought",
        "date_records",
        "quick_input",
        "daily_records",
        "meal_photos",
        "random_diary",
        "year_progress",
        "website",
    ),
    val desktopWidgetConfigs: List<DesktopWidgetConfig> = DEFAULT_DESKTOP_WIDGET_CONFIGS,
)

data class DiaryDocument(
    val uri: String,
    val name: String,
    val title: String,
    val dateIso: String,
    val monthKey: String,
    val lastModified: Long,
    val size: Long,
    val wordCount: Int,
)

data class DiaryEditorDocument(
    val uri: String,
    val name: String,
    val content: String,
    val lastModified: Long,
    val size: Long,
    val sha256: String,
)

data class DiaryTrashItem(
    val uri: String,
    val originalName: String,
    val deletedAt: Long,
)

data class ImportedMedia(
    val documentUri: String,
    val fileName: String,
    val markdown: String,
)
