package com.deskcubby.app.data.model

enum class VisualStyle { MATERIAL, LIQUID_GLASS, ORGANIC_FUTURE, CUSTOM }

/** Rendering behavior inherited by the user-defined visual style. */
enum class CustomThemeBaseStyle { MATERIAL, LIQUID_GLASS, ORGANIC_FUTURE }

const val MIN_CUSTOM_THEME_CORNER_RADIUS_DP: Float = 0f
const val MAX_CUSTOM_THEME_CORNER_RADIUS_DP: Float = 40f
const val MIN_CUSTOM_THEME_BORDER_WIDTH_DP: Float = 0f
const val MAX_CUSTOM_THEME_BORDER_WIDTH_DP: Float = 4f
const val MIN_CUSTOM_THEME_ELEVATION_DP: Float = 0f
const val MAX_CUSTOM_THEME_ELEVATION_DP: Float = 16f
const val MIN_CUSTOM_THEME_PANEL_OPACITY: Float = 0.65f
const val MAX_CUSTOM_THEME_PANEL_OPACITY: Float = 1f
const val MIN_CUSTOM_THEME_SPACING_SCALE: Float = 0.75f
const val MAX_CUSTOM_THEME_SPACING_SCALE: Float = 1.35f
const val MIN_CUSTOM_THEME_ANIMATION_SCALE: Float = 0f
const val MAX_CUSTOM_THEME_ANIMATION_SCALE: Float = 2f

val DEFAULT_CUSTOM_THEME_LIGHT_PALETTE = CustomThemePalette(
    backgroundArgb = 0xFFF7FBF5.toInt(),
    onBackgroundArgb = 0xFF171D19.toInt(),
    surfaceArgb = 0xFFF7FBF5.toInt(),
    onSurfaceArgb = 0xFF171D19.toInt(),
    surfaceContainerArgb = 0xFFE9EFE9.toInt(),
    surfaceVariantArgb = 0xFFDDE5DD.toInt(),
    onSurfaceVariantArgb = 0xFF414943.toInt(),
    outlineArgb = 0xFF717971.toInt(),
)

val DEFAULT_CUSTOM_THEME_DARK_PALETTE = CustomThemePalette(
    backgroundArgb = 0xFF101511.toInt(),
    onBackgroundArgb = 0xFFE0E4DF.toInt(),
    surfaceArgb = 0xFF101511.toInt(),
    onSurfaceArgb = 0xFFE0E4DF.toInt(),
    surfaceContainerArgb = 0xFF1B211C.toInt(),
    surfaceVariantArgb = 0xFF414943.toInt(),
    onSurfaceVariantArgb = 0xFFC1C9C1.toInt(),
    outlineArgb = 0xFF8B938A.toInt(),
)

/** A bounded set of Material color roles; arbitrary selectors, CSS and scripts are never stored. */
data class CustomThemePalette(
    val backgroundArgb: Int,
    val onBackgroundArgb: Int,
    val surfaceArgb: Int,
    val onSurfaceArgb: Int,
    val surfaceContainerArgb: Int,
    val surfaceVariantArgb: Int,
    val onSurfaceVariantArgb: Int,
    val outlineArgb: Int,
)

data class CustomThemeSettings(
    val baseStyle: CustomThemeBaseStyle = CustomThemeBaseStyle.MATERIAL,
    val lightPalette: CustomThemePalette = DEFAULT_CUSTOM_THEME_LIGHT_PALETTE,
    val darkPalette: CustomThemePalette = DEFAULT_CUSTOM_THEME_DARK_PALETTE,
    val cornerRadiusDp: Float = 18f,
    val borderWidthDp: Float = 1f,
    val elevationDp: Float = 2f,
    val panelOpacity: Float = 0.94f,
    val spacingScale: Float = 1f,
    val animationScale: Float = 1f,
)

fun CustomThemeSettings.normalized(): CustomThemeSettings = copy(
    lightPalette = lightPalette.normalizedCustomThemePalette(),
    darkPalette = darkPalette.normalizedCustomThemePalette(),
    cornerRadiusDp = cornerRadiusDp.normalizedThemeValue(
        MIN_CUSTOM_THEME_CORNER_RADIUS_DP,
        MAX_CUSTOM_THEME_CORNER_RADIUS_DP,
        18f,
    ),
    borderWidthDp = borderWidthDp.normalizedThemeValue(
        MIN_CUSTOM_THEME_BORDER_WIDTH_DP,
        MAX_CUSTOM_THEME_BORDER_WIDTH_DP,
        1f,
    ),
    elevationDp = elevationDp.normalizedThemeValue(
        MIN_CUSTOM_THEME_ELEVATION_DP,
        MAX_CUSTOM_THEME_ELEVATION_DP,
        2f,
    ),
    panelOpacity = panelOpacity.normalizedThemeValue(
        MIN_CUSTOM_THEME_PANEL_OPACITY,
        MAX_CUSTOM_THEME_PANEL_OPACITY,
        0.94f,
    ),
    spacingScale = spacingScale.normalizedThemeValue(
        MIN_CUSTOM_THEME_SPACING_SCALE,
        MAX_CUSTOM_THEME_SPACING_SCALE,
        1f,
    ),
    animationScale = animationScale.normalizedThemeValue(
        MIN_CUSTOM_THEME_ANIMATION_SCALE,
        MAX_CUSTOM_THEME_ANIMATION_SCALE,
        1f,
    ),
)

private fun CustomThemePalette.normalizedCustomThemePalette(): CustomThemePalette {
    val background = opaqueThemeArgb(backgroundArgb)
    val surface = opaqueThemeArgb(surfaceArgb)
    var surfaceContainer = opaqueThemeArgb(surfaceContainerArgb)
    val surfaceVariant = opaqueThemeArgb(surfaceVariantArgb)
    val onBackground = readableThemeColor(opaqueThemeArgb(onBackgroundArgb), listOf(background))
    var onSurface = readableThemeColor(
        opaqueThemeArgb(onSurfaceArgb),
        listOf(surface, surfaceContainer),
    )
    if (themeContrastRatio(onSurface, surfaceContainer) < 4.5) {
        // A single Material onSurface role cannot remain readable on two opposite-luminance
        // surfaces. Preserve the main surface and safely collapse the conflicting container.
        surfaceContainer = surface
        onSurface = readableThemeColor(onSurface, listOf(surface))
    }
    val onSurfaceVariant = readableThemeColor(
        opaqueThemeArgb(onSurfaceVariantArgb),
        listOf(surfaceVariant),
    )
    val outline = contrastedThemeColor(opaqueThemeArgb(outlineArgb), surface, 1.5)
    return copy(
        backgroundArgb = background,
        onBackgroundArgb = onBackground,
        surfaceArgb = surface,
        onSurfaceArgb = onSurface,
        surfaceContainerArgb = surfaceContainer,
        surfaceVariantArgb = surfaceVariant,
        onSurfaceVariantArgb = onSurfaceVariant,
        outlineArgb = outline,
    )
}

private fun Float.normalizedThemeValue(min: Float, max: Float, fallback: Float): Float =
    takeIf(Float::isFinite)?.coerceIn(min, max) ?: fallback

private fun opaqueThemeArgb(value: Int): Int = value or 0xFF000000.toInt()

private fun readableThemeColor(requested: Int, backgrounds: List<Int>): Int {
    if (backgrounds.all { themeContrastRatio(requested, it) >= 4.5 }) return requested
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    return listOf(black, white).maxBy { candidate ->
        backgrounds.minOf { background -> themeContrastRatio(candidate, background) }
    }
}

private fun contrastedThemeColor(requested: Int, background: Int, minimum: Double): Int {
    if (themeContrastRatio(requested, background) >= minimum) return requested
    val target = if (themeLuminance(background) > 0.5) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    for (step in 1..20) {
        val candidate = blendThemeArgb(requested, target, step / 20.0)
        if (themeContrastRatio(candidate, background) >= minimum) return candidate
    }
    return target
}

private fun themeContrastRatio(first: Int, second: Int): Double {
    val firstLuminance = themeLuminance(first)
    val secondLuminance = themeLuminance(second)
    return (maxOf(firstLuminance, secondLuminance) + 0.05) /
        (minOf(firstLuminance, secondLuminance) + 0.05)
}

private fun themeLuminance(argb: Int): Double {
    fun channel(shift: Int): Double {
        val encoded = ((argb ushr shift) and 0xFF) / 255.0
        return if (encoded <= 0.04045) encoded / 12.92
        else Math.pow((encoded + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

private fun blendThemeArgb(first: Int, second: Int, fraction: Double): Int {
    fun channel(shift: Int): Int {
        val start = (first ushr shift) and 0xFF
        val end = (second ushr shift) and 0xFF
        return (start + (end - start) * fraction).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

enum class DarkMode { SYSTEM, LIGHT, DARK }

/**
 * Device-local screen orientation preference.
 *
 * Controls only how the activity rotates (rotation behavior). It is intentionally
 * separate from [LayoutMode], which decides the UI structure from actual window
 * geometry. It is a device-local preference and must never enter cloud sync,
 * Obsidian sync, JSON backups, or user-settings restore:
 * a phone may stay portrait while a tablet stays landscape.
 */
enum class OrientationPreference { AUTO, PORTRAIT, LANDSCAPE }

/**
 * The UI structure tier derived from actual window size, not from the bare
 * orientation flag: a portrait tablet can still be wide enough for two panes and
 * a landscape phone may not fit a full three-column workspace. Orientation decides
 * rotation; LayoutMode decides structure.
 */
enum class LayoutMode { COMPACT, MEDIUM, EXPANDED }

enum class BrowserTheme { SYSTEM, LIGHT, DARK }

enum class AppLanguage { CHINESE, TRADITIONAL_CHINESE, ENGLISH, KOREAN, JAPANESE }

enum class MusicVisualizerStyle { BARS, WAVEFORM, CURVE }

enum class Game2048AnimationSpeed { SLOW, NORMAL, FAST }

enum class LauncherIcon { CURRENT, MAGIC_BOOK, DESK_CUBBY }

enum class ThoughtReopenMode { LAST_VISITED, ALL }

enum class ThoughtDisplayMode { SINGLE_LINE, FULL }

enum class MealPhotosPerRow { TWO, THREE, SMART }

enum class PoetryTextAlignment { START, CENTER }

enum class DesktopWidgetContentType { HOME_MODULE, APP_MODULE, APP_SHORTCUT }

enum class DesktopWidgetTextAlignment { START, CENTER, END }

enum class DesktopWidgetCornerStyle { ROUNDED, SQUARE }

val HOME_GAME_SHORTCUT_IDS: List<String> = listOf(
    "2048",
    "2048_5",
    "2048_6",
    "snake",
    "tetris",
    "minesweeper",
    "spider",
    "go",
)

val DEFAULT_HOME_GAME_SHORTCUT_IDS: List<String> = listOf(
    "2048",
    "snake",
    "minesweeper",
)

fun normalizeHomeGameShortcutIds(items: List<String>): List<String> {
    val selected = items.toSet()
    return HOME_GAME_SHORTCUT_IDS.filter(selected::contains)
}

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
    "notes",
    "game_shortcuts",
    "record_overview",
    // App modules: playable mini games that run directly on the home screen, music visualizer,
    // reader, three screen-time visualizations and the combined cloud-sync module.
    "game_2048",
    "game_2048_5",
    "game_2048_6",
    "game_snake",
    "game_tetris",
    "game_minesweeper",
    "game_spider",
    "game_go",
    "music_visualizer",
    "reader",
    "usage_overview",
    "usage_chart",
    "usage_apps",
    "cloud_sync",
)

/** Screen-time visualization ranges offered by the desktop usage modules. */
val DESKTOP_WIDGET_USAGE_RANGES: List<Int> = listOf(7, 30, 90)

/**
 * Desktop app-module ids: playable mini games (including the 5x5 and 6x6 2048 variants),
 * music visualizer, reader, three screen-time visualizations and the combined cloud-sync panel.
 */
val DESKTOP_WIDGET_APP_MODULE_IDS: List<String> = listOf(
    "game_2048",
    "game_2048_5",
    "game_2048_6",
    "game_snake",
    "game_tetris",
    "game_minesweeper",
    "game_spider",
    "game_go",
    "music_visualizer",
    "reader",
    "usage_overview",
    "usage_chart",
    "usage_apps",
    "cloud_sync",
)

fun normalizeDesktopWidgetHomeModuleId(value: String): String = when (value) {
    "cloud_sync_now" -> "cloud_sync"
    "cloud_sync_force" -> "cloud_sync"
    in DESKTOP_WIDGET_HOME_MODULE_IDS -> value
    else -> "today"
}

const val MIN_DESKTOP_WIDGET_CELLS: Int = 1
const val MAX_DESKTOP_WIDGET_CELLS: Int = 6
const val MIN_DESKTOP_WIDGET_BACKGROUND_OPACITY_PERCENT: Int = 0
const val MAX_DESKTOP_WIDGET_BACKGROUND_OPACITY_PERCENT: Int = 100
const val MIN_DESKTOP_WIDGET_TEXT_SCALE_PERCENT: Int = 75
const val MAX_DESKTOP_WIDGET_TEXT_SCALE_PERCENT: Int = 150
const val MIN_DESKTOP_WIDGET_SURFACE_SCALE_PERCENT: Int = 70
const val MAX_DESKTOP_WIDGET_SURFACE_SCALE_PERCENT: Int = 100
const val MIN_DESKTOP_WIDGET_APP_ICON_SCALE_PERCENT: Int = 50
const val MAX_DESKTOP_WIDGET_APP_ICON_SCALE_PERCENT: Int = 150

/** A reusable design that can be bound to one or more launcher App Widget instances. */
data class DesktopWidgetConfig(
    val id: String,
    val name: String,
    val widthCells: Int = 2,
    val heightCells: Int = 1,
    val backgroundColorArgb: Int = 0xFF263238.toInt(),
    val textColorArgb: Int = 0xFFFFFFFF.toInt(),
    val backgroundImageUri: String? = null,
    val showName: Boolean = true,
    val backgroundOpacityPercent: Int = 100,
    val showIcon: Boolean = true,
    val textAlignment: DesktopWidgetTextAlignment = DesktopWidgetTextAlignment.START,
    val textScalePercent: Int = 100,
    val cornerStyle: DesktopWidgetCornerStyle = DesktopWidgetCornerStyle.ROUNDED,
    /** Uniform visual scale inside the launcher-owned widget bounds. */
    val surfaceScalePercent: Int = 100,
    /** App-shortcut icon scale relative to the fixed 48 dp baseline. */
    val appIconScalePercent: Int = 100,
    val contentType: DesktopWidgetContentType = DesktopWidgetContentType.HOME_MODULE,
    val homeModuleId: String = "today",
    val appPackageName: String? = null,
    val appLabel: String? = null,
    /** Days shown by the usage_overview/usage_chart/usage_apps modules (7/30/90). */
    val usageRangeDays: Int = 7,
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

enum class AgentPermissionMode {
    REQUIRE_APPROVAL,
    FULL_AUTO,
}

enum class AgentDataSource(val wireValue: String) {
    DIARY("diary"),
    THOUGHTS("thoughts"),
    DATE_RECORDS("date_records"),
    DAILY_EVENTS("daily_events"),
    NOTES("notes"),
    POEMS("poems"),
    USAGE("usage"),
    STATISTICS("statistics"),
    APP_GUIDE("app_guide"),
}

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
    /** Agent execution requires provider-native OpenAI-compatible tool calling. */
    val supportsToolCalling: Boolean = false,
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
const val DEFAULT_CALORIE_TEXT_PROMPT: String = """你是谨慎的营养能量估算助手。根据随后 JSON 中同一天 photos 的 recognizedFoods、visionNotes 和可选 userNote，结合可食用分量、常见烹饪方式、可见油脂/酱汁与饮料统一估算当天各图片的能量；用户备注可用于判断多人分享、同一餐多角度拍摄、剩余比例或实际分量。综合全部图片避免重复计算，并在证据不足时采用中性的合理估值。按输入 photoIndex 为每张图片返回结果；确认是同一餐的重复角度时，可将重复图片记为 0 kJ。只返回 JSON，不要 Markdown：{"photos":[{"photoIndex":1,"energyKj":整数,"foods":[{"name":"食物名称","amount":"分量","unit":"单位","energyKj":整数}]}]}。所有能量使用千焦(kJ)，单张图片各项之和应与该图片总能量在合理舍入范围内一致。"""

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
const val MIN_APP_BACKGROUND_OPACITY: Float = 0f
const val MAX_APP_BACKGROUND_OPACITY: Float = 1f
const val MIN_APP_BACKGROUND_BLUR_DP: Float = 0f
const val MAX_APP_BACKGROUND_BLUR_DP: Float = 40f
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
const val MIN_MARKDOWN_HEADING_SIZE_SP: Float = 12f
const val MAX_MARKDOWN_HEADING_SIZE_SP: Float = 48f
val DEFAULT_MARKDOWN_HEADING_SIZES_SP: List<Float> = listOf(32f, 28f, 24f, 21f, 19f, 17f)

const val MIN_AI_PAGE_FONT_SIZE_SP: Float = 12f
const val MAX_AI_PAGE_FONT_SIZE_SP: Float = 28f
const val DEFAULT_AI_PAGE_FONT_SIZE_SP: Float = 16f
const val MIN_AI_REPLY_BOX_WIDTH_DP: Float = 280f
const val MAX_AI_REPLY_BOX_WIDTH_DP: Float = 1200f
const val DEFAULT_AI_REPLY_BOX_WIDTH_DP: Float = 680f
const val MIN_MORE_PAGE_COLUMNS: Int = 1
const val MAX_MORE_PAGE_COLUMNS: Int = 3
const val DEFAULT_MORE_PAGE_COLUMNS: Int = 2

/**
 * Default user-editable Agent instructions shown in AI settings. The built-in hard rules
 * (AgentSystemPrompt) always take precedence; this only adds style and task preferences.
 */
const val DEFAULT_AGENT_PROMPT: String =
    "回答简洁、准确、友好，使用与用户当前使用的语言。Be concise, accurate, and friendly; reply in the user's language."

fun normalizeMarkdownHeadingSizes(values: List<Float>): List<Float> =
    DEFAULT_MARKDOWN_HEADING_SIZES_SP.mapIndexed { index, fallback ->
        values.getOrNull(index)
            ?.takeIf(Float::isFinite)
            ?.coerceIn(MIN_MARKDOWN_HEADING_SIZE_SP, MAX_MARKDOWN_HEADING_SIZE_SP)
            ?: fallback
    }

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
    DESK("desk", "桌面", "Desk", "desk", "把今天留下的痕迹摊开在你的数字桌面上", "Your personal desk for today's traces", defaultVisible = false, defaultShowInMore = true),
    DIARY("diary", "日记", "Diary", "book", "浏览、编辑日记与吃历", "Diaries and meal calendar"),
    NOTES(
        "notes",
        "笔记",
        "Notes",
        "notes",
        "按文件夹管理 Obsidian 兼容 Markdown 笔记",
        "Manage Obsidian-compatible Markdown notes by folder",
        defaultVisible = false,
        defaultShowInMore = true,
    ),
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
    STATISTICS(
        "statistics",
        "统计",
        "Statistics",
        "statistics",
        "汇总日记、使用时间、健康、阅读与小游戏数据",
        "Explore diary, screen-time, health, reading, and game insights",
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
        defaultShowInMore = false,
    ),
    STEPS(
        "step_statistics",
        "健康",
        "Health",
        "steps",
        "读取并可视化每日步数、距离和活动热量",
        "Chart daily steps, distance, and active calories",
        defaultVisible = false,
        defaultShowInMore = false,
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
    /** Navigation-page icon button (tile) background; null keeps the themed default. */
    val moreButtonColorArgb: Int? = null,
    /** Navigation-page card background; null keeps the themed default. */
    val moreCardColorArgb: Int? = null,
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
    val customTheme: CustomThemeSettings = CustomThemeSettings(),
    val darkMode: DarkMode = DarkMode.SYSTEM,
    val appLanguage: AppLanguage = AppLanguage.CHINESE,
    /**
     * Device-local screen orientation: controls rotation only, never UI structure.
     * Intentionally excluded from JSON backups, cloud sync, Obsidian sync and user-settings
     * restore so one device's orientation choice never rewrites another device.
     */
    val orientationPreference: OrientationPreference = OrientationPreference.AUTO,
    val userName: String = "",
    val homeGreetings: List<HomeGreetingTemplate> = DEFAULT_HOME_GREETINGS,
    val themeColorArgb: Int = DEFAULT_THEME_COLOR_ARGB,
    val themeSecondaryColorsArgb: List<Int> = DEFAULT_THEME_SECONDARY_COLORS_ARGB,
    val fontScale: Float = 1f,
    val compactMode: Boolean = false,
    val backgroundImageUri: String? = null,
    val backgroundImageOpacity: Float = 0.45f,
    val backgroundImageBlurDp: Float = 0f,
    val tutorialModeEnabled: Boolean = true,
    /** Device-local walkthrough state. It is intentionally excluded from JSON backups. */
    val tutorialAcknowledgedPages: Set<String> = emptySet(),
    val useChineseLauncherName: Boolean = false,
    val launcherIcon: LauncherIcon = LauncherIcon.CURRENT,
    val backupTreeUri: String? = null,
    val cloudSyncEnabled: Boolean = false,
    val cloudSyncConfigs: List<CloudSyncConfig> = emptyList(),
    val diaryTreeUri: String? = null,
    val mediaTreeUri: String? = null,
    val notesTreeUri: String? = null,
    val fileNamePattern: String = "yyyy-MM-dd",
    val markdownTemplate: String = "# {title}\n\n",
    val imageNamePattern: String = "{date}_{category}_{seq}",
    val imageMaxWidthDp: Int = 720,
    val imageMaxHeightDp: Int = 640,
    val markdownHeadingSizesSp: List<Float> = DEFAULT_MARKDOWN_HEADING_SIZES_SP,
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
    val agentEnabledSources: Set<AgentDataSource> = emptySet(),
    val agentPermissionMode: AgentPermissionMode = AgentPermissionMode.REQUIRE_APPROVAL,
    val aiPageFontSizeSp: Float = DEFAULT_AI_PAGE_FONT_SIZE_SP,
    val aiReplyBoxWidthDp: Float = DEFAULT_AI_REPLY_BOX_WIDTH_DP,
    val agentPrompt: String = DEFAULT_AGENT_PROMPT,
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
    val morePageColumns: Int = DEFAULT_MORE_PAGE_COLUMNS,
    val defaultPage: NavItemId = NavItemId.HOME,
    val bottomNavShowLabels: Boolean = true,
    val musicVisualizerEnabled: Boolean = false,
    val musicVisualizerStyle: MusicVisualizerStyle = MusicVisualizerStyle.BARS,
    val musicVisualizerFrequencyMode: MusicVisualizerFrequencyMode =
        MusicVisualizerFrequencyMode.ADAPTIVE,
    val musicVisualizerMinFrequencyHz: Int = 60,
    val musicVisualizerMaxFrequencyHz: Int = 16_000,
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
        "notes",
        "game_shortcuts",
        "record_overview",
        "cloud_sync_now",
        "cloud_sync_force",
    ),
    val homeGameShortcuts: List<String> = DEFAULT_HOME_GAME_SHORTCUT_IDS,
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
        "notes",
        "game_shortcuts",
        "record_overview",
        "cloud_sync_now",
        "cloud_sync_force",
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
