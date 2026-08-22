"""Meal-calendar PNG export (bounded long image), port of exportMealCalendarPng.

The layout is computed and checked before any bitmap allocation: width 720 px,
max height 16384 px, max 12,000,000 pixels — beyond that the request is rejected
(HTTP 413) exactly like the Android preflight. Days render as photo grids with
captions and kJ totals; corrupt images become placeholders.
"""
from __future__ import annotations

import io
from datetime import date
from functools import lru_cache
from typing import Any

from PIL import Image, ImageDraw, ImageFont, ImageOps

EXPORT_WIDTH_PX = 720
EXPORT_MAX_HEIGHT_PX = 16_384
EXPORT_MAX_PIXELS = 12_000_000
MIN_IMAGE_HEIGHT_PX = 80
MAX_IMAGE_HEIGHT_PX = 320
CAPTION_HEIGHT_PX = 44
CONTENT_PADDING = 28
HEADER_HEIGHT = 118
DAY_HEADER_HEIGHT = 54
CELL_GAP = 12
ROW_GAP = 12
DAY_GAP = 24

CONTENT_COLOR = (248, 248, 246)
CARD_COLOR = (255, 255, 255)
TEXT_COLOR = (35, 39, 42)
MUTED_TEXT_COLOR = (95, 99, 104)
PLACEHOLDER_COLOR = (226, 229, 232)

_FONT_REGULAR_CANDIDATES = (
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
)
_FONT_BOLD_CANDIDATES = (
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
)


class ExportTooLargeError(Exception):
    pass


@lru_cache(maxsize=16)
def _font(size: int, bold: bool):
    candidates = _FONT_BOLD_CANDIDATES if bold else _FONT_REGULAR_CANDIDATES
    for path in candidates:
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    try:
        return ImageFont.load_default(size=size)
    except TypeError:
        return ImageFont.load_default()


def argb_to_rgb(value: Any, fallback: tuple[int, int, int]) -> tuple[int, int, int]:
    try:
        v = int(value) & 0xFFFFFFFF
    except (TypeError, ValueError):
        return fallback
    return ((v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF)


def contrast_text_color(background: tuple[int, int, int]) -> tuple[int, int, int]:
    luminance = (0.2126 * background[0] + 0.7152 * background[1] + 0.0722 * background[2]) / 255.0
    return (28, 31, 34) if luminance > 0.56 else (255, 255, 255)


def meal_photo_row_sizes(count: int, mode: str) -> list[int]:
    """Port of AppModels.mealPhotoRowSizes (SMART avoids a dangling singleton)."""
    if count <= 0:
        return []
    if mode == "TWO":
        return [2] * (count // 2) + ([1] if count % 2 == 1 else [])
    if mode == "THREE":
        return [3] * (count // 3) + ([count % 3] if count % 3 != 0 else [])
    # SMART
    if count == 1:
        return [1]
    if count % 3 == 0:
        return [3] * (count // 3)
    if count % 3 == 1:
        return [3] * ((count - 4) // 3) + [2, 2]
    return [3] * (count // 3) + [2]


def build_layout(photo_counts: list[int], image_max_height: int, show_captions: bool,
                 photos_per_row: str) -> dict[str, Any]:
    image_height = max(MIN_IMAGE_HEIGHT_PX, min(int(image_max_height or 124), MAX_IMAGE_HEIGHT_PX))
    caption_height = CAPTION_HEIGHT_PX if show_captions else 0
    card_height = image_height + caption_height

    height = CONTENT_PADDING + HEADER_HEIGHT + CONTENT_PADDING
    rows_per_day: list[list[int]] = []
    for photo_count in photo_counts:
        row_sizes = meal_photo_row_sizes(photo_count, photos_per_row if photos_per_row in ("TWO", "THREE", "SMART") else "SMART")
        rows_per_day.append(row_sizes)
        height += DAY_HEADER_HEIGHT
        height += len(row_sizes) * card_height
        height += max(0, len(row_sizes) - 1) * ROW_GAP
        height += DAY_GAP
        if height > EXPORT_MAX_HEIGHT_PX or EXPORT_WIDTH_PX * height > EXPORT_MAX_PIXELS:
            raise ExportTooLargeError("所选范围生成的长图过高，请缩短日期范围")
    return {
        "width": EXPORT_WIDTH_PX,
        "height": height,
        "imageHeight": image_height,
        "captionHeight": caption_height,
        "cardHeight": card_height,
        "rowsPerDay": rows_per_day,
    }


def _load_media_image(path, target_width: int, target_height: int) -> Image.Image | None:
    try:
        with Image.open(path) as img:
            img = ImageOps.exif_transpose(img)
            img.load()
    except Exception:
        return None
    if img.width <= 0 or img.height <= 0:
        return None
    source_aspect = img.width / img.height
    dest_aspect = target_width / target_height
    if source_aspect > dest_aspect:
        crop_width = max(1, round(img.height * dest_aspect))
        left = max(0, (img.width - crop_width) // 2)
        box = (left, 0, min(left + crop_width, img.width), img.height)
    else:
        crop_height = max(1, round(img.width / dest_aspect))
        top = max(0, (img.height - crop_height) // 2)
        box = (0, top, img.width, min(top + crop_height, img.height))
    cropped = img.crop(box).resize((target_width, target_height), Image.LANCZOS)
    return cropped.convert("RGB")


def _ellipsized(draw: ImageDraw.ImageDraw, text: str, max_width: float, font) -> str:
    if draw.textlength(text, font=font) <= max_width:
        return text
    ellipsis = "…"
    while text and draw.textlength(text + ellipsis, font=font) > max_width:
        text = text[:-1]
    return text + ellipsis


def render_meal_calendar_png(
    days: list[dict[str, Any]],
    settings: dict[str, Any],
    start_inclusive: date,
    end_inclusive: date,
    categories: list[dict[str, Any]],
    media_dir_resolver=None,
) -> bytes:
    """Renders one bounded PNG. `days` are pre-filtered MealCalendarDay dicts."""
    is_english = settings.get("appLanguage") == "ENGLISH"
    primary = argb_to_rgb(settings.get("themeColorArgb"), (66, 102, 77))
    secondary_list = settings.get("themeSecondaryColorsArgb") or []
    secondary = argb_to_rgb(secondary_list[0], primary) if secondary_list else primary
    title_color = contrast_text_color(primary)

    layout = build_layout(
        photo_counts=[len(day["photos"]) for day in days],
        image_max_height=settings.get("mealCalendarImageMaxHeightDp") or 124,
        show_captions=bool(settings.get("mealCalendarShowCaptions", True)),
        photos_per_row=settings.get("mealCalendarPhotosPerRow") or "SMART",
    )
    width, height = layout["width"], layout["height"]
    image_height = layout["imageHeight"]
    caption_height = layout["captionHeight"]
    card_height = layout["cardHeight"]

    img = Image.new("RGB", (width, height), CONTENT_COLOR)
    draw = ImageDraw.Draw(img)

    title_font = _font(38, bold=True)
    header_detail_font = _font(21, bold=False)
    date_font = _font(30, bold=True)
    energy_font = _font(22, bold=True)
    caption_font = _font(21, bold=False)
    placeholder_font = _font(20, bold=False)

    padding = CONTENT_PADDING
    header_bottom = padding + HEADER_HEIGHT - 12
    draw.rounded_rectangle(
        [padding, padding, width - padding, header_bottom],
        radius=24,
        fill=primary,
    )
    draw.text((padding + 24, padding + 18), "Meal calendar" if is_english else "吃历",
              font=title_font, fill=title_color)
    draw.text((padding + 24, padding + 62),
              f"{start_inclusive.isoformat()}  —  {end_inclusive.isoformat()}",
              font=header_detail_font, fill=title_color)
    separator = " · " if is_english else "、"
    category_text = separator.join(
        c["english"] if is_english else c["chinese"] for c in sorted(categories, key=lambda c: c["sortOrder"])
    )
    draw.text(
        (padding + 24, padding + 88),
        _ellipsized(draw, category_text, width - padding * 2 - 48, header_detail_font),
        font=header_detail_font, fill=title_color,
    )

    y = padding + HEADER_HEIGHT
    available_width = width - CONTENT_PADDING * 2
    resolver = media_dir_resolver

    for day_index, day in enumerate(days):
        draw.text((padding, y + 14), day["dateIso"], font=date_font, fill=TEXT_COLOR)
        total_energy = day.get("totalEnergyKj")
        if isinstance(total_energy, int):
            date_width = draw.textlength(day["dateIso"], font=date_font)
            draw.text((padding + date_width + 8, y + 20), f" · {total_energy} kJ",
                      font=energy_font, fill=secondary)
        y += DAY_HEADER_HEIGHT

        photo_offset = 0
        row_sizes = layout["rowsPerDay"][day_index]
        for row_index, row_size in enumerate(row_sizes):
            total_cell_gaps = CELL_GAP * (row_size - 1)
            cell_width = (available_width - total_cell_gaps) / row_size
            cell_width_int = int(cell_width)
            for column in range(row_size):
                photo = day["photos"][photo_offset]
                photo_offset += 1
                left = int(padding + column * (cell_width + CELL_GAP))
                card_rect = [left, y, left + cell_width_int, y + card_height]
                draw.rounded_rectangle(card_rect, radius=14, fill=CARD_COLOR)
                image_rect = (card_rect[0] + 2, card_rect[1] + 2,
                              card_rect[2] - 2, card_rect[1] + 2 + image_height - 4)
                source = None
                if resolver is not None:
                    source = resolver(photo, cell_width_int - 4, image_height - 4)
                if source is not None:
                    img.paste(source, (image_rect[0], image_rect[1]))
                else:
                    draw.rectangle(image_rect, fill=PLACEHOLDER_COLOR)
                    placeholder = "Image unavailable" if is_english else "图片损坏"
                    pw = draw.textlength(placeholder, font=placeholder_font)
                    draw.text(
                        ((image_rect[0] + image_rect[2]) / 2 - pw / 2,
                         (image_rect[1] + image_rect[3]) / 2 - 12),
                        placeholder, font=placeholder_font, fill=MUTED_TEXT_COLOR,
                    )
                if caption_height > 0:
                    caption = photo.get("caption") or ""
                    if not caption:
                        category = photo.get("category")
                        label = next((c for c in categories if c["key"] == category), None)
                        caption = (label["english"] if is_english else label["chinese"]) if label else ""
                    draw.text(
                        (card_rect[0] + 10, card_rect[1] + image_height + 8),
                        _ellipsized(draw, caption, cell_width_int - 20, caption_font),
                        font=caption_font, fill=TEXT_COLOR,
                    )
            y += card_height
            if row_index != len(row_sizes) - 1:
                y += ROW_GAP
        y += DAY_GAP

    buffer = io.BytesIO()
    img.save(buffer, format="PNG")
    return buffer.getvalue()
