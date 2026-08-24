"""Cloud sync engine (web port of android `data/sync/CloudSyncEngine.kt` +
`RemoteManifestStore.kt` + `CloudSyncUndoStore.kt`, scoped to the web layout).

One run reconciles the shared Android/Web remote inventory with the local workspace:

- File inventory: root Markdown diaries/notes, direct media files (including
  `dc-media.json`), and the four selected `.deskcubby` workspace JSON files.
- Database/settings content uses Android's record-level adapters: one bounded
  manifest per category plus immutable payload records. Relationship categories
  are implicit dependencies of thoughts/poems and are never exposed as switches.
- Remote layout mirrors Android's manifest store: payloads are immutable,
  content-addressed blobs (`.deskcubby-object-<keyhash>-<contenthash>`) and a
  small mutable manifest (`.deskcubby-sync-v1.manifest`) is the only mutable
  object. A missing manifest is created on first sync.
- Modes mirror `CloudSyncRunMode`: `now` (three-way by manifest base hash),
  `force_upload` / `force_download` (pick one side, skip the other direction;
  deletions never propagate).
- Conflicts never silently overwrite: the canonical local file is preserved and
  verified remote bytes are written to a deterministic `.remote-conflict-*`
  sibling; structured-workspace conflicts fail closed.
- Before any overwrite/create is applied locally an undo snapshot (overwritten
  originals + created-this-round list) is stored in `cloud_sync_state.undoSnapshotJson`,
  keeping only the latest run; "撤回一次" restores it.
- The aggregate `{uploaded, downloaded, conflicts, finishedAtMs}` result is
  persisted to `cloud_sync_state.lastResultJson` and returned.

Runs are serialized process-wide by a module-level asyncio.Lock.
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import re
import threading
import time
from pathlib import Path
from typing import Any

from ..core.config import DATA_DIR, DIARY_DIR, MEDIA_DIR, NOTES_DIR, PRIVATE_DIR, STRUCTURED_DIR
from ..core.db import write_lock
from ..core.errors import ApiError

MANIFEST_STORAGE_NAME = ".deskcubby-sync-v1.manifest"
MAX_MANIFEST_BYTES = 4 * 1024 * 1024
MAX_OBJECT_BYTES = 64 * 1024 * 1024          # CloudSyncLimits.maxObjectBytes
MAX_OBJECTS = 10_000                          # CloudSyncLimits.maxObjects
MAX_UNDO_ENTRY_BYTES = 8 * 1024 * 1024        # per-entry cap for inline originals
MAX_UNDO_TOTAL_BYTES = 32 * 1024 * 1024
UNDO_KEEP_LATEST_ONLY = True                  # CloudSyncUndoStore keeps one run
MAX_SYNC_KEY_CHARS = 2_048
MANIFEST_HEADER = "DeskCubby-Sync\t1"
MANIFEST_STORAGE_RE = re.compile(r"[.A-Za-z0-9_-]{1,200}")
SHA256_RE = re.compile(r"[0-9a-f]{64}")

FILE_CONTENTS = {
    "DIARIES": "diaries",
    "NOTES": "notes",
    "MEDIA": "media",
}
RECORD_CONTENTS = {
    "THOUGHTS", "THOUGHT_CATEGORIES", "DATE_RECORDS", "POEMS", "POETRY_CATEGORIES",
    "FAVORITES", "RSS_SUBSCRIPTIONS", "GAME_STATES", "GAME_STATISTICS",
    "USAGE_STATISTICS", "READING_PROGRESS", "READER_PREFERENCES", "AGENT_CHATS",
    "VAULT", "GLOBAL_SETTINGS",
}
STRUCTURED_WORKSPACE_FILES = {"fields.json", "records.json", "statistics.json", "settings.json"}

MODES = ("now", "force_upload", "force_download")

sync_lock = asyncio.Lock()
_secret_lock = threading.RLock()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def object_storage_name(key: str, content_hash: str) -> str:
    key_hash = hashlib.sha256(key.encode("utf-8")).hexdigest()[:32]
    return f".deskcubby-object-{key_hash}-{content_hash}"


# ---------------------------------------------------------------------------
# Config access helpers
# ---------------------------------------------------------------------------

def load_configs(con) -> list[dict[str, Any]]:
    from .settings_store import load_settings

    configs = load_settings(con).get("cloudSyncConfigs")
    return [dict(c) for c in configs if isinstance(c, dict)] if isinstance(configs, list) else []


def find_config(con, config_id: str) -> dict[str, Any]:
    for config in load_configs(con):
        if config.get("id") == config_id:
            return config
    raise ApiError(404, "config_not_found", "Sync configuration not found")


def read_secrets(config_id: str) -> dict[str, Any]:
    """Server-private credentials keyed by config id (never returned via API)."""
    path = DATA_DIR / "private" / "cloud-secrets.json"
    with _secret_lock:
        if not path.is_file():
            return {}
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}
    secrets = data.get(config_id) if isinstance(data, dict) else None
    return dict(secrets) if isinstance(secrets, dict) else {}


def write_secrets(config_id: str, secrets: dict[str, Any]) -> None:
    from ..core.fs import safe_write_text

    path = DATA_DIR / "private" / "cloud-secrets.json"
    with _secret_lock:
        try:
            data = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}
        except (OSError, ValueError):
            data = {}
        if not isinstance(data, dict):
            data = {}
        cleaned = {k: v for k, v in secrets.items() if isinstance(v, str) and v}
        if cleaned:
            data[config_id] = cleaned
        else:
            data.pop(config_id, None)
        safe_write_text(path, json.dumps(data, ensure_ascii=False, separators=(",", ":")))
        try:
            path.chmod(0o600)
        except OSError:  # pragma: no cover - best effort on filesystems without perms
            pass


def delete_secrets(config_id: str) -> None:
    write_secrets(config_id, {})


def hydrate_config(con, config_id: str) -> dict[str, Any]:
    """Merge stored non-secret metadata with server-side secrets."""
    config = find_config(con, config_id)
    merged = {**config, **read_secrets(config_id)}
    validate_config_for_sync(merged)
    return merged


def validate_config_for_sync(config: dict[str, Any]) -> None:
    from urllib.parse import urlsplit

    def has_iso_control(value: str) -> bool:
        return any(ord(ch) <= 0x1F or 0x7F <= ord(ch) <= 0x9F for ch in value)

    config_id = config.get("id")
    name = config.get("name")
    if (
        not isinstance(config_id, str) or not config_id.strip() or len(config_id) > 128 or
        has_iso_control(config_id)
    ):
        raise ApiError(400, "invalid_id", "Cloud sync configuration id is invalid")
    if not isinstance(name, str) or len(name) > 200 or has_iso_control(name):
        raise ApiError(400, "invalid_name", "Cloud sync configuration name is invalid")
    if not config.get("enabled"):
        raise ApiError(400, "sync_disabled", "This sync configuration is not enabled")
    selected = config.get("selectedContents")
    if not isinstance(selected, list) or not selected:
        raise ApiError(400, "no_contents", "Select at least one content category to sync")
    user_agent = config.get("userAgent")
    if (
        not isinstance(user_agent, str) or not user_agent.strip() or len(user_agent) > 512 or
        has_iso_control(user_agent)
    ):
        raise ApiError(400, "invalid_user_agent", "Cloud sync User-Agent is invalid")
    endpoint = str(config.get("endpointUrl") or "").strip()
    if len(endpoint) > 4_096:
        raise ApiError(400, "invalid_endpoint", "Cloud service URL is too long")
    try:
        parts = urlsplit(endpoint)
        hostname = parts.hostname
    except ValueError as exc:
        raise ApiError(400, "invalid_endpoint", "Cloud service URL is invalid") from exc
    scheme = (parts.scheme or "").lower()
    allow_http = bool(config.get("allowInsecureHttp"))
    if scheme == "http" and not allow_http:
        raise ApiError(400, "insecure_endpoint",
                       "HTTP sync is disabled by default; explicitly allow HTTP for trusted LAN services")
    if scheme not in ("https",) and not (scheme == "http" and allow_http):
        raise ApiError(400, "invalid_endpoint", "Cloud service URL must use HTTPS")
    if (
        not hostname or has_iso_control(endpoint) or "\\" in endpoint or
        any(ch.isspace() for ch in endpoint) or
        parts.username is not None or parts.password is not None or
        "?" in endpoint or "#" in endpoint
    ):
        raise ApiError(400, "invalid_endpoint", "Cloud service URL must not embed account info")
    remote_value = config.get("remotePath", "DeskCubby")
    if not isinstance(remote_value, str) or (
        len(remote_value) > 1_024 or has_iso_control(remote_value) or "\\" in remote_value
    ):
        raise ApiError(400, "invalid_path", "Remote sync path is invalid")
    remote_segments = remote_value.strip().strip("/").split("/")
    if any(segment in (".", "..") for segment in remote_segments if segment):
        raise ApiError(400, "invalid_path", "Remote sync path contains an invalid segment")
    service_type = config.get("serviceType")
    if service_type not in ("WEBDAV", "S3_COMPATIBLE"):
        raise ApiError(400, "invalid_service", "Unknown cloud service type")
    credentials = [
        str(config.get(key) or "") for key in (
            "webDavUsername", "webDavPassword", "s3AccessKey", "s3SecretKey",
            "s3SessionToken",
        )
    ]
    if any(len(value) > 8_192 for value in credentials):
        raise ApiError(400, "credentials_too_long", "Cloud sync credentials are too long")
    if service_type == "S3_COMPATIBLE":
        bucket = str(config.get("s3Bucket") or "")
        region = str(config.get("s3Region") or "")
        if (
            not bucket or len(bucket) > 255 or "/" in bucket or "\\" in bucket or
            has_iso_control(bucket)
        ):
            raise ApiError(400, "invalid_bucket", "S3 Bucket name is invalid")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", region):
            raise ApiError(400, "invalid_region", "S3 Region is invalid")
        if not bool(config.get("s3PathStyle", True)) and (
            not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]{0,61}[a-z0-9])?", bucket) or
            ":" in hostname
        ):
            raise ApiError(400, "invalid_bucket", "S3 Bucket is not virtual-host compatible")
        if not str(config.get("s3AccessKey") or "") or not str(config.get("s3SecretKey") or ""):
            raise ApiError(400, "missing_credentials", "S3 Access Key and Secret Key are required")


# ---------------------------------------------------------------------------
# Local inventory
# ---------------------------------------------------------------------------

def _local_objects(selected_contents: set[str]) -> dict[str, Path]:
    objects: dict[str, Path] = {}

    def direct_files(base: Path, prefix: str, *, markdown_only: bool = False) -> None:
        if not base.is_dir():
            return
        for path in sorted(base.iterdir(), key=lambda item: item.name.casefold()):
            if path.is_symlink() or not path.is_file() or path.name.startswith("."):
                continue
            if markdown_only and path.suffix.lower() != ".md":
                continue
            objects[f"{prefix}/{path.name}"] = path

    if "DIARIES" in selected_contents:
        direct_files(DIARY_DIR, FILE_CONTENTS["DIARIES"], markdown_only=True)
        for name in sorted(STRUCTURED_WORKSPACE_FILES):
            path = STRUCTURED_DIR / name
            if not path.is_symlink() and path.is_file():
                objects[f"diaries/.deskcubby/{name}"] = path
    if "NOTES" in selected_contents:
        # Android intentionally synchronizes direct root Markdown notes only.
        direct_files(NOTES_DIR, FILE_CONTENTS["NOTES"], markdown_only=True)
    if "MEDIA" in selected_contents:
        direct_files(MEDIA_DIR, FILE_CONTENTS["MEDIA"])

    return objects


def _target_path(key: str) -> Path:
    """Resolve only the three selected Android file-sync namespaces."""
    require_valid_sync_key(key)
    prefix, _, rel = key.partition("/")
    if not rel:
        raise ApiError(502, "manifest_invalid", "Remote sync path is invalid")
    if prefix == "diaries":
        if rel.startswith(".deskcubby/"):
            name = rel.removeprefix(".deskcubby/")
            if name not in STRUCTURED_WORKSPACE_FILES:
                raise ApiError(502, "manifest_invalid", "Remote structured-workspace path is invalid")
            return STRUCTURED_DIR / name
        if "/" in rel or not rel.lower().endswith(".md"):
            raise ApiError(502, "manifest_invalid", "Remote diary path is invalid")
        return DIARY_DIR / rel
    if prefix == "notes":
        if "/" in rel or not rel.lower().endswith(".md"):
            raise ApiError(502, "manifest_invalid", "Remote note path is invalid")
        return NOTES_DIR / rel
    if prefix == "media":
        if "/" in rel or rel.startswith("."):
            raise ApiError(502, "manifest_invalid", "Remote media path is invalid")
        return MEDIA_DIR / rel
    raise ApiError(502, "manifest_invalid", "Remote sync content was not selected")


# ---------------------------------------------------------------------------
# State store (cloud_sync_state singleton row)
# ---------------------------------------------------------------------------

def _state_row(con) -> Any:
    return con.execute("SELECT * FROM cloud_sync_state WHERE id = 1").fetchone()


def load_last_result(con) -> dict[str, Any] | None:
    row = _state_row(con)
    if row is None or not row["lastResultJson"]:
        return None
    try:
        parsed = json.loads(row["lastResultJson"])
        return parsed if isinstance(parsed, dict) else None
    except ValueError:
        return None


def save_last_result(con, result: dict[str, Any]) -> None:
    con.execute(
        "INSERT INTO cloud_sync_state(id, lastResultJson, undoSnapshotJson) VALUES(1, ?, NULL) "
        "ON CONFLICT(id) DO UPDATE SET lastResultJson = excluded.lastResultJson",
        (json.dumps(result, ensure_ascii=False),),
    )
    con.commit()


def load_undo_snapshot(con) -> dict[str, Any] | None:
    row = _state_row(con)
    if row is None or not row["undoSnapshotJson"]:
        return None
    try:
        parsed = json.loads(row["undoSnapshotJson"])
        return parsed if isinstance(parsed, dict) else None
    except ValueError:
        return None


def save_undo_snapshot(con, snapshot: dict[str, Any] | None) -> None:
    value = json.dumps(snapshot, ensure_ascii=False) if snapshot else None
    con.execute(
        "INSERT INTO cloud_sync_state(id, lastResultJson, undoSnapshotJson) VALUES(1, NULL, ?) "
        "ON CONFLICT(id) DO UPDATE SET undoSnapshotJson = excluded.undoSnapshotJson",
        (value,),
    )
    con.commit()


# ---------------------------------------------------------------------------
# Transport abstraction
# ---------------------------------------------------------------------------

class _Transport:
    def __init__(self, config: dict[str, Any]):
        self.config = config
        self._client: Any = None
        self._kind = str(config.get("serviceType") or "WEBDAV")

    def _get_client(self):
        if self._client is None:
            if self._kind == "S3_COMPATIBLE":
                from .s3_client import S3Client

                self._client = S3Client(self.config)
            else:
                from .webdav_client import WebDavClient

                self._client = WebDavClient(self.config)
        return self._client

    def get(self, name: str, max_bytes: int = MAX_OBJECT_BYTES) -> bytes | None:
        blob = self.get_blob(name, max_bytes=max_bytes)
        return blob[0] if blob is not None else None

    def get_blob(
        self,
        name: str,
        max_bytes: int = MAX_OBJECT_BYTES,
        *,
        expected_version: str | None = None,
    ) -> tuple[bytes, str] | None:
        client = self._get_client()
        if self._kind == "S3_COMPATIBLE":
            return client.get_blob(name, max_bytes, expected_version=expected_version)
        return client.get_blob(name, max_bytes, expected_version=expected_version)

    def put(self, name: str, data: bytes) -> None:
        self.put_blob(name, data)

    def put_blob(
        self,
        name: str,
        data: bytes,
        *,
        expected_version: str | None = None,
        must_not_exist: bool = False,
    ) -> str:
        client = self._get_client()
        if self._kind == "S3_COMPATIBLE":
            return client.put_blob(
                name, data, expected_version=expected_version, must_not_exist=must_not_exist,
            )
        return client.put_blob(
            name, data, expected_version=expected_version, must_not_exist=must_not_exist,
        )

    def ensure_collection(self) -> None:
        if self._kind != "WEBDAV":
            return  # S3 buckets need no MKCOL equivalent
        client = self._get_client()
        remote_value = self.config.get("remotePath", "DeskCubby")
        if remote_value is None:
            remote_value = "DeskCubby"
        segments = [segment for segment in str(remote_value).split("/") if segment]
        if segments and not client.exists():
            client.mkcol(segments)


Transport = _Transport


# ---------------------------------------------------------------------------
# Manifest handling
# ---------------------------------------------------------------------------

def require_valid_sync_key(key: str) -> str:
    if (
        not isinstance(key, str) or not key.strip() or len(key) > MAX_SYNC_KEY_CHARS or
        key.startswith("/") or key.endswith("/") or "\\" in key or
        any(ord(ch) < 32 or ord(ch) == 127 for ch in key)
    ):
        raise ApiError(502, "manifest_invalid", "Remote sync manifest contains an invalid path")
    if any(segment in ("", ".", "..") for segment in key.split("/")):
        raise ApiError(502, "manifest_invalid", "Remote sync manifest contains an invalid path")
    return key


def _encode_field(value: str) -> str:
    return base64.urlsafe_b64encode(value.encode("utf-8")).decode("ascii").rstrip("=")


def _decode_field(value: str) -> str:
    if not value or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise ApiError(502, "manifest_invalid", "Remote sync manifest contains invalid encoding")
    try:
        raw = base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)
        decoded = raw.decode("utf-8", errors="strict")
    except (ValueError, UnicodeDecodeError) as exc:
        raise ApiError(502, "manifest_invalid", "Remote sync manifest contains invalid encoding") from exc
    if _encode_field(decoded) != value:
        raise ApiError(502, "manifest_invalid", "Remote sync manifest contains non-canonical encoding")
    return decoded


def encode_manifest(entries: dict[str, dict[str, Any]]) -> bytes:
    lines = [MANIFEST_HEADER]
    for key in sorted(entries):
        entry = entries[key]
        require_valid_sync_key(key)
        digest = str(entry.get("sha256") or "")
        storage_name = str(entry.get("storageName") or "")
        blob_version = str(entry.get("blobVersion") or "")
        size = int(entry.get("size", -1))
        modified = int(entry.get("lastModified", -1))
        if (
            not SHA256_RE.fullmatch(digest) or size < 0 or modified < 0 or
            not MANIFEST_STORAGE_RE.fullmatch(storage_name) or not blob_version or
            len(blob_version) > 4_096
        ):
            raise ApiError(500, "manifest_invalid", "Cannot encode invalid sync manifest entry")
        lines.append("\t".join((
            _encode_field(key), digest, str(size), str(modified), storage_name,
            _encode_field(blob_version),
        )))
    return ("\n".join(lines) + "\n").encode("utf-8")


def decode_manifest(data: bytes) -> dict[str, dict[str, Any]]:
    try:
        text_value = data.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise ApiError(502, "manifest_invalid", "Remote sync manifest is not valid UTF-8") from exc
    lines = text_value.splitlines()
    if not lines or lines[0] != MANIFEST_HEADER:
        if data.lstrip().startswith(b"{"):
            raise ApiError(
                409,
                "legacy_sync_manifest",
                "The remote folder contains the obsolete Web-only sync format; it was left untouched",
            )
        raise ApiError(502, "manifest_invalid", "Remote sync manifest format is unsupported")
    entry_lines = [line for line in lines[1:] if line]
    if len(entry_lines) > MAX_OBJECTS:
        raise ApiError(413, "too_many_objects", "Remote sync manifest exceeds the object limit")
    clean: dict[str, dict[str, Any]] = {}
    for line in entry_lines:
        fields = line.split("\t")
        if len(fields) != 6:
            raise ApiError(502, "manifest_invalid", "Remote sync manifest entry is malformed")
        key = require_valid_sync_key(_decode_field(fields[0]))
        digest = fields[1]
        storage_name = fields[4]
        blob_version = _decode_field(fields[5])
        try:
            size = int(fields[2])
            modified = int(fields[3])
        except ValueError as exc:
            raise ApiError(502, "manifest_invalid", "Remote sync manifest entry is malformed") from exc
        if (
            key in clean or not SHA256_RE.fullmatch(digest) or size < 0 or modified < 0 or
            not MANIFEST_STORAGE_RE.fullmatch(storage_name) or not blob_version or
            len(blob_version) > 4_096
        ):
            raise ApiError(502, "manifest_invalid", "Remote sync manifest contains an invalid entry")
        clean[key] = {
            "sha256": digest,
            "size": size,
            "lastModified": modified,
            "storageName": storage_name,
            "blobVersion": blob_version,
        }
    return clean


# ---------------------------------------------------------------------------
# Undo snapshot
# ---------------------------------------------------------------------------

class _UndoRecorder:
    def __init__(self, con):
        self.con = con
        self.entries: list[dict[str, Any]] = []
        self.total_bytes = 0

    def capture_overwrite(self, key: str, path: Path, original: bytes) -> None:
        if not original or len(original) > MAX_UNDO_ENTRY_BYTES:
            return
        if self.total_bytes + len(original) > MAX_UNDO_TOTAL_BYTES:
            return
        self.total_bytes += len(original)
        self.entries.append({
            "key": key,
            "path": path.relative_to(DATA_DIR).as_posix(),
            "action": "overwrite",
            "contentBase64": base64.b64encode(original).decode("ascii"),
            "sha256": sha256_bytes(original),
        })

    def capture_create(self, key: str, path: Path) -> None:
        self.entries.append({
            "key": key,
            "path": path.relative_to(DATA_DIR).as_posix(),
            "action": "create",
        })

    def commit(self, config_id: str, finished_at_ms: int) -> None:
        # Latest run wins; a previous snapshot is discarded like CloudSyncUndoStore.beginRun.
        snapshot = {
            "configId": config_id,
            "finishedAtMs": finished_at_ms,
            "entries": self.entries,
        }
        with write_lock():
            save_undo_snapshot(self.con, snapshot)


# ---------------------------------------------------------------------------
# File-sync ancestry (the remote manifest is current state, not a three-way base)
# ---------------------------------------------------------------------------

def _scope_fingerprint(config: dict[str, Any]) -> str:
    service_type = str(config.get("serviceType") or "")
    remote_value = config.get("remotePath", "DeskCubby")
    if remote_value is None:
        remote_value = "DeskCubby"
    remote_path = "/".join(
        segment for segment in str(remote_value).strip().strip("/").split("/") if segment
    )
    fields = [
        service_type,
        str(config.get("endpointUrl") or "").strip(),
        remote_path,
    ]
    if service_type == "WEBDAV":
        fields.append(str(config.get("webDavUsername") or ""))
    else:
        fields.extend((
            str(config.get("s3Bucket") or ""),
            str(config.get("s3Region") or ""),
            "true" if bool(config.get("s3PathStyle", True)) else "false",
            str(config.get("s3AccessKey") or ""),
        ))
    raw = "\n".join(fields)
    return sha256_bytes(raw.encode("utf-8"))


def _file_state_path(config_id: str) -> Path:
    suffix = sha256_bytes((config_id + "\0files").encode("utf-8"))[:32]
    return PRIVATE_DIR / "cloud-sync-file-state" / f"{suffix}.json"


def _load_file_state(config: dict[str, Any]) -> dict[str, str]:
    path = _file_state_path(str(config.get("id") or ""))
    if not path.is_file() or path.stat().st_size > 8 * 1024 * 1024:
        return {}
    try:
        root = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    if not isinstance(root, dict) or root.get("format") != "DeskCubby-File-Sync-State-1":
        return {}
    if root.get("scopeFingerprint") != _scope_fingerprint(config):
        return {}
    raw_hashes = root.get("hashesByKey")
    if not isinstance(raw_hashes, dict) or len(raw_hashes) > MAX_OBJECTS:
        return {}
    hashes: dict[str, str] = {}
    for key, digest in raw_hashes.items():
        try:
            require_valid_sync_key(key)
        except ApiError:
            return {}
        if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
            return {}
        hashes[key] = digest
    return hashes


def _save_file_state(config: dict[str, Any], hashes: dict[str, str]) -> None:
    if len(hashes) > MAX_OBJECTS:
        raise ApiError(413, "too_many_objects", "Local sync state exceeds the object limit")
    payload = json.dumps({
        "format": "DeskCubby-File-Sync-State-1",
        "scopeFingerprint": _scope_fingerprint(config),
        "hashesByKey": dict(sorted(hashes.items())),
    }, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(payload) > 8 * 1024 * 1024:
        raise ApiError(413, "sync_state_too_large", "Local sync state exceeds the size limit")
    path = _file_state_path(str(config.get("id") or ""))
    path.parent.mkdir(parents=True, exist_ok=True)
    safe_write(path, payload)


# ---------------------------------------------------------------------------
# Record-sync bridge (per-record manifests share the outer object inventory)
# ---------------------------------------------------------------------------

def _sync_records(
    *, con, config: dict[str, Any], selected_contents: set[str], mode: str,
    remote_entries: dict[str, dict[str, Any]], new_remote: dict[str, dict[str, Any]],
    transport: _Transport, reserve_transfer,
) -> dict[str, Any]:
    from . import record_sync

    def remote_read(key: str, max_bytes: int) -> tuple[bytes, dict[str, Any]] | None:
        require_valid_sync_key(key)
        entry = new_remote.get(key)
        if entry is None:
            return None
        size = int(entry.get("size", -1))
        if size < 0 or size > max_bytes:
            raise ApiError(413, "record_too_large", "A remote record exceeds the sync limit")
        blob = transport.get_blob(
            str(entry.get("storageName") or ""),
            max_bytes=max_bytes,
            expected_version=str(entry.get("blobVersion") or ""),
        )
        if blob is None:
            raise ApiError(502, "record_payload_missing", "Remote record content is missing")
        data, version = blob
        reserve_transfer(len(data))
        if (
            version != entry.get("blobVersion") or len(data) != size or
            sha256_bytes(data) != entry.get("sha256")
        ):
            raise ApiError(502, "record_payload_invalid", "Remote record content failed verification")
        return data, entry

    def remote_write(key: str, data: bytes) -> dict[str, Any]:
        require_valid_sync_key(key)
        if not data or len(data) > record_sync.MAX_AGENT_PAYLOAD_BYTES:
            raise ApiError(413, "record_too_large", "A record exceeds the sync limit")
        digest = sha256_bytes(data)
        current = new_remote.get(key)
        if current is not None and current.get("sha256") == digest and int(current.get("size", -1)) == len(data):
            return current
        storage_name = object_storage_name(key, digest)
        stored = transport.get_blob(storage_name, max_bytes=len(data))
        if stored is None:
            try:
                blob_version = transport.put_blob(storage_name, data, must_not_exist=True)
                reserve_transfer(len(data))
            except ApiError as error:
                if error.status != 409:
                    raise
                stored = transport.get_blob(storage_name, max_bytes=len(data))
                if stored is None:
                    raise
                stored_data, blob_version = stored
                reserve_transfer(len(stored_data))
                if stored_data != data:
                    raise ApiError(409, "remote_conflict", "An immutable record blob has different content")
        else:
            stored_data, blob_version = stored
            reserve_transfer(len(stored_data))
            if len(stored_data) != len(data) or sha256_bytes(stored_data) != digest:
                raise ApiError(409, "remote_conflict", "An immutable record blob failed verification")
        entry = {
            "sha256": digest,
            "size": len(data),
            "lastModified": int(time.time() * 1000),
            "storageName": storage_name,
            "blobVersion": blob_version,
        }
        new_remote[key] = entry
        return entry

    return record_sync.sync_records(
        con=con,
        config=config,
        contents=selected_contents,
        mode=mode,
        scope_fingerprint=_scope_fingerprint(config),
        remote_read=remote_read,
        remote_write=remote_write,
    )


# ---------------------------------------------------------------------------
# Engine
# ---------------------------------------------------------------------------

async def run_sync(app, config_id: str, mode: str = "now") -> dict[str, Any]:
    """Run one serialized sync pass; returns the aggregate result document."""
    if mode not in MODES:
        raise ApiError(400, "invalid_mode", "mode must be now, force_upload or force_download")
    if sync_lock.locked():
        raise ApiError(409, "sync_running", "Another sync is already running")
    async with sync_lock:
        con = getattr(app.state, "db", None)
        if con is None:
            raise ApiError(500, "not_ready", "Application is not ready")
        config = hydrate_config(con, config_id)
        return await asyncio.to_thread(_run_sync_blocking, con, config, mode)


def _run_sync_blocking(con, config: dict[str, Any], mode: str) -> dict[str, Any]:
    # A local folder switch and a file-sync pass must observe one complete root
    # generation; otherwise one manifest could mix objects from two folders.
    from .storage_roots import root_io_lock

    with root_io_lock:
        return _run_sync_blocking_with_stable_roots(con, config, mode)


def _run_sync_blocking_with_stable_roots(con, config: dict[str, Any], mode: str) -> dict[str, Any]:
    started_at_ms = int(time.time() * 1000)
    selected_contents = {
        str(value) for value in (config.get("selectedContents") or []) if isinstance(value, str)
    }
    # Current Android exposes only the parent switches.  Dependencies remain
    # internal so a thought/poem can never arrive without its category row.
    if "THOUGHT_CATEGORIES" in selected_contents:
        selected_contents.add("THOUGHTS")
    if "POETRY_CATEGORIES" in selected_contents:
        selected_contents.add("POEMS")
    if "THOUGHTS" in selected_contents:
        selected_contents.add("THOUGHT_CATEGORIES")
    if "POEMS" in selected_contents:
        selected_contents.add("POETRY_CATEGORIES")
    selected_file_contents = selected_contents.intersection(FILE_CONTENTS)
    selected_record_contents = selected_contents.intersection(RECORD_CONTENTS)
    if not selected_file_contents and not selected_record_contents:
        raise ApiError(400, "no_contents", "Select at least one supported content category")

    # --- build only the explicitly selected local file inventory -----------
    local_files = _local_objects(selected_file_contents)
    local_entries: dict[str, dict[str, Any]] = {}
    for key, path in local_files.items():
        try:
            data = path.read_bytes()
            stat = path.stat()
        except OSError as exc:
            raise ApiError(409, "local_changed", "A local sync file changed during inventory") from exc
        if len(data) > MAX_OBJECT_BYTES:
            raise ApiError(413, "object_too_large", "A selected local file exceeds the sync limit")
        local_entries[key] = {
            "sha256": sha256_bytes(data),
            "size": len(data),
            "lastModified": int(stat.st_mtime * 1000),
        }
    if len(local_entries) > MAX_OBJECTS:
        raise ApiError(413, "too_many_objects", "Too many selected local files")

    # --- fetch the Android-compatible shared remote manifest ---------------
    transport = _Transport(config)
    transport.ensure_collection()
    manifest_blob = transport.get_blob(MANIFEST_STORAGE_NAME, MAX_MANIFEST_BYTES)
    if manifest_blob is None:
        remote_entries: dict[str, dict[str, Any]] = {}
        manifest_version: str | None = None
    else:
        raw_manifest, manifest_version = manifest_blob
        remote_entries = decode_manifest(raw_manifest)

    new_remote = dict(remote_entries)
    uploaded = downloaded = conflicts = 0
    undo = _UndoRecorder(con)
    transferred_bytes = 0
    pending_record_states: list[tuple[str, str | None, dict[str, dict[str, Any]]]] = []

    def reserve_transfer(byte_count: int) -> None:
        nonlocal transferred_bytes
        if byte_count < 0 or transferred_bytes + byte_count > 512 * 1024 * 1024:
            raise ApiError(413, "transfer_limit", "This sync run exceeded its transfer limit")
        transferred_bytes += byte_count

    def read_local(key: str, entry: dict[str, Any]) -> bytes:
        try:
            data = local_files[key].read_bytes()
        except OSError as exc:
            raise ApiError(409, "local_changed", "A local sync file changed during sync") from exc
        if len(data) != entry["size"] or sha256_bytes(data) != entry["sha256"]:
            raise ApiError(409, "local_changed", "A local sync file changed during sync")
        return data

    def upload(key: str, data: bytes, entry: dict[str, Any]) -> dict[str, Any]:
        storage_name = object_storage_name(key, entry["sha256"])
        existing_entry = remote_entries.get(key)
        if (
            existing_entry is not None and existing_entry.get("sha256") == entry["sha256"] and
            existing_entry.get("storageName") == storage_name
        ):
            return dict(existing_entry)
        existing = transport.get_blob(storage_name, max_bytes=MAX_OBJECT_BYTES)
        if existing is None:
            try:
                blob_version = transport.put_blob(storage_name, data, must_not_exist=True)
                reserve_transfer(len(data))
            except ApiError as error:
                if error.status != 409:
                    raise
                existing = transport.get_blob(storage_name, max_bytes=MAX_OBJECT_BYTES)
                if existing is None:
                    raise
                existing_data, blob_version = existing
                reserve_transfer(len(existing_data))
                if existing_data != data:
                    raise ApiError(409, "remote_conflict", "An immutable remote blob already has different content")
        else:
            existing_data, blob_version = existing
            reserve_transfer(len(existing_data))
            if len(existing_data) != len(data) or sha256_bytes(existing_data) != entry["sha256"]:
                raise ApiError(409, "remote_conflict", "An immutable remote blob failed verification")
        return {
            **entry,
            "storageName": storage_name,
            "blobVersion": blob_version,
        }

    def download(key: str, entry: dict[str, Any]) -> bytes:
        storage_name = str(entry.get("storageName") or "")
        blob = transport.get_blob(
            storage_name,
            max_bytes=MAX_OBJECT_BYTES,
            expected_version=str(entry.get("blobVersion") or ""),
        )
        if blob is None:
            raise ApiError(502, "blob_invalid",
                           "Remote content failed verification; sync again")
        data, actual_version = blob
        reserve_transfer(len(data))
        if (
            actual_version != entry.get("blobVersion") or len(data) != entry.get("size") or
            sha256_bytes(data) != entry["sha256"]
        ):
            raise ApiError(502, "blob_invalid", "Remote content failed verification; sync again")
        return data

    prefixes = {f"{FILE_CONTENTS[content]}/" for content in selected_file_contents}
    remote_file_entries = {
        key: entry for key, entry in remote_entries.items()
        if any(key.startswith(prefix) for prefix in prefixes)
    }
    for key, entry in remote_file_entries.items():
        _target_path(key)
        if int(entry.get("size", -1)) > MAX_OBJECT_BYTES:
            raise ApiError(413, "object_too_large", "A selected remote file exceeds the sync limit")

    all_keys = sorted(set(local_entries) | set(remote_file_entries))
    if len(all_keys) > MAX_OBJECTS:
        raise ApiError(413, "too_many_objects", "Too many objects for one sync run")
    old_hashes = _load_file_state(config)
    updated_hashes = dict(old_hashes)
    for key in list(updated_hashes):
        if any(key.startswith(prefix) for prefix in prefixes) and key not in all_keys:
            updated_hashes.pop(key, None)

    for key in all_keys:
        local_entry = local_entries.get(key)
        remote_entry = remote_file_entries.get(key)
        base_hash = old_hashes.get(key)

        if local_entry and remote_entry and local_entry["sha256"] == remote_entry["sha256"]:
            updated_hashes[key] = local_entry["sha256"]
            continue  # unchanged

        if remote_entry is None:
            if mode == "force_download":
                continue
            assert local_entry is not None
            new_remote[key] = upload(key, read_local(key, local_entry), local_entry)
            updated_hashes[key] = local_entry["sha256"]
            uploaded += 1
            continue

        if local_entry is None:
            if mode == "force_upload" or (mode == "now" and config.get("direction") == "UPLOAD_ONLY"):
                continue
            data = download(key, remote_entry)
            outcome = apply_remote(
                key, data, remote_entry, _target_path(key), undo,
                expected_local_sha256=None, conflict_copy=False,
            )
            if outcome == "conflict-copy":
                conflicts += 1
            else:
                downloaded += 1
                updated_hashes[key] = remote_entry["sha256"]
            continue

        if mode == "force_upload":
            new_remote[key] = upload(key, read_local(key, local_entry), local_entry)
            updated_hashes[key] = local_entry["sha256"]
            uploaded += 1
            continue

        if mode == "force_download":
            outcome = apply_remote(
                key, download(key, remote_entry), remote_entry, _target_path(key), undo,
                expected_local_sha256=local_entry["sha256"], conflict_copy=False,
            )
            if outcome == "applied":
                downloaded += 1
                updated_hashes[key] = remote_entry["sha256"]
            else:
                conflicts += 1
            continue

        if base_hash is None:
            if config.get("direction") == "UPLOAD_ONLY":
                continue
            outcome = apply_remote(
                key, download(key, remote_entry), remote_entry, _target_path(key), undo,
                expected_local_sha256=None, conflict_copy=True,
            )
            if outcome == "conflict-copy":
                conflicts += 1
                updated_hashes[key] = remote_entry["sha256"]
            else:
                downloaded += 1
                updated_hashes[key] = remote_entry["sha256"]
            continue

        local_changed = local_entry["sha256"] != base_hash
        remote_changed = remote_entry["sha256"] != base_hash
        if local_changed and not remote_changed:
            new_remote[key] = upload(key, read_local(key, local_entry), local_entry)
            updated_hashes[key] = local_entry["sha256"]
            uploaded += 1
            continue
        if not local_changed and remote_changed:
            if config.get("direction") == "UPLOAD_ONLY":
                continue
            outcome = apply_remote(
                key, download(key, remote_entry), remote_entry, _target_path(key), undo,
                expected_local_sha256=local_entry["sha256"], conflict_copy=False,
            )
            if outcome == "applied":
                downloaded += 1
                updated_hashes[key] = remote_entry["sha256"]
            else:
                conflicts += 1
            continue

        if config.get("direction") == "UPLOAD_ONLY":
            continue
        outcome = apply_remote(
            key, download(key, remote_entry), remote_entry, _target_path(key), undo,
            expected_local_sha256=None, conflict_copy=True,
        )
        conflicts += 1 if outcome == "conflict-copy" else 0
        downloaded += 1 if outcome == "applied" else 0
        updated_hashes[key] = remote_entry["sha256"]

    # Record adapters operate on the same immutable-blob inventory and add their
    # per-content manifests/payloads to new_remote before the single publication.
    if selected_record_contents:
        record_result = _sync_records(
            con=con,
            config=config,
            selected_contents=selected_record_contents,
            mode=mode,
            remote_entries=remote_entries,
            new_remote=new_remote,
            transport=transport,
            reserve_transfer=reserve_transfer,
        )
        uploaded += record_result["uploaded"]
        downloaded += record_result["downloaded"]
        conflicts += record_result["conflicts"]
        pending_record_states = record_result["pendingStates"]

    # --- publish once, conditionally, so a concurrent client cannot be lost --
    if new_remote != remote_entries:
        manifest_bytes = encode_manifest(new_remote)
        if len(manifest_bytes) > MAX_MANIFEST_BYTES:
            raise ApiError(413, "manifest_too_large", "Remote sync manifest exceeds the size limit")
        transport.put_blob(
            MANIFEST_STORAGE_NAME,
            manifest_bytes,
            expected_version=manifest_version,
            must_not_exist=manifest_version is None,
        )
        reserve_transfer(len(manifest_bytes))

    if pending_record_states:
        from .record_sync import commit_states

        commit_states(
            str(config.get("id") or ""),
            _scope_fingerprint(config),
            pending_record_states,
        )
    _save_file_state(config, updated_hashes)
    if any(key.startswith("diaries/") for key in all_keys) and (downloaded or conflicts):
        from .diary_files import scan_documents

        scan_documents(con)

    finished_at_ms = int(time.time() * 1000)
    result = {
        "configId": config.get("id"),
        "startedAtMs": started_at_ms,
        "uploaded": uploaded,
        "downloaded": downloaded,
        "conflicts": conflicts,
        "transferredBytes": transferred_bytes,
        "finishedAtMs": finished_at_ms,
    }
    undo.commit(str(config.get("id")), finished_at_ms)
    with write_lock():
        save_last_result(con, result)
    return result


def apply_remote(
    key: str,
    data: bytes | None,
    entry: dict[str, Any],
    path: Path,
    undo: "_UndoRecorder",
    *,
    expected_local_sha256: str | None = None,
    conflict_copy: bool,
) -> str:
    """Apply verified remote bytes without silently overwriting local changes.

    When ancestry says both sides changed (or the file changed after inventory),
    the canonical local file stays untouched and the *remote* bytes are written
    to a deterministic sibling. The previous implementation did the reverse:
    it moved the user's local edit aside and replaced the canonical file.
    """
    if data is None:
        return "skipped"
    if len(data) != int(entry.get("size", len(data))) or sha256_bytes(data) != entry["sha256"]:
        raise ApiError(502, "blob_invalid", "Remote content failed verification")
    existed = path.exists()
    if existed:
        try:
            current = path.read_bytes()
        except OSError as exc:
            raise ApiError(409, "local_changed", "A local sync file changed during sync") from exc
        if sha256_bytes(current) == entry["sha256"]:
            return "unchanged"
        current_hash = sha256_bytes(current)
        local_changed = expected_local_sha256 is None or current_hash != expected_local_sha256
        if conflict_copy or local_changed:
            if key.startswith("diaries/.deskcubby/"):
                raise ApiError(
                    409,
                    "structured_sync_conflict",
                    "Structured workspace content differs on both sides; no local file was overwritten",
                )
            copy_path = _remote_conflict_path(path, entry["sha256"])
            if copy_path.exists():
                try:
                    if sha256_bytes(copy_path.read_bytes()) == entry["sha256"]:
                        return "conflict-copy"
                except OSError:
                    pass
                copy_path = _unique_conflict_path(copy_path)
            copy_path.parent.mkdir(parents=True, exist_ok=True)
            safe_write(copy_path, data)
            undo.capture_create(key, copy_path)
            return "conflict-copy"
        undo.capture_overwrite(key, path, current)
    else:
        undo.capture_create(key, path)
    path.parent.mkdir(parents=True, exist_ok=True)
    safe_write(path, data)
    try:
        if sha256_bytes(path.read_bytes()) != entry["sha256"]:
            raise ApiError(500, "write_verify_failed", "Downloaded file failed read-back verification")
    except OSError as exc:
        raise ApiError(500, "write_verify_failed", "Downloaded file failed read-back verification") from exc
    return "applied"


def _remote_conflict_path(path: Path, digest: str) -> Path:
    if path.suffix:
        return path.with_name(f"{path.stem}.remote-conflict-{digest[:8]}{path.suffix}")
    return path.with_name(f"{path.name}.remote-conflict-{digest[:8]}")


def _unique_conflict_path(preferred: Path) -> Path:
    sequence = 1
    candidate = preferred
    while candidate.exists():
        if preferred.suffix:
            candidate = preferred.with_name(f"{preferred.stem}-{sequence}{preferred.suffix}")
        else:
            candidate = preferred.with_name(f"{preferred.name}-{sequence}")
        sequence += 1
    return candidate


def safe_write(path: Path, data: bytes) -> None:
    from ..core.fs import safe_write as fs_safe_write

    fs_safe_write(path, data)


# ---------------------------------------------------------------------------
# Undo ("撤回一次")
# ---------------------------------------------------------------------------

def undo_last_sync(app) -> int:
    """Restore overwritten files and delete created ones from the latest run."""
    con = getattr(app.state, "db", None)
    if con is None:
        raise ApiError(500, "not_ready", "Application is not ready")
    snapshot = load_undo_snapshot(con)
    if not snapshot:
        return 0
    restored = 0
    for entry in snapshot.get("entries", []):
        rel = str(entry.get("path") or "")
        action = entry.get("action")
        try:
            from ..core.fs import sanitize_rel_path

            path = sanitize_rel_path(rel, DATA_DIR)
        except ApiError:
            continue
        try:
            if action == "overwrite":
                payload = base64.b64decode(str(entry.get("contentBase64") or ""), validate=True)
                expected = entry.get("sha256")
                if payload and (not expected or sha256_bytes(payload) == expected):
                    path.parent.mkdir(parents=True, exist_ok=True)
                    safe_write(path, payload)
                    restored += 1
            elif action == "create":
                if path.is_file():
                    path.unlink()
                    restored += 1
                    _prune_empty_dirs(path.parent, DATA_DIR / "workspace")
        except (OSError, ValueError):  # noqa: BLE001 - one bad entry must not stop undo
            continue
    with write_lock():
        save_undo_snapshot(con, None)
    return restored


def _prune_empty_dirs(directory: Path, stop: Path) -> None:
    current = directory
    while current.is_dir() and current != stop and stop in current.parents:
        try:
            next(current.iterdir())
            return
        except StopIteration:
            try:
                current.rmdir()
            except OSError:
                return
            current = current.parent
        except OSError:
            return
