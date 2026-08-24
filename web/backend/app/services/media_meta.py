"""`dc-media.json` v2 sidecar codec + read-modify-write boundary.

Port of MediaMetaJsonCodec.kt plus DiaryFileRepository's mediaMutex flow:
- keys of `entries` are lower-cased media file names; unknown JSON fields survive updates;
- bounded input (2 MiB raw, entry/count/length caps), corrupt or oversized files are
  never silently overwritten with empty data;
- every update is read-modify-write under a module-level lock with an expected-original
  recheck, a verified previous copy, and a pending copy committed last;
- like DiaryFileRepository.kt, reads may fall back through previous/pending/legacy
  copies, but future updates ALWAYS commit to the short canonical name (`dc-media.json`).
"""
from __future__ import annotations

import json
import math
import threading
from datetime import date
from pathlib import Path
from typing import Any, Callable

from ..core.config import MEDIA_DIR
from ..core.errors import ApiError
from ..core.fs import safe_write_text

MEDIA_META_FILE_NAME = "dc-media.json"
MEDIA_META_PREVIOUS_FILE_NAME = "dc-media.previous.json"
LEGACY_MEDIA_META_FILE_NAME = "deskcubby-media.json"
MEDIA_META_PENDING_FILE_NAME = "dc-media.pending.json"
MEDIA_META_MAX_BYTES = 2 * 1024 * 1024

CURRENT_VERSION = 2
KEY_VERSION = "version"
KEY_ENTRIES = "entries"
KEY_MEAL_DAYS = "mealDays"

MAX_PLACE_CHARS = 1_000
MAX_MEDIA_META_ENTRIES = 20_000
MAX_MEAL_DAY_ENTRIES = 10_000
MAX_MEDIA_KEY_CHARS = 1_024
MAX_DATE_KEY_CHARS = 32
MAX_MEAL_FOODS = 64
MAX_MEAL_FOOD_NAME_CHARS = 200
MAX_MEAL_AMOUNT_CHARS = 80
MAX_MEAL_UNIT_CHARS = 40
MAX_MEAL_NOTE_CHARS = 4_000
MAX_MEAL_ENERGY_KJ = 1_000_000

media_mutex = threading.RLock()


class MediaMetaError(ApiError):
    def __init__(self, message: str):
        super().__init__(500, "media_meta_corrupt", message)


class MediaMetaConflictError(ApiError):
    def __init__(self, message: str = "媒体信息 JSON 已被其他应用修改，请刷新后重试"):
        super().__init__(409, "media_meta_conflict", message)


# ---------------------------------------------------------------------------
# Bounded field readers (raise MediaMetaError on violations -> corrupt)
# ---------------------------------------------------------------------------

def _round_half_away_from_zero(number: float) -> int:
    """Kotlin roundToInt semantics: 2.5 -> 3, -2.5 -> -3."""
    if number >= 0:
        return int(math.floor(number + 0.5))
    return -int(math.floor(-number + 0.5))


def _bounded_int(item: dict[str, Any], key: str, minimum: int = 0) -> int | None:
    value = item.get(key)
    if value is None:
        return None
    number: float | None
    if isinstance(value, bool):
        number = None
    elif isinstance(value, (int, float)):
        number = float(value)
    elif isinstance(value, str):
        try:
            number = float(value)
        except ValueError:
            number = None
    else:
        number = None
    if number is None or not math.isfinite(number) or number < minimum or number > MAX_MEAL_ENERGY_KJ:
        raise MediaMetaError("媒体信息 JSON 的数值无效")
    return _round_half_away_from_zero(number)


def _finite_double(value: Any) -> float | None:
    """org.json optDouble semantics: numeric strings like '1.5' are coerced;
    booleans and non-numeric values yield None (dropped on encode)."""
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        number = float(value)
    elif isinstance(value, str):
        text = value.strip()
        try:
            number = float(text) if text else None
        except ValueError:
            return None
    else:
        return None
    if number is None:
        return None
    return number if math.isfinite(number) else None


def _bounded_string(item: dict[str, Any], key: str, max_chars: int) -> str | None:
    value = item.get(key)
    if value is None:
        return None
    if not isinstance(value, str):
        raise MediaMetaError("媒体信息 JSON 的字段格式无效")
    if len(value) > max_chars:
        raise MediaMetaError("媒体信息 JSON 的字段过长")
    trimmed = value.strip()
    return trimmed or None


def _bounded_scalar_string(item: dict[str, Any], key: str, max_chars: int) -> str | None:
    value = item.get(key)
    if value is None:
        return None
    if isinstance(value, (dict, list)):
        raise MediaMetaError("媒体信息 JSON 的字段格式无效")
    text = str(value)
    if len(text) > max_chars:
        raise MediaMetaError("媒体信息 JSON 的字段过长")
    trimmed = text.strip()
    return trimmed or None


def decode_foods(array: Any) -> list[dict[str, Any]]:
    if array is None:
        return []
    if not isinstance(array, list):
        raise MediaMetaError("媒体信息 JSON 的食物条目格式无效")
    if len(array) > MAX_MEAL_FOODS:
        raise MediaMetaError("媒体信息 JSON 的食物数量超出限制")
    foods = []
    for item in array:
        if not isinstance(item, dict):
            raise MediaMetaError("媒体信息 JSON 的食物条目格式无效")
        name = _bounded_string(item, "name", MAX_MEAL_FOOD_NAME_CHARS)
        if name is None:
            raise MediaMetaError("媒体信息 JSON 的食物名称无效")
        foods.append(
            {
                "name": name,
                "amount": _bounded_scalar_string(item, "amount", MAX_MEAL_AMOUNT_CHARS),
                "unit": _bounded_string(item, "unit", MAX_MEAL_UNIT_CHARS),
                "energyKj": _bounded_int(item, "energyKj"),
            }
        )
    return foods


def decode_entry(item: dict[str, Any]) -> dict[str, Any]:
    return {
        "energyKj": _bounded_int(item, "energyKj"),
        "lat": _finite_double(item.get("lat")),
        "lng": _finite_double(item.get("lng")),
        "place": _bounded_string(item, "place", MAX_PLACE_CHARS),
        "foods": decode_foods(item.get("foods")),
    }


def normalize_day(details: dict[str, Any]) -> dict[str, Any]:
    override = details.get("totalEnergyKjOverride")
    if override is not None and (not isinstance(override, int) or not (0 <= override <= MAX_MEAL_ENERGY_KJ)):
        override = None
    note = (details.get("note") or "").strip()[:MAX_MEAL_NOTE_CHARS]
    return {"totalEnergyKjOverride": override, "note": note}


# ---------------------------------------------------------------------------
# Codec (raw-string in / raw-string out; unknown fields preserved)
# ---------------------------------------------------------------------------

def parse_root(raw: str) -> dict[str, Any]:
    if not raw.strip():
        return {}
    try:
        root = json.loads(raw)
    except ValueError:
        raise MediaMetaError("媒体信息 JSON 已损坏")
    if not isinstance(root, dict):
        raise MediaMetaError("媒体信息 JSON 已损坏")
    entries = root.get(KEY_ENTRIES)
    if entries is not None and not isinstance(entries, dict):
        raise MediaMetaError("媒体信息 JSON 的 entries 格式无效")
    if isinstance(entries, dict):
        if len(entries) > MAX_MEDIA_META_ENTRIES:
            raise MediaMetaError("媒体信息 JSON 的图片条目数量超出限制")
        for key, item in entries.items():
            if len(key) > MAX_MEDIA_KEY_CHARS:
                raise MediaMetaError("媒体信息 JSON 的图片文件名过长")
            if not isinstance(item, dict):
                raise MediaMetaError("媒体信息 JSON 的图片条目格式无效")
            decode_entry(item)
    days = root.get(KEY_MEAL_DAYS)
    if days is not None and not isinstance(days, dict):
        raise MediaMetaError("媒体信息 JSON 的 mealDays 格式无效")
    if isinstance(days, dict):
        if len(days) > MAX_MEAL_DAY_ENTRIES:
            raise MediaMetaError("媒体信息 JSON 的日期条目数量超出限制")
        for key, item in days.items():
            if len(key) > MAX_DATE_KEY_CHARS:
                raise MediaMetaError("媒体信息 JSON 的日期键过长")
            if not isinstance(item, dict):
                raise MediaMetaError("媒体信息 JSON 的日期条目格式无效")
            normalize_day(
                {
                    "totalEnergyKjOverride": _bounded_int(item, "totalEnergyKjOverride"),
                    "note": _bounded_string(item, "note", MAX_MEAL_NOTE_CHARS) or "",
                }
            )
    return root


def decode(raw: str) -> dict[str, Any]:
    """Decoded view: {entries: {lowerName: entry}, mealDays: {dateIso: details}}."""
    root = parse_root(raw)
    entries: dict[str, Any] = {}
    for key, item in (root.get(KEY_ENTRIES) or {}).items():
        entries[key.lower()] = decode_entry(item)
    meal_days: dict[str, Any] = {}
    for key, item in (root.get(KEY_MEAL_DAYS) or {}).items():
        try:
            date.fromisoformat(key)
        except ValueError:
            continue
        details = normalize_day(
            {
                "totalEnergyKjOverride": _bounded_int(item, "totalEnergyKjOverride"),
                "note": _bounded_string(item, "note", MAX_MEAL_NOTE_CHARS) or "",
            }
        )
        if details["totalEnergyKjOverride"] is not None or details["note"]:
            meal_days[key] = details
    return {"entries": entries, "mealDays": meal_days}


def _required_object_or_create(root: dict[str, Any], key: str) -> dict[str, Any]:
    existing = root.get(key)
    if existing is None:
        created: dict[str, Any] = {}
        root[key] = created
        return created
    if not isinstance(existing, dict):
        raise MediaMetaError(f"媒体信息 JSON 的 {key} 格式无效")
    return existing


def _set_or_remove(target: dict[str, Any], key: str, value: Any) -> None:
    if value is None:
        target.pop(key, None)
    else:
        target[key] = value


def prepare_for_write(root: dict[str, Any]) -> None:
    previous_version = root.get(KEY_VERSION)
    version = previous_version if isinstance(previous_version, int) and not isinstance(previous_version, bool) else 1
    root[KEY_VERSION] = max(version, CURRENT_VERSION)
    if not isinstance(root.get(KEY_ENTRIES), dict):
        root[KEY_ENTRIES] = {}


def normalize_media_key(key: str) -> str:
    normalized = key.strip().lower()
    if not normalized:
        raise ApiError(400, "invalid_name", "无法确定图片文件名，热量未记录")
    if "/" in normalized or "\\" in normalized or normalized in (".", ".."):
        raise ApiError(400, "invalid_name", "媒体文件名无效")
    return normalized


def normalize_entry(entry: dict[str, Any]) -> dict[str, Any]:
    energy = entry.get("energyKj")
    if energy is not None and not (isinstance(energy, int) and 0 <= energy <= MAX_MEAL_ENERGY_KJ):
        energy = None
    place = (entry.get("place") or "").strip()[:MAX_PLACE_CHARS] or None
    foods = []
    for food in (entry.get("foods") or [])[:MAX_MEAL_FOODS]:
        name = (food.get("name") or "").strip()[:MAX_MEAL_FOOD_NAME_CHARS]
        if not name:
            continue
        amount = (food.get("amount") or "").strip()[:MAX_MEAL_AMOUNT_CHARS] or None
        unit = (food.get("unit") or "").strip()[:MAX_MEAL_UNIT_CHARS] or None
        food_energy = food.get("energyKj")
        if food_energy is not None and (
            not isinstance(food_energy, int) or not (0 <= food_energy <= MAX_MEAL_ENERGY_KJ)
        ):
            food_energy = None
        foods.append({"name": name, "amount": amount, "unit": unit, "energyKj": food_energy})
    return {"energyKj": energy, "lat": entry.get("lat"), "lng": entry.get("lng"), "place": place, "foods": foods}


def encode_entry_into(item: dict[str, Any], entry: dict[str, Any]) -> None:
    """Overwrite only owned keys; unknown sibling fields inside `item` are preserved."""
    _set_or_remove(item, "energyKj", entry["energyKj"])
    lat = entry["lat"] if isinstance(entry["lat"], float) and math.isfinite(entry["lat"]) else None
    lng = entry["lng"] if isinstance(entry["lng"], float) and math.isfinite(entry["lng"]) else None
    _set_or_remove(item, "lat", lat)
    _set_or_remove(item, "lng", lng)
    _set_or_remove(item, "place", entry["place"])
    if not entry["foods"]:
        item.pop("foods", None)
    else:
        encoded_foods = []
        for food in entry["foods"]:
            encoded: dict[str, Any] = {"name": food["name"]}
            if food.get("amount") is not None:
                encoded["amount"] = food["amount"]
            if food.get("unit") is not None:
                encoded["unit"] = food["unit"]
            if food.get("energyKj") is not None:
                encoded["energyKj"] = food["energyKj"]
            encoded_foods.append(encoded)
        item["foods"] = encoded_foods


def _dump(root: dict[str, Any]) -> str:
    return json.dumps(root, ensure_ascii=False, indent=2)


def update_entry(raw: str, key: str, transform: Callable[[dict[str, Any]], dict[str, Any]]) -> str:
    normalized_key = normalize_media_key(key)
    root = parse_root(raw)
    entries = _required_object_or_create(root, KEY_ENTRIES)
    matching_keys = [k for k in entries.keys() if k.lower() == normalized_key]
    item: dict[str, Any] = {}
    for match in matching_keys:
        candidate = entries[match]
        if isinstance(candidate, dict):
            item = candidate
            break
    updated = normalize_entry(transform(decode_entry(item)))
    for match in matching_keys:
        entries.pop(match, None)
    encode_entry_into(item, updated)
    entries[normalized_key] = item
    prepare_for_write(root)
    return _dump(root)


def remove_entry(raw: str, key: str) -> str | None:
    normalized_key = normalize_media_key(key)
    root = parse_root(raw)
    entries = root.get(KEY_ENTRIES)
    if not isinstance(entries, dict):
        return None
    matching_keys = [k for k in entries.keys() if k.lower() == normalized_key]
    if not matching_keys:
        return None
    for match in matching_keys:
        entries.pop(match, None)
    prepare_for_write(root)
    return _dump(root)


def update_meal_day(raw: str, date_iso: str, details: dict[str, Any]) -> str:
    try:
        date.fromisoformat(date_iso)
    except ValueError:
        raise ApiError(400, "invalid_date", "Invalid dateIso")
    root = parse_root(raw)
    days = _required_object_or_create(root, KEY_MEAL_DAYS)
    item = days.get(date_iso)
    item = dict(item) if isinstance(item, dict) else {}
    normalized = normalize_day(details)
    _set_or_remove(item, "totalEnergyKjOverride", normalized["totalEnergyKjOverride"])
    _set_or_remove(item, "note", normalized["note"] or None)
    if not item:
        days.pop(date_iso, None)
    else:
        days[date_iso] = item
    if not days:
        root.pop(KEY_MEAL_DAYS, None)
    prepare_for_write(root)
    return _dump(root)


# ---------------------------------------------------------------------------
# File boundary under media_mutex
# ---------------------------------------------------------------------------

def _read_bounded(path: Path) -> str:
    size = path.stat().st_size
    if size > MEDIA_META_MAX_BYTES:
        raise MediaMetaError("媒体信息 JSON 超过安全上限；原文件未被覆盖")
    return path.read_text(encoding="utf-8")


def read_media_meta_raw(media_dir: Path = MEDIA_DIR) -> tuple[str, Path | None]:
    """Returns (raw, current_file). Falls back through previous/legacy/pending copies.

    A corrupt or oversized current file never causes an empty-document overwrite.
    """
    candidates = [
        MEDIA_META_FILE_NAME,
        MEDIA_META_PREVIOUS_FILE_NAME,
        LEGACY_MEDIA_META_FILE_NAME,
        MEDIA_META_PENDING_FILE_NAME,
    ]
    found: list[tuple[Path, str]] = []
    lowered = {p.name.lower(): p for p in media_dir.iterdir()} if media_dir.is_dir() else {}
    for name in candidates:
        path = lowered.get(name.lower())
        if path is not None and not path.is_symlink() and path.is_file():
            found.append((path, name))
    if not found:
        return "{}", media_dir / MEDIA_META_FILE_NAME
    for path, _name in found:
        try:
            raw = _read_bounded(path)
            parse_root(raw)
            return raw, path
        except (OSError, UnicodeDecodeError, ApiError):
            continue
    raise MediaMetaError("媒体信息 JSON 已损坏或超过安全上限；原文件未被覆盖")


def get_decoded(media_dir: Path = MEDIA_DIR) -> dict[str, Any]:
    with media_mutex:
        raw, _current = read_media_meta_raw(media_dir)
        return decode(raw)


def current_media_meta_target(media_dir: Path) -> Path:
    """Android DiaryFileRepository.currentMediaMetaFile: the existing `dc-media.json`
    (case-insensitive match), else the canonical short name. Future updates always
    commit to this file even when the read was satisfied by a previous/pending/legacy
    fallback copy; those copies stay untouched and recoverable.
    """
    if media_dir.is_dir():
        for p in sorted(media_dir.iterdir()):
            try:
                if not p.is_symlink() and p.is_file() and p.name.lower() == MEDIA_META_FILE_NAME.lower():
                    return p
            except OSError:
                continue
    return media_dir / MEDIA_META_FILE_NAME


def write_media_meta_raw(encoded: str, expected_original: str, media_dir: Path = MEDIA_DIR) -> None:
    """previous -> pending -> target commit flow with expected-original recheck."""
    if len(encoded.encode("utf-8")) > MEDIA_META_MAX_BYTES:
        raise ApiError(413, "media_meta_too_large", "媒体信息 JSON 超过 2 MiB 上限")
    parse_root(encoded)
    original, _read_path = read_media_meta_raw(media_dir)
    if original != expected_original:
        raise MediaMetaConflictError()
    media_dir.mkdir(parents=True, exist_ok=True)
    if original != "{}":
        previous_path = media_dir / MEDIA_META_PREVIOUS_FILE_NAME
        safe_write_text(previous_path, original)
    pending_path = media_dir / MEDIA_META_PENDING_FILE_NAME
    safe_write_text(pending_path, encoded)
    # Always commit future updates to the shorter canonical name, mirroring
    # DiaryFileRepository.kt — never back into the sidecar that satisfied the read.
    target_path = current_media_meta_target(media_dir)
    try:
        safe_write_text(target_path, encoded)
    except Exception:
        # Best-effort restore; the verified previous/pending copies remain recoverable.
        if original != "{}":
            try:
                safe_write_text(target_path, original)
            except Exception:
                pass
        raise
    pending_path.unlink(missing_ok=True)


def update_media_meta_entry(key: str, transform: Callable[[dict[str, Any]], dict[str, Any]],
                            media_dir: Path = MEDIA_DIR) -> dict[str, Any]:
    with media_mutex:
        original, _current = read_media_meta_raw(media_dir)
        encoded = update_entry(original, key, transform)
        write_media_meta_raw(encoded, original, media_dir)
        return decode(encoded)


def remove_media_meta_entry(key: str, media_dir: Path = MEDIA_DIR) -> None:
    with media_mutex:
        original, _current = read_media_meta_raw(media_dir)
        encoded = remove_entry(original, key)
        if encoded is None:
            return
        write_media_meta_raw(encoded, original, media_dir)


def put_photo_meta(payload: dict[str, Any], media_dir: Path = MEDIA_DIR) -> dict[str, Any]:
    """PUT /api/meals/photo-meta body handler.

    Per-photo owned fields: energyKj / place / lat / lng / foods. When `dateIso` is
    present, day-scoped `note` / `totalEnergyKjOverride` are applied to `mealDays`.
    """
    file_name = payload.get("fileName")
    if not isinstance(file_name, str) or not file_name.strip():
        raise ApiError(400, "invalid_name", "fileName is required")

    def transform(entry: dict[str, Any]) -> dict[str, Any]:
        updated = dict(entry)
        if "energyKj" in payload:
            value = payload.get("energyKj")
            if value is not None and (not isinstance(value, int) or isinstance(value, bool) or not (0 <= value <= MAX_MEAL_ENERGY_KJ)):
                raise ApiError(400, "invalid_value", "energyKj 无效")
            updated["energyKj"] = value
        if "place" in payload:
            place = payload.get("place")
            updated["place"] = place.strip()[:MAX_PLACE_CHARS] if isinstance(place, str) and place.strip() else None
        if "lat" in payload:
            updated["lat"] = _finite_double(payload.get("lat"))
        if "lng" in payload:
            updated["lng"] = _finite_double(payload.get("lng"))
        if "foods" in payload:
            foods = payload.get("foods")
            if foods is None:
                updated["foods"] = []
            else:
                if not isinstance(foods, list) or len(foods) > MAX_MEAL_FOODS:
                    raise ApiError(400, "invalid_value", "foods 无效")
                updated["foods"] = decode_foods(foods)
        return updated

    with media_mutex:
        original, _current = read_media_meta_raw(media_dir)
        encoded = update_entry(original, file_name, transform)
        date_iso = payload.get("dateIso")
        note = payload.get("note")
        override = payload.get("totalEnergyKjOverride")
        if date_iso or note is not None or override is not None:
            existing_days = decode(encoded)["mealDays"]
            day_details = existing_days.get(str(date_iso or ""), {})
            if note is not None:
                if not isinstance(note, str) or len(note) > MAX_MEAL_NOTE_CHARS:
                    raise ApiError(400, "invalid_value", "备注过长")
                day_details["note"] = note
            if override is not None:
                if not isinstance(override, int) or isinstance(override, bool) or not (0 <= override <= MAX_MEAL_ENERGY_KJ):
                    raise ApiError(400, "invalid_value", "总热量超出允许范围")
                day_details["totalEnergyKjOverride"] = override
            if date_iso:
                encoded = update_meal_day(encoded, str(date_iso), day_details)
        write_media_meta_raw(encoded, original, media_dir)
        decoded = decode(encoded)
    key = normalize_media_key(file_name)
    entry = decoded["entries"].get(key)
    day_view = decoded["mealDays"].get(str(date_iso)) if date_iso else None
    return {"fileName": key, "entry": entry or {}, "mealDay": day_view}
