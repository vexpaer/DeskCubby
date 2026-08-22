"""Desktop widget design API.

`desktopWidgetConfigs` lives inside AppSettings (Android `DesktopWidgetConfig`,
AppModels.kt). This convenience endpoint validates and normalizes each config
against the Android bounds before persisting through `settings_store`:

- widthCells / heightCells  1..6
- backgroundOpacityPercent  0..100
- textScalePercent          75..150
- surfaceScalePercent       70..100
- appIconScalePercent       50..150
- usageRangeDays            one of 7 / 30 / 90
- contentType               HOME_MODULE | APP_MODULE | APP_SHORTCUT
- homeModuleId              legacy `cloud_sync_now`/`cloud_sync_force` map to
                            `cloud_sync`; unknown ids fall back to `today`
"""
from __future__ import annotations

import re
from typing import Any

from fastapi import APIRouter, Depends
from pydantic import BaseModel, ValidationError

from ..core.db import get_db
from ..core.errors import ApiError
from ..services.settings_store import load_settings, update_settings

router = APIRouter(prefix="/api/widgets", tags=["widgets"])

MIN_CELLS, MAX_CELLS = 1, 6                       # MIN/MAX_DESKTOP_WIDGET_CELLS
MIN_BG_OPACITY, MAX_BG_OPACITY = 0, 100           # *_DESKTOP_WIDGET_BACKGROUND_OPACITY_PERCENT
MIN_TEXT_SCALE, MAX_TEXT_SCALE = 75, 150          # *_DESKTOP_WIDGET_TEXT_SCALE_PERCENT
MIN_SURFACE_SCALE, MAX_SURFACE_SCALE = 70, 100    # *_DESKTOP_WIDGET_SURFACE_SCALE_PERCENT
MIN_ICON_SCALE, MAX_ICON_SCALE = 50, 150          # *_DESKTOP_WIDGET_APP_ICON_SCALE_PERCENT
USAGE_RANGES = (7, 30, 90)                        # DESKTOP_WIDGET_USAGE_RANGES
CONTENT_TYPES = ("HOME_MODULE", "APP_MODULE", "APP_SHORTCUT")
TEXT_ALIGNMENTS = ("START", "CENTER", "END")
CORNER_STYLES = ("ROUNDED", "SQUARE")

HOME_MODULE_IDS = frozenset({
    "calendar", "weather", "poem", "today", "date_records", "streak",
    "month_diaries", "total_words", "recent_diary", "recent_thought",
    "quick_input", "daily_records", "meal_photos", "random_diary",
    "year_progress", "website", "notes", "game_shortcuts", "record_overview",
    # App modules (playable games, music visualizer, reader, usage views, cloud sync):
    "game_2048", "game_2048_5", "game_2048_6", "game_snake", "game_tetris",
    "game_minesweeper", "game_spider", "game_go", "music_visualizer",
    "reader", "usage_overview", "usage_chart", "usage_apps", "cloud_sync",
})
APP_MODULE_IDS = frozenset({
    "game_2048", "game_2048_5", "game_2048_6", "game_snake", "game_tetris",
    "game_minesweeper", "game_spider", "game_go", "music_visualizer",
    "reader", "usage_overview", "usage_chart", "usage_apps", "cloud_sync",
})
MAX_CONFIGS = 50
_ID_RE = re.compile(r"[A-Za-z0-9._-]{1,128}")
_PACKAGE_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.]{0,255}")
INT32_MIN, INT32_MAX = -(2**31), 2**31 - 1


def normalize_home_module_id(value: Any) -> str:
    """normalizeDesktopWidgetHomeModuleId: legacy cloud widgets collapse into
    the combined cloud_sync module; anything unknown becomes `today`."""
    if value == "cloud_sync_now" or value == "cloud_sync_force":
        return "cloud_sync"
    if isinstance(value, str) and value in HOME_MODULE_IDS:
        return value
    return "today"


def _require_int(config: dict[str, Any], field: str, lo: int, hi: int) -> int:
    value = config.get(field)
    if isinstance(value, bool) or not isinstance(value, int):
        raise ApiError(400, "invalid_widget_field", f"{field} must be an integer between {lo} and {hi}")
    if not lo <= value <= hi:
        raise ApiError(400, "widget_out_of_range", f"{field} must be between {lo} and {hi}")
    return value


def normalize_widget_config(raw: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise ApiError(400, "invalid_widget", "Each widget config must be an object")

    config_id = str(raw.get("id") or "").strip()
    if not _ID_RE.fullmatch(config_id):
        raise ApiError(400, "invalid_widget_id", "Widget id is invalid or missing")
    name = str(raw.get("name") or "").replace("\r", " ").replace("\n", " ").strip()
    if not name:
        raise ApiError(400, "invalid_widget_name", "Widget name is required")
    name = name[:60]

    width_cells = _require_int(raw, "widthCells", MIN_CELLS, MAX_CELLS)
    height_cells = _require_int(raw, "heightCells", MIN_CELLS, MAX_CELLS)
    bg_opacity = _require_int(raw, "backgroundOpacityPercent", MIN_BG_OPACITY, MAX_BG_OPACITY)
    text_scale = _require_int(raw, "textScalePercent", MIN_TEXT_SCALE, MAX_TEXT_SCALE)
    surface_scale = _require_int(raw, "surfaceScalePercent", MIN_SURFACE_SCALE, MAX_SURFACE_SCALE)
    icon_scale = _require_int(raw, "appIconScalePercent", MIN_ICON_SCALE, MAX_ICON_SCALE)
    usage_range = raw.get("usageRangeDays", 7)
    if isinstance(usage_range, bool) or not isinstance(usage_range, int) or usage_range not in USAGE_RANGES:
        raise ApiError(400, "widget_out_of_range", f"usageRangeDays must be one of {list(USAGE_RANGES)}")

    content_type = raw.get("contentType", "HOME_MODULE")
    if content_type not in CONTENT_TYPES:
        raise ApiError(400, "invalid_widget_content_type", "contentType is invalid")
    alignment = raw.get("textAlignment", "START")
    if alignment not in TEXT_ALIGNMENTS:
        raise ApiError(400, "invalid_widget_alignment", "textAlignment is invalid")
    corner_style = raw.get("cornerStyle", "ROUNDED")
    if corner_style not in CORNER_STYLES:
        raise ApiError(400, "invalid_widget_corner_style", "cornerStyle is invalid")

    home_module_id = normalize_home_module_id(raw.get("homeModuleId"))
    if home_module_id in APP_MODULE_IDS:
        content_type = "APP_MODULE"
    elif content_type == "APP_MODULE":
        content_type = "HOME_MODULE"
    app_package = raw.get("appPackageName")
    if isinstance(app_package, str):
        app_package = app_package.strip()[:256] or None
        if app_package and not _PACKAGE_RE.fullmatch(app_package):
            app_package = None
    else:
        app_package = None
    if content_type == "APP_SHORTCUT" and not app_package:
        raise ApiError(400, "widget_shortcut_requires_app",
                       "APP_SHORTCUT widgets require an appPackageName")

    def argb(field: str) -> int:
        value = raw.get(field)
        if isinstance(value, bool) or not isinstance(value, int) or not INT32_MIN <= value <= INT32_MAX:
            raise ApiError(400, "invalid_widget_color", f"{field} must be an ARGB integer")
        return value | -16777216  # force opaque alpha, matching opaqueArgb()

    label = raw.get("appLabel")
    if not isinstance(label, str):
        label = None
    else:
        label = label.replace("\r", " ").replace("\n", " ").strip()[:40] or None
    background_uri = raw.get("backgroundImageUri")
    if not (isinstance(background_uri, str) and background_uri.startswith("private://")):
        background_uri = None  # web has no SAF; only server-managed backgrounds survive

    return {
        "id": config_id,
        "name": name,
        "widthCells": width_cells,
        "heightCells": height_cells,
        "backgroundColorArgb": argb("backgroundColorArgb"),
        "textColorArgb": argb("textColorArgb"),
        "backgroundImageUri": background_uri,
        "showName": bool(raw.get("showName", True)),
        "backgroundOpacityPercent": bg_opacity,
        "showIcon": bool(raw.get("showIcon", True)),
        "textAlignment": alignment,
        "textScalePercent": text_scale,
        "cornerStyle": corner_style,
        "surfaceScalePercent": surface_scale,
        "appIconScalePercent": icon_scale,
        "contentType": content_type,
        "homeModuleId": home_module_id,
        "appPackageName": app_package,
        "appLabel": label,
        "usageRangeDays": usage_range,
    }


class WidgetsBody(BaseModel):
    configs: list[dict[str, Any]]


@router.get("/configs")
def get_configs(con=Depends(get_db)):
    return load_settings(con).get("desktopWidgetConfigs") or []


@router.put("/configs")
def put_configs(body: WidgetsBody, con=Depends(get_db)):
    if len(body.configs) > MAX_CONFIGS:
        raise ApiError(400, "too_many_widgets", f"At most {MAX_CONFIGS} widget designs are allowed")
    normalized: list[dict[str, Any]] = []
    seen: set[str] = set()
    for raw in body.configs:
        config = normalize_widget_config(raw)
        if config["id"] in seen:
            raise ApiError(400, "duplicate_widget_id", f"Duplicate widget id: {config['id']}")
        seen.add(config["id"])
        normalized.append(config)
    updated = update_settings(con, {"desktopWidgetConfigs": normalized})
    return updated["desktopWidgetConfigs"]


# Keep pydantic imported for body typing clarity in generated OpenAPI.
_ = ValidationError
