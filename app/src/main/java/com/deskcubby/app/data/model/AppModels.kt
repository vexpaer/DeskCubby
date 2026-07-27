package com.deskcubby.app.data.model

enum class VisualStyle { MATERIAL, LIQUID_GLASS, ORGANIC_FUTURE }

enum class DarkMode { SYSTEM, LIGHT, DARK }

enum class BrowserTheme { SYSTEM, LIGHT, DARK }

enum class AppLanguage { CHINESE, ENGLISH }

enum class LauncherIcon { CURRENT, MAGIC_BOOK }

enum class ThoughtReopenMode { LAST_VISITED, ALL }

enum class ThoughtDisplayMode { SINGLE_LINE, FULL }

enum class MealPhotosPerRow { TWO, THREE, SMART }

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
const val DEFAULT_THOUGHT_HIGHLIGHT_COLOR_ARGB: Int = 0xFFF6E3A1.toInt()
const val MIN_THOUGHT_EDITOR_MAX_HEIGHT_DP: Int = 96
const val MAX_THOUGHT_EDITOR_MAX_HEIGHT_DP: Int = 400
const val DEFAULT_THOUGHT_EDITOR_MAX_HEIGHT_DP: Int = 168

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
    GAMES(
        "games",
        "小游戏",
        "Games",
        "game",
        "2048、贪吃蛇与俄罗斯方块",
        "2048, Snake and Tetris",
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
        "步数记录",
        "Steps",
        "steps",
        "自动读取并可视化每日步数",
        "Automatically chart daily steps",
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

data class AppSettings(
    val visualStyle: VisualStyle = VisualStyle.MATERIAL,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val appLanguage: AppLanguage = AppLanguage.CHINESE,
    val userName: String = "",
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
    val defaultPage: NavItemId = NavItemId.HOME,
    val bottomNavShowLabels: Boolean = true,
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
