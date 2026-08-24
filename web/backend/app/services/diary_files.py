"""Diary Markdown file boundary over the real filesystem.

Real-FS equivalent of DiaryFileRepository.kt: diary/ and media/ are flat stores of
user-visible files; the SQLite `diary_index` is a rebuildable index (never the source
of truth). Writes use temp-file -> read-back SHA-256 verify -> commit (`fs.safe_write`),
external edits are detected by comparing previousSha256 against the on-disk bytes, and
deletes move a verified copy into `.trash` before removing the original.
"""
from __future__ import annotations

import os
import re
import shutil
import threading
import time
import urllib.parse
from datetime import date, datetime
from pathlib import Path
from typing import Any, Callable

from ..core.config import DIARY_DIR, DIARY_TRASH_DIR, MEDIA_DIR
from ..core.errors import ApiError
from ..core.fs import (
    file_sha256,
    safe_write_text,
    sanitize_file_name as fs_sanitize_file_name,
    sanitize_rel_path,
)

MAX_DIARY_BYTES = 8 * 1024 * 1024  # bounded read for one Markdown document

DATE_RE = re.compile(r"\d{4}-\d{2}-\d{2}")
WHITESPACE_RE = re.compile(r"\s+")
HAN_RE = re.compile(r"[\u4E00-\u9FFF]")
LATIN_WORD_RE = re.compile(r"[\w'-]+", re.UNICODE)
SANITIZE_RE = re.compile(r"[\\/:*?\"<>|\x00-\x1f]")

# Same image grammar as DiaryFileRepository.MARKDOWN_IMAGE_REGEX.
MARKDOWN_IMAGE_RE = re.compile(
    r"""!\[([^\]\r\n]*)\]\(\s*(?:<([^>\r\n]+)>|([^\s)\r\n]+))"""
    r"""(?:\s+(?:"[^"\r\n]*"|'[^'\r\n]*'|\([^\)\r\n]*\)))?\s*\)"""
)
ENERGY_SUFFIX_RE = re.compile(r"[-–—]\s*(\d+)\s*kJ\s*$", re.IGNORECASE)

# AppModels.kt MealCategory: sortOrder decides meal-calendar ordering.
MEAL_CATEGORIES: list[dict[str, Any]] = [
    {"key": "breakfast", "chinese": "早餐", "english": "Breakfast", "sortOrder": 0},
    {"key": "lunch", "chinese": "午餐", "english": "Lunch", "sortOrder": 1},
    {"key": "afternoon_tea", "chinese": "下午茶", "english": "Afternoon tea", "sortOrder": 2},
    {"key": "dinner", "chinese": "晚餐", "english": "Dinner", "sortOrder": 3},
    {"key": "fruit", "chinese": "水果", "english": "Fruit", "sortOrder": 4},
    {"key": "late_snack", "chinese": "夜宵", "english": "Late snack", "sortOrder": 5},
]
_MEAL_BY_KEY = {c["key"]: c for c in MEAL_CATEGORIES}

MEDIA_META_FILE_NAME = "dc-media.json"
LEGACY_MEDIA_META_FILE_NAME = "deskcubby-media.json"

write_mutex = threading.RLock()


class ExternalFileConflict(Exception):
    """On-disk sha256 does not match previousSha256; carries the disk document."""

    def __init__(self, disk_document: dict[str, Any]):
        super().__init__("Diary was modified by another application")
        self.document = disk_document


# ---------------------------------------------------------------------------
# Text utilities (port of DiaryTextUtils.kt)
# ---------------------------------------------------------------------------

def sanitize_file_name(value: str) -> str:
    value = SANITIZE_RE.sub("_", value).strip()
    return value if value else "未命名"


def normalize_markdown_file_name(value: str) -> str:
    stem = value.strip().rstrip(" .")
    while stem.lower().endswith(".md"):
        stem = stem[:-3].rstrip(" .")
    stem = sanitize_file_name(stem).rstrip(" .")
    if not stem:
        stem = "未命名"
    return stem + ".md"


def word_count(text: str) -> int:
    without_han = HAN_RE.sub(" ", text)
    latin = len(LATIN_WORD_RE.findall(without_han))
    han = len(HAN_RE.findall(text))
    return latin + han


def sha256_text(text: str) -> str:
    import hashlib

    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def preferred_line_ending(source: str) -> str:
    if "\r\n" in source:
        return "\r\n"
    if "\n" in source:
        return "\n"
    if "\r" in source:
        return "\r"
    return "\n"


def normalize_text_block(value: str, line_ending: str) -> str:
    return (
        value.strip()
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\n", line_ending)
    )


def markdown_stem(name: str) -> str:
    return name[:-3] if name.lower().endswith(".md") else name


_JAVA_TOKENS = [
    ("yyyy", "%Y"), ("yy", "%y"), ("MM", "%m"), ("dd", "%d"),
    ("HH", "%H"), ("mm", "%M"), ("ss", "%S"),
]


def _java_pattern_to_strftime(pattern: str) -> str | None:
    out = ""
    i = 0
    while i < len(pattern):
        for token, repl in _JAVA_TOKENS:
            if pattern.startswith(token, i):
                out += repl
                i += len(token)
                break
        else:
            ch = pattern[i]
            if ch == "'":
                end = pattern.find("'", i + 1)
                if end < 0:
                    return None
                out += pattern[i + 1 : end].replace("%", "%%")
                i = end + 1
            else:
                if ch.isalpha():
                    return None
                out += "%%" if ch == "%" else ch
                i += 1
    return out


def format_date(d: date, pattern: str | None, fallback: str) -> str:
    for candidate in (pattern, fallback):
        if not candidate:
            continue
        try:
            fmt = _java_pattern_to_strftime(candidate)
            if fmt is None:
                continue
            return d.strftime(fmt)
        except ValueError:
            continue
    return d.isoformat()


def extract_date(name: str, modified_ms: int, pattern: str | None = None) -> date:
    """Port of DiaryFileRepository.extractDate."""
    if pattern and pattern.strip():
        fmt = _java_pattern_to_strftime(pattern.strip())
        if fmt:
            try:
                return datetime.strptime(markdown_stem(name), fmt).date()
            except ValueError:
                pass
    m = DATE_RE.search(name)
    if m:
        try:
            return date.fromisoformat(m.group(0))
        except ValueError:
            pass
    if modified_ms and modified_ms > 0:
        return datetime.fromtimestamp(modified_ms / 1000).date()
    return date.today()


# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

def diary_path(name: str) -> Path:
    target = sanitize_rel_path(name, DIARY_DIR)
    if target.parent != DIARY_DIR.resolve():
        raise ApiError(400, "invalid_name", "Invalid diary name")
    return target


def list_diary_file_metas() -> list[dict[str, Any]]:
    metas = []
    for p in sorted(DIARY_DIR.iterdir()):
        try:
            if not p.is_symlink() and p.is_file() and p.suffix.lower() == ".md":
                st = p.stat()
                metas.append(
                    {"uri": p.name, "name": p.name, "lastModified": int(st.st_mtime * 1000), "size": st.st_size}
                )
        except OSError:
            continue
    return metas


# ---------------------------------------------------------------------------
# Index scan
# ---------------------------------------------------------------------------

def _document_from_file(path: Path) -> dict[str, Any]:
    st = path.stat()
    raw = path.read_bytes()
    content = raw.decode("utf-8")
    modified = int(st.st_mtime * 1000)
    doc_date = extract_date(path.name, modified)
    return {
        "uri": path.name,
        "name": path.name,
        "title": markdown_stem(path.name),
        "dateIso": doc_date.isoformat(),
        "monthKey": "%04d.%02d" % (doc_date.year, doc_date.month),
        "lastModified": modified,
        "size": st.st_size,
        "wordCount": word_count(content),
        "sha256": sha256_bytes_hex(raw),
        "content": content,
    }


def sha256_bytes_hex(data: bytes) -> str:
    import hashlib

    return hashlib.sha256(data).hexdigest()


def scan_documents(con) -> list[dict[str, Any]]:
    """Full rescan of diary/*.md; replaces the rebuildable diary_index atomically."""
    documents = []
    for meta in list_diary_file_metas():
        try:
            documents.append(_document_from_file(DIARY_DIR / meta["name"]))
        except (OSError, UnicodeDecodeError):
            continue
    now = int(time.time() * 1000)
    with con:
        con.execute("DELETE FROM diary_index")
        for d in documents:
            con.execute(
                "INSERT INTO diary_index(uri,name,title,dateIso,monthKey,lastModified,size,"
                "wordCount,sha256,indexedAt) VALUES(?,?,?,?,?,?,?,?,?,?)",
                (d["uri"], d["name"], d["title"], d["dateIso"], d["monthKey"],
                 d["lastModified"], d["size"], d["wordCount"], d["sha256"], now),
            )
    return [{"uri": d["uri"], "name": d["name"], "title": d["title"], "dateIso": d["dateIso"],
             "monthKey": d["monthKey"], "lastModified": d["lastModified"], "size": d["size"],
             "wordCount": d["wordCount"]} for d in documents]


def ensure_index_fresh(con) -> None:
    """Stat-only change detection before serving index-backed queries."""
    rows = con.execute("SELECT uri, lastModified, size FROM diary_index").fetchall()
    indexed = {r["uri"]: (int(r["lastModified"]), int(r["size"])) for r in rows}
    current = {}
    for meta in list_diary_file_metas():
        current[meta["name"]] = (meta["lastModified"], meta["size"])
    if current != indexed:
        with write_mutex:
            scan_documents(con)


def _upsert_index_row(con, doc: dict[str, Any]) -> None:
    with con:
        con.execute("DELETE FROM diary_index WHERE uri = ?", (doc["uri"],))
        con.execute(
            "INSERT INTO diary_index(uri,name,title,dateIso,monthKey,lastModified,size,"
            "wordCount,sha256,indexedAt) VALUES(?,?,?,?,?,?,?,?,?,?)",
            (doc["uri"], doc["name"], doc["title"], doc["dateIso"], doc["monthKey"],
             doc["lastModified"], doc["size"], doc["wordCount"], doc["sha256"],
             int(time.time() * 1000)),
        )


# ---------------------------------------------------------------------------
# Load / save / create
# ---------------------------------------------------------------------------

def load_document(name: str) -> dict[str, Any]:
    path = diary_path(name)
    if not path.is_file():
        raise ApiError(404, "not_found", "Diary document not found")
    try:
        return _document_from_file(path)
    except UnicodeDecodeError:
        raise ApiError(400, "invalid_encoding", "Document is not valid UTF-8")


def save_document(
    con,
    settings: dict[str, Any],
    name: str,
    content: str,
    previous_sha256: str | None,
    force: bool = False,
) -> dict[str, Any]:
    if len(content.encode("utf-8")) > MAX_DIARY_BYTES:
        raise ApiError(413, "file_too_large", "Document too large")
    with write_mutex:
        disk = load_document(name)
        if not force and previous_sha256 is not None and disk["sha256"] != previous_sha256:
            raise ExternalFileConflict(disk)
        safe_write_text(diary_path(name), content)
        fresh = load_document(name)
        _upsert_index_row(con, fresh)
        return _editor_view(fresh)


def _editor_view(doc: dict[str, Any]) -> dict[str, Any]:
    return {
        "uri": doc["uri"], "name": doc["name"], "content": doc["content"],
        "lastModified": doc["lastModified"], "size": doc["size"], "sha256": doc["sha256"],
    }


def _dedupe_candidate(root: Path, candidate: str) -> str:
    sequence = 2
    while (root / candidate).exists():
        stem = markdown_stem(candidate)
        candidate = f"{stem} ({sequence}).md"
        sequence += 1
    return candidate


def create_document(
    con,
    settings: dict[str, Any],
    name: str | None = None,
    title: str | None = None,
    date_iso: str | None = None,
    template: bool = True,
) -> dict[str, Any]:
    """`POST /api/diary/documents`.

    With an explicit name this mirrors DiaryFileRepository.create(); without one it
    mirrors enterToday(): the settings fileNamePattern names the file and an existing
    file is opened instead of duplicated.
    """
    target_date = _parse_iso_or_today(date_iso)
    date_text = target_date.isoformat()
    pattern = settings.get("fileNamePattern") or "yyyy-MM-dd"
    md_template = settings.get("markdownTemplate") or "# {title}\n\n"
    with write_mutex:
        if name:
            base_title = (title or markdown_stem(normalize_markdown_file_name(name))).strip() or "新日记"
            file_name = _dedupe_candidate(DIARY_DIR, normalize_markdown_file_name(name))
        else:
            base_name = format_date(target_date, pattern, "yyyy-MM-dd '日记'")
            file_name = sanitize_file_name(base_name) + ".md"
            existing = DIARY_DIR / file_name
            if existing.is_file():
                return save_document(con, settings, file_name, load_document(file_name)["content"], None)
            base_title = base_name
            title_for_content = title or base_title
            content = _render_template(md_template, title_for_content, date_text) if template else ""
            safe_write_text(existing, content)
            doc = load_document(file_name)
            _upsert_index_row(con, doc)
            return _editor_view(doc)
        title_for_content = title or base_title
        content = _render_template(md_template, title_for_content, date_text) if template else ""
        path = DIARY_DIR / file_name
        safe_write_text(path, content)
        doc = load_document(file_name)
        _upsert_index_row(con, doc)
        return _editor_view(doc)


def _render_template(md_template: str, title: str, date_text: str) -> str:
    return md_template.replace("{title}", title).replace("{date}", date_text)


def rename_document(con, name: str, new_name: str) -> dict[str, Any]:
    """`POST /api/diary/rename`: atomic same-dir rename inside diary/.

    Names are sanitized via `fs.sanitize_file_name` (400 on invalid input), a `.md`
    suffix is enforced so the document stays indexed, an existing target yields 409,
    and the rebuildable diary_index row follows the file's new uri/name/title.
    """
    with write_mutex:
        src = diary_path(name)
        if not src.is_file():
            raise ApiError(404, "not_found", "Diary document not found")
        cleaned = fs_sanitize_file_name(new_name.strip())
        if not cleaned.lower().endswith(".md"):
            cleaned += ".md"
        dest = diary_path(cleaned)
        if dest.exists():  # includes renaming onto the current name
            raise ApiError(409, "duplicate_name", "已存在同名日记")
        os.rename(src, dest)  # same-directory rename is atomic
        doc = load_document(dest.name)
        with con:
            con.execute("DELETE FROM diary_index WHERE uri = ?", (src.name,))
            _upsert_index_row(con, doc)
        return _editor_view(doc)


def _parse_iso_or_today(value: str | None) -> date:
    if not value:
        return date.today()
    try:
        return date.fromisoformat(str(value)[:10])
    except ValueError:
        raise ApiError(400, "invalid_date", "Invalid dateIso")


# ---------------------------------------------------------------------------
# Transform-for-date helper (used by structured records)
# ---------------------------------------------------------------------------

def transform_diary_for_date(
    con,
    settings: dict[str, Any],
    target_date: date,
    transform: Callable[[str], str],
) -> dict[str, Any]:
    """Opens (or creates) the journal file for the natural date and applies transform.

    Mirrors DiaryFileRepository.transformDiaryForDate: conflict detection immediately
    before the write, read-back verification after it, no write when unchanged.
    """
    with write_mutex:
        editor = create_document(con, settings, date_iso=target_date.isoformat(), template=True)
        original_content = editor["content"]
        updated = transform(original_content)
        if updated == original_content:
            return editor
        return save_document(con, settings, editor["name"], updated, editor["sha256"])


# ---------------------------------------------------------------------------
# Trash (originalName + deletedAt encoded as "{millis}__{name}" prefix)
# ---------------------------------------------------------------------------

_TRASH_PREFIX_RE = re.compile(r"^(\d{13,})__")


def trash_delete(con, name: str) -> None:
    path = diary_path(name)
    if not path.is_file():
        raise ApiError(404, "not_found", "Diary document not found")
    with write_mutex:
        DIARY_TRASH_DIR.mkdir(parents=True, exist_ok=True)
        stamp = int(time.time() * 1000)
        dest = DIARY_TRASH_DIR / f"{stamp}__{name}"
        seq = 1
        while dest.exists():
            dest = DIARY_TRASH_DIR / f"{stamp}-{seq}__{name}"  # collision only
            seq += 1
        # Copy -> verify -> remove original (crash-safe, like Android's backup flow).
        data = path.read_bytes()
        dest.write_bytes(data)
        if file_sha256(dest) != sha256_bytes_hex(data):
            dest.unlink(missing_ok=True)
            raise ApiError(500, "trash_verify_failed", "Trash backup verification failed")
        path.unlink()
        with con:
            con.execute("DELETE FROM diary_index WHERE uri = ?", (path.name,))


def trash_list() -> list[dict[str, Any]]:
    items = []
    if not DIARY_TRASH_DIR.is_dir():
        return items
    for p in DIARY_TRASH_DIR.iterdir():
        if not p.is_file():
            continue
        stored = p.name
        m = _TRASH_PREFIX_RE.match(stored)
        if m:
            original = stored[m.end():]
            deleted_at = int(m.group(1))
        else:
            original = stored
            deleted_at = int(p.stat().st_mtime * 1000)
        items.append({"uri": stored, "originalName": original, "deletedAt": deleted_at})
    items.sort(key=lambda item: item["deletedAt"], reverse=True)
    return items


def trash_restore(con, stored_name: str) -> dict[str, Any]:
    with write_mutex:
        src = sanitize_rel_path(stored_name, DIARY_TRASH_DIR)
        if src.parent != DIARY_TRASH_DIR.resolve() or not src.is_file():
            raise ApiError(404, "not_found", "Trash item not found")
        m = _TRASH_PREFIX_RE.match(src.name)
        original = src.name[m.end():] if m else src.name
        candidate = original
        sequence = 2
        while (DIARY_DIR / candidate).exists():
            extension = original.rsplit(".", 1)[-1]
            stem = original[: -len(extension) - 1] if "." in original else original
            candidate = f"{stem} (恢复 {sequence}).{extension}"
            sequence += 1
        dest = DIARY_DIR / candidate
        data = src.read_bytes()
        dest.write_bytes(data)
        if file_sha256(dest) != sha256_bytes_hex(data):
            dest.unlink(missing_ok=True)
            raise ApiError(500, "restore_verify_failed", "Restore verification failed")
        src.unlink()
        doc = load_document(dest.name)
        _upsert_index_row(con, doc)
        return _editor_view(doc)


def trash_permanent_delete(stored_name: str) -> None:
    src = sanitize_rel_path(stored_name, DIARY_TRASH_DIR)
    if src.parent != DIARY_TRASH_DIR.resolve() or not src.is_file():
        raise ApiError(404, "not_found", "Trash item not found")
    if not _TRASH_PREFIX_RE.match(src.name):
        raise ApiError(400, "invalid_name", "Not a trash item")
    src.unlink()


# ---------------------------------------------------------------------------
# Index queries (recent / random / stats)
# ---------------------------------------------------------------------------

def recent_documents(con, limit: int) -> list[dict[str, Any]]:
    ensure_index_fresh(con)
    rows = con.execute(
        "SELECT uri,name,title,dateIso,monthKey,lastModified,size,wordCount FROM diary_index "
        "ORDER BY dateIso DESC, name DESC LIMIT ?",
        (max(1, min(int(limit), 200)),),
    ).fetchall()
    return [dict(r) for r in rows]


def random_document(con) -> dict[str, Any] | None:
    ensure_index_fresh(con)
    row = con.execute(
        "SELECT uri,name,title,dateIso,monthKey,lastModified,size,wordCount FROM diary_index "
        "ORDER BY RANDOM() LIMIT 1"
    ).fetchone()
    return dict(row) if row else None


def diary_stats(con) -> dict[str, Any]:
    ensure_index_fresh(con)
    total = con.execute("SELECT COUNT(*) AS n, COALESCE(SUM(wordCount),0) AS w FROM diary_index").fetchone()
    today = date.today()
    dates = {
        r["dateIso"]
        for r in con.execute("SELECT DISTINCT dateIso FROM diary_index").fetchall()
        if _safe_parse_date(r["dateIso"])
    }
    streak = 0
    cursor = today if today.isoformat() in dates else today.fromordinal(today.toordinal() - 1)
    while cursor.isoformat() in dates:
        streak += 1
        cursor = cursor.fromordinal(cursor.toordinal() - 1)
    month_key = "%04d.%02d" % (today.year, today.month)
    month_count = con.execute(
        "SELECT COUNT(*) AS n FROM diary_index WHERE monthKey = ?", (month_key,)
    ).fetchone()["n"]
    return {
        "totalDocuments": int(total["n"]),
        "totalWords": int(total["w"]),
        "streakDays": streak,
        "monthDocuments": int(month_count),
        "monthKey": month_key,
    }


def query_documents(con, query: str | None, month: str | None) -> list[dict[str, Any]]:
    ensure_index_fresh(con)
    sql = ("SELECT uri,name,title,dateIso,monthKey,lastModified,size,wordCount FROM diary_index")
    clauses, params = [], []
    if query:
        clauses.append("(title LIKE ? OR name LIKE ?)")
        like = f"%{query}%"
        params += [like, like]
    if month:
        clauses.append("monthKey = ?")
        params.append(month)
    if clauses:
        sql += " WHERE " + " AND ".join(clauses)
    sql += " ORDER BY dateIso DESC, name DESC"
    return [dict(r) for r in con.execute(sql, params).fetchall()]


def _safe_parse_date(value: str) -> bool:
    try:
        date.fromisoformat(value)
        return True
    except (TypeError, ValueError):
        return False


# ---------------------------------------------------------------------------
# Meal calendar scanning
# ---------------------------------------------------------------------------

def decoded_target_file_name(target: str) -> str | None:
    try:
        cleaned = urllib.parse.unquote(
            target.strip().strip("<>").replace("\\", "/").rsplit("/", 1)[-1]
        )
    except Exception:
        return None
    return cleaned or None


def energy_from_caption(caption: str) -> int | None:
    m = ENERGY_SUFFIX_RE.search(caption)
    if not m:
        return None
    try:
        return int(m.group(1))
    except ValueError:
        return None


def meal_category_from_caption(caption: str) -> dict[str, Any] | None:
    normalized = WHITESPACE_RE.sub(" ", caption.strip().lower())
    stripped = ENERGY_SUFFIX_RE.sub("", normalized).rstrip("- ")
    for category in MEAL_CATEGORIES:
        if stripped == category["chinese"] or stripped == category["english"].lower():
            return category
    if normalized == "late-night snack":
        return _MEAL_BY_KEY["late_snack"]
    return None


def meal_category_from_file_name(target: str) -> dict[str, Any] | None:
    file_name = decoded_target_file_name(target)
    if not file_name:
        return None
    lowered = file_name.lower()
    for category in MEAL_CATEGORIES:
        if category["chinese"] in lowered:
            return category
        label = category["english"].lower()
        if category["key"] == "late_snack":
            body = r"late[ _-]+(?:night[ _-]+)?snack"
        else:
            body = re.escape(label)
        if re.search(r"(?:^|[^a-z])" + body + r"(?:[^a-z]|$)", lowered):
            return category
    return None


def media_meta_display_location(entry: dict[str, Any] | None) -> str | None:
    if not entry:
        return None
    place = (entry.get("place") or "").strip()
    if place:
        return place
    lat = entry.get("lat")
    lng = entry.get("lng")
    if isinstance(lat, (int, float)) and isinstance(lng, (int, float)):
        if -90.0 <= lat <= 90.0 and -180.0 <= lng <= 180.0:
            return "%.4f, %.4f" % (lat, lng)
    return None


def media_files_by_lower_name() -> dict[str, str]:
    result = {}
    if MEDIA_DIR.is_dir():
        for p in MEDIA_DIR.iterdir():
            try:
                if not p.is_symlink() and p.is_file() and not p.name.lower().endswith(".json"):
                    result.setdefault(p.name.lower(), p.name)
            except OSError:
                continue
    return result


def parse_meal_image_references(content: str) -> list[dict[str, str]]:
    refs = []
    for match in MARKDOWN_IMAGE_RE.finditer(content):
        refs.append(
            {
                "caption": match.group(1).strip(),
                "target": match.group(2) if match.group(2) else match.group(3),
                "markdown": match.group(0),
            }
        )
    return refs


def scan_meal_calendar(settings: dict[str, Any], media_meta_doc: dict[str, Any]) -> list[dict[str, Any]]:
    """Group photo references by the owning journal/calendar date (scanMealCalendar port).

    The dc-media.json sidecar wins over legacy `-800kJ` caption suffixes; captions are
    never rewritten (read-only fallback).
    """
    entries = media_meta_doc.get("entries", {})
    meal_days = media_meta_doc.get("mealDays", {})
    media_by_name = media_files_by_lower_name()
    diaries = []
    for meta in list_diary_file_metas():
        diaries.append(meta)
    diaries.sort(key=lambda m: (_date_sort_key(m), m["name"]), reverse=True)
    photos_by_date: dict[str, list[dict[str, Any]]] = {}
    for meta in diaries:
        path = DIARY_DIR / meta["name"]
        try:
            content = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        date_iso = extract_date(meta["name"], meta["lastModified"], settings.get("fileNamePattern")).isoformat()
        for ref in parse_meal_image_references(content):
            category = meal_category_from_caption(ref["caption"]) or meal_category_from_file_name(ref["target"])
            if category is None:
                continue
            key = (decoded_target_file_name(ref["target"]) or "").lower()
            actual_name = media_by_name.get(key)
            if not actual_name:
                continue
            entry = entries.get(key)
            energy = entry.get("energyKj") if entry else None
            if energy is None:
                energy = energy_from_caption(ref["caption"])
            photos_by_date.setdefault(date_iso, []).append(
                {
                    "fileName": key,
                    "fileNameActual": actual_name,
                    "caption": ref["caption"],
                    "category": category["key"],
                    "categorySortOrder": category["sortOrder"],
                    "diaryName": meta["name"],
                    "markdown": ref["markdown"],
                    "energyKj": energy,
                    "locationName": media_meta_display_location(entry),
                    "foods": (entry or {}).get("foods", []),
                }
            )
    days = []
    for date_iso, photos in photos_by_date.items():
        photos.sort(key=lambda p: p["categorySortOrder"])
        details = meal_days.get(date_iso, {})
        calculated = calculated_energy([p["energyKj"] for p in photos])
        days.append(
            {
                "dateIso": date_iso,
                "photos": photos,
                "details": details,
                "calculatedEnergyKj": calculated,
                "totalEnergyKj": details.get("totalEnergyKjOverride", calculated),
            }
        )
    days.sort(key=lambda d: d["dateIso"], reverse=True)
    return days


def _date_sort_key(meta: dict[str, Any]) -> str:
    return extract_date(meta["name"], meta["lastModified"]).isoformat()


def calculated_energy(values: list[Any]) -> int | None:
    known = [v for v in values if isinstance(v, int)]
    if not known:
        return None
    return min(sum(known), 1_000_000)
