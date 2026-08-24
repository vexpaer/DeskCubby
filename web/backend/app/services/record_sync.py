"""Android-compatible record-level cloud sync for the Web client.

The Android client intentionally keeps database-backed content out of the file
sync namespaces.  Each content type has a small record manifest and one payload
object per stable record id.  This module mirrors that contract, including the
V2 local ancestry state, tombstones, LWW/conflict-copy policies, and the hidden
thought/poetry category dependencies.

Remote I/O is supplied by :mod:`cloudsync_engine`; this keeps WebDAV/S3 object
verification and the single shared outer manifest in one place.
"""
from __future__ import annotations

import base64
import binascii
import hashlib
import json
import math
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

from ..core.config import PRIVATE_DIR
from ..core.db import write_lock
from ..core.errors import ApiError
from .android_json import android_float32, android_json_bytes

MAX_PAYLOAD_BYTES = 4 * 1024 * 1024
MAX_AGENT_PAYLOAD_BYTES = 64 * 1024 * 1024
MAX_USAGE_PAYLOAD_BYTES = 10 * 1024 * 1024 + 64 * 1024
MAX_VAULT_PAYLOAD_BYTES = 8 * 1024 * 1024
MAX_RECORD_STRING_CHARS = 1_000_000
MAX_RECORDS = 10_000
MAX_STATE_BYTES = 8 * 1024 * 1024
EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()
SHA256_RE = re.compile(r"[0-9a-f]{64}")
SAFE_AGENT_ID_RE = re.compile(r"[A-Za-z0-9._:-]{1,200}")

MAX_AGENT_CONVERSATIONS = 10_000
MAX_AGENT_MESSAGES = 100_000
MAX_AGENT_ATTACHMENTS = 200_000
MAX_AGENT_RUNS = 100_000
MAX_AGENT_TIMESTAMP = 253_402_300_799_999
MAX_AGENT_TOKENS = 1_000_000_000_000
MAX_AGENT_CALLS = 1_000_000

REMOTE_DIRECTORIES = {
    "THOUGHTS": "records/thoughts",
    "THOUGHT_CATEGORIES": "records/thought-categories",
    "DATE_RECORDS": "records/date-records",
    "POEMS": "records/poems",
    "POETRY_CATEGORIES": "records/poetry-categories",
    "FAVORITES": "records/favorites",
    "RSS_SUBSCRIPTIONS": "records/rss-subscriptions",
    "GAME_STATES": "records/game-states",
    "GAME_STATISTICS": "records/game-statistics",
    "USAGE_STATISTICS": "records/usage",
    "READING_PROGRESS": "records/reader-progress",
    "READER_PREFERENCES": "records/reader-preferences",
    "AGENT_CHATS": "records/agent-chats",
    "VAULT": "records/vault",
    "GLOBAL_SETTINGS": "records/global-settings",
}

CONFLICT_COPY_CONTENTS = {"THOUGHTS", "DATE_RECORDS", "POEMS"}

# Categories must arrive before records that refer to them by name.  Android
# hides these switches and adds them as dependencies; making the ordering
# explicit also avoids temporarily dropping a category relation on a new Web
# device.
CONTENT_ORDER = (
    "THOUGHT_CATEGORIES", "THOUGHTS", "DATE_RECORDS",
    "POETRY_CATEGORIES", "POEMS", "FAVORITES", "RSS_SUBSCRIPTIONS",
    "GAME_STATES", "GAME_STATISTICS", "USAGE_STATISTICS",
    "READING_PROGRESS", "READER_PREFERENCES", "AGENT_CHATS", "VAULT",
    "GLOBAL_SETTINGS",
)

# AgentDataSource declaration order in Android AppModels.kt. Global settings
# serializes this Set by enum ordinal; lexical sorting would produce a different
# payload on Web and make the two clients alternately re-upload the same values.
AGENT_SOURCE_ORDER = (
    "diary", "thoughts", "date_records", "daily_events", "notes", "poems",
    "usage", "statistics", "app_guide",
)


def _sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _json_bytes(value: Any) -> bytes:
    try:
        return android_json_bytes(value)
    except (TypeError, ValueError) as exc:
        raise ApiError(500, "record_encode_failed", "A local sync record could not be encoded") from exc


def _content_revision(payload: bytes) -> int:
    value = int(_sha(payload)[:16], 16)
    return value - 2**64 if value >= 2**63 else value


def _stable_record_id(local_key: str) -> str:
    digest = _sha(("record-key\0" + local_key).encode("utf-8"))[:32]
    return "record-" + digest


def _deterministic_conflict_id(original_id: str, local_sha: str, remote_sha: str) -> str:
    digest = _sha((original_id + "\0" + local_sha + "\0" + remote_sha).encode("utf-8"))
    return f"{original_id}-conflict-{digest}"


def _next_tombstone_revision(max_known: int, now: int) -> int:
    # Kotlin Long arithmetic wraps. In particular, Long.MAX_VALUE + 1 becomes
    # Long.MIN_VALUE, after which Android's maxOf(now, wrapped) selects `now`.
    # Python integers do not overflow, so mirror that boundary explicitly and
    # never emit 2**63 into a manifest that neither client can decode as Long.
    incremented = -(2**63) if max_known == 2**63 - 1 else max_known + 1
    return max(now, incremented)


def _payload_limit(content: str) -> int:
    if content == "AGENT_CHATS":
        return MAX_AGENT_PAYLOAD_BYTES
    if content == "USAGE_STATISTICS":
        return MAX_USAGE_PAYLOAD_BYTES
    if content == "VAULT":
        return MAX_VAULT_PAYLOAD_BYTES
    return MAX_PAYLOAD_BYTES


def _payload_key(content: str, record_id: str) -> str:
    _validate_record_id(content, record_id)
    encoded = base64.urlsafe_b64encode(record_id.encode("utf-8")).decode("ascii").rstrip("=")
    return f"{REMOTE_DIRECTORIES[content]}/{encoded}.json"


def _manifest_key(content: str) -> str:
    return f"sync-meta/{REMOTE_DIRECTORIES[content]}/manifest.json"


def _validate_record_id(content: str, record_id: Any) -> str:
    if not isinstance(record_id, str) or not record_id:
        raise ApiError(502, "record_manifest_invalid", "Remote record manifest contains an invalid id")
    key = f"records/{REMOTE_DIRECTORIES[content].split('/', 1)[1]}/{record_id}"
    if (
        key.startswith("/") or key.endswith("/") or "\\" in key or
        any(ord(ch) < 32 or ord(ch) == 127 for ch in key) or
        any(part in ("", ".", "..") for part in key.split("/")) or len(key) > 2_048
    ):
        raise ApiError(502, "record_manifest_invalid", "Remote record manifest contains an invalid id")
    return record_id


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _required_int(obj: dict[str, Any], key: str, lo: int = -(2**63), hi: int = 2**63 - 1) -> int:
    value = obj.get(key)
    if not _is_int(value) or not lo <= value <= hi:
        raise ApiError(502, "record_payload_invalid", "Remote record payload is invalid")
    return int(value)


def _required_str(obj: dict[str, Any], key: str, max_chars: int = MAX_RECORD_STRING_CHARS) -> str:
    value = obj.get(key)
    if not isinstance(value, str) or len(value) > max_chars:
        raise ApiError(502, "record_payload_invalid", "Remote record payload is invalid")
    return value


def _optional_str(obj: dict[str, Any], key: str, max_chars: int = MAX_RECORD_STRING_CHARS) -> str | None:
    value = obj.get(key)
    if value is None:
        return None
    if not isinstance(value, str) or len(value) > max_chars:
        raise ApiError(502, "record_payload_invalid", "Remote record payload is invalid")
    return value


def _required_bool(obj: dict[str, Any], key: str) -> bool:
    value = obj.get(key)
    if not isinstance(value, bool):
        raise ApiError(502, "record_payload_invalid", "Remote record payload is invalid")
    return value


def _payload_object(payload: bytes, *, max_bytes: int = MAX_PAYLOAD_BYTES) -> dict[str, Any]:
    if not payload or len(payload) > max_bytes:
        raise ApiError(502, "record_payload_invalid", "Remote record payload size is invalid")
    try:
        value = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, ValueError, RecursionError) as exc:
        raise ApiError(502, "record_payload_invalid", "Remote record payload is invalid") from exc
    if not isinstance(value, dict):
        raise ApiError(502, "record_payload_invalid", "Remote record payload is invalid")
    return value


@dataclass(frozen=True)
class LocalRef:
    local_key: str
    revision: int
    updated_at: int


@dataclass(frozen=True)
class SyncRecord:
    record_id: str
    revision: int
    updated_at: int
    payload: bytes

    @property
    def payload_sha(self) -> str:
        return _sha(self.payload)


class RecordAdapter:
    """Small SQLite/file adapters matching Android's record payloads."""

    def __init__(self, con, content: str):
        self.con = con
        self.content = content
        self.conflict_copy = content in CONFLICT_COPY_CONTENTS

    def list_local(self) -> list[LocalRef]:
        c = self.content
        if c == "THOUGHTS":
            rows = self.con.execute(
                "SELECT id, updatedAt FROM flash_thoughts WHERE deletedAt IS NULL ORDER BY id"
            ).fetchall()
            return [LocalRef(str(r["id"]), max(0, int(r["updatedAt"])), max(0, int(r["updatedAt"]))) for r in rows]
        if c == "THOUGHT_CATEGORIES":
            return self._timestamp_refs("thought_categories", "id")
        if c == "DATE_RECORDS":
            return self._timestamp_refs("date_records", "id")
        if c == "POEMS":
            return self._timestamp_refs("saved_poems", "id")
        if c == "POETRY_CATEGORIES":
            return self._timestamp_refs("poetry_categories", "id")
        if c == "FAVORITES":
            rows = self.con.execute(
                "SELECT url, lastVisitedAt FROM browser_records WHERE favorite = 1 ORDER BY url"
            ).fetchall()
            return [LocalRef(str(r["url"]), max(0, int(r["lastVisitedAt"])), max(0, int(r["lastVisitedAt"]))) for r in rows]
        if c == "GAME_STATES":
            return self._timestamp_refs("game_states", "gameId")
        if c == "GAME_STATISTICS":
            rows = self.con.execute(
                "SELECT gameId, metricKey, updatedAt FROM game_statistics ORDER BY gameId, metricKey"
            ).fetchall()
            return [
                LocalRef(f"{r['gameId']}\0{r['metricKey']}", max(0, int(r["updatedAt"])), max(0, int(r["updatedAt"])))
                for r in rows
            ]
        if c == "RSS_SUBSCRIPTIONS":
            settings = self._settings()
            refs: list[LocalRef] = []
            for item in settings.get("rssSubscriptions") or []:
                if not isinstance(item, dict) or not isinstance(item.get("id"), str):
                    continue
                payload = self._rss_payload(item)
                rev = _content_revision(payload)
                refs.append(LocalRef(item["id"], rev, rev))
            return refs
        if c == "USAGE_STATISTICS":
            from .backup_codec import build_usage_devices

            return [
                LocalRef(str(item["deviceId"]), max(0, int(item.get("updatedAtEpochMillis") or 0)),
                         max(0, int(item.get("updatedAtEpochMillis") or 0)))
                for item in build_usage_devices(self.con)
            ]
        if c == "READING_PROGRESS":
            from .reader_service import read_ledger_records

            return [
                LocalRef(f"{item['fingerprint']}\0{item['type']}", max(0, int(item["updatedAt"])),
                         max(0, int(item["updatedAt"])))
                for item in read_ledger_records()
            ]
        if c == "AGENT_CHATS":
            revision = self._agent_revision()
            return [LocalRef("agent-chats", max(1, revision), max(0, revision))]
        if c == "VAULT":
            from .backup_codec import build_vault

            backup = build_vault(self.con)
            revision = max((int(i.get("updatedAt") or 0) for i in backup.get("items") or []), default=0)
            return [LocalRef("vault", max(1, revision), max(0, revision))]
        if c == "GLOBAL_SETTINGS":
            payload = self._global_settings_payload()
            rev = _content_revision(payload)
            return [LocalRef("global-settings", rev, rev)]
        if c == "READER_PREFERENCES":
            payload = self._reader_preferences_payload()
            rev = _content_revision(payload)
            return [LocalRef("reader-preferences", rev, rev)]
        raise ApiError(500, "record_adapter_missing", f"Record sync adapter is missing for {c}")

    def _timestamp_refs(self, table: str, key: str) -> list[LocalRef]:
        rows = self.con.execute(
            f"SELECT {key} AS localKey, updatedAt FROM {table} ORDER BY {key}"
        ).fetchall()
        return [
            LocalRef(str(r["localKey"]), max(0, int(r["updatedAt"])), max(0, int(r["updatedAt"])))
            for r in rows
        ]

    def read_local(self, local_key: str) -> SyncRecord:
        c = self.content
        payload: bytes
        revision: int
        updated: int
        if c == "THOUGHTS":
            row = self._row("flash_thoughts", "id", local_key)
            category_name = self._category_name("thought_categories", row["categoryId"])
            payload = _json_bytes({
                "content": row["content"], "createdAt": row["createdAt"],
                "updatedAt": row["updatedAt"], "pinned": bool(row["pinned"]),
                "sortOrder": row["sortOrder"], "categoryName": category_name,
                "highlighted": bool(row["highlighted"]),
            })
            revision = updated = max(0, int(row["updatedAt"]))
        elif c in ("THOUGHT_CATEGORIES", "POETRY_CATEGORIES"):
            table = "thought_categories" if c == "THOUGHT_CATEGORIES" else "poetry_categories"
            row = self._row(table, "id", local_key)
            payload = _json_bytes({
                "name": row["name"], "colorArgb": row["colorArgb"],
                "sortOrder": row["sortOrder"], "createdAt": row["createdAt"],
                "updatedAt": row["updatedAt"],
            })
            revision = updated = max(0, int(row["updatedAt"]))
        elif c == "DATE_RECORDS":
            row = self._row("date_records", "id", local_key)
            payload = _json_bytes({k: row[k] for k in ("name", "icon", "dateIso", "createdAt", "updatedAt")})
            revision = updated = max(0, int(row["updatedAt"]))
        elif c == "POEMS":
            row = self._row("saved_poems", "id", local_key)
            payload = _json_bytes({
                "content": row["content"], "source": row["source"],
                "createdAt": row["createdAt"], "updatedAt": row["updatedAt"],
                "sortOrder": row["sortOrder"],
                "categoryName": self._category_name("poetry_categories", row["categoryId"]),
            })
            revision = updated = max(0, int(row["updatedAt"]))
        elif c == "FAVORITES":
            row = self._row("browser_records", "url", local_key)
            payload = _json_bytes({
                "url": row["url"], "title": row["title"],
                "lastVisitedAt": row["lastVisitedAt"], "visitCount": row["visitCount"],
            })
            revision = updated = max(0, int(row["lastVisitedAt"]))
        elif c == "GAME_STATES":
            row = self._row("game_states", "gameId", local_key)
            payload = _json_bytes({
                "gameId": row["gameId"], "highScore": row["highScore"],
                "saveJson": row["saveJson"], "updatedAt": row["updatedAt"],
            })
            revision = updated = max(0, int(row["updatedAt"]))
        elif c == "GAME_STATISTICS":
            parts = local_key.split("\0")
            if len(parts) != 2:
                raise ApiError(409, "local_changed", "A local game statistic changed during sync")
            row = self.con.execute(
                "SELECT * FROM game_statistics WHERE gameId = ? AND metricKey = ?", tuple(parts)
            ).fetchone()
            if row is None:
                raise ApiError(409, "local_changed", "A local game statistic changed during sync")
            payload = _json_bytes({k: row[k] for k in ("gameId", "metricKey", "value", "updatedAt")})
            revision = updated = max(0, int(row["updatedAt"]))
        elif c == "RSS_SUBSCRIPTIONS":
            item = next((i for i in self._settings().get("rssSubscriptions") or []
                         if isinstance(i, dict) and i.get("id") == local_key), None)
            if item is None:
                raise ApiError(409, "local_changed", "A local RSS subscription changed during sync")
            payload = self._rss_payload(item)
            revision = updated = _content_revision(payload)
        elif c == "USAGE_STATISTICS":
            from .backup_codec import build_usage_devices

            item = next((i for i in build_usage_devices(self.con) if i.get("deviceId") == local_key), None)
            if item is None:
                raise ApiError(409, "local_changed", "Local usage statistics changed during sync")
            payload = _json_bytes(item)
            revision = updated = max(0, int(item.get("updatedAtEpochMillis") or 0))
        elif c == "READING_PROGRESS":
            from .reader_service import encode_progress, read_ledger_records

            parts = local_key.split("\0")
            if len(parts) != 2:
                raise ApiError(409, "local_changed", "Local reader progress changed during sync")
            item = next((i for i in read_ledger_records()
                         if i["fingerprint"] == parts[0] and i["type"] == parts[1]), None)
            if item is None:
                raise ApiError(409, "local_changed", "Local reader progress changed during sync")
            payload = encode_progress([item]).encode("utf-8")
            revision = updated = max(0, int(item["updatedAt"]))
        elif c == "AGENT_CHATS":
            payload = self._agent_payload()
            revision = self._agent_revision()
            revision, updated = max(1, revision), max(0, revision)
        elif c == "VAULT":
            payload, revision = self._vault_payload()
            revision, updated = max(1, revision), max(0, revision)
        elif c == "GLOBAL_SETTINGS":
            payload = self._global_settings_payload()
            revision = updated = _content_revision(payload)
        elif c == "READER_PREFERENCES":
            payload = self._reader_preferences_payload()
            revision = updated = _content_revision(payload)
        else:  # pragma: no cover - guarded by factory/list_local
            raise ApiError(500, "record_adapter_missing", f"Record sync adapter is missing for {c}")
        limit = _payload_limit(c)
        if not payload or len(payload) > limit:
            raise ApiError(413, "record_too_large", "A local record exceeds the sync limit")
        return SyncRecord("local", revision, updated, payload)

    def apply_remote(
        self,
        record: SyncRecord,
        *,
        canonical_local_key: str | None = None,
        preserve_local: SyncRecord | None = None,
    ) -> tuple[str, str | None]:
        c = self.content
        if c == "THOUGHTS":
            with write_lock(), self.con:
                canonical = self._apply_thought(record.payload, canonical_local_key)
                conflict = self._apply_thought(preserve_local.payload, None) if preserve_local else None
            return canonical, conflict
        if c in ("THOUGHT_CATEGORIES", "POETRY_CATEGORIES"):
            table = "thought_categories" if c == "THOUGHT_CATEGORIES" else "poetry_categories"
            child = "flash_thoughts" if c == "THOUGHT_CATEGORIES" else "saved_poems"
            return self._apply_category(table, child, record.payload, canonical_local_key), None
        if c == "DATE_RECORDS":
            with write_lock(), self.con:
                canonical = self._apply_date(record.payload, canonical_local_key)
                conflict = self._apply_date(preserve_local.payload, None) if preserve_local else None
            return canonical, conflict
        if c == "POEMS":
            with write_lock(), self.con:
                canonical = self._apply_poem(record.payload, canonical_local_key)
                conflict = self._apply_poem(preserve_local.payload, None) if preserve_local else None
            return canonical, conflict
        if c == "FAVORITES":
            return self._apply_favorite(record.payload), None
        if c == "RSS_SUBSCRIPTIONS":
            return self._apply_rss(record.payload), None
        if c == "GAME_STATES":
            return self._apply_game_state(record.payload), None
        if c == "GAME_STATISTICS":
            return self._apply_game_statistic(record.payload), None
        if c == "USAGE_STATISTICS":
            return self._apply_usage(record.payload), None
        if c == "READING_PROGRESS":
            return self._apply_reader_progress(record.payload, canonical_local_key), None
        if c == "READER_PREFERENCES":
            self._apply_reader_preferences(record.payload)
            return "reader-preferences", None
        if c == "AGENT_CHATS":
            self._merge_agent_payload(record.payload)
            return "agent-chats", None
        if c == "VAULT":
            self._apply_vault(record.payload)
            return "vault", None
        if c == "GLOBAL_SETTINGS":
            self._apply_global_settings(record.payload)
            return "global-settings", None
        raise ApiError(500, "record_adapter_missing", f"Record sync adapter is missing for {c}")

    def delete_local(self, local_key: str) -> None:
        c = self.content
        with write_lock(), self.con:
            if c == "THOUGHTS":
                row = self.con.execute("SELECT deletedAt FROM flash_thoughts WHERE id = ?", (local_key,)).fetchone()
                if row is not None:
                    if row["deletedAt"] is None:
                        self.con.execute("UPDATE flash_thoughts SET deletedAt = ?, updatedAt = ? WHERE id = ?",
                                         (int(time.time() * 1000), int(time.time() * 1000), local_key))
                    else:
                        self.con.execute("DELETE FROM flash_thoughts WHERE id = ?", (local_key,))
            elif c == "THOUGHT_CATEGORIES":
                self.con.execute("UPDATE flash_thoughts SET categoryId = NULL WHERE categoryId = ?", (local_key,))
                self.con.execute("DELETE FROM thought_categories WHERE id = ?", (local_key,))
            elif c == "DATE_RECORDS":
                self.con.execute("DELETE FROM date_records WHERE id = ?", (local_key,))
            elif c == "POEMS":
                self.con.execute("DELETE FROM saved_poems WHERE id = ?", (local_key,))
            elif c == "POETRY_CATEGORIES":
                self.con.execute("UPDATE saved_poems SET categoryId = NULL WHERE categoryId = ?", (local_key,))
                self.con.execute("DELETE FROM poetry_categories WHERE id = ?", (local_key,))
            elif c == "FAVORITES":
                self.con.execute("UPDATE browser_records SET favorite = 0 WHERE url = ?", (local_key,))
            elif c == "GAME_STATES":
                self.con.execute("UPDATE game_states SET saveJson = NULL, updatedAt = ? WHERE gameId = ?",
                                 (int(time.time() * 1000), local_key))
            elif c == "GAME_STATISTICS":
                parts = local_key.split("\0")
                if len(parts) == 2:
                    self.con.execute("DELETE FROM game_statistics WHERE gameId = ? AND metricKey = ?", tuple(parts))
            # Usage is merge-only; aggregate settings/vault/chat/preferences are
            # deliberately never deleted locally.
        if c == "RSS_SUBSCRIPTIONS":
            settings = self._settings()
            self._update_settings({"rssSubscriptions": [
                item for item in settings.get("rssSubscriptions") or []
                if not isinstance(item, dict) or item.get("id") != local_key
            ]})
        elif c == "READING_PROGRESS":
            from .reader_service import read_ledger_records, write_ledger_records

            parts = local_key.split("\0")
            if len(parts) == 2:
                write_ledger_records([
                    item for item in read_ledger_records()
                    if not (item["fingerprint"] == parts[0] and item["type"] == parts[1])
                ])

    # ---- simple DB payload helpers -------------------------------------

    def _row(self, table: str, key: str, value: str):
        row = self.con.execute(f"SELECT * FROM {table} WHERE {key} = ? LIMIT 1", (value,)).fetchone()
        if row is None:
            raise ApiError(409, "local_changed", "A local record changed during sync")
        return row

    def _category_name(self, table: str, category_id: Any) -> str | None:
        if category_id is None:
            return None
        row = self.con.execute(f"SELECT name FROM {table} WHERE id = ?", (category_id,)).fetchone()
        return str(row["name"]) if row else None

    def _category_id(self, table: str, name: str | None) -> int | None:
        if name is None:
            return None
        row = self.con.execute(f"SELECT id FROM {table} WHERE name = ? COLLATE NOCASE LIMIT 1", (name,)).fetchone()
        return int(row["id"]) if row else None

    def _apply_thought(self, payload: bytes, local_key: str | None) -> str:
        obj = _payload_object(payload)
        values = (
            _required_str(obj, "content"), _required_int(obj, "createdAt", 0),
            _required_int(obj, "updatedAt", 0), int(_required_bool(obj, "pinned")),
            _required_int(obj, "sortOrder"),
            self._category_id("thought_categories", _optional_str(obj, "categoryName")),
            int(_required_bool(obj, "highlighted")),
        )
        exists = local_key is not None and self.con.execute(
            "SELECT 1 FROM flash_thoughts WHERE id = ?", (local_key,)
        ).fetchone() is not None
        if exists:
            self.con.execute(
                "UPDATE flash_thoughts SET content=?, createdAt=?, updatedAt=?, pinned=?, deletedAt=NULL, "
                "sortOrder=?, categoryId=?, highlighted=? WHERE id=?", (*values, local_key),
            )
            return str(local_key)
        cur = self.con.execute(
            "INSERT INTO flash_thoughts(content,createdAt,updatedAt,pinned,deletedAt,sortOrder,categoryId,highlighted) "
            "VALUES(?,?,?,?,NULL,?,?,?)", values,
        )
        return str(cur.lastrowid)

    def _apply_category(self, table: str, child: str, payload: bytes, local_key: str | None) -> str:
        obj = _payload_object(payload)
        # Keep the exact Room value. Android's adapter does not trim category
        # names while applying a remote record; trimming here would change the
        # next payload hash and make the two devices fight over whitespace.
        name = _required_str(obj, "name")
        values = (name, _required_int(obj, "colorArgb", -(2**31), 2**31 - 1),
                  _required_int(obj, "sortOrder"), _required_int(obj, "createdAt", 0),
                  _required_int(obj, "updatedAt", 0))
        with write_lock(), self.con:
            exists = local_key is not None and self.con.execute(
                f"SELECT 1 FROM {table} WHERE id = ?", (local_key,)
            ).fetchone() is not None
            if exists:
                # Room @Upsert updates this primary key directly. A duplicate
                # name therefore fails the transaction instead of silently
                # remapping this record identity onto another category.
                self.con.execute(
                    f"UPDATE {table} SET name=?,colorArgb=?,sortOrder=?,createdAt=?,updatedAt=? WHERE id=?",
                    (*values, local_key),
                )
                return str(local_key)
            same = self.con.execute(f"SELECT id FROM {table} WHERE name = ? COLLATE NOCASE", (name,)).fetchone()
            if same is not None:
                return str(same["id"])
            next_sort_order = int(self.con.execute(
                f"SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM {table}"
            ).fetchone()[0])
            cur = self.con.execute(
                f"INSERT INTO {table}(name,colorArgb,sortOrder,createdAt,updatedAt) VALUES(?,?,?,?,?)",
                (values[0], values[1], next_sort_order, values[3], values[4]),
            )
            return str(cur.lastrowid)

    def _apply_date(self, payload: bytes, local_key: str | None) -> str:
        obj = _payload_object(payload)
        values = (_required_str(obj, "name"), _required_str(obj, "icon"),
                  _required_str(obj, "dateIso"), _required_int(obj, "createdAt", 0),
                  _required_int(obj, "updatedAt", 0))
        exists = local_key is not None and self.con.execute(
            "SELECT 1 FROM date_records WHERE id = ?", (local_key,)
        ).fetchone() is not None
        if exists:
            self.con.execute(
                "UPDATE date_records SET name=?,icon=?,dateIso=?,createdAt=?,updatedAt=? WHERE id=?",
                (*values, local_key),
            )
            return str(local_key)
        cur = self.con.execute(
            "INSERT INTO date_records(name,icon,dateIso,createdAt,updatedAt) VALUES(?,?,?,?,?)", values
        )
        return str(cur.lastrowid)

    def _apply_poem(self, payload: bytes, local_key: str | None) -> str:
        obj = _payload_object(payload)
        values = (_required_str(obj, "content"), _required_str(obj, "source"),
                  _required_int(obj, "createdAt", 0), _required_int(obj, "updatedAt", 0),
                  _required_int(obj, "sortOrder"),
                  self._category_id("poetry_categories", _optional_str(obj, "categoryName")))
        exists = local_key is not None and self.con.execute(
            "SELECT 1 FROM saved_poems WHERE id = ?", (local_key,)
        ).fetchone() is not None
        if exists:
            self.con.execute(
                "UPDATE saved_poems SET content=?,source=?,createdAt=?,updatedAt=?,sortOrder=?,categoryId=? WHERE id=?",
                (*values, local_key),
            )
            return str(local_key)
        cur = self.con.execute(
            "INSERT INTO saved_poems(content,source,createdAt,updatedAt,sortOrder,categoryId) VALUES(?,?,?,?,?,?)",
            values,
        )
        return str(cur.lastrowid)

    def _apply_favorite(self, payload: bytes) -> str:
        obj = _payload_object(payload)
        url = _required_str(obj, "url")
        title = _required_str(obj, "title")
        visited = _required_int(obj, "lastVisitedAt", 0)
        count = _required_int(obj, "visitCount", 0, 2**31 - 1)
        with write_lock(), self.con:
            existing = self.con.execute("SELECT visitCount FROM browser_records WHERE url = ?", (url,)).fetchone()
            count = max(count, int(existing["visitCount"])) if existing else count
            self.con.execute(
                "INSERT INTO browser_records(url,title,lastVisitedAt,visitCount,favorite) VALUES(?,?,?,?,1) "
                "ON CONFLICT(url) DO UPDATE SET title=excluded.title,lastVisitedAt=excluded.lastVisitedAt,"
                "visitCount=excluded.visitCount,favorite=1", (url, title, visited, count),
            )
        return url

    def _apply_game_state(self, payload: bytes) -> str:
        obj = _payload_object(payload)
        game_id = _required_str(obj, "gameId")
        values = (game_id, _required_int(obj, "highScore", -(2**31), 2**31 - 1),
                  self._optional_record_payload_string(obj, "saveJson"), _required_int(obj, "updatedAt", 0))
        with write_lock(), self.con:
            existing = self.con.execute("SELECT updatedAt FROM game_states WHERE gameId = ?", (game_id,)).fetchone()
            if existing is None or values[3] >= int(existing["updatedAt"]):
                self.con.execute(
                    "INSERT INTO game_states(gameId,highScore,saveJson,updatedAt) VALUES(?,?,?,?) "
                    "ON CONFLICT(gameId) DO UPDATE SET highScore=excluded.highScore,saveJson=excluded.saveJson,"
                    "updatedAt=excluded.updatedAt", values,
                )
        return game_id

    def _optional_record_payload_string(self, obj: dict[str, Any], key: str) -> str | None:
        """Decode Android's legacy string-or-nested-JSON game save field."""
        value = obj.get(key)
        if value is None:
            return None
        if isinstance(value, str):
            cleaned = value
        elif isinstance(value, (dict, list)):
            cleaned = _json_bytes(value).decode("utf-8")
        else:
            raise ApiError(502, "record_payload_invalid", "Remote game save is invalid")
        if len(cleaned.encode("utf-8")) > MAX_PAYLOAD_BYTES:
            raise ApiError(502, "record_payload_invalid", "Remote game save exceeds the record limit")
        return cleaned

    def _apply_game_statistic(self, payload: bytes) -> str:
        obj = _payload_object(payload)
        values = (_required_str(obj, "gameId"), _required_str(obj, "metricKey"),
                  _required_int(obj, "value"), _required_int(obj, "updatedAt", 0))
        with write_lock(), self.con:
            self.con.execute(
                "INSERT INTO game_statistics(gameId,metricKey,value,updatedAt) VALUES(?,?,?,?) "
                "ON CONFLICT(gameId,metricKey) DO UPDATE SET value=excluded.value,updatedAt=excluded.updatedAt",
                values,
            )
        return f"{values[0]}\0{values[1]}"

    # ---- settings/file aggregate adapters ------------------------------

    def _settings(self) -> dict[str, Any]:
        from .settings_store import load_settings

        return load_settings(self.con)

    def _update_settings(self, patch: dict[str, Any]) -> None:
        from .settings_store import update_settings

        with write_lock():
            update_settings(self.con, patch)

    def _rss_payload(self, item: dict[str, Any]) -> bytes:
        return _json_bytes({
            "id": str(item.get("id") or ""), "title": str(item.get("title") or ""),
            "url": str(item.get("url") or ""), "enabled": bool(item.get("enabled", True)),
        })

    def _apply_rss(self, payload: bytes) -> str:
        obj = _payload_object(payload)
        remote = {"id": _required_str(obj, "id"), "title": _required_str(obj, "title"),
                  "url": _required_str(obj, "url"), "enabled": _required_bool(obj, "enabled")}
        settings = self._settings()
        current = [dict(i) for i in settings.get("rssSubscriptions") or [] if isinstance(i, dict)]
        same = next((i for i in current if str(i.get("url") or "").casefold() == remote["url"].casefold()), None)
        if same is not None:
            same_id = str(same.get("id") or remote["id"])
            updated = [{**i, "title": remote["title"] or str(i.get("title") or ""),
                        "enabled": remote["enabled"]} if i is same else i for i in current]
            self._update_settings({"rssSubscriptions": updated})
            return same_id
        self._update_settings({"rssSubscriptions": current + [remote]})
        return remote["id"]

    def _apply_usage(self, payload: bytes) -> str:
        from .backup_import import BackupDecodeError, _decode_usage_devices
        from .usage_service import merge_android_usage_devices

        obj = _payload_object(payload, max_bytes=MAX_USAGE_PAYLOAD_BYTES)
        try:
            records = _decode_usage_devices([obj])
        except BackupDecodeError as exc:
            raise ApiError(502, "record_payload_invalid", "Usage record payload is invalid") from exc
        merge_android_usage_devices(self.con, records)
        return str(records[0]["deviceId"])

    def _apply_reader_progress(self, payload: bytes, local_key: str | None) -> str:
        from .reader_service import decode_progress, merged_document, read_ledger_records, write_ledger_records

        records = decode_progress(payload)
        if len(records) != 1:
            raise ApiError(502, "record_payload_invalid", "Reader progress payload must contain one record")
        write_ledger_records(merged_document(read_ledger_records() + records))
        item = records[0]
        return f"{item['fingerprint']}\0{item['type']}"

    def _reader_preferences_path(self) -> Path:
        from .reader_service import PREFERENCES_PATH

        return PREFERENCES_PATH

    def _read_reader_preferences(self) -> dict[str, Any]:
        from .reader_service import read_reader_preferences

        return read_reader_preferences()

    def _reader_preferences_payload(self) -> bytes:
        from .reader_service import encode_reader_preferences

        return encode_reader_preferences()

    def _apply_reader_preferences(self, payload: bytes) -> None:
        from .reader_service import apply_reader_preferences_payload

        apply_reader_preferences_payload(payload)

    # ---- Agent aggregate ------------------------------------------------

    def _agent_payload(self) -> bytes:
        from .backup_codec import build_agent_chats_b64

        encoded = build_agent_chats_b64(self.con)
        try:
            return base64.b64decode(encoded, validate=True)
        except (ValueError, binascii.Error) as exc:  # pragma: no cover - locally generated
            raise ApiError(500, "record_encode_failed", "Agent chat snapshot could not be encoded") from exc

    def _agent_revision(self) -> int:
        conversation = self.con.execute("SELECT COALESCE(MAX(updatedAt), 0) FROM ai_conversations").fetchone()[0]
        completed = self.con.execute("SELECT COALESCE(MAX(completedAt), 0) FROM agent_runs").fetchone()[0]
        return max(int(conversation or 0), int(completed or 0))

    def _merge_agent_payload(self, payload: bytes) -> None:
        root = _payload_object(payload, max_bytes=MAX_AGENT_PAYLOAD_BYTES)
        if root.get("format") != "deskcubby-agent-chats" or root.get("version") != 1:
            raise ApiError(502, "record_payload_invalid", "Agent chat payload is invalid")
        sections = {key: root.get(key) for key in ("conversations", "messages", "attachments", "runs")}
        section_limits = {
            "conversations": MAX_AGENT_CONVERSATIONS,
            "messages": MAX_AGENT_MESSAGES,
            "attachments": MAX_AGENT_ATTACHMENTS,
            "runs": MAX_AGENT_RUNS,
        }
        if any(
            not isinstance(value, list) or len(value) > section_limits[key]
            for key, value in sections.items()
        ):
            raise ApiError(502, "record_payload_invalid", "Agent chat payload is invalid")

        def safe_id(obj: dict[str, Any], key: str, *, optional: bool = False) -> str | None:
            value = obj.get(key)
            if value is None and optional:
                return None
            if not isinstance(value, str) or not SAFE_AGENT_ID_RE.fullmatch(value):
                raise ApiError(502, "record_payload_invalid", "Agent chat identity is invalid")
            return value

        def optional_long(obj: dict[str, Any], key: str, maximum: int = MAX_AGENT_TOKENS) -> int | None:
            value = obj.get(key)
            if value is None:
                return None
            if not _is_int(value) or not 0 <= value <= maximum:
                raise ApiError(502, "record_payload_invalid", "Agent run counter is invalid")
            return int(value)

        # Validate identity uniqueness and references before making the first
        # database change. SQLite rollback protects the transaction as well,
        # but fail-fast validation keeps malformed aggregate payloads inert.
        conversation_payload_ids: set[str] = set()
        for raw in sections["conversations"]:
            if not isinstance(raw, dict):
                raise ApiError(502, "record_payload_invalid", "Agent conversation is invalid")
            sync_id = safe_id(raw, "syncId")
            assert sync_id is not None
            if sync_id in conversation_payload_ids:
                raise ApiError(502, "record_payload_invalid", "Agent conversation ids are duplicated")
            conversation_payload_ids.add(sync_id)
        message_payload_ids: set[str] = set()
        for raw in sections["messages"]:
            if not isinstance(raw, dict):
                raise ApiError(502, "record_payload_invalid", "Agent message is invalid")
            sync_id = safe_id(raw, "syncId")
            parent = safe_id(raw, "conversationSyncId")
            assert sync_id is not None and parent is not None
            if sync_id in message_payload_ids or parent not in conversation_payload_ids:
                raise ApiError(502, "record_payload_invalid", "Agent message relationship is invalid")
            message_payload_ids.add(sync_id)
        attachment_payload_ids: set[str] = set()
        for raw in sections["attachments"]:
            if not isinstance(raw, dict):
                raise ApiError(502, "record_payload_invalid", "Agent attachment is invalid")
            sync_id = safe_id(raw, "syncId")
            parent = safe_id(raw, "messageSyncId")
            assert sync_id is not None and parent is not None
            if sync_id in attachment_payload_ids or parent not in message_payload_ids:
                raise ApiError(502, "record_payload_invalid", "Agent attachment relationship is invalid")
            attachment_payload_ids.add(sync_id)
        run_payload_ids: set[str] = set()
        for raw in sections["runs"]:
            if not isinstance(raw, dict):
                raise ApiError(502, "record_payload_invalid", "Agent run is invalid")
            run_id = safe_id(raw, "runId")
            parent = safe_id(raw, "conversationSyncId", optional=True)
            assert run_id is not None
            if run_id in run_payload_ids or (parent is not None and parent not in conversation_payload_ids):
                raise ApiError(502, "record_payload_invalid", "Agent run relationship is invalid")
            run_payload_ids.add(run_id)

        with write_lock(), self.con:
            conversation_ids = {
                str(r["syncId"]): int(r["id"]) for r in self.con.execute(
                    "SELECT id,syncId FROM ai_conversations WHERE syncId IS NOT NULL"
                ).fetchall()
            }
            for raw in sorted(sections["conversations"], key=lambda x: x.get("createdAt", 0) if isinstance(x, dict) else 0):
                obj = raw if isinstance(raw, dict) else {}
                sync_id = safe_id(obj, "syncId")
                assert sync_id is not None
                values = (_required_str(obj, "title", 500), _required_str(obj, "modelConfigId", 200),
                          _required_int(obj, "createdAt", 0, MAX_AGENT_TIMESTAMP),
                          _required_int(obj, "updatedAt", 0, MAX_AGENT_TIMESTAMP),
                          obj.get("deletedAt"))
                if values[3] < values[2] or (
                    values[4] is not None and (
                        not _is_int(values[4]) or not values[2] <= int(values[4]) <= MAX_AGENT_TIMESTAMP
                    )
                ):
                    raise ApiError(502, "record_payload_invalid", "Agent conversation timestamp is invalid")
                local = self.con.execute("SELECT * FROM ai_conversations WHERE syncId = ?", (sync_id,)).fetchone()
                if local is None:
                    cur = self.con.execute(
                        "INSERT INTO ai_conversations(title,modelConfigId,createdAt,updatedAt,syncId,deletedAt) "
                        "VALUES(?,?,?,?,?,?)", (*values[:4], sync_id, values[4]),
                    )
                    conversation_ids[sync_id] = int(cur.lastrowid)
                else:
                    remote_key = (values[3], values[4] is not None, values[0], values[1], values[2], values[4] or -1)
                    local_key = (int(local["updatedAt"]), local["deletedAt"] is not None,
                                 str(local["title"]), str(local["modelConfigId"]), int(local["createdAt"]),
                                 int(local["deletedAt"] or -1))
                    if remote_key > local_key:
                        self.con.execute(
                            "UPDATE ai_conversations SET title=?,modelConfigId=?,createdAt=?,updatedAt=?,deletedAt=? WHERE id=?",
                            (*values, local["id"]),
                        )
                    conversation_ids[sync_id] = int(local["id"])
            message_ids = {
                str(r["syncId"]): int(r["id"]) for r in self.con.execute(
                    "SELECT id,syncId FROM ai_messages WHERE syncId IS NOT NULL"
                ).fetchall()
            }
            for raw in sorted(sections["messages"], key=lambda x: x.get("createdAt", 0) if isinstance(x, dict) else 0):
                obj = raw if isinstance(raw, dict) else {}
                sync_id = safe_id(obj, "syncId")
                assert sync_id is not None
                if sync_id in message_ids:
                    continue
                conversation_sync_id = safe_id(obj, "conversationSyncId")
                assert conversation_sync_id is not None
                conv_id = conversation_ids.get(conversation_sync_id)
                if conv_id is None:
                    raise ApiError(502, "record_payload_invalid", "Agent message relationship is invalid")
                role = _required_str(obj, "role", 20)
                if role not in {"user", "assistant", "system"}:
                    raise ApiError(502, "record_payload_invalid", "Agent message role is invalid")
                cur = self.con.execute(
                    "INSERT INTO ai_messages(conversationId,role,content,reasoning,imageUri,imageMimeType,"
                    "imagePermissionOwned,createdAt,syncId) VALUES(?,?,?,?,NULL,?,0,?,?)",
                    (conv_id, role, _required_str(obj, "content", 1_000_000),
                     _required_str(obj, "reasoning", 1_000_000),
                     _optional_str(obj, "imageMimeType", 200),
                     _required_int(obj, "createdAt", 0, MAX_AGENT_TIMESTAMP), sync_id),
                )
                message_ids[sync_id] = int(cur.lastrowid)
            existing_attachments = {
                str(r[0]) for r in self.con.execute(
                    "SELECT syncId FROM ai_attachments WHERE syncId IS NOT NULL"
                ).fetchall()
            }
            for raw in sections["attachments"]:
                obj = raw if isinstance(raw, dict) else {}
                sync_id = safe_id(obj, "syncId")
                assert sync_id is not None
                if sync_id in existing_attachments:
                    continue
                message_sync_id = safe_id(obj, "messageSyncId")
                assert message_sync_id is not None
                message_id = message_ids.get(message_sync_id)
                if message_id is None:
                    raise ApiError(502, "record_payload_invalid", "Agent attachment relationship is invalid")
                kind = _required_str(obj, "kind", 20)
                if kind not in {"IMAGE", "DOCUMENT"}:
                    raise ApiError(502, "record_payload_invalid", "Agent attachment kind is invalid")
                self.con.execute(
                    "INSERT INTO ai_attachments(messageId,uri,mimeType,displayName,sizeBytes,kind,extractedText,"
                    "permissionOwned,syncId) VALUES(?,'',?,?,?,?,?,0,?)",
                    (message_id, _required_str(obj, "mimeType", 200), _required_str(obj, "displayName", 500),
                     _required_int(obj, "sizeBytes", 0, 64 * 1024 * 1024), kind,
                     _optional_str(obj, "extractedText", 256 * 1024), sync_id),
                )
            for raw in sections["runs"]:
                obj = raw if isinstance(raw, dict) else {}
                run_id = safe_id(obj, "runId")
                conversation_sync_id = safe_id(obj, "conversationSyncId", optional=True)
                assert run_id is not None
                conversation_id = conversation_ids.get(conversation_sync_id) if conversation_sync_id else None
                permission_mode = _required_str(obj, "permissionMode", 32)
                if permission_mode not in {"REQUIRE_APPROVAL", "FULL_AUTO"}:
                    raise ApiError(502, "record_payload_invalid", "Agent permission mode is invalid")
                sources_json = _required_str(obj, "enabledSourcesJson", 2_048)
                try:
                    sources = json.loads(sources_json)
                except (ValueError, RecursionError) as exc:
                    raise ApiError(502, "record_payload_invalid", "Agent source list is invalid") from exc
                if not isinstance(sources, list) or len(sources) > 64 or any(not isinstance(item, str) for item in sources):
                    raise ApiError(502, "record_payload_invalid", "Agent source list is invalid")
                raw_status = _required_str(obj, "status", 32).upper()
                status = {
                    "COMPLETED": "SUCCEEDED", "ERROR": "FAILED", "CANCELLED": "CANCELED",
                }.get(raw_status, raw_status)
                if status not in {"SUCCEEDED", "FAILED", "CANCELED"}:
                    raise ApiError(502, "record_payload_invalid", "Agent run status is invalid")
                input_tokens = optional_long(obj, "inputTokens")
                cached_tokens = optional_long(obj, "cachedInputTokens")
                cache_rate_tokens = optional_long(obj, "cacheRateInputTokens")
                if cache_rate_tokens is None and cached_tokens is not None:
                    cache_rate_tokens = input_tokens
                if cache_rate_tokens is not None and cached_tokens is not None and cached_tokens > cache_rate_tokens:
                    raise ApiError(502, "record_payload_invalid", "Agent cache counters are invalid")
                values = {
                    "runId": run_id,
                    "conversationId": conversation_id,
                    "conversationTitle": _required_str(obj, "conversationTitle", 500),
                    "userRequestSummary": _required_str(obj, "userRequestSummary", 2_000),
                    "modelConfigId": _required_str(obj, "modelConfigId", 200),
                    "permissionMode": permission_mode,
                    "enabledSourcesJson": sources_json,
                    "status": status,
                    "modelCallCount": _required_int(obj, "modelCallCount", 0, MAX_AGENT_CALLS),
                    "usageReportedCallCount": _required_int(obj, "usageReportedCallCount", 0, MAX_AGENT_CALLS),
                    "inputTokens": input_tokens,
                    "outputTokens": optional_long(obj, "outputTokens"),
                    "totalTokens": optional_long(obj, "totalTokens"),
                    "cachedInputTokens": cached_tokens,
                    "cacheRateInputTokens": cache_rate_tokens,
                    "reasoningTokens": optional_long(obj, "reasoningTokens"),
                    "startedAt": _required_int(obj, "startedAt", 0, MAX_AGENT_TIMESTAMP),
                    "completedAt": _required_int(obj, "completedAt", 0, MAX_AGENT_TIMESTAMP),
                }
                if values["completedAt"] < values["startedAt"]:
                    raise ApiError(502, "record_payload_invalid", "Agent run timestamp is invalid")
                local = self.con.execute("SELECT * FROM agent_runs WHERE runId = ?", (run_id,)).fetchone()
                if local is None:
                    self.con.execute(
                        "INSERT INTO agent_runs(runId,conversationId,conversationTitle,userRequestSummary,"
                        "modelConfigId,permissionMode,enabledSourcesJson,status,modelCallCount,"
                        "usageReportedCallCount,inputTokens,outputTokens,totalTokens,cachedInputTokens,"
                        "cacheRateInputTokens,reasoningTokens,startedAt,completedAt) "
                        "VALUES(:runId,:conversationId,:conversationTitle,:userRequestSummary,:modelConfigId,"
                        ":permissionMode,:enabledSourcesJson,:status,:modelCallCount,:usageReportedCallCount,"
                        ":inputTokens,:outputTokens,:totalTokens,:cachedInputTokens,:cacheRateInputTokens,"
                        ":reasoningTokens,:startedAt,:completedAt)",
                        values,
                    )
                else:
                    remote_key = (
                        int(values["completedAt"]), str(values["status"]),
                        str(values["conversationTitle"]), str(values["userRequestSummary"]),
                        "" if values["totalTokens"] is None else str(values["totalTokens"]),
                    )
                    local_key = (
                        int(local["completedAt"] or -1), str(local["status"]),
                        str(local["conversationTitle"]), str(local["userRequestSummary"]),
                        "" if local["totalTokens"] is None else str(local["totalTokens"]),
                    )
                    if remote_key > local_key:
                        self.con.execute(
                            "UPDATE agent_runs SET conversationId=:conversationId,conversationTitle=:conversationTitle,"
                            "userRequestSummary=:userRequestSummary,status=:status,modelCallCount=:modelCallCount,"
                            "usageReportedCallCount=:usageReportedCallCount,inputTokens=:inputTokens,"
                            "outputTokens=:outputTokens,totalTokens=:totalTokens,cachedInputTokens=:cachedInputTokens,"
                            "cacheRateInputTokens=:cacheRateInputTokens,reasoningTokens=:reasoningTokens,"
                            "completedAt=:completedAt WHERE runId=:runId",
                            values,
                        )

    # ---- Vault aggregate ------------------------------------------------

    def _vault_payload(self) -> tuple[bytes, int]:
        from .backup_codec import build_vault

        backup = build_vault(self.con)
        revision = max((int(i.get("updatedAt") or 0) for i in backup.get("items") or []), default=0)
        return _json_bytes({"format": "deskcubby-vault-sync", "version": 1, **backup}), revision

    def _apply_vault(self, payload: bytes) -> None:
        obj = _payload_object(payload, max_bytes=8 * 1024 * 1024)
        if obj.get("format") != "deskcubby-vault-sync" or obj.get("version") != 1:
            raise ApiError(502, "record_payload_invalid", "Vault sync payload is invalid")
        from .backup_import import BackupDecodeError, _decode_vault

        try:
            decoded = _decode_vault({k: obj.get(k) for k in ("active", "pending", "items")})
        except BackupDecodeError as exc:
            raise ApiError(502, "record_payload_invalid", "Vault sync payload is invalid") from exc
        from ..core.fs import safe_write_text
        from . import vault_service

        meta: dict[str, Any] = {}
        active = decoded.get("active")
        if isinstance(active, dict):
            meta.update({
                "metadataVersion": 2, "saltBase64": active["saltBase64"],
                "verifierCipher": active["verifierCipher"], "verifierIv": active["verifierIv"],
                "kdfIterations": active["iterations"],
            })
            if active.get("generationId"):
                meta["activeGenerationId"] = active["generationId"]
            pending = decoded.get("pending")
            if isinstance(pending, dict):
                meta.update({
                    "migrationState": "prepared_v1", "pendingSaltBase64": pending["saltBase64"],
                    "pendingVerifierCipher": pending["verifierCipher"], "pendingVerifierIv": pending["verifierIv"],
                    "pendingKdfIterations": pending["iterations"], "pendingGenerationId": pending["generationId"],
                })
        with write_lock(), self.con:
            self.con.execute("DELETE FROM vault_items")
            for item in decoded.get("items") or []:
                self.con.execute(
                    "INSERT INTO vault_items(id,cipherText,iv,createdAt,updatedAt,sortOrder) VALUES(?,?,?,?,?,?)",
                    (item["id"], item["cipherText"], item["iv"], item["createdAt"], item["updatedAt"], item["sortOrder"]),
                )
        path = PRIVATE_DIR / "vault-meta.json"
        if meta:
            path.parent.mkdir(parents=True, exist_ok=True)
            safe_write_text(path, json.dumps(meta, ensure_ascii=False, separators=(",", ":")))
        else:
            # A remotely restored empty vault has no key metadata. Keeping the
            # previous verifier would leave a phantom locked vault locally.
            path.unlink(missing_ok=True)
        vault_service.lock()

    # ---- Global settings aggregate -------------------------------------

    _GLOBAL_KEYS = (
        "visualStyle", "customTheme", "darkMode", "appLanguage", "userName", "homeGreetings",
        "themeColorArgb", "themeSecondaryColorsArgb", "tutorialModeEnabled", "fileNamePattern",
        "markdownTemplate", "imageNamePattern", "imageMaxWidthDp", "imageMaxHeightDp",
        "markdownHeadingSizesSp", "mealImageCompressionEnabled", "mealImageCompressionQuality",
        "saveOriginalToGallery", "photoLocationEnabled", "browserHomeUrl", "browserTheme",
        "browserDesktopMode", "thoughtDisplayMode", "thoughtHighlightColorArgb",
        "thoughtEditorMaxHeightDp", "poetryShowSource", "poetryShowQuoteMark",
        "poetrySevenCharacterWrapEnabled", "mealCalendarImageMaxHeightDp",
        "mealCalendarShowCaptions", "mealCalendarWrapEnabled", "mealCalendarPhotosPerRow",
        "mealPhotoFilter", "mealButtonsUseIcons", "mealButtonIcons", "dailyEventTemplates",
        "rssMaxItemsPerFeed", "rssShowSummaries", "aiEndpointUrl", "aiModel", "aiSystemPrompt",
        "aiTemperature", "aiAllowInsecureHttp", "aiConfigs", "aiChatConfigId",
        "agentEnabledSources", "agentPermissionMode", "agentPrompt", "calorieEstimationEnabled",
        "calorieTextConfigId", "calorieImageConfigId", "calorieVisionPrompt", "calorieTextPrompt",
        "game2048AnimationSpeed",
    )

    @staticmethod
    def _global_settings_root(
        settings: dict[str, Any], *, strict_nested_fields: bool = False,
    ) -> dict[str, Any]:
        """Project AppSettings in GlobalSettingsSyncCodec's exact wire order.

        Besides dropping device/secret fields, Android reconstructs every
        nested model explicitly. Passing stored dictionaries through verbatim
        would retain unknown keys/insertion order and generate a different
        content hash for otherwise identical settings.
        """
        from .settings_store import default_settings

        defaults = default_settings()

        def raw_value(key: str) -> Any:
            return settings[key] if key in settings else defaults[key]

        def string_value(key: str) -> str:
            value = raw_value(key)
            if not isinstance(value, str):
                raise ValueError(f"{key} must be a string")
            return value

        def nullable_string_value(key: str) -> str | None:
            value = raw_value(key)
            if value is not None and not isinstance(value, str):
                raise ValueError(f"{key} must be a string or null")
            return value

        def boolean_value(key: str) -> bool:
            value = raw_value(key)
            if not isinstance(value, bool):
                raise ValueError(f"{key} must be a boolean")
            return value

        def integer(value: Any, key: str) -> int:
            if isinstance(value, bool) or not isinstance(value, int) or not -(2**31) <= value <= 2**31 - 1:
                raise ValueError(f"{key} must be a 32-bit integer")
            return value

        def integer_value(key: str) -> int:
            return integer(raw_value(key), key)

        def float_wire(value: Any, key: str) -> float:
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)):
                raise ValueError(f"{key} must be a finite number")
            try:
                normalized = android_float32(value)
            except OverflowError as exc:
                raise ValueError(f"{key} must fit in a Float") from exc
            if not math.isfinite(normalized):
                raise ValueError(f"{key} must fit in a Float")
            return normalized

        def enum_value(key: str, allowed: set[str]) -> str:
            value = string_value(key)
            if value not in allowed:
                raise ValueError(f"{key} has an unsupported enum value")
            return value

        def object_value(key: str) -> dict[str, Any]:
            value = raw_value(key)
            if not isinstance(value, dict):
                raise ValueError(f"{key} must be an object")
            base = defaults[key]
            if strict_nested_fields and isinstance(base, dict):
                missing = set(base) - set(value)
                if missing:
                    raise ValueError(f"{key} is missing required fields")
            return {**base, **value} if isinstance(base, dict) else dict(value)

        palette_fields = (
            "backgroundArgb", "onBackgroundArgb", "surfaceArgb", "onSurfaceArgb",
            "surfaceContainerArgb", "surfaceVariantArgb", "onSurfaceVariantArgb",
            "outlineArgb",
        )

        def palette(value: Any, fallback: dict[str, Any]) -> dict[str, Any]:
            if not isinstance(value, dict):
                raise ValueError("custom theme palette must be an object")
            if strict_nested_fields and set(palette_fields) - set(value):
                raise ValueError("custom theme palette is missing required fields")
            merged = {**fallback, **value}
            return {key: integer(merged[key], f"customTheme.{key}") for key in palette_fields}

        custom = object_value("customTheme")
        default_custom = defaults["customTheme"]
        custom_wire = {
            "baseStyle": custom["baseStyle"],
            "lightPalette": palette(custom["lightPalette"], default_custom["lightPalette"]),
            "darkPalette": palette(custom["darkPalette"], default_custom["darkPalette"]),
            "cornerRadiusDp": float_wire(custom["cornerRadiusDp"], "customTheme.cornerRadiusDp"),
            "borderWidthDp": float_wire(custom["borderWidthDp"], "customTheme.borderWidthDp"),
            "elevationDp": float_wire(custom["elevationDp"], "customTheme.elevationDp"),
            "panelOpacity": float_wire(custom["panelOpacity"], "customTheme.panelOpacity"),
            "spacingScale": float_wire(custom["spacingScale"], "customTheme.spacingScale"),
            "animationScale": float_wire(custom["animationScale"], "customTheme.animationScale"),
        }
        if custom_wire["baseStyle"] not in {"MATERIAL", "LIQUID_GLASS", "ORGANIC_FUTURE"}:
            raise ValueError("customTheme.baseStyle has an unsupported enum value")

        meal = object_value("mealPhotoFilter")
        meal_wire = {
            "enabled": meal["enabled"],
            "brightness": float_wire(meal["brightness"], "mealPhotoFilter.brightness"),
            "contrast": float_wire(meal["contrast"], "mealPhotoFilter.contrast"),
            "saturation": float_wire(meal["saturation"], "mealPhotoFilter.saturation"),
            "warmth": float_wire(meal["warmth"], "mealPhotoFilter.warmth"),
            "tint": float_wire(meal["tint"], "mealPhotoFilter.tint"),
        }
        if not isinstance(meal_wire["enabled"], bool):
            raise ValueError("mealPhotoFilter.enabled must be a boolean")

        def object_list(key: str) -> list[dict[str, Any]]:
            value = raw_value(key)
            if not isinstance(value, list) or any(not isinstance(item, dict) for item in value):
                raise ValueError(f"{key} must be an object array")
            return value

        greetings: list[dict[str, str]] = []
        for item in object_list("homeGreetings"):
            if not isinstance(item.get("chinese"), str):
                raise ValueError("homeGreetings.chinese must be a string")
            if not isinstance(item.get("english"), str):
                raise ValueError("homeGreetings.english must be a string")
            greetings.append({"chinese": item["chinese"], "english": item["english"]})
        templates: list[dict[str, Any]] = []
        for item in object_list("dailyEventTemplates"):
            if any(not isinstance(item.get(key), str) for key in ("id", "text", "firstUnit", "secondUnit")):
                raise ValueError("dailyEventTemplates contains a non-string field")
            templates.append({key: item[key] for key in ("id", "text", "firstUnit", "secondUnit")})

        ai_configs: list[dict[str, Any]] = []
        for item in object_list("aiConfigs"):
            string_fields = ("id", "name", "type", "endpointUrl", "model", "systemPrompt")
            if any(not isinstance(item.get(key), str) for key in string_fields):
                raise ValueError("aiConfigs contains a non-string field")
            if item["type"] not in {"TEXT", "IMAGE"}:
                raise ValueError("aiConfigs.type has an unsupported enum value")
            if not isinstance(item.get("enabled"), bool) or not isinstance(item.get("allowInsecureHttp"), bool):
                raise ValueError("aiConfigs contains an invalid boolean")
            if not isinstance(item.get("supportsToolCalling"), bool):
                raise ValueError("aiConfigs.supportsToolCalling must be a boolean")
            ai_configs.append({
                "id": item["id"], "name": item["name"], "type": item["type"],
                "endpointUrl": item["endpointUrl"], "model": item["model"],
                "enabled": item["enabled"], "allowInsecureHttp": item["allowInsecureHttp"],
                "temperature": float_wire(item.get("temperature"), "aiConfigs.temperature"),
                "systemPrompt": item["systemPrompt"],
                "supportsToolCalling": item["supportsToolCalling"],
            })

        heading_sizes = raw_value("markdownHeadingSizesSp")
        if not isinstance(heading_sizes, list):
            raise ValueError("markdownHeadingSizesSp must be an array")
        sources = raw_value("agentEnabledSources")
        if not isinstance(sources, (list, set, tuple)) or any(not isinstance(item, str) for item in sources):
            raise ValueError("agentEnabledSources must be an array")
        selected_sources = set(sources)

        secondary_colors = raw_value("themeSecondaryColorsArgb")
        if not isinstance(secondary_colors, list):
            raise ValueError("themeSecondaryColorsArgb must be an array")
        secondary_colors_wire = [integer(item, "themeSecondaryColorsArgb") for item in secondary_colors]
        meal_icons = raw_value("mealButtonIcons")
        if not isinstance(meal_icons, list) or any(not isinstance(item, str) for item in meal_icons):
            raise ValueError("mealButtonIcons must be a string array")

        special: dict[str, Any] = {
            "visualStyle": enum_value("visualStyle", {"MATERIAL", "LIQUID_GLASS", "ORGANIC_FUTURE", "CUSTOM"}),
            "customTheme": custom_wire,
            "darkMode": enum_value("darkMode", {"SYSTEM", "LIGHT", "DARK"}),
            "appLanguage": enum_value(
                "appLanguage", {"CHINESE", "TRADITIONAL_CHINESE", "ENGLISH", "KOREAN", "JAPANESE"},
            ),
            "homeGreetings": greetings,
            "themeColorArgb": integer_value("themeColorArgb"),
            "themeSecondaryColorsArgb": secondary_colors_wire,
            "markdownHeadingSizesSp": [
                float_wire(item, "markdownHeadingSizesSp") for item in heading_sizes
            ],
            "browserTheme": enum_value("browserTheme", {"SYSTEM", "LIGHT", "DARK"}),
            "thoughtDisplayMode": enum_value("thoughtDisplayMode", {"SINGLE_LINE", "FULL"}),
            "thoughtHighlightColorArgb": integer_value("thoughtHighlightColorArgb"),
            "mealCalendarPhotosPerRow": enum_value("mealCalendarPhotosPerRow", {"TWO", "THREE", "SMART"}),
            "mealPhotoFilter": meal_wire,
            "mealButtonIcons": list(meal_icons),
            "dailyEventTemplates": templates,
            "aiTemperature": float_wire(raw_value("aiTemperature"), "aiTemperature"),
            "aiConfigs": ai_configs,
            "agentEnabledSources": [
                source for source in AGENT_SOURCE_ORDER if source in selected_sources
            ],
            "agentPermissionMode": enum_value(
                "agentPermissionMode", {"REQUIRE_APPROVAL", "FULL_AUTO"},
            ),
            "game2048AnimationSpeed": enum_value(
                "game2048AnimationSpeed", {"SLOW", "NORMAL", "FAST"},
            ),
        }
        for key in (
            "userName", "fileNamePattern", "markdownTemplate", "imageNamePattern",
            "browserHomeUrl", "aiEndpointUrl", "aiModel", "aiSystemPrompt", "agentPrompt",
            "calorieVisionPrompt", "calorieTextPrompt",
        ):
            special[key] = string_value(key)
        for key in ("aiChatConfigId", "calorieTextConfigId", "calorieImageConfigId"):
            special[key] = nullable_string_value(key)
        for key in (
            "tutorialModeEnabled", "mealImageCompressionEnabled", "saveOriginalToGallery",
            "photoLocationEnabled", "browserDesktopMode", "poetryShowSource",
            "poetryShowQuoteMark", "poetrySevenCharacterWrapEnabled", "mealCalendarShowCaptions",
            "mealCalendarWrapEnabled", "mealButtonsUseIcons", "rssShowSummaries",
            "aiAllowInsecureHttp", "calorieEstimationEnabled",
        ):
            special[key] = boolean_value(key)
        for key in (
            "imageMaxWidthDp", "imageMaxHeightDp", "mealImageCompressionQuality",
            "thoughtEditorMaxHeightDp", "mealCalendarImageMaxHeightDp", "rssMaxItemsPerFeed",
        ):
            special[key] = integer_value(key)

        if set(special) != set(RecordAdapter._GLOBAL_KEYS):
            raise ValueError("global settings projection is incomplete")
        root: dict[str, Any] = {
            "format": "deskcubby-global-settings",
            "version": 1,
        }
        for key in RecordAdapter._GLOBAL_KEYS:
            root[key] = special.get(key, settings.get(key, defaults[key]))
        return root

    def _global_settings_payload(self) -> bytes:
        try:
            root = self._global_settings_root(self._settings())
        except (KeyError, TypeError, ValueError) as exc:
            raise ApiError(
                500, "record_encode_failed", "Global settings could not be encoded",
            ) from exc
        payload = _json_bytes(root)
        if len(payload) > 1024 * 1024:
            raise ApiError(413, "record_too_large", "Global settings exceed the sync limit")
        return payload

    def _apply_global_settings(self, payload: bytes) -> None:
        obj = _payload_object(payload, max_bytes=1024 * 1024)
        if (
            obj.get("format") != "deskcubby-global-settings" or obj.get("version") != 1 or
            set(obj) != {"format", "version", *self._GLOBAL_KEYS}
        ):
            raise ApiError(502, "record_payload_invalid", "Global settings payload is invalid")
        try:
            canonical = self._global_settings_root(obj, strict_nested_fields=True)
        except (KeyError, TypeError, ValueError) as exc:
            raise ApiError(502, "record_payload_invalid", "Global settings payload is invalid") from exc
        patch = {key: canonical[key] for key in self._GLOBAL_KEYS}
        current_api_keys = {
            str(item.get("id")): str(item.get("apiKey") or "")
            for item in self._settings().get("aiConfigs") or []
            if isinstance(item, dict) and isinstance(item.get("id"), str)
        }
        configs = []
        for item in patch.get("aiConfigs") or []:
            configs.append({**item, "apiKey": current_api_keys.get(str(item.get("id")), "")})
        patch["aiConfigs"] = configs
        self._update_settings(patch)


# ---------------------------------------------------------------------------
# Record manifest + state codecs
# ---------------------------------------------------------------------------

def decode_record_manifest(data: bytes, content: str) -> dict[str, dict[str, Any]]:
    root = _payload_object(data, max_bytes=8 * 1024 * 1024)
    if root.get("format") != "deskcubby-record-manifest" or root.get("version") != 1 or root.get("contentType") != content:
        raise ApiError(502, "record_manifest_invalid", "Remote record manifest format is invalid")
    records = root.get("records")
    if not isinstance(records, list) or len(records) > MAX_RECORDS:
        raise ApiError(502, "record_manifest_invalid", "Remote record manifest size is invalid")
    result: dict[str, dict[str, Any]] = {}
    for raw in records:
        if not isinstance(raw, dict):
            raise ApiError(502, "record_manifest_invalid", "Remote record manifest entry is invalid")
        record_id = _validate_record_id(content, raw.get("id"))
        digest = raw.get("sha256")
        if record_id in result or not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
            raise ApiError(502, "record_manifest_invalid", "Remote record manifest entry is invalid")
        result[record_id] = {
            "id": record_id, "revision": _required_int(raw, "revision"),
            # Aggregate adapters use the signed Long interpretation of the
            # first eight SHA-256 bytes for both revision and updatedAt, exactly
            # like Android's Long.parseUnsignedLong(...).  Values with the high
            # bit set are therefore negative and remain valid wire values.
            "updatedAt": _required_int(raw, "updatedAt"),
            "deleted": _required_bool(raw, "deleted"), "sha256": digest,
        }
    return result


def encode_record_manifest(content: str, entries: dict[str, dict[str, Any]]) -> bytes:
    records = []
    for record_id in sorted(entries):
        entry = entries[record_id]
        _validate_record_id(content, record_id)
        records.append({
            "id": record_id, "revision": int(entry["revision"]),
            "updatedAt": int(entry["updatedAt"]), "deleted": bool(entry["deleted"]),
            "sha256": str(entry["sha256"]),
        })
    return _json_bytes({
        "format": "deskcubby-record-manifest", "version": 1,
        "contentType": content, "records": records,
    })


def _state_path(config_id: str, content: str) -> Path:
    suffix = _sha((config_id + "\0" + content).encode("utf-8"))[:32]
    return PRIVATE_DIR / "cloud-sync-record-state" / f"{suffix}.state"


def load_state(config_id: str, content: str, scope: str) -> dict[str, dict[str, Any]]:
    path = _state_path(config_id, content)
    if not path.is_file() or path.stat().st_size > MAX_STATE_BYTES:
        return {}
    try:
        root = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    if (
        not isinstance(root, dict) or root.get("format") != "DeskCubby-Record-Sync-State-2" or
        root.get("scopeFingerprint") != scope or root.get("contentType") != content
    ):
        return {}
    raw_entries = root.get("entries")
    if not isinstance(raw_entries, list) or len(raw_entries) > MAX_RECORDS:
        return {}
    result: dict[str, dict[str, Any]] = {}
    try:
        for raw in raw_entries:
            if not isinstance(raw, dict):
                return {}
            record_id = _validate_record_id(content, raw.get("id"))
            digest = raw.get("payloadSha256")
            local_key = raw.get("localKey")
            if (
                record_id in result or not isinstance(digest, str) or not SHA256_RE.fullmatch(digest) or
                (local_key is not None and (
                    not isinstance(local_key, str) or len(local_key) > MAX_RECORD_STRING_CHARS
                ))
            ):
                return {}
            result[record_id] = {
                "id": record_id, "revision": _required_int(raw, "revision"),
                "updatedAt": _required_int(raw, "updatedAt"),
                "deleted": _required_bool(raw, "deleted"), "sha256": digest,
                "localKey": local_key,
            }
    except ApiError:
        return {}
    return result


def save_state(config_id: str, content: str, scope: str, manifest_version: str | None,
               entries: dict[str, dict[str, Any]]) -> None:
    payload = _json_bytes({
        "format": "DeskCubby-Record-Sync-State-2", "scopeFingerprint": scope,
        "manifestVersion": manifest_version, "contentType": content,
        "entries": [
            {
                "id": item["id"], "revision": item["revision"], "updatedAt": item["updatedAt"],
                "deleted": item["deleted"], "payloadSha256": item["sha256"],
                "localKey": item.get("localKey"),
            }
            for _, item in sorted(entries.items())
        ],
    })
    if len(payload) > MAX_STATE_BYTES:
        raise ApiError(413, "record_state_too_large", "Local record sync state exceeds the size limit")
    from ..core.fs import safe_write

    path = _state_path(config_id, content)
    path.parent.mkdir(parents=True, exist_ok=True)
    safe_write(path, payload)


# ---------------------------------------------------------------------------
# Reconciliation engine
# ---------------------------------------------------------------------------

RemoteRead = Callable[[str, int], tuple[bytes, dict[str, Any]] | None]
RemoteWrite = Callable[[str, bytes], dict[str, Any]]


def sync_records(
    *, con, config: dict[str, Any], contents: set[str], mode: str,
    scope_fingerprint: str, remote_read: RemoteRead, remote_write: RemoteWrite,
) -> dict[str, Any]:
    uploaded = downloaded = conflicts = 0
    pending_states: list[tuple[str, str | None, dict[str, dict[str, Any]]]] = []
    ordered = [content for content in CONTENT_ORDER if content in contents]
    missing = contents.difference(ordered)
    if missing:
        raise ApiError(500, "record_adapter_missing", "Record sync adapter is missing")

    for content in ordered:
        adapter = RecordAdapter(con, content)
        state_entries = load_state(str(config.get("id") or ""), content, scope_fingerprint)
        manifest_key = _manifest_key(content)
        remote_blob = remote_read(manifest_key, 8 * 1024 * 1024)
        if remote_blob is None:
            remote_entries: dict[str, dict[str, Any]] = {}
            current_manifest_version = None
        else:
            manifest_bytes, manifest_outer_entry = remote_blob
            remote_entries = decode_record_manifest(manifest_bytes, content)
            current_manifest_version = str(manifest_outer_entry.get("blobVersion") or "") or None

        refs = adapter.list_local()
        if len(refs) > MAX_RECORDS or len({ref.local_key for ref in refs}) != len(refs):
            raise ApiError(409, "local_records_invalid", "Local record identities are invalid")
        id_by_local: dict[str, str] = {}
        ref_by_id: dict[str, LocalRef] = {}
        for ref in refs:
            existing_id = next((record_id for record_id, entry in state_entries.items()
                                if entry.get("localKey") == ref.local_key and not entry.get("deleted")), None)
            record_id = existing_id or _stable_record_id(ref.local_key)
            if record_id in ref_by_id:
                raise ApiError(409, "local_records_invalid", "Local record identities collided")
            id_by_local[ref.local_key] = record_id
            ref_by_id[record_id] = ref

        all_ids = sorted(set(ref_by_id) | set(remote_entries) | set(state_entries))
        if len(all_ids) > MAX_RECORDS:
            raise ApiError(413, "too_many_records", "A record content type exceeds the sync limit")
        next_entries: dict[str, dict[str, Any]] = {}
        updated_state: dict[str, dict[str, Any]] = {}

        def state_entry(entry: dict[str, Any], local_key: str | None) -> dict[str, Any]:
            return {**entry, "localKey": local_key}

        def local_record(record_id: str) -> SyncRecord | None:
            ref = ref_by_id.get(record_id)
            if ref is None:
                return None
            record = adapter.read_local(ref.local_key)
            if record.revision != ref.revision or record.updated_at != ref.updated_at:
                raise ApiError(409, "local_changed", "A local record changed during sync")
            return SyncRecord(record_id, record.revision, record.updated_at, record.payload)

        def upload(record_id: str, record: SyncRecord) -> None:
            remote_write(_payload_key(content, record_id), record.payload)
            entry = {"id": record_id, "revision": record.revision, "updatedAt": record.updated_at,
                     "deleted": False, "sha256": record.payload_sha}
            next_entries[record_id] = entry
            updated_state[record_id] = state_entry(entry, ref_by_id.get(record_id).local_key if record_id in ref_by_id else None)

        def apply_remote(entry: dict[str, Any], local_key: str | None = None) -> None:
            blob = remote_read(_payload_key(content, entry["id"]), _payload_limit(content))
            if blob is None:
                raise ApiError(502, "record_payload_missing", "Remote record payload is missing")
            payload, _outer_entry = blob
            if _sha(payload) != entry["sha256"]:
                raise ApiError(502, "record_payload_invalid", "Remote record payload failed verification")
            record = SyncRecord(entry["id"], entry["revision"], entry["updatedAt"], payload)
            canonical, _copy = adapter.apply_remote(record, canonical_local_key=local_key)
            next_entries[entry["id"]] = dict(entry)
            updated_state[entry["id"]] = state_entry(entry, canonical)

        for record_id in all_ids:
            ref = ref_by_id.get(record_id)
            remote = remote_entries.get(record_id)
            old = state_entries.get(record_id)
            local_key = ref.local_key if ref is not None else (old.get("localKey") if old else None)

            if ref is None and remote is None:
                if old is not None and not old.get("deleted"):
                    now = int(time.time() * 1000)
                    tomb = {"id": record_id, "revision": _next_tombstone_revision(int(old["revision"]), now),
                            "updatedAt": now, "deleted": True, "sha256": EMPTY_SHA256}
                    next_entries[record_id] = tomb
                    updated_state[record_id] = state_entry(tomb, None)
                elif old is not None and old.get("deleted"):
                    tomb = {k: old[k] for k in ("id", "revision", "updatedAt", "deleted", "sha256")}
                    next_entries[record_id] = tomb
                    updated_state[record_id] = state_entry(tomb, None)
                continue

            if remote is not None and remote["deleted"]:
                if local_key is not None:
                    adapter.delete_local(local_key)
                next_entries[record_id] = dict(remote)
                updated_state[record_id] = state_entry(remote, None)
                if ref is not None:
                    downloaded += 1
                continue

            if ref is None:
                assert remote is not None
                if old is not None and not old.get("deleted"):
                    if mode != "force_download":
                        now = int(time.time() * 1000)
                        max_rev = max(int(old["revision"]), int(remote["revision"]))
                        tomb = {"id": record_id, "revision": _next_tombstone_revision(max_rev, now),
                                "updatedAt": now, "deleted": True, "sha256": EMPTY_SHA256}
                        next_entries[record_id] = tomb
                        updated_state[record_id] = state_entry(tomb, None)
                        uploaded += 1
                    else:
                        apply_remote(remote)
                        downloaded += 1
                elif mode == "force_upload" or (mode == "now" and config.get("direction") == "UPLOAD_ONLY"):
                    next_entries[record_id] = dict(remote)
                    updated_state[record_id] = state_entry(remote, None)
                else:
                    apply_remote(remote)
                    downloaded += 1
                continue

            if remote is None or mode == "force_upload":
                if mode == "force_download":
                    continue
                local = local_record(record_id)
                assert local is not None
                upload(record_id, local)
                uploaded += 1
                continue

            if mode == "force_download":
                apply_remote(remote, ref.local_key)
                downloaded += 1
                continue

            local_changed = old is None or old.get("deleted") or int(old["revision"]) != ref.revision
            remote_changed = old is None or remote["sha256"] != old.get("sha256") or int(remote["revision"]) != int(old["revision"])
            local = local_record(record_id) if local_changed else None

            if not local_changed and not remote_changed:
                next_entries[record_id] = dict(remote)
                updated_state[record_id] = state_entry(remote, ref.local_key)
            elif not local_changed and remote_changed:
                if config.get("direction") == "UPLOAD_ONLY":
                    next_entries[record_id] = dict(remote)
                    updated_state[record_id] = state_entry(old or remote, ref.local_key)
                else:
                    apply_remote(remote, ref.local_key)
                    downloaded += 1
            elif local_changed and not remote_changed:
                assert local is not None
                upload(record_id, local)
                uploaded += 1
            elif local is not None and local.payload_sha == remote["sha256"]:
                merged = {**remote, "revision": max(local.revision, int(remote["revision"])),
                          "updatedAt": max(local.updated_at, int(remote["updatedAt"]))}
                next_entries[record_id] = merged
                updated_state[record_id] = state_entry(merged, ref.local_key)
            else:
                assert local is not None
                remote_blob = remote_read(_payload_key(content, record_id), _payload_limit(content))
                if remote_blob is None or _sha(remote_blob[0]) != remote["sha256"]:
                    raise ApiError(502, "record_payload_invalid", "Remote record payload failed verification")
                remote_record = SyncRecord(record_id, remote["revision"], remote["updatedAt"], remote_blob[0])
                if not adapter.conflict_copy:
                    remote_wins = remote_record.revision > local.revision or (
                        remote_record.revision == local.revision and remote_record.payload_sha > local.payload_sha
                    )
                    if remote_wins and config.get("direction") != "UPLOAD_ONLY":
                        canonical, _ = adapter.apply_remote(remote_record, canonical_local_key=ref.local_key)
                        next_entries[record_id] = dict(remote)
                        updated_state[record_id] = state_entry(remote, canonical)
                        downloaded += 1
                    elif remote_wins:
                        next_entries[record_id] = dict(remote)
                        updated_state[record_id] = state_entry(old or remote, ref.local_key)
                    else:
                        upload(record_id, local)
                        uploaded += 1
                else:
                    conflict_id = _deterministic_conflict_id(record_id, local.payload_sha, remote_record.payload_sha)
                    now = int(time.time() * 1000)
                    conflict_record = SyncRecord(
                        conflict_id,
                        _next_tombstone_revision(max(local.revision, remote_record.revision), now),
                        now,
                        local.payload,
                    )
                    canonical, conflict_key = adapter.apply_remote(
                        remote_record, canonical_local_key=ref.local_key, preserve_local=local,
                    )
                    remote_write(_payload_key(content, conflict_id), conflict_record.payload)
                    conflict_entry = {"id": conflict_id, "revision": conflict_record.revision,
                                      "updatedAt": conflict_record.updated_at, "deleted": False,
                                      "sha256": conflict_record.payload_sha}
                    next_entries[record_id] = dict(remote)
                    next_entries[conflict_id] = conflict_entry
                    updated_state[record_id] = state_entry(remote, canonical)
                    updated_state[conflict_id] = state_entry(conflict_entry, conflict_key)
                    conflicts += 1

        manifest_bytes = encode_record_manifest(content, next_entries)
        current_bytes = encode_record_manifest(content, remote_entries)
        if manifest_bytes != current_bytes:
            manifest_outer = remote_write(manifest_key, manifest_bytes)
            current_manifest_version = str(manifest_outer.get("blobVersion") or "") or None
        pending_states.append((content, current_manifest_version, updated_state))

    return {
        "uploaded": uploaded, "downloaded": downloaded, "conflicts": conflicts,
        "pendingStates": pending_states,
    }


def commit_states(config_id: str, scope: str,
                  pending: list[tuple[str, str | None, dict[str, dict[str, Any]]]]) -> None:
    for content, manifest_version, entries in pending:
        save_state(config_id, content, scope, manifest_version, entries)
