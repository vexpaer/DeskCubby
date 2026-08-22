"""Cloud sync API (WebDAV / S3-compatible).

Mirrors android `AppCloudSyncService` + `CloudSyncSecretStore` semantics:
- Configs are stored as non-secret metadata in settings.cloudSyncConfigs;
  passwords/keys live server-side in data/private/cloud-secrets.json and are
  never returned by any endpoint (redacted to empty strings plus a
  `hasCredentials` flag, like the Android editor).
- POST /sync runs one engine pass synchronously and returns 409 while another
  sync is running; POST /undo restores the latest undo snapshot once.
"""
from __future__ import annotations

import re
import uuid
from typing import Any

from fastapi import APIRouter, Depends, Request
from pydantic import BaseModel

from ..core.db import get_db
from ..core.errors import ApiError
from ..services import cloudsync_engine as engine
from ..services.settings_store import load_settings, update_settings

router = APIRouter(prefix="/api/cloudsync", tags=["cloudsync"])

SECRET_FIELDS = ("webDavPassword", "s3AccessKey", "s3SecretKey", "s3SessionToken")

SERVICE_TYPES = ("WEBDAV", "S3_COMPATIBLE")
DIRECTIONS = ("UPLOAD_ONLY", "TWO_WAY")
CONTENT_VALUES = (
    "DIARIES", "NOTES", "MEDIA",
    "THOUGHTS", "THOUGHT_CATEGORIES", "DATE_RECORDS", "POEMS", "POETRY_CATEGORIES",
    "FAVORITES", "RSS_SUBSCRIPTIONS", "GAME_STATES", "GAME_STATISTICS",
    "USAGE_STATISTICS", "READING_PROGRESS", "READER_PREFERENCES", "AGENT_CHATS",
    "VAULT", "GLOBAL_SETTINGS",
)
DEFAULT_CONTENTS = [
    "DIARIES", "NOTES", "MEDIA", "THOUGHTS", "THOUGHT_CATEGORIES", "DATE_RECORDS",
    "POEMS", "POETRY_CATEGORIES", "FAVORITES", "READING_PROGRESS",
    "READER_PREFERENCES", "AGENT_CHATS",
]
MAX_CONFIGS = 20                      # SettingsRepository.MAX_CLOUD_SYNC_CONFIGS
_S3_REGION_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")


class ConfigBody(BaseModel):
    id: str | None = None
    name: str = ""
    enabled: bool = True
    serviceType: str = "WEBDAV"
    endpointUrl: str = ""
    remotePath: str = "DeskCubby"
    userAgent: str = "DeskCubby-Sync/1"
    webDavUsername: str = ""
    webDavPassword: str = ""
    s3Bucket: str = ""
    s3Region: str = "us-east-1"
    s3AccessKey: str = ""
    s3SecretKey: str = ""
    s3SessionToken: str = ""
    s3PathStyle: bool = True
    allowInsecureHttp: bool = False
    selectedContents: list[str] = []
    direction: str = "TWO_WAY"


class SyncBody(BaseModel):
    configId: str
    mode: str = "now"


# ---------------------------------------------------------------------------
# Validation / redaction
# ---------------------------------------------------------------------------

def _validate_config(body: ConfigBody) -> dict[str, Any]:
    """Validate kind + base URL + bounds exactly where Android does
    (CloudSyncValidation.kt)."""
    name = body.name.strip().replace("\r", " ").replace("\n", " ")[:200]
    if not name:
        raise ApiError(400, "invalid_name", "Configuration name is required")
    if body.serviceType not in SERVICE_TYPES:
        raise ApiError(400, "invalid_service", "serviceType must be WEBDAV or S3_COMPATIBLE")
    if body.direction not in DIRECTIONS:
        raise ApiError(400, "invalid_direction", "direction must be UPLOAD_ONLY or TWO_WAY")

    endpoint = body.endpointUrl.strip()
    from urllib.parse import urlsplit

    parts = urlsplit(endpoint)
    scheme = (parts.scheme or "").lower()
    if scheme == "http" and not body.allowInsecureHttp:
        raise ApiError(400, "insecure_endpoint",
                       "HTTP sync is off by default; explicitly allow it for trusted LAN services")
    if scheme != "https" and scheme != "http":
        raise ApiError(400, "invalid_endpoint", "Cloud service URL must use HTTPS")
    if not parts.hostname or parts.username or parts.query or parts.fragment:
        raise ApiError(400, "invalid_endpoint",
                       "Cloud service URL must be absolute and free of account info or query")

    remote_path = "/".join(
        segment for segment in body.remotePath.replace("\\", "/").split("/") if segment.strip("/")
    )
    if any(segment in (".", "..") for segment in remote_path.split("/")):
        raise ApiError(400, "invalid_path", "Remote path must not contain . or .. segments")
    if len(remote_path) > 1024:
        raise ApiError(400, "invalid_path", "Remote path is too long")

    user_agent = body.userAgent.strip()[:512]
    if not user_agent:
        user_agent = "DeskCubby-Sync/1"

    selected = [c for c in dict.fromkeys(body.selectedContents) if c in CONTENT_VALUES]
    if not selected:
        raise ApiError(400, "no_contents", "Select at least one content category to sync")

    config: dict[str, Any] = {
        "id": (body.id or "").strip()[:128] or str(uuid.uuid4()),
        "name": name,
        "enabled": bool(body.enabled),
        "serviceType": body.serviceType,
        "endpointUrl": endpoint,
        "remotePath": remote_path or "DeskCubby",
        "userAgent": user_agent,
        "webDavUsername": body.webDavUsername[:8192],
        "s3Bucket": body.s3Bucket.strip(),
        "s3Region": body.s3Region.strip() or "us-east-1",
        "s3PathStyle": bool(body.s3PathStyle),
        "allowInsecureHttp": bool(body.allowInsecureHttp),
        "selectedContents": selected,
        "direction": body.direction,
    }
    if config["serviceType"] == "S3_COMPATIBLE":
        bucket = config["s3Bucket"]
        if not bucket or len(bucket) > 255 or "/" in bucket or "\\" in bucket:
            raise ApiError(400, "invalid_bucket", "S3 Bucket name is invalid")
        if not _S3_REGION_RE.fullmatch(config["s3Region"]):
            raise ApiError(400, "invalid_region", "S3 Region is invalid")
    return config


def redact_config(config: dict[str, Any]) -> dict[str, Any]:
    """Browser-safe projection: secret values become empty strings (§3.7),
    presence is flagged via `hasCredentials` like the Android editor."""
    out = dict(config)
    secrets_present = any(str(out.get(field) or "") for field in SECRET_FIELDS)
    for field in SECRET_FIELDS:
        out[field] = ""
    out.pop("hasCredentials", None)
    out["hasCredentials"] = secrets_present
    return out


def _stored_configs(con) -> list[dict[str, Any]]:
    configs = load_settings(con).get("cloudSyncConfigs")
    return [dict(c) for c in configs if isinstance(c, dict)] if isinstance(configs, list) else []


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@router.get("/status")
def status(con=Depends(get_db)):
    last_result = engine.load_last_result(con)
    undo = engine.load_undo_snapshot(con)
    configs = []
    for config in _stored_configs(con):
        # `hasCredentials` reflects the server-side secret container; the values
        # themselves are blanked by redact_config before leaving the process.
        stored_secrets = engine.read_secrets(str(config.get("id") or ""))
        configs.append(redact_config({**config, **stored_secrets}))
    return {
        "running": engine.sync_lock.locked(),
        "configs": configs,
        "lastResult": last_result,
        "undoAvailable": bool(undo and undo.get("entries")),
        "undoFinishedAtMs": undo.get("finishedAtMs") if undo else None,
    }


@router.post("/configs")
def create_config(body: ConfigBody, con=Depends(get_db)):
    config = _validate_config(body)
    configs = _stored_configs(con)
    if any(c.get("id") == config["id"] for c in configs):
        raise ApiError(409, "duplicate_id", "A configuration with this id already exists")
    if len(configs) >= MAX_CONFIGS:
        raise ApiError(400, "too_many_configs", f"At most {MAX_CONFIGS} sync configurations are allowed")
    configs.append(config)
    update_settings(con, {"cloudSyncConfigs": configs})
    engine.write_secrets(config["id"], {
        "webDavPassword": body.webDavPassword,
        "s3AccessKey": body.s3AccessKey,
        "s3SecretKey": body.s3SecretKey,
        "s3SessionToken": body.s3SessionToken,
    })
    hydrated = {**config,
                "webDavPassword": body.webDavPassword,
                "s3AccessKey": body.s3AccessKey,
                "s3SecretKey": body.s3SecretKey,
                "s3SessionToken": body.s3SessionToken}
    return {"config": redact_config(hydrated)}


@router.put("/configs/{config_id}")
def replace_config(config_id: str, body: ConfigBody, con=Depends(get_db)):
    body.id = config_id
    config = _validate_config(body)
    configs = _stored_configs(con)
    index = next((i for i, c in enumerate(configs) if c.get("id") == config_id), -1)
    if index < 0:
        raise ApiError(404, "config_not_found", "Sync configuration not found")
    configs[index] = config
    update_settings(con, {"cloudSyncConfigs": configs})
    # Blank secret fields keep existing stored credentials (CloudSyncSecretStore.save semantics).
    existing = engine.read_secrets(config_id)
    merged = {
        "webDavPassword": body.webDavPassword or existing.get("webDavPassword", ""),
        "s3AccessKey": body.s3AccessKey or existing.get("s3AccessKey", ""),
        "s3SecretKey": body.s3SecretKey or existing.get("s3SecretKey", ""),
        "s3SessionToken": body.s3SessionToken or existing.get("s3SessionToken", ""),
    }
    engine.write_secrets(config_id, merged)
    return {"config": redact_config(merged | config)}


@router.delete("/configs/{config_id}")
def delete_config(config_id: str, con=Depends(get_db)):
    configs = _stored_configs(con)
    remaining = [c for c in configs if c.get("id") != config_id]
    if len(remaining) == len(configs):
        raise ApiError(404, "config_not_found", "Sync configuration not found")
    update_settings(con, {"cloudSyncConfigs": remaining})
    engine.delete_secrets(config_id)  # purge stored credentials with the config
    return {"ok": True}


@router.post("/sync")
async def sync(body: SyncBody, request: Request, con=Depends(get_db)):
    result = await engine.run_sync(request.app, body.configId, body.mode)
    return {"result": result}


@router.post("/undo")
def undo(request: Request, con=Depends(get_db)):
    # Undoing while a sync round is in flight would restore files the running
    # pass then re-overwrites and resurrect a stale undo snapshot.
    if engine.sync_lock.locked():
        raise ApiError(409, "sync_running", "同步进行中，请稍后再撤回。")
    restored = engine.undo_last_sync(request.app)
    return {"restored": restored}
