"""AppSettings store. The persisted shape mirrors Android AppSettings (camelCase,
AppModels.kt) so v34 backup settings round-trip losslessly. API Key values are
stored but never serialized back to the browser (see `public_settings`).
"""
from __future__ import annotations

import copy
import json
import math
from typing import Any

from ..core.db import connect

SETTINGS_KEY = "settings_json_v1"

DEFAULT_MARKDOWN_HEADING_SIZES_SP = [32.0, 28.0, 24.0, 21.0, 19.0, 17.0]
DEFAULT_THEME_COLOR_ARGB = -12434355  # 0xFF42664D
DEFAULT_THEME_SECONDARY_COLORS_ARGB = [-3524662, -2909908, 11453073]  # 0xFFC96F4A 0xFFD4A72C 0xFF527F91
DEFAULT_THOUGHT_HIGHLIGHT_COLOR_ARGB = -618335  # 0xFFF6E3A1
DEFAULT_AI_PAGE_FONT_SIZE_SP = 16.0
DEFAULT_AI_REPLY_BOX_WIDTH_DP = 680.0
DEFAULT_AGENT_PROMPT = (
    "回答简洁、准确、友好，使用与用户当前使用的语言。Be concise, accurate, and friendly; reply in the user's language."
)
DEFAULT_CALORIE_VISION_PROMPT = """你是谨慎的餐食视觉记录助手。识别图片中所有可食用食物和饮料，按主食、蛋白质、蔬菜、水果、酱汁/油和饮料等实际组成拆分；估计可食用部分的数值分量与单位，餐具和装饰不要算作食物，同一食物不要重复列出。只返回 JSON，不要 Markdown：{"foods":[{"name":"食物名称","amount":"估计数值或范围","unit":"g、ml、个或份","confidence":0.0}],"sceneNotes":"烹饪方式、遮挡和份量不确定性"}。看不清时给出保守的合理范围并降低 confidence，不要虚构无法从图片推断的品牌或配方。"""
DEFAULT_CALORIE_TEXT_PROMPT = """你是谨慎的营养能量估算助手。根据随后 JSON 中同一天 photos 的 recognizedFoods、visionNotes 和可选 userNote，结合可食用分量、常见烹饪方式、可见油脂/酱汁与饮料统一估算当天各图片的能量；用户备注可用于判断多人分享、同一餐多角度拍摄、剩余比例或实际分量。综合全部图片避免重复计算，并在证据不足时采用中性的合理估值。按输入 photoIndex 为每张图片返回结果；确认是同一餐的重复角度时，可将重复图片记为 0 kJ。只返回 JSON，不要 Markdown：{"photos":[{"photoIndex":1,"energyKj":整数,"foods":[{"name":"食物名称","amount":"分量","unit":"单位","energyKj":整数}]}]}。所有能量使用千焦(kJ)，单张图片各项之和应与该图片总能量在合理舍入范围内一致。"""

NAV_ITEMS = [
    ("HOME", "home", "首页", "Home", "home", "今日概览与快捷记录", "Overview and quick capture", True, False),
    ("DESK", "desk", "桌面", "Desk", "desk", "把今天留下的痕迹摊开在你的数字桌面上", "Your personal desk for today's traces", False, True),
    ("DIARY", "diary", "日记", "Diary", "book", "浏览、编辑日记与吃历", "Diaries and meal calendar", True, False),
    ("NOTES", "notes", "笔记", "Notes", "notes", "按文件夹管理 Obsidian 兼容 Markdown 笔记", "Manage Obsidian-compatible Markdown notes by folder", False, True),
    ("BLOG", "blog", "浏览器", "Browser", "language", "在应用内浏览网页", "Browse the web in the app", False, True),
    ("THOUGHT", "thought", "小巧思", "Thoughts", "bolt", "记录与整理瞬间想法", "Capture and organize thoughts", True, False),
    ("DATE", "date_records", "日期记录", "Dates", "event", "追踪纪念日与目标日期", "Track occasions and target dates", False, True),
    ("POETRY", "poetry_book", "诗词本", "Poetry book", "poetry", "收藏喜欢的诗词", "Keep your favorite poems", False, True),
    ("RSS", "rss", "RSS 订阅", "RSS", "rss", "阅读订阅源的最新文章", "Read the latest from your feeds", False, True),
    ("AI_CHAT", "ai_chat", "AI 聊天", "AI chat", "ai", "选择本机记录作为上下文并与模型分析", "Analyze selected local records with AI", False, True),
    ("VAULT", "vault", "收藏夹", "Vault", "lock", "密码保护的私密收藏", "Password-protected private notes", False, True),
    ("READER", "reader", "阅读", "Reader", "reader", "导入并阅读 TXT/PDF 小说", "Import and read TXT/PDF books", False, True),
    ("GAMES", "games", "小游戏", "Games", "game", "2048、贪吃蛇、俄罗斯方块、扫雷与蜘蛛纸牌", "2048, Snake, Tetris, Minesweeper, and Spider Solitaire", False, True),
    ("STATISTICS", "statistics", "统计", "Statistics", "statistics", "汇总日记、使用时间、健康、阅读与小游戏数据", "Explore diary, screen-time, health, reading, and game insights", False, True),
    ("USAGE", "usage_statistics", "手机使用时间", "Screen time", "usage", "按天查看各应用的使用时长", "Daily usage time by app", False, False),
    ("STEPS", "step_statistics", "健康", "Health", "steps", "读取并可视化每日步数、距离和活动热量", "Chart daily steps, distance, and active calories", False, False),
    ("WIDGETS", "desktop_widgets", "小卡片", "Widgets", "widgets", "设计并添加可缩放的桌面小卡片", "Design and add resizable home-screen widgets", False, True),
    ("MORE", "more", "导航", "More", "apps", "打开收纳的页面", "Open collected pages", False, False),
    ("SETTINGS", "settings", "设置", "Settings", "settings", "调整应用与页面设置", "Adjust app and page settings", True, False),
]

DEFAULT_HOME_GREETINGS = [
    {"chinese": "今天从这里开始", "english": "Start here today"},
    {"chinese": "看看今天的安排", "english": "Check today's plan"},
    {"chinese": "有想法就记下来", "english": "Write down what's on your mind"},
    {"chinese": "先完成一件小事", "english": "Start with one small task"},
    {"chinese": "今天想写点什么？", "english": "What would you like to write today?"},
    {"chinese": "看看最近的记录", "english": "Review your recent notes"},
    {"chinese": "先处理重要的事", "english": "Start with what matters"},
    {"chinese": "打开日历看看", "english": "Take a look at the calendar"},
    {"chinese": "记录一下当前状态", "english": "Record where things stand"},
    {"chinese": "今天的进度怎么样？", "english": "How is today going?"},
    {"chinese": "先快速记一条", "english": "Add a quick note"},
    {"chinese": "看看时间都去哪了", "english": "See where the time went"},
    {"chinese": "今天走了多少步？", "english": "How many steps today?"},
    {"chinese": "查看新的订阅", "english": "Check the latest feeds"},
    {"chinese": "整理一下当前思路", "english": "Organize your current thoughts"},
    {"chinese": "从最简单的事开始", "english": "Begin with the simplest thing"},
    {"chinese": "该记录今天了", "english": "Time to record today"},
    {"chinese": "翻翻过去写的内容", "english": "Browse something you wrote before"},
    {"chinese": "现在要做什么？", "english": "What comes next?"},
    {"chinese": "先看一眼今日数据", "english": "Check today's numbers"},
    {"chinese": "把刚才的想法留下", "english": "Keep that thought before it slips away"},
    {"chinese": "今天也按计划推进", "english": "Keep today's plan moving"},
    {"chinese": "检查一下重要日期", "english": "Check the important dates"},
    {"chinese": "{name}，欢迎回来", "english": "Welcome back, {name}"},
]

DEFAULT_MEAL_BUTTON_ICONS = ["🥪", "🍱", "🍹", "🍜", "🍊", "🍤"]

HOME_GAME_SHORTCUT_IDS = ["2048", "2048_5", "2048_6", "snake", "tetris", "minesweeper", "spider", "go"]
DEFAULT_HOME_GAME_SHORTCUT_IDS = ["2048", "snake", "minesweeper"]

DEFAULT_HOME_WIDGETS = [
    "today", "poem", "quick_input", "meal_photos", "year_progress",
    "notes", "game_shortcuts", "record_overview", "cloud_sync_now", "cloud_sync_force",
]
DEFAULT_HOME_WIDGET_TITLES = [
    "calendar", "weather", "poem", "today", "streak", "month_diaries", "total_words",
    "recent_diary", "recent_thought", "date_records", "quick_input", "daily_records",
    "meal_photos", "random_diary", "year_progress", "website", "notes", "game_shortcuts",
    "record_overview", "cloud_sync_now", "cloud_sync_force",
]

DEFAULT_DESKTOP_WIDGET_CONFIGS = [{
    "id": "default-today", "name": "今天 / Today", "widthCells": 2, "heightCells": 1,
    "backgroundColorArgb": -15461322, "textColorArgb": -1, "backgroundImageUri": None,
    "showName": True, "backgroundOpacityPercent": 100, "showIcon": True,
    "textAlignment": "START", "textScalePercent": 100, "cornerStyle": "ROUNDED",
    "surfaceScalePercent": 100, "appIconScalePercent": 100,
    "contentType": "HOME_MODULE", "homeModuleId": "today", "appPackageName": None,
    "appLabel": None, "usageRangeDays": 7,
}]

# AppModels.kt DESKTOP_WIDGET_HOME_MODULE_IDS (+ app modules). The legacy ids
# `cloud_sync_now` / `cloud_sync_force` are kept AS-IS here (HomePage renders those
# two ids directly); only the widgets API maps them onto `cloud_sync`.
DESKTOP_WIDGET_HOME_MODULE_IDS = frozenset({
    "calendar", "weather", "poem", "today", "date_records", "streak",
    "month_diaries", "total_words", "recent_diary", "recent_thought",
    "quick_input", "daily_records", "meal_photos", "random_diary",
    "year_progress", "website", "notes", "game_shortcuts", "record_overview",
    "game_2048", "game_2048_5", "game_2048_6", "game_snake", "game_tetris",
    "game_minesweeper", "game_spider", "game_go", "music_visualizer",
    "reader", "usage_overview", "usage_chart", "usage_apps", "cloud_sync",
})
DESKTOP_WIDGET_LEGACY_HOME_MODULE_IDS = ("cloud_sync_now", "cloud_sync_force")
DESKTOP_WIDGET_CONTENT_TYPES = ("HOME_MODULE", "APP_MODULE", "APP_SHORTCUT")
DESKTOP_WIDGET_USAGE_RANGES = (7, 30, 90)
# MIN/MAX_DESKTOP_WIDGET_* bounds from AppModels.kt.
MIN_DESKTOP_WIDGET_CELLS, MAX_DESKTOP_WIDGET_CELLS = 1, 6

DEFAULT_CUSTOM_THEME = {
    "baseStyle": "MATERIAL",
    "lightPalette": {
        "backgroundArgb": -826215, "onBackgroundArgb": -15179559, "surfaceArgb": -826215,
        "onSurfaceArgb": -15179559, "surfaceContainerArgb": -1454411, "surfaceVariantArgb": -2270307,
        "onSurfaceVariantArgb": -12462269, "outlineArgb": -9317807,
    },
    "darkPalette": {
        "backgroundArgb": -15789831, "onBackgroundArgb": -2103073, "surfaceArgb": -15789831,
        "onSurfaceArgb": -2103073, "surfaceContainerArgb": -14941924, "surfaceVariantArgb": -12462269,
        "onSurfaceVariantArgb": -4069503, "outlineArgb": -7569142,
    },
    "cornerRadiusDp": 18.0, "borderWidthDp": 1.0, "elevationDp": 2.0,
    "panelOpacity": 0.94, "spacingScale": 1.0, "animationScale": 1.0,
}


def default_settings() -> dict[str, Any]:
    return {
        "visualStyle": "MATERIAL",
        "customTheme": copy.deepcopy(DEFAULT_CUSTOM_THEME),
        "darkMode": "SYSTEM",
        "appLanguage": "CHINESE",
        "orientationPreference": "AUTO",
        "userName": "",
        "homeGreetings": copy.deepcopy(DEFAULT_HOME_GREETINGS),
        "themeColorArgb": DEFAULT_THEME_COLOR_ARGB,
        "themeSecondaryColorsArgb": list(DEFAULT_THEME_SECONDARY_COLORS_ARGB),
        "fontScale": 1.0,
        "compactMode": False,
        "backgroundImageUri": None,
        "backgroundImageOpacity": 0.45,
        "backgroundImageBlurDp": 0.0,
        "tutorialModeEnabled": True,
        "tutorialAcknowledgedPages": [],
        "useChineseLauncherName": False,
        "launcherIcon": "CURRENT",
        "backupTreeUri": None,
        "cloudSyncEnabled": False,
        "cloudSyncConfigs": [],
        "diaryTreeUri": "workspace://diary",
        "mediaTreeUri": "workspace://media",
        "notesTreeUri": None,
        "fileNamePattern": "yyyy-MM-dd",
        "markdownTemplate": "# {title}\n\n",
        "imageNamePattern": "{date}_{category}_{seq}",
        "imageMaxWidthDp": 720,
        "imageMaxHeightDp": 640,
        "markdownHeadingSizesSp": list(DEFAULT_MARKDOWN_HEADING_SIZES_SP),
        "mealImageCompressionEnabled": True,
        "mealImageCompressionQuality": 80,
        "saveOriginalToGallery": False,
        "photoLocationEnabled": False,
        "browserHomeUrl": "https://www.google.com",
        "lastBrowserUrl": None,
        "browserTheme": "SYSTEM",
        "browserDesktopMode": False,
        "thoughtSplitRatio": 0.58,
        "thoughtRowHeightDp": 56,
        "thoughtReopenMode": "ALL",
        "lastThoughtPageKey": "all",
        "thoughtDisplayMode": "SINGLE_LINE",
        "thoughtHighlightColorArgb": DEFAULT_THOUGHT_HIGHLIGHT_COLOR_ARGB,
        "thoughtEditorMaxHeightDp": 168,
        "vaultRowHeightDp": 56,
        "poetryFontUri": None,
        "poetryFontSizeSp": 18.0,
        "poetryLineSpacing": 1.45,
        "poetryTextAlignment": "START",
        "poetryShowSource": True,
        "poetryShowQuoteMark": True,
        "poetrySevenCharacterWrapEnabled": False,
        "mealCalendarImageMaxHeightDp": 124,
        "mealCalendarShowCaptions": True,
        "mealCalendarWrapEnabled": False,
        "mealCalendarPhotosPerRow": "SMART",
        "mealPhotoFilter": {
            "enabled": False, "brightness": 0.0, "contrast": 0.0,
            "saturation": 0.0, "warmth": 0.0, "tint": 0.0,
        },
        "mealButtonsUseIcons": False,
        "mealButtonIcons": list(DEFAULT_MEAL_BUTTON_ICONS),
        "dailyEventTemplates": [],
        "structuredAutoRecordSleepWake": False,
        "rssSubscriptions": [],
        "rssMaxItemsPerFeed": 50,
        "rssShowSummaries": True,
        "aiEndpointUrl": "https://api.openai.com/v1/chat/completions",
        "aiModel": "",
        "aiSystemPrompt": "你是一个有帮助的助手。",
        "aiTemperature": 0.7,
        "aiAllowInsecureHttp": False,
        "aiConfigs": [],
        "aiChatConfigId": None,
        "agentEnabledSources": [],
        "agentPermissionMode": "REQUIRE_APPROVAL",
        "aiPageFontSizeSp": DEFAULT_AI_PAGE_FONT_SIZE_SP,
        "aiReplyBoxWidthDp": DEFAULT_AI_REPLY_BOX_WIDTH_DP,
        "agentPrompt": DEFAULT_AGENT_PROMPT,
        "calorieEstimationEnabled": False,
        "calorieTextConfigId": None,
        "calorieImageConfigId": None,
        "calorieVisionPrompt": DEFAULT_CALORIE_VISION_PROMPT,
        "calorieTextPrompt": DEFAULT_CALORIE_TEXT_PROMPT,
        "usageTrackingEnabled": False,
        "stepTrackingEnabled": False,
        "navigationIntroAcknowledged": False,
        "navItems": [
            {
                "id": _id, "label": _zh, "iconKey": _icon,
                "visible": _visible or _id == "MORE",
                "showInMore": _show_more,
                "moreDescription": _desc, "moreButtonColorArgb": None, "moreCardColorArgb": None,
            }
            for (_id, _route, _zh, _en, _icon, _desc, _edesc, _visible, _show_more) in NAV_ITEMS
        ],
        "morePageOrder": [],
        "morePageColumns": 2,
        "defaultPage": "HOME",
        "bottomNavShowLabels": True,
        "musicVisualizerEnabled": False,
        "musicVisualizerStyle": "BARS",
        "musicVisualizerFrequencyMode": "ADAPTIVE",
        "musicVisualizerMinFrequencyHz": 60,
        "musicVisualizerMaxFrequencyHz": 16000,
        "game2048AnimationSpeed": "NORMAL",
        "morePageShowDescriptions": True,
        "homeWidgetBordersEnabled": True,
        "homeWidgets": list(DEFAULT_HOME_WIDGETS),
        "homeGameShortcuts": list(DEFAULT_HOME_GAME_SHORTCUT_IDS),
        "homeWidgetTitles": list(DEFAULT_HOME_WIDGET_TITLES),
        "desktopWidgetConfigs": copy.deepcopy(DEFAULT_DESKTOP_WIDGET_CONFIGS),
    }


# Secret-bearing fields: values are stored server-side, never returned to the browser.
# Beyond AI keys, known cloud-credential fields (canonical + legacy Android names) are
# stripped wherever they appear, e.g. inside cloudSyncConfigs entries.
SECRET_FIELDS = {
    "apiKey",
    "password",
    "accessKey",
    "secretKey",
    "sessionToken",
    "webDavPassword",  # legacy Android CloudSyncConfig field name
    "awsSecretKey",  # legacy Android CloudSyncConfig field name
    # canonical Android CloudSyncModels.kt S3 credential names
    "s3AccessKey",
    "s3SecretKey",
    "s3SessionToken",
}


def _redact(obj: Any) -> Any:
    if isinstance(obj, dict):
        return {
            k: ("" if k in SECRET_FIELDS and isinstance(v, str) and v else v)
            for k, v in ((k, _redact(v)) for k, v in obj.items())
        }
    if isinstance(obj, list):
        return [_redact(v) for v in obj]
    return obj


def load_settings(con) -> dict[str, Any]:
    row = con.execute("SELECT value FROM app_settings_kv WHERE key = ?", (SETTINGS_KEY,)).fetchone()
    if row is None:
        settings = default_settings()
        _persist(con, settings)
        return copy.deepcopy(settings)
    stored = json.loads(row["value"])
    merged = default_settings()
    merged.update(stored)
    return merged


def _persist(con, settings: dict[str, Any]) -> None:
    con.execute(
        "INSERT INTO app_settings_kv(key, value) VALUES(?, ?) "
        "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        (SETTINGS_KEY, json.dumps(settings, ensure_ascii=False)),
    )
    con.commit()


def update_settings(con, patch: dict[str, Any]) -> dict[str, Any]:
    """Merge a camelCase AppSettings patch. Secret-preserving: an aiConfigs entry
    whose apiKey is empty keeps the previously stored key for the same id."""
    current = load_settings(con)
    for key, value in patch.items():
        if key not in current:
            continue
        if key == "aiConfigs" and isinstance(value, list):
            old_by_id = {c.get("id"): c for c in current.get("aiConfigs", []) if isinstance(c, dict)}
            cleaned = []
            for cfg in value:
                if not isinstance(cfg, dict):
                    continue
                cfg = copy.deepcopy(cfg)
                old = old_by_id.get(cfg.get("id"))
                if old and not (cfg.get("apiKey") or "").strip():
                    cfg["apiKey"] = old.get("apiKey", "")
                cleaned.append(cfg)
            current[key] = cleaned
            continue
        current[key] = value
    current = normalize_settings(current)
    _persist(con, current)
    return current


def normalize_settings(s: dict[str, Any]) -> dict[str, Any]:
    """Bounded normalization mirroring AppModels.kt limits."""
    def clamp(v, lo, hi, dflt):
        try:
            v = float(v)
        except (TypeError, ValueError):
            return dflt
        if v != v:  # NaN
            return dflt
        return max(lo, min(hi, v))

    s["fontScale"] = clamp(s.get("fontScale", 1.0), 0.8, 1.3, 1.0)
    s["backgroundImageOpacity"] = clamp(s.get("backgroundImageOpacity", 0.45), 0.0, 1.0, 0.45)
    s["backgroundImageBlurDp"] = clamp(s.get("backgroundImageBlurDp", 0.0), 0.0, 40.0, 0.0)
    s["morePageColumns"] = int(clamp(s.get("morePageColumns", 2), 1, 3, 2))
    s["aiPageFontSizeSp"] = clamp(s.get("aiPageFontSizeSp", 16.0), 12.0, 28.0, 16.0)
    s["aiReplyBoxWidthDp"] = clamp(s.get("aiReplyBoxWidthDp", 680.0), 280.0, 1200.0, 680.0)
    s["poetryFontSizeSp"] = clamp(s.get("poetryFontSizeSp", 18.0), 14.0, 36.0, 18.0)
    s["poetryLineSpacing"] = clamp(s.get("poetryLineSpacing", 1.45), 1.0, 2.0, 1.45)
    s["thoughtEditorMaxHeightDp"] = int(clamp(s.get("thoughtEditorMaxHeightDp", 168), 96, 400, 168))
    s["vaultRowHeightDp"] = int(clamp(s.get("vaultRowHeightDp", 56), 48, 120, 56))
    sizes = s.get("markdownHeadingSizesSp")
    if not isinstance(sizes, list) or len(sizes) != 6:
        sizes = list(DEFAULT_MARKDOWN_HEADING_SIZES_SP)
    s["markdownHeadingSizesSp"] = [
        clamp(v, 12.0, 48.0, d) for v, d in zip(sizes, DEFAULT_MARKDOWN_HEADING_SIZES_SP)
    ]
    sec = s.get("themeSecondaryColorsArgb")
    if not isinstance(sec, list) or not (2 <= len(sec) <= 5):
        sec = list(DEFAULT_THEME_SECONDARY_COLORS_ARGB)
    s["themeSecondaryColorsArgb"] = sec
    hs = s.get("homeGameShortcuts")
    if isinstance(hs, list):
        s["homeGameShortcuts"] = [g for g in HOME_GAME_SHORTCUT_IDS if g in set(hs)]
    order = s.get("morePageOrder")
    if isinstance(order, list):
        orderable = [n["id"] for n in s.get("navItems", []) if n["id"] not in ("HOME", "MORE", "SETTINGS")]
        seen: list[str] = []
        for item in order:
            if item in orderable and item not in seen:
                seen.append(item)
        for n in s.get("navItems", []):
            if n["id"] in orderable and n["id"] not in seen:
                seen.append(n["id"])
        s["morePageOrder"] = seen
    else:
        s["morePageOrder"] = []
    ct = s.get("customTheme")
    if isinstance(ct, dict):
        ct["cornerRadiusDp"] = clamp(ct.get("cornerRadiusDp", 18.0), 0.0, 40.0, 18.0)
        ct["borderWidthDp"] = clamp(ct.get("borderWidthDp", 1.0), 0.0, 4.0, 1.0)
        ct["elevationDp"] = clamp(ct.get("elevationDp", 2.0), 0.0, 16.0, 2.0)
        ct["panelOpacity"] = clamp(ct.get("panelOpacity", 0.94), 0.65, 1.0, 0.94)
        ct["spacingScale"] = clamp(ct.get("spacingScale", 1.0), 0.75, 1.35, 1.0)
        ct["animationScale"] = clamp(ct.get("animationScale", 1.0), 0.0, 2.0, 1.0)
    s["desktopWidgetConfigs"] = _normalize_desktop_widget_configs(
        s.get("desktopWidgetConfigs")
    )
    return s


def _widget_bounded_int(value: Any, lo: int, hi: int, dflt: int) -> int:
    """Clamp a widget field to [lo, hi]; non-numeric input falls back to the
    Android data-class default."""
    if isinstance(value, bool):
        return dflt
    try:
        number = float(value)
    except (TypeError, ValueError):
        return dflt
    if not math.isfinite(number):
        return dflt
    clamped = max(lo, min(hi, number))
    # Half-away-from-zero, matching Kotlin roundToInt on the bounded value.
    return int(math.floor(clamped + 0.5)) if clamped >= 0 else -int(math.floor(-clamped + 0.5))


def _widget_usage_range_days(value: Any) -> int:
    if isinstance(value, bool):
        return 7
    if isinstance(value, float) and value.is_integer():
        value = int(value)
    return value if isinstance(value, int) and value in DESKTOP_WIDGET_USAGE_RANGES else 7


def _normalize_desktop_widget_configs(value: Any) -> list[dict[str, Any]]:
    """Mirror AppModels.kt DesktopWidgetConfig bounds + normalizeDesktopWidgetHomeModuleId.

    The widgets API validates strictly (4xx) before persisting; this store-level pass
    keeps anything that reached settings through merges/imports inside Android bounds.
    """
    if not isinstance(value, list):
        return copy.deepcopy(DEFAULT_DESKTOP_WIDGET_CONFIGS)
    configs: list[dict[str, Any]] = []
    for raw in value:
        if not isinstance(raw, dict):
            continue
        cfg = dict(raw)
        cfg["widthCells"] = _widget_bounded_int(cfg.get("widthCells"), MIN_DESKTOP_WIDGET_CELLS, MAX_DESKTOP_WIDGET_CELLS, 2)
        cfg["heightCells"] = _widget_bounded_int(cfg.get("heightCells"), MIN_DESKTOP_WIDGET_CELLS, MAX_DESKTOP_WIDGET_CELLS, 1)
        cfg["backgroundOpacityPercent"] = _widget_bounded_int(cfg.get("backgroundOpacityPercent"), 0, 100, 100)
        cfg["textScalePercent"] = _widget_bounded_int(cfg.get("textScalePercent"), 75, 150, 100)
        cfg["surfaceScalePercent"] = _widget_bounded_int(cfg.get("surfaceScalePercent"), 70, 100, 100)
        cfg["appIconScalePercent"] = _widget_bounded_int(cfg.get("appIconScalePercent"), 50, 150, 100)
        cfg["usageRangeDays"] = _widget_usage_range_days(cfg.get("usageRangeDays"))
        content_type = cfg.get("contentType")
        cfg["contentType"] = content_type if content_type in DESKTOP_WIDGET_CONTENT_TYPES else "HOME_MODULE"
        module_id = cfg.get("homeModuleId")
        if module_id in DESKTOP_WIDGET_LEGACY_HOME_MODULE_IDS:
            pass  # kept AS-IS: HomePage renders cloud_sync_now / cloud_sync_force directly
        elif not (isinstance(module_id, str) and module_id in DESKTOP_WIDGET_HOME_MODULE_IDS):
            cfg["homeModuleId"] = "today"
        configs.append(cfg)
    return configs


def public_settings(con) -> dict[str, Any]:
    """Settings safe for the browser: secret values stripped."""
    return _redact(load_settings(con))
