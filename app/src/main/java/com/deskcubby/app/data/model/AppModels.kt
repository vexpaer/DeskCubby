package com.deskcubby.app.data.model

enum class VisualStyle { MATERIAL, LIQUID_GLASS }

enum class DarkMode { SYSTEM, LIGHT, DARK }

enum class BrowserTheme { SYSTEM, LIGHT, DARK }

enum class AppLanguage { CHINESE, ENGLISH }

val DEFAULT_MEAL_BUTTON_ICONS: List<String> = listOf("🍳", "🥗", "🍚", "🍎", "🌙")

enum class NavItemId(val route: String, val defaultLabel: String, val englishLabel: String, val defaultIcon: String) {
    HOME("home", "首页", "Home", "home"),
    DIARY("diary", "日记", "Diary", "book"),
    BLOG("blog", "浏览器", "Browser", "language"),
    THOUGHT("thought", "小巧思", "Thoughts", "bolt"),
    DATE("date_records", "日期记录", "Dates", "event"),
    POETRY("poetry_book", "诗词本", "Poetry book", "poetry"),
    SETTINGS("settings", "设置", "Settings", "settings"),
}

data class NavItemConfig(
    val id: NavItemId,
    val label: String = id.defaultLabel,
    val iconKey: String = id.defaultIcon,
    val visible: Boolean = true,
)

data class AppSettings(
    val visualStyle: VisualStyle = VisualStyle.MATERIAL,
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val appLanguage: AppLanguage = AppLanguage.CHINESE,
    val userName: String = "",
    val themeColorArgb: Int = 0xFF42664D.toInt(),
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
    val mealButtonsUseIcons: Boolean = false,
    val mealButtonIcons: List<String> = DEFAULT_MEAL_BUTTON_ICONS,
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
