"""阅读 Reader service — book registry, content streaming, progress ledger, engagement.

Port of android `data/repository/ReaderRepository.kt` (book import/rename/remove,
full-file fingerprinting) and `data/sync/ReaderProgressJsonCodec.kt` (the URI-free
`reading/v1/progress.json` ledger):

- Books live in `workspace/books/<id>.<txt|pdf>`; the `reader_books` registry row
  stores uuid id, original file name, TXT/PDF type, title derived from the file
  name, size and SHA-256 fingerprint.
- The fingerprint is byte-compatible with Android: SHA-256 over
  `"DeskCubby.ReaderBook.v1" || 0x00 || "TXT"|"PDF" || 0x00 || <file bytes>`, so a
  progress record written here matches the same book on an Android device.
- Progress records merge last-writer-wins by `(fingerprint, type)` with
  `updatedAt`, tie-broken by paragraph/page position, exactly like
  `mergeReaderProgressLedger`; the persisted document is the canonical codec shape
  (`{"version":1,"records":[...]}` sorted by fingerprint+type).
- Engagement seconds are appended per local day into
  `private/reading/engagement.json` and aggregated for statshub/backup export.
"""
from __future__ import annotations

import hashlib
import json
import re
import threading
import time
import uuid
from datetime import date
from pathlib import Path
from typing import Any, IO

from ..core.config import BOOKS_DIR, PRIVATE_DIR
from ..core.db import write_lock
from ..core.errors import ApiError
from ..core.fs import safe_write_text

READER_FINGERPRINT_DOMAIN = "DeskCubby.ReaderBook.v1"
FINGERPRINT_REGEX = re.compile(r"[0-9a-f]{64}")

MAX_TEXT_BYTES = 32 * 1024 * 1024  # README: TXT 最大 32 MiB
MAX_BOOKS = 500
MAX_TITLE_CHARS = 240

# ReaderProgressJsonCodec.kt bounds
PROGRESS_FORMAT_VERSION = 1
PROGRESS_MAX_RECORDS = 500
PROGRESS_MAX_JSON_BYTES = 512 * 1024
MAX_TEXT_PAGES = 50_000
MAX_TEXT_PARAGRAPHS = 250_000
MAX_PDF_PAGES = 20_000

PROGRESS_PATH = PRIVATE_DIR / "reading" / "v1" / "progress.json"
ENGAGEMENT_PATH = PRIVATE_DIR / "reading" / "engagement.json"

_progress_mutex = threading.RLock()
_engagement_mutex = threading.RLock()

VALID_TYPES = ("TXT", "PDF")


def now_ms() -> int:
    return int(time.time() * 1000)


# ---------------------------------------------------------------------------
# Fingerprint + text decoding (ReaderRepository.kt)
# ---------------------------------------------------------------------------

def compute_fingerprint(data: bytes, book_type: str) -> str:
    digest = hashlib.sha256()
    digest.update(READER_FINGERPRINT_DOMAIN.encode("utf-8"))
    digest.update(b"\x00")
    digest.update(book_type.encode("utf-8"))
    digest.update(b"\x00")
    digest.update(data)
    return digest.digest().hex()


def decode_reader_text(data: bytes) -> str:
    """UTF-8/UTF-16 BOM detection; strict UTF-8; GB18030 fallback."""
    if data[:3] == b"\xef\xbb\xbf":
        try:
            return data[3:].decode("utf-8")
        except UnicodeDecodeError:
            return data[3:].decode("gb18030", errors="replace")
    if data[:2] == b"\xff\xfe":
        return data[2:].decode("utf-16-le", errors="replace")
    if data[:2] == b"\xfe\xff":
        return data[2:].decode("utf-16-be", errors="replace")
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode("gb18030", errors="replace")


# ---------------------------------------------------------------------------
# Registry helpers
# ---------------------------------------------------------------------------

def row_to_book(row) -> dict[str, Any]:
    return {
        "id": row["id"],
        "fileName": row["fileName"],
        "bookType": row["bookType"],
        "title": row["title"],
        "sizeBytes": int(row["sizeBytes"]),
        "fingerprint": row["sha256"],
        "addedAt": int(row["addedAt"]),
    }


def get_book_row(con, book_id: str):
    row = con.execute("SELECT * FROM reader_books WHERE id = ? LIMIT 1", (str(book_id),)).fetchone()
    if row is None:
        raise ApiError(404, "book_not_found", "书籍不存在")
    return row


def book_file_path(row) -> Path:
    extension = "pdf" if row["bookType"] == "PDF" else "txt"
    path = BOOKS_DIR / f"{row['id']}.{extension}"
    if not path.is_file():
        raise ApiError(404, "book_file_missing", "书籍文件不存在")
    return path


def title_from_file_name(name: str) -> str:
    stem = name.rsplit(".", 1)[0] if "." in name else name
    title = stem.strip()[:MAX_TITLE_CHARS].strip()
    return title or "Untitled"


def detect_book_type(file_name: str, mime: str) -> str | None:
    lowered = (file_name or "").lower()
    mime_l = (mime or "").lower()
    if mime_l == "application/pdf" or lowered.endswith(".pdf"):
        return "PDF"
    if mime_l.startswith("text/") or lowered.endswith(".txt"):
        return "TXT"
    return None


def list_books(con) -> list[dict[str, Any]]:
    rows = con.execute("SELECT * FROM reader_books ORDER BY addedAt DESC, id ASC").fetchall()
    return [row_to_book(row) for row in rows]


def create_book(con, *, file_name: str, book_type: str, data: bytes) -> dict[str, Any]:
    """Persist one uploaded book + registry entry atomically enough to survive
    interruption: registry row is only inserted after the verified file commit."""
    book_id = str(uuid.uuid4())
    extension = "pdf" if book_type == "PDF" else "txt"
    target = BOOKS_DIR / f"{book_id}.{extension}"
    from ..core.fs import safe_write

    safe_write(target, data)
    fingerprint = compute_fingerprint(data, book_type)
    now = now_ms()
    with write_lock(), con:
        con.execute(
            "INSERT INTO reader_books(id, fileName, bookType, title, sizeBytes, sha256, addedAt)"
            " VALUES(?,?,?,?,?,?,?)",
            (
                book_id,
                file_name,
                book_type,
                title_from_file_name(file_name),
                len(data),
                fingerprint,
                now,
            ),
        )
    return {
        "id": book_id,
        "fileName": file_name,
        "bookType": book_type,
        "title": title_from_file_name(file_name),
        "sizeBytes": len(data),
        "fingerprint": fingerprint,
        "addedAt": now,
    }


def rename_book(con, book_id: str, raw_title: str) -> dict[str, Any]:
    row = get_book_row(con, book_id)
    title = (raw_title or "").strip()[:MAX_TITLE_CHARS].strip() or "Untitled"
    with write_lock(), con:
        con.execute(
            "UPDATE reader_books SET title = ? WHERE id = ?", (title, str(row["id"]))
        )
    fresh = get_book_row(con, book_id)
    return row_to_book(fresh)


def delete_book(con, book_id: str) -> None:
    row = get_book_row(con, book_id)
    with write_lock(), con:
        con.execute("DELETE FROM reader_books WHERE id = ?", (str(row["id"]),))
    extension = "pdf" if row["bookType"] == "PDF" else "txt"
    (BOOKS_DIR / f"{row['id']}.{extension}").unlink(missing_ok=True)


# ---------------------------------------------------------------------------
# Content streaming with Range support
# ---------------------------------------------------------------------------

def parse_range_header(header: str | None, total: int) -> tuple[int, int] | None | str:
    """Returns (start, end) inclusive, None when absent, or "invalid"."""
    if not header:
        return None
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", header.strip())
    if not match:
        return "invalid"
    start_text, end_text = match.group(1), match.group(2)
    if not start_text and not end_text:
        return "invalid"
    if not start_text:
        suffix = int(end_text)
        if suffix <= 0 or total == 0:
            return "invalid"
        start = max(0, total - suffix)
        return (start, total - 1)
    start = int(start_text)
    end = int(end_text) if end_text else total - 1
    if start >= total or end < start:
        return "invalid"
    return (start, min(end, total - 1))


def iter_file_range(handle: IO[bytes], start: int, end: int, chunk_size: int = 1 << 20):
    handle.seek(start)
    remaining = end - start + 1
    try:
        while remaining > 0:
            chunk = handle.read(min(chunk_size, remaining))
            if not chunk:
                break
            remaining -= len(chunk)
            yield chunk
    finally:
        handle.close()


# ---------------------------------------------------------------------------
# Progress ledger (ReaderProgressJsonCodec.kt + mergeReaderProgressLedger)
# ---------------------------------------------------------------------------

_RECORD_KEYS = {"fingerprint", "type", "textPageIndex", "textParagraphIndex",
                "pdfPageIndex", "totalPages", "updatedAt"}
_ROOT_KEYS = {"version", "records"}


class ProgressDamaged(ApiError):
    def __init__(self) -> None:
        super().__init__(500, "progress_damaged", "阅读进度文件已损坏，未做任何改动")


def _require_int(value: Any, minimum: int, maximum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError("not an integer")
    number = int(value)
    if not minimum <= number <= maximum:
        raise ValueError("out of range")
    return number


def validate_record(item: Any) -> dict[str, Any]:
    if not isinstance(item, dict) or set(item.keys()) != _RECORD_KEYS:
        raise ValueError("unexpected record fields")
    fingerprint = item.get("fingerprint")
    if not isinstance(fingerprint, str) or not FINGERPRINT_REGEX.fullmatch(fingerprint):
        raise ValueError("invalid fingerprint")
    book_type = item.get("type")
    if book_type not in VALID_TYPES:
        raise ValueError("invalid type")
    max_total_pages = MAX_PDF_PAGES if book_type == "PDF" else MAX_TEXT_PAGES
    record = {
        "fingerprint": fingerprint,
        "type": book_type,
        "textPageIndex": _require_int(item.get("textPageIndex"), -1, MAX_TEXT_PAGES - 1),
        "textParagraphIndex": _require_int(item.get("textParagraphIndex"), 0, MAX_TEXT_PARAGRAPHS - 1),
        "pdfPageIndex": _require_int(item.get("pdfPageIndex"), 0, MAX_PDF_PAGES - 1),
        "totalPages": _require_int(item.get("totalPages"), 0, max_total_pages),
        "updatedAt": _require_int(item.get("updatedAt"), 0, 2**63 - 1),
    }
    return record


def decode_progress(raw: bytes) -> list[dict[str, Any]]:
    if not raw or len(raw) > PROGRESS_MAX_JSON_BYTES:
        raise ProgressDamaged()
    try:
        text = raw.decode("utf-8")
        root = json.loads(text)
        if not isinstance(root, dict) or set(root.keys()) != _ROOT_KEYS:
            raise ValueError("root")
        version = root.get("version")
        if isinstance(version, bool) or not isinstance(version, int) or version != PROGRESS_FORMAT_VERSION:
            raise ValueError("version")
        records_raw = root.get("records")
        if not isinstance(records_raw, list) or len(records_raw) > PROGRESS_MAX_RECORDS:
            raise ValueError("records")
        decoded: list[dict[str, Any]] = []
        seen: set[tuple[str, str]] = set()
        for item in records_raw:
            record = validate_record(item)
            key = (record["fingerprint"], record["type"])
            if key in seen:
                raise ValueError("duplicate record")
            seen.add(key)
            decoded.append(record)
    except ProgressDamaged:
        raise
    except Exception:  # noqa: BLE001 - every malformed byte sequence means damaged
        raise ProgressDamaged()
    decoded.sort(key=lambda r: (r["fingerprint"], r["type"]))
    return decoded


def encode_progress(records: list[dict[str, Any]]) -> str:
    normalized = [validate_record(record) for record in records]
    keys = [(r["fingerprint"], r["type"]) for r in normalized]
    if len(set(keys)) != len(keys):
        raise ApiError(400, "duplicate_record", "Duplicate reader progress record")
    normalized.sort(key=lambda r: (r["fingerprint"], r["type"]))
    document = {
        "version": PROGRESS_FORMAT_VERSION,
        "records": [
            {
                "fingerprint": r["fingerprint"],
                "type": r["type"],
                "textPageIndex": r["textPageIndex"],
                "textParagraphIndex": r["textParagraphIndex"],
                "pdfPageIndex": r["pdfPageIndex"],
                "totalPages": r["totalPages"],
                "updatedAt": r["updatedAt"],
            }
            for r in normalized
        ],
    }
    encoded = json.dumps(document, ensure_ascii=False, separators=(",", ":"))
    if len(encoded.encode("utf-8")) > PROGRESS_MAX_JSON_BYTES:
        raise ApiError(413, "progress_too_large", "Reader progress JSON is too large")
    return encoded


def progress_sort_index(record: dict[str, Any]) -> int:
    if record["type"] == "TXT":
        return record["textParagraphIndex"] * 100
    return record["pdfPageIndex"] * 100


def merge_ledger(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """LWW by updatedAt, then position; newest first, capped at 500 entries."""
    groups: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for record in records:
        try:
            normalized = validate_record(record)
        except ValueError:
            continue
        groups.setdefault((normalized["fingerprint"], normalized["type"]), []).append(normalized)
    winners = [
        max(matches, key=lambda r: (r["updatedAt"], progress_sort_index(r)))
        for matches in groups.values()
    ]
    winners.sort(key=lambda r: (-r["updatedAt"], r["fingerprint"], r["type"]))
    return winners[:PROGRESS_MAX_RECORDS]


def read_ledger_records() -> list[dict[str, Any]]:
    """Canonical stored records; damaged files fail loudly instead of resetting."""
    with _progress_mutex:
        if not PROGRESS_PATH.is_file():
            return []
        raw = PROGRESS_PATH.read_bytes()
    return decode_progress(raw)


def write_ledger_records(records: list[dict[str, Any]]) -> None:
    encoded = encode_progress(records)
    with _progress_mutex:
        # A damaged/oversized existing file is never silently overwritten with
        # fresh data (mirrors the Android STATE_FILE_DAMAGED write block).
        if PROGRESS_PATH.exists():
            decode_progress(PROGRESS_PATH.read_bytes())
        PROGRESS_PATH.parent.mkdir(parents=True, exist_ok=True)
        safe_write_text(PROGRESS_PATH, encoded)


def merged_document(records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    canonical = sorted(
        merge_ledger(records), key=lambda r: (r["fingerprint"], r["type"])
    )
    return canonical


def upsert_progress(payload: Any) -> dict[str, Any]:
    """PUT body handler. Accepts the full document or a single record object."""
    incoming: list[dict[str, Any]]
    try:
        if isinstance(payload, dict) and set(payload.keys()) == _ROOT_KEYS:
            incoming = [validate_record(item) for item in payload.get("records", [])]
            if len(incoming) > PROGRESS_MAX_RECORDS:
                raise ApiError(400, "too_many_records", "Too many reader progress records")
        elif isinstance(payload, dict):
            incoming = [validate_record(payload)]
        elif isinstance(payload, list):
            if len(payload) > PROGRESS_MAX_RECORDS:
                raise ApiError(400, "too_many_records", "Too many reader progress records")
            incoming = [validate_record(item) for item in payload]
        else:
            raise ApiError(400, "invalid_progress", "Reader progress payload invalid")
    except ApiError:
        raise
    except Exception:  # noqa: BLE001 - malformed record shapes are client errors
        raise ApiError(400, "invalid_progress", "Reader progress record is invalid")
    with _progress_mutex:
        current = read_ledger_records()
        merged = merged_document(current + incoming)
        write_ledger_records(merged)
        return {"version": PROGRESS_FORMAT_VERSION, "records": merged}


# ---------------------------------------------------------------------------
# Engagement (private/reading/engagement.json)
# ---------------------------------------------------------------------------

MAX_ENGAGEMENT_SECONDS_PER_POST = 24 * 3600
MAX_ENGAGEMENT_BOOKS = 5_000
MAX_ENGAGEMENT_DAYS_PER_BOOK = 36_600


def read_engagement() -> dict[str, Any]:
    try:
        raw = ENGAGEMENT_PATH.read_text(encoding="utf-8")
        root = json.loads(raw)
    except (OSError, UnicodeDecodeError, ValueError):
        return {"version": 1, "books": {}}
    if not isinstance(root, dict):
        return {"version": 1, "books": {}}
    books = root.get("books") if isinstance(root.get("books"), dict) else {}
    return {"version": 1, "books": books}


def append_engagement(con, book_id: str, seconds: float) -> dict[str, Any]:
    row = con.execute(
        "SELECT id, title FROM reader_books WHERE id = ? LIMIT 1", (str(book_id),)
    ).fetchone()
    book_key = str(row["id"] if row is not None else book_id)
    bounded = max(0.0, min(float(seconds), float(MAX_ENGAGEMENT_SECONDS_PER_POST)))
    day_iso = date.today().isoformat()
    with _engagement_mutex:
        doc = read_engagement()
        books = doc["books"]
        entry = books.get(book_key)
        entry = dict(entry) if isinstance(entry, dict) else {}
        days = entry.get("days") if isinstance(entry.get("days"), dict) else {}
        previous_total = _coerce_number(entry.get("totalSeconds")) or 0.0
        day_value = _coerce_number(days.get(day_iso)) or 0.0
        days[day_iso] = round(day_value + bounded, 3)
        if len(days) > MAX_ENGAGEMENT_DAYS_PER_BOOK:
            oldest = sorted(days.keys())[: len(days) - MAX_ENGAGEMENT_DAYS_PER_BOOK]
            for key in oldest:
                days.pop(key, None)
        entry["days"] = days
        entry["totalSeconds"] = round(previous_total + bounded, 3)
        if row is not None:
            entry["title"] = row["title"]
        if entry.get("lastEngagedAt") is None or isinstance(entry.get("lastEngagedAt"), (int, float)):
            entry["lastEngagedAt"] = now_ms()
        if book_key not in books and len(books) >= MAX_ENGAGEMENT_BOOKS:
            raise ApiError(413, "too_many_books", "阅读时长记录数量超出限制")
        books[book_key] = entry
        ENGAGEMENT_PATH.parent.mkdir(parents=True, exist_ok=True)
        safe_write_text(
            ENGAGEMENT_PATH,
            json.dumps({"version": 1, "books": books}, ensure_ascii=False, indent=2),
        )
    return engagement_summary(days=30)


def _coerce_number(value: Any) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return float(value)


def engagement_summary(days: int = 30) -> dict[str, Any]:
    with _engagement_mutex:
        doc = read_engagement()
    books_doc = doc["books"]
    today = date.today()
    window_start = (today.toordinal() - max(0, min(int(days), MAX_ENGAGEMENT_DAYS_PER_BOOK)) + 1)
    series: dict[str, float] = {}
    items: list[dict[str, Any]] = []
    total_seconds = 0.0
    for book_id, entry in sorted(books_doc.items()):
        if not isinstance(entry, dict):
            continue
        book_total = _coerce_number(entry.get("totalSeconds"))
        per_days = entry.get("days") if isinstance(entry.get("days"), dict) else {}
        summed = 0.0
        for day_iso, value in per_days.items():
            number = _coerce_number(value)
            if number is None:
                continue
            try:
                ordinal = date.fromisoformat(str(day_iso)[:10]).toordinal()
            except ValueError:
                continue
            if ordinal >= window_start:
                series[str(day_iso)[:10]] = round(series.get(str(day_iso)[:10], 0.0) + number, 3)
            summed += number
        effective_total = book_total if book_total is not None else summed
        total_seconds += effective_total
        items.append(
            {
                "bookId": book_id,
                "title": entry.get("title"),
                "totalSeconds": round(effective_total, 3),
            }
        )
    items.sort(key=lambda item: (-item["totalSeconds"], item["bookId"]))
    series_list = [
        {"date": day, "seconds": round(series[day], 3)}
        for day in sorted(series.keys())
    ]
    return {
        "available": True,
        "rangeDays": max(0, min(int(days), MAX_ENGAGEMENT_DAYS_PER_BOOK)),
        "totalSeconds": round(total_seconds, 3),
        "totalMinutes": round(total_seconds / 60.0, 1),
        "booksWithRecords": sum(1 for item in items if item["totalSeconds"] > 0),
        "books": items,
        "series": series_list,
    }


def engagement_for_backup() -> dict[str, Any]:
    """Raw aggregate used by the backup export boundary (no raw event stream)."""
    summary = engagement_summary(days=MAX_ENGAGEMENT_DAYS_PER_BOOK)
    with _engagement_mutex:
        doc = read_engagement()
    return {"summary": summary, "books": doc["books"]}
