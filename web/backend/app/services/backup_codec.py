"""DeskCubby Web backup JSON codec.

Python port of android `data/backup/BackupJsonCodec.kt` (format v34).

Export produces the exact v34 document shape: root keys `format` ("DeskCubby"),
`version`, `exportedAt` (epoch millis), then the section names used by the Kotlin
encoder: settings, thoughts, categories, favorites, dateRecords, poetryCategories,
poems, vault, gameStates, gameStatistics, usageDevices, readerProgress, agentChats.

Per project rules the export NEVER contains AI API keys, SAF/tree URIs or cloud
sync credentials (Android `AppSettings.sanitizedForManualBackup()` semantics).
"""
from __future__ import annotations

import base64
import copy
import datetime as _dt
import json
import time
import uuid
from pathlib import Path
from typing import Any

from ..core.config import DATA_DIR
from .settings_store import load_settings

FORMAT_VERSION = 34
FORMAT_NAME = "DeskCubby"

MAX_AGENT_CHAT_BYTES = 64 * 1024 * 1024
MAX_AGENT_CHAT_BASE64_CHARS = (MAX_AGENT_CHAT_BYTES + 2) // 3 * 4
_VAULT_KEY_MARKER_ID = -(2**63)  # VaultMetadata.kt VAULT_KEY_MARKER_ENTITY_ID

# Settings keys that are device-local and must never appear in a backup.
_URI_SETTING_KEYS = (
    "backgroundImageUri",
    "backupTreeUri",
    "diaryTreeUri",
    "mediaTreeUri",
    "notesTreeUri",
    "poetryFontUri",
)

# Keys Android's BackupJsonCodec.encodeSettings never writes; they stay server-local
# and are dropped from exports entirely instead of being emitted as nulls.
_SERVER_LOCAL_ONLY_SETTING_KEYS = (
    "backupTreeUri",
    "lastThoughtPageKey",
    "navigationIntroAcknowledged",
    "orientationPreference",
    "structuredAutoRecordSleepWake",
    "tutorialAcknowledgedPages",
)

# Exact metadata field set written by BackupJsonCodec.encodeCloudSyncConfigs;
# secret fields (webDavPassword / s3AccessKey / s3SecretKey / s3SessionToken)
# are never serialized.
_CLOUD_SYNC_CONFIG_FIELDS = (
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

# CloudSyncContent enum declaration order (AppModels CloudSyncModels.kt).
_CLOUD_SYNC_CONTENT_ORDER = (
    "DIARIES",
    "NOTES",
    "MEDIA",
    "THOUGHTS",
    "THOUGHT_CATEGORIES",
    "DATE_RECORDS",
    "POEMS",
    "POETRY_CATEGORIES",
    "FAVORITES",
    "RSS_SUBSCRIPTIONS",
    "GAME_STATES",
    "GAME_STATISTICS",
    "USAGE_STATISTICS",
    "READING_PROGRESS",
    "READER_PREFERENCES",
    "AGENT_CHATS",
    "VAULT",
    "GLOBAL_SETTINGS",
)

# AppModels HOME_GAME_SHORTCUT_IDS order; "go" is Android-only and stays local.
_HOME_GAME_SHORTCUT_ORDER = (
    "2048",
    "2048_5",
    "2048_6",
    "snake",
    "tetris",
    "minesweeper",
    "spider",
    "go",
)
_ANDROID_ONLY_GAME_ID = "go"


def _sanitize_settings(settings: dict[str, Any]) -> dict[str, Any]:
    """Project AppSettings onto the portable v34 shape (no URIs, no secrets,
    device-local grants and cloud enablement forced off)."""
    out = copy.deepcopy(settings)
    for key in _URI_SETTING_KEYS:
        if key in out:
            out[key] = None
    # Android's encodeSettings never writes these keys: keep them server-local only.
    for key in _SERVER_LOCAL_ONLY_SETTING_KEYS:
        out.pop(key, None)
    out["cloudSyncEnabled"] = False
    out["usageTrackingEnabled"] = False
    out["stepTrackingEnabled"] = False
    configs = []
    for cfg in out.get("cloudSyncConfigs") or []:
        if not isinstance(cfg, dict):
            continue
        cleaned: dict[str, Any] = {}
        for field in _CLOUD_SYNC_CONFIG_FIELDS:
            if field in cfg:
                cleaned[field] = copy.deepcopy(cfg[field])
        cleaned["enabled"] = False
        contents = cleaned.get("selectedContents")
        if isinstance(contents, list):
            wanted = {c for c in contents if c != "JSON_BACKUP"}
            cleaned["selectedContents"] = [
                c for c in _CLOUD_SYNC_CONTENT_ORDER if c in wanted
            ]
        configs.append(cleaned)
    out["cloudSyncConfigs"] = configs
    for cfg in out.get("aiConfigs") or []:
        if isinstance(cfg, dict):
            cfg["apiKey"] = ""
    for cfg in out.get("desktopWidgetConfigs") or []:
        if isinstance(cfg, dict) and "backgroundImageUri" in cfg:
            cfg["backgroundImageUri"] = None
    shortcuts = out.get("homeGameShortcuts")
    if isinstance(shortcuts, list):
        wanted = {s for s in shortcuts if s != _ANDROID_ONLY_GAME_ID}
        out["homeGameShortcuts"] = [
            g for g in _HOME_GAME_SHORTCUT_ORDER if g in wanted
        ]
    return out


def _rows(con: Any, sql: str, params: tuple = ()) -> list[dict[str, Any]]:
    return [dict(row) for row in con.execute(sql, params).fetchall()]


def _as_bool(value: Any) -> bool:
    return bool(value)


def build_thoughts(con: Any) -> list[dict[str, Any]]:
    """flash_thoughts -> Kotlin FlashThoughtEntity field names."""
    return [
        {
            "id": row["id"],
            "content": row["content"],
            "createdAt": row["createdAt"],
            "updatedAt": row["updatedAt"],
            "pinned": _as_bool(row["pinned"]),
            "deletedAt": row["deletedAt"],
            "sortOrder": row["sortOrder"],
            "categoryId": row["categoryId"],
            "highlighted": _as_bool(row["highlighted"]),
        }
        for row in _rows(con, "SELECT * FROM flash_thoughts ORDER BY id")
    ]


def _build_category_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "id": row["id"],
            "name": row["name"],
            "colorArgb": row["colorArgb"],
            "sortOrder": row["sortOrder"],
            "createdAt": row["createdAt"],
            "updatedAt": row["updatedAt"],
        }
        for row in rows
    ]


def build_categories(con: Any) -> list[dict[str, Any]]:
    """thought_categories -> ThoughtCategoryEntity field names."""
    return _build_category_rows(
        _rows(con, "SELECT * FROM thought_categories ORDER BY id")
    )


def build_favorites(con: Any) -> list[dict[str, Any]]:
    """browser_records (favorite=1 only) -> BrowserRecordEntity field names."""
    return [
        {
            "url": row["url"],
            "title": row["title"],
            "lastVisitedAt": row["lastVisitedAt"],
            "visitCount": row["visitCount"],
            "favorite": True,
        }
        for row in _rows(
            con,
            "SELECT * FROM browser_records WHERE favorite = 1 ORDER BY url",
        )
    ]


def build_date_records(con: Any) -> list[dict[str, Any]]:
    """date_records -> DateRecordEntity field names."""
    return [
        {
            "id": row["id"],
            "name": row["name"],
            "icon": row["icon"],
            "dateIso": row["dateIso"],
            "createdAt": row["createdAt"],
            "updatedAt": row["updatedAt"],
        }
        for row in _rows(con, "SELECT * FROM date_records ORDER BY id")
    ]


def build_poetry_categories(con: Any) -> list[dict[str, Any]]:
    """poetry_categories -> PoetryCategoryEntity field names."""
    return _build_category_rows(
        _rows(con, "SELECT * FROM poetry_categories ORDER BY id")
    )


def build_poems(con: Any) -> list[dict[str, Any]]:
    """saved_poems -> SavedPoemEntity field names."""
    return [
        {
            "id": row["id"],
            "content": row["content"],
            "source": row["source"],
            "createdAt": row["createdAt"],
            "updatedAt": row["updatedAt"],
            "sortOrder": row["sortOrder"],
            "categoryId": row["categoryId"],
        }
        for row in _rows(con, "SELECT * FROM saved_poems ORDER BY id")
    ]


def build_game_states(con: Any) -> list[dict[str, Any]]:
    """game_states -> GameStateEntity field names, sorted by gameId, minus the
    Android-only `go` save (projectForV28Export semantics kept for v34)."""
    return [
        {
            "gameId": row["gameId"],
            "highScore": row["highScore"],
            "saveJson": row["saveJson"],
            "updatedAt": row["updatedAt"],
        }
        for row in _rows(con, "SELECT * FROM game_states ORDER BY gameId")
        if row["gameId"] != _ANDROID_ONLY_GAME_ID
    ]


def build_game_statistics(con: Any) -> list[dict[str, Any]]:
    """game_statistics -> GameStatisticEntity field names, sorted by
    (gameId, metricKey), minus the Android-only `go` metrics."""
    return [
        {
            "gameId": row["gameId"],
            "metricKey": row["metricKey"],
            "value": row["value"],
            "updatedAt": row["updatedAt"],
        }
        for row in _rows(
            con,
            "SELECT * FROM game_statistics ORDER BY gameId, metricKey",
        )
        if row["gameId"] != _ANDROID_ONLY_GAME_ID
    ]


def build_vault(con: Any, data_dir: Path | None = None) -> dict[str, Any]:
    """vault_items ciphertext + data/private/vault-meta.json key metadata into
    the Kotlin VaultEncryptedBackup shape {active, pending, items}.

    Passwords / derived keys never exist server-side; only the salt and the
    encrypted verifier travel, exactly like Android's v20+ vault section.
    """
    root = {"active": None, "pending": None, "items": []}
    base = Path(data_dir) if data_dir else DATA_DIR
    meta_path = base / "private" / "vault-meta.json"
    meta: dict[str, Any] = {}
    if meta_path.is_file():
        try:
            loaded = json.loads(meta_path.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                meta = loaded
        except (OSError, ValueError):
            meta = {}

    def key_meta(prefix: str = "") -> dict[str, Any] | None:
        """Read one key descriptor from private/vault-meta.json.

        Field names follow VaultMetadata.kt as persisted by vault_service:
        active keys are flat (`saltBase64`, `verifierCipher`, `verifierIv`,
        `kdfIterations`, `activeGenerationId`) and change-pending keys carry the
        `pending*` prefix. Legacy aliases are tolerated for older files.
        """

        def pick(*names: str) -> Any:
            for name in names:
                value = meta.get(name)
                if isinstance(value, str) and value.strip():
                    return value
            return None

        salt = pick(f"{prefix}SaltBase64", f"{prefix}saltBase64")
        verifier = pick(
            f"{prefix}VerifierHashBase64", f"{prefix}verifierHashBase64",
            f"{prefix}VerifierCipher", f"{prefix}verifierCipher",
        )
        if not salt or not verifier:
            return None
        iv = pick(f"{prefix}VerifierIv", f"{prefix}verifierIv", f"{prefix}iv")
        iterations = None
        for name in (
            f"{prefix}KdfIterations", f"{prefix}kdfIterations", f"{prefix}iterations",
        ):
            if meta.get(name) is not None:
                iterations = meta[name]
                break
        generation = pick(f"{prefix}GenerationId", f"{prefix}generationId")
        if not generation and not prefix:
            # The stable descriptor stores its generation under activeGenerationId.
            generation = pick("activeGenerationId", "generationId")
        try:
            iterations_value = int(iterations) if iterations is not None else 0
        except (TypeError, ValueError):
            iterations_value = 0
        return {
            "saltBase64": salt,
            "verifierCipher": verifier,
            "verifierIv": iv,
            "iterations": iterations_value,
            "generationId": generation,
        }

    active = key_meta()
    if active is not None:
        root["active"] = active
        root["pending"] = key_meta("pending")
    root["items"] = [
        {
            "id": row["id"],
            "cipherText": row["cipherText"],
            "iv": row["iv"],
            "createdAt": row["createdAt"],
            "updatedAt": row["updatedAt"],
            "sortOrder": row["sortOrder"],
        }
        for row in _rows(
            con,
            f"SELECT * FROM vault_items WHERE id > 0 AND id != {_VAULT_KEY_MARKER_ID} "
            "ORDER BY id",
        )
    ]
    return root


def build_usage_devices(con: Any) -> list[dict[str, Any]]:
    """Usage tables -> canonical Android UsageDeviceJsonCodec records.

    Exact Kotlin keys (StatisticsJsonCodecs.kt): record {schemaVersion, deviceId,
    deviceName, platform, updatedAtEpochMillis, history}; history {schemaVersion,
    trackingStartedOn, backfillCompletedThrough, days}; day {date, zoneId, state,
    collectedAtEpochMillis, apps}; app {packageName, foregroundMillis}.
    """
    devices = _rows(con, "SELECT * FROM usage_devices ORDER BY deviceId")
    records: list[dict[str, Any]] = []
    today_iso = _dt.date.today().isoformat()
    for device in devices:
        metadata_rows = _rows(
            con,
            "SELECT dayIso,zoneId,state,collectedAt FROM usage_days "
            "WHERE deviceId=? ORDER BY dayIso",
            (device["deviceId"],),
        )
        metadata = {row["dayIso"]: row for row in metadata_rows}
        event_rows = _rows(
            con,
            "SELECT dayIso,MAX(lastSeen) AS collectedAt FROM usage_events_daily "
            "WHERE deviceId=? GROUP BY dayIso ORDER BY dayIso",
            (device["deviceId"],),
        )
        events = {row["dayIso"]: row for row in event_rows}
        day_isos = sorted(set(metadata) | set(events))
        days = []
        max_collected = 0
        for day_iso in day_isos:
            day = metadata.get(day_iso)
            fallback = events.get(day_iso)
            apps = [
                {"packageName": row["packageName"], "foregroundMillis": row["totalTimeMs"]}
                for row in _rows(
                    con,
                    "SELECT packageName, totalTimeMs FROM usage_events_daily "
                    "WHERE deviceId = ? AND dayIso = ? ORDER BY packageName",
                    (device["deviceId"], day_iso),
                )
            ]
            collected = int(
                (day["collectedAt"] if day is not None else fallback["collectedAt"]) or 0
            )
            max_collected = max(max_collected, collected)
            days.append(
                {
                    "date": day_iso,
                    "zoneId": day["zoneId"] if day is not None else "UTC",
                    "state": (
                        day["state"] if day is not None
                        else ("OPEN" if day_iso == today_iso else "FINAL")
                    ),
                    "collectedAtEpochMillis": collected,
                    "apps": apps,
                }
            )
        updated_at = max(int(device["updatedAt"] or 0), max_collected)
        tracking_started = device.get("trackingStartedOn")
        if days and (not tracking_started or days[0]["date"] < tracking_started):
            tracking_started = days[0]["date"]
        records.append(
            {
                "schemaVersion": 1,
                "deviceId": device["deviceId"],
                "deviceName": device["deviceName"],
                "platform": device.get("platform") or "web",
                "updatedAtEpochMillis": updated_at,
                "history": {
                    "schemaVersion": 4,
                    "trackingStartedOn": tracking_started,
                    "backfillCompletedThrough": device.get("backfillCompletedThrough"),
                    "days": days,
                },
            }
        )
    return records


def build_reader_progress(data_dir: Path | None = None) -> list[dict[str, Any]]:
    """Embed the URI-free reading/v1/progress.json ledger records as-is."""
    base = Path(data_dir) if data_dir else DATA_DIR
    path = base / "private" / "reading" / "v1" / "progress.json"
    if not path.is_file():
        return []
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    if isinstance(payload, list):
        return [r for r in payload if isinstance(r, dict)]
    if isinstance(payload, dict):
        records = payload.get("records")
        if isinstance(records, list):
            return [r for r in records if isinstance(r, dict)]
        for key in ("books", "progress"):
            nested = payload.get(key)
            if isinstance(nested, dict):
                return [v for v in nested.values() if isinstance(v, dict)]
    return []


def _backfill_sync_ids(con: Any, table: str) -> None:
    rows = con.execute(
        f"SELECT id FROM {table} WHERE syncId IS NULL"
    ).fetchall()
    for row in rows:
        con.execute(
            f"UPDATE {table} SET syncId = ? WHERE id = ? AND syncId IS NULL",
            (str(uuid.uuid4()), row["id"]),
        )


def build_agent_chats_b64(con: Any) -> str:
    """AI/Agent tables -> AgentChatSyncCodec JSON (AgentChatSyncRepository.kt)
    base64-encoded into the single `agentChats` string. Like Android, the payload
    is always encoded (empty collections stay valid JSON), never omitted."""
    with con:
        _backfill_sync_ids(con, "ai_conversations")
        _backfill_sync_ids(con, "ai_messages")
        _backfill_sync_ids(con, "ai_attachments")

    conversations = [
        {
            "syncId": row["syncId"],
            "title": row["title"],
            "modelConfigId": row["modelConfigId"],
            "createdAt": row["createdAt"],
            "updatedAt": row["updatedAt"],
            "deletedAt": row["deletedAt"],
        }
        for row in _rows(
            con, "SELECT * FROM ai_conversations WHERE syncId IS NOT NULL ORDER BY syncId"
        )
    ]
    conv_by_id = {row["id"]: row["syncId"] for row in _rows(
        con, "SELECT id, syncId FROM ai_conversations WHERE syncId IS NOT NULL"
    )}
    messages = []
    for row in _rows(
        con, "SELECT * FROM ai_messages WHERE syncId IS NOT NULL ORDER BY syncId"
    ):
        conversation_sync_id = conv_by_id.get(row["conversationId"])
        if conversation_sync_id is None:
            continue
        messages.append(
            {
                "syncId": row["syncId"],
                "conversationSyncId": conversation_sync_id,
                "role": row["role"],
                "content": row["content"],
                "reasoning": row["reasoning"],
                "imageMimeType": row["imageMimeType"],
                "createdAt": row["createdAt"],
            }
        )
    msg_by_id = {row["id"]: row["syncId"] for row in _rows(
        con, "SELECT id, syncId FROM ai_messages WHERE syncId IS NOT NULL"
    )}
    attachments = []
    for row in _rows(
        con, "SELECT * FROM ai_attachments WHERE syncId IS NOT NULL ORDER BY syncId"
    ):
        message_sync_id = msg_by_id.get(row["messageId"])
        if message_sync_id is None:
            continue
        attachments.append(
            {
                "syncId": row["syncId"],
                "messageSyncId": message_sync_id,
                "mimeType": row["mimeType"],
                "displayName": row["displayName"],
                "sizeBytes": row["sizeBytes"],
                # Early Web builds used lowercase attachment kinds; Android's
                # AgentChatSyncCodec wire enum is uppercase.
                "kind": str(row["kind"] or "").upper(),
                "extractedText": row["extractedText"],
            }
        )
    runs = []
    for row in _rows(
        con,
        "SELECT * FROM agent_runs WHERE completedAt IS NOT NULL ORDER BY runId",
    ):
        status = {
            "COMPLETED": "SUCCEEDED",
            "ERROR": "FAILED",
            "CANCELLED": "CANCELED",
        }.get(str(row["status"] or "").upper(), str(row["status"] or "").upper())
        # Old Web builds persisted different names. Canonicalize them at the
        # Android-compatible export boundary without rewriting user history.
        if status not in {"SUCCEEDED", "FAILED", "CANCELED"}:
            status = "FAILED"
        runs.append(
            {
                "runId": row["runId"],
                "conversationSyncId": conv_by_id.get(row["conversationId"]),
                "conversationTitle": row["conversationTitle"],
                "userRequestSummary": row["userRequestSummary"],
                "modelConfigId": row["modelConfigId"],
                "permissionMode": row["permissionMode"],
                "enabledSourcesJson": row["enabledSourcesJson"],
                "status": status,
                "modelCallCount": row["modelCallCount"],
                "usageReportedCallCount": row["usageReportedCallCount"],
                "inputTokens": row["inputTokens"],
                "outputTokens": row["outputTokens"],
                "totalTokens": row["totalTokens"],
                "cachedInputTokens": row["cachedInputTokens"],
                "cacheRateInputTokens": row["cacheRateInputTokens"],
                "reasoningTokens": row["reasoningTokens"],
                "startedAt": row["startedAt"],
                "completedAt": row["completedAt"],
            }
        )
    payload = json.dumps(
        {
            "format": "deskcubby-agent-chats",
            "version": 1,
            "conversations": conversations,
            "messages": messages,
            "attachments": attachments,
            "runs": runs,
        },
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    if len(payload) > MAX_AGENT_CHAT_BYTES:
        raise ValueError("Agent chats backup exceeds the 64 MiB limit")
    encoded = base64.b64encode(payload).decode("ascii")
    if len(encoded) > MAX_AGENT_CHAT_BASE64_CHARS:  # pragma: no cover - size-guard
        raise ValueError("Agent chats backup exceeds the base64 size limit")
    return encoded


def export_v34(
    con: Any,
    settings: dict[str, Any],
    data_dir: Path | None = None,
) -> dict[str, Any]:
    """Build the complete v34 backup document from live SQLite rows, AppSettings
    and the private workspace files that feed vault/reader sections."""
    return {
        "format": FORMAT_NAME,
        "version": FORMAT_VERSION,
        "exportedAt": int(time.time() * 1000),
        "settings": _sanitize_settings(settings),
        "thoughts": build_thoughts(con),
        "categories": build_categories(con),
        "favorites": build_favorites(con),
        "dateRecords": build_date_records(con),
        "poetryCategories": build_poetry_categories(con),
        "poems": build_poems(con),
        "vault": build_vault(con, data_dir),
        "gameStates": build_game_states(con),
        "gameStatistics": build_game_statistics(con),
        "usageDevices": build_usage_devices(con),
        "readerProgress": build_reader_progress(data_dir),
        "agentChats": build_agent_chats_b64(con),
    }


def export_backup(con: Any, data_dir: Path | None = None) -> dict[str, Any]:
    """Full v34 export entry point: current settings + all collection builders."""
    return export_v34(con, load_settings(con), data_dir)
