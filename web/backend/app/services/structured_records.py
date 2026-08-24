"""Structured records workspace: `.deskcubby/*.json` + `<!--dc:f_<id>-->` Markdown protocol.

Port of data/structuredrecords/*.kt: StructuredRecordsCodec (settings/fields/records/
statistics JSON, forward-compatible), StructuredMarkdownProtocol (paired HTML-comment
markers with fenced-code protection), StructuredFieldNormalizer (word/number/type/time/
duration), JournalDayEngine (day boundary default 05:00), MetricEvaluator (derived
formula metrics), and the Room-mirror index tables structured_record_files /
structured_record_occurrences.
"""
from __future__ import annotations

import json
import math
import re
import threading
import time
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any, Callable

from ..core.config import DIARY_DIR, PRIVATE_DIR, STRUCTURED_DIR
from ..core.errors import ApiError
from . import diary_files

FILE_SETTINGS = "settings.json"
FILE_FIELDS = "fields.json"
FILE_RECORDS = "records.json"
FILE_STATISTICS = "statistics.json"

MAX_FIELDS = 400
MAX_TEMPLATES = 400
MAX_METRICS = 200
MAX_OPTIONS = 200
MAX_NAME_CHARS = 80
MAX_TEXT_CHARS = 500

DEFAULT_DAY_BOUNDARY = "05:00"
TODAY_DIARY_SWITCH_PATH = PRIVATE_DIR / "today-diary-settings.json"
SYSTEM_FIELD_SLEEP_TIME = "f_system_sleep_time"
SYSTEM_FIELD_WAKE_TIME = "f_system_wake_time"

FIELD_TYPES = ("word", "number", "type", "time", "duration")
FIELD_SOURCES = ("manual", "system", "agent")
SELECTORS = ("first", "last", "min", "max", "sum", "average", "count")
METRIC_RESULT_TYPES = ("number", "time", "duration")
CHART_PERIODS = ("day", "week", "month")

structured_mutex = threading.RLock()

# ---------------------------------------------------------------------------
# JournalDayEngine
# ---------------------------------------------------------------------------

_TIME_PATTERN = re.compile(r"^(\d{1,2}):(\d{2})$")


def parse_boundary(value: str | None) -> int | None:
    if not value:
        return None
    m = _TIME_PATTERN.match(value.strip())
    if not m:
        return None
    hour, minute = int(m.group(1)), int(m.group(2))
    if hour > 23 or minute > 59:
        return None
    return hour * 60 + minute


def format_boundary(minutes_since_midnight: int) -> str:
    safe = max(0, min(int(minutes_since_midnight), 24 * 60 - 1))
    return "%02d:%02d" % (safe // 60, safe % 60)


def resolve_today_diary_date(now: datetime | None = None, switch_minutes: int | None = None) -> date:
    now = now or datetime.now()
    switch = switch_minutes if switch_minutes is not None else (parse_boundary(DEFAULT_DAY_BOUNDARY) or 5 * 60)
    return now.date() if now.hour * 60 + now.minute >= switch else now.date() - timedelta(days=1)


# ---------------------------------------------------------------------------
# StructuredRecordsCodec
# ---------------------------------------------------------------------------

def _read_workspace(file_name: str) -> str | None:
    path = STRUCTURED_DIR / file_name
    if path.is_symlink() or not path.is_file():
        return None
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return None


def _write_workspace(file_name: str, content: str) -> None:
    from ..core.fs import safe_write_text

    STRUCTURED_DIR.mkdir(parents=True, exist_ok=True)
    safe_write_text(STRUCTURED_DIR / file_name, content)


def decode_settings(raw: str | None) -> dict[str, Any]:
    """Decode the portable workspace settings.

    Calendar ownership is now the natural local date.  Legacy ``dayBoundary``
    and ``dayBoundaryHistory`` fields are intentionally ignored; the one
    switch used by “Open today's diary” is device-local.
    """
    if raw is None:
        return {"schemaVersion": 1, "markdownProtocolVersion": 1}
    try:
        json_obj = json.loads(raw)
    except ValueError:
        json_obj = {}
    if not isinstance(json_obj, dict):
        json_obj = {}
    return {
        "schemaVersion": _opt_int(json_obj, "schemaVersion", 1),
        "markdownProtocolVersion": _opt_int(json_obj, "markdownProtocolVersion", 1),
    }


def _opt_int(obj: dict[str, Any], key: str, default: int) -> int:
    value = obj.get(key)
    return value if isinstance(value, int) and not isinstance(value, bool) else default


def load_settings_doc() -> dict[str, Any]:
    with structured_mutex:
        doc = decode_settings(_read_workspace(FILE_SETTINGS))
        if _read_workspace(FILE_SETTINGS) is None:
            _write_workspace(FILE_SETTINGS, json.dumps(
                {"schemaVersion": doc["schemaVersion"], "markdownProtocolVersion": doc["markdownProtocolVersion"]})
            )
        return doc


def save_settings_doc(doc: dict[str, Any]) -> None:
    with structured_mutex:
        root = {
            "schemaVersion": doc.get("schemaVersion", 1),
            "markdownProtocolVersion": doc.get("markdownProtocolVersion", 1),
        }
        _write_workspace(FILE_SETTINGS, json.dumps(root, ensure_ascii=False))


def load_today_diary_switch_time() -> str:
    if not TODAY_DIARY_SWITCH_PATH.is_file() or TODAY_DIARY_SWITCH_PATH.stat().st_size > 4 * 1024:
        return DEFAULT_DAY_BOUNDARY
    try:
        root = json.loads(TODAY_DIARY_SWITCH_PATH.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return DEFAULT_DAY_BOUNDARY
    value = root.get("todayDiarySwitchTime") if isinstance(root, dict) else None
    minutes = parse_boundary(value if isinstance(value, str) else None)
    return format_boundary(minutes) if minutes is not None else DEFAULT_DAY_BOUNDARY


def save_today_diary_switch_time(value: str) -> str:
    minutes = parse_boundary(value)
    if minutes is None:
        raise ApiError(400, "invalid_value", "today diary switch time is invalid")
    normalized = format_boundary(minutes)
    from ..core.fs import safe_write_text

    TODAY_DIARY_SWITCH_PATH.parent.mkdir(parents=True, exist_ok=True)
    safe_write_text(
        TODAY_DIARY_SWITCH_PATH,
        json.dumps({"todayDiarySwitchTime": normalized}, ensure_ascii=False, separators=(",", ":")),
    )
    return normalized


def decode_fields(raw: str | None) -> list[dict[str, Any]]:
    if not raw:
        return []
    try:
        json_obj = json.loads(raw)
    except ValueError:
        return []
    array = json_obj.get("fields") if isinstance(json_obj, dict) else None
    if not isinstance(array, list):
        return []
    result: list[dict[str, Any]] = []
    ids: set[str] = set()
    for item in array:
        if not isinstance(item, dict):
            continue
        fid = str(item.get("id") or "").strip()[:120]
        if not fid or fid in ids:
            continue
        ftype = item.get("type")
        if ftype not in FIELD_TYPES:
            continue
        name = str(item.get("name") or "").strip()[:MAX_NAME_CHARS]
        if not name:
            continue
        source = item.get("source")
        options_raw = item.get("options")
        options: list[str] = []
        if isinstance(options_raw, list):
            for opt in options_raw:
                value = str(opt).strip()[:MAX_NAME_CHARS]
                if value and value not in options:
                    options.append(value)
                if len(options) >= MAX_OPTIONS:
                    break
        ids.add(fid)
        result.append(
            {
                "id": fid,
                "name": name,
                "type": ftype,
                "source": source if source in FIELD_SOURCES else "manual",
                "unit": (str(item.get("unit")).strip()[:20] or None) if item.get("unit") else None,
                "options": options,
                "allowCustomOption": bool(item["allowCustomOption"]) if "allowCustomOption" in item else True,
                "collector": (str(item.get("collector")).strip()[:80] or None) if item.get("collector") else None,
                "archived": bool(item.get("archived")),
                "sortOrder": _opt_int(item, "sortOrder", 0),
            }
        )
        if len(result) >= MAX_FIELDS:
            break
    result.sort(key=lambda f: f["sortOrder"])
    return result


def encode_fields(fields: list[dict[str, Any]]) -> str:
    encoded = []
    for field in fields:
        item: dict[str, Any] = {
            "id": field["id"], "name": field["name"], "type": field["type"],
            "source": field.get("source") or "manual",
        }
        if field.get("unit"):
            item["unit"] = field["unit"]
        if field.get("options"):
            item["options"] = field["options"]
        item["allowCustomOption"] = bool(field.get("allowCustomOption", True))
        if field.get("collector"):
            item["collector"] = field["collector"]
        item["archived"] = bool(field.get("archived"))
        if field.get("sortOrder"):
            item["sortOrder"] = field["sortOrder"]
        encoded.append(item)
    return json.dumps({"schemaVersion": 1, "fields": encoded}, ensure_ascii=False)


DEFAULT_FIELDS: list[dict[str, Any]] = [
    {"id": "f_word_today", "name": "今日一句话", "type": "word", "source": "manual", "options": [], "allowCustomOption": True, "archived": False, "sortOrder": 0},
    {"id": "f_number_pushups", "name": "俯卧撑次数", "type": "number", "source": "manual", "unit": "次", "options": [], "allowCustomOption": True, "archived": False, "sortOrder": 1},
    {"id": "f_type_top_color", "name": "今天衣服颜色", "type": "type", "source": "manual", "options": ["黑色", "白色", "蓝色", "灰色"], "allowCustomOption": True, "archived": False, "sortOrder": 2},
    {"id": "f_time_lunch", "name": "午饭时间", "type": "time", "source": "manual", "options": [], "allowCustomOption": True, "archived": False, "sortOrder": 3},
    {"id": "f_duration_nap", "name": "午睡时长", "type": "duration", "source": "manual", "options": [], "allowCustomOption": True, "archived": False, "sortOrder": 4},
]

DEFAULT_TEMPLATES: list[dict[str, Any]] = [
    {"id": "r_word_today", "name": "今日一句话", "archived": False, "sortOrder": 0,
     "segments": [{"kind": "field", "fieldId": "f_word_today"}]},
    {"id": "r_pushups", "name": "俯卧撑次数", "archived": False, "sortOrder": 1,
     "segments": [{"kind": "text", "value": "做了 "}, {"kind": "field", "fieldId": "f_number_pushups"},
                  {"kind": "text", "value": " 个俯卧撑"}]},
    {"id": "r_top_color", "name": "今天衣服颜色", "archived": False, "sortOrder": 2,
     "segments": [{"kind": "text", "value": "上衣："}, {"kind": "field", "fieldId": "f_type_top_color"}]},
    {"id": "r_lunch_time", "name": "午饭时间", "archived": False, "sortOrder": 3,
     "segments": [{"kind": "text", "value": "午饭："}, {"kind": "field", "fieldId": "f_time_lunch"}]},
    {"id": "r_nap_duration", "name": "午睡时长", "archived": False, "sortOrder": 4,
     "segments": [{"kind": "text", "value": "午睡："}, {"kind": "field", "fieldId": "f_duration_nap"}]},
]


def load_fields() -> list[dict[str, Any]]:
    with structured_mutex:
        decoded = decode_fields(_read_workspace(FILE_FIELDS))
        if not decoded:
            _write_workspace(FILE_FIELDS, encode_fields(DEFAULT_FIELDS))
            return list(DEFAULT_FIELDS)
        return decoded


def save_fields(fields: list[dict[str, Any]]) -> list[dict[str, Any]]:
    with structured_mutex:
        _write_workspace(FILE_FIELDS, encode_fields(fields))
        return fields


def load_templates() -> list[dict[str, Any]]:
    with structured_mutex:
        decoded = _decode_templates(_read_workspace(FILE_RECORDS))
        if not decoded:
            _write_workspace(FILE_RECORDS, _encode_templates(DEFAULT_TEMPLATES))
            return list(DEFAULT_TEMPLATES)
        return decoded


def _encode_templates(templates: list[dict[str, Any]]) -> str:
    return json.dumps({"schemaVersion": 1, "records": templates}, ensure_ascii=False)


def _decode_templates(raw: str | None) -> list[dict[str, Any]]:
    if not raw:
        return []
    try:
        json_obj = json.loads(raw)
    except ValueError:
        return []
    array = json_obj.get("records") if isinstance(json_obj, dict) else None
    if not isinstance(array, list):
        return []
    result: list[dict[str, Any]] = []
    ids: set[str] = set()
    for item in array:
        if not isinstance(item, dict):
            continue
        tid = str(item.get("id") or "").strip()[:120]
        if not tid or tid in ids:
            continue
        segments_raw = item.get("segments")
        if not isinstance(segments_raw, list):
            continue
        segments: list[dict[str, Any]] = []
        for seg in segments_raw[:64]:
            if not isinstance(seg, dict):
                continue
            if seg.get("kind") == "text":
                value = str(seg.get("value") or "")[:MAX_TEXT_CHARS]
                if value:
                    segments.append({"kind": "text", "value": value})
            elif seg.get("kind") == "field":
                fid = str(seg.get("fieldId") or "").strip()[:120]
                if fid:
                    segments.append({"kind": "field", "fieldId": fid})
        if not segments:
            continue
        ids.add(tid)
        result.append(
            {"id": tid, "name": (str(item.get("name") or "").strip()[:MAX_NAME_CHARS] or "记录"),
             "segments": segments, "archived": bool(item.get("archived")),
             "sortOrder": _opt_int(item, "sortOrder", 0)}
        )
        if len(result) >= MAX_TEMPLATES:
            break
    result.sort(key=lambda t: t["sortOrder"])
    return result


def decode_metrics(raw: str | None) -> list[dict[str, Any]]:
    if not raw:
        return []
    try:
        json_obj = json.loads(raw)
    except ValueError:
        return []
    array = json_obj.get("metrics") if isinstance(json_obj, dict) else None
    if not isinstance(array, list):
        return []
    result: list[dict[str, Any]] = []
    ids: set[str] = set()
    for item in array:
        if not isinstance(item, dict):
            continue
        mid = str(item.get("id") or "").strip()[:120]
        if not mid or mid in ids:
            continue
        result_type = item.get("resultType")
        if result_type not in METRIC_RESULT_TYPES:
            continue
        expression = _decode_expression(item.get("expression"), depth=0)
        if expression is None:
            continue
        display = item.get("display") if isinstance(item.get("display"), dict) else {}
        period = display.get("period")
        ids.add(mid)
        result.append(
            {
                "id": mid,
                "name": (str(item.get("name") or "").strip()[:MAX_NAME_CHARS] or "统计"),
                "resultType": result_type,
                "expression": expression,
                "display": {
                    "chart": (str(display.get("chart") or "").strip()[:30] or "line"),
                    "period": period if period in CHART_PERIODS else "day",
                },
                "archived": bool(item.get("archived")),
                "sortOrder": _opt_int(item, "sortOrder", 0),
            }
        )
        if len(result) >= MAX_METRICS:
            break
    result.sort(key=lambda m: m["sortOrder"])
    return result


def load_metrics() -> list[dict[str, Any]]:
    with structured_mutex:
        return decode_metrics(_read_workspace(FILE_STATISTICS))


def _decode_expression(node: Any, depth: int) -> dict[str, Any] | None:
    if not isinstance(node, dict) or depth > 12:
        return None
    op = node.get("op")
    if op == "fieldRef":
        fid = str(node.get("fieldId") or "").strip()[:120]
        if not fid:
            return None
        day_offset = _opt_int(node, "dayOffset", 0)
        day_offset = max(-40000, min(day_offset, 40000))
        selector = node.get("selector")
        return {"op": "fieldRef", "fieldId": fid, "dayOffset": day_offset,
                "selector": selector if selector in SELECTORS else "last"}
    if op == "constant":
        value = node.get("value")
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)):
            return None
        return {"op": "constant", "value": float(value)}
    if op == "timeDiff":
        end = _decode_expression(node.get("end"), depth + 1)
        start = _decode_expression(node.get("start"), depth + 1)
        if end is None or start is None:
            return None
        return {"op": "timeDiff", "end": end, "start": start}
    if op in ("add", "subtract", "multiply", "divide"):
        left = _decode_expression(node.get("left"), depth + 1)
        right = _decode_expression(node.get("right"), depth + 1)
        if left is None or right is None:
            return None
        return {"op": op, "left": left, "right": right}
    return None


# ---------------------------------------------------------------------------
# StructuredMarkdownProtocol
# ---------------------------------------------------------------------------

MARKER_PREFIX = "<!--dc:"
MARKER_SUFFIX = "-->"
_FENCE_RE = re.compile(r"^\s*(`{3,}|~{3,})")


def open_marker(field_id: str) -> str:
    return f"{MARKER_PREFIX}{field_id}{MARKER_SUFFIX}"


def close_marker(field_id: str) -> str:
    return f"{MARKER_PREFIX}/{field_id}{MARKER_SUFFIX}"


def _fenced_spans(content: str) -> list[tuple[int, int]]:
    """Inclusive [start, last] spans of fenced code blocks (CommonMark rules)."""
    spans: list[tuple[int, int]] = []
    line_start = 0
    fence_char: str | None = None
    fence_len = 0
    span_start = 0
    length = len(content)
    while line_start <= length:
        line_end = content.find("\n", line_start)
        if line_end < 0:
            line_end = length
        line = content[line_start:line_end]
        if fence_char is None:
            m = _FENCE_RE.match(line)
            if m:
                run = m.group(1)
                fence_char = run[0]
                fence_len = len(run)
                span_start = line_start
        else:
            trimmed = line.strip()
            if len(trimmed) >= fence_len and trimmed and all(ch == fence_char for ch in trimmed):
                spans.append((span_start, line_end))
                fence_char = None
        line_start = line_end + 1
    return spans


def parse_occurrences(content: str) -> list[dict[str, Any]]:
    """Port of StructuredMarkdownProtocol.parse (fence-aware, nesting-safe)."""
    result: list[dict[str, Any]] = []
    fence_spans = _fenced_spans(content)
    cursor = 0
    order = 0
    fence_index = 0
    while True:
        while fence_index < len(fence_spans) and cursor >= fence_spans[fence_index][1]:
            fence_index += 1
        open_at = content.find(MARKER_PREFIX, cursor)
        if open_at < 0:
            break
        if fence_index < len(fence_spans) and fence_spans[fence_index][0] <= open_at < fence_spans[fence_index][1]:
            cursor = fence_spans[fence_index][1]
            fence_index += 1
            continue
        close_at = content.find(MARKER_SUFFIX, open_at)
        if close_at < 0:
            break
        field_id = content[open_at + len(MARKER_PREFIX):close_at].strip()
        if not field_id or "/" in field_id:
            cursor = close_at + len(MARKER_SUFFIX)
            continue
        value_start = close_at + len(MARKER_SUFFIX)
        close_marker = f"{MARKER_PREFIX}/{field_id}{MARKER_SUFFIX}"
        value_end = content.find(close_marker, value_start)
        if value_end < 0:
            cursor = value_start
            continue
        nested_open = content.find(f"{MARKER_PREFIX}{field_id}{MARKER_SUFFIX}", value_start)
        if value_start <= nested_open < value_end:
            cursor = value_start
            continue
        raw_value = content[value_start:value_end]
        result.append(
            {
                "fieldId": field_id,
                "rawValue": raw_value,
                "startIndex": open_at,
                "endIndex": value_end + len(close_marker),
                "orderInFile": order,
            }
        )
        order += 1
        cursor = value_end + len(close_marker)
    return result


def replace_value(content: str, occurrence: dict[str, Any], new_raw_value: str) -> str:
    replacement = open_marker(occurrence["fieldId"]) + new_raw_value + close_marker(occurrence["fieldId"])
    return content[: occurrence["startIndex"]] + replacement + content[occurrence["endIndex"]:]


def strip_markers(content: str) -> str:
    occurrences = parse_occurrences(content)
    if not occurrences:
        return content
    output = content
    for occurrence in reversed(occurrences):
        output = (
            output[: occurrence["startIndex"]]
            + occurrence["rawValue"]
            + output[occurrence["endIndex"]:]
        )
    return output


# ---------------------------------------------------------------------------
# StructuredFieldNormalizer
# ---------------------------------------------------------------------------

class NormalizedValue:
    __slots__ = ("kind", "text", "number", "seconds", "minutes")

    def __init__(self, kind: str, text: str | None = None, number: float | None = None,
                 seconds: int | None = None, minutes: int | None = None):
        self.kind = kind
        self.text = text
        self.number = number
        self.seconds = seconds
        self.minutes = minutes

    @property
    def display(self) -> str:
        if self.kind == "number":
            return format_number(self.number or 0.0)
        if self.kind == "time":
            return "%02d:%02d" % ((self.minutes or 0) // 60, (self.minutes or 0) % 60)
        if self.kind == "duration":
            return format_duration(self.seconds or 0)
        return self.text or ""


_NUMBER_RE = re.compile(r"^([+-]?\d+(?:[.,，]\d+)?)\s*([^\d.,，]+)?$")
_TIME_VALUE_RE = re.compile(r"^(\d{1,2}):(\d{2})(?::(\d{2}))?$")


def normalize_value(field_type: str, raw: str) -> tuple[NormalizedValue | None, str | None]:
    text = raw.strip()
    if not text:
        return None, None
    if "<!--" in text or "-->" in text:
        return None, "内容包含保留标记 <!-- 与 -->"
    if field_type == "word":
        return NormalizedValue("word", text=text), None
    if field_type == "type":
        return NormalizedValue("type", text=text), None
    if field_type == "number":
        return _normalize_number(text)
    if field_type == "time":
        return _normalize_time(text)
    if field_type == "duration":
        return _normalize_duration(text)
    return None, "未知字段类型"


def _normalize_number(text: str) -> tuple[NormalizedValue | None, str | None]:
    m = _NUMBER_RE.match(text.strip())
    if not m:
        return None, "数值无效"
    numeric = m.group(1).replace(",", ".").replace("，", ".").replace("−", "-")
    try:
        value = float(numeric)
    except ValueError:
        return None, "数值无效"
    if not math.isfinite(value):
        return None, "数值无效"
    return NormalizedValue("number", number=value), None


def _normalize_time(text: str) -> tuple[NormalizedValue | None, str | None]:
    m = _TIME_VALUE_RE.match(text.strip())
    if not m:
        return None, "时间格式应为 HH:mm"
    hour, minute = int(m.group(1)), int(m.group(2))
    if hour > 23 or minute > 59:
        return None, "时间无效"
    second_text = m.group(3)
    if second_text and int(second_text) > 59:
        return None, "时间无效"
    return NormalizedValue("time", minutes=hour * 60 + minute), None


def _normalize_duration(text: str) -> tuple[NormalizedValue | None, str | None]:
    clean = text.strip().lower()
    if re.fullmatch(r"\d+", clean):
        return NormalizedValue("duration", seconds=max(0, int(clean))), None
    colon = re.fullmatch(r"(\d{1,3}):(\d{1,2})", clean)
    if colon:
        hours, minutes = int(colon.group(1)), int(colon.group(2))
        if minutes > 59:
            return None, "时长分钟数无效"
        return NormalizedValue("duration", seconds=hours * 3600 + minutes * 60), None
    hours = re.fullmatch(r"(\d+(?:\.\d+)?)\s*h", clean)
    if hours:
        return NormalizedValue("duration", seconds=int(float(hours.group(1)) * 3600)), None
    minutes = re.fullmatch(r"(\d+(?:\.\d+)?)\s*(?:m|min|分钟|分)?", clean)
    if minutes:
        return NormalizedValue("duration", seconds=int(float(minutes.group(1)) * 60)), None
    combined = re.fullmatch(r"(?:(\d+)\s*(?:h|小时)?\s*)?(?:(\d+)\s*(?:m|分钟|分))?", clean)
    if combined and (combined.group(1) or combined.group(2)):
        h = int(combined.group(1)) if combined.group(1) else 0
        m = int(combined.group(2)) if combined.group(2) else 0
        return NormalizedValue("duration", seconds=h * 3600 + m * 60), None
    return None, "时长格式无效"


def format_number(value: float) -> str:
    if value == int(value):
        return str(int(value))
    rounded = round(value * 100) / 100.0
    if rounded == int(rounded):
        return str(int(rounded))
    return str(rounded)


def format_duration(seconds: int) -> str:
    total = max(0, int(seconds))
    h, rem = divmod(total, 3600)
    m, s = divmod(rem, 60)
    minutes = m + (1 if s >= 30 else 0)
    hour = h + minutes // 60
    minute = minutes % 60
    return "%d:%02d" % (hour, minute) if hour > 0 else "0:%02d" % minute


def allowed_selectors(field_type: str) -> tuple[str, ...]:
    if field_type == "time":
        return ("first", "last", "min", "max")
    if field_type in ("number", "duration"):
        return SELECTORS
    return ("first", "last", "count")


def apply_selector(values: list[NormalizedValue], selector: str) -> NormalizedValue | None:
    if not values:
        return None
    if selector == "count":
        return NormalizedValue("number", number=float(len(values)))
    if selector == "first":
        return values[0]
    if selector == "last":
        return values[-1]
    first = values[0]
    if selector in ("min", "max"):
        if first.kind == "number":
            nums = [v for v in values if v.kind == "number"]
            if not nums:
                return None
            pick = min(nums, key=lambda v: v.number) if selector == "min" else max(nums, key=lambda v: v.number)
            return pick
        if first.kind == "duration":
            durs = [v for v in values if v.kind == "duration"]
            if not durs:
                return None
            pick = min(durs, key=lambda v: v.seconds) if selector == "min" else max(durs, key=lambda v: v.seconds)
            return pick
        if first.kind == "time":
            times = [v for v in values if v.kind == "time"]
            if not times:
                return None
            pick = min(times, key=lambda v: v.minutes) if selector == "min" else max(times, key=lambda v: v.minutes)
            return pick
        return None
    if selector == "sum":
        if first.kind == "number":
            nums = [v.number for v in values if v.kind == "number"]
            return NormalizedValue("number", number=sum(nums)) if nums else None
        if first.kind == "duration":
            durs = [v.seconds for v in values if v.kind == "duration"]
            return NormalizedValue("duration", seconds=sum(durs)) if durs else None
        return None
    if selector == "average":
        if first.kind == "number":
            nums = [v.number for v in values if v.kind == "number"]
            return NormalizedValue("number", number=sum(nums) / len(nums)) if nums else None
        if first.kind == "duration":
            durs = [v.seconds for v in values if v.kind == "duration"]
            return NormalizedValue("duration", seconds=int(sum(durs) / len(durs))) if durs else None
        return None
    return None


# ---------------------------------------------------------------------------
# Workspace config API
# ---------------------------------------------------------------------------

def get_config() -> dict[str, Any]:
    doc = load_settings_doc()
    boundary = load_today_diary_switch_time()
    fields = load_fields()
    templates = load_templates()
    return {
        "schemaVersion": doc["schemaVersion"],
        "markdownProtocolVersion": doc["markdownProtocolVersion"],
        "dayBoundary": boundary,
        "dayBoundaryHour": int(boundary.split(":")[0]),
        "todayDiarySwitchTime": boundary,
        "fields": fields,
        "templates": templates,
    }


def set_day_boundary(hours: Any) -> dict[str, Any]:
    if isinstance(hours, str) and ":" in hours:
        minutes = parse_boundary(hours)
    else:
        try:
            hours_int = int(hours)
        except (TypeError, ValueError):
            raise ApiError(400, "invalid_value", "day boundary hours must be an integer")
        if not (0 <= hours_int <= 23):
            raise ApiError(400, "invalid_value", "day boundary hours must be 0..23")
        minutes = hours_int * 60
    if minutes is None:
        raise ApiError(400, "invalid_value", "day boundary 无效")
    save_today_diary_switch_time(format_boundary(minutes))
    return get_config()


def put_fields(fields: Any) -> list[dict[str, Any]]:
    if not isinstance(fields, list):
        raise ApiError(400, "invalid_value", "fields must be a list")
    if len(fields) > MAX_FIELDS:
        raise ApiError(413, "too_many_fields", "字段数量超出上限")
    validated = decode_fields(encode_fields([f for f in fields if isinstance(f, dict)]))
    if len(validated) != len(fields):
        raise ApiError(400, "invalid_field", "存在无效的字段定义")
    seen: set[str] = set()
    for index, field in enumerate(validated):
        if field["id"] in seen:
            raise ApiError(400, "invalid_field", "存在重复的字段 ID")
        seen.add(field["id"])
        field["sortOrder"] = field["sortOrder"] or index
    return save_fields(validated)


# ---------------------------------------------------------------------------
# Record write (upsert one field value into the journal file for a day)
# ---------------------------------------------------------------------------

def upsert_record(con, settings: dict[str, Any], field_id: str, raw_value: str,
                  journal_day: str | None, document_name: str | None = None) -> dict[str, Any]:
    fields = {f["id"]: f for f in load_fields()}
    field = fields.get(field_id)
    if field is None:
        raise ApiError(400, "unknown_field", "字段不存在")
    normalized, error = normalize_value(field["type"], raw_value)
    if error:
        raise ApiError(400, "invalid_value", f"“{field['name']}”无效：{error}")
    if normalized is None:
        raise ApiError(400, "invalid_value", f"“{field['name']}”不能为空")
    display = normalized.display
    if document_name:
        # Diary-editor entry owns the exact document currently open, even when
        # its name does not follow the active filename pattern.
        current_document = diary_files.load_document(document_name)
        target_date = diary_files.extract_date(
            current_document["name"], current_document["lastModified"], settings.get("fileNamePattern")
        )
    elif journal_day:
        try:
            target_date = date.fromisoformat(str(journal_day)[:10])
        except ValueError:
            raise ApiError(400, "invalid_date", "journalDay 无效")
    else:
        # Structured records belong to the natural calendar date.  The
        # device-local switch time affects only the explicit “Open today's
        # diary” action and never moves recorded values between dates.
        target_date = date.today()

    def transform(content: str) -> str:
        occurrences = parse_occurrences(content)
        existing = next((o for o in occurrences if o["fieldId"] == field_id), None)
        if existing is not None:
            return replace_value(content, existing, display)
        line_ending = diary_files.preferred_line_ending(content)
        block = f"{field['name']}：{open_marker(field_id)}{display}{close_marker(field_id)}"
        block = diary_files.normalize_text_block(block, line_ending)
        separator = "" if not content or content.endswith("\n") or content.endswith("\r") else line_ending
        return content + separator + block

    with structured_mutex:
        if document_name:
            current_document = diary_files.load_document(document_name)
            updated = transform(current_document["content"])
            editor = (
                diary_files._editor_view(current_document)
                if updated == current_document["content"]
                else diary_files.save_document(
                    con, settings, current_document["name"], updated, current_document["sha256"]
                )
            )
        else:
            editor = diary_files.transform_diary_for_date(con, settings, target_date, transform)
        _parse_and_store_file(con, settings, DIARY_DIR / editor["name"])
    return {"success": True, "journalDay": target_date.isoformat(), "document": editor}


# ---------------------------------------------------------------------------
# Index (structured_record_files / structured_record_occurrences)
# ---------------------------------------------------------------------------

def _parse_and_store_file(con, settings: dict[str, Any], path: Path) -> int:
    try:
        raw = path.read_bytes()
        content = raw.decode("utf-8")
    except (OSError, UnicodeDecodeError):
        return 0
    st = path.stat()
    modified = int(st.st_mtime * 1000)
    journal_day = diary_files.extract_date(path.name, modified, settings.get("fileNamePattern")).isoformat()
    now = int(time.time() * 1000)
    sha = diary_files.sha256_bytes_hex(raw)
    occurrences = parse_occurrences(content)
    with con:
        con.execute("DELETE FROM structured_record_occurrences WHERE sourceFile = ?", (path.name,))
        for occurrence in occurrences:
            con.execute(
                "INSERT INTO structured_record_occurrences(journalDay, sourceFile, "
                "sourceFileModifiedAt, fieldId, rawValue, normalizedValue, valueType, "
                "orderInFile, parsedAt) VALUES(?,?,?,?,?,?,?,?,?)",
                (journal_day, path.name, modified, occurrence["fieldId"], occurrence["rawValue"],
                 occurrence["rawValue"], "raw", occurrence["orderInFile"], now),
            )
        con.execute(
            "INSERT INTO structured_record_files(sourceFile, modifiedAt, fileSize, sha256, parsedAt) "
            "VALUES(?,?,?,?,?) ON CONFLICT(sourceFile) DO UPDATE SET modifiedAt=excluded.modifiedAt, "
            "fileSize=excluded.fileSize, sha256=excluded.sha256, parsedAt=excluded.parsedAt",
            (path.name, modified, st.st_size, sha, now),
        )
    return len(occurrences)


def reindex(con, settings: dict[str, Any]) -> dict[str, Any]:
    """Full rebuild of both structured index tables by scanning workspace/diary."""
    with structured_mutex:
        files = diary_files.list_diary_file_metas()
        with con:
            con.execute("DELETE FROM structured_record_occurrences")
            con.execute("DELETE FROM structured_record_files")
        total = 0
        parsed = 0
        for meta in files:
            count = _parse_and_store_file(con, settings, DIARY_DIR / meta["name"])
            total += count
            parsed += 1
        return {"parsedFiles": parsed, "totalOccurrences": total}


def refresh_incremental(con, settings: dict[str, Any]) -> dict[str, Any]:
    """Stat-based incremental refresh: reparse changed files, drop missing ones."""
    with structured_mutex:
        metas = diary_files.list_diary_file_metas()
        rows = con.execute("SELECT sourceFile, modifiedAt, fileSize FROM structured_record_files").fetchall()
        known = {r["sourceFile"]: (int(r["modifiedAt"]), int(r["fileSize"])) for r in rows}
        active = set()
        parsed = 0
        total = 0
        for meta in metas:
            active.add(meta["name"])
            if known.get(meta["name"]) == (meta["lastModified"], meta["size"]):
                continue
            total += _parse_and_store_file(con, settings, DIARY_DIR / meta["name"])
            parsed += 1
        missing = [name for name in known if name not in active]
        if missing:
            with con:
                for name in missing:
                    con.execute("DELETE FROM structured_record_occurrences WHERE sourceFile = ?", (name,))
                    con.execute("DELETE FROM structured_record_files WHERE sourceFile = ?", (name,))
        count = con.execute("SELECT COUNT(*) AS n FROM structured_record_occurrences").fetchone()["n"]
        return {"parsedFiles": parsed, "totalOccurrences": int(count)}


def list_occurrences(con, from_day: str | None, to_day: str | None) -> list[dict[str, Any]]:
    sql = ("SELECT id, journalDay, sourceFile, sourceFileModifiedAt, fieldId, rawValue, "
           "normalizedValue, valueType, orderInFile, parsedAt FROM structured_record_occurrences")
    clauses, params = [], []
    if from_day:
        clauses.append("journalDay >= ?")
        params.append(from_day)
    if to_day:
        clauses.append("journalDay <= ?")
        params.append(to_day)
    if clauses:
        sql += " WHERE " + " AND ".join(clauses)
    sql += " ORDER BY journalDay DESC, orderInFile ASC"
    return [dict(r) for r in con.execute(sql, params).fetchall()]


# ---------------------------------------------------------------------------
# MetricEvaluator (derived formula metrics)
# ---------------------------------------------------------------------------

def evaluate_expression(expression: dict[str, Any], anchor: date,
                        provider: Callable[[str, date], list[NormalizedValue]]) -> tuple[str, float] | None:
    """Returns ("num", value) | ("dur", seconds); None propagates Missing."""
    op = expression["op"]
    if op == "constant":
        return ("num", float(expression["value"]))
    if op == "fieldRef":
        ref_date = anchor + timedelta(days=max(-40000, min(int(expression["dayOffset"]), 40000)))
        values = provider(expression["fieldId"], ref_date)
        selected = apply_selector(values, expression["selector"])
        if selected is None:
            return None
        if selected.kind == "number":
            return ("num", selected.number)
        if selected.kind == "duration":
            return ("dur", float(selected.seconds))
        if selected.kind == "time":
            return ("num", float((selected.minutes or 0) * 60))
        return None
    if op in ("add", "subtract", "multiply", "divide"):
        left = evaluate_expression(expression["left"], anchor, provider)
        right = evaluate_expression(expression["right"], anchor, provider)
        if left is None or right is None:
            return None
        (lk, lv), (rk, rv) = left, right
        if op in ("add", "subtract"):
            if lk == "num" and rk == "num":
                return ("num", lv + rv if op == "add" else lv - rv)
            if lk == "dur" and rk == "dur":
                return ("dur", lv + rv if op == "add" else lv - rv)
            return None
        if op == "multiply":
            if lk == "num" and rk == "num":
                return ("num", lv * rv)
            if lk == "num" and rk == "dur":
                return ("dur", lv * rv)
            if lk == "dur" and rk == "num":
                return ("dur", lv * rv)
            return None
        if op == "divide":
            if rv == 0:
                return None
            if lk == "num" and rk == "num":
                return ("num", lv / rv)
            if lk == "dur" and rk == "num":
                return ("dur", lv / rv)
            return None
        return None
    if op == "timeDiff":
        end = _as_natural_datetime(expression["end"], anchor, provider)
        start = _as_natural_datetime(expression["start"], anchor, provider)
        if end is None or start is None:
            return None
        end_dt, end_ref = end
        start_dt, _start_ref = start
        if end_dt < start_dt and end_ref == _field_ref_date(expression["start"], anchor) == _field_ref_date(expression["end"], anchor):
            end_dt = end_dt + timedelta(days=1)
        return ("dur", (end_dt - start_dt).total_seconds())
    return None


def _field_ref_date(node: dict[str, Any], anchor: date) -> date:
    offset = max(-40000, min(int(node.get("dayOffset", 0)), 40000))
    return anchor + timedelta(days=offset)


def _as_natural_datetime(node: dict[str, Any], anchor: date,
                         provider: Callable[[str, date], list[NormalizedValue]]):
    if node.get("op") != "fieldRef":
        return None
    ref_date = _field_ref_date(node, anchor)
    values = provider(node["fieldId"], ref_date)
    selected = apply_selector(values, node["selector"])
    if selected is None or selected.kind != "time":
        return None
    minutes = selected.minutes or 0
    moment = datetime.combine(ref_date, datetime.min.time()).replace(hour=minutes // 60, minute=minutes % 60)
    return (moment, ref_date)


# ---------------------------------------------------------------------------
# Statistics
# ---------------------------------------------------------------------------

def _circular_average_time(times: list[int]) -> str | None:
    if not times:
        return None
    angles = [t / 86400.0 * 2.0 * math.pi for t in (m * 60 for m in times)]
    mean_sin = sum(math.sin(a) for a in angles) / len(angles)
    mean_cos = sum(math.cos(a) for a in angles) / len(angles)
    angle = math.atan2(mean_sin, mean_cos)
    if angle < 0:
        angle += 2.0 * math.pi
    minute_of_day = int(round(angle / (2.0 * math.pi) * 1440.0)) % 1440
    return "%02d:%02d" % (minute_of_day // 60, minute_of_day % 60)


def field_auto_stats(con, field: dict[str, Any], start_iso: str, end_iso: str,
                     selector: str) -> dict[str, Any]:
    selector = selector if selector in allowed_selectors(field["type"]) else "last"
    rows = con.execute(
        "SELECT journalDay, rawValue, orderInFile FROM structured_record_occurrences "
        "WHERE fieldId = ? AND journalDay >= ? AND journalDay <= ? ORDER BY journalDay, orderInFile",
        (field["id"], start_iso, end_iso),
    ).fetchall()
    by_day: dict[str, list[str]] = {}
    for row in rows:
        by_day.setdefault(row["journalDay"], []).append(row["rawValue"])

    def normalize_all(raws: list[str]) -> list[NormalizedValue]:
        values = []
        for raw in raws:
            value, _error = normalize_value(field["type"], raw)
            if value is not None:
                values.append(value)
        return values

    points: list[dict[str, Any]] = []
    for day_iso in sorted(by_day.keys()):
        values = normalize_all(by_day[day_iso])
        if not values:
            continue
        selected = apply_selector(values, selector)
        if selected is None:
            continue
        if selected.kind == "number":
            points.append({"journalDay": day_iso, "chartValue": selected.number,
                           "display": format_number(selected.number)})
        elif selected.kind == "duration":
            points.append({"journalDay": day_iso, "chartValue": float(selected.seconds),
                           "display": format_duration(selected.seconds)})
        elif selected.kind == "time":
            points.append({"journalDay": day_iso, "chartValue": float(selected.minutes),
                           "display": "%02d:%02d" % (selected.minutes // 60, selected.minutes % 60)})
        else:
            points.append({"journalDay": day_iso, "chartValue": None,
                           "display": selected.display, "rawValue": selected.text})

    latest_row = max(rows, key=lambda r: r["orderInFile"]) if rows else None
    stats: dict[str, Any] = {
        "fieldId": field["id"],
        "name": field["name"],
        "type": field["type"],
        "unit": field.get("unit"),
        "count": len(rows),
        "series": points,
        "latest": latest_row["rawValue"] if latest_row else None,
    }
    if field["type"] == "number":
        nums = [p["chartValue"] for p in points if p["chartValue"] is not None]
        stats["average"] = format_number(sum(nums) / len(nums)) if nums else None
        stats["total"] = format_number(sum(nums)) if nums and selector == "sum" else None
    elif field["type"] == "duration":
        secs = [p["chartValue"] for p in points if p["chartValue"] is not None]
        stats["total"] = format_duration(int(sum(secs))) if secs and selector == "sum" else None
        stats["average"] = format_duration(int(sum(secs) / len(secs))) if secs else None
    elif field["type"] == "time":
        times = [int(p["chartValue"]) for p in points if p["chartValue"] is not None]
        stats["earliest"] = "%02d:%02d" % (min(times) // 60, min(times) % 60) if times else None
        stats["average"] = _circular_average_time(times)
    elif field["type"] == "type":
        counts: dict[str, int] = {}
        for raw in (r["rawValue"] for r in rows):
            counts[raw] = counts.get(raw, 0) + 1
        stats["categoryCounts"] = [
            {"category": category, "count": count}
            for category, count in sorted(counts.items(), key=lambda kv: -kv[1])
        ]
    return stats


def statistics(con, start_iso: str, end_iso: str, field_id: str | None,
               selector: str = "last") -> dict[str, Any]:
    fields = load_fields()
    selected_fields = [f for f in fields if f["id"] == field_id] if field_id else fields
    field_stats = [field_auto_stats(con, f, start_iso, end_iso, selector) for f in selected_fields]
    metrics = [m for m in load_metrics() if not m.get("archived")]
    metric_series: list[dict[str, Any]] = []
    if metrics:
        try:
            start = date.fromisoformat(start_iso)
            end = date.fromisoformat(end_iso)
        except ValueError:
            start, end = date.today(), date.today()
        if start <= end:
            occurrences = con.execute(
                "SELECT journalDay, fieldId, rawValue FROM structured_record_occurrences "
                "WHERE journalDay >= ? AND journalDay <= ?",
                ((start - timedelta(days=14)).isoformat(), end.isoformat()),
            ).fetchall()
            grouped: dict[tuple[str, str], list[str]] = {}
            for row in occurrences:
                grouped.setdefault((row["fieldId"], row["journalDay"]), []).append(row["rawValue"])
            fields_by_id = {f["id"]: f for f in fields}

            def provider(fid: str, day: date) -> list[NormalizedValue]:
                raws = grouped.get((fid, day.isoformat()), [])
                field = fields_by_id.get(fid)
                if field is None:
                    return []
                values = []
                for raw in raws:
                    value, _error = normalize_value(field["type"], raw)
                    if value is not None:
                        values.append(value)
                return values

            for metric in metrics:
                days = []
                cursor = start
                while cursor <= end:
                    result = evaluate_expression(metric["expression"], cursor, provider)
                    if result is None:
                        days.append({"journalDay": cursor.isoformat(), "chartValue": None, "display": None})
                    else:
                        kind, value = result
                        display = format_number(value) if kind == "num" else format_duration(int(value))
                        days.append({"journalDay": cursor.isoformat(), "chartValue": value, "display": display})
                    cursor += timedelta(days=1)
                metric_series.append(
                    {"id": metric["id"], "name": metric["name"], "resultType": metric["resultType"],
                     "display": metric["display"], "series": days}
                )
    return {"fromDay": start_iso, "toDay": end_iso, "fields": field_stats, "metrics": metric_series}
