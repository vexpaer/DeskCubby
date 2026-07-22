package com.deskcubby.app.data.model

enum class VisualStyle { MATERIAL, LIQUID_GLASS, ORGANIC_FUTURE }

enum class DarkMode { SYSTEM, LIGHT, DARK }

enum class BrowserTheme { SYSTEM, LIGHT, DARK }

enum class AppLanguage { CHINESE, ENGLISH }

enum class ThoughtReopenMode { LAST_VISITED, ALL }

enum class ThoughtDisplayMode { SINGLE_LINE, FULL }

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

const val DEFAULT_CALORIE_VISION_PROMPT: String = """识别图片中的所有食物和饮料。只返回 JSON，不要 Markdown：{"foods":[{"name":"食物名称","amount":"估计份量","unit":"单位","confidence":0.0}],"notes":"必要说明"}。无法确定时给出合理估计并降低 confidence。"""
const val DEFAULT_CALORIE_TEXT_PROMPT: String = """根据随后提供的食物识别 JSON，估算整张图片中食物的总能量。只返回 JSON，不要 Markdown：{"energyKj":整数}。energyKj 使用千焦(kJ)，综合份量并避免重复计算。"""

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

enum class NavItemId(
    val route: String,
    val defaultLabel: String,
    val englishLabel: String,
    val defaultIcon: String,
    val defaultVisible: Boolean = true,
) {
    HOME("home", "首页", "Home", "home"),
    DIARY("diary", "日记", "Diary", "book"),
    BLOG("blog", "浏览器", "Browser", "language"),
    THOUGHT("thought", "小巧思", "Thoughts", "bolt"),
    DATE("date_records", "日期记录", "Dates", "event"),
    POETRY("poetry_book", "诗词本", "Poetry book", "poetry"),
    RSS("rss", "RSS 订阅", "RSS", "rss", defaultVisible = false),
    AI_CHAT("ai_chat", "AI 聊天", "AI chat", "ai", defaultVisible = false),
    SETTINGS("settings", "设置", "Settings", "settings"),
}

data class NavItemConfig(
    val id: NavItemId,
    val label: String = id.defaultLabel,
    val iconKey: String = id.defaultIcon,
    val visible: Boolean = id.defaultVisible,
)

data class AppSettings(
    val visualStyle: VisualStyle = VisualStyle.MATERIAL,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val appLanguage: AppLanguage = AppLanguage.CHINESE,
    val userName: String = "",
    val themeColorArgb: Int = DEFAULT_THEME_COLOR_ARGB,
    val themeSecondaryColorsArgb: List<Int> = DEFAULT_THEME_SECONDARY_COLORS_ARGB,
    val fontScale: Float = 1f,
    val backupTreeUri: String? = null,
    val diaryTreeUri: String? = null,
    val mediaTreeUri: String? = null,
    val fileNamePattern: String = "yyyy-MM-dd",
    val markdownTemplate: String = "# {title}\n\n",
    val imageNamePattern: String = "{date}_{category}_{seq}",
    val imageMaxWidthDp: Int = 720,
    val imageMaxHeightDp: Int = 640,
    val mealImageCompressionEnabled: Boolean = true,
    val mealImageCompressionQuality: Int = 80,
    val browserHomeUrl: String = "https://www.google.com",
    val lastBrowserUrl: String? = null,
    val browserTheme: BrowserTheme = BrowserTheme.SYSTEM,
    val browserDesktopMode: Boolean = false,
    val thoughtSplitRatio: Float = 0.58f,
    val thoughtRowHeightDp: Int = 56,
    val thoughtReopenMode: ThoughtReopenMode = ThoughtReopenMode.ALL,
    val lastThoughtPageKey: String = "all",
    val thoughtDisplayMode: ThoughtDisplayMode = ThoughtDisplayMode.SINGLE_LINE,
    val mealCalendarImageMaxHeightDp: Int = 124,
    val mealCalendarShowCaptions: Boolean = true,
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
    val navigationIntroAcknowledged: Boolean = false,
    val navItems: List<NavItemConfig> = NavItemId.entries.map(::NavItemConfig),
    val defaultPage: NavItemId = NavItemId.HOME,
    val bottomNavShowLabels: Boolean = true,
    val homeWidgetBordersEnabled: Boolean = true,
    val homeWidgets: List<String> = listOf(
        "today",
        "poem",
        "year_progress",
        "month_diaries",
        "recent_diary",
        "recent_thought",
        "date_records",
        "quick_input",
        "daily_records",
        "meal_photos",
        "website",
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
