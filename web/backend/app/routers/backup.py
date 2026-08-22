"""Backup API: v34 export download, preview/commit import, auto backups.

Mirrors android `AppBackupRepository` semantics adapted to the server layout:
- Export is ALWAYS sanitized (`AppSettings.sanitizedForManualBackup`): no AI
  API keys, no tree URIs, no cloud credentials — `includeSecrets=1` is refused.
- Import decodes v1..v34 (`backup_import.decode`) into a preview first; the
  confirmed commit replaces core tables in ONE SQLite transaction so a failure
  rolls back completely ("单事务替换+回滚保护"). Reader progress merges LWW,
  usage devices merge per device, agent chats replace their tables, and the
  Vault ciphertext/meta are restored like `VaultRepository.restoreEncryptedBackup`.
- Auto backups write timestamped v34 files under data/backups/<dirName>/ and
  prune to `keepCount` (retention behavior of AppBackupRepository's auto path).
"""
from __future__ import annotations

import json
import time
import uuid
from typing import Any

from fastapi import APIRouter, Depends, Request, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from ..core.config import BACKUP_FORMAT_VERSION
from ..core.db import get_db, write_lock
from ..core.errors import ApiError
from ..services import backup_import, backup_service
from ..services.backup_codec import export_backup
from ..services.backup_import import BackupDecodeError, counts_per_section, decode, map_to_rows

router = APIRouter(prefix="/api/backup", tags=["backup"])

_PENDING_TTL_MS = 15 * 60 * 1000
_PENDING_LIMIT = 3
_pending: dict[str, dict[str, Any]] = {}


# ---------------------------------------------------------------------------
# Export
# ---------------------------------------------------------------------------

@router.get("/export")
def export(includeSecrets: bool = False, con=Depends(get_db)):
    """Download the current data as a v34 DeskCubby JSON document."""
    if includeSecrets:
        # Secrets never travel: AI keys, tree URIs and cloud credentials are stripped.
        raise ApiError(400, "secrets_unavailable",
                       "Backups never contain secrets; omit includeSecrets")
    document = export_backup(con)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    payload = json.dumps(document, ensure_ascii=False, indent=2)
    return JSONResponse(
        content=json.loads(payload),
        headers={
            "Content-Disposition": f'attachment; filename="deskcubby-backup-v{document["version"]}-{stamp}.json"',
            "X-Backup-Version": str(document["version"]),
        },
    )


# ---------------------------------------------------------------------------
# Import (preview -> confirm)
# ---------------------------------------------------------------------------

class CommitBody(BaseModel):
    token: str


def _purge_expired_pending() -> None:
    now_ms = int(time.time() * 1000)
    stale = [token for token, item in _pending.items() if item["expiresAt"] <= now_ms]
    for token in stale:
        _pending.pop(token, None)


@router.post("/import")
async def preview_import(file: UploadFile):
    """Validate an uploaded backup file and return per-section counts."""
    data = await file.read()
    try:
        parsed = decode(data)
    except BackupDecodeError as exc:
        raise ApiError(400, "invalid_backup", str(exc)) from exc
    summary = counts_per_section(parsed)

    _purge_expired_pending()
    if len(_pending) >= _PENDING_LIMIT:
        _pending.pop(next(iter(_pending)), None)
    token = uuid.uuid4().hex
    _pending[token] = {
        "parsed": parsed,
        "rows": map_to_rows(parsed),
        "summary": summary,
        "expiresAt": int(time.time() * 1000) + _PENDING_TTL_MS,
    }
    return {"token": token, "version": parsed["version"], **summary}


@router.post("/import/commit")
def commit_import(body: CommitBody, con=Depends(get_db)):
    """Apply a previously validated preview: replace core data transactionally."""
    pending = _pending.pop(body.token, None)
    if pending is None:
        raise ApiError(404, "preview_expired", "Import preview expired; upload the file again")

    parsed = pending["parsed"]
    rows = pending["rows"]
    version = parsed["version"]
    try:
        applied = _apply_import(con, parsed, rows, version)
    except Exception as exc:  # noqa: BLE001 - converted to a safe message below
        raise ApiError(500, "import_failed", "导入失败：原有内容已恢复。 / Import failed; previous content was restored.") from exc
    return {"ok": True, "restored": pending["summary"], **applied}


def _apply_import(con, parsed: dict[str, Any], rows: dict[str, list[dict[str, Any]]], version: int) -> dict[str, Any]:
    """Single-transaction core replace + post-commit private store restores."""
    agent_chats_present = bool(version >= 34 and parsed.get("agentChats"))
    try:
        with write_lock(), con:
            _replace_core_tables(con, rows, version, agent_chats_present)
    except Exception as exc:  # noqa: BLE001 - the transaction rolled back above
        raise ApiError(500, "import_failed", "导入失败：原有内容已恢复。 / Import failed; previous content was restored.") from exc

    # Private stores are restored after the DB transaction committed (Android
    # treats the Room transaction as the final commit point).
    _restore_vault(con, parsed.get("vault") or {})
    _merge_usage_devices(con, rows.get("usage_devices") or [])
    _merge_reader_progress(rows.get("reader_progress") or [])
    _restore_settings(con, parsed)
    return {"settingsRestored": True}


def _replace_core_tables(
    con,
    rows: dict[str, list[dict[str, Any]]],
    version: int,
    agent_chats_present: bool = False,
) -> None:
    # Thoughts before categories (FK), inserts in reverse order.
    con.execute("DELETE FROM flash_thoughts")
    con.execute("DELETE FROM thought_categories")
    for category in rows["thought_categories"]:
        con.execute(
            "INSERT INTO thought_categories(id, name, colorArgb, sortOrder, createdAt, updatedAt) "
            "VALUES(:id, :name, :colorArgb, :sortOrder, :createdAt, :updatedAt)",
            category,
        )
    for thought in rows["flash_thoughts"]:
        con.execute(
            "INSERT INTO flash_thoughts(id, content, createdAt, updatedAt, pinned, deletedAt, "
            "sortOrder, categoryId, highlighted) VALUES(:id, :content, :createdAt, :updatedAt, "
            ":pinned, :deletedAt, :sortOrder, :categoryId, :highlighted)",
            thought,
        )

    # Favorites: clear flags, upsert rows; browsing history survives (Android
    # BrowserRecordDao.replaceFavoritesForBackup).
    con.execute("UPDATE browser_records SET favorite = 0 WHERE favorite = 1")
    for favorite in rows["browser_records"]:
        con.execute(
            "INSERT INTO browser_records(url, title, lastVisitedAt, visitCount, favorite) "
            "VALUES(:url, :title, :lastVisitedAt, :visitCount, :favorite) "
            "ON CONFLICT(url) DO UPDATE SET title=:title, lastVisitedAt=:lastVisitedAt, "
            "visitCount=:visitCount, favorite=:favorite",
            favorite,
        )

    con.execute("DELETE FROM date_records")
    for record in rows["date_records"]:
        con.execute(
            "INSERT INTO date_records(id, name, icon, dateIso, createdAt, updatedAt) "
            "VALUES(:id, :name, :icon, :dateIso, :createdAt, :updatedAt)",
            record,
        )

    con.execute("DELETE FROM saved_poems")
    con.execute("DELETE FROM poetry_categories")
    for category in rows["poetry_categories"]:
        con.execute(
            "INSERT INTO poetry_categories(id, name, colorArgb, sortOrder, createdAt, updatedAt) "
            "VALUES(:id, :name, :colorArgb, :sortOrder, :createdAt, :updatedAt)",
            category,
        )
    for poem in rows["saved_poems"]:
        con.execute(
            "INSERT INTO saved_poems(id, content, source, createdAt, updatedAt, sortOrder, categoryId) "
            "VALUES(:id, :content, :source, :createdAt, :updatedAt, :sortOrder, :categoryId)",
            poem,
        )

    # Game saves/statistics merge with the live rows read inside this transaction,
    # mirroring mergeGameStateBackups / mergeGameStatisticBackups.
    if version >= 20:
        live_states = {
            r["gameId"]: dict(r)
            for r in con.execute("SELECT * FROM game_states").fetchall()
        }
        for state in rows["game_states"]:
            live = live_states.get(state["gameId"])
            if live is None or state["updatedAt"] >= live["updatedAt"]:
                live_states[state["gameId"]] = state
        con.execute("DELETE FROM game_states")
        for state in live_states.values():
            con.execute(
                "INSERT INTO game_states(gameId, highScore, saveJson, updatedAt) "
                "VALUES(:gameId, :highScore, :saveJson, :updatedAt)",
                state,
            )
    if version >= 24:
        live_stats = {
            (r["gameId"], r["metricKey"]): dict(r)
            for r in con.execute("SELECT * FROM game_statistics").fetchall()
        }
        for stat in rows["game_statistics"]:
            key = (stat["gameId"], stat["metricKey"])
            live = live_stats.get(key)
            if live is None or stat["updatedAt"] >= live["updatedAt"]:
                live_stats[key] = stat
        con.execute("DELETE FROM game_statistics")
        for stat in live_stats.values():
            con.execute(
                "INSERT INTO game_statistics(gameId, metricKey, value, updatedAt) "
                "VALUES(:gameId, :metricKey, :value, :updatedAt)",
                stat,
            )

    # Agent chats (v34): replaceFromBackupSnapshot semantics — only when the
    # snapshot carries data; otherwise local chats stay untouched.
    if agent_chats_present:
        con.execute("DELETE FROM ai_attachments")
        con.execute("DELETE FROM ai_messages")
        con.execute("DELETE FROM ai_conversations")
        for conversation in rows["ai_conversations"]:
            con.execute(
                "INSERT OR IGNORE INTO ai_conversations(syncId, title, modelConfigId, createdAt, updatedAt, deletedAt) "
                "VALUES(:syncId, :title, :modelConfigId, :createdAt, :updatedAt, :deletedAt)",
                conversation,
            )
        conv_ids = {
            r["syncId"]: r["id"]
            for r in con.execute("SELECT id, syncId FROM ai_conversations").fetchall()
        }
        msg_ids: dict[str, int] = {}
        for message in rows["ai_messages"]:
            cursor = con.execute(
                "INSERT INTO ai_messages(conversationId, role, content, reasoning, imageMimeType, "
                "imagePermissionOwned, createdAt, syncId) VALUES(?,?,?,?,?,?,?,?)",
                (
                    conv_ids.get(message["conversationSyncId"]),
                    message["role"],
                    message["content"],
                    message["reasoning"],
                    message["imageMimeType"],
                    0,
                    message["createdAt"],
                    message["syncId"],
                ),
            )
            msg_ids[message["syncId"]] = cursor.lastrowid
        for attachment in rows["ai_attachments"]:
            con.execute(
                "INSERT INTO ai_attachments(messageId, uri, mimeType, displayName, sizeBytes, kind, "
                "extractedText, permissionOwned, syncId) VALUES(?,?,?,?,?,?,?,?,?)",
                (
                    msg_ids.get(attachment["messageSyncId"]),
                    "",
                    attachment["mimeType"],
                    attachment["displayName"],
                    attachment["sizeBytes"],
                    attachment["kind"],
                    attachment["extractedText"],
                    0,
                    attachment["syncId"],
                ),
            )


def _restore_vault(con, vault: dict[str, Any]) -> None:
    """Restore Vault ciphertext + key metadata (never passwords/derived keys)."""
    meta_document: dict[str, Any] = {}
    active = vault.get("active")
    if isinstance(active, dict):
        meta_document.update({
            "saltBase64": active.get("saltBase64"),
            "verifierCipher": active.get("verifierCipher"),
            "verifierIv": active.get("verifierIv"),
            "kdfIterations": active.get("iterations"),
        })
        if active.get("generationId"):
            meta_document["activeGenerationId"] = active["generationId"]
        pending = vault.get("pending")
        if isinstance(pending, dict):
            meta_document.update({
                "pendingSaltBase64": pending.get("saltBase64"),
                "pendingVerifierCipher": pending.get("verifierCipher"),
                "pendingVerifierIv": pending.get("verifierIv"),
                "pendingKdfIterations": pending.get("iterations"),
                "pendingGenerationId": pending.get("generationId"),
            })

    with write_lock(), con:
        # Replace the full ciphertext set exactly like restoreEncryptedBackup:
        # items plus the hidden generation marker row travel together.
        con.execute("DELETE FROM vault_items")
        for item in vault.get("items") or []:
            con.execute(
                "INSERT OR REPLACE INTO vault_items(id, cipherText, iv, createdAt, updatedAt, sortOrder) "
                "VALUES(:id, :cipherText, :iv, :createdAt, :updatedAt, :sortOrder)",
                item,
            )

    if meta_document:
        from ..core.config import PRIVATE_DIR
        from ..core.fs import safe_write_text

        target = PRIVATE_DIR / "vault-meta.json"
        try:
            existing = json.loads(target.read_text(encoding="utf-8")) if target.is_file() else {}
        except (OSError, ValueError):
            existing = {}
        existing.update(meta_document)
        safe_write_text(target, json.dumps(existing, ensure_ascii=False))


def _merge_usage_devices(con, records: list[dict[str, Any]]) -> None:
    """UsageDeviceRepository.mergeBackup: per-device LWW upsert of days/apps."""
    if not records:
        return
    with write_lock(), con:
        for record in records:
            device_id = record["deviceId"]
            row = con.execute(
                "SELECT updatedAt FROM usage_devices WHERE deviceId = ?", (device_id,)
            ).fetchone()
            if row is not None and int(record.get("updatedAtEpochMillis") or 0) < int(row["updatedAt"] or 0):
                continue
            con.execute(
                "INSERT INTO usage_devices(deviceId, deviceName, isLocal, updatedAt) VALUES(?,?,0,?) "
                "ON CONFLICT(deviceId) DO UPDATE SET deviceName=excluded.deviceName, "
                "updatedAt=excluded.updatedAt",
                (device_id, record.get("deviceName") or device_id, record.get("updatedAtEpochMillis") or 0),
            )
            con.execute("DELETE FROM usage_events_daily WHERE deviceId = ?", (device_id,))
            for day in (record.get("history") or {}).get("days") or []:
                for app in day.get("apps") or []:
                    collected = int(day.get("collectedAtEpochMillis") or 0)
                    con.execute(
                        "INSERT OR REPLACE INTO usage_events_daily(deviceId, dayIso, packageName, appName, "
                        "firstSeen, lastSeen, totalTimeMs) VALUES(?,?,?,?,?,?,?)",
                        (
                            device_id,
                            day["date"],
                            app["packageName"],
                            app.get("appName") or app["packageName"],
                            collected,
                            collected,
                            int(app.get("foregroundMillis") or 0),
                        ),
                    )


def _merge_reader_progress(records: list[dict[str, Any]]) -> None:
    if not records:
        return
    from ..services.reader_service import merged_document, write_ledger_records

    write_ledger_records(merged_document(records))


def _restore_settings(con, parsed: dict[str, Any]) -> None:
    """Portable settings only: URIs/secrets/device grants stay sanitized."""
    from ..services.backup_codec import _sanitize_settings
    from ..services.settings_store import update_settings

    portable = _sanitize_settings(parsed.get("settings") or {})
    update_settings(con, portable)


# ---------------------------------------------------------------------------
# Auto backups
# ---------------------------------------------------------------------------

class AutoBackupBody(BaseModel):
    enabled: bool | None = None
    dirUri: str | None = None   # Android SAF tree URI; the web keeps a folder name
    dirName: str | None = None
    keepCount: int | None = None


@router.get("/auto")
def get_auto(con=Depends(get_db)):
    return backup_service.load_auto_backup_config(con)


@router.put("/auto")
def put_auto(body: AutoBackupBody, con=Depends(get_db)):
    current = backup_service.load_auto_backup_config(con)
    patch: dict[str, Any] = {}
    if body.enabled is not None:
        patch["enabled"] = bool(body.enabled)
    name = body.dirName or body.dirUri
    if name:
        patch["dirName"] = name.split("/")[-1][:120]
    if body.keepCount is not None:
        patch["keepCount"] = int(body.keepCount)
    return backup_service.save_auto_backup_config(con, {**current, **patch})


@router.post("/auto/run")
def run_auto(request: Request, con=Depends(get_db)):
    _ = request
    result = backup_service.run_auto_backup(con)
    return {**result, "formatVersion": BACKUP_FORMAT_VERSION}
