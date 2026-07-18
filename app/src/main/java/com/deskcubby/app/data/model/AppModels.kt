package com.deskcubby.app.data.model

enum class VisualStyle { MATERIAL, LIQUID_GLASS }

enum class DarkMode { SYSTEM, LIGHT, DARK }

enum class NavItemId(val route: String, val defaultLabel: String, val defaultIcon: String) {
    HOME("home", "首页", "home"),
    DIARY("diary", "日记", "book"),
    BLOG("blog", "博客", "language"),
    THOUGHT("thought", "闪思", "bolt"),
    SETTINGS("settings", "设置", "settings"),
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
    val diaryTreeUri: String? = null,
    val mediaTreeUri: String? = null,
    val mediaMarkdownPrefix: String = "../Attachments",
    val fileNamePattern: String = "yyyy-MM-dd '日记'",
    val titlePattern: String = "yyyy年M月d日 EEEE",
    val datePattern: String = "yyyy-MM-dd",
    val markdownTemplate: String = "# {title}\n\n",
    val imageNamePattern: String = "{date}_{category}_{seq}",
    val imageMaxWidthDp: Int = 720,
    val imageMaxHeightDp: Int = 640,
    val browserHomeUrl: String = "https://www.google.com",
    val lastBrowserUrl: String? = null,
    val thoughtSplitRatio: Float = 0.58f,
    val navItems: List<NavItemConfig> = NavItemId.entries.map(::NavItemConfig),
    val defaultPage: NavItemId = NavItemId.HOME,
    val homeWidgets: List<String> = listOf(
        "today",
        "year_progress",
        "month_diaries",
        "recent_diary",
        "recent_thought",
        "quick_input",
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
