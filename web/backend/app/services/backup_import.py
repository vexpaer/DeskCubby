"""DeskCubby Web backup importer.

Python port of the decode half of android `data/backup/BackupJsonCodec.kt`
(format v1..v34) plus `AppBackupRepository.map` semantics that turn a validated
document into plain per-table row dicts.

Validation mirrors Kotlin exactly where it guards stored data: 64 MiB input cap,
required section presence per version, count/length/enum limits, duplicate id
and relation (foreign-key) checks. Unknown fields inside known objects are
tolerated and dropped — old backups keep importing when Android adds fields.

`decode()` never mutates any store; `map_to_rows()` only produces dicts.
"""
from __future__ import annotations

import base64
import datetime as _dt
import json
import re
import uuid
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

MAX_JSON_BYTES = 64 * 1024 * 1024
FORMAT_VERSION = 34
FORMAT_NAME = "DeskCubby"

# Count limits (BackupJsonCodec.kt constants).
MAX_THOUGHTS = 50_000
MAX_FAVORITES = 20_000
MAX_DATE_RECORDS = 50_000
MAX_CATEGORIES = 10_000
MAX_POETRY_CATEGORIES = 10_000
MAX_POEMS = 50_000
MAX_VAULT_ITEMS = 50_000
MAX_GAME_STATES = 16
MAX_GAME_STATISTICS = 64
MAX_USAGE_DEVICES = 64
MAX_READER_PROGRESS_RECORDS = 500

# Length limits.
MAX_THOUGHT_CHARS = 1_000_000
MAX_URL_CHARS = 8_192
MAX_TITLE_CHARS = 4_096
MAX_DATE_NAME_CHARS = 256
MAX_DATE_ICON_CHARS = 64
MAX_CATEGORY_NAME_CHARS = 40
MAX_POETRY_CATEGORY_NAME_CHARS = 100
MAX_POEM_CONTENT_CHARS = 100_000
MAX_POEM_SOURCE_CHARS = 4_096
MAX_VAULT_CIPHER_CHARS = 2 * 1024 * 1024
MAX_VAULT_IV_CHARS = 128
MAX_VAULT_SALT_CHARS = 2_048
MAX_VAULT_GENERATION_CHARS = 64
MAX_GAME_ID_CHARS = 64
MAX_GAME_SAVE_CHARS = 16 * 1024 * 1024
MAX_AGENT_CHAT_BYTES = 64 * 1024 * 1024
MAX_AGENT_CHAT_BASE64_CHARS = (MAX_AGENT_CHAT_BYTES + 2) // 3 * 4
MAX_AGENT_CONVERSATIONS = 10_000
MAX_AGENT_MESSAGES = 100_000
MAX_AGENT_ATTACHMENTS = 200_000
MAX_AGENT_RUNS = 100_000
MAX_AGENT_MESSAGE_CHARS = 1_000_000
MAX_AGENT_EXTRACTED_TEXT_CHARS = 256 * 1024
MAX_AGENT_ATTACHMENT_BYTES = 64 * 1024 * 1024
MAX_AGENT_TOKENS = 1_000_000_000_000
MAX_AGENT_CALLS = 1_000_000
MAX_AGENT_TIMESTAMP = 253_402_300_799_999
MAX_READER_TEXT_PAGES = 50_000
MAX_READER_TEXT_PARAGRAPHS = 250_000
MAX_READER_PDF_PAGES = 20_000
MAX_USAGE_STATISTICS_DAYS = 36_600
MAX_USAGE_APPS_PER_DAY = 4_096
MAX_USAGE_PACKAGE_NAME_CHARS = 255
MAX_USAGE_ZONE_ID_CHARS = 128
MAX_USAGE_DEVICE_NAME_CODE_POINTS = 80
MAX_USAGE_FOREGROUND_MILLIS_PER_APP_DAY = 26 * 60 * 60 * 1_000
MAX_USAGE_DEVICE_JSON_BYTES = 10 * 1024 * 1024 + 64 * 1024

VAULT_KEY_MARKER_ENTITY_ID = -(2**63)  # VaultMetadata.kt VAULT_KEY_MARKER_ENTITY_ID

INT32_MIN = -(2**31)
INT32_MAX = 2**31 - 1
INT64_MIN = -(2**63)
INT64_MAX = 2**63 - 1

SUPPORTED_GAME_IDS = {"2048", "2048_5", "2048_6", "snake", "tetris", "minesweeper", "spider"}

# GameStatisticCatalog.supportedMetricsByGameId (go stays Android-only).
_GAME_2048_METRICS = {"wins", "losses", "moveAttempts", "effectiveMoves", "merges", "highestTile"}
_SUPPORTED_METRICS_BY_GAME_ID = {
    "2048": _GAME_2048_METRICS,
    "2048_5": _GAME_2048_METRICS,
    "2048_6": _GAME_2048_METRICS,
    "snake": {"losses", "foodEaten", "maxLength"},
    "tetris": {"losses", "piecesLocked", "linesCleared", "tetrises"},
    "minesweeper": {"wins", "losses", "minesCellsRevealed", "minesSwept", "flagsPlaced"},
    "spider": {"wins", "losses", "spiderCardMoves", "spiderDeals", "spiderUndos"},
}

_READER_FINGERPRINT_CHARS = set("0123456789abcdef")
_READER_TYPES = {"TXT", "PDF"}
_USAGE_DAY_STATES = {"OPEN", "FINAL"}
_USAGE_PLATFORM_RE = re.compile(r"[a-z][a-z0-9_-]{0,31}")
_USAGE_FIXED_ZONE_RE = re.compile(
    r"(?:Z|(?:UTC|GMT|UT)(?:[+-](?:(?:0\d|1[0-7]):[0-5]\d|18:00))?|"
    r"[+-](?:(?:0\d|1[0-7]):[0-5]\d|18:00))"
)
_AGENT_SAFE_ID = re.compile(r"[A-Za-z0-9._:-]{1,200}")


class BackupDecodeError(ValueError):
    """Raised for any invalid backup document; message is safe for display."""


# ---------------------------------------------------------------------------
# Primitive readers mirroring the Kotlin JSONObject helpers.
# ---------------------------------------------------------------------------

def _fail(field: str, why: str) -> None:
    raise BackupDecodeError(f"{field} {why}")


def _req(obj: dict[str, Any], field: str) -> Any:
    if field not in obj:
        raise BackupDecodeError(f"Missing required field: {field}")
    return obj[field]


def _req_str(obj: dict[str, Any], field: str) -> str:
    value = _req(obj, field)
    if not isinstance(value, str):
        raise BackupDecodeError(f"{field} must be a string")
    return value


def _req_nullable_str(obj: dict[str, Any], field: str) -> str | None:
    value = _req(obj, field)
    if value is None:
        return None
    if not isinstance(value, str):
        raise BackupDecodeError(f"{field} must be a string or null")
    return value


def _req_bool(obj: dict[str, Any], field: str) -> bool:
    value = _req(obj, field)
    if not isinstance(value, bool):
        raise BackupDecodeError(f"{field} must be a boolean")
    return value


def _req_int(obj: dict[str, Any], field: str, *, lo: int = INT64_MIN, hi: int = INT64_MAX) -> int:
    """Kotlin requiredLong/requiredInt: integral number within 64-bit range."""
    value = _req(obj, field)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise BackupDecodeError(f"{field} must be an integer")
    if isinstance(value, float):
        if value != int(value):  # BigDecimal.longValueExact()
            raise BackupDecodeError(f"{field} must be a 64-bit integer")
        value = int(value)
    if value < INT64_MIN or value > INT64_MAX:
        raise BackupDecodeError(f"{field} must be a 64-bit integer")
    if value < lo or value > hi:
        raise BackupDecodeError(f"{field} must be a {lo}-{hi} integer")
    return value


def _req_nullable_int(obj: dict[str, Any], field: str) -> int | None:
    if field in obj and obj[field] is None:
        return None
    return _req_int(obj, field)


def _coerced_int(obj: dict[str, Any], field: str, lo: int, hi: int) -> int:
    value = _req(obj, field)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise BackupDecodeError(f"{field} must be an integer")
    if isinstance(value, float) and value != int(value):
        raise BackupDecodeError(f"{field} must be an integer")
    return max(lo, min(hi, int(value)))


def _max_len(value: str, field: str, maximum: int) -> str:
    if len(value) > maximum:
        raise BackupDecodeError(f"{field} is too long")
    return value


def _req_array(root: dict[str, Any], name: str) -> list[Any]:
    value = _req(root, name)
    if not isinstance(value, list):
        raise BackupDecodeError(f"{name} must be an array")
    return value


def _req_object(root: dict[str, Any], name: str) -> dict[str, Any]:
    value = _req(root, name)
    if not isinstance(value, dict):
        raise BackupDecodeError(f"{name} must be an object")
    return value


def _array_item(items: list[Any], index: int, name: str) -> dict[str, Any]:
    try:
        value = items[index]
    except IndexError as exc:  # pragma: no cover - iteration bounds it
        raise BackupDecodeError(f"{name}[{index}] is missing") from exc
    if not isinstance(value, dict):
        raise BackupDecodeError(f"{name}[{index}] must be an object")
    return value


def _decode_base64(value: str, field: str) -> bytes:
    try:
        return base64.b64decode(value.encode("ascii"), validate=True)
    except Exception as exc:
        raise BackupDecodeError(f"{field} is not valid Base64") from exc


def _valid_date_iso(value: str, field: str) -> str:
    if len(value) != 10:
        raise BackupDecodeError(f"{field} must use yyyy-MM-dd")
    try:
        _dt.date.fromisoformat(value)
    except ValueError as exc:
        raise BackupDecodeError(f"{field} must be a valid yyyy-MM-dd date") from exc
    return value


def _valid_usage_zone_id(value: str, field: str) -> str:
    if not value.strip() or len(value) > MAX_USAGE_ZONE_ID_CHARS:
        raise BackupDecodeError(f"{field} is invalid")
    try:
        ZoneInfo(value)
    except (ZoneInfoNotFoundError, ValueError):
        if not _USAGE_FIXED_ZONE_RE.fullmatch(value):
            raise BackupDecodeError(f"{field} is invalid")
    return value


def _valid_favorite_url(url: str, field: str) -> str:
    lowered = url.lower()
    if len(url) > MAX_URL_CHARS or not (
        lowered.startswith("https://") or lowered.startswith("http://")
    ):
        raise BackupDecodeError(f"{field} must use http or https")
    return url


# ---------------------------------------------------------------------------
# Section decoders (per-version gating happens in decode_root).
# ---------------------------------------------------------------------------

def _decode_thoughts(
    items: list[Any], *, include_category_id: bool, include_highlighted: bool
) -> list[dict[str, Any]]:
    if len(items) > MAX_THOUGHTS:
        raise BackupDecodeError("Backup contains too many thoughts")
    seen_ids: set[int] = set()
    out: list[dict[str, Any]] = []
    for index, item in enumerate(items):
        row = _array_item(items, index, "thoughts")
        row_id = _req_int(row, "id", lo=1)
        if row_id in seen_ids:
            raise BackupDecodeError(f"Duplicate thought id: {row_id}")
        seen_ids.add(row_id)
        content = _max_len(_req_str(row, "content"), f"thoughts[{index}].content", MAX_THOUGHT_CHARS)
        created_at = _req_int(row, "createdAt")
        updated_at = _req_int(row, "updatedAt")
        deleted_at = _req_nullable_int(row, "deletedAt")
        if created_at < 0:
            raise BackupDecodeError(f"thoughts[{index}].createdAt must not be negative")
        if updated_at < created_at:
            raise BackupDecodeError(f"thoughts[{index}].updatedAt must not precede createdAt")
        if deleted_at is not None and deleted_at < created_at:
            raise BackupDecodeError(f"thoughts[{index}].deletedAt must not precede createdAt")
        out.append(
            {
                "id": row_id,
                "content": content,
                "createdAt": created_at,
                "updatedAt": updated_at,
                "pinned": _req_bool(row, "pinned"),
                "deletedAt": deleted_at,
                "sortOrder": _req_int(row, "sortOrder"),
                "categoryId": _req_nullable_int(row, "categoryId") if include_category_id else None,
                "highlighted": _req_bool(row, "highlighted") if include_highlighted else False,
            }
        )
    return out


def _decode_categories(items: list[Any], section: str, max_name_chars: int) -> list[dict[str, Any]]:
    limit = MAX_CATEGORIES if section == "categories" else MAX_POETRY_CATEGORIES
    if len(items) > limit:
        raise BackupDecodeError(f"Backup contains too many {'poetry ' if section != 'categories' else ''}categories")
    seen_ids: set[int] = set()
    seen_names: set[str] = set()
    out: list[dict[str, Any]] = []
    for index, item in enumerate(items):
        row = _array_item(items, index, section)
        row_id = _req_int(row, "id", lo=1)
        if row_id in seen_ids:
            raise BackupDecodeError(f"Duplicate category id: {row_id}")
        seen_ids.add(row_id)
        name = _max_len(_req_str(row, "name"), f"{section}[{index}].name", max_name_chars)
        if not name.strip():
            raise BackupDecodeError(f"{section}[{index}].name must not be blank")
        folded = name.casefold()
        if folded in seen_names:
            raise BackupDecodeError(f"Duplicate category name (case-insensitive): {name}")
        seen_names.add(folded)
        created_at = _req_int(row, "createdAt")
        updated_at = _req_int(row, "updatedAt")
        if created_at < 0:
            raise BackupDecodeError(f"{section}[{index}].createdAt must not be negative")
        if updated_at < created_at:
            raise BackupDecodeError(f"{section}[{index}].updatedAt must not precede createdAt")
        out.append(
            {
                "id": row_id,
                "name": name,
                "colorArgb": _req_int(row, "colorArgb", lo=INT32_MIN, hi=INT32_MAX),
                "sortOrder": _req_int(row, "sortOrder"),
                "createdAt": created_at,
                "updatedAt": updated_at,
            }
        )
    return out


def _decode_favorites(items: list[Any]) -> list[dict[str, Any]]:
    if len(items) > MAX_FAVORITES:
        raise BackupDecodeError("Backup contains too many favorites")
    seen_urls: set[str] = set()
    out: list[dict[str, Any]] = []
    for index, item in enumerate(items):
        row = _array_item(items, index, "favorites")
        url = _valid_favorite_url(_req_str(row, "url"), f"favorites[{index}].url")
        if url in seen_urls:
            raise BackupDecodeError(f"Duplicate favorite url: {url}")
        seen_urls.add(url)
        if not _req_bool(row, "favorite"):
            raise BackupDecodeError(f"favorites[{index}].favorite must be true")
        last_visited_at = _req_int(row, "lastVisitedAt")
        if last_visited_at < 0:
            raise BackupDecodeError(f"favorites[{index}].lastVisitedAt must not be negative")
        out.append(
            {
                "url": url,
                "title": _max_len(_req_str(row, "title"), f"favorites[{index}].title", MAX_TITLE_CHARS),
                "lastVisitedAt": last_visited_at,
                "visitCount": _coerced_int(row, "visitCount", 1, INT32_MAX),
                "favorite": True,
            }
        )
    return out


def _decode_date_records(items: list[Any]) -> list[dict[str, Any]]:
    if len(items) > MAX_DATE_RECORDS:
        raise BackupDecodeError("Backup contains too many date records")
    seen_ids: set[int] = set()
    out: list[dict[str, Any]] = []
    for index, item in enumerate(items):
        row = _array_item(items, index, "dateRecords")
        row_id = _req_int(row, "id", lo=1)
        if row_id in seen_ids:
            raise BackupDecodeError(f"Duplicate date record id: {row_id}")
        seen_ids.add(row_id)
        name = _max_len(_req_str(row, "name"), f"dateRecords[{index}].name", MAX_DATE_NAME_CHARS)
        if not name.strip():
            raise BackupDecodeError(f"dateRecords[{index}].name must not be blank")
        icon = _max_len(_req_str(row, "icon"), f"dateRecords[{index}].icon", MAX_DATE_ICON_CHARS)
        if not icon.strip():
            raise BackupDecodeError(f"dateRecords[{index}].icon must not be blank")
        date_iso = _valid_date_iso(_req_str(row, "dateIso"), f"dateRecords[{index}].dateIso")
        created_at = _req_int(row, "createdAt")
        updated_at = _req_int(row, "updatedAt")
        if created_at < 0:
            raise BackupDecodeError(f"dateRecords[{index}].createdAt must not be negative")
        if updated_at < created_at:
            raise BackupDecodeError(f"dateRecords[{index}].updatedAt must not precede createdAt")
        out.append(
            {
                "id": row_id,
                "name": name,
                "icon": icon,
                "dateIso": date_iso,
                "createdAt": created_at,
                "updatedAt": updated_at,
            }
        )
    return out


def _decode_poems(
    items: list[Any], *, include_category_id: bool, include_sort_order: bool
) -> list[dict[str, Any]]:
    if len(items) > MAX_POEMS:
        raise BackupDecodeError("Backup contains too many poems")
    seen_ids: set[int] = set()
    out: list[dict[str, Any]] = []
    for index, item in enumerate(items):
        row = _array_item(items, index, "poems")
        row_id = _req_int(row, "id", lo=1)
        if row_id in seen_ids:
            raise BackupDecodeError(f"Duplicate poem id: {row_id}")
        seen_ids.add(row_id)
        content = _max_len(_req_str(row, "content"), f"poems[{index}].content", MAX_POEM_CONTENT_CHARS)
        if not content.strip():
            raise BackupDecodeError(f"poems[{index}].content must not be blank")
        source = _max_len(_req_str(row, "source"), f"poems[{index}].source", MAX_POEM_SOURCE_CHARS)
        created_at = _req_int(row, "createdAt")
        updated_at = _req_int(row, "updatedAt")
        if created_at < 0:
            raise BackupDecodeError(f"poems[{index}].createdAt must not be negative")
        if updated_at < created_at:
            raise BackupDecodeError(f"poems[{index}].updatedAt must not precede createdAt")
        out.append(
            {
                "id": row_id,
                "content": content,
                "source": source,
                "createdAt": created_at,
                "updatedAt": updated_at,
                "sortOrder": _req_int(row, "sortOrder") if include_sort_order else 0,
                "categoryId": _req_nullable_int(row, "categoryId") if include_category_id else None,
            }
        )
    return out


def _validate_aes_gcm_base64(cipher: str, iv: str, field: str) -> None:
    if not cipher.strip() or len(cipher) > MAX_VAULT_CIPHER_CHARS:
        raise BackupDecodeError(f"{field} ciphertext is invalid")
    if len(iv) > MAX_VAULT_IV_CHARS:
        raise BackupDecodeError(f"{field} IV is too long")
    if len(_decode_base64(cipher, f"{field} ciphertext")) < 16:
        raise BackupDecodeError(f"{field} ciphertext is too short")
    if len(_decode_base64(iv, f"{field} IV")) != 12:
        raise BackupDecodeError(f"{field} IV size is invalid")


def _validate_vault_key(key: dict[str, Any], *, generation_required: bool) -> None:
    salt_b64 = key["saltBase64"]
    if len(salt_b64) > MAX_VAULT_SALT_CHARS:
        raise BackupDecodeError("Vault salt is too long")
    salt_len = len(_decode_base64(salt_b64, "Vault salt"))
    if not 1 <= salt_len <= 1_024:
        raise BackupDecodeError("Vault salt size is invalid")
    iterations = key["iterations"]
    if not 1 <= iterations <= 10_000_000:
        raise BackupDecodeError("Vault KDF iterations are invalid")
    generation = key["generationId"]
    if generation_required and generation is None:
        raise BackupDecodeError("Pending Vault generation is missing")
    if generation is not None:
        ok = (
            isinstance(generation, str)
            and 1 <= len(generation) <= MAX_VAULT_GENERATION_CHARS
            and all(c.isalnum() and c.isascii() or c == "-" for c in generation)
        )
        if not ok:
            raise BackupDecodeError("Vault generation id is invalid")
    _validate_aes_gcm_base64(key["verifierCipher"], key["verifierIv"], "Vault verifier")


def _decode_vault_key(obj: dict[str, Any], *, pending: bool) -> dict[str, Any]:
    key = {
        "saltBase64": _max_len(_req_str(obj, "saltBase64"), "vault.saltBase64", MAX_VAULT_SALT_CHARS),
        "verifierCipher": _max_len(_req_str(obj, "verifierCipher"), "vault.verifierCipher", MAX_VAULT_CIPHER_CHARS),
        "verifierIv": _max_len(_req_str(obj, "verifierIv"), "vault.verifierIv", MAX_VAULT_IV_CHARS),
        "iterations": _req_int(obj, "iterations"),
        "generationId": _max_len(
            _req_nullable_str(obj, "generationId") or "",
            "vault.generationId",
            MAX_VAULT_GENERATION_CHARS,
        )
        if _req_nullable_str(obj, "generationId") is not None
        else None,
    }
    _validate_vault_key(key, generation_required=pending)
    return key


def _decode_vault(obj: dict[str, Any]) -> dict[str, Any]:
    # json.isNull("active") ? null : decodeVaultKey(..., pending = false)
    active = None
    if obj.get("active") is not None:
        active = _decode_vault_key(_req_object(obj, "active"), pending=False)
    pending = None
    if obj.get("pending") is not None:
        pending = _decode_vault_key(_req_object(obj, "pending"), pending=True)
    items_json = _req_array(obj, "items")
    if len(items_json) > MAX_VAULT_ITEMS:
        raise BackupDecodeError("Backup contains too many Vault rows")
    seen_ids: set[int] = set()
    items: list[dict[str, Any]] = []
    for index, item in enumerate(items_json):
        row = _array_item(items_json, index, "vault.items")
        row_id = _req_int(row, "id")
        if row_id <= 0 and row_id != VAULT_KEY_MARKER_ENTITY_ID:
            raise BackupDecodeError(f"vault.items[{index}].id is invalid")
        if row_id in seen_ids:
            raise BackupDecodeError(f"Duplicate Vault row id: {row_id}")
        seen_ids.add(row_id)
        created_at = _req_int(row, "createdAt")
        updated_at = _req_int(row, "updatedAt")
        sort_order = _req_int(row, "sortOrder")
        if created_at < 0 or updated_at < 0 or sort_order < 0:
            raise BackupDecodeError(f"vault.items[{index}] contains a negative value")
        cipher = _max_len(_req_str(row, "cipherText"), f"vault.items[{index}].cipherText", MAX_VAULT_CIPHER_CHARS)
        iv = _max_len(_req_str(row, "iv"), f"vault.items[{index}].iv", MAX_VAULT_IV_CHARS)
        _validate_aes_gcm_base64(cipher, iv, f"vault.items[{index}]")
        items.append(
            {
                "id": row_id,
                "cipherText": cipher,
                "iv": iv,
                "createdAt": created_at,
                "updatedAt": updated_at,
                "sortOrder": sort_order,
            }
        )
    vault = {"active": active, "pending": pending, "items": items}
    if active is None and (pending is not None or items):
        raise BackupDecodeError("Vault rows or pending metadata exist without active metadata")
    return vault


def _decode_game_states(items: list[Any]) -> list[dict[str, Any]]:
    if len(items) > MAX_GAME_STATES:
        raise BackupDecodeError("Backup contains too many game states")
    seen: set[str] = set()
    out: list[dict[str, Any]] = []
    for index, item in enumerate(items):
        row = _array_item(items, index, "gameStates")
        game_id = _max_len(_req_str(row, "gameId"), f"gameStates[{index}].gameId", MAX_GAME_ID_CHARS)
        if game_id not in SUPPORTED_GAME_IDS:
            raise BackupDecodeError(f"gameStates[{index}].gameId is unsupported")
        if game_id in seen:
            raise BackupDecodeError(f"Duplicate game state id: {game_id}")
        seen.add(game_id)
        high_score = _req_int(row, "highScore", lo=INT32_MIN, hi=INT32_MAX)
        updated_at = _req_int(row, "updatedAt")
        if high_score < 0 or updated_at < 0:
            raise BackupDecodeError(f"gameStates[{index}] contains a negative value")
        save_json = _req_nullable_str(row, "saveJson")
        if save_json is not None:
            save_json = _max_len(save_json, f"gameStates[{index}].saveJson", MAX_GAME_SAVE_CHARS)
            if not save_json.strip():
                raise BackupDecodeError(f"gameStates[{index}].saveJson must not be blank")
            try:
                parsed_save = json.loads(save_json)
            except ValueError as exc:
                raise BackupDecodeError(
                    f"gameStates[{index}].saveJson must contain one JSON object"
                ) from exc
            if not isinstance(parsed_save, dict):
                raise BackupDecodeError(f"gameStates[{index}].saveJson must contain one JSON object")
        out.append(
            {
                "gameId": game_id,
                "highScore": high_score,
                "saveJson": save_json,
                "updatedAt": updated_at,
            }
        )
    return out


def _decode_game_statistics(items: list[Any]) -> list[dict[str, Any]]:
    if len(items) > MAX_GAME_STATISTICS:
        raise BackupDecodeError("Backup contains too many game statistics")
    seen: set[tuple[str, str]] = set()
    out: list[dict[str, Any]] = []
    for index, item in enumerate(items):
        row = _array_item(items, index, "gameStatistics")
        game_id = _max_len(_req_str(row, "gameId"), f"gameStatistics[{index}].gameId", MAX_GAME_ID_CHARS)
        metric_key = _max_len(_req_str(row, "metricKey"), f"gameStatistics[{index}].metricKey", MAX_GAME_ID_CHARS)
        if game_id not in SUPPORTED_GAME_IDS or metric_key not in _SUPPORTED_METRICS_BY_GAME_ID.get(game_id, set()):
            raise BackupDecodeError(f"gameStatistics[{index}] contains an unsupported key")
        pair = (game_id, metric_key)
        if pair in seen:
            raise BackupDecodeError("Duplicate game statistic key")
        seen.add(pair)
        value = _req_int(row, "value")
        updated_at = _req_int(row, "updatedAt")
        if value < 0 or updated_at < 0:
            raise BackupDecodeError(f"gameStatistics[{index}] contains a negative value")
        out.append(
            {
                "gameId": game_id,
                "metricKey": metric_key,
                "value": value,
                "updatedAt": updated_at,
            }
        )
    return out


def _decode_usage_devices(items: list[Any]) -> list[dict[str, Any]]:
    """UsageDeviceJsonCodec record shape: {schemaVersion, deviceId, deviceName,
    platform, updatedAtEpochMillis, history{schemaVersion, trackingStartedOn,
    backfillCompletedThrough, days[]}}; day {date, zoneId, state,
    collectedAtEpochMillis, apps[]}; app {packageName, foregroundMillis}."""
    if len(items) > MAX_USAGE_DEVICES:
        raise BackupDecodeError("Backup contains too many usage devices")
    seen: set[str] = set()
    out: list[dict[str, Any]] = []
    for index, item in enumerate(items):
        record = _array_item(items, index, "usageDevices")
        if set(record) != {
            "schemaVersion", "deviceId", "deviceName", "platform",
            "updatedAtEpochMillis", "history",
        }:
            raise BackupDecodeError(f"usageDevices[{index}] contains missing or unknown fields")
        if _req_int(record, "schemaVersion") != 1:
            raise BackupDecodeError(f"usageDevices[{index}].schemaVersion is invalid")
        raw_device_id = _req_str(record, "deviceId").strip()
        try:
            device_id = str(uuid.UUID(raw_device_id))
        except (ValueError, AttributeError):
            raise BackupDecodeError(f"usageDevices[{index}].deviceId is invalid")
        if device_id in seen:
            raise BackupDecodeError(f"Duplicate usage device id: {device_id}")
        seen.add(device_id)
        device_name = _req_str(record, "deviceName").strip()
        if (
            not device_name or len(device_name) > MAX_USAGE_DEVICE_NAME_CODE_POINTS or
            any(ord(ch) < 32 or 127 <= ord(ch) <= 159 for ch in device_name)
        ):
            raise BackupDecodeError(f"usageDevices[{index}].deviceName is invalid")
        platform = _req_str(record, "platform").strip().lower()
        if not _USAGE_PLATFORM_RE.fullmatch(platform):
            raise BackupDecodeError(f"usageDevices[{index}].platform is invalid")
        updated_at = _req_int(record, "updatedAtEpochMillis", lo=0)

        history = _req_object(record, "history")
        history_schema = _req_int(history, "schemaVersion")
        if history_schema == 1:
            expected_history_keys = {"schemaVersion", "trackingStartedOn", "days"}
        elif history_schema in {2, 3, 4}:
            expected_history_keys = {
                "schemaVersion", "trackingStartedOn", "backfillCompletedThrough", "days",
            }
        else:
            raise BackupDecodeError(f"usageDevices[{index}].history.schemaVersion is invalid")
        if set(history) != expected_history_keys:
            raise BackupDecodeError(f"usageDevices[{index}].history contains missing or unknown fields")
        days_json = history.get("days")
        if not isinstance(days_json, list) or len(days_json) > MAX_USAGE_STATISTICS_DAYS:
            raise BackupDecodeError(f"usageDevices[{index}].history.days is invalid")
        days: list[dict[str, Any]] = []
        seen_days: set[str] = set()
        for day_index, day_value in enumerate(days_json):
            if not isinstance(day_value, dict):
                raise BackupDecodeError(f"usageDevices[{index}].days[{day_index}] must be an object")
            if set(day_value) != {"date", "zoneId", "state", "collectedAtEpochMillis", "apps"}:
                raise BackupDecodeError(
                    f"usageDevices[{index}].days[{day_index}] contains missing or unknown fields"
                )
            date = _valid_date_iso(_req_str(day_value, "date"), f"usageDevices[{index}].days[{day_index}].date")
            if date in seen_days:
                raise BackupDecodeError(f"usageDevices[{index}] contains a duplicate day: {date}")
            seen_days.add(date)
            zone_id = _valid_usage_zone_id(
                _req_str(day_value, "zoneId"),
                f"usageDevices[{index}].days[{day_index}].zoneId",
            )
            state = _req_str(day_value, "state")
            if state not in _USAGE_DAY_STATES:
                raise BackupDecodeError(f"usageDevices[{index}].days[{day_index}].state is invalid")
            apps_json = day_value.get("apps")
            if not isinstance(apps_json, list) or len(apps_json) > MAX_USAGE_APPS_PER_DAY:
                raise BackupDecodeError(f"usageDevices[{index}].days[{day_index}].apps is invalid")
            apps: list[dict[str, Any]] = []
            seen_packages: set[str] = set()
            for app_index, app_value in enumerate(apps_json):
                if not isinstance(app_value, dict):
                    raise BackupDecodeError(
                        f"usageDevices[{index}].days[{day_index}].apps[{app_index}] must be an object"
                    )
                if set(app_value) != {"packageName", "foregroundMillis"}:
                    raise BackupDecodeError(
                        f"usageDevices[{index}].days[{day_index}].apps[{app_index}] "
                        "contains missing or unknown fields"
                    )
                package = _req_str(app_value, "packageName")
                if (
                    not package.strip() or len(package) > MAX_USAGE_PACKAGE_NAME_CHARS or
                    any(ch.isspace() or ord(ch) < 32 or 127 <= ord(ch) <= 159 for ch in package)
                ):
                    raise BackupDecodeError(
                        f"usageDevices[{index}].days[{day_index}].apps[{app_index}].packageName is invalid"
                    )
                if package in seen_packages:
                    raise BackupDecodeError(
                        f"usageDevices[{index}].days[{day_index}] contains a duplicate package"
                    )
                seen_packages.add(package)
                foreground = _req_int(
                    app_value,
                    "foregroundMillis",
                    lo=0,
                    hi=MAX_USAGE_FOREGROUND_MILLIS_PER_APP_DAY,
                )
                apps.append({"packageName": package, "foregroundMillis": foreground})
            collected_at = _req_int(day_value, "collectedAtEpochMillis", lo=0)
            days.append(
                {
                    "date": date,
                    "zoneId": zone_id,
                    "state": state,
                    "collectedAtEpochMillis": collected_at,
                    "apps": apps,
                }
            )
        tracking_started_on = _req_nullable_str(history, "trackingStartedOn")
        if tracking_started_on is not None:
            tracking_started_on = _valid_date_iso(
                tracking_started_on, f"usageDevices[{index}].trackingStartedOn",
            )
        if days and tracking_started_on is None:
            raise BackupDecodeError(f"usageDevices[{index}].trackingStartedOn is required")
        if tracking_started_on is not None and any(day["date"] < tracking_started_on for day in days):
            raise BackupDecodeError(f"usageDevices[{index}] contains a day before trackingStartedOn")
        backfill = _req_nullable_str(history, "backfillCompletedThrough") if history_schema >= 2 else None
        if backfill is not None:
            backfill = _valid_date_iso(
                backfill, f"usageDevices[{index}].backfillCompletedThrough",
            )
        # v2/v3 carried a watermark field, but Android deliberately discards it
        # and forces a bounded rebuild. Only current schema v4 preserves it.
        if history_schema != 4:
            backfill = None
        newest_day = max((day["collectedAtEpochMillis"] for day in days), default=0)
        if updated_at < newest_day:
            raise BackupDecodeError(f"usageDevices[{index}].updatedAtEpochMillis precedes history")
        out.append(
            {
                "schemaVersion": 1,
                "deviceId": device_id,
                "deviceName": device_name,
                "platform": platform,
                "updatedAtEpochMillis": updated_at,
                "history": {
                    "schemaVersion": 4,
                    "trackingStartedOn": tracking_started_on,
                    "backfillCompletedThrough": backfill,
                    "days": days,
                },
            }
        )
    return out


def _decode_reader_progress(items: list[Any]) -> list[dict[str, Any]]:
    if len(items) > MAX_READER_PROGRESS_RECORDS:
        raise BackupDecodeError("Backup contains too many reader progress records")
    seen: set[tuple[str, str]] = set()
    out: list[dict[str, Any]] = []
    for index, item in enumerate(items):
        record = _array_item(items, index, "readerProgress")
        fingerprint = _req_str(record, "fingerprint")
        if len(fingerprint) != 64 or not set(fingerprint) <= _READER_FINGERPRINT_CHARS:
            raise BackupDecodeError(f"readerProgress[{index}].fingerprint is invalid")
        book_type = _req_str(record, "type")
        if book_type not in _READER_TYPES:
            raise BackupDecodeError(f"readerProgress[{index}].type is invalid")
        key = (fingerprint, book_type)
        if key in seen:
            raise BackupDecodeError("Duplicate reader progress key")
        seen.add(key)
        updated_at = _req_int(record, "updatedAt")
        if updated_at < 0:
            raise BackupDecodeError(f"readerProgress[{index}].updatedAt must not be negative")
        text_page_index = _req_int(record, "textPageIndex", lo=INT32_MIN, hi=INT32_MAX)
        text_paragraph_index = _req_int(record, "textParagraphIndex", lo=INT32_MIN, hi=INT32_MAX)
        pdf_page_index = _req_int(record, "pdfPageIndex", lo=INT32_MIN, hi=INT32_MAX)
        total_pages = _req_int(record, "totalPages", lo=INT32_MIN, hi=INT32_MAX)
        if not -1 <= text_page_index < MAX_READER_TEXT_PAGES:
            raise BackupDecodeError(f"readerProgress[{index}].textPageIndex is out of range")
        if not 0 <= text_paragraph_index < MAX_READER_TEXT_PARAGRAPHS:
            raise BackupDecodeError(f"readerProgress[{index}].textParagraphIndex is out of range")
        if not 0 <= pdf_page_index < MAX_READER_PDF_PAGES:
            raise BackupDecodeError(f"readerProgress[{index}].pdfPageIndex is out of range")
        page_cap = MAX_READER_TEXT_PAGES if book_type == "TXT" else MAX_READER_PDF_PAGES
        if not 0 <= total_pages <= page_cap:
            raise BackupDecodeError(f"readerProgress[{index}].totalPages is out of range for {book_type}")
        out.append(
            {
                "fingerprint": fingerprint,
                "type": book_type,
                "textPageIndex": text_page_index,
                "textParagraphIndex": text_paragraph_index,
                "pdfPageIndex": pdf_page_index,
                "totalPages": total_pages,
                "updatedAt": updated_at,
            }
        )
    return out


def _decode_agent_chats(raw: str) -> bytes:
    if len(raw) > MAX_AGENT_CHAT_BASE64_CHARS:
        raise BackupDecodeError("Agent chats backup is too large")
    if not raw.strip():
        return b""
    payload = _decode_base64(raw, "agentChats")
    if not payload or len(payload) > MAX_AGENT_CHAT_BYTES:
        raise BackupDecodeError("Agent chats backup size is invalid")
    try:
        parsed = json.loads(payload.decode("utf-8"))
    except (ValueError, UnicodeDecodeError, RecursionError) as exc:
        raise BackupDecodeError("agentChats payload is not valid JSON") from exc
    if (
        not isinstance(parsed, dict)
        or parsed.get("format") != "deskcubby-agent-chats"
        or parsed.get("version") != 1
    ):
        raise BackupDecodeError("agentChats payload format is unsupported")
    limits = {
        "conversations": MAX_AGENT_CONVERSATIONS,
        "messages": MAX_AGENT_MESSAGES,
        "attachments": MAX_AGENT_ATTACHMENTS,
        "runs": MAX_AGENT_RUNS,
    }
    for section, limit in limits.items():
        value = parsed.get(section)
        if not isinstance(value, list):
            raise BackupDecodeError(f"agentChats.{section} must be an array")
        if len(value) > limit:
            raise BackupDecodeError(f"agentChats.{section} contains too many records")
    return payload


def _decode_agent_chat_payload(payload: bytes) -> dict[str, list[dict[str, Any]]]:
    """Strict AgentChatSyncCodec shape → decoded table-ready dictionaries."""
    if not payload:
        return {"conversations": [], "messages": [], "attachments": [], "runs": []}
    parsed = json.loads(payload.decode("utf-8"))
    conversations_raw = parsed["conversations"]
    messages_raw = parsed["messages"]
    attachments_raw = parsed["attachments"]
    runs_raw = parsed["runs"]

    def safe_id(item: dict[str, Any], key: str, location: str) -> str:
        value = _req_str(item, key)
        if not _AGENT_SAFE_ID.fullmatch(value):
            raise BackupDecodeError(f"{location}.{key} is invalid")
        return value

    def safe_string(
        item: dict[str, Any], key: str, maximum: int, location: str, *, allow_empty: bool = False,
    ) -> str:
        value = _req_str(item, key)
        if len(value) > maximum or (not allow_empty and not value.strip()):
            raise BackupDecodeError(f"{location}.{key} is invalid")
        return value

    def optional_string(item: dict[str, Any], key: str, maximum: int, location: str) -> str | None:
        value = _req_nullable_str(item, key)
        if value is not None and (not value.strip() or len(value) > maximum):
            raise BackupDecodeError(f"{location}.{key} is invalid")
        return value

    def safe_time(item: dict[str, Any], key: str, location: str) -> int:
        try:
            return _req_int(item, key, lo=0, hi=MAX_AGENT_TIMESTAMP)
        except BackupDecodeError as exc:
            raise BackupDecodeError(f"{location}.{key} is invalid") from exc

    def optional_tokens(item: dict[str, Any], key: str, location: str) -> int | None:
        value = _req_nullable_int(item, key)
        if value is not None and not 0 <= value <= MAX_AGENT_TOKENS:
            raise BackupDecodeError(f"{location}.{key} is invalid")
        return value

    conversations: list[dict[str, Any]] = []
    conv_sync_ids: set[str] = set()
    for index, item in enumerate(conversations_raw):
        location = f"agentChats.conversations[{index}]"
        if not isinstance(item, dict):
            raise BackupDecodeError(f"{location} must be an object")
        sync_id = safe_id(item, "syncId", location)
        if sync_id in conv_sync_ids:
            raise BackupDecodeError(f"{location}.syncId is duplicated")
        conv_sync_ids.add(sync_id)
        title = safe_string(item, "title", 500, location)
        model_config_id = safe_string(item, "modelConfigId", 200, location, allow_empty=True)
        created_at = safe_time(item, "createdAt", location)
        updated_at = safe_time(item, "updatedAt", location)
        deleted_at = _req_nullable_int(item, "deletedAt")
        if (
            updated_at < created_at
            or (deleted_at is not None and not created_at <= deleted_at <= MAX_AGENT_TIMESTAMP)
        ):
            raise BackupDecodeError(f"{location} contains invalid timestamps")
        conversations.append(
            {
                "syncId": sync_id,
                "title": title,
                "modelConfigId": model_config_id,
                "createdAt": created_at,
                "updatedAt": updated_at,
                "deletedAt": deleted_at,
            }
        )

    messages: list[dict[str, Any]] = []
    msg_sync_ids: set[str] = set()
    for index, item in enumerate(messages_raw):
        location = f"agentChats.messages[{index}]"
        if not isinstance(item, dict):
            raise BackupDecodeError(f"{location} must be an object")
        sync_id = safe_id(item, "syncId", location)
        conversation_sync_id = safe_id(item, "conversationSyncId", location)
        if sync_id in msg_sync_ids:
            raise BackupDecodeError(f"{location}.syncId is duplicated")
        if conversation_sync_id not in conv_sync_ids:
            raise BackupDecodeError(f"{location} references a missing conversation")
        msg_sync_ids.add(sync_id)
        role = safe_string(item, "role", 20, location)
        if role not in {"user", "assistant", "system"}:
            raise BackupDecodeError(f"{location}.role is invalid")
        messages.append(
            {
                "syncId": sync_id,
                "conversationSyncId": conversation_sync_id,
                "role": role,
                "content": safe_string(item, "content", MAX_AGENT_MESSAGE_CHARS, location, allow_empty=True),
                "reasoning": safe_string(item, "reasoning", MAX_AGENT_MESSAGE_CHARS, location, allow_empty=True),
                "imageMimeType": optional_string(item, "imageMimeType", 200, location),
                "createdAt": safe_time(item, "createdAt", location),
            }
        )

    attachments: list[dict[str, Any]] = []
    att_sync_ids: set[str] = set()
    for index, item in enumerate(attachments_raw):
        location = f"agentChats.attachments[{index}]"
        if not isinstance(item, dict):
            raise BackupDecodeError(f"{location} must be an object")
        sync_id = safe_id(item, "syncId", location)
        message_sync_id = safe_id(item, "messageSyncId", location)
        if sync_id in att_sync_ids:
            raise BackupDecodeError(f"{location}.syncId is duplicated")
        if message_sync_id not in msg_sync_ids:
            raise BackupDecodeError(f"{location} references a missing message")
        att_sync_ids.add(sync_id)
        kind = safe_string(item, "kind", 20, location).upper()
        if kind not in {"IMAGE", "DOCUMENT"}:
            raise BackupDecodeError(f"{location}.kind is invalid")
        attachments.append(
            {
                "syncId": sync_id,
                "messageSyncId": message_sync_id,
                "mimeType": safe_string(item, "mimeType", 200, location),
                "displayName": safe_string(item, "displayName", 500, location),
                "sizeBytes": _req_int(item, "sizeBytes", lo=0, hi=MAX_AGENT_ATTACHMENT_BYTES),
                "kind": kind,
                "extractedText": optional_string(
                    item, "extractedText", MAX_AGENT_EXTRACTED_TEXT_CHARS, location,
                ),
            }
        )

    runs: list[dict[str, Any]] = []
    run_ids: set[str] = set()
    for index, item in enumerate(runs_raw):
        location = f"agentChats.runs[{index}]"
        if not isinstance(item, dict):
            raise BackupDecodeError(f"{location} must be an object")
        run_id = safe_id(item, "runId", location)
        if run_id in run_ids:
            raise BackupDecodeError(f"{location}.runId is duplicated")
        run_ids.add(run_id)
        conversation_sync_id = optional_string(item, "conversationSyncId", 200, location)
        if conversation_sync_id is not None:
            if not _AGENT_SAFE_ID.fullmatch(conversation_sync_id):
                raise BackupDecodeError(f"{location}.conversationSyncId is invalid")
            if conversation_sync_id not in conv_sync_ids:
                raise BackupDecodeError(f"{location} references a missing conversation")
        permission_mode = safe_string(item, "permissionMode", 32, location)
        if permission_mode not in {"REQUIRE_APPROVAL", "FULL_AUTO"}:
            raise BackupDecodeError(f"{location}.permissionMode is invalid")
        enabled_sources_json = safe_string(item, "enabledSourcesJson", 2_048, location)
        try:
            if not isinstance(json.loads(enabled_sources_json), list):
                raise ValueError
        except (ValueError, RecursionError) as exc:
            raise BackupDecodeError(f"{location}.enabledSourcesJson is invalid") from exc
        legacy_status = safe_string(item, "status", 32, location).upper()
        status = {
            "COMPLETED": "SUCCEEDED",
            "ERROR": "FAILED",
            "CANCELLED": "CANCELED",
        }.get(legacy_status, legacy_status)
        if status not in {"SUCCEEDED", "FAILED", "CANCELED"}:
            raise BackupDecodeError(f"{location}.status is invalid")
        model_call_count = _req_int(item, "modelCallCount", lo=0, hi=MAX_AGENT_CALLS)
        usage_call_count = _req_int(item, "usageReportedCallCount", lo=0, hi=MAX_AGENT_CALLS)
        input_tokens = optional_tokens(item, "inputTokens", location)
        cached_tokens = optional_tokens(item, "cachedInputTokens", location)
        cache_rate_input = optional_tokens(item, "cacheRateInputTokens", location)
        if cache_rate_input is None and cached_tokens is not None:
            cache_rate_input = input_tokens
        if cache_rate_input is not None and cached_tokens is not None and cached_tokens > cache_rate_input:
            raise BackupDecodeError(f"{location} contains invalid cache-token counts")
        runs.append({
            "runId": run_id,
            "conversationSyncId": conversation_sync_id,
            "conversationTitle": safe_string(item, "conversationTitle", 500, location),
            "userRequestSummary": safe_string(item, "userRequestSummary", 2_000, location, allow_empty=True),
            "modelConfigId": safe_string(item, "modelConfigId", 200, location, allow_empty=True),
            "permissionMode": permission_mode,
            "enabledSourcesJson": enabled_sources_json,
            "status": status,
            "modelCallCount": model_call_count,
            "usageReportedCallCount": usage_call_count,
            "inputTokens": input_tokens,
            "outputTokens": optional_tokens(item, "outputTokens", location),
            "totalTokens": optional_tokens(item, "totalTokens", location),
            "cachedInputTokens": cached_tokens,
            "cacheRateInputTokens": cache_rate_input,
            "reasoningTokens": optional_tokens(item, "reasoningTokens", location),
            "startedAt": safe_time(item, "startedAt", location),
            "completedAt": safe_time(item, "completedAt", location),
        })
    return {
        "conversations": conversations,
        "messages": messages,
        "attachments": attachments,
        "runs": runs,
    }


# ---------------------------------------------------------------------------
# Root document
# ---------------------------------------------------------------------------

def decode(payload_bytes: bytes) -> dict[str, Any]:
    """Validate a raw backup file (bytes) without touching any local data.

    Returns the parsed document: {version, exportedAt, settings, thoughts,
    categories, favorites, dateRecords, poetryCategories, poems, vault,
    gameStates, gameStatistics, usageDevices, readerProgress, agentChats(bytes),
    agentChatData}. Raises BackupDecodeError with a safe message on any problem.
    """
    if len(payload_bytes) > MAX_JSON_BYTES:
        raise BackupDecodeError("备份文件不能超过 64 MiB。 / Backup JSON exceeds the 64 MiB limit.")
    try:
        text = payload_bytes.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise BackupDecodeError("备份文件不是有效的 UTF-8 文本。 / Not valid UTF-8.") from exc
    text = text.removeprefix("\ufeff")
    try:
        root = json.loads(text)
    except ValueError as exc:
        raise BackupDecodeError("Invalid backup JSON") from exc
    if not isinstance(root, dict):
        raise BackupDecodeError("Backup root must be a JSON object")

    fmt = _req_str(root, "format")
    if fmt != FORMAT_NAME:
        raise BackupDecodeError(f"Unsupported backup format: {fmt}")
    version = _req_int(root, "version")
    if not 1 <= version <= FORMAT_VERSION:
        raise BackupDecodeError(f"Unsupported backup version: {version}")
    exported_at = _req_int(root, "exportedAt")
    if exported_at < 0:
        raise BackupDecodeError("exportedAt must not be negative")

    settings = _req_object(root, "settings")  # tolerant: kept verbatim (unknown fields allowed)

    categories = (
        _decode_categories(_req_array(root, "categories"), "categories", MAX_CATEGORY_NAME_CHARS)
        if version >= 3
        else []
    )
    thoughts = _decode_thoughts(
        _req_array(root, "thoughts"),
        include_category_id=version >= 3,
        include_highlighted=version >= 14,
    )
    # Relation check: thoughts.categoryId must reference an existing category.
    category_ids = {c["id"] for c in categories}
    for index, thought in enumerate(thoughts):
        if thought["categoryId"] is not None and thought["categoryId"] not in category_ids:
            raise BackupDecodeError(
                f"thoughts[{index}].categoryId references a missing category: {thought['categoryId']}"
            )

    favorites = _decode_favorites(_req_array(root, "favorites"))
    date_records = _decode_date_records(_req_array(root, "dateRecords")) if version >= 2 else []
    poetry_categories = (
        _decode_categories(
            _req_array(root, "poetryCategories"), "poetryCategories", MAX_POETRY_CATEGORY_NAME_CHARS
        )
        if version >= 19
        else []
    )
    poems = (
        _decode_poems(
            _req_array(root, "poems"),
            include_category_id=version >= 19,
            include_sort_order=version >= 21,
        )
        if version >= 4
        else []
    )
    poetry_category_ids = {c["id"] for c in poetry_categories}
    for index, poem in enumerate(poems):
        if poem["categoryId"] is not None and poem["categoryId"] not in poetry_category_ids:
            raise BackupDecodeError(
                f"poems[{index}].categoryId references a missing poetry category: {poem['categoryId']}"
            )

    empty_vault = {"active": None, "pending": None, "items": []}
    vault = _decode_vault(_req_object(root, "vault")) if version >= 20 else empty_vault
    game_states = _decode_game_states(_req_array(root, "gameStates")) if version >= 20 else []
    game_statistics = _decode_game_statistics(_req_array(root, "gameStatistics")) if version >= 24 else []
    usage_devices = _decode_usage_devices(_req_array(root, "usageDevices")) if version >= 20 else []
    reader_progress = _decode_reader_progress(_req_array(root, "readerProgress")) if version >= 28 else []
    agent_chats_payload = _decode_agent_chats(_req_str(root, "agentChats")) if version >= 34 else b""
    agent_chat_data = _decode_agent_chat_payload(agent_chats_payload)

    return {
        "version": version,
        "exportedAt": exported_at,
        "settings": settings,
        "thoughts": thoughts,
        "categories": categories,
        "favorites": favorites,
        "dateRecords": date_records,
        "poetryCategories": poetry_categories,
        "poems": poems,
        "vault": vault,
        "gameStates": game_states,
        "gameStatistics": game_statistics,
        "usageDevices": usage_devices,
        "readerProgress": reader_progress,
        "agentChats": agent_chats_payload,
        "agentChatData": agent_chat_data,
    }


def counts_per_section(parsed: dict[str, Any]) -> dict[str, int]:
    """BackupSummary-shaped counters (camelCase, mirrors AppBackupRepository)."""
    return {
        "thoughtCount": len(parsed["thoughts"]),
        "categoryCount": len(parsed["categories"]),
        "favoriteCount": len(parsed["favorites"]),
        "dateRecordCount": len(parsed["dateRecords"]),
        "poetryCategoryCount": len(parsed["poetryCategories"]),
        "poemCount": len(parsed["poems"]),
        "vaultItemCount": sum(1 for item in parsed["vault"]["items"] if item["id"] > 0),
        "gameStateCount": len(parsed["gameStates"]),
        "gameStatisticCount": len(parsed["gameStatistics"]),
        "usageDeviceCount": len(parsed["usageDevices"]),
        "usageDayCount": sum(len(d["history"]["days"]) for d in parsed["usageDevices"]),
        "readerProgressCount": len(parsed["readerProgress"]),
        "agentConversationCount": len(parsed["agentChatData"]["conversations"]),
        "exportedAt": parsed["exportedAt"],
    }


def map_to_rows(parsed: dict[str, Any]) -> dict[str, list[dict[str, Any]]]:
    """Per-table row dicts ready for INSERT into the mirrored SQLite schema.

    Keys: flash_thoughts, thought_categories, browser_records, date_records,
    poetry_categories, saved_poems, vault_items, game_states, game_statistics,
    ai_conversations, ai_messages, ai_attachments, agent_runs, plus `cloud_sync_configs`
    (secret-free metadata for the settings merge; not a table).
    """
    return {
        "flash_thoughts": [
            {
                "id": t["id"],
                "content": t["content"],
                "createdAt": t["createdAt"],
                "updatedAt": t["updatedAt"],
                "pinned": 1 if t["pinned"] else 0,
                "deletedAt": t["deletedAt"],
                "sortOrder": t["sortOrder"],
                "categoryId": t["categoryId"],
                "highlighted": 1 if t["highlighted"] else 0,
            }
            for t in parsed["thoughts"]
        ],
        "thought_categories": [
            {
                "id": c["id"],
                "name": c["name"],
                "colorArgb": c["colorArgb"],
                "sortOrder": c["sortOrder"],
                "createdAt": c["createdAt"],
                "updatedAt": c["updatedAt"],
            }
            for c in parsed["categories"]
        ],
        "browser_records": [
            {
                "url": f["url"],
                "title": f["title"],
                "lastVisitedAt": f["lastVisitedAt"],
                "visitCount": f["visitCount"],
                "favorite": 1,
            }
            for f in parsed["favorites"]
        ],
        "date_records": [
            {
                "id": r["id"],
                "name": r["name"],
                "icon": r["icon"],
                "dateIso": r["dateIso"],
                "createdAt": r["createdAt"],
                "updatedAt": r["updatedAt"],
            }
            for r in parsed["dateRecords"]
        ],
        "poetry_categories": [
            {
                "id": c["id"],
                "name": c["name"],
                "colorArgb": c["colorArgb"],
                "sortOrder": c["sortOrder"],
                "createdAt": c["createdAt"],
                "updatedAt": c["updatedAt"],
            }
            for c in parsed["poetryCategories"]
        ],
        "saved_poems": [
            {
                "id": p["id"],
                "content": p["content"],
                "source": p["source"],
                "createdAt": p["createdAt"],
                "updatedAt": p["updatedAt"],
                "sortOrder": p["sortOrder"],
                "categoryId": p["categoryId"],
            }
            for p in parsed["poems"]
        ],
        "vault_items": [
            {
                "id": item["id"],
                "cipherText": item["cipherText"],
                "iv": item["iv"],
                "createdAt": item["createdAt"],
                "updatedAt": item["updatedAt"],
                "sortOrder": item["sortOrder"],
            }
            for item in parsed["vault"]["items"]
        ],
        "game_states": [
            {
                "gameId": s["gameId"],
                "highScore": s["highScore"],
                "saveJson": s["saveJson"],
                "updatedAt": s["updatedAt"],
            }
            for s in parsed["gameStates"]
        ],
        "game_statistics": [
            {
                "gameId": s["gameId"],
                "metricKey": s["metricKey"],
                "value": s["value"],
                "updatedAt": s["updatedAt"],
            }
            for s in parsed["gameStatistics"]
        ],
        "ai_conversations": parsed["agentChatData"]["conversations"],
        "ai_messages": parsed["agentChatData"]["messages"],
        "ai_attachments": parsed["agentChatData"]["attachments"],
        "agent_runs": parsed["agentChatData"]["runs"],
        # Metadata only (secrets were already stripped by the encoder); consumed by
        # the commit path to refresh settings.cloudSyncConfigs, never sent to clients.
        "cloud_sync_configs": _cloud_sync_config_metadata(parsed),
        "usage_devices": parsed["usageDevices"],
        "reader_progress": parsed["readerProgress"],
    }


_CLOUD_CONFIG_METADATA_FIELDS = (
    "id",
    "name",
    "enabled",
    "serviceType",
    "endpointUrl",
    "remotePath",
    "userAgent",
    "webDavUsername",
    "s3Bucket",
    "s3Region",
    "s3PathStyle",
    "allowInsecureHttp",
    "selectedContents",
    "direction",
)

_SECRET_CONFIG_FIELDS = ("webDavPassword", "s3AccessKey", "s3SecretKey", "s3SessionToken")


def _cloud_sync_config_metadata(parsed: dict[str, Any]) -> list[dict[str, Any]]:
    configs = parsed["settings"].get("cloudSyncConfigs")
    if not isinstance(configs, list):
        return []
    out: list[dict[str, Any]] = []
    for cfg in configs:
        if not isinstance(cfg, dict) or not isinstance(cfg.get("id"), str) or not cfg["id"]:
            continue
        cleaned = {field: cfg.get(field) for field in _CLOUD_CONFIG_METADATA_FIELDS}
        for secret in _SECRET_CONFIG_FIELDS:  # defense in depth: never re-enter secrets
            cleaned.pop(secret, None)
        cleaned["enabled"] = bool(cleaned.get("enabled")) and bool(cleaned.get("selectedContents"))
        out.append(cleaned)
    return out[:20]
